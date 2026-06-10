package com.github.itskenny0.r1ha.feature.dashboards.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class ClockLogicTest {

    // ── time_format resolution ──────────────────────────────────────────────

    @Test fun `explicit 24 forces 24h regardless of device`() {
        assertTrue(clockUses24h("24", systemIs24h = false))
    }

    @Test fun `explicit 12 forces AM-PM regardless of device`() {
        assertFalse(clockUses24h("12", systemIs24h = true))
    }

    @Test fun `auto and null follow the device setting`() {
        assertTrue(clockUses24h("auto", systemIs24h = true))
        assertFalse(clockUses24h("auto", systemIs24h = false))
        assertTrue(clockUses24h(null, systemIs24h = true))
        assertFalse(clockUses24h(null, systemIs24h = false))
    }

    // ── time_zone resolution ────────────────────────────────────────────────

    @Test fun `valid zone id is used`() {
        val fallback = ZoneId.of("UTC")
        assertEquals(ZoneId.of("America/New_York"), clockZone("America/New_York", fallback))
    }

    @Test fun `blank or unknown zone falls back`() {
        val fallback = ZoneId.of("UTC")
        assertEquals(fallback, clockZone(null, fallback))
        assertEquals(fallback, clockZone("", fallback))
        assertEquals(fallback, clockZone("Not/AZone", fallback))
    }

    // ── analog hand geometry ────────────────────────────────────────────────

    @Test fun `12 o'clock sharp points all hands up`() {
        val h = clockHands(12, 0, 0)
        assertEquals(0f, h.hourDeg, 0.001f)
        assertEquals(0f, h.minuteDeg, 0.001f)
        assertEquals(0f, h.secondDeg, 0.001f)
    }

    @Test fun `3 o'clock hour hand is at 90 degrees`() {
        assertEquals(90f, clockHands(3, 0, 0).hourDeg, 0.001f)
    }

    @Test fun `hour hand sweeps halfway by 1-30`() {
        // 1:30 -> hour hand halfway between 1 (30deg) and 2 (60deg) = 45deg.
        assertEquals(45f, clockHands(1, 30, 0).hourDeg, 0.001f)
        // minute hand at 30 minutes = 180deg.
        assertEquals(180f, clockHands(1, 30, 0).minuteDeg, 0.001f)
    }

    @Test fun `second hand ticks 6 degrees per second`() {
        assertEquals(90f, clockHands(0, 0, 15).secondDeg, 0.001f)
    }

    @Test fun `hours wrap modulo 12`() {
        assertEquals(clockHands(0, 0, 0).hourDeg, clockHands(12, 0, 0).hourDeg, 0.001f)
        assertEquals(90f, clockHands(15, 0, 0).hourDeg, 0.001f)
    }
}
