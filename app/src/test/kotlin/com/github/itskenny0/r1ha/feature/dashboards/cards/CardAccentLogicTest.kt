package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

/**
 * Pure-logic tests for the shared glance/tile accent decisions
 * ([effectiveStateColor], [glanceTileAccent], [tileIconAccent]).
 */
class CardAccentLogicTest {

    private fun state(
        id: String,
        raw: String,
        on: Boolean,
        available: Boolean = true,
        rgb: Triple<Int, Int, Int>? = null,
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
        attributesJson = rgb?.let { (r, g, b) ->
            buildJsonObject {
                put("rgb_color", JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b))))
            }
        },
    )

    // ── effectiveStateColor ──────────────────────────────────────────────────

    @Test fun `per-entity state_color overrides the card flag`() {
        assertEquals(false, effectiveStateColor(cardStateColor = true, rowStateColor = false))
        assertEquals(true, effectiveStateColor(cardStateColor = false, rowStateColor = true))
    }

    @Test fun `null per-entity flag inherits the card flag`() {
        assertEquals(true, effectiveStateColor(cardStateColor = true, rowStateColor = null))
        assertEquals(false, effectiveStateColor(cardStateColor = false, rowStateColor = null))
    }

    // ── glanceTileAccent ─────────────────────────────────────────────────────

    @Test fun `glance state_color on uses the state palette`() {
        val s = state("light.a", "on", on = true)
        assertEquals(stateAccentFor("light.a", s), glanceTileAccent("light.a", s, stateColor = true))
    }

    @Test fun `glance state_color off reads neutral for an on entity`() {
        val s = state("light.a", "on", on = true)
        assertEquals(R1.InkSoft, glanceTileAccent("light.a", s, stateColor = false))
    }

    @Test fun `glance state_color off still flags unavailable as a fault`() {
        val s = state("light.a", "unavailable", on = false, available = false)
        assertEquals(R1.StatusRed, glanceTileAccent("light.a", s, stateColor = false))
    }

    @Test fun `glance missing state reads muted`() {
        assertEquals(R1.InkMuted, glanceTileAccent("light.a", null, stateColor = true))
        assertEquals(R1.InkMuted, glanceTileAccent("light.a", null, stateColor = false))
    }

    // ── tileIconAccent ───────────────────────────────────────────────────────

    @Test fun `tile config color wins while the entity is active`() {
        val s = state("switch.a", "on", on = true)
        assertEquals(R1.AccentGreen, tileIconAccent("switch.a", s, configAccent = R1.AccentGreen, stateColor = true))
    }

    @Test fun `tile on light takes its rgb_color when no config color`() {
        val s = state("light.a", "on", on = true, rgb = Triple(255, 0, 0))
        val accent = tileIconAccent("light.a", s, configAccent = null, stateColor = true)
        assertEquals(Color(red = 1f, green = 0f, blue = 0f, alpha = 1f), accent)
    }

    @Test fun `tile off light ignores rgb_color and uses state accent`() {
        val s = state("light.a", "off", on = false, rgb = Triple(255, 0, 0))
        assertEquals(stateAccentFor("light.a", s), tileIconAccent("light.a", s, configAccent = null, stateColor = true))
    }

    @Test fun `tile unavailable always reads red even with a config color`() {
        val s = state("light.a", "unavailable", on = false, available = false)
        assertEquals(R1.StatusRed, tileIconAccent("light.a", s, configAccent = R1.AccentGreen, stateColor = true))
    }

    @Test fun `tile state_color off reads neutral on-off for a non-light`() {
        val on = state("switch.a", "on", on = true)
        val off = state("switch.a", "off", on = false)
        assertEquals(R1.AccentWarm, tileIconAccent("switch.a", on, configAccent = null, stateColor = false))
        assertEquals(R1.InkSoft, tileIconAccent("switch.a", off, configAccent = null, stateColor = false))
    }

    @Test fun `tile config color ignored while inactive`() {
        // HA colours the icon by `color` only when on; an off entity keeps its
        // state accent rather than the decorative colour.
        val s = state("switch.a", "off", on = false)
        assertEquals(stateAccentFor("switch.a", s), tileIconAccent("switch.a", s, configAccent = R1.AccentGreen, stateColor = true))
    }
}
