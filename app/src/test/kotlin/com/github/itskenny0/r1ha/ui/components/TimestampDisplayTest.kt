package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.lovelace.TimestampFormat
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneOffset

/**
 * Pure-function tests for the timestamp display engine (TimestampDisplay.kt).
 * All assertions pin UTC and inject [now] so results are deterministic on any host.
 *
 * Covers:
 *  - [isTimestampDeviceClass]: timestamp and uptime classes detected, others not
 *  - [resolveTimestampFormat]: explicit override, device-class defaults
 *  - [formatTimestamp]: all five formats (relative/total/date/time/datetime),
 *    relative wording boundaries, total HH:MM:SS / M:SS shapes, locale-pinned output
 *  - [formatTotalDuration]: past/future symmetry, H:MM:SS vs M:SS boundary
 *  - [timestampInstantOrNull]: valid/invalid input, wrong device-class
 */
class TimestampDisplayTest {

    private val utc = ZoneOffset.UTC

    // Fixed reference point: 2026-06-10T12:00:00Z
    private val now: Instant = Instant.parse("2026-06-10T12:00:00Z")
    private fun past(sec: Long): Instant = now.minusSeconds(sec)
    private fun future(sec: Long): Instant = now.plusSeconds(sec)

    // ── isTimestampDeviceClass ────────────────────────────────────────────────

    @Test fun `timestamp device class is recognised`() {
        assertThat(isTimestampDeviceClass("timestamp")).isTrue()
        assertThat(isTimestampDeviceClass("TIMESTAMP")).isTrue()
    }

    @Test fun `uptime device class is recognised`() {
        assertThat(isTimestampDeviceClass("uptime")).isTrue()
        assertThat(isTimestampDeviceClass("UPTIME")).isTrue()
    }

    @Test fun `other device classes are not timestamp classes`() {
        assertThat(isTimestampDeviceClass("temperature")).isFalse()
        assertThat(isTimestampDeviceClass("battery")).isFalse()
        assertThat(isTimestampDeviceClass(null)).isFalse()
        assertThat(isTimestampDeviceClass("")).isFalse()
    }

    // ── resolveTimestampFormat ────────────────────────────────────────────────

    @Test fun `explicit row format overrides device-class default`() {
        assertThat(resolveTimestampFormat(TimestampFormat.DATE, "timestamp"))
            .isEqualTo(TimestampFormat.DATE)
        assertThat(resolveTimestampFormat(TimestampFormat.TOTAL, "uptime"))
            .isEqualTo(TimestampFormat.TOTAL)
        assertThat(resolveTimestampFormat(TimestampFormat.TIME, null))
            .isEqualTo(TimestampFormat.TIME)
    }

    @Test fun `timestamp device-class defaults to relative`() {
        assertThat(resolveTimestampFormat(null, "timestamp")).isEqualTo(TimestampFormat.RELATIVE)
    }

    @Test fun `uptime device-class defaults to total`() {
        assertThat(resolveTimestampFormat(null, "uptime")).isEqualTo(TimestampFormat.TOTAL)
    }

    @Test fun `non-timestamp device class returns null`() {
        assertThat(resolveTimestampFormat(null, "temperature")).isNull()
        assertThat(resolveTimestampFormat(null, null)).isNull()
    }

    // ── formatTimestamp: RELATIVE ─────────────────────────────────────────────

    @Test fun `relative format uses existing formatRelativeTime wording`() {
        assertThat(formatTimestamp(past(5), TimestampFormat.RELATIVE, now, utc, true))
            .isEqualTo("just now")
        assertThat(formatTimestamp(past(300), TimestampFormat.RELATIVE, now, utc, true))
            .isEqualTo("5m ago")
        assertThat(formatTimestamp(future(7200), TimestampFormat.RELATIVE, now, utc, true))
            .isEqualTo("in 2h")
    }

