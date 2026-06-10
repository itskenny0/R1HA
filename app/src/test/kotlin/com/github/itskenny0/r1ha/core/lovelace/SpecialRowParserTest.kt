package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parser coverage for entities-card special row types and the explicit type override.
 * Each test round-trips a JSON fragment through [LovelaceParser.parseCard] and
 * verifies the typed model.
 */
class SpecialRowParserTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    /** Build a minimal entities card JSON with the given items array. */
    private fun entitiesCard(itemsJson: String): LovelaceCard.Entities =
        LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":$itemsJson}"""),
        ) as LovelaceCard.Entities

    private fun items(itemsJson: String): List<EntitiesItem> =
        entitiesCard(itemsJson).rowItems

    private fun special(itemsJson: String): SpecialRow {
        val item = items(itemsJson).single()
        return (item as EntitiesItem.Special).row
    }

    // ── Section row ───────────────────────────────────────────────────────────

    @Test fun `section row with label`() {
        val r = special("""[{"type":"section","label":"My section"}]""") as SpecialRow.Section
        assertEquals("My section", r.label)
    }

    @Test fun `section row without label`() {
        val r = special("""[{"type":"section"}]""") as SpecialRow.Section
        assertNull(r.label)
    }

    // ── Divider row ───────────────────────────────────────────────────────────

    @Test fun `divider row`() {
        val r = special("""[{"type":"divider"}]""")
        assertTrue(r is SpecialRow.Divider)
    }

    // ── Attribute row ─────────────────────────────────────────────────────────

    @Test fun `attribute row with all fields`() {
        val r = special(
            """[{"type":"attribute","entity":"sensor.x","attribute":"voltage",
                "name":"Volts","icon":"mdi:lightning","prefix":"~","suffix":" V",
                "format":"relative"}]""",
        ) as SpecialRow.Attribute
        assertEquals("sensor.x", r.entityId)
        assertEquals("voltage", r.attribute)
        assertEquals("Volts", r.name)
        assertEquals("mdi:lightning", r.icon)
        assertEquals("~", r.prefix)
        assertEquals(" V", r.suffix)
        assertEquals(TimestampFormat.RELATIVE, r.format)
    }

    @Test fun `attribute row minimal`() {
        val r = special(
            """[{"type":"attribute","entity":"sensor.x","attribute":"temperature"}]""",
        ) as SpecialRow.Attribute
        assertNull(r.name)
        assertNull(r.prefix)
        assertNull(r.suffix)
        assertNull(r.format)
    }

    @Test fun `attribute row without entity is dropped`() {
        // attribute without entity is required; the row should be dropped.
        val list = items("""[{"type":"attribute","attribute":"voltage"}]""")
        assertTrue("expected empty list, got: $list", list.isEmpty())
    }

    // ── Button row ────────────────────────────────────────────────────────────

    @Test fun `button row with name and tap_action`() {
        val r = special(
            """[{"type":"button","name":"Run script","action_name":"GO",
                "tap_action":{"action":"call-service","service":"script.run","target":{"entity_id":"script.x"}}}]""",
        ) as SpecialRow.Button
        assertEquals("Run script", r.name)
        assertEquals("GO", r.actionName)
        val action = r.tapAction as LovelaceAction.CallService
        assertEquals("script.run", action.service)
        assertEquals("script.x", action.entityId)
    }

    @Test fun `button row with entity but no name`() {
        val r = special(
            """[{"type":"button","entity":"script.run_all"}]""",
        ) as SpecialRow.Button
        assertEquals("script.run_all", r.entityId)
        assertNull(r.name)
    }

    @Test fun `button row without name or entity is dropped`() {
        val list = items("""[{"type":"button"}]""")
        assertTrue("expected empty list", list.isEmpty())
    }

    // ── call-service / perform-action row ─────────────────────────────────────

    @Test fun `call-service row with service and data`() {
        val r = special(
            """[{"type":"call-service","name":"Toggle fan","service":"fan.toggle",
                "target":{"entity_id":"fan.bedroom"}}]""",
        ) as SpecialRow.Button
        assertEquals("Toggle fan", r.name)
        val action = r.tapAction as LovelaceAction.CallService
        assertEquals("fan.toggle", action.service)
        assertEquals("fan.bedroom", action.entityId)
    }

    @Test fun `perform-action row with action key`() {
        val r = special(
            """[{"type":"perform-action","name":"Restart","action":"homeassistant.restart"}]""",
        ) as SpecialRow.Button
        assertEquals("Restart", r.name)
        val action = r.tapAction as LovelaceAction.CallService
        assertEquals("homeassistant.restart", action.service)
    }

    @Test fun `call-service row without name is dropped`() {
        val list = items("""[{"type":"call-service","service":"light.turn_on"}]""")
        assertTrue("expected empty list", list.isEmpty())
    }

    // ── Buttons row ───────────────────────────────────────────────────────────

    @Test fun `buttons row with multiple entries`() {
        val r = special(
            """[{"type":"buttons","entities":[
                {"entity":"light.a","icon":"mdi:lamp","name":"Lamp",
                 "tap_action":{"action":"toggle"}},
                "switch.b"
               ]}]""",
        ) as SpecialRow.Buttons
        assertThat(r.entries).hasSize(2)
        val first = r.entries[0]
        assertEquals("light.a", first.entityId)
        assertEquals("mdi:lamp", first.icon)
        assertEquals("Lamp", first.name)
        assertTrue(first.tapAction is LovelaceAction.Builtin)
        val second = r.entries[1]
        assertEquals("switch.b", second.entityId)
        assertNull(second.icon)
    }

    @Test fun `buttons row with no entries`() {
        val r = special("""[{"type":"buttons"}]""") as SpecialRow.Buttons
        assertTrue(r.entries.isEmpty())
    }

    // ── Conditional row ───────────────────────────────────────────────────────

    @Test fun `conditional row wrapping entity row`() {
        val r = special(
            """[{"type":"conditional",
                "conditions":[{"condition":"state","entity":"sun.sun","state":"above_horizon"}],
                "row":{"entity":"light.kitchen","name":"Kitchen"}}]""",
        ) as SpecialRow.Conditional
        assertThat(r.conditions).hasSize(1)
        val cond = r.conditions.single() as LovelaceCondition.StateEquals
        assertEquals("sun.sun", cond.entityId)
        val payload = r.row as ConditionalRowPayload.EntityRowPayload
        assertEquals("light.kitchen", payload.row.entityId)
        assertEquals("Kitchen", payload.row.name)
    }

    @Test fun `conditional row wrapping a section special row`() {
        val r = special(
            """[{"type":"conditional",
                "conditions":[{"condition":"state","entity":"input_boolean.x","state":"on"}],
                "row":{"type":"section","label":"Night"}}]""",
        ) as SpecialRow.Conditional
        val payload = r.row as ConditionalRowPayload.SpecialRowPayload
        val section = payload.row as SpecialRow.Section
        assertEquals("Night", section.label)
    }

    @Test fun `conditional row without row is dropped`() {
        val list = items(
            """[{"type":"conditional","conditions":[{"entity":"x","state":"on"}]}]""",
        )
        assertTrue("expected empty, got: $list", list.isEmpty())
    }

    @Test fun `conditional row with bare entity string in row`() {
        val r = special(
            """[{"type":"conditional",
                "conditions":[{"entity":"light.k","state":"on"}],
                "row":"sensor.temp"}]""",
        ) as SpecialRow.Conditional
        val payload = r.row as ConditionalRowPayload.EntityRowPayload
        assertEquals("sensor.temp", payload.row.entityId)
    }

    // ── Text row ─────────────────────────────────────────────────────────────

    @Test fun `text row with all fields`() {
        val r = special(
            """[{"type":"text","name":"Status","text":"All good","icon":"mdi:check"}]""",
        ) as SpecialRow.Text
        assertEquals("Status", r.name)
        assertEquals("All good", r.text)
        assertEquals("mdi:check", r.icon)
    }

    @Test fun `text row without name is dropped`() {
        val list = items("""[{"type":"text","text":"hello"}]""")
        assertTrue("expected empty", list.isEmpty())
    }

    @Test fun `text row without text is dropped`() {
        val list = items("""[{"type":"text","name":"x"}]""")
        assertTrue("expected empty", list.isEmpty())
    }

    // ── Weblink row ───────────────────────────────────────────────────────────

    @Test fun `weblink row with all fields`() {
        val r = special(
            """[{"type":"weblink","url":"https://example.com","name":"Docs","icon":"mdi:book"}]""",
        ) as SpecialRow.Weblink
        assertEquals("https://example.com", r.url)
        assertEquals("Docs", r.name)
        assertEquals("mdi:book", r.icon)
    }

    @Test fun `weblink row uses url as name when name absent`() {
        val r = special(
            """[{"type":"weblink","url":"https://ha.io"}]""",
        ) as SpecialRow.Weblink
        assertEquals("https://ha.io", r.name)
        // Parser pre-populates icon with mdi:link when absent.
        assertEquals("mdi:link", r.icon)
    }

    @Test fun `weblink row without url is dropped`() {
        val list = items("""[{"type":"weblink","name":"Missing"}]""")
        assertTrue("expected empty", list.isEmpty())
    }

    // ── Cast row ──────────────────────────────────────────────────────────────

    @Test fun `cast row`() {
        val r = special("""[{"type":"cast"}]""")
        assertTrue(r is SpecialRow.Cast)
    }

    // ── Unknown row ───────────────────────────────────────────────────────────

    @Test fun `unknown special row type becomes Unknown with typeName`() {
        val r = special("""[{"type":"custom:my-row"}]""") as SpecialRow.Unknown
        assertEquals("custom:my-row", r.typeName)
    }

    // ── Entity-less survival and order preservation ───────────────────────────

    @Test fun `mixed entity and special rows preserve order`() {
        val card = entitiesCard(
            """["light.a",{"type":"section","label":"Group"},{"entity":"sensor.temp"},
               {"type":"divider"},"switch.b"]""",
        )
        assertThat(card.rowItems).hasSize(5)
        assertThat(card.rowItems[0]).isInstanceOf(EntitiesItem.Entity::class.java)
        assertThat(card.rowItems[1]).isInstanceOf(EntitiesItem.Special::class.java)
        assertThat((card.rowItems[1] as EntitiesItem.Special).row).isInstanceOf(SpecialRow.Section::class.java)
        assertThat(card.rowItems[2]).isInstanceOf(EntitiesItem.Entity::class.java)
        assertThat(card.rowItems[3]).isInstanceOf(EntitiesItem.Special::class.java)
        assertThat((card.rowItems[3] as EntitiesItem.Special).row).isInstanceOf(SpecialRow.Divider::class.java)
        assertThat(card.rowItems[4]).isInstanceOf(EntitiesItem.Entity::class.java)
    }

    @Test fun `backward compat entities property only returns entity rows`() {
        val card = entitiesCard(
            """["light.a",{"type":"section"},"sensor.b",{"type":"divider"}]""",
        )
        assertEquals(listOf("light.a", "sensor.b"), card.entities.map { it.entityId })
    }

    // ── Explicit type override on entity rows ─────────────────────────────────

    @Test fun `entity row with explicit type stores explicitType`() {
        val card = entitiesCard(
            """[{"entity":"light.k","type":"toggle"}]""",
        )
        val row = (card.rowItems.single() as EntitiesItem.Entity).row
        assertEquals("light.k", row.entityId)
        assertEquals("toggle", row.explicitType)
    }

    @Test fun `entity row without explicit type has null explicitType`() {
        val card = entitiesCard("""["light.k"]""")
        val row = (card.rowItems.single() as EntitiesItem.Entity).row
        assertNull(row.explicitType)
    }

    // ── TimestampFormat parsing ───────────────────────────────────────────────

    @Test fun `format field maps to TimestampFormat enum`() {
        val cases = mapOf(
            "relative" to TimestampFormat.RELATIVE,
            "total" to TimestampFormat.TOTAL,
            "date" to TimestampFormat.DATE,
            "time" to TimestampFormat.TIME,
            "datetime" to TimestampFormat.DATETIME,
        )
        cases.forEach { (fmt, expected) ->
            val card = entitiesCard("""[{"entity":"sensor.x","format":"$fmt"}]""")
            val row = (card.rowItems.single() as EntitiesItem.Entity).row
            assertEquals("format $fmt", expected, row.format)
        }
    }

    @Test fun `unknown format leaves format null`() {
        val card = entitiesCard("""[{"entity":"sensor.x","format":"hour"}]""")
        val row = (card.rowItems.single() as EntitiesItem.Entity).row
        assertNull(row.format)
    }
}
