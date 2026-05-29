package com.github.itskenny0.r1ha.feature.calendars

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Drives the cross-calendar Agenda surface: lists every `calendar.*` entity,
 * fetches each one's events over a selectable look-ahead window in parallel,
 * and merges them into a single chronological stream. Per-calendar visibility
 * toggles and the grouped/sorted rendering are pure functions in [Agenda].
 *
 * Backs entirely onto the existing [HaRepository] surface
 * ([listRawEntitiesByDomain] + [fetchCalendarEvents]); no new core/ha methods.
 */
class AgendaViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class CalendarRef(
        val entityId: String,
        val name: String,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val calendars: List<CalendarRef> = emptyList(),
        /** Calendar ids the user has hidden via the chip row. */
        val hidden: Set<String> = emptySet(),
        val entries: List<AgendaEntry> = emptyList(),
        val windowDays: Int = 14,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Toggle a calendar's visibility in the merged agenda. */
    fun toggleCalendar(entityId: String) {
        val cur = _ui.value
        val next = if (entityId in cur.hidden) cur.hidden - entityId else cur.hidden + entityId
        _ui.value = cur.copy(hidden = next)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val windowDays = settings.settings.first().integrations.calendarLookaheadDays
            haRepository.listRawEntitiesByDomain("calendar").fold(
                onSuccess = { rows ->
                    val refs = rows
                        .map { CalendarRef(entityId = it.entityId, name = it.friendlyName) }
                        .sortedBy { it.name.lowercase() }
                    val merged = fetchAllEvents(refs, windowDays)
                    R1Log.i(
                        "Agenda",
                        "loaded ${merged.size} events across ${refs.size} calendars",
                    )
                    _ui.value = _ui.value.copy(
                        loading = false,
                        calendars = refs,
                        entries = merged,
                        windowDays = windowDays,
                        // Drop hidden ids for calendars that no longer exist.
                        hidden = _ui.value.hidden.intersect(refs.map { it.entityId }.toSet()),
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Agenda", "calendar list failed: ${t.message}")
                    Toaster.error("Agenda load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Fetches every calendar's events for the window in parallel and flattens
     * them into [AgendaEntry] rows. A single calendar's fetch failing is
     * tolerated: it contributes no rows rather than failing the whole agenda
     * (one flaky integration shouldn't blank the screen).
     */
    private suspend fun fetchAllEvents(
        refs: List<CalendarRef>,
        windowDays: Int,
    ): List<AgendaEntry> = coroutineScope {
        refs.map { ref ->
            async {
                haRepository.fetchCalendarEvents(
                    entityId = ref.entityId,
                    fromDaysBack = 0,
                    toDaysAhead = windowDays,
                ).fold(
                    onSuccess = { events ->
                        events.map { AgendaEntry(ref.entityId, ref.name, it) }
                    },
                    onFailure = { t ->
                        R1Log.w("Agenda", "${ref.entityId} events failed: ${t.message}")
                        emptyList()
                    },
                )
            }
        }.awaitAll().flatten()
    }

    /** Visible calendar ids given the current hidden set (empty = all on). */
    fun visibleIds(): Set<String> =
        _ui.value.calendars.map { it.entityId }.filter { it !in _ui.value.hidden }.toSet()

    companion object {
        fun factory(haRepository: HaRepository, settings: SettingsRepository) = viewModelFactory {
            initializer { AgendaViewModel(haRepository, settings) }
        }
    }
}

/** Bridges to the pure [buildAgenda] using the current UI state + clock. */
fun AgendaViewModel.UiState.toDays(now: Instant = Instant.now()): List<AgendaDay> {
    val visible = calendars.map { it.entityId }.filter { it !in hidden }.toSet()
    return buildAgenda(entries = entries, now = now, visible = visible)
}
