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

    @Test fun `sections view flattens concrete section cards and skips strategy sections`() {
        // HA's UI editor produces "sections" views (default since 2024.x) where
        // cards live under sections[].cards, not the legacy top-level cards[].
        // A strategy section carries no concrete cards and must be skipped, not
        // crash the parse.
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "type": "sections",
                    "path": "home",
                    "title": "Home",
                    "sections": [
                      {"type": "grid", "cards": [
                        {"type": "tile", "entity": "light.kitchen"},
                        {"type": "tile", "entity": "switch.fan"}
                      ]},
                      {"type": "grid", "cards": [
                        {"type": "entities", "entities": ["sensor.temp"]}
                      ]},
                      {"strategy": {"type": "area", "area": "living_room"}}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(3, cards.size)
        assertEquals("light.kitchen", (cards[0] as LovelaceCard.Tile).entityId)
        assertEquals("switch.fan", (cards[1] as LovelaceCard.Tile).entityId)
        assertEquals("sensor.temp", (cards[2] as LovelaceCard.Entities).entities.first().entityId)
    }

    @Test fun `view with both top-level cards and sections concatenates them`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "path": "mix",
                    "cards": [{"type": "tile", "entity": "light.a"}],
                    "sections": [{"type": "grid", "cards": [{"type": "tile", "entity": "light.b"}]}]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(2, cards.size)
        assertEquals("light.a", (cards[0] as LovelaceCard.Tile).entityId)
        assertEquals("light.b", (cards[1] as LovelaceCard.Tile).entityId)
    }

    @Test fun `parses sensor picture-glance picture-entity area history-graph alarm-panel map`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "path": "p",
                    "cards": [
                      {"type": "sensor", "entity": "sensor.power", "graph": "line", "hours_to_show": 12},
                      {"type": "picture-glance", "title": "Porch", "image": "/local/porch.jpg",
                       "entities": ["light.porch", "switch.porch"]},
                      {"type": "picture-entity", "entity": "camera.front", "show_state": false},
                      {"type": "area", "area": "living_room", "navigation_path": "/lovelace/0"},
                      {"type": "history-graph", "entities": ["sensor.a", "sensor.b"], "hours_to_show": 48},
                      {"type": "alarm-panel", "entity": "alarm_control_panel.home",
                       "states": ["arm_home", "arm_away"]},
                      {"type": "map", "entities": ["device_tracker.phone"], "hours_to_show": 6}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(7, cards.size)
        val sensor = cards[0] as LovelaceCard.Sensor
        assertEquals("sensor.power", sensor.entityId)
        assertTrue(sensor.graph)
        assertEquals(12, sensor.hoursToShow)
        val pg = cards[1] as LovelaceCard.PictureGlance
        assertEquals("/local/porch.jpg", pg.image)
        assertEquals(listOf("light.porch", "switch.porch"), pg.entities.map { it.entityId })
        val pe = cards[2] as LovelaceCard.PictureEntity
        assertEquals("camera.front", pe.entityId)
        assertEquals(false, pe.showState)
        val area = cards[3] as LovelaceCard.Area
        assertEquals("living_room", area.area)
        assertEquals("/lovelace/0", area.navigationPath)
        val hg = cards[4] as LovelaceCard.HistoryGraph
        assertEquals(48, hg.hoursToShow)
        assertEquals(2, hg.entities.size)
        val alarm = cards[5] as LovelaceCard.AlarmPanel
        assertEquals("alarm_control_panel.home", alarm.entityId)
        assertEquals(listOf("arm_home", "arm_away"), alarm.states)
        val map = cards[6] as LovelaceCard.Map
        assertEquals(listOf("device_tracker.phone"), map.entities.map { it.entityId })
        assertEquals(6, map.hoursToShow)
    }

    @Test fun `sensor without graph defaults to no line and 24h`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"sensor","entity":"sensor.x"}"""),
        ) as LovelaceCard.Sensor
        assertEquals(false, card.graph)
        assertEquals(24, card.hoursToShow)
    }

    @Test fun `sensor and area without required entity fall to Unsupported`() {
        val noEntity = LovelaceParser.parseCard(obj("""{"type":"sensor"}"""))
        assertTrue(noEntity is LovelaceCard.Unsupported)
        val noArea = LovelaceParser.parseCard(obj("""{"type":"area"}"""))
        assertTrue(noArea is LovelaceCard.Unsupported)
    }

    @Test fun `picture-glance uses entity as camera image when camera_image absent`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"picture-glance","entity":"camera.kitchen","entities":["light.k"]}"""),
        ) as LovelaceCard.PictureGlance
        assertEquals("camera.kitchen", card.cameraImage)
    }

    @Test fun `root-level strategy with no views flags isStrategyGenerated`() {
        val cfg = LovelaceParser.parseConfig(
            obj("""{"strategy":{"type":"original-states"}}"""),
        )
        assertTrue(cfg.isStrategyGenerated)
        assertTrue(cfg.views.isEmpty())
    }

    @Test fun `view-level strategy with no cards flags the view but a concrete view does not`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [
                    {"path": "auto", "strategy": {"type": "areas"}},
                    {"path": "manual", "cards": [{"type": "tile", "entity": "light.a"}]}
                  ]
                }
                """.trimIndent(),
            ),
        )
        val auto = cfg.views[0]
        val manual = cfg.views[1]
        assertTrue(auto.isStrategyGenerated)
        assertTrue(auto.cards.isEmpty())
        assertTrue(!manual.isStrategyGenerated)
        // Mixed dashboard (one strategy view + one concrete view) is NOT a
        // wholly strategy-generated dashboard.
        assertTrue(!cfg.isStrategyGenerated)
    }

    @Test fun `sections all-strategy view flags isStrategyGenerated`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "type": "sections",
                    "path": "home",
                    "sections": [
                      {"strategy": {"type": "area", "area": "kitchen"}},
                      {"strategy": {"type": "area", "area": "bedroom"}}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val view = cfg.views.first()
        assertTrue(view.isStrategyGenerated)
        assertTrue(view.cards.isEmpty())
        assertTrue(cfg.isStrategyGenerated)
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
