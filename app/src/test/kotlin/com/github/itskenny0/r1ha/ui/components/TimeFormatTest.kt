package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.prefs.ClockFormat
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Locks in the pure 12/24-hour clock mapping behind the Settings → Appearance →
 * Clock format option: AUTO defers to the system, H12 / H24 force a style, and
 * the absolute-timestamp buckets shorten as the instant ages (time only today,
 * day + time this year, date only across a year boundary). All assertions pin
 * UTC so the expectations are stable regardless of the test host's zone.
 */
class TimeFormatTest {

    private val utc = ZoneOffset.UTC

    // 2026-06-09T14:32:05Z — a fixed "now" mid-afternoon.
    private val now: Instant = Instant.parse("2026-06-09T14:32:05Z")

    @Test fun `auto follows the system setting`() {
        assertThat(use24HourClock(ClockFormat.AUTO, system24h = true)).isTrue()
        assertThat(use24HourClock(ClockFormat.AUTO, system24h = false)).isFalse()
    }

    @Test fun `h12 and h24 override the system setting`() {
        assertThat(use24HourClock(ClockFormat.H12, system24h = true)).isFalse()
        assertThat(use24HourClock(ClockFormat.H12, system24h = false)).isFalse()
        assertThat(use24HourClock(ClockFormat.H24, system24h = true)).isTrue()
        assertThat(use24HourClock(ClockFormat.H24, system24h = false)).isTrue()
    }

    @Test fun `clock pattern matches the resolved style`() {
        assertThat(clockPattern(use24h = true)).isEqualTo("HH:mm")
        assertThat(clockPattern(use24h = false)).isEqualTo("h:mm a")
    }

    @Test fun `formatClockTime renders both styles`() {
        assertThat(formatClockTime(now, utc, use24h = true)).isEqualTo("14:32")
        assertThat(formatClockTime(now, utc, use24h = false)).isEqualTo("2:32 PM")
    }

    @Test fun `formatClockTime pads 24-hour mornings and keeps 12-hour noon-midnight rules`() {
        val morning = Instant.parse("2026-06-09T05:07:00Z")
        assertThat(formatClockTime(morning, utc, use24h = true)).isEqualTo("05:07")
        assertThat(formatClockTime(morning, utc, use24h = false)).isEqualTo("5:07 AM")
        val midnight = Instant.parse("2026-06-09T00:01:00Z")
        assertThat(formatClockTime(midnight, utc, use24h = true)).isEqualTo("00:01")
        assertThat(formatClockTime(midnight, utc, use24h = false)).isEqualTo("12:01 AM")
        val noon = Instant.parse("2026-06-09T12:30:00Z")
        assertThat(formatClockTime(noon, utc, use24h = false)).isEqualTo("12:30 PM")
    }

    @Test fun `absolute timestamp shows time only for the same local day`() {
        val earlier = Instant.parse("2026-06-09T08:15:00Z")
        assertThat(formatAbsoluteTimestamp(earlier, now, utc, use24h = true)).isEqualTo("08:15")
        assertThat(formatAbsoluteTimestamp(earlier, now, utc, use24h = false)).isEqualTo("8:15 AM")
    }

    @Test fun `absolute timestamp adds the day inside the same year`() {
        val lastWeek = Instant.parse("2026-06-03T18:05:00Z")
        assertThat(formatAbsoluteTimestamp(lastWeek, now, utc, use24h = true))
            .isEqualTo("3 Jun 18:05")
        assertThat(formatAbsoluteTimestamp(lastWeek, now, utc, use24h = false))
            .isEqualTo("3 Jun 6:05 PM")
    }

    @Test fun `absolute timestamp drops the time across a year boundary`() {
        val lastYear = Instant.parse("2025-11-20T18:05:00Z")
        assertThat(formatAbsoluteTimestamp(lastYear, now, utc, use24h = true))
            .isEqualTo("20 Nov 2025")
        // The 12/24 choice is irrelevant once the time of day is dropped.
        assertThat(formatAbsoluteTimestamp(lastYear, now, utc, use24h = false))
            .isEqualTo("20 Nov 2025")
    }

    @Test fun `absolute timestamp buckets future instants the same way`() {
        // A sunrise later today renders as a plain clock time…
        val laterToday = Instant.parse("2026-06-09T21:04:00Z")
        assertThat(formatAbsoluteTimestamp(laterToday, now, utc, use24h = true))
            .isEqualTo("21:04")
        // …and a calendar event next month carries its day.
        val nextMonth = Instant.parse("2026-07-01T09:00:00Z")
        assertThat(formatAbsoluteTimestamp(nextMonth, now, utc, use24h = true))
            .isEqualTo("1 Jul 09:00")
    }

    @Test fun `absolute timestamp stays on the local wall clock across a DST jump`() {
        // Europe/Berlin springs forward 2026-03-29 (02:00 CET -> 03:00 CEST).
        // A sunrise after the jump must render its local CEST wall-clock time and
        // still bucket as "today" relative to a pre-jump now on the same local
        // date; both depend on the zone-aware atZone conversion, not epoch math.
        val berlin = java.time.ZoneId.of("Europe/Berlin")
        val preJump = Instant.parse("2026-03-29T00:30:00Z") // 01:30 CET, 29 Mar
        val sunrise = Instant.parse("2026-03-29T04:30:00Z") // 06:30 CEST, 29 Mar
        assertThat(formatAbsoluteTimestamp(sunrise, preJump, berlin, use24h = true))
            .isEqualTo("06:30")
        assertThat(formatAbsoluteTimestamp(sunrise, preJump, berlin, use24h = false))
            .isEqualTo("6:30 AM")
    }

    @Test fun `yesterday is not today even when under 24 hours away`() {
        // 23:50 the previous local day, 14h 42m before "now" — the bucket keys
        // on the LOCAL DATE, not the elapsed duration, so this carries its day.
        val lateLastNight = Instant.parse("2026-06-08T23:50:00Z")
        assertThat(formatAbsoluteTimestamp(lateLastNight, now, utc, use24h = true))
            .isEqualTo("8 Jun 23:50")
    }
}
