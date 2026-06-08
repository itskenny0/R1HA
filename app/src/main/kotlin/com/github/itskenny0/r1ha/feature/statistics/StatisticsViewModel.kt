package com.github.itskenny0.r1ha.feature.statistics

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Drives the long-term statistics chart screen. Pulls the
 * `recorder/list_statistic_ids` catalogue once to populate the entity
 * picker, then re-fetches `recorder/statistics_during_period` whenever
 * the user changes statistic / window / period / aggregation.
 *
 * Aggregation is a UI-only knob: we always ask the recorder for every
 * column it has (mean / min / max / sum / state / change) and slice the
 * series client-side. Re-fetching on every chip flip would feel laggy
 * over a 30-day window where HA's reply can run into hundreds of
 * buckets.
 *
 * Two orthogonal selectors drive the fetch: [Window] picks how far back
 * to look, [Period] picks the recorder bucket resolution
 * (hour / day / week / month). Decoupling them mirrors HA's own
 * developer-tools statistics view, where you can ask for a month of data
 * bucketed by day, or a week bucketed by hour.
 */
class StatisticsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /** Time-window selector: how far back from "now" to fetch. */
    enum class Window(val label: String, val hours: Long) {
        H24("24H", 24L),
        D7("7D", 7L * 24L),
        D30("30D", 30L * 24L),
        D90("90D", 90L * 24L),
    }

    /** Recorder bucket resolution. These are the period strings HA's
     *  `statistics_during_period` accepts; coarser periods keep long
     *  windows readable, finer periods zoom into a recent window. */
    enum class Period(val label: String, val wire: String, val approxSeconds: Long) {
        FIVE_MIN("5MIN", "5minute", 300L),
        HOUR("HOUR", "hour", 3600L),
        DAY("DAY", "day", 86_400L),
        WEEK("WEEK", "week", 7L * 86_400L),
        MONTH("MONTH", "month", 30L * 86_400L),
    }

    /** How HA's recorder collects a statistic, which decides the natural
     *  series and summary. MEASUREMENT statistics (temperature, humidity)
     *  carry a mean with a min/max envelope. METERED statistics (energy,
     *  water, gas) carry a cumulative sum from which per-bucket change is
     *  derived; their natural reading is consumption over the window. */
    enum class StatKind { MEASUREMENT, METERED, NONE }

    /** Aggregation chips; only the ones the picked statistic actually
     *  supports are enabled. CHANGE is the per-bucket delta of SUM, useful
     *  for "kWh used per hour" rather than "total kWh ever". */
    enum class Aggregation(val label: String) {
        MEAN("MEAN"),
        MIN("MIN"),
        MAX("MAX"),
        SUM("SUM"),
        CHANGE("CHANGE"),
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        /** Catalogue load; drives the picker spinner. */
        val catalogueLoading: Boolean = true,
        val catalogueError: String? = null,
        val available: List<StatisticId> = emptyList(),
        /** Currently selected statistic, null until the user picks one
         *  (or the picker auto-restores a previous selection). */
        val selected: StatisticId? = null,
        /** True while the picker overlay is open. */
        val pickerOpen: Boolean = false,
        val window: Window = Window.H24,
        val period: Period = Period.HOUR,
        val aggregation: Aggregation = Aggregation.MEAN,
        /** Series load state; independent of catalogueLoading so flipping
         *  windows doesn't blank the picker. */
        val seriesLoading: Boolean = false,
        val seriesError: String? = null,
        val buckets: List<StatisticsBucket> = emptyList(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Project the buckets through the active aggregation chip. Skips
     *  buckets the recorder didn't fill in (HA omits the field per
     *  statistic-class so a temperature sensor's `sum` is always null). */
    fun seriesPoints(state: UiState = _ui.value): List<TimedValue> =
        seriesPoints(state.buckets, state.aggregation)

    /** True once a statistic is picked that the recorder tracks with
     *  neither a mean nor a sum, so no aggregation chip can ever plot it.
     *  HA does surface such ids (e.g. a `total` sensor whose state_class
     *  was reconfigured, or an integration that registered the id before
     *  populating any column). The chart would otherwise sit on a bare
     *  "NO STATISTICS" with no hint why every chip is inert. */
    fun hasNoPlottableAggregation(state: UiState = _ui.value): Boolean =
        classify(state.selected) == StatKind.NONE

    /** Which aggregation chips should be enabled for the current
     *  statistic. Falls back to "everything on" while nothing is selected
     *  so the chip row never collapses to a single chip. */
    fun supportedAggregations(state: UiState = _ui.value): Set<Aggregation> {
        val s = state.selected ?: return Aggregation.entries.toSet()
        return supportedAggregations(s)
    }

    /** Min/max envelope points for a MEASUREMENT statistic. Empty for
     *  metered statistics (no per-bucket spread) or when the recorder
     *  didn't fill min/max. Plotted as a faint band behind the mean line. */
    fun bandPoints(state: UiState = _ui.value): List<TimedBand> =
        if (classify(state.selected) == StatKind.MEASUREMENT) bandPoints(state.buckets) else emptyList()

    fun loadCatalogue() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(catalogueLoading = true, catalogueError = null)
            // recorder/list_statistic_ids returns name=null for entity-backed
            // series (the recorder doesn't carry friendly names), so fetch the
            // entity friendly-name map alongside the catalogue and fill it in.
            // Run in parallel so the picker isn't gated on two serial round trips;
            // a failed name fetch just leaves rows falling back to the id.
            val namesDeferred = async {
                haRepository.listAllEntitiesForSearch().getOrNull()
                    ?.associate { it.id.value to it.friendlyName }
                    .orEmpty()
            }
            haRepository.listStatisticIds().fold(
                onSuccess = { rows ->
                    val nameByEntity = namesDeferred.await()
                    val enriched = rows
                        .map { row ->
                            if (!row.name.isNullOrBlank()) {
                                row
                            } else {
                                // Entity-backed statistic_ids are the entity_id
                                // itself; resolve the display name from the entity.
                                nameByEntity[row.statisticId]
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let { row.copy(name = it) }
                                    ?: row
                            }
                        }
                        .sortedBy {
                            it.name?.lowercase()?.ifBlank { null } ?: it.statisticId.lowercase()
                        }
                    R1Log.i("Statistics", "catalogue size=${rows.size}")
                    _ui.value = _ui.value.copy(
                        catalogueLoading = false,
                        available = enriched,
                        catalogueError = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Statistics", "catalogue load failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        catalogueLoading = false,
                        catalogueError = t.message ?: "unknown",
                    )
                },
            )
        }
    }

    fun openPicker() {
        _ui.value = _ui.value.copy(pickerOpen = true)
    }

    fun closePicker() {
        _ui.value = _ui.value.copy(pickerOpen = false)
    }

    fun selectStatistic(id: StatisticId) {
        val supported = supportedAggregations(id)
        // Snap the aggregation chip to a supported one if the current pick
        // isn't valid for the new statistic. Flipping from a kWh meter
        // (sum-only) to a temperature sensor (mean-only) shouldn't leave
        // SUM highlighted while the chart paints nothing.
        val aggro = if (_ui.value.aggregation in supported) {
            _ui.value.aggregation
        } else {
            defaultAggregation(id) ?: supported.firstOrNull() ?: Aggregation.MEAN
        }
        _ui.value = _ui.value.copy(
            selected = id,
            pickerOpen = false,
            aggregation = aggro,
            buckets = emptyList(),
            seriesError = null,
        )
        refreshSeries()
    }

    fun setWindow(window: Window) {
        if (_ui.value.window == window) return
        // The recorder only retains 5-minute statistics for a short span (HA's
        // own UI never offers it beyond a recent window), so a 5MIN bucket over
        // 30/90 days would ask for tens of thousands of empty buckets. Snap the
        // period coarser when the user widens the window past where 5MIN is
        // useful, mirroring HA's period gating.
        val period = clampPeriodToWindow(_ui.value.period, window)
        _ui.value = _ui.value.copy(window = window, period = period)
        refreshSeries()
    }

    fun setPeriod(period: Period) {
        if (_ui.value.period == period) return
        _ui.value = _ui.value.copy(period = period)
        refreshSeries()
    }

    /** Whether a [period] is sensible for a [window]: 5-minute buckets are only
     *  offered for windows of a week or less, matching HA's recorder retention
     *  and its own period picker. */
    fun periodAllowedFor(period: Period, window: Window): Boolean =
        period != Period.FIVE_MIN || window.hours <= Window.D7.hours

    private fun clampPeriodToWindow(period: Period, window: Window): Period =
        if (periodAllowedFor(period, window)) period else Period.HOUR

    fun setAggregation(aggregation: Aggregation) {
        if (_ui.value.aggregation == aggregation) return
        _ui.value = _ui.value.copy(aggregation = aggregation)
    }

    fun refreshSeries() {
        val selected = _ui.value.selected ?: return
        val window = _ui.value.window
        val period = _ui.value.period
        viewModelScope.launch {
            _ui.value = _ui.value.copy(seriesLoading = true, seriesError = null)
            val end = Instant.now()
            val start = end.minusSeconds(window.hours * 3600L)
            haRepository.getStatisticsDuringPeriod(
                statisticIds = listOf(selected.statisticId),
                start = start,
                end = end,
                period = period.wire,
            ).fold(
                onSuccess = { byId ->
                    val buckets = byId[selected.statisticId].orEmpty()
                    R1Log.i(
                        "Statistics",
                        "${selected.statisticId} window=${window.label} period=${period.wire} " +
                            "buckets=${buckets.size}",
                    )
                    _ui.value = _ui.value.copy(
                        seriesLoading = false,
                        buckets = buckets,
                        seriesError = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Statistics", "${selected.statisticId} fetch failed: ${t.message}")
                    Toaster.error("Statistics load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        seriesLoading = false,
                        seriesError = t.message,
                    )
                },
            )
        }
    }

    /** Lightweight (timestamp, value) pair the chart projects after the
     *  aggregation chip selects a series column. */
    data class TimedValue(val timestamp: Instant, val value: Double)

    /** Min/max envelope sample for a measurement statistic's band. */
    data class TimedBand(val timestamp: Instant, val min: Double, val max: Double)

    /** Type-aware window summary. For METERED statistics the headline is
     *  the window total (sum of per-bucket change); for MEASUREMENT
     *  statistics the headline is the window average alongside min/max of
     *  the selected series. [count] is the number of plotted points. */
    data class WindowSummary(
        val kind: StatKind,
        val current: Double?,
        val min: Double?,
        val max: Double?,
        val avg: Double?,
        /** Window total (metered consumption); null for measurements. */
        val total: Double?,
        val count: Int,
    )

    fun windowSummary(state: UiState = _ui.value): WindowSummary =
        windowSummary(
            kind = summaryKind(state.selected, state.aggregation),
            buckets = state.buckets,
            points = seriesPoints(state),
        )

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { StatisticsViewModel(haRepository) }
        }
    }
}

