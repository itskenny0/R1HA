package com.github.itskenny0.r1ha.feature.moreinfo

import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.ui.components.ChartSample
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Locale

/**
 * Pure decision + rendering logic for the more-info history / logbook / details
 * embeds, mirroring HA's `ha-more-info-history.ts`,
 * `ha-more-info-history-and-logbook.ts`, and `ha-more-info-details.ts`. Compose
 * stays thin and reads its data from these results so the "stats vs raw history",
 * "is this entity a timeline", and YAML-rendering choices can be unit-tested on
 * the JVM.
 */
object MoreInfoEmbeds {

    /**
     * How the history section should render an entity, mirroring HA's split
     * between the statistics-backed aggregate chart (sensors with a long-term
     * `state_class`), the line chart (numeric state), and the timeline chart
     * (categorical / on-off state).
     */
    enum class HistoryMode {
        /** Statistics-backed mean/min/max aggregate (HA `hasStatistics`). */
        STATISTICS,

        /** Raw numeric line chart (sensor with no recorded statistics). */
        LINE,

        /** Categorical state-band timeline (binary_sensor, switch, lock, ...). */
        TIMELINE,

        /** Nothing meaningful to chart. */
        NONE,
    }

    /**
     * Choose the history rendering mode. [numericNow] is whether the entity's
     * current state parses as a number; [hasStatistics] is whether HA's recorder
     * is collecting long-term statistics for it (a `state_class` was set);
     * [supportsTimeline] is whether the domain has meaningful categorical
     * transitions worth a band timeline.
     *
     * HA prefers statistics when available (they survive recorder purges and
     * aggregate cleanly), falls back to the raw line for live numeric entities,
     * and routes everything else to the timeline.
     */
    fun chooseHistoryMode(
        numericNow: Boolean,
        hasStatistics: Boolean,
        supportsTimeline: Boolean,
    ): HistoryMode = when {
        numericNow && hasStatistics -> HistoryMode.STATISTICS
        numericNow -> HistoryMode.LINE
        supportsTimeline -> HistoryMode.TIMELINE
        else -> HistoryMode.NONE
    }

    /**
     * Map statistics buckets to the mean / min / max sample series that drive the
     * aggregate sparkline. Buckets missing a given aggregate are skipped for that
     * series (HA leaves the band edge undrawn rather than zero-filling). The mean
     * series falls back to a bucket's `state` then `change` when no mean was
     * recorded, so a total-only statistic (energy meter) still draws a line.
     *
     * Returns a triple of (mean, min, max) sample lists, each chronological.
     */
    fun statisticsSeries(
        buckets: List<StatisticsBucket>,
    ): StatisticsSeries {
        val mean = ArrayList<ChartSample>(buckets.size)
        val min = ArrayList<ChartSample>(buckets.size)
        val max = ArrayList<ChartSample>(buckets.size)
        for (b in buckets) {
            val t = b.start.toEpochMilli()
            val meanV = b.mean ?: b.state ?: b.change
            if (meanV != null) mean.add(ChartSample(t, meanV))
            b.min?.let { min.add(ChartSample(t, it)) }
            b.max?.let { max.add(ChartSample(t, it)) }
        }
        return StatisticsSeries(mean = mean, min = min, max = max)
    }

    /** The pre-scaled aggregate series for the statistics chart. */
    data class StatisticsSeries(
        val mean: List<ChartSample>,
        val min: List<ChartSample>,
        val max: List<ChartSample>,
    ) {
        val isEmpty: Boolean get() = mean.isEmpty() && min.isEmpty() && max.isEmpty()
    }

    /**
     * Render an entity's state + attributes as a compact YAML-ish read-only block,
     * mirroring the YAML view of HA's `ha-more-info-details.ts`. Deterministic:
     * keys are emitted in insertion order for attributes (HA keeps registry
     * order), with `state:` always first.
     *
     * Scalars render inline; lists and nested objects render as indented YAML
     * children. Strings that need quoting (empty, leading/trailing space, or a
     * leading character YAML would misread) are quoted; everything else is bare,
     * matching how a human-authored HA YAML dump reads.
     */
    fun renderStateYaml(rawState: String?, attributes: JsonObject?): String {
        val sb = StringBuilder()
        sb.append("state: ").append(scalarYaml(rawState ?: "")).append('\n')
        if (attributes != null && attributes.isNotEmpty()) {
            for ((key, value) in attributes) {
                appendYaml(sb, key, value, indent = 0)
            }
        }
        return sb.toString().trimEnd('\n')
    }

