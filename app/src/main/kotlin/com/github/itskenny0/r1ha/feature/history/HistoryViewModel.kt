package com.github.itskenny0.r1ha.feature.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the History drill-in surface — a full-screen view of one
 * entity's recent state-change history, fetched from HA's
 * `/api/history/period/...` REST endpoint.
 *
 * The card stack's per-entity sparkline gives a 72-dp glimpse at 24 h
 * of history; this VM backs the much larger view a user gets when
 * they explicitly drill into an entity to investigate it — bigger
 * chart, configurable time window (1 h / 6 h / 24 h / 7 d), and
 * numerical readouts of min/max/avg/current.
 *
 * Multi-entity overlay: the screen opens on a single "primary" entity
 * (series[0]) — the existing drill-in path. The user can ADD up to
 * [MAX_SERIES] - 1 further numeric entities, each fetched over the
 * same window and overlaid on the same chart with its own accent.
 * Single-entity entry stays unchanged: with one series the chart, the
 * summary, and the rewind panels behave exactly as before.
 */
class HistoryViewModel(
    private val haRepository: HaRepository,
    private val primaryEntityId: EntityId,
) : ViewModel() {

    /** Time-window selector — the chips at the top of the screen flip
     *  between these. Each value is the number of hours to pull from
     *  HA's history endpoint. */
    enum class Window(val hours: Int, val label: String) {
        H1(1, "1H"),
        H6(6, "6H"),
        H24(24, "24H"),
        D7(7 * 24, "7D"),
    }

    /**
     * One overlaid entity's loaded history plus its derived numeric
     * summary. `colorIndex` is a stable 0-based slot into the screen's
     * accent palette so a series keeps its colour as others are added
     * or removed in front of it.
     */
    @androidx.compose.runtime.Stable
    data class Series(
        val entityId: EntityId,
        val colorIndex: Int,
        val points: List<HistoryPoint> = emptyList(),
        val displayName: String = entityId.value,
        val unit: String? = null,
        val min: Double? = null,
        val max: Double? = null,
        val avg: Double? = null,
        val current: String? = null,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val window: Window = Window.H24,
        /** Ordered series; [0] is always the primary drill-in entity. */
        val series: List<Series> = emptyList(),
        val error: String? = null,
        /** entity_id of the primary series — used as the title fallback
         *  before the first fetch resolves a friendly name. */
        val primaryEntityIdValue: String = "",
    ) {
        /** True once the overlay cap is reached — the ADD affordance
         *  surfaces this so the user knows why it's disabled. */
        val atCap: Boolean get() = series.size >= MAX_SERIES

        // --- Single-series convenience accessors -------------------
        // Keep the original UiState surface so the summary / rewind /
        // single-line render paths read unchanged when only the
        // primary entity is loaded.
        val primary: Series? get() = series.firstOrNull()
        val points: List<HistoryPoint> get() = primary?.points.orEmpty()
        val displayName: String get() = primary?.displayName ?: primaryEntityIdValue
        val unit: String? get() = primary?.unit
        val min: Double? get() = primary?.min
        val max: Double? get() = primary?.max
        val avg: Double? get() = primary?.avg
        val current: String? get() = primary?.current
        val isOverlay: Boolean get() = series.size > 1
    }

    private val _ui = MutableStateFlow(
        UiState(
            series = listOf(Series(entityId = primaryEntityId, colorIndex = 0)),
            primaryEntityIdValue = primaryEntityId.value,
        ),
    )
    val ui: StateFlow<UiState> = _ui

    fun setWindow(w: Window) {
        if (_ui.value.window == w) return
        _ui.value = _ui.value.copy(window = w)
        refresh()
    }

    /**
     * Add a further entity to the overlay. No-ops when the cap is hit
     * or the entity is already present (the primary included). The new
     * series gets the lowest free colour slot so colours stay distinct
     * and stable across removals. Fetches just the new series over the
     * current window rather than re-pulling everything.
     */
    fun addEntity(entityId: EntityId) {
        val current = _ui.value
        if (current.atCap) {
            Toaster.error("Overlay limit is $MAX_SERIES entities")
            return
        }
        if (current.series.any { it.entityId == entityId }) return
        val used = current.series.map { it.colorIndex }.toSet()
        val slot = (0 until MAX_SERIES).first { it !in used }
        val series = Series(entityId = entityId, colorIndex = slot)
        _ui.value = current.copy(series = current.series + series)
        fetchSeries(entityId)
    }

    /** Remove an overlaid entity. The primary (index 0) can't be
     *  removed — the screen always shows at least its origin entity. */
    fun removeEntity(entityId: EntityId) {
        val current = _ui.value
        if (entityId == primaryEntityId) return
        _ui.value = current.copy(series = current.series.filterNot { it.entityId == entityId })
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val w = _ui.value.window
            val ids = _ui.value.series.map { it.entityId }
            // Fetch all overlaid series in parallel over the shared window.
            val results = ids.map { id ->
                async { id to haRepository.fetchHistory(id, hours = w.hours) }
            }.awaitAll()
            // Pull friendly names + units once from the live registry; the
            // history API omits attributes.
            val live = haRepository.listAllEntities().getOrNull().orEmpty()
            var firstError: Throwable? = null
            val updated = _ui.value.series.map { existing ->
                val res = results.firstOrNull { it.first == existing.entityId }?.second
                res?.fold(
                    onSuccess = { points ->
                        val meta = live.firstOrNull { it.id == existing.entityId }
                        summarize(existing, points, meta?.friendlyName, meta?.unit)
                    },
                    onFailure = { t ->
                        if (firstError == null) firstError = t
                        R1Log.w("History", "${existing.entityId.value} fetch failed: ${t.message}")
                        existing
                    },
                ) ?: existing
            }
            firstError?.let { Toaster.error("History load failed: ${it.message ?: "unknown"}") }
            R1Log.i(
                "History",
                "window=${w.label} series=${updated.size} " +
                    updated.joinToString(",") { "${it.entityId.value}:${it.points.size}" },
            )
            _ui.value = _ui.value.copy(
                loading = false,
                series = updated,
                error = firstError?.message,
            )
        }
    }

    /** Fetch a single newly-added series without disturbing the others. */
    private fun fetchSeries(entityId: EntityId) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true)
            val w = _ui.value.window
            val res = haRepository.fetchHistory(entityId, hours = w.hours)
            val live = haRepository.listAllEntities().getOrNull().orEmpty()
            res.fold(
                onSuccess = { points ->
                    val meta = live.firstOrNull { it.id == entityId }
                    val updated = _ui.value.series.map { s ->
                        if (s.entityId == entityId) summarize(s, points, meta?.friendlyName, meta?.unit) else s
                    }
                    _ui.value = _ui.value.copy(loading = false, series = updated)
                },
                onFailure = { t ->
                    R1Log.w("History", "${entityId.value} add-fetch failed: ${t.message}")
                    Toaster.error("History load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false)
                },
            )
        }
    }

    private fun summarize(
        base: Series,
        points: List<HistoryPoint>,
        friendlyName: String?,
        unit: String?,
    ): Series {
        val numeric = points.mapNotNull { it.numeric }
        return base.copy(
            points = points,
            displayName = friendlyName ?: base.entityId.value,
            unit = unit,
            min = numeric.minOrNull(),
            max = numeric.maxOrNull(),
            avg = if (numeric.isNotEmpty()) numeric.sum() / numeric.size else null,
            current = points.lastOrNull()?.state,
        )
    }

    companion object {
        /** Hard cap on overlaid entities. Keeps the chart legible (one
         *  legend row per series) and bounds the parallel fetch fan-out. */
        const val MAX_SERIES = 5

        fun factory(haRepository: HaRepository, entityId: EntityId) = viewModelFactory {
            initializer { HistoryViewModel(haRepository, entityId) }
        }
    }
}
