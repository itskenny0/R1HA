package com.github.itskenny0.r1ha.feature.calendars

import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import java.time.Instant
import java.time.ZoneId

/**
 * Pure, Compose-free helpers that build merged screen-reader descriptions for
 * the Calendars surface. Split out so the label assembly can be unit-tested
 * without a device clock or a live Compose tree.
 *
 * Every description states selection and all-day state in words rather than
 * relying on colour, so the surface stays usable with a screen reader or for
 * colour-blind users. Each event row collapses to a single spoken label
 * (title, then time, then calendar / location) instead of announcing a pile of
 * disconnected text nodes.
 */

/**
 * Spoken label for a calendar visibility toggle chip in the agenda filter row.
 * Conveys the on/off state in words (a screen reader otherwise only sees the
 * name plus a colour swatch).
 */
fun calendarToggleDescription(name: String, selected: Boolean): String {
    val safeName = name.ifBlank { "Calendar" }
    val stateWord = if (selected) "shown" else "hidden"
    return "$safeName, $stateWord. Double tap to toggle."
}

/**
 * Spoken label for a calendar summary row on the Calendars list (next-up
 * preview). Merges the name, current/next state and the next event message
 * and location into one description.
 */
fun calendarRowDescription(
    name: String,
    happeningNow: Boolean,
    allDay: Boolean,
    relativeTime: String,
    message: String?,
    location: String?,
): String = buildString {
    append(name.ifBlank { "Calendar" })
    when {
        happeningNow -> append(", happening now")
        allDay -> append(", all day")
        relativeTime.isNotBlank() -> append(", ").append(relativeTime)
    }
    if (!message.isNullOrBlank()) append(". ").append(message.trim())
    if (!location.isNullOrBlank()) append(". At ").append(location.trim())
}

/**
 * Spoken label for one merged agenda row. Joins the per-calendar accent's
 * meaning (the calendar name) with the event title, its formatted time and
 * an optional relative hint so the row reads as a single sentence.
 */
fun agendaRowDescription(
    event: CalendarEvent,
    calendarName: String,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    val allDay = isAllDay(event)
    append(event.summary.ifBlank { "Untitled event" })
    if (allDay) {
        append(", all day")
    } else {
        append(", ").append(formatEventTime(event, zone))
    }
    val hint = relativeStartHint(event, now)
    when (hint) {
        "" -> {}
        "NOW" -> append(", happening now")
        "STARTED" -> append(", already started")
        else -> append(", ").append(hint.lowercase())
    }
    if (calendarName.isNotBlank()) append(", on ").append(calendarName)
    if (!event.location.isNullOrBlank()) append(", at ").append(event.location.trim())
    if (!event.description.isNullOrBlank()) append(". ").append(event.description.trim())
}

/**
 * Spoken label for a per-calendar event row in the drill-down list. Conveys
 * the now/all-day state in words and folds in time, location and description.
 */
fun eventRowDescription(
    event: CalendarEvent,
    happeningNow: Boolean,
    zone: ZoneId = ZoneId.systemDefault(),
): String = buildString {
    append(event.summary.ifBlank { "Untitled event" })
    when {
        happeningNow -> append(", happening now")
        isAllDay(event) -> append(", all day")
        else -> append(", ").append(formatEventTime(event, zone))
    }
    if (!event.location.isNullOrBlank()) append(", at ").append(event.location.trim())
    if (!event.description.isNullOrBlank()) append(". ").append(event.description.trim())
}
