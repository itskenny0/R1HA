package com.github.itskenny0.r1ha.core.lovelace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies that recognised community / custom card types are routed to the
 * nearest native [LovelaceCard] instead of falling through to the generic
 * [LovelaceCard.Unsupported] best-effort row, and that a custom card with no
 * entity stays a tasteful Unsupported fallback.
 */
class LovelaceCustomCardMappingTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `mushroom-light-card maps to Light`() {
        val c = card("""{"type":"custom:mushroom-light-card","entity":"light.kitchen","name":"Kitchen"}""")
        assertTrue(c is LovelaceCard.Light)
        c as LovelaceCard.Light
        assertEquals("light.kitchen", c.entityId)
        assertEquals("Kitchen", c.name)
    }

    @Test fun `mushroom-media-player-card maps to MediaControl`() {
        val c = card("""{"type":"custom:mushroom-media-player-card","entity":"media_player.den"}""")
        assertTrue(c is LovelaceCard.MediaControl)
        assertEquals("media_player.den", (c as LovelaceCard.MediaControl).entityId)
    }

    @Test fun `mushroom-climate-card maps to Thermostat`() {
        val c = card("""{"type":"custom:mushroom-climate-card","entity":"climate.living_room"}""")
        assertTrue(c is LovelaceCard.Thermostat)
        assertEquals("climate.living_room", (c as LovelaceCard.Thermostat).entityId)
    }

    @Test fun `mushroom-fan-card maps to Tile`() {
        val c = card("""{"type":"custom:mushroom-fan-card","entity":"fan.bedroom"}""")
        assertTrue(c is LovelaceCard.Tile)
        assertEquals("fan.bedroom", (c as LovelaceCard.Tile).entityId)
    }

    @Test fun `mushroom-cover-card maps to Tile`() {
        val c = card("""{"type":"custom:mushroom-cover-card","entity":"cover.garage"}""")
        assertTrue(c is LovelaceCard.Tile)
        assertEquals("cover.garage", (c as LovelaceCard.Tile).entityId)
    }

    @Test fun `mushroom-person-card maps to Tile`() {
        val c = card("""{"type":"custom:mushroom-person-card","entity":"person.alex"}""")
        assertTrue(c is LovelaceCard.Tile)
        assertEquals("person.alex", (c as LovelaceCard.Tile).entityId)
    }

    @Test fun `mushroom-entity-card maps to Tile and preserves tap_action`() {
        val c = card(
            """{"type":"custom:mushroom-entity-card","entity":"sensor.temp","name":"Temp",
               "tap_action":{"action":"navigate","navigation_path":"/lovelace/0"}}""",
        )
        assertTrue(c is LovelaceCard.Tile)
        c as LovelaceCard.Tile
        assertEquals("sensor.temp", c.entityId)
        assertEquals("Temp", c.name)
        assertEquals("/lovelace/0", (c.tapAction as LovelaceAction.Navigate).path)
    }

    @Test fun `mushroom-template-card uses explicit entity and plain primary as name`() {
        val c = card(
            """{"type":"custom:mushroom-template-card","entity":"switch.pump",
               "primary":"Pool Pump","icon":"mdi:water"}""",
        )
        assertTrue(c is LovelaceCard.Tile)
        c as LovelaceCard.Tile
        assertEquals("switch.pump", c.entityId)
        assertEquals("Pool Pump", c.name)
        assertEquals("mdi:water", c.icon)
    }

    @Test fun `mushroom-template-card scrapes entity from template when no entity key`() {
        val c = card(
            """{"type":"custom:mushroom-template-card",
               "primary":"{{ states('sensor.power') }} W","secondary":"Power"}""",
        )
        assertTrue(c is LovelaceCard.Tile)
        c as LovelaceCard.Tile
        assertEquals("sensor.power", c.entityId)
        // primary is a template, so it must not become the plain-text name.
        assertNull(c.name)
    }

    @Test fun `mushroom-chips-card maps to a glance of entity chips`() {
        val c = card(
            """{"type":"custom:mushroom-chips-card","chips":[
                 {"type":"entity","entity":"binary_sensor.door"},
                 {"type":"entity","entity":"light.hall","icon":"mdi:lightbulb"},
                 {"type":"weather","entity":"weather.home"}
               ]}""",
        )
        assertTrue(c is LovelaceCard.Glance)
        c as LovelaceCard.Glance
        assertEquals(
            listOf("binary_sensor.door", "light.hall", "weather.home"),
            c.entities.map { it.entityId },
        )
    }

    @Test fun `button-card maps to Button with name icon entity and tap_action`() {
        val c = card(
            """{"type":"custom:button-card","entity":"switch.fountain","name":"Fountain",
               "icon":"mdi:fountain","tap_action":{"action":"toggle"}}""",
        )
        assertTrue(c is LovelaceCard.Button)
        c as LovelaceCard.Button
        assertEquals("switch.fountain", c.entityId)
        assertEquals("Fountain", c.name)
        assertEquals("mdi:fountain", c.icon)
        assertEquals("toggle", (c.tapAction as LovelaceAction.Builtin).name)
    }

    @Test fun `button-card with only a name still maps to Button`() {
        val c = card("""{"type":"custom:button-card","name":"Run scene"}""")
        assertTrue(c is LovelaceCard.Button)
        c as LovelaceCard.Button
        assertNull(c.entityId)
        assertEquals("Run scene", c.name)
    }

    @Test fun `custom card with no entity stays a tasteful Unsupported fallback`() {
        // A mushroom-light-card without an entity can't be mapped to Light, so it
        // drops to the best-effort Unsupported card (no entityRefs, no url here).
        val c = card("""{"type":"custom:mushroom-light-card","name":"Orphan"}""")
        assertTrue(c is LovelaceCard.Unsupported)
        c as LovelaceCard.Unsupported
        assertEquals("custom:mushroom-light-card", c.type)
        assertTrue(c.entityRefs.isEmpty())
    }

    @Test fun `unrecognised custom card stays Unsupported`() {
        val c = card("""{"type":"custom:some-unknown-card"}""")
        assertTrue(c is LovelaceCard.Unsupported)
        assertEquals("custom:some-unknown-card", (c as LovelaceCard.Unsupported).type)
    }
}
