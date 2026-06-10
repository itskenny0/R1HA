package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser tests for the CU1 cleanup batch: structured `name:` (EntityNameItem),
 * card / per-entity `state_color`, glance per-entity `show_state` / `image`, and
 * button `icon_height`.
 */
class StructuredNameParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // ── structured name on cards ─────────────────────────────────────────────

    @Test fun `tile structured name array parses into name items`() {
        val c = card(
            """{"type":"tile","entity":"light.a","name":[{"type":"device"},{"type":"area"}]}""",
        ) as LovelaceCard.Tile
        assertThat(c.name).isNull()
        assertThat(c.nameItems).containsExactly(
            EntityNameItem.Part("device"),
            EntityNameItem.Part("area"),
        ).inOrder()
    }

    @Test fun `tile structured name single object plus text part`() {
        val c = card(
            """{"type":"tile","entity":"light.a","name":[{"type":"text","text":"Lamp"},{"type":"device"}]}""",
        ) as LovelaceCard.Tile
        assertThat(c.nameItems).containsExactly(
            EntityNameItem.Text("Lamp"),
            EntityNameItem.Part("device"),
        ).inOrder()
    }

    @Test fun `plain string name leaves name items empty`() {
        val c = card("""{"type":"tile","entity":"light.a","name":"Kitchen Lamp"}""") as LovelaceCard.Tile
        assertThat(c.name).isEqualTo("Kitchen Lamp")
        assertThat(c.nameItems).isEmpty()
    }

    @Test fun `button thermostat alarm-panel parse structured name`() {
        val b = card("""{"type":"button","entity":"light.a","name":[{"type":"device"}]}""") as LovelaceCard.Button
        assertThat(b.nameItems).containsExactly(EntityNameItem.Part("device"))
        val t = card("""{"type":"thermostat","entity":"climate.a","name":[{"type":"area"}]}""") as LovelaceCard.Thermostat
        assertThat(t.nameItems).containsExactly(EntityNameItem.Part("area"))
        val a = card("""{"type":"alarm-panel","entity":"alarm_control_panel.a","name":[{"type":"floor"}]}""") as LovelaceCard.AlarmPanel
        assertThat(a.nameItems).containsExactly(EntityNameItem.Part("floor"))
    }

    @Test fun `unknown structured name item types are dropped`() {
        val c = card(
            """{"type":"tile","entity":"light.a","name":[{"type":"bogus"},{"type":"device"}]}""",
        ) as LovelaceCard.Tile
        assertThat(c.nameItems).containsExactly(EntityNameItem.Part("device"))
    }

    // ── glance state_color / show_state / image ──────────────────────────────

    @Test fun `glance card defaults state_color true and parses false`() {
        val on = card("""{"type":"glance","entities":["light.a"]}""") as LovelaceCard.Glance
        assertThat(on.stateColor).isTrue()
        val off = card("""{"type":"glance","state_color":false,"entities":["light.a"]}""") as LovelaceCard.Glance
        assertThat(off.stateColor).isFalse()
    }

    @Test fun `glance per-entity state_color show_state and image parse`() {
        val c = card(
            """{"type":"glance","entities":[
                {"entity":"light.a","state_color":false,"show_state":false,"image":"/local/a.png"}
            ]}""",
        ) as LovelaceCard.Glance
        val row = c.entities.single()
        assertThat(row.stateColor).isFalse()
        assertThat(row.showState).isFalse()
        assertThat(row.image).isEqualTo("/local/a.png")
    }

    @Test fun `glance per-entity flags default to null when absent`() {
        val c = card("""{"type":"glance","entities":[{"entity":"light.a"}]}""") as LovelaceCard.Glance
        val row = c.entities.single()
        assertThat(row.stateColor).isNull()
        assertThat(row.showState).isNull()
    }

    // ── tile state_color ─────────────────────────────────────────────────────

    @Test fun `tile state_color defaults true and parses false`() {
        val def = card("""{"type":"tile","entity":"light.a"}""") as LovelaceCard.Tile
        assertThat(def.stateColor).isTrue()
        val off = card("""{"type":"tile","entity":"light.a","state_color":false}""") as LovelaceCard.Tile
        assertThat(off.stateColor).isFalse()
    }

    // ── button icon_height ───────────────────────────────────────────────────

    @Test fun `button parses icon_height as string and number`() {
        val s = card("""{"type":"button","entity":"light.a","icon_height":"48px"}""") as LovelaceCard.Button
        assertThat(s.iconHeight).isEqualTo("48px")
        val n = card("""{"type":"button","entity":"light.a","icon_height":40}""") as LovelaceCard.Button
        assertThat(n.iconHeight).isEqualTo("40")
    }

    @Test fun `button icon_height null when absent`() {
        val c = card("""{"type":"button","entity":"light.a"}""") as LovelaceCard.Button
        assertThat(c.iconHeight).isNull()
    }

    // ── clock no_background ──────────────────────────────────────────────────

    @Test fun `clock parses no_background and defaults false`() {
        val def = card("""{"type":"clock"}""") as LovelaceCard.Clock
        assertThat(def.noBackground).isFalse()
        val nb = card("""{"type":"clock","no_background":true}""") as LovelaceCard.Clock
        assertThat(nb.noBackground).isTrue()
    }

    // ── new small cards land as typed Unsupported with their dispatch hooks ───

    @Test fun `plant-status keeps its type and scrapes the entity ref`() {
        val c = card("""{"type":"plant-status","entity":"plant.fern"}""") as LovelaceCard.Unsupported
        assertThat(c.type).isEqualTo("plant-status")
        assertThat(c.entityRefs).containsExactly("plant.fern")
    }

    @Test fun `discovered-devices keeps its type`() {
        val c = card("""{"type":"discovered-devices","title":"New"}""") as LovelaceCard.Unsupported
        assertThat(c.type).isEqualTo("discovered-devices")
    }

    @Test fun `error card keeps its type and raw config`() {
        val c = card("""{"type":"error","error":"boom","origConfig":{"type":"bogus"}}""") as LovelaceCard.Unsupported
        assertThat(c.type).isEqualTo("error")
        assertThat(c.raw["error"]).isNotNull()
        assertThat(c.raw["origConfig"]).isNotNull()
    }

    // ── map card new options ─────────────────────────────────────────────────

    @Test fun `map parses show_all fit_zones cluster and geo sources`() {
        val c = card(
            """{"type":"map","show_all":true,"fit_zones":true,"cluster":false,
                "geo_location_sources":["usgs","nsw_rural_fire"]}""",
        ) as LovelaceCard.Map
        assertThat(c.showAll).isTrue()
        assertThat(c.fitZones).isTrue()
        assertThat(c.cluster).isFalse()
        assertThat(c.geoLocationSources).containsExactly("usgs", "nsw_rural_fire").inOrder()
    }

    @Test fun `map cluster defaults true and conditions default empty`() {
        val c = card("""{"type":"map","entities":["person.a"]}""") as LovelaceCard.Map
        assertThat(c.cluster).isTrue()
        assertThat(c.showAll).isFalse()
        assertThat(c.conditions).isEmpty()
    }

    @Test fun `map parses per-entity visibility conditions`() {
        val c = card(
            """{"type":"map","entities":["person.a"],
                "conditions":[{"condition":"state","entity":"person.a","state":"home"}]}""",
        ) as LovelaceCard.Map
        assertThat(c.conditions).hasSize(1)
    }
}
