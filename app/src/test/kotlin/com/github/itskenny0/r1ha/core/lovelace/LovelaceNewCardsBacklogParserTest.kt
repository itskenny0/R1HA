package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelaceNewCardsBacklogParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // ── #1 statistics-graph ──────────────────────────────────────────────────

    @Test fun `parses statistics-graph with entity list and defaults`() {
        val c = card(
            """{"type":"statistics-graph","entities":["sensor.energy_a","sensor.energy_b"]}""",
        ) as LovelaceCard.StatisticsGraph
        assertThat(c.entityIds).containsExactly("sensor.energy_a", "sensor.energy_b")
        assertThat(c.statTypes).containsExactly("mean")
        assertThat(c.period).isEqualTo("day")
        assertThat(c.chartType).isEqualTo("line")
        assertThat(c.daysToShow).isNull()
        assertThat(c.title).isNull()
    }

    @Test fun `parses statistics-graph with object entities and all fields`() {
        val c = card(
            """
            {"type":"statistics-graph","title":"Usage","entities":[
              {"entity":"sensor.energy_a","name":"A"},
              "sensor.energy_b"
            ],"stat_types":["min","max"],"period":{"calendar":{"period":"month"}},
             "chart_type":"bar","days_to_show":14}
            """.trimIndent(),
        ) as LovelaceCard.StatisticsGraph
        assertThat(c.title).isEqualTo("Usage")
        assertThat(c.entityIds).containsExactly("sensor.energy_a", "sensor.energy_b")
        assertThat(c.statTypes).containsExactly("min", "max")
        assertThat(c.period).isEqualTo("month")
        assertThat(c.chartType).isEqualTo("bar")
        assertThat(c.daysToShow).isEqualTo(14)
    }

    @Test fun `statistics-graph without entities degrades to Unsupported`() {
        val c = card("""{"type":"statistics-graph"}""")
        assertThat(c).isInstanceOf(LovelaceCard.Unsupported::class.java)
    }

    // ── #2 picture ───────────────────────────────────────────────────────────

    @Test fun `parses picture card with static image url`() {
        val c = card(
            """{"type":"picture","image":"/local/bg.jpg"}""",
        ) as LovelaceCard.Picture
        assertThat(c.image).isEqualTo("/local/bg.jpg")
        assertThat(c.imageEntity).isNull()
        assertThat(c.tapAction).isNull()
    }

    @Test fun `parses picture card with image_entity and tap action`() {
        val c = card(
            """{"type":"picture","image_entity":"camera.front_door",
               "tap_action":{"action":"more-info"}}""",
        ) as LovelaceCard.Picture
        assertThat(c.imageEntity).isEqualTo("camera.front_door")
        assertThat(c.image).isNull()
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Builtin::class.java)
    }

    @Test fun `picture card with neither image nor image_entity degrades to Unsupported`() {
        val c = card("""{"type":"picture"}""")
        assertThat(c).isInstanceOf(LovelaceCard.Unsupported::class.java)
    }

    // ── #3 bar-gauge tile feature ────────────────────────────────────────────

    @Test fun `parseTileFeatures parses bar-gauge with defaults`() {
        val raw = Json.parseToJsonElement(
            """{"type":"tile","entity":"sensor.battery",
               "features":[{"type":"bar-gauge"}]}""",
        ) as JsonObject
        val card = LovelaceParser.parseCard(raw) as LovelaceCard.Tile
        val f = card.features.single() as LovelaceTileFeature.BarGauge
        assertThat(f.attribute).isNull()
        assertThat(f.min).isEqualTo(0.0)
        assertThat(f.max).isEqualTo(100.0)
        assertThat(f.color).isNull()
    }

    @Test fun `parseTileFeatures parses bar-gauge with all fields`() {
        val raw = Json.parseToJsonElement(
            """{"type":"tile","entity":"sensor.battery",
               "features":[{"type":"bar-gauge","attribute":"battery_level","min":10,"max":90,"color":"green"}]}""",
        ) as JsonObject
        val card = LovelaceParser.parseCard(raw) as LovelaceCard.Tile
        val f = card.features.single() as LovelaceTileFeature.BarGauge
        assertThat(f.attribute).isEqualTo("battery_level")
        assertThat(f.min).isEqualTo(10.0)
        assertThat(f.max).isEqualTo(90.0)
        assertThat(f.color).isEqualTo("green")
    }

    // ── #4 trend-graph tile feature ──────────────────────────────────────────

    @Test fun `parseTileFeatures parses trend-graph with default hours`() {
        val raw = Json.parseToJsonElement(
            """{"type":"tile","entity":"sensor.temp","features":[{"type":"trend-graph"}]}""",
        ) as JsonObject
        val card = LovelaceParser.parseCard(raw) as LovelaceCard.Tile
        val f = card.features.single() as LovelaceTileFeature.TrendGraph
        assertThat(f.hoursToShow).isEqualTo(24)
    }

    @Test fun `parseTileFeatures parses trend-graph with custom hours`() {
        val raw = Json.parseToJsonElement(
            """{"type":"tile","entity":"sensor.temp","features":[{"type":"trend-graph","hours_to_show":48}]}""",
        ) as JsonObject
        val card = LovelaceParser.parseCard(raw) as LovelaceCard.Tile
        val f = card.features.single() as LovelaceTileFeature.TrendGraph
        assertThat(f.hoursToShow).isEqualTo(48)
    }

    // ── #5 date-set tile feature ─────────────────────────────────────────────

    @Test fun `parseTileFeatures parses date-set feature`() {
        val raw = Json.parseToJsonElement(
            """{"type":"tile","entity":"date.appointment","features":[{"type":"date-set"}]}""",
        ) as JsonObject
        val card = LovelaceParser.parseCard(raw) as LovelaceCard.Tile
        assertThat(card.features.single()).isInstanceOf(LovelaceTileFeature.DateSet::class.java)
    }
}