    @Test fun `relative past boundaries seconds minutes hours days weeks months years`() {
        assertThat(formatTimestamp(past(45), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("45s ago")
        assertThat(formatTimestamp(past(5 * 60), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("5m ago")
        assertThat(formatTimestamp(past(3 * 3600), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("3h ago")
        assertThat(formatTimestamp(past(2 * 86_400), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("2d ago")
        assertThat(formatTimestamp(past(14 * 86_400), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("2w ago")
        assertThat(formatTimestamp(past(45 * 86_400), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("1mo ago")
        assertThat(formatTimestamp(past(400 * 86_400), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("1y ago")
    }

    @Test fun `relative future boundaries`() {
        assertThat(formatTimestamp(future(45), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("in 45s")
        assertThat(formatTimestamp(future(5 * 60), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("in 5m")
        assertThat(formatTimestamp(future(3600), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("in 1h")
        assertThat(formatTimestamp(future(3 * 86_400), TimestampFormat.RELATIVE, now, utc, true)).isEqualTo("in 3d")
    }

    // ── formatTimestamp: TOTAL ────────────────────────────────────────────────

    @Test fun `total format renders M colon SS below one hour`() {
        // 5 minutes 3 seconds = 303 s
        assertThat(formatTimestamp(past(303), TimestampFormat.TOTAL, now, utc, true)).isEqualTo("5:03")
        assertThat(formatTimestamp(future(303), TimestampFormat.TOTAL, now, utc, true)).isEqualTo("5:03")
    }

    @Test fun `total format renders H colon MM colon SS at or above one hour`() {
        // 1 h 2 m 3 s = 3723 s
        assertThat(formatTimestamp(past(3723), TimestampFormat.TOTAL, now, utc, true)).isEqualTo("1:02:03")
        // 10 h exactly
        assertThat(formatTimestamp(past(36_000), TimestampFormat.TOTAL, now, utc, true)).isEqualTo("10:00:00")
    }

    @Test fun `total format at zero delta`() {
        assertThat(formatTimestamp(now, TimestampFormat.TOTAL, now, utc, true)).isEqualTo("0:00")
    }

    @Test fun `total format is unsigned for future instants`() {
        // A future instant (uptime sensor whose date is in the future) reads the same
        // unsigned duration as an equivalent past instant.
        assertThat(formatTimestamp(future(3723), TimestampFormat.TOTAL, now, utc, true))
            .isEqualTo("1:02:03")
    }

    // ── formatTimestamp: DATE ─────────────────────────────────────────────────

    @Test fun `date format renders locale-pinned day month year`() {
        val at = Instant.parse("2026-03-07T00:00:00Z")
        assertThat(formatTimestamp(at, TimestampFormat.DATE, now, utc, true)).isEqualTo("7 Mar 2026")
        assertThat(formatTimestamp(at, TimestampFormat.DATE, now, utc, false)).isEqualTo("7 Mar 2026")
    }

    // ── formatTimestamp: TIME ─────────────────────────────────────────────────

    @Test fun `time format respects 24-hour choice`() {
        val at = Instant.parse("2026-06-10T14:30:00Z")
        assertThat(formatTimestamp(at, TimestampFormat.TIME, now, utc, true)).isEqualTo("14:30")
        assertThat(formatTimestamp(at, TimestampFormat.TIME, now, utc, false)).isEqualTo("2:30 PM")
    }

    @Test fun `time format morning AM stays pinned to US locale`() {
        val at = Instant.parse("2026-06-10T08:05:00Z")
        assertThat(formatTimestamp(at, TimestampFormat.TIME, now, utc, false)).isEqualTo("8:05 AM")
    }

    // ── formatTimestamp: DATETIME ─────────────────────────────────────────────

    @Test fun `datetime format respects 24-hour choice`() {
        val at = Instant.parse("2026-06-09T14:30:00Z")
        assertThat(formatTimestamp(at, TimestampFormat.DATETIME, now, utc, true)).isEqualTo("9 Jun 14:30")
        assertThat(formatTimestamp(at, TimestampFormat.DATETIME, now, utc, false)).isEqualTo("9 Jun 2:30 PM")
    }

    // ── formatTotalDuration ───────────────────────────────────────────────────

    @Test fun `formatTotalDuration zero is 0 colon 00`() {
        assertThat(formatTotalDuration(now, now)).isEqualTo("0:00")
    }

    @Test fun `formatTotalDuration sub-hour is M colon SS with zero-padded seconds`() {
        assertThat(formatTotalDuration(past(61), now)).isEqualTo("1:01")
        assertThat(formatTotalDuration(past(599), now)).isEqualTo("9:59")
        assertThat(formatTotalDuration(past(3599), now)).isEqualTo("59:59")
    }

    @Test fun `formatTotalDuration at-or-above-one-hour is H colon MM colon SS`() {
        assertThat(formatTotalDuration(past(3600), now)).isEqualTo("1:00:00")
        assertThat(formatTotalDuration(past(3661), now)).isEqualTo("1:01:01")
        assertThat(formatTotalDuration(past(36_000), now)).isEqualTo("10:00:00")
    }

    @Test fun `formatTotalDuration is unsigned for future and past`() {
        assertThat(formatTotalDuration(future(3661), now)).isEqualTo("1:01:01")
        assertThat(formatTotalDuration(past(3661), now)).isEqualTo("1:01:01")
    }

    // ── timestampInstantOrNull ────────────────────────────────────────────────

    @Test fun `valid timestamp device-class and HA ISO string returns instant`() {
        val inst = timestampInstantOrNull("timestamp", "2026-06-10T07:12:03+00:00")
        assertThat(inst).isNotNull()
    }

    @Test fun `uptime device-class and valid string returns instant`() {
        val inst = timestampInstantOrNull("uptime", "2026-06-10T07:12:03+00:00")
        assertThat(inst).isNotNull()
    }

    @Test fun `non-timestamp device-class returns null regardless of state`() {
        assertThat(timestampInstantOrNull("temperature", "2026-06-10T07:12:03+00:00")).isNull()
        assertThat(timestampInstantOrNull(null, "2026-06-10T07:12:03+00:00")).isNull()
    }

    @Test fun `invalid state string returns null and does not throw`() {
        assertThat(timestampInstantOrNull("timestamp", "not-a-timestamp")).isNull()
        assertThat(timestampInstantOrNull("timestamp", "")).isNull()
        assertThat(timestampInstantOrNull("timestamp", null)).isNull()
        assertThat(timestampInstantOrNull("timestamp", "unavailable")).isNull()
        assertThat(timestampInstantOrNull("timestamp", "unknown")).isNull()
    }

    @Test fun `HA plus-zero-zero offset is accepted via parseHaInstant`() {
        // The +00:00 form that plain Instant.parse would reject on desugared runtimes.
        val inst = timestampInstantOrNull("timestamp", "2026-06-10T07:12:03+00:00")
        assertThat(inst).isEqualTo(Instant.parse("2026-06-10T07:12:03Z"))
    }
}