/**
 * Pure helpers backing the statistics screen. Kept top-level (no
 * ViewModel / coroutine state) so they unit-test as plain functions.
 */

/** Classify a statistic by which recorder columns it carries. A series
 *  with a mean is a measurement (temperature, humidity, power reading);
 *  one with only a sum is metered (energy, water, gas). A series with
 *  both (rare) is treated as measurement since its mean is the more
 *  natural default to chart. Neither column means nothing is plottable. */
fun classify(id: StatisticId?): StatisticsViewModel.StatKind = when {
    id == null -> StatisticsViewModel.StatKind.NONE
    id.hasMean -> StatisticsViewModel.StatKind.MEASUREMENT
    id.hasSum -> StatisticsViewModel.StatKind.METERED
    else -> StatisticsViewModel.StatKind.NONE
}

/** Effective summary kind for the headline rows: a statistic that carries
 *  both a mean and a sum (e.g. a power sensor that also totals energy) is
 *  classified MEASUREMENT for its default chart, but while the user views a
 *  cumulative SUM or per-bucket CHANGE series the measurement headline (avg /
 *  min / max of a cumulative reading) is meaningless. Treat the summary as
 *  METERED in that case so it shows total / per-bucket / peak instead. The
 *  per-statistic columns still gate which aggregation chips exist, so a
 *  pure-measurement series can never reach this branch. */
