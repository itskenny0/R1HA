package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.TimestampFormat
import com.github.itskenny0.r1ha.ui.components.formatTimestamp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale

/**
 * Pure-function coverage for DisplayEntityRows.kt: timerDisplayText,
 * formatTimerTotal, formatTimestamp, and weatherRowSecondaryLine.
 */
class DisplayEntityRowsTest {

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun state(
        id: String = "timer.test",
        rawState: String = "idle",
        attrs: Map<String, String> = emptyMap(),
    ): EntityState {
        val jsonAttrs = if (attrs.isEmpty()) null else {
            kotlinx.serialization.json.buildJsonObject {
                attrs.forEach { (k, v) ->
                    put(k, kotlinx.serialization.json.JsonPrimitive(v))
                }
            }
        }
        return EntityState(
            id = EntityId(id),
            friendlyName = id,
            area = null,
            isOn = false,
            percent = null,
            raw = null,
            lastChanged = Instant.EPOCH,
            isAvailable = true,
            rawState = rawState,
            attributesJson = jsonAttrs,
        )
    }

    // ── formatTimerTotal ─────────────────────────────────────────────────────

    @Test fun `formatTimerTotal zero seconds`() {
        assertEquals("00:00:00", formatTimerTotal(0))
    }

    @Test fun `formatTimerTotal one minute`() {
        assertEquals("00:01:00", formatTimerTotal(60))
    }

    @Test fun `formatTimerTotal one hour`() {
        assertEquals("01:00:00", formatTimerTotal(3600))
    }

    @Test fun `formatTimerTotal one hour thirty minutes ten seconds`() {
        assertEquals("01:30:10", formatTimerTotal(3600 + 1800 + 10))
    }

    @Test fun `formatTimerTotal negative coerces to zero`() {
        assertEquals("00:00:00", formatTimerTotal(-5))
    }

    @Test fun `formatTimerTotal large value pads correctly`() {
        assertEquals("10:00:00", formatTimerTotal(36000))
    }

    // ── timerDisplayText ─────────────────────────────────────────────────────

    @Test fun `timerDisplayText idle returns raw state`() {
        val s = state(rawState = "idle")
        assertEquals("idle", timerDisplayText(s, Instant.EPOCH))
    }

    @Test fun `timerDisplayText paused returns remaining attr`() {
        val s = state(rawState = "paused", attrs = mapOf("remaining" to "00:03:45"))
        assertEquals("00:03:45", timerDisplayText(s, Instant.EPOCH))
    }

    @Test fun `timerDisplayText paused with no remaining attr falls back to raw state`() {
        val s = state(rawState = "paused")
        assertEquals("paused", timerDisplayText(s, Instant.EPOCH))
    }

    @Test fun `timerDisplayText active uses finishes_at for countdown`() {
        val now = Instant.parse("2025-06-01T12:00:00Z")
        val finishesAt = "2025-06-01T12:01:30Z" // 90 seconds from now
        val s = state(rawState = "active", attrs = mapOf("finishes_at" to finishesAt))
        assertEquals("00:01:30", timerDisplayText(s, now))
    }

    @Test fun `timerDisplayText active past finishes_at shows zero`() {
        val now = Instant.parse("2025-06-01T12:05:00Z")
        val finishesAt = "2025-06-01T12:00:00Z" // already in the past
        val s = state(rawState = "active", attrs = mapOf("finishes_at" to finishesAt))
        assertEquals("00:00:00", timerDisplayText(s, now))
    }

    @Test fun `timerDisplayText active falls back to remaining when finishes_at absent`() {
        val s = state(rawState = "active", attrs = mapOf("remaining" to "00:10:00"))
        assertEquals("00:10:00", timerDisplayText(s, Instant.EPOCH))
    }

    @Test fun `timerDisplayText active falls back to raw when both attrs absent`() {
        val s = state(rawState = "active")
        assertEquals("active", timerDisplayText(s, Instant.EPOCH))
    }

    // ── formatTimestamp ──────────────────────────────────────────────────────

    private val utc: ZoneId = ZoneOffset.UTC
    private val fixedNow = Instant.parse("2025-06-10T14:30:00Z")
    private val fixedAt = Instant.parse("2025-06-10T10:00:00Z") // 4h 30m before now

    @Test fun `formatTimestamp RELATIVE produces age string`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.RELATIVE, fixedNow, utc, true)
        // formatRelativeTime shows "4h ago" for 4.5 hours
        assertTrue("expected hours label, got: $result", result.contains("h") || result.contains("m"))
    }

    @Test fun `formatTimestamp TOTAL produces H MM SS`() {
        // diff = 4h 30m = 16200s. The shared timestamp engine renders TOTAL as
        // H:MM:SS (no leading-zero hours) for durations of an hour or more.
        val result = formatTimestamp(fixedAt, TimestampFormat.TOTAL, fixedNow, utc, true)
        assertEquals("4:30:00", result)
    }

    @Test fun `formatTimestamp DATE formats as day month year`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.DATE, fixedNow, utc, true)
        assertEquals("10 Jun 2025", result)
    }

    @Test fun `formatTimestamp TIME 24h format`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.TIME, fixedNow, utc, use24h = true)
        assertEquals("10:00", result)
    }

    @Test fun `formatTimestamp TIME 12h format`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.TIME, fixedNow, utc, use24h = false)
        // 10:00 AM
        assertTrue("expected AM, got: $result", result.contains("AM") || result.contains("am"))
    }

    @Test fun `formatTimestamp DATETIME 24h`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.DATETIME, fixedNow, utc, use24h = true)
        assertEquals("10 Jun 10:00", result)
    }

    @Test fun `formatTimestamp DATETIME 12h`() {
        val result = formatTimestamp(fixedAt, TimestampFormat.DATETIME, fixedNow, utc, use24h = false)
        assertTrue("expected AM, got: $result", result.contains("AM") || result.contains("am"))
        assertTrue("expected Jun, got: $result", result.contains("Jun"))
    }

    // ── weatherRowSecondaryLine ───────────────────────────────────────────────

    @Test fun `weatherRowSecondaryLine both humidity and wind`() {
        val s = state(
            id = "weather.home",
            rawState = "sunny",
            attrs = mapOf(
                "humidity" to "65",
                "wind_speed" to "12",
                "wind_speed_unit" to "km/h",
            ),
        )
        val result = weatherRowSecondaryLine(s)
        assertEquals("Humidity 65%  ·  Wind 12 km/h", result)
    }

    @Test fun `weatherRowSecondaryLine humidity only`() {
        val s = state(
            id = "weather.home",
            rawState = "cloudy",
            attrs = mapOf("humidity" to "70"),
        )
        val result = weatherRowSecondaryLine(s)
        assertEquals("Humidity 70%", result)
    }

    @Test fun `weatherRowSecondaryLine wind only`() {
        val s = state(
            id = "weather.home",
            rawState = "windy",
            attrs = mapOf("wind_speed" to "25", "wind_speed_unit" to "mph"),
        )
        val result = weatherRowSecondaryLine(s)
        assertEquals("Wind 25 mph", result)
    }

    @Test fun `weatherRowSecondaryLine neither attr returns null`() {
        val s = state(id = "weather.home", rawState = "sunny")
        val result = weatherRowSecondaryLine(s)
        assertEquals(null, result)
    }

    @Test fun `weatherRowSecondaryLine wind without unit omits unit`() {
        val s = state(
            id = "weather.home",
            rawState = "sunny",
            attrs = mapOf("wind_speed" to "5"),
        )
        val result = weatherRowSecondaryLine(s)
        assertEquals("Wind 5", result)
    }
}
