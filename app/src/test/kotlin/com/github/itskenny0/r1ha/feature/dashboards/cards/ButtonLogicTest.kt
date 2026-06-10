package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ButtonLogicTest {

    private fun state(
        id: String,
        raw: String,
        on: Boolean,
        available: Boolean = true,
    ): EntityState = EntityState(
        id = EntityId(id),
        friendlyName = id,
        area = null,
        isOn = on,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = available,
        rawState = raw,
    )

    @Test fun `explicit color always wins`() {
        // Even an off entity keeps the configured colour.
        val s = state("light.a", "off", on = false)
        assertEquals(R1.AccentGreen, buttonAccent("green", stateColor = true, "light.a", s))
        assertEquals(R1.AccentCool, buttonAccent("blue", stateColor = false, "light.a", s))
    }

    @Test fun `state_color off keeps the button neutral`() {
        val s = state("light.a", "on", on = true)
        assertEquals(R1.InkSoft, buttonAccent(null, stateColor = false, "light.a", s))
    }

    @Test fun `active entity tints with the state accent when state_color on`() {
        val s = state("light.a", "on", on = true)
        assertEquals(stateAccentFor("light.a", s), buttonAccent(null, stateColor = true, "light.a", s))
    }

    @Test fun `off entity stays neutral even with state_color on`() {
        val s = state("light.a", "off", on = false)
        assertEquals(R1.InkSoft, buttonAccent(null, stateColor = true, "light.a", s))
    }

    @Test fun `unknown entity stays neutral`() {
        val s = state("sensor.a", "unknown", on = false)
        assertEquals(R1.InkSoft, buttonAccent(null, stateColor = true, "sensor.a", s))
    }

    @Test fun `unavailable entity reads as a fault regardless of state_color default`() {
        val s = state("light.a", "unavailable", on = false, available = false)
        assertEquals(R1.StatusRed, buttonAccent(null, stateColor = true, "light.a", s))
    }

    @Test fun `entityless action button is neutral`() {
        assertEquals(R1.InkSoft, buttonAccent(null, stateColor = true, null, null))
    }
}
