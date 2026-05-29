package com.github.itskenny0.r1ha.feature.calendars

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Unit tests for [parseCalendarInstant], the shared HA calendar start/end
 * parser used by the Calendars list and the Dashboard "next event" tile.
 *
 * Timezone policy under test: offset-bearing datetimes are honoured exactly,
 * offset-less datetimes are local wall-clock, and bare all-day dates resolve
 * to local midnight (so same-day all-day events are selectable rather than
 * sinking to Instant.MAX).
 */
class CalendarInstantTest {
    // Europe/Berlin: +01:00 in winter, +02:00 in summer (DST).
    private val berlin = ZoneId.of("Europe/Berlin")
    private val utc = ZoneId.of("UTC")

    @Test
    fun `offset datetime is honoured exactly regardless of zone`() {
        val raw = "2026-05-15T09:00:00+02:00"
        assertThat(parseCalendarInstant(raw, berlin))
            .isEqualTo(Instant.parse("2026-05-15T07:00:00Z"))
        assertThat(parseCalendarInstant(raw, utc))
            .isEqualTo(Instant.parse("2026-05-15T07:00:00Z"))
    }

    @Test
    fun `zulu datetime is honoured`() {
        assertThat(parseCalendarInstant("2026-05-15T07:00:00Z", berlin))
            .isEqualTo(Instant.parse("2026-05-15T07:00:00Z"))
    }

    @Test
    fun `offset-less datetime resolves in device zone`() {
        // 09:00 local in Berlin summer (+02:00) -> 07:00Z.
        assertThat(parseCalendarInstant("2026-05-15T09:00:00", berlin))
            .isEqualTo(Instant.parse("2026-05-15T07:00:00Z"))
        // Same wall-clock interpreted in UTC -> 09:00Z.
        assertThat(parseCalendarInstant("2026-05-15T09:00:00", utc))
            .isEqualTo(Instant.parse("2026-05-15T09:00:00Z"))
    }

    @Test
    fun `space-separated local datetime is tolerated`() {
        assertThat(parseCalendarInstant("2026-05-15 09:00:00", berlin))
            .isEqualTo(Instant.parse("2026-05-15T07:00:00Z"))
    }

    @Test
    fun `bare date all-day resolves to local midnight`() {
        val expected = LocalDate.of(2026, 5, 15).atStartOfDay(berlin).toInstant()
        assertThat(parseCalendarInstant("2026-05-15", berlin)).isEqualTo(expected)
        // Berlin midnight is 22:00Z the previous day in summer.
        assertThat(parseCalendarInstant("2026-05-15", berlin))
            .isEqualTo(Instant.parse("2026-05-14T22:00:00Z"))
    }

    @Test
    fun `bare date resolves to local midnight in utc`() {
        assertThat(parseCalendarInstant("2026-05-15", utc))
            .isEqualTo(Instant.parse("2026-05-15T00:00:00Z"))
    }

    @Test
    fun `same-day all-day event sorts before a later timed event`() {
        // Regression: an all-day event today must not sink below a same-day
        // timed event. The old loose path parsed the bare date to null, so it
        // fell back to Instant.MAX in both the list sort and the dashboard
        // "next event" min-by.
        val allDay = parseCalendarInstant("2026-05-15", berlin)
        val timed = parseCalendarInstant("2026-05-15T18:00:00", berlin)
        assertThat(allDay).isNotNull()
        assertThat(timed).isNotNull()
        assertThat(allDay!! < timed!!).isTrue()
    }

    @Test
    fun `blank and malformed inputs return null`() {
        assertThat(parseCalendarInstant(null, berlin)).isNull()
        assertThat(parseCalendarInstant("", berlin)).isNull()
        assertThat(parseCalendarInstant("   ", berlin)).isNull()
        assertThat(parseCalendarInstant("not-a-date", berlin)).isNull()
    }
}
