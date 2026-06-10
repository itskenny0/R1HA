package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import java.util.Locale
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
        assertEquals(listOf("below_horizon"), condition.states)
        assertEquals(false, condition.negate)
    }

    @Test fun `unknown card types preserve raw JSON in Unsupported`() {
        val cfg = LovelaceParser.parseConfig(
            obj("""{"views":[{"path":"p","cards":[{"type":"custom:foo","wibble":42}]}]}"""),
        )
        val card = cfg.views.first().cards.first() as LovelaceCard.Unsupported
        assertEquals("custom:foo", card.type)
        assertEquals("42", card.raw["wibble"].toString())
    }

    @Test fun `unknown condition type fails closed to Never`() {
        // A `template` condition can't be evaluated locally (no Jinja engine), so it
        // must hide the card, not leak it. (`screen` is handled separately and fails
        // open to AlwaysTrue, matching single-window semantics.)
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"conditional",
                 "conditions":[{"condition":"template","value_template":"{{ true }}"}],
                 "card":{"type":"button","name":"A"}}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Conditional
        assertEquals(LovelaceCondition.Never, card.conditions.first())
    }

    @Test fun `state_not condition parses as a negated state-equals`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"conditional",
                 "conditions":[{"condition":"state_not","entity":"light.k","state_not":"off"}],
                 "card":{"type":"button","name":"A"}}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Conditional
        val cond = card.conditions.first() as LovelaceCondition.StateEquals
        assertEquals(listOf("off"), cond.states)
        assertTrue(cond.negate)
    }

    @Test fun `state condition accepts a list of states`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"conditional",
                 "conditions":[{"condition":"state","entity":"alarm.x","state":["armed_home","armed_away"]}],
                 "card":{"type":"button","name":"A"}}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Conditional
        val cond = card.conditions.first() as LovelaceCondition.StateEquals
        assertEquals(listOf("armed_home", "armed_away"), cond.states)
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

    @Test fun `parses thermostat media-control and humidifier cards`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "path": "p",
                    "cards": [
                      {"type": "thermostat", "entity": "climate.living_room", "name": "Lounge"},
                      {"type": "media-control", "entity": "media_player.kitchen"},
                      {"type": "humidifier", "entity": "humidifier.bedroom"}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(3, cards.size)
        val thermostat = cards[0] as LovelaceCard.Thermostat
        assertEquals("climate.living_room", thermostat.entityId)
        assertEquals("Lounge", thermostat.name)
        val media = cards[1] as LovelaceCard.MediaControl
        assertEquals("media_player.kitchen", media.entityId)
        val humidifier = cards[2] as LovelaceCard.Humidifier
        assertEquals("humidifier.bedroom", humidifier.entityId)
    }

    @Test fun `thermostat media-control and humidifier without entity fall to Unsupported`() {
        assertTrue(LovelaceParser.parseCard(obj("""{"type":"thermostat"}""")) is LovelaceCard.Unsupported)
        assertTrue(LovelaceParser.parseCard(obj("""{"type":"media-control"}""")) is LovelaceCard.Unsupported)
        assertTrue(LovelaceParser.parseCard(obj("""{"type":"humidifier"}""")) is LovelaceCard.Unsupported)
    }

    @Test fun `custom card with single entity captures entity ref`() {
        // An UNMAPPED custom type still falls to the best-effort Unsupported card
        // and captures its entity ref. Recognised types like mushroom-light-card
        // are now routed to native cards instead (see LovelaceCustomCardMappingTest).
        val card = LovelaceParser.parseCard(
            obj("""{"type":"custom:my-single","entity":"light.kitchen"}"""),
        ) as LovelaceCard.Unsupported
        assertEquals("custom:my-single", card.type)
        assertEquals(listOf("light.kitchen"), card.entityRefs)
        assertEquals("my-single", card.friendlyType)
        assertEquals(null, card.url)
    }

    @Test fun `custom card with entities array captures all entity refs`() {
        val card = LovelaceParser.parseCard(
            obj(
                """{"type":"custom:auto-entities",
                    "entities":["light.a", {"entity":"switch.b"}, "sensor.c"]}""",
            ),
        ) as LovelaceCard.Unsupported
        assertEquals(listOf("light.a", "switch.b", "sensor.c"), card.entityRefs)
    }

    @Test fun `iframe card captures url`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"iframe","url":"https://example.com/panel","aspect_ratio":"50%"}"""),
        ) as LovelaceCard.Unsupported
        assertEquals("https://example.com/panel", card.url)
        assertTrue(card.entityRefs.isEmpty())
    }

    @Test fun `unknown card with neither entity nor url stays plain Unsupported`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"custom:weird-thing","foo":"bar"}"""),
        ) as LovelaceCard.Unsupported
        assertTrue(card.entityRefs.isEmpty())
        assertEquals(null, card.url)
        assertEquals("bar", (card.raw["foo"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test fun `custom card skips non-entity-shaped strings in entities`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"custom:thing","entity":"not an entity","entities":["light.ok","template stuff"]}"""),
        ) as LovelaceCard.Unsupported
        assertEquals(listOf("light.ok"), card.entityRefs)
    }

    @Test fun `parses entity-filter statistic logbook and clock cards`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {
                  "views": [{
                    "path": "p",
                    "cards": [
                      {"type": "entity-filter", "title": "Lights on",
                       "entities": ["light.a", {"entity": "light.b", "name": "Bee"}],
                       "state_filter": ["on", {"value": "home"}]},
                      {"type": "statistic", "entity": "sensor.power",
                       "stat_type": "MEAN", "period": "week", "name": "Avg"},
                      {"type": "logbook", "title": "Recent",
                       "target": {"entity_id": ["light.a", "switch.b"]},
                       "hours_to_show": 6},
                      {"type": "clock", "title": "Now",
                       "clock_style": "analog", "show_seconds": true}
                    ]
                  }]
                }
                """.trimIndent(),
            ),
        )
        val cards = cfg.views.first().cards
        assertEquals(4, cards.size)

        val filter = cards[0] as LovelaceCard.EntityFilter
        assertEquals("Lights on", filter.title)
        assertEquals(2, filter.entities.size)
        assertEquals("light.a", filter.entities[0].entityId)
        assertEquals("Bee", filter.entities[1].name)
        assertEquals(
            listOf("on", "home"),
            filter.stateFilter.map { it.value },
        )
        assertTrue(filter.stateFilter.all { it.operator == StateFilterOperator.EQ })
        assertTrue(filter.showEmpty)

        val stat = cards[1] as LovelaceCard.Statistic
        assertEquals("sensor.power", stat.entityId)
        assertEquals("mean", stat.statType)
        assertEquals("week", stat.period)
        assertEquals("Avg", stat.name)

        val logbook = cards[2] as LovelaceCard.Logbook
        assertEquals("Recent", logbook.title)
        assertEquals(listOf("light.a", "switch.b"), logbook.entities)
        assertEquals(6, logbook.hoursToShow)

        val clock = cards[3] as LovelaceCard.Clock
        assertEquals("Now", clock.title)
        assertTrue(clock.analog)
        assertTrue(clock.showSeconds)
    }

    @Test fun `statistic accepts entities list and defaults stat_type and period`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"statistic","entities":["sensor.energy"]}"""),
        ) as LovelaceCard.Statistic
        assertEquals("sensor.energy", card.entityId)
        assertEquals("mean", card.statType)
        assertEquals("day", card.period)
    }

    @Test fun `logbook falls back to deprecated entities list`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"logbook","entities":["light.x","light.y"]}"""),
        ) as LovelaceCard.Logbook
        assertEquals(listOf("light.x", "light.y"), card.entities)
        assertEquals(12, card.hoursToShow)
    }

    @Test fun `entity-filter without filter keeps empty filter list and clock defaults to digital`() {
        val filter = LovelaceParser.parseCard(
            obj("""{"type":"entity-filter","entities":["light.a"]}"""),
        ) as LovelaceCard.EntityFilter
        assertTrue(filter.stateFilter.isEmpty())
        val clock = LovelaceParser.parseCard(obj("""{"type":"clock"}""")) as LovelaceCard.Clock
        assertTrue(!clock.analog)
        assertTrue(!clock.showSeconds)
    }

    @Test fun `statistic without entity falls to Unsupported`() {
        assertTrue(LovelaceParser.parseCard(obj("""{"type":"statistic"}""")) is LovelaceCard.Unsupported)
        assertTrue(
            LovelaceParser.parseCard(obj("""{"type":"statistic","entities":[]}""")) is LovelaceCard.Unsupported,
        )
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

    // ----------------------------------------------------------------
    // Conditional card: full conditions schema + fail-closed
    // ----------------------------------------------------------------
    private fun conditions(conditionsJson: String): List<LovelaceCondition> {
        val json = """
            {"type":"conditional","conditions":$conditionsJson,
             "card":{"type":"markdown","content":"x"}}
        """.trimIndent()
        return (LovelaceParser.parseCard(obj(json)) as LovelaceCard.Conditional).conditions
    }

    @Test
    fun `parses state condition with a list of accepted states`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"state","entity":"s.mode","state":["home","away"]}]""")
        val s = parsed.single() as LovelaceCondition.StateEquals
        assertThat(s.entityId).isEqualTo("s.mode")
        assertThat(s.states).containsExactly("home", "away")
        assertThat(s.negate).isFalse()
    }

    @Test
    fun `parses state_not condition`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"state_not","entity":"s.mode","state_not":"off"}]""")
        val s = parsed.single() as LovelaceCondition.StateEquals
        assertThat(s.states).containsExactly("off")
        assertThat(s.negate).isTrue()
    }

    @Test
    fun `parses legacy entity-state shorthand`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"entity":"light.k","state":"on"}]""")
        val s = parsed.single() as LovelaceCondition.StateEquals
        assertThat(s.entityId).isEqualTo("light.k")
        assertThat(s.states).containsExactly("on")
    }

    @Test
    fun `state condition without an entity keeps a null entity for the context fallback`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"state","state":"on"}]""")
        val s = parsed.single() as LovelaceCondition.StateEquals
        assertThat(s.entityId).isNull()
        assertThat(s.states).containsExactly("on")
    }

    @Test
    fun `state condition with no accepted values fails closed as Never`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"state","entity":"light.k"}]""")
        assertThat(parsed.single()).isEqualTo(LovelaceCondition.Never)
    }

    @Test
    fun `parses numeric_state with both bounds`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"numeric_state","entity":"s.t","above":10,"below":20.5}]""")
        val n = parsed.single() as LovelaceCondition.NumericState
        assertThat(n.above).isEqualTo(10.0)
        assertThat(n.below).isEqualTo(20.5)
    }

    @Test
    fun `parses numeric_state with only above`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"numeric_state","entity":"s.t","above":5}]""")
        val n = parsed.single() as LovelaceCondition.NumericState
        assertThat(n.above).isEqualTo(5.0)
        assertThat(n.below).isNull()
    }

    @Test
    fun `numeric_state with no bound fails closed as Never`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"numeric_state","entity":"s.t"}]""")
        assertThat(parsed.single()).isEqualTo(LovelaceCondition.Never)
    }

    @Test
    fun `numeric_state with a non-numeric bound never resolves so it fails closed`() {
        // The reported bug: `above: never` on a timestamp helper used to parse to an
        // unbounded NumericState that matched every value and leaked the card.
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"numeric_state","entity":"s.timer","above":"never"}]""")
        assertThat(parsed.single()).isEqualTo(LovelaceCondition.Never)
    }

    @Test
    fun `numeric_state accepts numeric strings as bounds`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"numeric_state","entity":"s.t","below":"30.0"}]""")
        val n = parsed.single() as LovelaceCondition.NumericState
        assertThat(n.below).isEqualTo(30.0)
    }

    @Test
    fun `screen condition parses to a Screen carrying the media query`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"screen","media_query":"(min-width: 600px)"}]""")
        val s = parsed.single() as LovelaceCondition.Screen
        assertThat(s.mediaQuery).isEqualTo("(min-width: 600px)")
    }

    @Test
    fun `user condition parses to a modelled User with its id list`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"user","users":["abc","def"]}]""")
        val u = parsed.single() as LovelaceCondition.User
        assertThat(u.userIds).containsExactly("abc", "def")
    }

    @Test
    fun `time condition parses to a Time with its after bound`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"time","after":"08:00:00"}]""")
        val t = parsed.single() as LovelaceCondition.Time
        assertThat(t.after).isEqualTo(TimeOfDay(8, 0, 0))
        assertThat(t.before).isNull()
    }

    @Test
    fun `an unmodelled condition type (template) still fails closed as Never`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions("""[{"condition":"template","value_template":"{{ true }}"}]""")
        assertThat(parsed.single()).isEqualTo(LovelaceCondition.Never)
    }

    // ----------------------------------------------------------------
    // Logical groups: and / or / not (nested arbitrarily)
    // ----------------------------------------------------------------
    @Test
    fun `parses and group with nested state and numeric conditions`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"and","conditions":[
                 {"condition":"state","entity":"light.k","state":"on"},
                 {"condition":"numeric_state","entity":"s.t","above":10}
               ]}]""",
        )
        val and = parsed.single() as LovelaceCondition.And
        assertThat(and.conditions).hasSize(2)
        assertThat(and.conditions[0]).isInstanceOf(LovelaceCondition.StateEquals::class.java)
        assertThat(and.conditions[1]).isInstanceOf(LovelaceCondition.NumericState::class.java)
    }

    @Test
    fun `parses or group`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"or","conditions":[
                 {"condition":"state","entity":"a","state":"on"},
                 {"condition":"state","entity":"b","state":"on"}
               ]}]""",
        )
        val or = parsed.single() as LovelaceCondition.Or
        assertThat(or.conditions).hasSize(2)
    }

    @Test
    fun `parses not group`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"not","conditions":[{"condition":"state","entity":"a","state":"on"}]}]""",
        )
        val not = parsed.single() as LovelaceCondition.Not
        assertThat(not.conditions).hasSize(1)
    }

    @Test
    fun `parses deeply nested and-or-not`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"and","conditions":[
                 {"condition":"or","conditions":[
                   {"condition":"state","entity":"a","state":"on"},
                   {"condition":"not","conditions":[{"condition":"state","entity":"b","state":"off"}]}
                 ]}
               ]}]""",
        )
        val and = parsed.single() as LovelaceCondition.And
        val or = and.conditions.single() as LovelaceCondition.Or
        assertThat(or.conditions[0]).isInstanceOf(LovelaceCondition.StateEquals::class.java)
        assertThat(or.conditions[1]).isInstanceOf(LovelaceCondition.Not::class.java)
    }

    @Test
    fun `and group keeps an unmodelled child as Never so it fails closed`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"and","conditions":[
                 {"condition":"state","entity":"a","state":"on"},
                 {"condition":"template","value_template":"{{ true }}"}
               ]}]""",
        )
        // The `template` child can't be evaluated locally, so it becomes Never.
        // Keeping it (rather than dropping it) makes the AND fail closed the way
        // HA would, instead of passing on the evaluable state sibling alone.
        val and = parsed.single() as LovelaceCondition.And
        assertThat(and.conditions).hasSize(2)
        assertThat(and.conditions[0]).isInstanceOf(LovelaceCondition.StateEquals::class.java)
        assertThat(and.conditions[1]).isEqualTo(LovelaceCondition.Never)
    }

    // ----------------------------------------------------------------
    // attribute + cross-entity numeric bounds
    // ----------------------------------------------------------------
    @Test
    fun `state condition carries the attribute key`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"state","entity":"climate.x","attribute":"hvac_action","state":"heating"}]""",
        )
        val s = parsed.single() as LovelaceCondition.StateEquals
        assertThat(s.attribute).isEqualTo("hvac_action")
        assertThat(s.states).containsExactly("heating")
    }

    @Test
    fun `numeric_state above referencing another entity parses as an entity bound`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"numeric_state","entity":"sensor.in","above":"sensor.out"}]""",
        )
        val n = parsed.single() as LovelaceCondition.NumericState
        assertThat(n.above).isNull()
        assertThat(n.aboveEntity).isEqualTo("sensor.out")
    }

    @Test
    fun `numeric_state with attribute carries the attribute key`() {
        Locale.setDefault(Locale.US)
        val parsed = conditions(
            """[{"condition":"numeric_state","entity":"climate.x","attribute":"temperature","above":20}]""",
        )
        val n = parsed.single() as LovelaceCondition.NumericState
        assertThat(n.attribute).isEqualTo("temperature")
        assertThat(n.above).isEqualTo(20.0)
    }

    // ----------------------------------------------------------------
    // per-card `visibility:` wraps any card in a Conditional
    // ----------------------------------------------------------------
    @Test
    fun `card-level visibility wraps the card in a conditional`() {
        Locale.setDefault(Locale.US)
        val card = LovelaceParser.parseCard(
            obj(
                """{"type":"tile","entity":"light.k",
                    "visibility":[{"condition":"state","entity":"sun.sun","state":"above_horizon"}]}""",
            ),
        )
        val cond = card as LovelaceCard.Conditional
        assertThat((cond.conditions.single() as LovelaceCondition.StateEquals).entityId).isEqualTo("sun.sun")
        assertThat(cond.card).isInstanceOf(LovelaceCard.Tile::class.java)
    }

    @Test
    fun `empty visibility array leaves the card unwrapped`() {
        Locale.setDefault(Locale.US)
        val card = LovelaceParser.parseCard(
            obj("""{"type":"tile","entity":"light.k","visibility":[]}"""),
        )
        assertThat(card).isInstanceOf(LovelaceCard.Tile::class.java)
    }

    @Test
    fun `parses tile features into typed feature list`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"tile","entity":"climate.living",
                 "features":[
                   {"type":"climate-hvac-modes","hvac_modes":["heat","off"]},
                   {"type":"target-temperature"},
                   {"type":"light-brightness"},
                   {"type":"cover-open-close"},
                   {"type":"toggle"},
                   {"type":"select-options","options":["a","b"]},
                   {"type":"alarm-modes","modes":["armed_home"]},
                   {"type":"some-unknown-feature"}
                 ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Tile
        assertEquals(8, card.features.size)
        val hvac = card.features[0] as LovelaceTileFeature.ClimateHvacModes
        assertThat(hvac.modes).containsExactly("heat", "off").inOrder()
        assertThat(card.features[1]).isInstanceOf(LovelaceTileFeature.TargetTemperature::class.java)
        assertThat(card.features[2]).isInstanceOf(LovelaceTileFeature.LightBrightness::class.java)
        assertThat(card.features[3]).isInstanceOf(LovelaceTileFeature.CoverOpenClose::class.java)
        assertThat(card.features[4]).isInstanceOf(LovelaceTileFeature.Toggle::class.java)
        val select = card.features[5] as LovelaceTileFeature.SelectOptions
        assertThat(select.options).containsExactly("a", "b").inOrder()
        val alarm = card.features[6] as LovelaceTileFeature.AlarmModes
        assertThat(alarm.modes).containsExactly("armed_home")
        val unknown = card.features[7] as LovelaceTileFeature.Unsupported
        assertEquals("some-unknown-feature", unknown.type)
    }

    @Test
    fun `tile without features parses to an empty feature list`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"tile","entity":"light.k"}"""),
        ) as LovelaceCard.Tile
        assertThat(card.features).isEmpty()
    }

    @Test
    fun `parses gauge segments sorted ascending by from`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"gauge","entity":"sensor.power","needle":true,
                 "segments":[
                   {"from":50,"color":"red"},
                   {"from":0,"color":"green"},
                   {"from":25,"color":"#ffaa00"}
                 ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Gauge
        assertTrue(card.needle)
        assertThat(card.segments.map { it.from }).containsExactly(0.0, 25.0, 50.0).inOrder()
        assertThat(card.segments.map { it.color }).containsExactly("green", "#ffaa00", "red").inOrder()
    }

    @Test
    fun `gauge severity still parses alongside no segments`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"gauge","entity":"sensor.x","severity":{"green":0,"yellow":50,"red":80}}"""),
        ) as LovelaceCard.Gauge
        assertThat(card.segments).isEmpty()
        assertThat(card.severity?.yellow).isEqualTo(50.0)
    }

    // ── Timestamp format: EntityRow.format parsing ────────────────────────────

    @Test fun `entity row with format relative parses to TimestampFormat RELATIVE`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"entities","entities":[
                  {"entity":"sensor.boot_time","format":"relative"}
                ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.RELATIVE)
    }

    @Test fun `entity row with format total parses to TimestampFormat TOTAL`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"entities","entities":[
                  {"entity":"sensor.uptime","format":"total"}
                ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.TOTAL)
    }

    @Test fun `entity row with format date parses to TimestampFormat DATE`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts","format":"date"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.DATE)
    }

    @Test fun `entity row with format time parses to TimestampFormat TIME`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts","format":"time"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.TIME)
    }

    @Test fun `entity row with format datetime parses to TimestampFormat DATETIME`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts","format":"datetime"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.DATETIME)
    }

    @Test fun `entity row with unknown format parses to null`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts","format":"invalid_value"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isNull()
    }

    @Test fun `entity row without format key has null format`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isNull()
    }

    @Test fun `bare entity-id string row has null format`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":["sensor.uptime"]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isNull()
    }

    @Test fun `format key is case-insensitive`() {
        val card = LovelaceParser.parseCard(
            obj("""{"type":"entities","entities":[{"entity":"sensor.ts","format":"RELATIVE"}]}"""),
        ) as LovelaceCard.Entities
        assertThat(card.entities.first().format).isEqualTo(TimestampFormat.RELATIVE)
    }

    @Test fun `glance card entity row also accepts format key`() {
        val card = LovelaceParser.parseCard(
            obj(
                """
                {"type":"glance","entities":[
                  {"entity":"sensor.boot","format":"total"},
                  {"entity":"sensor.ts","format":"relative"}
                ]}
                """.trimIndent(),
            ),
        ) as LovelaceCard.Glance
        assertThat(card.entities[0].format).isEqualTo(TimestampFormat.TOTAL)
        assertThat(card.entities[1].format).isEqualTo(TimestampFormat.RELATIVE)
    }
}
