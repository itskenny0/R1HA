package com.github.itskenny0.r1ha.core.lovelace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LovelaceParserTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test fun `parses a minimal entities card`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "title": "Home",
                  "views": [
                    {
                      "path": "default_view",
                      "title": "Default",
                      "cards": [
                        {"type": "entities", "title": "Lights",
                         "entities": ["light.kitchen", {"entity": "light.bedroom", "name": "Master"}]}
                      ]
                    }
                  ]
                }
                """.trimIndent(),
            ),
        )
        assertEquals("Home", cfg.title)
        assertEquals(1, cfg.views.size)
        val view = cfg.views.first()
        assertEquals("default_view", view.path)
        val card = view.cards.first() as LovelaceCard.Entities
        assertEquals(2, card.entities.size)
        assertEquals("light.kitchen", card.entities[0].entityId)
        assertEquals(null, card.entities[0].name)
        assertEquals("light.bedroom", card.entities[1].entityId)
        assertEquals("Master", card.entities[1].name)
    }

    @Test fun `parses tile glance button light gauge weather markdown heading conditional`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "path": "p",
                    "cards": [
                      {"type": "tile", "entity": "light.kitchen", "vertical": true},
                      {"type": "glance", "entities": ["sensor.a"], "columns": 3},
                      {"type": "button", "name": "Go", "tap_action": {"action": "toggle"}},
                      {"type": "light", "entity": "light.bedroom"},
                      {"type": "gauge", "entity": "sensor.power", "min": 0, "max": 5000,
                       "severity": {"green": 0, "yellow": 2000, "red": 4000}},
                      {"type": "weather-forecast", "entity": "weather.home"},
                      {"type": "markdown", "content": "Hello"},
                      {"type": "heading", "heading": "Section"},
                      {"type": "conditional",
                       "conditions": [{"entity": "sun.sun", "state": "below_horizon"}],
                       "card": {"type": "markdown", "content": "Night"}}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(9, cards.size)
        val tile = cards[0] as LovelaceCard.Tile
        assertTrue(tile.vertical)
        assertEquals("light.kitchen", tile.entityId)
        val glance = cards[1] as LovelaceCard.Glance
        assertEquals(3, glance.columns)
        val button = cards[2] as LovelaceCard.Button
        assertEquals(LovelaceAction.Builtin("toggle"), button.tapAction)
        cards[3] as LovelaceCard.Light
        val gauge = cards[4] as LovelaceCard.Gauge
        assertEquals(5000.0, gauge.max, 0.001)
        assertNotNull(gauge.severity)
        cards[5] as LovelaceCard.WeatherForecast
        val md = cards[6] as LovelaceCard.Markdown
        assertEquals("Hello", md.content)
        cards[7] as LovelaceCard.Heading
        val cond = cards[8] as LovelaceCard.Conditional
        val condition = cond.conditions.first() as LovelaceCondition.StateEquals
        assertEquals("below_horizon", condition.state)
    }

    @Test fun `unknown card types preserve raw JSON in Unsupported`() {
        val cfg = LovelaceParser.parseConfig(
            obj("""{"views":[{"path":"p","cards":[{"type":"custom:foo","wibble":42}]}]}"""),
        )
        val card = cfg.views.first().cards.first() as LovelaceCard.Unsupported
        assertEquals("custom:foo", card.type)
        assertEquals("42", card.raw["wibble"].toString())
    }

    @Test fun `bare entity_id string in glance entities resolves to a row`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"glance","entities":["sensor.x","sensor.y"]}"""),
        ) as LovelaceCard.Glance
        assertEquals(listOf("sensor.x", "sensor.y"), card.entities.map { it.entityId })
    }

    @Test fun `nested stack cards parse recursively`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"vertical-stack","cards":[
                  {"type":"horizontal-stack","cards":[
                    {"type":"button","name":"A"},
                    {"type":"button","name":"B"}
                  ]},
                  {"type":"grid","columns":2,"cards":[
                    {"type":"tile","entity":"sensor.x"}
                  ]}
                ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.VerticalStack
        val h = card.cards[0] as LovelaceCard.HorizontalStack
        assertEquals(2, h.cards.size)
        val g = card.cards[1] as LovelaceCard.Grid
        assertEquals(2, g.columns)
        assertEquals(1, g.cards.size)
    }

    @Test fun `dashboard list parses entries and skips malformed rows`() {
        val arr = (Json.parseToJsonElement(
            """[{"id":"a","url_path":"lights","title":"Lights","mode":"storage"},
                {"id":"b","title":"No url"}]""".trimIndent(),
        ) as kotlinx.serialization.json.JsonArray)
        val list = LovelaceParser.parseDashboards(arr)
        assertEquals(2, list.size)
        assertEquals("lights", list[0].urlPath)
        assertEquals("No url", list[1].title)
    }
}
