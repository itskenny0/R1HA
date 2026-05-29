package com.github.itskenny0.r1ha.core.lovelace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject

/**
 * Local-only Lovelace overrides. Lets the user reorder, add, delete, or
 * patch cards inside a HA-imported dashboard view WITHOUT writing back
 * to HA's `lovelace/config/save` (a hard rule from the feature scope).
 *
 * The override is stored as a per-`(dashboardUrlPath, viewPath)` ordered
 * list of edit operations the renderer applies on top of HA's authoritative
 * config at render time. Storing operations (rather than a fully-rewritten
 * view) means HA-side changes to cards we haven't touched flow through; only
 * the cards the user explicitly customised stay pinned.
 *
 * Operations (in apply order):
 *  - `Reorder(fromIndex, toIndex)`. move card N to slot M
 *  - `Replace(index, json)`. substitute the card at slot N with the given raw JSON
 *  - `Delete(index)`. remove the card at slot N
 *  - `Append(json)`. append a new raw-JSON card to the end of the view
 *
 * Operations target indices into the ORIGINAL HA config so a user can
 * always toggle overrides off to reveal HA's authoritative layout (the
 * "show HA layout" toggle on the editor). The renderer evaluates them in
 * a stable order: deletes + replaces apply against original indices, then
 * reorders are run as swaps, then appends extend the list.
 *
 * Backed by a separate DataStore file so it doesn't bloat the main
 * settings.preferences (which already carries ~50 KB on a power-user
 * install).
 */
class LovelaceOverrideStore(context: Context) {

    private val dataStore: DataStore<Preferences> = context.applicationContext.lovelaceOverrideDataStore

    /**
     * Observe the override blob. Emits an empty [LovelaceOverrides] when
     * nothing has been written yet so the renderer can paint the pure-HA
     * layout without a special "first run" case.
     */
    val overrides: Flow<LovelaceOverrides> = dataStore.data.map { prefs ->
        val raw = prefs[KEY] ?: return@map LovelaceOverrides.EMPTY
        runCatching { JSON.decodeFromString<LovelaceOverrides>(raw) }
            .getOrElse {
                R1Log.w("LovelaceOverrideStore", "decode failed; resetting: ${it.message}")
                LovelaceOverrides.EMPTY
            }
    }

    /**
     * Atomically read-modify-write the override blob. Caller passes a
     * transform that receives the current blob and returns the next one;
     * DataStore guarantees serial application across coroutines.
     */
    suspend fun update(transform: (LovelaceOverrides) -> LovelaceOverrides) {
        dataStore.edit { prefs ->
            val current = prefs[KEY]?.let { raw ->
                runCatching { JSON.decodeFromString<LovelaceOverrides>(raw) }.getOrNull()
            } ?: LovelaceOverrides.EMPTY
            val next = transform(current)
            prefs[KEY] = JSON.encodeToString(next)
        }
    }

