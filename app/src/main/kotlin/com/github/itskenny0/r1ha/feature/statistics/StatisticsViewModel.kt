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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Drives the long-term statistics chart screen. Pulls the
 * `recorder/list_statistic_ids` catalogue once to populate the entity
 * picker, then re-fetches `recorder/statistics_during_period` whenever
 * the user changes statistic / window / aggregation.
 *
 * Aggregation is a UI-only knob: we always ask the recorder for every
 * column it has (mean / min / max / sum / state / change) and slice the
 * series client-side. Re-fetching on every chip flip would feel laggy
 * over a 30-day window where HA's reply can run into hundreds of
 * buckets.
 */
class StatisticsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /** Time-window selector; each chip picks both a span and a bucket
     *  resolution. The defaults follow HA's own statistics card: short
     *  windows zoom into 5-minute buckets, week+ windows aggregate so
     *  the chart stays readable. */
    enum class Window(val label: String, val hours: Long, val period: String) {
        H1("1H", 1L, "5minute"),
        H24("24H", 24L, "hour"),
        D7("7D", 7L * 24L, "hour"),
        D30("30D", 30L * 24L, "day"),
    }

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
        state.buckets.mapNotNull { b ->
            val v = when (state.aggregation) {
                Aggregation.MEAN -> b.mean
                Aggregation.MIN -> b.min
                Aggregation.MAX -> b.max
                Aggregation.SUM -> b.sum
                Aggregation.CHANGE -> b.change
            }?.takeIf { it.isFinite() } ?: return@mapNotNull null
            TimedValue(b.start, v)
        }

    /** True once a statistic is picked that the recorder tracks with
     *  neither a mean nor a sum, so no aggregation chip can ever plot it.
     *  HA does surface such ids (e.g. a `total` sensor whose state_class
     *  was reconfigured, or an integration that registered the id before
     *  populating any column). The chart would otherwise sit on a bare
     *  "NO STATISTICS" with no hint why every chip is inert. */
    fun hasNoPlottableAggregation(state: UiState = _ui.value): Boolean {
        val s = state.selected ?: return false
        return !s.hasMean && !s.hasSum
    }

    /** Which aggregation chips should be enabled for the current
     *  statistic. Falls back to "everything on" while nothing is selected
     *  so the chip row never collapses to a single chip. */
    fun supportedAggregations(state: UiState = _ui.value): Set<Aggregation> {
        val s = state.selected ?: return Aggregation.entries.toSet()
        val out = mutableSetOf<Aggregation>()
        if (s.hasMean) {
            out += Aggregation.MEAN
            out += Aggregation.MIN
            out += Aggregation.MAX
        }
        if (s.hasSum) {
            out += Aggregation.SUM
            out += Aggregation.CHANGE
        }
        return out
    }

    fun loadCatalogue() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(catalogueLoading = true, catalogueError = null)
            haRepository.listStatisticIds().fold(
                onSuccess = { rows ->
                    R1Log.i("Statistics", "catalogue size=${rows.size}")
                    _ui.value = _ui.value.copy(
                        catalogueLoading = false,
                        available = rows,
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
        val supported = run {
            val out = mutableSetOf<Aggregation>()
            if (id.hasMean) {
                out += Aggregation.MEAN
                out += Aggregation.MIN
                out += Aggregation.MAX
            }
            if (id.hasSum) {
                out += Aggregation.SUM
                out += Aggregation.CHANGE
            }
            out
        }
        // Snap the aggregation chip to a supported one if the current pick
        // isn't valid for the new statistic. Flipping from a kWh meter
        // (sum-only) to a temperature sensor (mean-only) shouldn't leave
        // SUM highlighted while the chart paints nothing.
        val aggro = if (_ui.value.aggregation in supported) {
            _ui.value.aggregation
        } else {
            supported.firstOrNull() ?: Aggregation.MEAN
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
        _ui.value = _ui.value.copy(window = window)
        refreshSeries()
    }

    fun setAggregation(aggregation: Aggregation) {
        if (_ui.value.aggregation == aggregation) return
        _ui.value = _ui.value.copy(aggregation = aggregation)
    }

    fun refreshSeries() {
        val selected = _ui.value.selected ?: return
        val window = _ui.value.window
        viewModelScope.launch {
            _ui.value = _ui.value.copy(seriesLoading = true, seriesError = null)
            val end = Instant.now()
            val start = end.minusSeconds(window.hours * 3600L)
            haRepository.getStatisticsDuringPeriod(
                statisticIds = listOf(selected.statisticId),
                start = start,
                end = end,
                period = window.period,
            ).fold(
                onSuccess = { byId ->
                    val buckets = byId[selected.statisticId].orEmpty()
                    R1Log.i(
                        "Statistics",
                        "${selected.statisticId} window=${window.label} period=${window.period} " +
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

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { StatisticsViewModel(haRepository) }
        }
    }
}
