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

    // ── buttonSentLabel ──────────────────────────────────────────────────────

    @Test fun `sent label is tense-matched to the action`() {
        assertEquals(
            "SENT",
            buttonSentLabel(LovelaceAction.CallService("remote.send_command", "remote.rm4", null)),
        )
        assertEquals(
            "FIRED",
            buttonSentLabel(LovelaceAction.CallService("button.press", "button.bell", null)),
        )
        assertEquals(
            "TOGGLED",
            buttonSentLabel(LovelaceAction.CallService("light.toggle", "light.a", null)),
        )
        assertEquals(
            "DONE",
            buttonSentLabel(LovelaceAction.CallService("scene.turn_on", "scene.movie", null)),
        )
        assertEquals("TOGGLED", buttonSentLabel(LovelaceAction.Builtin("toggle", "light.a")))
    }

    @Test fun `sent label is null exactly when the tap hint is null`() {
        // Both gate the confirmation footer; they must agree on inert surfaces.
        assertEquals(null, buttonSentLabel(null))
        assertEquals(null, buttonSentLabel(LovelaceAction.Builtin("none")))
        assertEquals(null, buttonSentLabel(LovelaceAction.Invalid("nope")))
    }

    // ── buttonFiresSignal ────────────────────────────────────────────────────

    @Test fun `fire-and-forget services signal-pulse`() {
        assertEquals(
            true,
            buttonFiresSignal(LovelaceAction.CallService("remote.send_command", "remote.rm4", null)),
        )
        assertEquals(
            true,
            buttonFiresSignal(LovelaceAction.CallService("automation.trigger", "automation.a", null)),
        )
        assertEquals(
            true,
            buttonFiresSignal(LovelaceAction.CallService("scene.turn_on", "scene.movie", null)),
        )
        assertEquals(
            true,
            buttonFiresSignal(LovelaceAction.CallService("button.press", "button.bell", null)),
        )
    }

    @Test fun `toggle and turn_off services do not signal-pulse`() {
        assertEquals(
            false,
            buttonFiresSignal(LovelaceAction.CallService("light.toggle", "light.a", null)),
        )
        assertEquals(
            false,
            buttonFiresSignal(LovelaceAction.CallService("switch.turn_off", "switch.a", null)),
        )
    }

    @Test fun `navigations and builtins never signal-pulse`() {
        assertEquals(false, buttonFiresSignal(LovelaceAction.Navigate("/lovelace/0")))
        assertEquals(false, buttonFiresSignal(LovelaceAction.Url("https://example.org")))
        assertEquals(false, buttonFiresSignal(LovelaceAction.Builtin("toggle", "light.a")))
        assertEquals(false, buttonFiresSignal(LovelaceAction.Builtin("more-info")))
        assertEquals(false, buttonFiresSignal(null))
        assertEquals(false, buttonFiresSignal(LovelaceAction.Invalid("nope")))
    }

    // ── buttonIconSlug ───────────────────────────────────────────────────────

    @Test fun `send_command forces the remote glyph over a stale stored icon`() {
        // A pre-existing pinned IR card carrying a cog slug still draws remote.
        val tap = LovelaceAction.CallService("remote.send_command", "remote.rm4", null)
        assertEquals("remote", buttonIconSlug(tap, "mdi:robot"))
        assertEquals("remote", buttonIconSlug(tap, null))
    }

    @Test fun `non send_command keeps the configured icon untouched`() {
        val scene = LovelaceAction.CallService("scene.turn_on", "scene.movie", null)
        assertEquals("mdi:palette", buttonIconSlug(scene, "mdi:palette"))
        assertEquals(null, buttonIconSlug(scene, null))
        // An automation.trigger button keeps whatever icon it carries.
        val auto = LovelaceAction.CallService("automation.trigger", "automation.a", null)
        assertEquals("mdi:robot", buttonIconSlug(auto, "mdi:robot"))
    }

    // ── buttonNameBadge ──────────────────────────────────────────────────────

    @Test fun `leading bracket tag splits into an upper-cased badge plus label`() {
        assertEquals("IR" to "Living Room", buttonNameBadge("[IR] Living Room"))
        assertEquals("RF" to "Garage", buttonNameBadge("[rf]   Garage"))
    }

    @Test fun `names without a clean tag keep the original string`() {
        assertEquals(null to "Bedroom Lamp", buttonNameBadge("Bedroom Lamp"))
        // A tag with no following label would leave the face nameless.
        assertEquals(null to "[scene]", buttonNameBadge("[scene]"))
    }

    @Test fun `odd bracket names never crash or drop text`() {
        assertEquals(null to "[", buttonNameBadge("["))
        assertEquals(null to "[]", buttonNameBadge("[]"))
        assertEquals(null to "[] x", buttonNameBadge("[] x"))
    }

    // ── pulseRing ────────────────────────────────────────────────────────────

    @Test fun `pulse ring is silent before the clock starts and after it ends`() {
        assertEquals(0f, pulseRing(0f, 0, 3).alpha)
        assertEquals(0f, pulseRing(1f, 0, 3).alpha)
    }

    @Test fun `leading ring expands and fades over the clock`() {
        val early = pulseRing(0.2f, 0, 3)
        val late = pulseRing(0.8f, 0, 3)
        // Radius grows monotonically while alpha decays.
        assertEquals(true, late.radiusFraction > early.radiusFraction)
        assertEquals(true, late.alpha < early.alpha)
        // Eased-out radius stays within 0..1.
        assertEquals(true, early.radiusFraction in 0f..1f)
        assertEquals(true, late.radiusFraction in 0f..1f)
    }

    @Test fun `trailing rings start later than the leading ring`() {
        // At a small progress only the leading ring (index 0) has launched.
        val lead = pulseRing(0.05f, 0, 3)
        val trail = pulseRing(0.05f, 2, 3)
        assertEquals(true, lead.alpha > 0f)
        assertEquals(0f, trail.alpha)
    }
}
