package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.calendars.AgendaEntry
import com.github.itskenny0.r1ha.feature.calendars.accentForCalendar
import com.github.itskenny0.r1ha.feature.calendars.buildAgenda
import com.github.itskenny0.r1ha.feature.calendars.formatEventTime
import com.github.itskenny0.r1ha.feature.calendars.isAllDay
import com.github.itskenny0.r1ha.feature.calendars.isHappeningNow
import java.time.Instant
import java.time.ZoneId

/** Max days ahead to fetch calendar events for the card. */
private const val CALENDAR_CARD_DAYS_AHEAD = 7

/** Max event rows shown in a card. Long lists belong on the native Calendars screen. */
private const val MAX_CALENDAR_ROWS = 10

/**
 * Renderer for HA's `calendar` card. Displays a compact agenda list (date-grouped
 * event rows) for one or more calendar entities. A full month grid is not practical
 * at 640x480; the agenda view is the right R1-native choice regardless of
 * [LovelaceCard.Calendar.initialView].
 *
 * Events are fetched from the same [HaRepository.fetchCalendarEvents] path used
 * by the native Calendars feature. Each calendar gets a stable accent derived from
 * its entity id hash (via [accentForCalendar]), providing the same per-calendar
 * tinting as the native agenda.
 */
@Composable
fun CalendarCard(
    card: LovelaceCard.Calendar,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    var entries by remember(card.entityIds) { mutableStateOf<List<AgendaEntry>?>(null) }

    LaunchedEffect(card.entityIds) {
        if (repo == null) {
            entries = emptyList()
            return@LaunchedEffect
        }
        val collected = mutableListOf<AgendaEntry>()
        for (entityId in card.entityIds) {
            repo.fetchCalendarEvents(entityId, fromDaysBack = 0, toDaysAhead = CALENDAR_CARD_DAYS_AHEAD)
                .onSuccess { events ->
                    events.forEach { event ->
                        collected.add(AgendaEntry(calendarId = entityId, calendarName = entityId, event = event))
                    }
                }
        }
        entries = collected
    }

    val titleText = card.title?.takeUnless { it.isBlank() }
        ?: if (card.entityIds.size == 1) card.entityIds.first()
            .substringAfter('.').replace('_', ' ')
            .replaceFirstChar { it.uppercase() }
        else "Calendar"

    CardSurface(modifier = modifier, title = titleText) {
        val now = Instant.now()
        when {
            repo == null -> EmptyRow(text = "Calendar unavailable")
            entries == null -> EmptyRow(text = "Loading...")
            else -> {
                val days = buildAgenda(entries!!, now, emptySet(), ZoneId.systemDefault())
                if (days.isEmpty()) {
                    EmptyRow(text = "No upcoming events")
                } else {
                    var rowCount = 0
                    for (day in days) {
                        if (rowCount >= MAX_CALENDAR_ROWS) break
                        // Day header
                        Text(
                            text = day.header,
                            style = R1.sectionHeader,
                            color = R1.InkSoft,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                        )
                        for (entry in day.entries) {
                            if (rowCount >= MAX_CALENDAR_ROWS) break
                            if (rowCount > 0 || day.entries.first() != entry) {
                                CalendarDivider()
                            }
                            CalendarEventRow(entry = entry, now = now)
                            rowCount++
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CalendarEventRow(entry: AgendaEntry, now: Instant) {
    val event = entry.event
    val accent = accentForCalendar(entry.calendarId)
    val timeText = if (isAllDay(event)) "ALL DAY" else formatEventTime(event, ZoneId.systemDefault())
    val isNow = isHappeningNow(event, now)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Calendar accent bar
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(36.dp)
                .clip(R1.ShapeS)
                .background(accent),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = event.summary.ifBlank { "Untitled event" },
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isNow) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "NOW",
                        style = R1.labelMicro,
                        color = R1.AccentGreen,
                    )
                }
            }
            Text(
                text = timeText,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!event.location.isNullOrBlank()) {
                Text(
                    text = event.location,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

@Composable
private fun CalendarDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}
