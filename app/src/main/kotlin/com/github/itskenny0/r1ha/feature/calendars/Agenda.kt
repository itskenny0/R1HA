package com.github.itskenny0.r1ha.feature.calendars

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure helpers backing the cross-calendar Agenda surface. Everything here is
 * deterministic and zone/clock-injectable so it can be unit-tested without a
 * device clock or a live HA connection. The Compose layer in
 * [AgendaScreen]/[AgendaViewModel] supplies the real [Instant.now] and
 * [ZoneId.systemDefault].
 */

/**
 * One event in the merged agenda, carrying the calendar it came from so the
 * UI can tint it with a stable per-calendar accent and honour the visibility
 * toggles.
 */
data class AgendaEntry(
    val calendarId: String,
    val calendarName: String,
    val event: CalendarEvent,
)

/** A run of [entries] that share a calendar day, with a rendered header. */
data class AgendaDay(
    val date: LocalDate,
    val header: String,
    val entries: List<AgendaEntry>,
)

/**
 * The stable per-calendar accent palette. A calendar's colour is derived from
 * a hash of its entity id so the same calendar always renders in the same
 * accent across launches without persisting anything. Drawn from the existing
 * R1 accent tokens so the agenda stays on-palette.
 */
val AGENDA_ACCENTS: List<Color> = listOf(
    R1.AccentWarm,
    R1.AccentCool,
    R1.AccentGreen,
    R1.StatusAmber,
    R1.AccentNeutral,
)

/** Stable accent for [calendarId]; same id always maps to the same colour. */
fun accentForCalendar(calendarId: String): Color {
    // Math.floorMod keeps the index non-negative even when hashCode is negative.
    val idx = Math.floorMod(calendarId.hashCode(), AGENDA_ACCENTS.size)
    return AGENDA_ACCENTS[idx]
}

/**
 * True when [event] occupies a whole day (no specific clock time). HA marks
 * these with the all-day flag; we also treat a missing start as all-day so a
 * date-only event never renders a misleading "in 2h" countdown.
 */
fun isAllDay(event: CalendarEvent): Boolean = event.allDay || event.start == null

/**
 * Builds the grouped, chronologically-sorted agenda from [entries], keeping
 * only events that:
 *  - belong to a calendar present in [visible] (an empty set means "all on"),
 *  - end at or after [now] (so finished events drop off the agenda; an
 *    end-less event is kept if its start is at/after the start of today).
 *
 * Within a day, entries sort by start time (all-day / start-less first), then
 * by calendar name, then by summary for a deterministic order.
 */
fun buildAgenda(
    entries: List<AgendaEntry>,
    now: Instant,
    visible: Set<String>,
    zone: ZoneId = ZoneId.systemDefault(),
): List<AgendaDay> {
    val today = now.atZone(zone).toLocalDate()
    val startOfToday = today.atStartOfDay(zone).toInstant()

    val kept = entries.filter { entry ->
        val visibleCalendar = visible.isEmpty() || entry.calendarId in visible
        if (!visibleCalendar) return@filter false
        val e = entry.event
        when {
            // Still running, or ends in the future.
            e.end != null -> !e.end.isBefore(now)
            // No end: keep if it starts today or later (covers all-day rows).
            e.start != null -> !e.start.isBefore(startOfToday)
            // No timing info at all: keep so it surfaces somewhere.
            else -> true
        }
    }

    val byDay = kept.groupBy { entry ->
        val anchor = entry.event.start ?: startOfToday
        anchor.atZone(zone).toLocalDate()
    }

    return byDay.entries
        .sortedBy { it.key }
        .map { (date, dayEntries) ->
            AgendaDay(
                date = date,
                header = dayHeader(date, today),
                entries = dayEntries.sortedWith(agendaEntryComparator),
            )
        }
}

private val agendaEntryComparator: Comparator<AgendaEntry> =
    compareBy<AgendaEntry> { it.event.start ?: Instant.MIN }
        .thenBy { it.calendarName.lowercase(Locale.US) }
        .thenBy { it.event.summary.lowercase(Locale.US) }

private val dayHeaderFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE, MMM d", Locale.US)

/** TODAY / TOMORROW for the two near days, otherwise an absolute date. */
fun dayHeader(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "TODAY"
    today.plusDays(1) -> "TOMORROW"
    else -> dayHeaderFormatter.format(date).uppercase(Locale.US)
}

// Fixed 12-hour pattern rather than ofLocalizedTime: the localized SHORT style
// emits a narrow no-break space before AM/PM on recent JDK/ICU data, which makes
// the rendered string brittle to assert on. "h:mm a" with Locale.US gives a
// stable "2:00 PM".
private val timeFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h:mm a", Locale.US)

/** "9:00 AM" for a timed event, "ALL DAY" for an all-day one. */
fun formatEventTime(event: CalendarEvent, zone: ZoneId = ZoneId.systemDefault()): String {
    if (isAllDay(event)) return "ALL DAY"
    val start = event.start ?: return "ALL DAY"
    val startStr = timeFormatter.format(start.atZone(zone))
    val end = event.end ?: return startStr
    return "$startStr - ${timeFormatter.format(end.atZone(zone))}"
}

/**
 * Forward-looking relative hint for an upcoming or in-progress event:
 *  - in progress (now within [start, end)) -> "NOW"
 *  - starts within a minute -> "NOW"
 *  - future -> "IN 5M" / "IN 2H" / "IN 3D"
 *  - already started but no end / past start -> "STARTED"
 *  - no start (all-day with no time) -> "" (caller hides the label)
 *
 * Distinct from the app-wide RelativeTimeLabel which only renders past
 * "5m ago" strings; agenda events are upcoming, so we want the future form.
 */
fun relativeStartHint(event: CalendarEvent, now: Instant): String {
    val start = event.start ?: return ""
    val end = event.end
    if (end != null && !now.isBefore(start) && now.isBefore(end)) return "NOW"
    val deltaSec = (start.toEpochMilli() - now.toEpochMilli()) / 1000
    return when {
        deltaSec < 60 && deltaSec > -60 -> "NOW"
        deltaSec <= 0 -> "STARTED"
        deltaSec < 3600 -> "IN ${deltaSec / 60}M"
        deltaSec < 86_400 -> "IN ${deltaSec / 3600}H"
        else -> "IN ${deltaSec / 86_400}D"
    }
}
