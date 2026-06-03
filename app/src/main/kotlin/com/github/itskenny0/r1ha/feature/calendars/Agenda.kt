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
 * True when [event] is in progress on [date] but did NOT start on it: a
 * multi-day span that should also surface under [date] (typically TODAY) so an
 * ongoing event isn't hidden on the day it began. All-day spans use the
 * inclusive last day ([allDayLastDate], collapsing HA's exclusive end); timed
 * spans compare against the day's bounds. Returns false for single-day events
 * and for the event's own start day (it already groups there).
 */
fun isOngoingOn(event: CalendarEvent, date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Boolean {
    val start = event.start ?: return false
    val startDate = start.atZone(zone).toLocalDate()
    if (!startDate.isBefore(date)) return false
    return if (isAllDay(event)) {
        val lastDate = allDayLastDate(event, zone) ?: return false
        !date.isAfter(lastDate)
    } else {
        val end = event.end ?: return false
        val dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant()
        // Covers the day when it starts before the day ends and ends after it begins.
        end.isAfter(date.atStartOfDay(zone).toInstant()) && start.isBefore(dayEnd)
    }
}

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

    // Each entry groups under its own start day. An entry that is an ongoing
    // multi-day span on today (started earlier, still running) also surfaces
    // under TODAY so it isn't hidden on the day it began.
    val byDay: MutableMap<LocalDate, MutableList<AgendaEntry>> = LinkedHashMap()
    kept.forEach { entry ->
        val startDate = (entry.event.start ?: startOfToday).atZone(zone).toLocalDate()
        byDay.getOrPut(startDate) { mutableListOf() }.add(entry)
        if (isOngoingOn(entry.event, today, zone) && startDate != today) {
            byDay.getOrPut(today) { mutableListOf() }.add(entry)
        }
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

/** A single calendar's events grouped under a rendered day header. */
data class EventDay(
    val date: LocalDate,
    val header: String,
    val events: List<CalendarEvent>,
)

/**
 * Groups one calendar's [events] by their start day for the drill-down list,
 * sorted chronologically with all-day / start-less events leading each day.
 * Mirrors the Agenda's day grouping so the two surfaces read the same. An
 * event with no start anchors to today so it still surfaces under a header.
 */
fun groupEventsByDay(
    events: List<CalendarEvent>,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): List<EventDay> {
    val today = now.atZone(zone).toLocalDate()
    val startOfToday = today.atStartOfDay(zone).toInstant()
    // Group by start day, and additionally surface an ongoing multi-day span
    // under TODAY so a still-running event isn't stuck on the day it began.
    val byDay: MutableMap<LocalDate, MutableList<CalendarEvent>> = LinkedHashMap()
    events.forEach { e ->
        val startDate = (e.start ?: startOfToday).atZone(zone).toLocalDate()
        byDay.getOrPut(startDate) { mutableListOf() }.add(e)
        if (isOngoingOn(e, today, zone) && startDate != today) {
            byDay.getOrPut(today) { mutableListOf() }.add(e)
        }
    }
    return byDay
        .entries
        .sortedBy { it.key }
        .map { (date, dayEvents) ->
            EventDay(
                date = date,
                header = dayHeader(date, today),
                events = dayEvents.sortedBy { it.start ?: Instant.MIN },
            )
        }
}

/**
 * Trims [text] to at most [max] characters, appending a single-character
 * ellipsis when it had to cut, so a long calendar title reads as deliberately
 * shortened ("Work Calend…") instead of a hard mid-word slice. Trailing
 * whitespace before the ellipsis is dropped so it never reads "Work …".
 */
fun ellipsize(text: String, max: Int): String {
    if (max <= 0 || text.length <= max) return text
    // Reserve one slot for the ellipsis character.
    val keep = (max - 1).coerceAtLeast(0)
    return text.take(keep).trimEnd() + "…"
}

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

// Compact date for a timed event whose end lands on a different day, so a
// multi-day span reads "2:00 PM - Jun 4 9:00 AM" instead of a misleading
// "2:00 PM - 9:00 AM" that looks like it ends earlier the same day.
private val endDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

// "Jun 4" date label for the start/end of an all-day span. Reused for both
// ends of a multi-day all-day range so it reads "ALL DAY, Jun 4 - Jun 6".
private val allDayDateFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("MMM d", Locale.US)

/**
 * The inclusive last calendar day an all-day [event] covers. HA reports an
 * all-day event's `end` as the EXCLUSIVE midnight after the final day, so a
 * single-day event has start = day 0 and end = day 1: subtract a day to get
 * the real last day. Returns null when timing is missing. Shared by the
 * formatter and the grouping so "what days does this span" is decided once.
 */
fun allDayLastDate(event: CalendarEvent, zone: ZoneId = ZoneId.systemDefault()): LocalDate? {
    val start = event.start ?: return null
    val startDate = start.atZone(zone).toLocalDate()
    val end = event.end ?: return startDate
    // Exclusive end midnight -> subtract a day for the inclusive final day.
    val endDateExclusive = end.atZone(zone).toLocalDate()
    val lastDay = endDateExclusive.minusDays(1)
    // Guard against a malformed end that lands at/before the start.
    return if (lastDay.isBefore(startDate)) startDate else lastDay
}

/**
 * "9:00 AM" / "9:00 AM - 5:00 PM" for a timed event; "ALL DAY" for a one-day
 * all-day event; "ALL DAY, Jun 4 - Jun 6" for a multi-day all-day span (HA's
 * exclusive end is collapsed to the inclusive last day via [allDayLastDate]).
 */
fun formatEventTime(event: CalendarEvent, zone: ZoneId = ZoneId.systemDefault()): String {
    if (isAllDay(event)) {
        val start = event.start ?: return "ALL DAY"
        val startDate = start.atZone(zone).toLocalDate()
        val lastDate = allDayLastDate(event, zone) ?: return "ALL DAY"
        if (lastDate == startDate) return "ALL DAY"
        // Multi-day all-day span: show the inclusive date range.
        return "ALL DAY, ${allDayDateFormatter.format(startDate)} - ${allDayDateFormatter.format(lastDate)}"
    }
    val start = event.start ?: return "ALL DAY"
    val startZdt = start.atZone(zone)
    val startStr = timeFormatter.format(startZdt)
    val end = event.end ?: return startStr
    val endZdt = end.atZone(zone)
    val endStr = if (endZdt.toLocalDate() == startZdt.toLocalDate()) {
        timeFormatter.format(endZdt)
    } else {
        // Different calendar day: prefix the end date so the span is unambiguous.
        "${endDateFormatter.format(endZdt)} ${timeFormatter.format(endZdt)}"
    }
    return "$startStr - $endStr"
}

/**
 * True when [now] falls inside an event's [start, end) span. Shared by every
 * calendar surface so "happening now" is decided one way. An event with no end
 * is treated as a point in time and is never "ongoing".
 */
fun isHappeningNow(event: CalendarEvent, now: Instant): Boolean {
    // All-day events carry a real [start, end) in HA (end is the exclusive
    // midnight after the final day), so a naive containment check would light
    // a "NOW" pill for the whole day. All-day events never get a NOW pill;
    // they get the ALL-DAY pill instead.
    if (isAllDay(event)) return false
    val start = event.start ?: return false
    val end = event.end ?: return false
    return !now.isBefore(start) && now.isBefore(end)
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
    // All-day events get no relative/NOW hint here: their timing is a whole
    // day, not a moment, and the ALL-DAY pill carries that meaning.
    if (isAllDay(event)) return ""
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

/**
 * Forward "IN 5M / IN 2H / IN 3D" hint for a single future [at] instant, with
 * a "NOW" band around the present. Used by the Calendars list where the only
 * timing we have is the next event's start: the app-wide RelativeTimeLabel
 * only renders past "ago" strings, so a future event there collapsed to
 * "just now". Returns "" when [at] is null.
 */
fun relativeFutureHint(at: Instant?, now: Instant): String {
    if (at == null) return ""
    val deltaSec = (at.toEpochMilli() - now.toEpochMilli()) / 1000
    return when {
        deltaSec < 60 && deltaSec > -60 -> "NOW"
        deltaSec <= 0 -> "STARTED"
        deltaSec < 3600 -> "IN ${deltaSec / 60}M"
        deltaSec < 86_400 -> "IN ${deltaSec / 3600}H"
        else -> "IN ${deltaSec / 86_400}D"
    }
}
