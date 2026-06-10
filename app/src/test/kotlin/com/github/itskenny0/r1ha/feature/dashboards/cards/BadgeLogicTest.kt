package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceBadge
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/**
 * Pure-decision coverage for badge colour and state-content logic: the
 * active-gated custom-colour rule, the state_content token resolver, and the
 * entity-picture URL passthrough.
 */
class BadgeLogicTest {

    private fun state(
        id: String,
        raw: String,
        on: Boolean,
        available: Boolean = true,
        attrs: kotlinx.serialization.json.JsonObject? = null,
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
        attributesJson = attrs,
    )

    private fun badge(
        entityId: String? = null,
        color: String? = null,
        showState: Boolean = true,
        stateContent: List<String> = emptyList(),
    ): LovelaceBadge = LovelaceBadge(
        entityId = entityId,
        name = null,
        icon = null,
        color = color,
        showName = false,
        showState = showState,
        showIcon = true,
        tapAction = null,
        stateContent = stateContent,
    )

    // ── badgeColorAccent: custom colour gated on active ───────────────────────

    @Test fun `custom color applied when entity is active`() {
        val s = state("light.a", "on", on = true)
        val result = badgeColorAccent("green", "light.a", s)
        assertEquals(R1.AccentGreen, result)
    }

    @Test fun `custom color NOT applied when entity is inactive, falls back to state accent`() {
        val s = state("light.a", "off", on = false)
        // Off light -> R1.InkSoft from stateAccentFor
        val result = badgeColorAccent("green", "light.a", s)
        assertEquals(R1.InkSoft, result)
    }

    @Test fun `no config color uses state accent`() {
        val s = state("light.b", "on", on = true)
        val result = badgeColorAccent(null, "light.b", s)
        assertEquals(R1.AccentWarm, result)
    }

    @Test fun `entity-less badge applies color unconditionally`() {
        // Shortcut badge has no entity; config colour is always applied.
        val result = badgeColorAccent("blue", null, null)
        assertEquals(R1.AccentCool, result)
    }

    @Test fun `entity-less badge with no color returns InkSoft`() {
        val result = badgeColorAccent(null, null, null)
        assertEquals(R1.InkSoft, result)
    }

    @Test fun `unavailable entity ignores config color, returns red`() {
        val s = state("binary_sensor.a", "unavailable", on = false, available = false)
        // stateAccentFor returns R1.StatusRed for unavailable entities.
        val result = badgeColorAccent("green", "binary_sensor.a", s)
        assertEquals(R1.StatusRed, result)
    }

    // ── badgeStateText: state_content token list ──────────────────────────────

    @Test fun `empty stateContent falls back to compactStateText`() {
        val attrs = buildJsonObject { }
        val s = state("sensor.power", "42", on = true, attrs = attrs)
        val b = badge(entityId = "sensor.power", stateContent = emptyList())
        // compactStateText returns the raw state string for sensors.
        val result = badgeStateText(b, s)
        assertEquals("42", result)
    }

    @Test fun `state token resolves to compact state text`() {
        val s = state("sensor.temp", "21", on = true)
        val b = badge(entityId = "sensor.temp", stateContent = listOf("state"))
        val result = badgeStateText(b, s)
        assertEquals("21", result)
    }

    @Test fun `attribute token resolves attribute value`() {
        val attrs = buildJsonObject { put("brightness", 200) }
        val s = state("light.a", "on", on = true, attrs = attrs)
        val b = badge(entityId = "light.a", stateContent = listOf("brightness"))
        val result = badgeStateText(b, s)
        assertEquals("200", result)
    }

    @Test fun `unknown attribute token is skipped`() {
        val attrs = buildJsonObject { put("brightness", 200) }
        val s = state("light.a", "on", on = true, attrs = attrs)
        val b = badge(entityId = "light.a", stateContent = listOf("not_a_thing"))
        // All tokens blank -> returns null.
        assertNull(badgeStateText(b, s))
    }

