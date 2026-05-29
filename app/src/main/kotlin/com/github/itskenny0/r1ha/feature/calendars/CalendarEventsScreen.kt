package com.github.itskenny0.r1ha.feature.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Calendar drill-down — fetches the next ~2 weeks of events for a
 * single calendar entity via HA's `/api/calendars/<id>?start=&end=`
 * endpoint and renders them as a chronological list.
 *
 * Unlike the parent CalendarsScreen which only shows the next event
 * from each calendar's attributes, this drill-down lists every event
 * in the window — useful for "what's on my agenda this week".
 */
class CalendarEventsViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    private val entityId: String,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val events: List<CalendarEvent> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val lookahead = settings.settings.first().integrations.calendarLookaheadDays
            haRepository.fetchCalendarEvents(entityId, fromDaysBack = 0, toDaysAhead = lookahead).fold(
                onSuccess = { events ->
                    R1Log.i("CalendarEvents", "$entityId loaded ${events.size}")
                    _ui.value = _ui.value.copy(loading = false, events = events, error = null)
                },
                onFailure = { t ->
                    R1Log.w("CalendarEvents", "$entityId failed: ${t.message}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
            entityId: String,
        ) = viewModelFactory {
            initializer { CalendarEventsViewModel(haRepository, settings, entityId) }
        }
    }
}

@Composable
fun CalendarEventsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    entityId: String,
    calendarName: String,
    onBack: () -> Unit,
) {
    val vm: CalendarEventsViewModel = viewModel(
        key = entityId,
        factory = CalendarEventsViewModel.factory(haRepository, settings, entityId),
    )
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(entityId) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = calendarName.uppercase().take(20), onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.events.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = R1.body, color = R1.StatusRed)
                }
                ui.events.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No events in the next 14 days.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = R1.space.m, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        val now = Instant.now()
                        // Key includes the list index: summary+start alone collides for
                        // repeated-title events that share (or both lack) a start time,
                        // e.g. two all-day events whose start parses to null both keyed
                        // "Lunch|null" — duplicate LazyColumn keys throw at composition.
                        // The index keeps every row distinct while preserving stability
                        // within a single loaded window.
                        itemsIndexed(
                            items = ui.events,
                            key = { index, e -> "$index|${e.summary}|${e.start?.toEpochMilli()}" },
                        ) { _, e ->
                            EventRow(e, isHappeningNow = e.start != null && e.end != null &&
                                now.isAfter(e.start) && now.isBefore(e.end))
                        }
                    }
                }
            }
        } // AdaptiveContent
    }
}

@Composable
private fun EventRow(e: CalendarEvent, isHappeningNow: Boolean) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s)
            .clearAndSetSemantics {
                contentDescription = eventRowDescription(e, happeningNow = isHappeningNow)
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isHappeningNow) {
                R1Chip(text = "NOW", variant = R1ChipVariant.Pill, tone = R1.AccentGreen)
                Spacer(Modifier.width(R1.space.s))
            }
            if (e.allDay) {
                R1Chip(text = "ALL-DAY", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
                Spacer(Modifier.width(R1.space.s))
            }
            Text(text = e.summary, style = R1.bodyEmph, color = R1.Ink, maxLines = 2, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(R1.space.s))
            RelativeTimeLabel(at = e.start, color = R1.InkMuted, style = R1.labelMicro)
        }
        if (!e.location.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(text = "@ ${e.location}", style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
        }
        if (!e.description.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(text = e.description, style = R1.labelMicro, color = R1.InkMuted, maxLines = 3)
        }
    }
}