    private fun appendYaml(sb: StringBuilder, key: String, value: JsonElement, indent: Int) {
        val pad = "  ".repeat(indent)
        when (value) {
            is JsonNull -> sb.append(pad).append(key).append(": null\n")
            is JsonPrimitive -> sb.append(pad).append(key).append(": ")
                .append(scalarYaml(value.content)).append('\n')
            is JsonArray -> {
                if (value.isEmpty()) {
                    sb.append(pad).append(key).append(": []\n")
                } else {
                    sb.append(pad).append(key).append(":\n")
                    for (el in value) {
                        when (el) {
                            is JsonObject -> {
                                // List of maps: emit "- " then the map's first key
                                // inline, remaining keys indented under it.
                                sb.append(pad).append("  - ")
                                if (el.isEmpty()) {
                                    sb.append("{}\n")
                                } else {
                                    var first = true
                                    for ((k, v) in el) {
                                        if (first) {
                                            appendInlineFirst(sb, k, v, indent + 2)
                                            first = false
                                        } else {
                                            appendYaml(sb, k, v, indent + 2)
                                        }
                                    }
                                }
                            }
                            is JsonArray -> {
                                sb.append(pad).append("  -\n")
                                el.forEachIndexed { _, inner ->
                                    appendYaml(sb, "-", inner, indent + 2)
                                }
                            }
                            is JsonNull -> sb.append(pad).append("  - null\n")
                            is JsonPrimitive -> sb.append(pad).append("  - ")
                                .append(scalarYaml(el.content)).append('\n')
                            else -> sb.append(pad).append("  - ").append(el.toString()).append('\n')
                        }
                    }
                }
            }
            is JsonObject -> {
                if (value.isEmpty()) {
                    sb.append(pad).append(key).append(": {}\n")
                } else {
                    sb.append(pad).append(key).append(":\n")
                    for ((k, v) in value) appendYaml(sb, k, v, indent + 1)
                }
            }
            else -> sb.append(pad).append(key).append(": ").append(value.toString()).append('\n')
        }
    }

    /** Emit the first key of a list-item map on the same physical line as the
     *  "- " marker that was already written. */
    private fun appendInlineFirst(sb: StringBuilder, key: String, value: JsonElement, indent: Int) {
        when (value) {
            is JsonPrimitive -> sb.append(key).append(": ").append(scalarYaml(value.content)).append('\n')
            is JsonNull -> sb.append(key).append(": null\n")
            else -> {
                // Complex first value: break to its own block under the key.
                sb.append(key).append(":\n")
                appendYaml(sb, "", value, indent + 1)
            }
        }
    }

    /**
     * Quote a scalar only when leaving it bare would be ambiguous YAML: empty
     * string, surrounding whitespace, or a value that would otherwise be read as
     * a different type (a bare `null`, a leading indicator char). Numbers and
     * plain words stay unquoted so the dump reads naturally.
     */
    private fun scalarYaml(raw: String): String {
        if (raw.isEmpty()) return "\"\""
        if (raw != raw.trim()) return quote(raw)
        val lower = raw.lowercase(Locale.US)
        if (lower == "null" || lower == "true" || lower == "false" || lower == "~") return quote(raw)
        val firstChar = raw.first()
        if (firstChar in YAML_INDICATOR_CHARS) return quote(raw)
        if (raw.contains(": ") || raw.contains(" #") || raw.endsWith(":")) return quote(raw)
        return raw
    }

    private fun quote(raw: String): String =
        "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private val YAML_INDICATOR_CHARS = setOf(
        '!', '&', '*', '?', '|', '>', '%', '@', '`', '"', '\'', '#', ',', '[', ']', '{', '}',
    )
}

/** Parse a statistics-period `JsonElement` payload (the per-id list HA returns)
 *  into a chronological list of [StatisticsBucket]. Tolerant: skips rows missing
 *  the required `start`, returns empty on any structural surprise. Pure so the
 *  decode contract is testable without a live recorder. */
internal fun parseStatisticsBuckets(payload: JsonArray?): List<StatisticsBucket> {
    if (payload == null) return emptyList()
    return payload.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        fun dbl(k: String): Double? = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
        fun longMs(k: String): Long? {
            val p = (o[k] as? JsonPrimitive)?.content ?: return null
            // HA sends `start`/`end` as epoch-millis numbers in the WS reply.
            return p.toLongOrNull() ?: p.toDoubleOrNull()?.toLong()
        }
        val startMs = longMs("start") ?: return@mapNotNull null
        val endMs = longMs("end") ?: startMs
        StatisticsBucket(
            start = java.time.Instant.ofEpochMilli(startMs),
            end = java.time.Instant.ofEpochMilli(endMs),
            mean = dbl("mean"),
            min = dbl("min"),
            max = dbl("max"),
            sum = dbl("sum"),
            state = dbl("state"),
            change = dbl("change"),
        )
    }
}
