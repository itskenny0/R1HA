package com.github.itskenny0.r1ha.feature.calendars

import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CalendarsDescriptionTest {

    private val zone = ZoneId.of("America/New_York")

    private fun event(
        summary: String,
        start: Instant?,
        end: Instant?,
        allDay: Boolean,
        location: String? = null,
        description: String? = null,
    ) = CalendarEvent(
        summary = summary,
        start = start,
        end = end,
        allDay = allDay,
        location = location,
        description = description,
    )

    @Test
    fun calendarToggle_conveysShownStateInWords() {
        assertEquals(
            "Work, shown. Double tap to toggle.",
            calendarToggleDescription("Work", selected = true),
        )
    }

    @Test
    fun calendarToggle_conveysHiddenStateInWords() {
        assertEquals(
            "Work, hidden. Double tap to toggle.",
            calendarToggleDescription("Work", selected = false),
        )
    }

    @Test
    fun calendarToggle_blankNameFallsBack() {
        assertEquals(
            "Calendar, shown. Double tap to toggle.",
            calendarToggleDescription("", selected = true),
        )
    }

    @Test
    fun calendarRow_happeningNowOverridesRelativeTime() {
        assertEquals(
            "Standup, happening now. Daily sync. At Room 1",
            calendarRowDescription(
                name = "Standup",
                happeningNow = true,
                allDay = false,
                relativeTime = "in 2 h",
                message = "Daily sync",
                location = "Room 1",
            ),
        )
    }

    @Test
    fun calendarRow_allDayConveyedInWords() {
        assertEquals(
            "Birthday, all day",
            calendarRowDescription(
                name = "Birthday",
                happeningNow = false,
                allDay = true,
                relativeTime = "",
                message = null,
                location = null,
            ),
        )
    }

    @Test
    fun calendarRow_usesRelativeTimeWhenTimed() {
        assertEquals(
            "Dentist, in 3 h",
            calendarRowDescription(
                name = "Dentist",
                happeningNow = false,
                allDay = false,
                relativeTime = "in 3 h",
                message = "",
                location = "",
            ),
        )
    }

    @Test
    fun agendaRow_timedEventMergesTimeCalendarAndLocation() {
        val e = event(
            summary = "Lunch",
            start = Instant.parse("2026-01-02T14:00:00Z"),
            end = Instant.parse("2026-01-02T15:00:00Z"),
            allDay = false,
            location = "Cafe",
        )
        // now exactly 5 h before start (UTC 09:00 vs 14:00): future hint "IN 5H".
        val now = Instant.parse("2026-01-02T09:00:00Z")
        assertEquals(
            "Lunch, 9:00 AM - 10:00 AM, in 5h, on Personal, at Cafe",
            agendaRowDescription(e, "Personal", now, zone),
        )
    }

    @Test
    fun agendaRow_allDayConveyedInWords() {
        val e = event(summary = "Holiday", start = null, end = null, allDay = true)
        val now = Instant.parse("2026-01-02T09:00:00Z")
        assertEquals(
            "Holiday, all day, on Work",
            agendaRowDescription(e, "Work", now, zone),
        )
    }

    @Test
    fun agendaRow_inProgressEventSaysHappeningNow() {
        val e = event(
            summary = "Sprint review",
            start = Instant.parse("2026-01-02T13:00:00Z"),
            end = Instant.parse("2026-01-02T16:00:00Z"),
            allDay = false,
        )
        val now = Instant.parse("2026-01-02T14:00:00Z")
        assertEquals(
            "Sprint review, 8:00 AM - 11:00 AM, happening now, on Team",
            agendaRowDescription(e, "Team", now, zone),
        )
    }

    @Test
    fun eventRow_happeningNowConveyedInWords() {
        val e = event(
            summary = "Call",
            start = Instant.parse("2026-01-02T13:00:00Z"),
            end = Instant.parse("2026-01-02T14:00:00Z"),
            allDay = false,
            location = "Zoom",
            description = "Quarterly",
        )
        assertEquals(
            "Call, happening now, at Zoom. Quarterly",
            eventRowDescription(e, happeningNow = true, zone),
        )
    }

    @Test
    fun eventRow_allDayConveyedInWords() {
        val e = event(summary = "Conference", start = null, end = null, allDay = true)
        assertEquals(
            "Conference, all day",
            eventRowDescription(e, happeningNow = false, zone),
        )
    }

    @Test
    fun eventRow_timedEventIncludesFormattedTime() {
        val e = event(
            summary = "Review",
            start = Instant.parse("2026-01-02T18:30:00Z"),
            end = Instant.parse("2026-01-02T19:00:00Z"),
            allDay = false,
        )
        assertEquals(
            "Review, 1:30 PM - 2:00 PM",
            eventRowDescription(e, happeningNow = false, zone),
        )
    }
}
