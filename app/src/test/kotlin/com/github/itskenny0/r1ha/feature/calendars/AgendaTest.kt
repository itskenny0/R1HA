package com.github.itskenny0.r1ha.feature.calendars

import com.github.itskenny0.r1ha.core.ha.CalendarEvent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for the pure agenda helpers in [Agenda]: grouping into day
 * buckets with TODAY/TOMORROW headers, chronological sort, all-day
 * detection, visibility filtering, time formatting and the forward-looking
 * relative-start hint.
 */
class AgendaTest {
    private val utc: ZoneId = ZoneId.of("UTC")

    // A fixed "now" so every test is deterministic: 2026-05-15 10:00 UTC.
    private val now: Instant = Instant.parse("2026-05-15T10:00:00Z")
    private val today: LocalDate = LocalDate.of(2026, 5, 15)

    private fun timed(
        summary: String,
        start: String,
        end: String? = null,
        location: String? = null,
    ) = CalendarEvent(
        summary = summary,
        start = Instant.parse(start),
        end = end?.let { Instant.parse(it) },
        allDay = false,
        location = location,
        description = null,
    )

    private fun allDayEvent(summary: String, startDate: String) = CalendarEvent(
        summary = summary,
        start = LocalDate.parse(startDate).atStartOfDay(utc).toInstant(),
        end = null,
        allDay = true,
        location = null,
        description = null,
    )

    private fun entry(cal: String, name: String, e: CalendarEvent) = AgendaEntry(cal, name, e)

    // ── all-day detection ────────────────────────────────────────────────

    @Test
    fun `isAllDay true for flagged event`() {
        assertThat(isAllDay(allDayEvent("Holiday", "2026-05-15"))).isTrue()
    }

    @Test
    fun `isAllDay true when start missing`() {
        val e = CalendarEvent("Mystery", null, null, allDay = false, location = null, description = null)
        assertThat(isAllDay(e)).isTrue()
    }

    @Test
    fun `isAllDay false for timed event`() {
        assertThat(isAllDay(timed("Standup", "2026-05-15T09:00:00Z"))).isFalse()
    }

    // ── day headers ──────────────────────────────────────────────────────

    @Test
    fun `day header is TODAY for today`() {
        assertThat(dayHeader(today, today)).isEqualTo("TODAY")
    }

    @Test
    fun `day header is TOMORROW for the next day`() {
        assertThat(dayHeader(today.plusDays(1), today)).isEqualTo("TOMORROW")
    }

    @Test
    fun `day header is an absolute uppercase date further out`() {
        assertThat(dayHeader(LocalDate.of(2026, 5, 20), today)).isEqualTo("WED, MAY 20")
    }

    // ── grouping + sort ──────────────────────────────────────────────────

    @Test
    fun `events group by day and sort by start time within a day`() {
        val entries = listOf(
            entry("cal.a", "Work", timed("Late", "2026-05-15T18:00:00Z")),
            entry("cal.a", "Work", timed("Early", "2026-05-15T11:00:00Z")),
            entry("cal.b", "Home", timed("Tomorrow", "2026-05-16T09:00:00Z")),
        )
        val days = buildAgenda(entries, now, visible = emptySet(), zone = utc)
        assertThat(days).hasSize(2)
        assertThat(days[0].header).isEqualTo("TODAY")
        assertThat(days[0].entries.map { it.event.summary }).containsExactly("Early", "Late").inOrder()
        assertThat(days[1].header).isEqualTo("TOMORROW")
        assertThat(days[1].entries.map { it.event.summary }).containsExactly("Tomorrow")
    }

    @Test
    fun `all-day event sorts before timed events on the same day`() {
        val entries = listOf(
            entry("cal.a", "Work", timed("Noon", "2026-05-15T12:00:00Z")),
            entry("cal.b", "Home", allDayEvent("Birthday", "2026-05-15")),
        )
        val days = buildAgenda(entries, now, visible = emptySet(), zone = utc)
        assertThat(days[0].entries.map { it.event.summary })
            .containsExactly("Birthday", "Noon").inOrder()
    }

