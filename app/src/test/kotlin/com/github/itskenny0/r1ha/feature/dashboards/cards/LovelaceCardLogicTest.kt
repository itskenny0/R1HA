package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.ui.components.WindowTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Pure-logic coverage for the dashboards bug-fix batch: toggle direction from
 * live state, default-tap entity binding, value-hiding, raw-id keying, and the
 * responsive column / horizontal-stack wrapping helpers.
 */
class LovelaceCardLogicTest {

    private fun state(id: String, raw: String, on: Boolean, unit: String? = null): EntityState {
        val eid = EntityId(id)
        return EntityState(
            id = eid,
            friendlyName = id,
            area = null,
            isOn = on,
            percent = null,
            raw = null,
            lastChanged = Instant.EPOCH,
            isAvailable = true,
            rawState = raw,
            unit = unit,
        )
    }

    // ── toggle direction from state (bug 1) ─────────────────────────────────

    @Test fun `tapAction turns an on switch off`() {
        val call = ServiceCall.tapAction(EntityId("switch.fan"), isOn = true)
        assertEquals("turn_off", call.service)
    }

    @Test fun `tapAction turns an off switch on`() {
        val call = ServiceCall.tapAction(EntityId("switch.fan"), isOn = false)
        assertEquals("turn_on", call.service)
    }

    @Test fun `tapAction closes an open cover and opens a closed one`() {
        assertEquals("close_cover", ServiceCall.tapAction(EntityId("cover.garage"), isOn = true).service)
        assertEquals("open_cover", ServiceCall.tapAction(EntityId("cover.garage"), isOn = false).service)
    }

    // ── default tap action carries the entity id (bugs 1 & 7) ───────────────

    @Test fun `defaultTapAction for a toggle domain carries the entity id`() {
        val action = defaultTapAction("switch.kettle") as LovelaceAction.Builtin
        assertEquals("toggle", action.name)
        assertEquals("switch.kettle", action.entityId)
    }

    @Test fun `defaultTapAction for an action domain is a CallService with entity`() {
        val action = defaultTapAction("script.bedtime") as LovelaceAction.CallService
        assertEquals("script.turn_on", action.service)
        assertEquals("script.bedtime", action.entityId)
    }

    @Test fun `boundTo fills a builtin's missing entity but keeps an existing one`() {
        val bare = LovelaceAction.Builtin("toggle")
        assertEquals("light.lamp", (bare.boundTo("light.lamp") as LovelaceAction.Builtin).entityId)
        val already = LovelaceAction.Builtin("toggle", "switch.a")
        assertEquals("switch.a", (already.boundTo("switch.b") as LovelaceAction.Builtin).entityId)
    }

    // ── value hiding (bug 2) ────────────────────────────────────────────────

    @Test fun `compactStateText is blank when the entity has no raw state`() {
        assertEquals("", compactStateText(state("sensor.empty", raw = "", on = false)))
    }

    @Test fun `compactStateText renders a numeric reading with its unit`() {
        assertEquals("21 °C", compactStateText(state("sensor.temp", raw = "21", on = false, unit = "°C")))
    }

    // ── domain-agnostic raw keying (bug 3) ──────────────────────────────────

    @Test fun `byRaw resolves an entity regardless of typed-id support`() {
        val map = mapOf("sensor.power" to state("sensor.power", raw = "42", on = false, unit = "W"))
        val holder = EntityStates.ofRaw(map)
        assertEquals("42", holder.byRaw("sensor.power")?.rawState)
        assertNull(holder.byRaw("sensor.absent"))
    }

    @Test fun `collectEntityIds gathers raw ids including condition entities`() {
        val card = com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Conditional(
            raw = kotlinx.serialization.json.JsonObject(emptyMap()),
            conditions = listOf(
                com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.StateEquals("sun.sun", "above_horizon"),
            ),
            card = com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Tile(
                raw = kotlinx.serialization.json.JsonObject(emptyMap()),
                entityId = "light.kitchen",
                name = null,
                icon = null,
                hideState = false,
                vertical = false,
                color = null,
                tapAction = null,
            ),
        )
        val ids = LinkedHashSet<String>()
        collectEntityIds(card, ids)
        assertTrue("sun.sun" in ids)
        assertTrue("light.kitchen" in ids)
    }

    // ── responsive columns (bug 6) ──────────────────────────────────────────

    @Test fun `grid columns collapse on narrow tiers and honour the request as a ceiling`() {
        // A config asking for 4 columns is capped to 2 on the R1 panel, 3 on a
        // compact phone, and honoured on roomier windows.
        assertEquals(2, responsiveColumnCount(4, WindowTier.R1))
        assertEquals(3, responsiveColumnCount(4, WindowTier.COMPACT))
        assertEquals(4, responsiveColumnCount(4, WindowTier.MEDIUM))
        // The author's request is always a ceiling: a 1-col grid stays 1 col.
        assertEquals(1, responsiveColumnCount(1, WindowTier.EXTRA_LARGE))
    }

    // ── iframe url resolution (bug 5) ───────────────────────────────────────

    @Test fun `iframe url resolution`() {
        assertEquals("https://x.test/p", resolveIframeUrl("https://x.test/p", null))
        // A server-relative path is joined onto the configured HA origin.
        assertEquals(
            "http://ha.local:8123/local/panel.html",
            resolveIframeUrl("/local/panel.html", "http://ha.local:8123/"),
        )
        // No origin to anchor a relative path, blank, or unsupported scheme -> null
        // so the card renders its placeholder rather than a blank WebView.
        assertNull(resolveIframeUrl("/local/panel.html", null))
        assertNull(resolveIframeUrl("   ", "http://ha.local:8123"))
    }

    @Test fun `horizontal stack wraps on narrow tiers but not on wide ones`() {
        assertEquals(2, horizontalStackMaxPerRow(5, WindowTier.R1))
        assertEquals(3, horizontalStackMaxPerRow(5, WindowTier.COMPACT))
        assertEquals(5, horizontalStackMaxPerRow(5, WindowTier.EXTRA_LARGE))
        // A single child never wraps and never reports a zero per-row cap.
        assertEquals(1, horizontalStackMaxPerRow(1, WindowTier.R1))
    }
}
