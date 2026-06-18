package com.github.itskenny0.r1ha.feature.energy

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
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
    private val settings: SettingsRepository,
) : ViewModel() {

    @Stable
    data class Consumer(
        val entityId: String,
        val name: String,
        /** Instantaneous power in watts. */
        val watts: Double,
    )

    /** One manually-excluded power sensor, surfaced in the management sheet so
     *  the user can review and re-include it. [name] is the friendliest label
     *  available: the entity's last-seen friendly name when we have one (from a
     *  prior TOP CONSUMERS render), otherwise the entity id itself. */
    @Stable
    data class ExcludedSensor(
        val entityId: String,
        val name: String,
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
        /** True while a live-tile refresh is in flight; drives the
         *  pull-to-refresh indicator. */
        val refreshing: Boolean = false,
        /** Sum of every `device_class=power` sensor's positive state in
         *  W. Negative sensors (battery export, grid export) are
         *  excluded so the figure is "what's being USED right now". */
        val currentDrawW: Double? = null,
        /** Production estimate in W from sensors with solar/pv/export
         *  hints in the entity_id. Conservative — only known
         *  patterns count. */
        val productionW: Double? = null,
        /** Raw [SUM_TODAY_KWH] template result: the summed CURRENT STATE of every
         *  `device_class=energy state_class=total_increasing` sensor. NOTE this is
         *  NOT "today's energy": those counters are cumulative (a smart plug's
         *  lifetime kWh, a utility meter's running total), so the sum is a lifetime
         *  figure, not a since-midnight delta. A Jinja template cannot compute the
         *  daily delta (it has no recorder history), so this is kept ONLY as a
         *  template-engine liveness probe for the error gate, never shown as TODAY.
         *  The displayed TODAY is [statsTodayKwh] (recorder), or '—' until it
         *  answers. (Was previously surfaced as a fallback, which showed a
         *  nonsensical lifetime total for installs with cumulative meters.) */
        val todayKwh: Double? = null,
        /** Today's consumption derived from the recorder: sum of every
         *  energy meter's per-bucket `change` since local midnight. The FALLBACK
         *  for the TODAY tile when the real-time figure isn't available; [todayKwh]
         *  is a cumulative template sum and is deliberately NOT used as a fallback. */
        val statsTodayKwh: Double? = null,
        /** REAL-TIME today (kWh): each energy meter's rise in reading from local
         *  midnight to now, summed (see [EnergyTodayCalc]). Computed from raw state
         *  history every refresh, so it is current during the day and ~0 right after
         *  midnight, with no hourly recorder lag. The PREFERRED TODAY figure;
         *  [statsTodayKwh] is the recorder fallback when the history fetch fails. */
        val realtimeTodayKwh: Double? = null,
        /** Top consumers by current W draw. Empty when no data. */
        val topConsumers: List<Consumer> = emptyList(),
        /** Power sensors the user has manually excluded from every aggregate.
         *  Friendly names attached where known (from a prior consumer render),
         *  otherwise the entity id. Sorted by name for a stable management list. */
        val excludedSensors: List<ExcludedSensor> = emptyList(),
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

        // ---- water / gas summary tiles ---------------------------------------
        // NOTE: these fields are built offline and have NOT been verified against
        // a live Home Assistant instance. They mirror the SUM_TODAY_KWH template
        // path (known to be unverified for normalisation/unit handling - see the
        // energy-template-unit-normalization memory note) using device_class=water
        // and device_class=gas respectively. They are null when no such sensors
        // exist, so the tiles appear only on installs that have water / gas meters.

        /** Today's total water consumption, summed from
         *  `device_class=water state_class=total_increasing` sensors, in the
         *  unit of the first sensor encountered (mixed units are summed raw;
         *  see [SUM_TODAY_WATER] comment). Null when no water sensors exist.
         *
         *  UNVERIFIED OFFLINE: template path mirrors SUM_TODAY_KWH but has not
         *  been tested against a live HA with water meters. */
        val todayWater: Double? = null,

        /** Unit string for [todayWater] (e.g. "m3", "gal"). Null when
         *  [todayWater] is null.
         *
         *  UNVERIFIED OFFLINE: read from the first water sensor's
         *  unit_of_measurement via the Jinja template. */
        val waterUnit: String? = null,

        /** Today's total gas consumption, summed from
         *  `device_class=gas state_class=total_increasing` sensors, in the
         *  unit of the first sensor encountered (mixed units are summed raw;
         *  see [SUM_TODAY_GAS] comment). Null when no gas sensors exist.
         *
         *  UNVERIFIED OFFLINE: template path mirrors SUM_TODAY_KWH but has not
         *  been tested against a live HA with gas meters. */
        val todayGas: Double? = null,

        /** Unit string for [todayGas] (e.g. "m3", "ft3"). Null when
         *  [todayGas] is null.
         *
         *  UNVERIFIED OFFLINE: read from the first gas sensor's
         *  unit_of_measurement via the Jinja template. */
        val gasUnit: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Last-seen friendly name per entity id, accumulated from every TOP
     *  CONSUMERS render. Lets the exclusion management sheet show "Living room
     *  plug" rather than the bare entity id even after that sensor has been
     *  excluded (and so no longer appears in the live consumer list). */
    private val knownNames = mutableMapOf<String, String>()

    init {
        // Re-fetch the live tiles whenever the WS (re)connects. A resume can
        // race the reconnect: the screen's initial refresh fires while the
        // socket is still down, every template fails, and the all-errors
        // banner sat latched until the 30 s auto-tick — observed on device as
        // 'n/a tiles until manual refresh'. Connected is the exact moment a
        // retry can succeed, so refresh then instead of waiting out the tick.
        viewModelScope.launch {
            haRepository.connection.collect { conn ->
                if (conn is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected) {
                    refresh()
                }
            }
        }
    }

    /**
     * [indicate] marks a user-initiated refresh (pull gesture, REFRESH chip):
     * only those drive the pull-to-refresh indicator. The 30s auto-refresh and
     * the initial load keep it false so the indicator doesn't pop unbidden on
     * every background tick.
     */
    fun refresh(indicate: Boolean = false) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, refreshing = indicate, error = null)
            // The user's manual exclusion list, read fresh each refresh so a
            // mid-session exclude/include re-renders every aggregate without it.
            // Spliced into each power-sensor template as a safe rejectattr clause
            // (see [EnergyTemplates]); an empty set is a no-op so unexcluded
            // installs render exactly as before.
            val excluded = settings.settings.first().energyExcludedSensors
            // Core electricity templates in parallel; each one is cheap server-side
            // (a single Jinja pass over states.sensor), but firing them
            // serially would gate every refresh on the slowest. await
            // them all so the UI flips loading → ready in one render.
            val drawJob = async { haRepository.renderTemplate(EnergyTemplates.sumPowerDraw(excluded)) }
            val prodJob = async { haRepository.renderTemplate(EnergyTemplates.sumProduction(excluded)) }
            val kwhJob = async { haRepository.renderTemplate(SUM_TODAY_KWH) }
            val topJob = async { haRepository.renderTemplate(EnergyTemplates.topConsumersJson(excluded)) }
            val batJob = async { haRepository.renderTemplate(HAS_BATTERY_SOURCE) }
            // Water + gas are additive/optional: fetched in parallel with the
            // electricity templates but their failure is always silent:
            // tiles simply stay absent rather than surfacing an error.
            // UNVERIFIED OFFLINE: these templates mirror SUM_TODAY_KWH and have
            // not been tested against a live HA with water / gas meters.
            val waterJob = async { haRepository.renderTemplate(SUM_TODAY_WATER) }
            val gasJob = async { haRepository.renderTemplate(SUM_TODAY_GAS) }
            // Energy prefs are best-effort: a failure or empty result is a no-op
            // overlay (consumers still display with their HA friendly_name).
            // UNVERIFIED OFFLINE: energy/get_prefs WS command not tested against
            // a live HA instance.
            val prefsJob = async { haRepository.getEnergyPrefs() }
            // REAL-TIME today: per-meter state-history delta since local midnight,
            // in parallel with the templates. Best-effort — a null result (no
            // meters, or every history fetch failed) leaves the last value / the
            // recorder fallback in place rather than blanking the tile.
            val realtimeJob = async { computeRealtimeTodayKwh() }
            awaitAll(drawJob, prodJob, kwhJob, topJob, batJob, waterJob, gasJob, prefsJob, realtimeJob)
            val realtimeToday = realtimeJob.await()

            val drawRaw = drawJob.await().getOrNull()?.trim()
            val prodRaw = prodJob.await().getOrNull()?.trim()
            val kwhRaw = kwhJob.await().getOrNull()?.trim()
            val topRaw = topJob.await().getOrNull()?.trim()
            val batRaw = batJob.await().getOrNull()?.trim()
            val waterRaw = waterJob.await().getOrNull()?.trim()
            val gasRaw = gasJob.await().getOrNull()?.trim()
            // Custom name overrides keyed by entity/stat id. Empty map when
            // the fetch failed or the user hasn't set any custom names.
            val customNames = prefsJob.await().getOrElse { emptyMap() }

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
                // Apply custom display names where available. The consumer
                // fetch and ranking are unchanged; only the display name is
                // overridden. UNVERIFIED OFFLINE: relies on getEnergyPrefs()
                // which has not been tested against a live HA instance.
                .map { c ->
                    val override = customNames[c.entityId]
                    if (override != null) c.copy(name = override) else c
                }
            // Remember each consumer's friendly name so the exclusion sheet can
            // label a sensor that's since been excluded (and thus dropped from
            // the live list). Custom name overrides win here too.
            top.forEach { c -> knownNames[c.entityId] = c.name }
            val excludedSensors = buildExcludedSensors(excluded)
            val (waterValue, waterUnitStr) = parseValueUnit(waterRaw)
            val (gasValue, gasUnitStr) = parseValueUnit(gasRaw)
            R1Log.i(
                "Energy",
                "draw=$drawRaw prod=$prodRaw kwh=$kwhRaw consumers=${top.size} " +
                    "water=$waterRaw gas=$gasRaw customNames=${customNames.size}",
            )
            // copy() over the existing state so the history section (window,
            // bars, load flags) survives a live-tile refresh; rebuilding a
            // fresh UiState here would blank the chart on every 30 s tick.
            _ui.value = _ui.value.copy(
                loading = false,
                refreshing = false,
                currentDrawW = drawRaw?.toDoubleOrNull(),
                productionW = prodRaw?.toDoubleOrNull(),
                todayKwh = kwhRaw?.toDoubleOrNull(),
                // Keep the previous real-time figure when this fetch yields null
                // (transient history failure) so the tile doesn't flicker to '—'.
                realtimeTodayKwh = realtimeToday ?: _ui.value.realtimeTodayKwh,
                topConsumers = top,
                excludedSensors = excludedSensors,
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
                // Water + gas: null when the template returned empty (no sensors).
                // UNVERIFIED OFFLINE: see SUM_TODAY_WATER / SUM_TODAY_GAS above.
                todayWater = waterValue,
                waterUnit = waterUnitStr,
                todayGas = gasValue,
                gasUnit = gasUnitStr,
            )
        }
    }

    /** Map the persisted excluded entity-id set to [ExcludedSensor] rows for
     *  the management sheet, attaching a friendly name from [knownNames] when
     *  one was seen, falling back to the entity id. Sorted by display name (case
     *  insensitive) so the list reads stably regardless of set iteration order. */
    private fun buildExcludedSensors(excluded: Set<String>): List<ExcludedSensor> =
        excluded
            .map { id -> ExcludedSensor(entityId = id, name = knownNames[id] ?: id) }
            .sortedBy { it.name.lowercase() }

    /**
     * Exclude [entityId] from every Energy aggregate. Persists the choice, then
     * refreshes so DRAW, PRODUCTION, the breakdown, and TOP CONSUMERS all
     * recompute without it. Reflecting the new excluded row immediately (before
     * the async render lands) keeps the management badge honest the instant the
     * user long-presses.
     */
    fun excludeSensor(entityId: String) {
        viewModelScope.launch {
            settings.excludeEnergySensor(entityId)
            val next = (_ui.value.excludedSensors.map { it.entityId } + entityId).toSet()
            _ui.value = _ui.value.copy(excludedSensors = buildExcludedSensors(next))
            refresh()
        }
    }

    /** Re-include a previously-excluded sensor: persist the removal, optimistically
     *  drop its row, then refresh so the aggregates fold it back in. */
    fun includeSensor(entityId: String) {
        viewModelScope.launch {
            settings.includeEnergySensor(entityId)
            val next = _ui.value.excludedSensors.map { it.entityId }.filterNot { it == entityId }.toSet()
            _ui.value = _ui.value.copy(excludedSensors = buildExcludedSensors(next))
            refresh()
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

    /**
     * REAL-TIME today (kWh): for every energy meter, fetch its raw state history
     * since local midnight and sum the rise in reading (see [EnergyTodayCalc]).
     * Runs each refresh in parallel with the templates; returns null when there
     * are no energy meters or every history fetch failed, so the caller keeps the
     * last value / falls back to the recorder figure. Per-meter fetches run
     * concurrently; the energy screen is foreground so the handful of history
     * calls is acceptable.
     */
    private suspend fun computeRealtimeTodayKwh(): Double? {
        val ids = ensureEnergyMeters()
        if (ids.isEmpty()) return null
        val now = Instant.now()
        val midnight = localMidnight(now)
        // History span to request: hours since midnight + a 2 h margin so the
        // pre-midnight baseline sample is included; clamped to a sane ceiling.
        val hours = ((now.epochSecond - midnight.epochSecond) / 3600 + 2)
            .toInt().coerceIn(1, 26)
        val pointsById = kotlinx.coroutines.coroutineScope {
            ids.map { id ->
                id to async {
                    haRepository.fetchHistory(
                        com.github.itskenny0.r1ha.core.ha.EntityId(id),
                        hours,
                    ).getOrNull().orEmpty()
                }
            }.associate { (id, job) -> id to job.await() }
        }
        return EnergyTodayCalc.todayKwh(
            pointsById = pointsById,
            unitById = energyStatisticUnits,
            midnight = midnight,
            unitToKwh = { unit -> energyUnitToKwh(unit) },
        )
    }

    /** Resolve (and cache) the energy-meter statistic ids + units from the recorder
     *  catalogue. Shared by the history chart and the real-time today computation;
     *  returns the empty list (and caches it) when the catalogue load fails. */
    private suspend fun ensureEnergyMeters(): List<String> {
        if (energyStatisticIds.isNotEmpty()) return energyStatisticIds
        val resolved = haRepository.listStatisticIds().fold(
            onSuccess = { rows ->
                energyStatisticUnits = rows.associate { it.statisticId to it.unitOfMeasurement }
                selectEnergyStatisticIds(rows)
            },
            onFailure = { emptyList() },
        )
        energyStatisticIds = resolved
        return resolved
    }

    /** Local midnight (start of today in the device timezone) as an Instant. */
    private fun localMidnight(now: Instant): Instant =
        now.atZone(ZoneId.systemDefault()).toLocalDate()
            .atStartOfDay(ZoneId.systemDefault()).toInstant()

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
        // The power-sensor aggregate templates (DRAW, PRODUCTION, TOP CONSUMERS)
        // moved to [EnergyTemplates] so each can be built with the user's manual
        // exclusion list spliced in as a safe rejectattr clause. The two templates
        // below stay here because they don't slice device_class=power and so carry
        // no exclusion (HAS_BATTERY_SOURCE is a presence probe; SUM_TODAY_KWH sums
        // device_class=energy meters, a separate domain the exclusion never touches).

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

        // ---- water / gas templates -------------------------------------------
        // UNVERIFIED OFFLINE: both templates mirror SUM_TODAY_KWH exactly, with
        // only the device_class filter changed (water / gas instead of energy).
        // The unit is read from the FIRST matching sensor via `| first` so the
        // value and unit always agree. If an install has mixed units (e.g. one
        // sensor in m3 and another in L), the values are summed raw and the unit
        // shown is that of the first sensor; the caller should note this in the
        // UI. An empty string is emitted when no matching sensors exist, which
        // the client treats as null (absent tile).

        /**
         * Sum every `device_class=water state_class=total_increasing` sensor.
         * Emits "value|unit" so a single template call returns both. An absent
         * install emits an empty string.
         *
         * UNVERIFIED OFFLINE: mirrors SUM_TODAY_KWH. Not tested against a live
         * HA with water meters.
         */
        private const val SUM_TODAY_WATER = "{%- set ws = states.sensor " +
            "| selectattr('attributes.device_class','eq','water') " +
            "| selectattr('attributes.state_class','eq','total_increasing') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| list -%}" +
            "{%- if ws -%}" +
            "{{ ws | map(attribute='state') | map('float',0) | sum | round(3) }}" +
            "|{{ ws | first | attr('attributes') | attr('unit_of_measurement') }}" +
            "{%- endif -%}"

        /**
         * Sum every `device_class=gas state_class=total_increasing` sensor.
         * Emits "value|unit". An absent install emits an empty string.
         *
         * UNVERIFIED OFFLINE: mirrors SUM_TODAY_KWH. Not tested against a live
         * HA with gas meters.
         */
        private const val SUM_TODAY_GAS = "{%- set gs = states.sensor " +
            "| selectattr('attributes.device_class','eq','gas') " +
            "| selectattr('attributes.state_class','eq','total_increasing') " +
            "| rejectattr('state','in',['unavailable','unknown','none']) " +
            "| list -%}" +
            "{%- if gs -%}" +
            "{{ gs | map(attribute='state') | map('float',0) | sum | round(3) }}" +
            "|{{ gs | first | attr('attributes') | attr('unit_of_measurement') }}" +
            "{%- endif -%}"

        /** Parse a "value|unit" pair returned by [SUM_TODAY_WATER] and
         *  [SUM_TODAY_GAS]. Returns null for either field when the raw string
         *  is blank (no sensors) or malformed. */
        internal fun parseValueUnit(raw: String?): Pair<Double?, String?> {
            if (raw.isNullOrBlank()) return null to null
            val parts = raw.trim().split("|", limit = 2)
            val value = parts.getOrNull(0)?.toDoubleOrNull()
            val unit = parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }
            return value to unit
        }

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

        fun factory(haRepository: HaRepository, settings: SettingsRepository) = viewModelFactory {
            initializer { EnergyViewModel(haRepository, settings) }
        }
    }
}
