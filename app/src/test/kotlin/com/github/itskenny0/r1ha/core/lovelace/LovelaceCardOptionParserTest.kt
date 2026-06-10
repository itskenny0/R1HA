package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelaceCardOptionParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // ── Item 1: Heading card tap_action ───────────────────────────────────────

    @Test fun `heading card parses tap_action`() {
        val c = card(
            """{"type":"heading","heading":"Lights","tap_action":{"action":"navigate","navigation_path":"/lovelace/lights"}}""",
        ) as LovelaceCard.Heading
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
        assertThat((c.tapAction as LovelaceAction.Navigate).path).isEqualTo("/lovelace/lights")
    }

    @Test fun `heading card tap_action defaults to null when absent`() {
        val c = card("""{"type":"heading","heading":"Section"}""") as LovelaceCard.Heading
        assertThat(c.tapAction).isNull()
    }

    @Test fun `legacy heading entities migrate to badges`() {
        val c = card(
            """{"type":"heading","heading":"Lights","entities":["light.kitchen","light.hall"]}""",
        ) as LovelaceCard.Heading
        assertThat(c.badges.map { it.entityId })
            .containsExactly("light.kitchen", "light.hall").inOrder()
    }

    @Test fun `legacy heading entities append after explicit badges`() {
        val c = card(
            """{"type":"heading","heading":"H","badges":["sensor.a"],"entities":["light.b"]}""",
        ) as LovelaceCard.Heading
        // HA's migrateHeadingCardConfig: badges = [...badges, ...entities].
        assertThat(c.badges.map { it.entityId })
            .containsExactly("sensor.a", "light.b").inOrder()
    }

    @Test fun `heading entities accept entity-object shape`() {
        val c = card(
            """{"type":"heading","heading":"H","entities":[{"type":"entity","entity":"sensor.power","name":"Power"}]}""",
        ) as LovelaceCard.Heading
        val b = c.badges.single()
        assertThat(b.entityId).isEqualTo("sensor.power")
        assertThat(b.name).isEqualTo("Power")
    }

    // ── Item 2: Tile card state_content ───────────────────────────────────────

    @Test fun `tile card parses state_content list`() {
        val c = card(
            """{"type":"tile","entity":"sensor.power","state_content":["state","last_changed"]}""",
        ) as LovelaceCard.Tile
        assertThat(c.stateContent).containsExactly("state", "last_changed").inOrder()
    }

    @Test fun `tile card state_content defaults to empty list`() {
        val c = card("""{"type":"tile","entity":"sensor.power"}""") as LovelaceCard.Tile
        assertThat(c.stateContent).isEmpty()
    }

    // ── Item 3: Thermostat + Humidifier show_current_temperature ─────────────

    @Test fun `thermostat card parses show_current_temperature false`() {
        val c = card(
            """{"type":"thermostat","entity":"climate.bedroom","show_current_temperature":false}""",
        ) as LovelaceCard.Thermostat
        assertThat(c.showCurrentTemperature).isFalse()
    }

    @Test fun `thermostat card show_current_temperature defaults to true`() {
        val c = card("""{"type":"thermostat","entity":"climate.bedroom"}""") as LovelaceCard.Thermostat
        assertThat(c.showCurrentTemperature).isTrue()
    }

    @Test fun `humidifier card parses show_current_temperature false`() {
        val c = card(
            """{"type":"humidifier","entity":"humidifier.living","show_current_temperature":false}""",
        ) as LovelaceCard.Humidifier
        assertThat(c.showCurrentTemperature).isFalse()
    }

    @Test fun `humidifier card show_current_temperature defaults to true`() {
        val c = card("""{"type":"humidifier","entity":"humidifier.living"}""") as LovelaceCard.Humidifier
        assertThat(c.showCurrentTemperature).isTrue()
    }

    // ── Item 4: Thermostat features array ─────────────────────────────────────

    @Test fun `thermostat card parses features array`() {
        val c = card(
            """{"type":"thermostat","entity":"climate.bedroom","features":[{"type":"climate-hvac-modes"}]}""",
        ) as LovelaceCard.Thermostat
        assertThat(c.features).hasSize(1)
        assertThat(c.features[0]).isInstanceOf(LovelaceTileFeature.ClimateHvacModes::class.java)
    }

    @Test fun `thermostat card features defaults to empty`() {
        val c = card("""{"type":"thermostat","entity":"climate.bedroom"}""") as LovelaceCard.Thermostat
        assertThat(c.features).isEmpty()
    }

    // ── Item 5: PictureEntity + PictureGlance fit_mode ───────────────────────

    @Test fun `picture-entity parses fit_mode`() {
        val c = card(
            """{"type":"picture-entity","entity":"camera.front","fit_mode":"contain"}""",
        ) as LovelaceCard.PictureEntity
        assertThat(c.fitMode).isEqualTo("contain")
    }

    @Test fun `picture-entity fit_mode defaults to null`() {
        val c = card("""{"type":"picture-entity","entity":"camera.front"}""") as LovelaceCard.PictureEntity
        assertThat(c.fitMode).isNull()
    }

    @Test fun `picture-glance parses fit_mode`() {
        val c = card(
            """{"type":"picture-glance","fit_mode":"fill","entities":[]}""",
        ) as LovelaceCard.PictureGlance
        assertThat(c.fitMode).isEqualTo("fill")
    }

    @Test fun `picture-glance fit_mode defaults to null`() {
        val c = card("""{"type":"picture-glance","entities":[]}""") as LovelaceCard.PictureGlance
        assertThat(c.fitMode).isNull()
    }

    // ── Item 6: Clock card clock_size + time_format ───────────────────────────

    @Test fun `clock card parses clock_size and time_format`() {
        val c = card(
            """{"type":"clock","clock_size":"large","time_format":"12"}""",
        ) as LovelaceCard.Clock
        assertThat(c.clockSize).isEqualTo("large")
        assertThat(c.timeFormat).isEqualTo("12")
    }

    @Test fun `clock card clock_size and time_format default to null`() {
        val c = card("""{"type":"clock"}""") as LovelaceCard.Clock
        assertThat(c.clockSize).isNull()
        assertThat(c.timeFormat).isNull()
    }

    // ── Item 7: Map card label_mode + focus_entities ─────────────────────────

    @Test fun `map card parses label_mode and focus_entities`() {
        val c = card(
            """{"type":"map","label_mode":"state","focus_entities":["person.alice","device_tracker.phone"]}""",
        ) as LovelaceCard.Map
        assertThat(c.labelMode).isEqualTo("state")
        assertThat(c.focusEntities).containsExactly("person.alice", "device_tracker.phone")
    }

    @Test fun `map card label_mode defaults to null and focus_entities defaults to empty`() {
        val c = card("""{"type":"map"}""") as LovelaceCard.Map
        assertThat(c.labelMode).isNull()
        assertThat(c.focusEntities).isEmpty()
    }

    // ── Item 8: Gauge segments label ─────────────────────────────────────────

    @Test fun `gauge segment parses label field`() {
        val c = card(
            """{"type":"gauge","entity":"sensor.co2","segments":[{"from":0,"color":"green","label":"Good"},{"from":800,"color":"yellow","label":"Poor"}]}""",
        ) as LovelaceCard.Gauge
        assertThat(c.segments[0].label).isEqualTo("Good")
        assertThat(c.segments[1].label).isEqualTo("Poor")
    }

    @Test fun `gauge segment label defaults to null`() {
        val c = card(
            """{"type":"gauge","entity":"sensor.co2","segments":[{"from":0,"color":"green"}]}""",
        ) as LovelaceCard.Gauge
        assertThat(c.segments[0].label).isNull()
    }

    // ── Item 9: Todo-list card sort ───────────────────────────────────────────

    @Test fun `todo-list card parses sort field`() {
        // todo-list lands in Unsupported so we read sort from raw
        val c = card(
            """{"type":"todo-list","entity":"todo.shopping","sort":"alpha"}""",
        ) as LovelaceCard.Unsupported
        // The sort field is preserved in raw; confirm the entity is captured
        assertThat(c.entityRefs).contains("todo.shopping")
        // The sort value is available through raw JSON
        val sortVal = c.raw["sort"]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        assertThat(sortVal).isEqualTo("alpha")
    }

    // ── Item 10: Dashboard view subview ──────────────────────────────────────

    @Test fun `view parses subview true`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """{"views":[{"path":"sub","subview":true,"cards":[]}]}""",
            ) as JsonObject,
        )
        assertThat(cfg.views.first().subview).isTrue()
    }

    @Test fun `view subview defaults to false`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """{"views":[{"path":"main","cards":[]}]}""",
            ) as JsonObject,
        )
        assertThat(cfg.views.first().subview).isFalse()
    }

    // ── Item 11: Badge size ───────────────────────────────────────────────────

    @Test fun `badge parses size field`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """{"views":[{"path":"p","badges":[{"type":"entity","entity":"sensor.time","size":"large"}],"cards":[]}]}""",
            ) as JsonObject,
        )
        assertThat(cfg.views.first().badges.first().size).isEqualTo("large")
    }

    @Test fun `badge size defaults to null`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """{"views":[{"path":"p","badges":["sensor.time"],"cards":[]}]}""",
            ) as JsonObject,
        )
        assertThat(cfg.views.first().badges.first().size).isNull()
    }
}