    @Test
    fun `finished events are dropped`() {
        val entries = listOf(
            entry("cal.a", "Work", timed("Done", "2026-05-15T08:00:00Z", end = "2026-05-15T09:00:00Z")),
            entry("cal.a", "Work", timed("Running", "2026-05-15T09:30:00Z", end = "2026-05-15T11:00:00Z")),
        )
        val days = buildAgenda(entries, now, visible = emptySet(), zone = utc)
        val summaries = days.flatMap { it.entries }.map { it.event.summary }
        assertThat(summaries).containsExactly("Running")
    }

    @Test
    fun `in-progress event is kept`() {
        val e = timed("Meeting", "2026-05-15T09:30:00Z", end = "2026-05-15T11:00:00Z")
        val days = buildAgenda(listOf(entry("cal.a", "Work", e)), now, emptySet(), utc)
        assertThat(days.flatMap { it.entries }).hasSize(1)
    }

    // ── visibility filtering ─────────────────────────────────────────────

    @Test
    fun `only events from visible calendars are kept`() {
        val entries = listOf(
            entry("cal.a", "Work", timed("WorkEvent", "2026-05-15T12:00:00Z")),
            entry("cal.b", "Home", timed("HomeEvent", "2026-05-15T13:00:00Z")),
        )
        val days = buildAgenda(entries, now, visible = setOf("cal.a"), zone = utc)
        val summaries = days.flatMap { it.entries }.map { it.event.summary }
        assertThat(summaries).containsExactly("WorkEvent")
    }

    @Test
    fun `empty visible set means all calendars on`() {
        val entries = listOf(
            entry("cal.a", "Work", timed("WorkEvent", "2026-05-15T12:00:00Z")),
            entry("cal.b", "Home", timed("HomeEvent", "2026-05-15T13:00:00Z")),
        )
        val days = buildAgenda(entries, now, visible = emptySet(), zone = utc)
        assertThat(days.flatMap { it.entries }).hasSize(2)
    }

    // ── per-calendar accent ──────────────────────────────────────────────

    @Test
    fun `accent for a calendar is stable across calls`() {
        assertThat(accentForCalendar("calendar.work")).isEqualTo(accentForCalendar("calendar.work"))
    }

    @Test
    fun `accent index is always within palette bounds`() {
        // Even ids whose hashCode is negative must map to a valid index.
        listOf("a", "calendar.x", "-negative-hash", "zzzzz").forEach { id ->
            assertThat(AGENDA_ACCENTS).contains(accentForCalendar(id))
        }
    }

    // ── time formatting ──────────────────────────────────────────────────

    @Test
    fun `all-day event formats as ALL DAY`() {
        assertThat(formatEventTime(allDayEvent("Holiday", "2026-05-15"), utc)).isEqualTo("ALL DAY")
    }

    @Test
    fun `timed event with end shows a range`() {
        val e = timed("Call", "2026-05-15T14:00:00Z", end = "2026-05-15T15:30:00Z")
        assertThat(formatEventTime(e, utc)).isEqualTo("2:00 PM - 3:30 PM")
    }

    @Test
    fun `timed event without end shows just the start`() {
        val e = timed("Call", "2026-05-15T14:00:00Z")
        assertThat(formatEventTime(e, utc)).isEqualTo("2:00 PM")
    }

    // ── relative start hint ──────────────────────────────────────────────

    @Test
    fun `hint is NOW for an in-progress event`() {
        val e = timed("Meeting", "2026-05-15T09:30:00Z", end = "2026-05-15T11:00:00Z")
        assertThat(relativeStartHint(e, now)).isEqualTo("NOW")
    }

    @Test
    fun `hint counts minutes hours and days ahead`() {
        assertThat(relativeStartHint(timed("m", "2026-05-15T10:30:00Z"), now)).isEqualTo("IN 30M")
        assertThat(relativeStartHint(timed("h", "2026-05-15T12:00:00Z"), now)).isEqualTo("IN 2H")
        assertThat(relativeStartHint(timed("d", "2026-05-18T10:00:00Z"), now)).isEqualTo("IN 3D")
    }

    @Test
    fun `hint is STARTED for a past start with no end`() {
        assertThat(relativeStartHint(timed("late", "2026-05-15T08:00:00Z"), now)).isEqualTo("STARTED")
    }

    @Test
    fun `hint is empty for an event with no start`() {
        val e = CalendarEvent("x", null, null, allDay = true, location = null, description = null)
        assertThat(relativeStartHint(e, now)).isEmpty()
    }
}
