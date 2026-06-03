package com.github.itskenny0.r1ha.feature.calendars

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.R1CenteredContent
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import androidx.compose.material3.Icon
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * A `now` instant that re-emits roughly once a minute so countdowns ("IN 5M"),
 * the NOW pill, and the TODAY/ongoing grouping stay live instead of freezing
 * at the value captured when the screen first composed. One minute is fine: the
 * coarsest unit any calendar hint renders is the minute.
 */
@Composable
internal fun rememberTickingNow(): State<Instant> = produceState(initialValue = Instant.now()) {
    while (true) {
        value = Instant.now()
        delay(60_000L)
    }
}

/**
 * Cross-calendar Agenda: merges upcoming events from every visible
 * `calendar.*` entity into one chronological stream, grouped by day
 * (TODAY / TOMORROW / absolute date) and sorted by start time. A chip row
 * toggles per-calendar visibility; each calendar carries a stable accent
 * colour ([accentForCalendar]) so its events read as a set.
 *
 * Complements the per-calendar [CalendarsScreen] / [CalendarEventsScreen]
 * drill-down: this is the "everything on my plate this week" view.
 */
@Composable
fun AgendaScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: AgendaViewModel = viewModel(factory = AgendaViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(initial = AppSettings())
    val refreshSec = appSettings.integrations.calendarsRefreshSec
    if (refreshSec > 0) {
        AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "AGENDA", onBack = onBack)
        R1CenteredContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Agenda load failed: ${ui.error}",
                        style = responsiveType(R1.body),
                        color = R1.StatusRed,
                    )
                }
                else -> AgendaBody(ui = ui, listState = listState, onToggle = vm::toggleCalendar, onRefresh = vm::refresh)
            }
        } // R1CenteredContent
    }
}

@Composable
private fun AgendaBody(
    ui: AgendaViewModel.UiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (String) -> Unit,
    onRefresh: () -> Unit,
) {
    val now by rememberTickingNow()
    val days = ui.toDays(now)
    Column(modifier = Modifier.fillMaxSize()) {
        if (ui.calendars.size > 1) {
            CalendarChipRow(ui = ui, onToggle = onToggle)
        }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = onRefresh,
            modifier = Modifier.fillMaxSize(),
        ) {
            if (days.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                    contentAlignment = Alignment.Center,
                ) {
                    val msg = if (ui.calendars.isEmpty()) {
                        "No calendar entities in HA. Add a calendar integration to see them here."
                    } else {
                        "No upcoming events in the next ${ui.windowDays} days."
                    }
                    Text(text = msg, style = responsiveType(R1.body), color = R1.InkMuted)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.m, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    days.forEach { day ->
                        item(key = "header-${day.date}") {
                            DayHeader(day.header, day.entries.size)
                        }
                        items(
                            count = day.entries.size,
                            key = { idx ->
                                val e = day.entries[idx]
                                "${day.date}|$idx|${e.calendarId}|${e.event.summary}"
                            },
                        ) { idx ->
                            AgendaRow(entry = day.entries[idx], now = now)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarChipRow(ui: AgendaViewModel.UiState, onToggle: (String) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        ui.calendars.forEach { cal ->
            val accent = accentForCalendar(cal.entityId)
            val selected = cal.entityId !in ui.hidden
            R1Chip(
                text = ellipsize(cal.name, 16),
                variant = R1ChipVariant.Filter,
                selected = selected,
                tone = accent,
                onClick = { onToggle(cal.entityId) },
                leadingContent = { CalendarDot(accent) },
                // Selection state is spoken, not just shown by the accent dot.
                contentDescription = calendarToggleDescription(cal.name, selected),
            )
        }
    }
}

@Composable
private fun CalendarDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(R1.ShapeRound)
            .background(color),
    )
}

@Composable
private fun DayHeader(header: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = R1.space.s, bottom = R1.space.xxs)
            .semantics(mergeDescendants = true) { heading() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading calendar glyph anchoring each day section; decorative, the
        // heading semantics carry the spoken header.
        Icon(
            imageVector = R1Icons.forDomain("calendar"),
            contentDescription = null,
            tint = R1.AccentWarm,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = header,
            style = responsiveType(R1.sectionHeader),
            color = R1.AccentWarm,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(R1.space.s))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(text = count.toString(), style = R1.labelMicro, color = R1.InkMuted)
    }
}

@Composable
private fun AgendaRow(entry: AgendaEntry, now: Instant) {
    val accent = accentForCalendar(entry.calendarId)
    val event = entry.event
    val allDay = isAllDay(event)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s)
            .clearAndSetSemantics {
                contentDescription = agendaRowDescription(event, entry.calendarName, now)
            },
    ) {
        // Accent rail: the per-calendar colour, so events read as a set.
        Box(
            modifier = Modifier
                .width(3.dp)
                .heightIn(min = R1.space.xl)
                .clip(R1.ShapeS)
                .background(accent),
        )
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (allDay) {
                    R1Chip(text = "ALL-DAY", variant = R1ChipVariant.Pill, tone = accent)
                    Spacer(Modifier.width(R1.space.s))
                }
                Text(
                    text = event.summary,
                    style = responsiveType(R1.bodyEmph),
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                val hint = relativeStartHint(event, now)
                if (hint.isNotEmpty()) {
                    Spacer(Modifier.width(R1.space.s))
                    val tone = if (hint == "NOW") R1.AccentGreen else R1.InkMuted
                    Text(text = hint, style = R1.labelMicro, color = tone)
                }
            }
            Spacer(Modifier.size(R1.space.xxs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatEventTime(event),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = entry.calendarName,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            if (!event.location.isNullOrBlank()) {
                Spacer(Modifier.size(R1.space.xxs))
                Text(
                    text = "@ ${event.location}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            if (!event.description.isNullOrBlank()) {
                Spacer(Modifier.size(R1.space.xxs))
                Text(
                    text = event.description,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
