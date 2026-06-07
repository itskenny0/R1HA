package com.github.itskenny0.r1ha.feature.energy

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId

/**
 * Energy summary surface — mirrors a slim slice of HA's Energy panel.
 *
 * HA's full Energy dashboard has per-hour bar charts, source breakdown
 * (grid / solar / battery), gas + water meters, and per-device
 * consumption. Re-implementing that on the R1's small screen would
 * trade legibility for completeness, so this surface picks the four
 * numbers a user actually wants at a glance:
 *
 *   1. Current power draw (W) — sum of every `device_class=power` sensor
 *      with positive state (negative-sign sensors are battery export /
 *      solar production and are excluded from the consumption total)
 *   2. Solar production (W) — sum of positive-state `device_class=power`
 *      sensors whose entity_id matches the word-boundary-anchored
 *      `solar` / `pv` / `photovoltaic` / `production` heuristics, plus a
 *      `grid_export` sensor's exported (negative) power as max(-state, 0)
 *   3. Today's energy (kWh) — sum of every `device_class=energy` sensor
 *      whose `state_class=total_increasing` and whose `last_reset`
 *      lands today (the standard HA pattern for "kWh since midnight"
 *      sensors)
 *   4. Top 5 current consumers — sorted descending by instantaneous W
 *
 * Everything is fetched via `/api/template` so we never have to ship
 * a full /api/states pull just to aggregate.
 */
class EnergyViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @Stable
    data class Consumer(
        val entityId: String,
        val name: String,
        /** Instantaneous power in watts. */
        val watts: Double,
    )

    /** History window selector. Each chip picks both a span and the
     *  recorder bucket resolution to request, following HA's own energy
     *  card: short windows zoom into hour buckets, the month view drops to
     *  day buckets so the chart stays legible. TODAY is the calendar day
     *  since local midnight (matching the TODAY kWh tile above). */
    enum class Window(val label: String) {
        TODAY("TODAY"),
        H24("24H"),
        D7("7D"),
        D30("30D"),
    }

    /** One consumption bar: the bucket's start instant and the kWh used
     *  during that bucket (summed across every energy meter). */
    data class HistoryBar(val timestamp: Instant, val kwh: Double)

    @Stable
    data class UiState(
        val loading: Boolean = true,
        /** Sum of every `device_class=power` sensor's positive state in
         *  W. Negative sensors (battery export, grid export) are
         *  excluded so the figure is "what's being USED right now". */
        val currentDrawW: Double? = null,
        /** Production estimate in W from sensors with solar/pv/export
         *  hints in the entity_id. Conservative — only known
         *  patterns count. */
        val productionW: Double? = null,
        /** Today's energy total in kWh. Prefer [statsTodayKwh] (recorder
         *  `change` summed since local midnight, exactly how HA's Energy
         *  panel computes it); fall back to the live-template sum only when
         *  the recorder hasn't answered yet. */
        val todayKwh: Double? = null,
        /** Today's consumption derived from the recorder: sum of every
         *  energy meter's per-bucket `change` since local midnight. Null
         *  until a history fetch covering today has succeeded. This is the
         *  authoritative TODAY figure; [todayKwh] holds the template
         *  fallback used before the recorder answers. */
        val statsTodayKwh: Double? = null,
        /** Top consumers by current W draw. Empty when no data. */
        val topConsumers: List<Consumer> = emptyList(),
        /** True when the install exposes a battery power source (a
         *  `device_class=power` sensor whose entity_id carries a `battery`
         *  hint). Lets the UI mark the PRODUCTION tile with the battery glyph
         *  instead of the solar sun, so a battery-backed site reads correctly
         *  rather than implying everything is photovoltaic. */
        val hasBatterySource: Boolean = false,
        val error: String? = null,
        /** Selected history window for the consumption chart. */
        val window: Window = Window.TODAY,
        /** True while the recorder statistics fetch is in flight. Kept
         *  independent of [loading] so flipping windows doesn't blank the
         *  live tiles above. */
        val historyLoading: Boolean = false,
        /** Per-bucket consumption (kWh) for [window], oldest first. Empty
         *  when the recorder has no energy statistics for the span. */
        val historyBars: List<HistoryBar> = emptyList(),
        /** Set when the statistics fetch itself failed (transport / auth).
         *  Distinct from "no statistics", which renders an empty state. */
        val historyError: String? = null,
        /** True once the catalogue has been checked and no energy meter
         *  statistic ids were found, so the chart shows a clear "recorder
         *  has no energy statistics" message rather than a blank panel. */
        val historyNoStatistics: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Four templates in parallel — each one is cheap server-side
            // (a single Jinja pass over states.sensor), but firing them
            // serially would gate every refresh on the slowest. await
            // them all so the UI flips loading → ready in one render.
            val drawJob = async { haRepository.renderTemplate(SUM_POWER_DRAW) }
            val prodJob = async { haRepository.renderTemplate(SUM_PRODUCTION) }
            val kwhJob = async { haRepository.renderTemplate(SUM_TODAY_KWH) }
            val topJob = async { haRepository.renderTemplate(TOP_CONSUMERS_JSON) }
            val batJob = async { haRepository.renderTemplate(HAS_BATTERY_SOURCE) }
            awaitAll(drawJob, prodJob, kwhJob, topJob, batJob)

            val drawRaw = drawJob.await().getOrNull()?.trim()
            val prodRaw = prodJob.await().getOrNull()?.trim()
            val kwhRaw = kwhJob.await().getOrNull()?.trim()
            val topRaw = topJob.await().getOrNull()?.trim()
            val batRaw = batJob.await().getOrNull()?.trim()

            // Any single template failure shouldn't tank the whole
            // surface — we render whatever did succeed and the other
            // tiles show '—'. The user can still glean useful info.
            val anyFailed = listOf(drawJob, prodJob, kwhJob, topJob)
                .any { it.await().isFailure }
            if (anyFailed) {
                val firstError = listOf(drawJob, prodJob, kwhJob, topJob)
                    .firstNotNullOfOrNull { it.await().exceptionOrNull()?.message }
                R1Log.w("Energy", "partial load failure: $firstError")
                // Don't toast — partial failure is normal on installs
                // that don't have any power-class sensors yet.
            }

            val top = topRaw?.let { parseTopConsumers(it) }.orEmpty()
            R1Log.i(
                "Energy",
                "draw=$drawRaw prod=$prodRaw kwh=$kwhRaw consumers=${top.size}",
            )
            // copy() over the existing state so the history section (window,
            // bars, load flags) survives a live-tile refresh; rebuilding a
            // fresh UiState here would blank the chart on every 30 s tick.
            _ui.value = _ui.value.copy(
                loading = false,
                currentDrawW = drawRaw?.toDoubleOrNull(),
                productionW = prodRaw?.toDoubleOrNull(),
                todayKwh = kwhRaw?.toDoubleOrNull(),
                topConsumers = top,
                // Best-effort enrichment: leave the flag unchanged if the
                // probe failed (null) so a transient template error doesn't
                // flip the icon off mid-session.
                hasBatterySource = batRaw?.equals("True", ignoreCase = true)
                    ?: _ui.value.hasBatterySource,
                error = if (anyFailed && drawRaw == null && prodRaw == null &&
                    kwhRaw == null && top.isEmpty()) {
                    "All energy templates returned errors. Does HA have any " +
                        "device_class=power or device_class=energy sensors?"
                } else null,
            )
        }
    }

    /** Switch the history window and re-fetch its consumption series. No-op
     *  if the window is unchanged so tapping the active chip is free. */
    fun setWindow(window: Window) {
        if (_ui.value.window == window) return
        _ui.value = _ui.value.copy(window = window)
        refreshHistory()
    }

    /**
     * Load the consumption-per-bucket history for the selected window from
     * the recorder. Picks the energy-meter statistic ids (the same
     * `device_class=energy` `total_increasing` family that feeds the TODAY
     * tile), fetches their long-term buckets, and sums each bucket's
     * `change` (consumption during the bucket) across every meter.
     *
     * Reuses the existing `getStatisticsDuringPeriod` repo method, so no
     * new repository surface is needed. Installs with no recorder energy
     * statistics land in a clear empty state.
     */
    fun refreshHistory() {
        val window = _ui.value.window
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                historyLoading = true,
                historyError = null,
                historyNoStatistics = false,
            )
            val ids = energyStatisticIds.ifEmpty {
                // Lazily resolve (and cache) the energy meter ids from the
                // recorder catalogue the first time history is requested.
                val resolved = haRepository.listStatisticIds().fold(
                    onSuccess = { rows ->
                        energyStatisticUnits = rows.associate { it.statisticId to it.unitOfMeasurement }
                        selectEnergyStatisticIds(rows)
                    },
                    onFailure = { t ->
                        R1Log.w("Energy", "statistic catalogue load failed: ${t.message}")
                        _ui.value = _ui.value.copy(
                            historyLoading = false,
                            historyError = t.message ?: "unknown",
                        )
                        return@launch
                    },
                )
                energyStatisticIds = resolved
                resolved
            }
            if (ids.isEmpty()) {
                R1Log.i("Energy", "no energy meter statistic ids in recorder catalogue")
                _ui.value = _ui.value.copy(
                    historyLoading = false,
                    historyBars = emptyList(),
                    historyNoStatistics = true,
                )
                return@launch
            }
            val end = Instant.now()
            val start = windowStart(window, end)
            haRepository.getStatisticsDuringPeriod(
                statisticIds = ids,
                start = start,
                end = end,
                period = window.period(),
            ).fold(
                onSuccess = { byId ->
                    val bars = aggregateConsumption(byId, energyStatisticUnits)
                    // Sum the consumption of buckets that fall on or after
                    // local midnight: every window we request runs up to now,
                    // so it always contains today's buckets. This is the
                    // recorder-accurate TODAY figure, regardless of which
                    // window is selected. Hour buckets give it per-hour
                    // resolution; the 30-day day-bucket view still lands on a
                    // single bucket for the current day.
                    val midnight = end.atZone(ZoneId.systemDefault())
                        .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
                    val today = bars
                        .filter { !it.timestamp.isBefore(midnight) }
                        .sumOf { it.kwh }
                        .takeIf { bars.any { b -> !b.timestamp.isBefore(midnight) } }
                    R1Log.i(
                        "Energy",
                        "history window=${window.label} period=${window.period()} " +
                            "ids=${ids.size} bars=${bars.size} todayKwh=$today",
                    )
                    _ui.value = _ui.value.copy(
                        historyLoading = false,
                        historyBars = bars,
                        historyError = null,
                        statsTodayKwh = today ?: _ui.value.statsTodayKwh,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Energy", "history fetch failed: ${t.message}")
                    Toaster.error("Energy history load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        historyLoading = false,
                        historyError = t.message,
                    )
                },
            )
        }
    }

    /** Cached energy-meter statistic ids, resolved from the recorder
     *  catalogue on first history load and reused across window flips. */
    private var energyStatisticIds: List<String> = emptyList()

    /** Recorder unit per statistic id (e.g. "kWh", "Wh"), captured alongside
     *  [energyStatisticIds] so [aggregateConsumption] can normalise meters that
     *  report in Wh / MWh / GWh into the kWh figure the chart labels. */
    private var energyStatisticUnits: Map<String, String?> = emptyMap()

    /** Parse the JSON array of [entity_id, name, watts] triples that
     *  the TOP_CONSUMERS_JSON template emits. Robust to malformed
     *  rows: a single bad triple drops out without breaking the rest. */
    private fun parseTopConsumers(raw: String): List<Consumer> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val arr = Json.parseToJsonElement(raw) as? JsonArray ?: return emptyList()
            arr.mapNotNull { row ->
                val r = row as? JsonArray ?: return@mapNotNull null
                if (r.size < 3) return@mapNotNull null
                val id = (r[0] as? JsonPrimitive)?.contentOrNull() ?: return@mapNotNull null
                val name = (r[1] as? JsonPrimitive)?.contentOrNull() ?: id
                val watts = (r[2] as? JsonPrimitive)?.contentOrNull()?.toDoubleOrNull()
                    ?: return@mapNotNull null
                Consumer(entityId = id, name = name, watts = watts)
            }
        }.onFailure { R1Log.w("Energy", "top-consumers parse failed: ${it.message}") }
            .getOrNull().orEmpty()
    }

    /** Recorder bucket resolution to request for each window. Hour buckets
     *  for spans up to a week keep the bar count sensible (24..168); the
     *  30-day view drops to day buckets so the chart shows 30 bars rather
     *  than 720. TODAY uses hour buckets, one bar per hour of the day. */
    private fun Window.period(): String = when (this) {
        Window.TODAY -> "hour"
        Window.H24 -> "hour"
        Window.D7 -> "hour"
        Window.D30 -> "day"
    }

    /** Window start instant. TODAY snaps to local midnight so the chart
     *  matches the TODAY kWh tile's "since midnight" figure; the rolling
     *  windows subtract a fixed span from [end]. */
    private fun windowStart(window: Window, end: Instant): Instant = when (window) {
        Window.TODAY -> end.atZone(ZoneId.systemDefault())
            .toLocalDate().atStartOfDay(ZoneId.systemDefault()).toInstant()
        Window.H24 -> end.minusSeconds(24L * 3600L)
        Window.D7 -> end.minusSeconds(7L * 24L * 3600L)
        Window.D30 -> end.minusSeconds(30L * 24L * 3600L)
    }

    /** Compose's runtime doesn't expose JsonPrimitive.contentOrNull
     *  on every minSdk we target — pull `.content` and handle null
     *  manually. */
    private fun JsonPrimitive.contentOrNull(): String? =
        runCatching { content }.getOrNull()?.takeIf { it != "null" }

    companion object {
        /** Word-boundary-anchored production regex shared by DRAW (reject),
         *  PRODUCTION (select), and TOP_CONSUMERS (reject). Anchoring `pv` and
         *  `solar` to `\b` stops the old bare `pv` substring from matching
         *  unrelated ids like `sensor.hp_valve_power` ("...pv..." within
         *  another word). `photovoltaic`, `grid_export`, and `production` stay
         *  as plain (already-bounded enough) substrings. The doubled
         *  backslashes are Kotlin escapes: the template receives a single
         *  `\b` per anchor, which is the regex word-boundary the HA-side
         *  `search` (Python `re.search`) test consumes. */
        private const val PRODUCTION_RE = "\\bsolar\\b|photovoltaic|grid_export|production|\\bpv\\b"

        /** Sum positive-state device_class=power sensors. Excludes
         *  unavailable / unknown / non-numeric, and excludes the
         *  production-heuristic entities (solar / pv / grid_export /
         *  production): inverters that report production as a positive
         *  power value would otherwise be counted as consumption here
         *  AND as production in SUM_PRODUCTION, double-counting them.
         *  The reject mirrors SUM_PRODUCTION's select so the two slices
         *  stay disjoint. */
        private const val SUM_POWER_DRAW = "{{ states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| rejectattr('entity_id','search','$PRODUCTION_RE') " +
            "| map(attribute='state') | map('float',0) " +
            "| select('>',0) | sum | round(0) }}"

        /** Production heuristic. Solar / PV / photovoltaic / production
         *  inverters report generation as a positive power value, so only
         *  positive states count (a 0 W night-time inverter, or a sign-flipped
         *  reading, must not subtract from the total). A `grid_export` sensor
         *  is really signed grid power: positive = importing, negative =
         *  exporting, so its contribution to PRODUCTION is max(-state, 0).
         *  The old `map('abs')` inflated the figure by folding imported grid
         *  power in as if it were generation. Two passes summed together. */
        private const val SUM_PRODUCTION = "{% set gen = states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| selectattr('entity_id','search','\\bsolar\\b|photovoltaic|production|\\bpv\\b') " +
            "| map(attribute='state') | map('float',0) " +
            "| select('>',0) | sum %}" +
            "{% set grid = states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| selectattr('entity_id','search','grid_export') " +
            "| map(attribute='state') | map('float',0) | sum %}" +
            "{% set exp = [(grid * -1), 0] | max %}" +
            "{{ (gen + exp) | round(0) }}"

        /** True when at least one `device_class=power` sensor carries a
         *  `battery` word-boundary hint in its entity_id. Drives the
         *  PRODUCTION tile's battery-vs-sun icon. Emits a bare "True"/"False"
         *  the client parses case-insensitively. */
        private const val HAS_BATTERY_SOURCE = "{{ (states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| selectattr('entity_id','search','\\bbattery\\b') " +
            "| list | count) > 0 }}"

        /** Sum every device_class=energy sensor whose state_class is
         *  total_increasing — those are the per-day-resetting kWh
         *  meters HA uses for the Energy dashboard. */
        private const val SUM_TODAY_KWH = "{{ states.sensor " +
            "| selectattr('attributes.device_class','eq','energy') " +
            "| selectattr('attributes.state_class','eq','total_increasing') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| map(attribute='state') | map('float',0) | sum | round(2) }}"

        /** JSON array of [entity_id, friendly_name, watts] triples for
         *  the top 5 current consumers. We pass a list comprehension
         *  through to_json so the parsing on the client side is one
         *  Json.parseToJsonElement call. */
        private const val TOP_CONSUMERS_JSON = "{%- set out = namespace(items=[]) -%}" +
            "{%- for s in (states.sensor " +
            "| selectattr('attributes.device_class','eq','power') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| rejectattr('entity_id','search','$PRODUCTION_RE') " +
            "| sort(attribute='state', reverse=True))[:8] -%}" +
            "{%- set w = s.state | float(0) -%}" +
            "{%- if w > 0 -%}" +
            "{%- set out.items = out.items + [[s.entity_id, s.name, w]] -%}" +
            "{%- endif -%}" +
            "{%- endfor -%}" +
            "{{ out.items | tojson }}"

        /** Sum each bucket's per-meter `change` (consumption during the
         *  bucket) across every requested meter, keyed by bucket start,
         *  oldest first. `change` is HA's server-computed per-bucket delta
         *  of the cumulative sum, i.e. exactly "kWh used in this hour/day",
         *  so no client-side differencing is needed. Buckets the recorder
         *  left without a finite `change` (the first bucket of a series,
         *  gaps) contribute nothing. Returns bars only for instants where
         *  at least one meter reported, so single- and multi-meter installs
         *  both read cleanly. */
        fun aggregateConsumption(
            byId: Map<String, List<StatisticsBucket>>,
            unitsById: Map<String, String?> = emptyMap(),
        ): List<HistoryBar> {
            val sums = sortedMapOf<Instant, Double>()
            for ((id, buckets) in byId) {
                // Normalise each meter to kWh: a Wh meter's `change` is 1000x the
                // kWh figure the chart labels, so summing raw across mixed units
                // (or a pure-Wh install) would be wildly off. Unknown unit -> 1.0.
                val factor = energyUnitToKwh(unitsById[id])
                for (b in buckets) {
                    val c = b.change?.takeIf { it.isFinite() } ?: continue
                    sums[b.start] = (sums[b.start] ?: 0.0) + c * factor
                }
            }
            return sums.map { (start, kwh) -> HistoryBar(start, kwh) }
        }

        /** Multiplier converting a recorder energy unit to kWh, so meters
         *  reporting in Wh / MWh / GWh aggregate into the kWh figure the chart
         *  and TODAY tile label. An absent or unrecognised unit defaults to 1.0
         *  (treated as already kWh, matching the common case). */
        fun energyUnitToKwh(unit: String?): Double = when (unit?.trim()?.lowercase()) {
            "wh" -> 0.001
            "mwh" -> 1_000.0
            "gwh" -> 1_000_000.0
            else -> 1.0
        }

        /** Pick the energy-meter statistic ids from the recorder catalogue:
         *  the cumulative `total_increasing` kWh meters that drive HA's
         *  Energy dashboard. We match on [StatisticId.hasSum] (only total
         *  statistics carry a cumulative sum the recorder can difference
         *  into `change`) AND an energy unit (Wh / kWh / MWh / GWh), so a
         *  water or gas meter that also records a sum doesn't sneak into the
         *  electricity chart. */
        fun selectEnergyStatisticIds(rows: List<StatisticId>): List<String> =
            rows.filter { it.hasSum && isEnergyUnit(it.unitOfMeasurement) }
                .map { it.statisticId }

        /** True for the recorder's electrical-energy units. Case-insensitive,
         *  trimmed; rejects power units (W / kW) and non-energy meters. */
        private fun isEnergyUnit(unit: String?): Boolean {
            val u = unit?.trim()?.lowercase() ?: return false
            return u == "wh" || u == "kwh" || u == "mwh" || u == "gwh"
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { EnergyViewModel(haRepository) }
        }
    }
}