    companion object {
        // App-scoped extension on Context. single instance per process.
        private val Context.lovelaceOverrideDataStore by preferencesDataStore("r1ha_lovelace_overrides")
        private val KEY = stringPreferencesKey("overrides.v1")
        private val JSON = Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * Root override blob. Keyed by `(dashboardUrlPath ?: "_default_", viewPath)`
 * → ordered list of operations. Per-view rather than per-dashboard so an
 * edit on a view in dashboard A doesn't accidentally apply to a same-named
 * view in dashboard B.
 */
@Serializable
data class LovelaceOverrides(
    val views: Map<String, ViewOverride> = emptyMap(),
) {
    companion object {
        val EMPTY = LovelaceOverrides(views = emptyMap())

        /** Compose the storage key for a dashboard+view pair. Null dashboard
         *  path (the default dashboard) maps to a sentinel so missing-key
         *  semantics still work in JSON. */
        fun keyFor(dashboardUrlPath: String?, viewPath: String): String =
            (dashboardUrlPath ?: "_default_") + "::" + viewPath
    }
}

@Serializable
data class ViewOverride(
    val operations: List<OverrideOp> = emptyList(),
    /** Last edit time (epoch ms) so the editor can show "edited 3 m ago". */
    val updatedAt: Long = 0L,
) {
    fun isEmpty(): Boolean = operations.isEmpty()
}

/**
 * One mutation against a view's card list. JSON-tagged sealed class with
 * `@SerialName` discriminators so the on-disk format is stable even if
 * we reorder enum cases.
 */
@Serializable
sealed class OverrideOp {
    @Serializable
    @kotlinx.serialization.SerialName("reorder")
    data class Reorder(val fromIndex: Int, val toIndex: Int) : OverrideOp()

    @Serializable
    @kotlinx.serialization.SerialName("replace")
    data class Replace(
        val index: Int,
        /** Raw card JSON (mirrors HA's card config shape). Stored as text
         *  so the kotlinx-serialization codegen doesn't have to traverse
         *  a JsonObject tree on every read. */
        val json: String,
    ) : OverrideOp()

    @Serializable
    @kotlinx.serialization.SerialName("delete")
    data class Delete(val index: Int) : OverrideOp()

    @Serializable
    @kotlinx.serialization.SerialName("append")
    data class Append(val json: String) : OverrideOp()
}

/**
 * Apply [ViewOverride] operations on top of HA's authoritative card
 * list. Pure function: stateless, no IO. Tested in isolation.
 *
 * Application order matches HA-frontend's mental model the user is most
 * likely to expect:
 *  1. Per-index `Replace` operations rewrite individual cards in place.
 *  2. `Delete` operations remove cards (highest index first so indices
 *     stay stable while we delete).
 *  3. `Reorder` operations move surviving cards into the user's chosen
 *     order (applied sequentially).
 *  4. `Append` operations push freshly-authored cards onto the end.
 *
 * Out-of-range indices are silently ignored so a HA-side edit that
 * shortened the view doesn't crash the renderer.
 */
object LovelaceOverrideApplier {

    private val JSON = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun apply(
        original: List<LovelaceCard>,
        override: ViewOverride,
    ): List<LovelaceCard> {
        if (override.operations.isEmpty()) return original
        // Mutable working set keyed by original index so reorders can refer
        // to a stable handle. We track (originalIndex, currentCard) so a
        // later Replace can locate the same slot even after deletions.
        val working = original.mapIndexed { idx, card -> idx to card }.toMutableList()

        // 1) Replace.
        override.operations.filterIsInstance<OverrideOp.Replace>().forEach { op ->
            val slot = working.indexOfFirst { it.first == op.index }
            if (slot < 0) return@forEach
            val raw = runCatching { JSON.decodeFromString<JsonElement>(op.json) }
                .getOrNull() as? JsonObject ?: return@forEach
            working[slot] = op.index to LovelaceParser.parseCard(raw)
        }

        // 2) Delete (highest first).
        override.operations
            .filterIsInstance<OverrideOp.Delete>()
            .sortedByDescending { it.index }
            .forEach { op ->
                val slot = working.indexOfFirst { it.first == op.index }
                if (slot >= 0) working.removeAt(slot)
            }

        // 3) Reorder. applied sequentially against the current list of
        // surviving cards. fromIndex / toIndex refer to the index inside the
        // surviving list at the moment the op runs, so chained reorders
        // compose the way the editor's drag-drop visually shows.
        override.operations.filterIsInstance<OverrideOp.Reorder>().forEach { op ->
            val from = op.fromIndex.coerceIn(0, working.lastIndex.coerceAtLeast(0))
            val to = op.toIndex.coerceIn(0, working.lastIndex.coerceAtLeast(0))
            if (from == to || working.isEmpty()) return@forEach
            val moved = working.removeAt(from)
            working.add(to, moved)
        }

        // 4) Append.
        override.operations.filterIsInstance<OverrideOp.Append>().forEach { op ->
            val raw = runCatching { JSON.decodeFromString<JsonElement>(op.json) }
                .getOrNull() as? JsonObject ?: return@forEach
            // Use a synthetic negative index sentinel so appended cards can
            // be edited by a later Replace targeting an absolute index in
            // the post-apply list. but we don't try to round-trip those
            // edits today. The editor regenerates the override blob from
            // the final visible list when the user mutates an appended card.
            working.add(-(working.size + 1) to LovelaceParser.parseCard(raw))
        }

        return working.map { it.second }
    }
}

/**
 * Render-time bridge that uses [LovelaceOverrideApplier] but also keeps
 * track of the per-card "is this an override?" flag for the visual hint
 * on the edit-mode overlay. Returned list is parallel to the rendered
 * cards.
 */
data class RenderedCard(val card: LovelaceCard, val isOverridden: Boolean)

/**
 * Apply [override] against [original] and tag every surviving card with
 * whether the user's overrides touched it. Used by the dashboard view
 * screen to draw a subtle marker on overridden cards.
 */
fun renderWithFlags(
    original: List<LovelaceCard>,
    override: ViewOverride,
): List<RenderedCard> {
    if (override.operations.isEmpty()) {
        return original.map { RenderedCard(it, isOverridden = false) }
    }
    val replacedIndices = override.operations.filterIsInstance<OverrideOp.Replace>().map { it.index }.toSet()
    val deletedIndices = override.operations.filterIsInstance<OverrideOp.Delete>().map { it.index }.toSet()
    val reordered = override.operations.any { it is OverrideOp.Reorder }
    val appended = override.operations.count { it is OverrideOp.Append }
    val applied = LovelaceOverrideApplier.apply(original, override)
    // Approximation: the appended count maps to the last N rendered cards.
    // The first (applied.size - appended) entries are the surviving HA cards;
    // we mark them as overridden if their original index was replaced, or
    // any reorder happened (we can't easily reconstruct per-card reorder
    // identity post-apply, so reorder taints the whole view).
    val survivingCount = (applied.size - appended).coerceAtLeast(0)
    val survivingOriginalIndices = (original.indices - deletedIndices).toList()
    return applied.mapIndexed { renderIdx, card ->
        val isAppended = renderIdx >= survivingCount
        if (isAppended) return@mapIndexed RenderedCard(card, isOverridden = true)
        val origIdx = survivingOriginalIndices.getOrNull(renderIdx)
        val touched = origIdx != null && origIdx in replacedIndices
        RenderedCard(card, isOverridden = touched || reordered)
    }
}

/**
 * Encode a card's raw JSON back into a string suitable for Replace / Append.
 * Lives here (rather than on the editor) because the JSON instance is the
 * one configured by this module and the encoder rules must match what the
 * applier reads back.
 */
fun encodeCardJson(obj: JsonObject): String = LOVELACE_EDIT_JSON.encodeToString(obj)

/** Builder helper: append-card op from a parsed JsonObject. */
fun appendOp(obj: JsonObject): OverrideOp.Append = OverrideOp.Append(json = encodeCardJson(obj))

/** Builder helper: replace-card op from a parsed JsonObject. */
fun replaceOp(index: Int, obj: JsonObject): OverrideOp.Replace =
    OverrideOp.Replace(index = index, json = encodeCardJson(obj))

/** Pretty-print JSON shape used by the editor's text area. */
val LOVELACE_EDIT_JSON: Json = Json {
    prettyPrint = true
    encodeDefaults = false
    ignoreUnknownKeys = true
}

/** Parse a JSON blob from the editor into a card object. Returns null
 *  when the text isn't valid JSON or isn't a JSON object. */
fun parseCardJsonBlob(text: String): JsonObject? = runCatching {
    LOVELACE_EDIT_JSON.decodeFromString<JsonElement>(text) as? JsonObject
}.getOrNull()

/**
 * Empty-state stub used by the card picker to surface every card type
 * we currently render natively. Keys are the `type` value, values are
 * the empty-JSON skeleton that's safe to insert + then edit.
 */
val PICKER_TEMPLATES: List<Pair<String, JsonObject>> = listOf(
    "entities" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("entities"))
        put("title", kotlinx.serialization.json.JsonPrimitive("New list"))
        put("entities", buildJsonArray { })
    },
    "glance" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("glance"))
        put("title", kotlinx.serialization.json.JsonPrimitive("At a glance"))
        put("entities", buildJsonArray { })
    },
    "tile" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("tile"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "button" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("button"))
        put("name", kotlinx.serialization.json.JsonPrimitive("Action"))
    },
    "light" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("light"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "gauge" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("gauge"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
        put("min", kotlinx.serialization.json.JsonPrimitive(0))
        put("max", kotlinx.serialization.json.JsonPrimitive(100))
    },
    "markdown" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("markdown"))
        put("content", kotlinx.serialization.json.JsonPrimitive("## Hello\n\nMarkdown body."))
    },
    "heading" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("heading"))
        put("heading", kotlinx.serialization.json.JsonPrimitive("Section"))
        put("heading_style", kotlinx.serialization.json.JsonPrimitive("title"))
    },
    "weather-forecast" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("weather-forecast"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "thermostat" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("thermostat"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "media-control" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("media-control"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "humidifier" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("humidifier"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
    },
    "vertical-stack" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("vertical-stack"))
        put("cards", buildJsonArray { })
    },
    "horizontal-stack" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("horizontal-stack"))
        put("cards", buildJsonArray { })
    },
    "grid" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("grid"))
        put("columns", kotlinx.serialization.json.JsonPrimitive(3))
        put("cards", buildJsonArray { })
    },
    "conditional" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("conditional"))
        put("conditions", buildJsonArray { })
        put("card", buildJsonObject {
            put("type", kotlinx.serialization.json.JsonPrimitive("tile"))
            put("entity", kotlinx.serialization.json.JsonPrimitive(""))
        })
    },
    "entity-filter" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("entity-filter"))
        put("entities", buildJsonArray { })
        put("state_filter", buildJsonArray { add(kotlinx.serialization.json.JsonPrimitive("on")) })
    },
    "statistic" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("statistic"))
        put("entity", kotlinx.serialization.json.JsonPrimitive(""))
        put("stat_type", kotlinx.serialization.json.JsonPrimitive("mean"))
        put("period", kotlinx.serialization.json.JsonPrimitive("day"))
    },
    "logbook" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("logbook"))
        put("entities", buildJsonArray { })
        put("hours_to_show", kotlinx.serialization.json.JsonPrimitive(12))
    },
    "clock" to buildJsonObject {
        put("type", kotlinx.serialization.json.JsonPrimitive("clock"))
        put("show_seconds", kotlinx.serialization.json.JsonPrimitive(true))
    },
)