fun summaryKind(
    id: StatisticId?,
    aggregation: StatisticsViewModel.Aggregation,
): StatisticsViewModel.StatKind {
    val base = classify(id)
    val viewingSumSeries = aggregation == StatisticsViewModel.Aggregation.SUM ||
        aggregation == StatisticsViewModel.Aggregation.CHANGE
    return if (base == StatisticsViewModel.StatKind.MEASUREMENT && viewingSumSeries) {
        StatisticsViewModel.StatKind.METERED
    } else {
        base
    }
}

/** Which aggregation chips a statistic supports, derived from its
 *  recorder columns. */
fun supportedAggregations(id: StatisticId): Set<StatisticsViewModel.Aggregation> {
    val out = mutableSetOf<StatisticsViewModel.Aggregation>()
    if (id.hasMean) {
        out += StatisticsViewModel.Aggregation.MEAN
        out += StatisticsViewModel.Aggregation.MIN
        out += StatisticsViewModel.Aggregation.MAX
    }
    if (id.hasSum) {
        out += StatisticsViewModel.Aggregation.SUM
        out += StatisticsViewModel.Aggregation.CHANGE
    }
    return out
}

/** The aggregation to land on when a statistic is first picked: MEAN for
 *  measurements, CHANGE for metered (per-bucket consumption is the useful
 *  default rather than the ever-growing cumulative sum). Null when the
 *  statistic plots nothing. */
fun defaultAggregation(id: StatisticId): StatisticsViewModel.Aggregation? = when (classify(id)) {
    StatisticsViewModel.StatKind.MEASUREMENT -> StatisticsViewModel.Aggregation.MEAN
    StatisticsViewModel.StatKind.METERED -> StatisticsViewModel.Aggregation.CHANGE
    StatisticsViewModel.StatKind.NONE -> null
}

