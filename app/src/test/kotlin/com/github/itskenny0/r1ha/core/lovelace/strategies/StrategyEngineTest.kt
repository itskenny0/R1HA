package com.github.itskenny0.r1ha.core.lovelace.strategies

import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Fixture tests for the client-side strategy engine. Each test builds a small
 * [StrategyData] snapshot + a raw `strategy:` config and asserts the engine
 * expands it into the concrete cards HA would have produced, then re-parses the
 * output through [LovelaceParser] to prove the renderer can draw it.
 */
class StrategyEngineTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun entity(
        id: String,
        name: String = id,
        state: String = "on",
        lastChangedMs: Long = 0L,
        deviceClass: String? = null,
        hvacModes: Int = 0,
    ) = StrategyEntity(
        entityId = id,
        friendlyName = name,
        state = state,
        lastChangedMs = lastChangedMs,
        hvacModesCount = hvacModes,
        deviceClass = deviceClass,
    )

    private fun data(
        states: List<StrategyEntity> = emptyList(),
        areas: List<StrategyArea> = emptyList(),
        devices: List<StrategyDevice> = emptyList(),
        registry: List<StrategyRegistryEntity> = emptyList(),
        floors: List<StrategyFloor> = emptyList(),
        hasEnergyGrid: Boolean = false,
        commonControls: List<String>? = null,
        starting: Boolean = false,
        recoveryMode: Boolean = false,
    ) = StrategyData(
        states = states.associateBy { it.entityId },
        areas = areas.associateBy { it.areaId },
        devices = devices.associateBy { it.id },
        entities = registry.associateBy { it.entityId },
        floors = floors.associateBy { it.floorId },
        hasEnergyGrid = hasEnergyGrid,
        commonControls = commonControls,
        starting = starting,
        recoveryMode = recoveryMode,
    )

    // --- resolution engine ---------------------------------------------------

    @Test fun `hasAnyStrategy detects root, view, and section strategies`() {
        assertThat(StrategyEngine.hasAnyStrategy(obj("""{"strategy":{"type":"home"}}"""))).isTrue()
        assertThat(StrategyEngine.hasAnyStrategy(obj("""{"views":[{"strategy":{"type":"map"}}]}"""))).isTrue()
        assertThat(
            StrategyEngine.hasAnyStrategy(
                obj("""{"views":[{"sections":[{"strategy":{"type":"common-controls"}}]}]}"""),
            ),
        ).isTrue()
        assertThat(StrategyEngine.hasAnyStrategy(obj("""{"views":[{"cards":[]}]}"""))).isFalse()
    }

    @Test fun `unknown dashboard strategy expands to an explanatory card not a blank`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"weird-custom"}}"""), data())
        val cfg = LovelaceParser.parseConfig(out)
        val card = cfg.views.single().cards.single()
        assertThat(card).isInstanceOf(LovelaceCard.Markdown::class.java)
        assertThat((card as LovelaceCard.Markdown).content).contains("weird-custom")
    }

    @Test fun `custom JS strategy renders the labeled placeholder`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"custom:my-strategy"}}"""), data())
        val cfg = LovelaceParser.parseConfig(out)
        val card = cfg.views.single().cards.single() as LovelaceCard.Markdown
        assertThat(card.content).contains("plugin")
    }

    @Test fun `mixed sections view expands strategy sections in place keeping concrete ones`() {
        val raw = obj(
            """
            {"views":[{"type":"sections","sections":[
              {"cards":[{"type":"markdown","content":"concrete"}]},
              {"strategy":{"type":"common-controls","limit":2}}
            ]}]}
            """.trimIndent(),
        )
        val d = data(
            states = listOf(entity("light.a", lastChangedMs = 2), entity("switch.b", lastChangedMs = 1)),
        )
        val out = StrategyEngine.expand(raw, d)
        val cfg = LovelaceParser.parseConfig(out)
        // Concrete markdown survives and the strategy section produced tiles.
        val texts = cfg.views.single().cards
        assertThat(texts.any { it is LovelaceCard.Markdown && it.content == "concrete" }).isTrue()
        assertThat(texts.any { it is LovelaceCard.Tile }).isTrue()
    }

    @Test fun `starting state renders the explanatory card`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"home"}}"""), data(starting = true))
        val cfg = LovelaceParser.parseConfig(out)
        assertThat(cfg.views.single().cards.single()).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `recovery mode renders the explanatory card`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"areas"}}"""), data(recoveryMode = true))
        val cfg = LovelaceParser.parseConfig(out)
        assertThat(cfg.views.single().cards.single()).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    // --- original-states -----------------------------------------------------

    @Test fun `original-states groups by area and hides categorised entities`() {
        val d = data(
            states = listOf(
                entity("light.kitchen", "Kitchen Light"),
                entity("sensor.kitchen_power", "Kitchen Power"),
                entity("sensor.uptime", "Uptime"),
            ),
            areas = listOf(StrategyArea("kitchen", "Kitchen")),
            registry = listOf(
                StrategyRegistryEntity("light.kitchen", areaId = "kitchen"),
                StrategyRegistryEntity("sensor.kitchen_power", areaId = "kitchen"),
                // Diagnostic entity: filtered out of the default view.
                StrategyRegistryEntity("sensor.uptime", entityCategory = "diagnostic"),
            ),
        )
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"original-states"}}"""), d)
        val cfg = LovelaceParser.parseConfig(out)
        val view = cfg.views.single()
        // An entities/grid card titled "Kitchen" exists; uptime never appears.
        val flat = view.cards.flatMap { card ->
            when (card) {
                is LovelaceCard.Grid -> card.cards
                else -> listOf(card)
            }
        }
        val entityRows = flat.filterIsInstance<LovelaceCard.Entities>()
            .flatMap { it.entities }.map { it.entityId }
        assertThat(entityRows).contains("light.kitchen")
        assertThat(entityRows).doesNotContain("sensor.uptime")
    }

    @Test fun `original-states with no entities yields an empty-state card`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"original-states"}}"""), data())
        val cfg = LovelaceParser.parseConfig(out)
        assertThat(cfg.views.single().cards.single()).isInstanceOf(LovelaceCard.EmptyState::class.java)
    }

    @Test fun `original-states emits an energy card when a grid source exists`() {
        val d = data(
            states = listOf(entity("light.a", "A")),
            registry = listOf(StrategyRegistryEntity("light.a")),
            hasEnergyGrid = true,
        )
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"original-states"}}"""), d)
        // The energy-distribution card type is preserved in the raw output.
        assertThat(out.toString()).contains("energy-distribution")
    }

    // --- areas ---------------------------------------------------------------

    @Test fun `areas dashboard emits an overview plus per-area subviews`() {
        val d = data(
            states = listOf(entity("light.lr", "Lamp")),
            areas = listOf(StrategyArea("living_room", "Living Room")),
            registry = listOf(StrategyRegistryEntity("light.lr", areaId = "living_room")),
        )
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"areas"}}"""), d)
        val cfg = LovelaceParser.parseConfig(out)
        // Overview view (path home) + one subview (areas-living_room).
        assertThat(cfg.views.map { it.path }).containsAtLeast("home", "areas-living_room")
        val subview = cfg.views.first { it.path == "areas-living_room" }
        assertThat(subview.subview).isTrue()
        // The subview has a Lights group tile for the light.
        assertThat(subview.cards.any { it is LovelaceCard.Tile && it.entityId == "light.lr" }).isTrue()
    }

    @Test fun `area subview adds temperature and humidity badges`() {
        val d = data(
            states = listOf(entity("light.lr", "Lamp")),
            areas = listOf(
                StrategyArea(
                    "lr", "Living Room",
                    temperatureEntityId = "sensor.lr_temp",
                    humidityEntityId = "sensor.lr_hum",
                ),
            ),
            registry = listOf(StrategyRegistryEntity("light.lr", areaId = "lr")),
        )
        val out = StrategyEngine.expand(
            obj("""{"views":[{"strategy":{"type":"area","area":"lr"}}]}"""),
            d,
        )
        val cfg = LovelaceParser.parseConfig(out)
        val badges = cfg.views.single().badges.map { it.entityId }
        assertThat(badges).containsExactly("sensor.lr_temp", "sensor.lr_hum")
    }

    // --- common-controls -----------------------------------------------------

    @Test fun `common-controls uses the server prediction when present`() {
        val d = data(
            states = listOf(entity("light.a"), entity("switch.b")),
            commonControls = listOf("switch.b", "light.a"),
        )
        val out = StrategyEngine.expand(
            obj("""{"views":[{"type":"sections","sections":[{"strategy":{"type":"common-controls","limit":5}}]}]}"""),
            d,
        )
        val cfg = LovelaceParser.parseConfig(out)
        val tiles = cfg.views.single().cards.filterIsInstance<LovelaceCard.Tile>().map { it.entityId }
        assertThat(tiles).containsExactly("switch.b", "light.a").inOrder()
    }

    @Test fun `common-controls falls back to recently-changed toggleables`() {
        val d = data(
            states = listOf(
                entity("light.recent", lastChangedMs = 100),
                entity("light.old", lastChangedMs = 1),
                entity("sensor.ignored", lastChangedMs = 999),
            ),
            commonControls = null,
        )
        val out = StrategyEngine.expand(
            obj("""{"views":[{"type":"sections","sections":[{"strategy":{"type":"common-controls","limit":2}}]}]}"""),
            d,
        )
        val cfg = LovelaceParser.parseConfig(out)
        val tiles = cfg.views.single().cards.filterIsInstance<LovelaceCard.Tile>().map { it.entityId }
        // Most-recent toggleable first; the sensor is not toggleable so excluded.
        assertThat(tiles.first()).isEqualTo("light.recent")
        assertThat(tiles).doesNotContain("sensor.ignored")
    }

    @Test fun `common-controls honors include and exclude`() {
        val d = data(
            states = listOf(entity("light.inc"), entity("switch.exc"), entity("light.pred")),
            commonControls = listOf("switch.exc", "light.pred"),
        )
        val out = StrategyEngine.expand(
            obj(
                """{"views":[{"type":"sections","sections":[{"strategy":{"type":"common-controls",
                   "limit":5,"include_entities":["light.inc"],"exclude_entities":["switch.exc"]}}]}]}""",
            ),
            d,
        )
        val cfg = LovelaceParser.parseConfig(out)
        val tiles = cfg.views.single().cards.filterIsInstance<LovelaceCard.Tile>().map { it.entityId }
        assertThat(tiles).contains("light.inc")
        assertThat(tiles).contains("light.pred")
        assertThat(tiles).doesNotContain("switch.exc")
    }

    // --- map / iframe --------------------------------------------------------

    @Test fun `map dashboard is a panel view with a single map card`() {
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"map"}}"""), data())
        val cfg = LovelaceParser.parseConfig(out)
        val view = cfg.views.single()
        assertThat(view.panel).isTrue()
        assertThat(view.cards.single()).isInstanceOf(LovelaceCard.Map::class.java)
    }

    @Test fun `iframe dashboard preserves the url on the unsupported iframe card`() {
        val out = StrategyEngine.expand(
            obj("""{"strategy":{"type":"iframe","url":"https://example.com","title":"Web"}}"""),
            data(),
        )
        val cfg = LovelaceParser.parseConfig(out)
        val card = cfg.views.single().cards.single()
        assertThat(card.type).isEqualTo("iframe")
        assertThat(card.raw.toString()).contains("example.com")
    }

    // --- home ----------------------------------------------------------------

    @Test fun `home dashboard emits overview, area subviews, media players and other devices`() {
        val d = data(
            states = listOf(entity("media_player.tv", "TV"), entity("light.lr", "Lamp")),
            areas = listOf(StrategyArea("lr", "Living Room")),
            registry = listOf(
                StrategyRegistryEntity("media_player.tv", areaId = "lr"),
                StrategyRegistryEntity("light.lr", areaId = "lr"),
            ),
        )
        val out = StrategyEngine.expand(obj("""{"strategy":{"type":"home"}}"""), d)
        val cfg = LovelaceParser.parseConfig(out)
        val paths = cfg.views.map { it.path }
        assertThat(paths).containsAtLeast("overview", "areas-lr", "media-players", "other-devices")
    }

    @Test fun `home media-players view groups players by area`() {
        val d = data(
            states = listOf(entity("media_player.kitchen", "Kitchen Speaker")),
            areas = listOf(StrategyArea("kitchen", "Kitchen")),
            registry = listOf(StrategyRegistryEntity("media_player.kitchen", areaId = "kitchen")),
        )
        val out = StrategyEngine.expand(
            obj("""{"views":[{"strategy":{"type":"home-media-players"}}]}"""),
            d,
        )
        val cfg = LovelaceParser.parseConfig(out)
        assertThat(
            cfg.views.single().cards.any { it is LovelaceCard.MediaControl && it.entityId == "media_player.kitchen" },
        ).isTrue()
    }
}
