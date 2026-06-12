package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
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

    @Test fun `entityless action button takes the warm accent`() {
        // A bare action button has no state to be "off"; neutral grey read as
        // a disabled control, so it gets the warm accent (deliberate HA
        // divergence; see buttonAccent).
        assertEquals(R1.AccentWarm, buttonAccent(null, stateColor = true, null, null))
    }

    @Test fun `entityless button still honours an explicit color`() {
        assertEquals(R1.AccentCool, buttonAccent("blue", stateColor = true, null, null))
    }

    // ── buttonTapHint ────────────────────────────────────────────────────────

    @Test fun `send_command taps hint SEND`() {
        val a = LovelaceAction.CallService("remote.send_command", "remote.rm4", null)
        assertEquals("TAP TO SEND", buttonTapHint(a))
    }

    @Test fun `press and toggle services hint their own verbs`() {
        assertEquals(
            "TAP TO PRESS",
            buttonTapHint(LovelaceAction.CallService("button.press", "button.bell", null)),
        )
        assertEquals(
            "TAP TO TOGGLE",
            buttonTapHint(LovelaceAction.CallService("light.toggle", "light.a", null)),
        )
    }

    @Test fun `generic services hint RUN`() {
        assertEquals(
            "TAP TO RUN",
            buttonTapHint(LovelaceAction.CallService("scene.turn_on", "scene.movie", null)),
        )
        assertEquals(
            "TAP TO RUN",
            buttonTapHint(LovelaceAction.CallService("automation.trigger", "automation.a", null)),
        )
    }

    @Test fun `builtin and navigation actions hint their verbs`() {
        assertEquals("TAP TO TOGGLE", buttonTapHint(LovelaceAction.Builtin("toggle", "light.a")))
        assertEquals("TAP FOR INFO", buttonTapHint(LovelaceAction.Builtin("more-info", "sensor.a")))
        assertEquals("TAP TO ASK", buttonTapHint(LovelaceAction.Builtin("assist")))
        assertEquals("TAP TO OPEN", buttonTapHint(LovelaceAction.Navigate("/lovelace/0")))
        assertEquals("TAP TO OPEN", buttonTapHint(LovelaceAction.Url("https://example.org")))
    }

    @Test fun `inert surfaces get no hint`() {
        assertEquals(null, buttonTapHint(null))
        assertEquals(null, buttonTapHint(LovelaceAction.Builtin("none")))
        assertEquals(null, buttonTapHint(LovelaceAction.Invalid("nope")))
    }

    // ── iconHeightDp ─────────────────────────────────────────────────────────

    @Test fun `icon_height parses bare number pixels and em`() {
        assertEquals(48f, iconHeightDp("48"))
        assertEquals(40f, iconHeightDp("40px"))
        // 2em against HA's 24px base icon = 48px.
        assertEquals(48f, iconHeightDp("2em"))
    }

    @Test fun `icon_height clamps out-of-range values`() {
        assertEquals(96f, iconHeightDp("9999px"))
        assertEquals(16f, iconHeightDp("4px"))
    }

    @Test fun `icon_height rejects blank null and unparseable input`() {
        assertEquals(null, iconHeightDp(null))
        assertEquals(null, iconHeightDp("  "))
        assertEquals(null, iconHeightDp("tall"))
        assertEquals(null, iconHeightDp("0px"))
    }
}