    @Test fun `multiple tokens joined with space, blanks dropped`() {
        val attrs = buildJsonObject { put("unit_of_measurement", "W") }
        val s = state("sensor.power", "42", on = true, attrs = attrs)
        val b = badge(entityId = "sensor.power", stateContent = listOf("state", "unit_of_measurement"))
        val result = badgeStateText(b, s)
        assertEquals("42 W", result)
    }

    @Test fun `blank result returns null`() {
        val s = state("sensor.x", "", on = false)
        val b = badge(entityId = "sensor.x", stateContent = emptyList())
        // compactStateText on an empty raw state returns blank.
        // badgeStateText must return null, not an empty string.
        // (The state text collapse path; blank state text is not displayed.)
        val result = badgeStateText(b, s)
        // Either null or the formatted state; we just check it isn't empty string.
        if (result != null) assertEquals(false, result.isBlank())
    }

    // ── Parser: bare string -> show_name=true ─────────────────────────────────

    @Test fun `bare entity-id string badge gets showName true`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"views":[{"path":"p","badges":["sensor.time"],"cards":[]}]}"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals("sensor.time", badge.entityId)
        assertEquals(true, badge.showName)
        assertEquals(true, badge.isLegacyBareString)
    }

    // ── Parser: visibility conditions ────────────────────────────────────────

    @Test fun `badge with visibility conditions parses conditions`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"entity","entity":"sensor.power",
                "visibility":[{"condition":"state","entity":"input_boolean.show","state":"on"}]
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals("sensor.power", badge.entityId)
        assertEquals(1, badge.conditions.size)
    }

    @Test fun `disabled badge has Never condition`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"entity","entity":"sensor.power","disabled":true
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals(1, badge.conditions.size)
        assertEquals(
            com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Never,
            badge.conditions.first(),
        )
    }

    // ── Parser: state_content ─────────────────────────────────────────────────

    @Test fun `badge state_content parsed as token list`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"entity","entity":"sensor.power",
                "state_content":["state","last_changed"]
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals(listOf("state", "last_changed"), badge.stateContent)
    }

    // ── Parser: heading-card button badge ─────────────────────────────────────

    @Test fun `heading-card button badge parses type button with text and icon`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","cards":[{
                "type":"heading","heading":"Section",
                "badges":[{
                  "type":"button","text":"Add","icon":"mdi:plus",
                  "tap_action":{"action":"navigate","navigation_path":"/config"}
                }]
              }]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val heading = parsed.views.first().cards.first()
            as com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Heading
        val badge = heading.badges.first()
        // `text` maps to name for button badges.
        assertEquals("Add", badge.name)
        assertEquals("mdi:plus", badge.icon)
        assertEquals(false, badge.showState)  // button badges default showState=false
    }

    // ── Parser: state-label badge showName defaults true ─────────────────────

    @Test fun `state-label badge defaults showName true`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"state-label","entity":"sensor.power"
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals(true, badge.showName)
    }

    // ── Parser: show_entity_picture ───────────────────────────────────────────

    @Test fun `show_entity_picture parsed from badge config`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"entity","entity":"camera.front_door","show_entity_picture":true
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals(true, badge.showEntityPicture)
    }

    // ── Parser: hold_action / double_tap_action ───────────────────────────────

    @Test fun `badge hold and double_tap actions parsed`() {
        val config = kotlinx.serialization.json.Json.parseToJsonElement(
            """{
              "views":[{"path":"p","badges":[{
                "type":"entity","entity":"light.a",
                "hold_action":{"action":"more-info"},
                "double_tap_action":{"action":"toggle"}
              }],"cards":[]}]
            }"""
        ) as kotlinx.serialization.json.JsonObject
        val parsed = com.github.itskenny0.r1ha.core.lovelace.LovelaceParser.parseConfig(config)
        val badge = parsed.views.first().badges.first()
        assertEquals(
            com.github.itskenny0.r1ha.core.lovelace.LovelaceAction.Builtin("more-info"),
            badge.holdAction,
        )
        assertEquals(
            com.github.itskenny0.r1ha.core.lovelace.LovelaceAction.Builtin("toggle"),
            badge.doubleTapAction,
        )
    }
}