/** Project buckets onto the selected aggregation column, dropping buckets
 *  the recorder left empty or non-finite. */
fun seriesPoints(
    buckets: List<StatisticsBucket>,
    aggregation: StatisticsViewModel.Aggregation,
): List<StatisticsViewModel.TimedValue> = buckets.mapNotNull { b ->
    val v = when (aggregation) {
        StatisticsViewModel.Aggregation.MEAN -> b.mean
        StatisticsViewModel.Aggregation.MIN -> b.min
        StatisticsViewModel.Aggregation.MAX -> b.max
        StatisticsViewModel.Aggregation.SUM -> b.sum
        StatisticsViewModel.Aggregation.CHANGE -> b.change
    }?.takeIf { it.isFinite() } ?: return@mapNotNull null
    StatisticsViewModel.TimedValue(b.start, v)
}

/** Min/max envelope for the measurement band: keeps only buckets that
 *  carry both finite min and max, ordered by bucket start. */
fun bandPoints(buckets: List<StatisticsBucket>): List<StatisticsViewModel.TimedBand> =
    buckets.mapNotNull { b ->
        val lo = b.min?.takeIf { it.isFinite() } ?: return@mapNotNull null
        val hi = b.max?.takeIf { it.isFinite() } ?: return@mapNotNull null
        // Guard against a recorder hiccup that swaps the pair.
        StatisticsViewModel.TimedBand(b.start, minOf(lo, hi), maxOf(lo, hi))
    }

/** Sum of finite per-bucket `change` values: the consumption over the
 *  window for a metered statistic. Null when no bucket carries a change. */
fun windowTotal(buckets: List<StatisticsBucket>): Double? {
    var sum = 0.0
    var any = false
    for (b in buckets) {
        val c = b.change ?: continue
        if (!c.isFinite()) continue
        sum += c
        any = true
    }
    return if (any) sum else null
}

/** Build the type-aware window summary. Measurement statistics headline avg
 *  with min/max of the plotted series. Metered statistics headline the window
 *  total (consumption) with per-bucket and peak consumption derived from the
 *  recorder's per-bucket `change`, not the plotted column: when the user is
 *  viewing the cumulative SUM series, the min/max/avg of those ever-growing
 *  readings would be meaningless, whereas the consumption stays correct. */
fun windowSummary(
    kind: StatisticsViewModel.StatKind,
    buckets: List<StatisticsBucket>,
    points: List<StatisticsViewModel.TimedValue>,
): StatisticsViewModel.WindowSummary {
    if (kind == StatisticsViewModel.StatKind.METERED) {
        val changes = buckets.mapNotNull { it.change?.takeIf { c -> c.isFinite() } }
        return StatisticsViewModel.WindowSummary(
            kind = kind,
            current = points.lastOrNull()?.value,
            min = changes.minOrNull(),
            max = changes.maxOrNull(),
            avg = if (changes.isNotEmpty()) changes.sum() / changes.size else null,
            total = if (changes.isNotEmpty()) changes.sum() else null,
            count = points.size,
        )
    }
    val values = points.map { it.value }
    return StatisticsViewModel.WindowSummary(
        kind = kind,
        current = values.lastOrNull(),
        min = values.minOrNull(),
        max = values.maxOrNull(),
        avg = if (values.isNotEmpty()) values.sum() / values.size else null,
        total = null,
        count = points.size,
    )
}

/** Drop unhelpful trailing decimals: 23.0 -> "23", 23.45 -> "23.45".
 *  Mirrors HistoryScreen's formatter so the surfaces print identically. A value that
 *  rounds to zero from below (-0.002 -> "-0.00") is emitted as "0", not "-0.00": "%f"
 *  keeps the sign even when the magnitude rounds away, and "-0" reads as a glitch. */
fun formatStatNum(v: Double): String {
    val s = if (kotlin.math.abs(v - v.toLong()) < 1e-9) {
        "${v.toLong()}"
    } else {
        java.lang.String.format(java.util.Locale.US, "%.2f", v)
    }
    // "-0.00" -> "0" (not "0.00": this formatter keeps two fixed decimals, so dropping
    // only the sign would leave a bare ".00"; collapse a zero magnitude to plain "0").
    val collapsed = if (s.startsWith("-") && s.drop(1).all { it == '0' || it == '.' }) "0" else s
    // Group thousands so a large total ("12345" kWh) reads the same here as on the sensor
    // cards; the shared helper only groups 5+ integer digits, leaving years / short codes.
    return com.github.itskenny0.r1ha.ui.components.groupThousands(collapsed)
}
