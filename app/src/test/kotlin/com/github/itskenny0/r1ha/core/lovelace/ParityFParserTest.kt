package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser coverage for Batch F: the entity-filter card's operator-form
 * `state_filter:`, `conditions:`, wrapped `card:`, and per-entity overrides; the
 * card-level `header:` / `footer:` slots; and the entity card's option keys.
 */
class ParityFParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    // ── entity-filter: operator-form state_filter ─────────────────────────────

    @Test fun `entity-filter parses bare-string state_filter as equality`() {
        val c = card("""{"type":"entity-filter","entities":["light.a"],"state_filter":["on","home"]}""")
            as LovelaceCard.EntityFilter
        assertThat(c.stateFilter).hasSize(2)
        assertThat(c.stateFilter.map { it.operator }).containsExactly(StateFilterOperator.EQ, StateFilterOperator.EQ)
        assertThat(c.stateFilter.map { it.value }).containsExactly("on", "home")
    }

    @Test fun `entity-filter parses operator object form with attribute`() {
        val c = card(
            """{"type":"entity-filter","entities":["sensor.x"],
               "state_filter":[{"operator":">=","value":50,"attribute":"battery"}]}""",
        ) as LovelaceCard.EntityFilter
        val rule = c.stateFilter.single()
        assertThat(rule.operator).isEqualTo(StateFilterOperator.GTE)
        assertThat(rule.value).isEqualTo("50")
        assertThat(rule.attribute).isEqualTo("battery")
    }

    @Test fun `entity-filter parses in operator with a value list`() {
        val c = card(
            """{"type":"entity-filter","entities":["person.a"],
               "state_filter":[{"operator":"in","value":["home","work"]}]}""",
        ) as LovelaceCard.EntityFilter
        val rule = c.stateFilter.single()
        assertThat(rule.operator).isEqualTo(StateFilterOperator.IN)
        assertThat(rule.values).containsExactly("home", "work")
    }

    @Test fun `entity-filter drops an unknown operator rule`() {
        val c = card(
            """{"type":"entity-filter","entities":["light.a"],
               "state_filter":[{"operator":"~=","value":"x"}]}""",
        ) as LovelaceCard.EntityFilter
        assertThat(c.stateFilter).isEmpty()
    }

    // ── entity-filter: conditions ─────────────────────────────────────────────

    @Test fun `entity-filter parses modern conditions list`() {
        val c = card(
            """{"type":"entity-filter","entities":["light.a"],
               "conditions":[{"condition":"state","state":"on"}]}""",
        ) as LovelaceCard.EntityFilter
        assertThat(c.conditions).hasSize(1)
        assertThat(c.conditions.single()).isInstanceOf(LovelaceCondition.StateEquals::class.java)
        // A condition with no entity: keeps null so the candidate is substituted.
        assertThat((c.conditions.single() as LovelaceCondition.StateEquals).entityId).isNull()
    }

    // ── entity-filter: wrapped card + per-entity ──────────────────────────────

    @Test fun `entity-filter keeps a wrapped glance card config`() {
        val c = card(
            """{"type":"entity-filter","entities":["light.a"],"state_filter":["on"],
               "card":{"type":"glance","columns":2}}""",
        ) as LovelaceCard.EntityFilter
        assertThat(c.wrappedCard).isNotNull()
        assertThat(c.wrappedCard!!["type"].toString()).contains("glance")
    }

    @Test fun `entity-filter rejects a wrapped entity-filter to avoid recursion`() {
        val c = card(
            """{"type":"entity-filter","entities":["light.a"],"state_filter":["on"],
               "card":{"type":"entity-filter","entities":["light.b"]}}""",
        ) as LovelaceCard.EntityFilter
        assertThat(c.wrappedCard).isNull()
    }

    @Test fun `entity-filter parses per-entity state_filter override`() {
        val c = card(
            """{"type":"entity-filter","state_filter":["on"],
               "entities":[{"entity":"light.a","state_filter":["off"]}]}""",
        ) as LovelaceCard.EntityFilter
        val entry = c.entries.single()
        assertThat(entry.stateFilter).hasSize(1)
        assertThat(entry.stateFilter.single().value).isEqualTo("off")
    }

    @Test fun `entity-filter show_empty defaults true and parses false`() {
        val def = card("""{"type":"entity-filter","entities":["light.a"],"state_filter":["on"]}""")
            as LovelaceCard.EntityFilter
        assertThat(def.showEmpty).isTrue()
        val off = card(
            """{"type":"entity-filter","entities":["light.a"],"state_filter":["on"],"show_empty":false}""",
        ) as LovelaceCard.EntityFilter
        assertThat(off.showEmpty).isFalse()
    }

    // ── header / footer slot parsing ──────────────────────────────────────────

    @Test fun `entities card parses a graph footer`() {
        val c = card(
            """{"type":"entities","entities":["light.a"],
               "footer":{"type":"graph","entity":"sensor.power","hours_to_show":48,"detail":2,
                         "limits":{"min":0,"max":100}}}""",
        ) as LovelaceCard.Entities
        val graph = c.footer as LovelaceHeaderFooter.Graph
        assertThat(graph.entityId).isEqualTo("sensor.power")
        assertThat(graph.hoursToShow).isEqualTo(48)
        assertThat(graph.detail).isEqualTo(2)
        assertThat(graph.limitMin).isEqualTo(0.0)
        assertThat(graph.limitMax).isEqualTo(100.0)
    }

    @Test fun `entities card parses a buttons header`() {
        val c = card(
            """{"type":"entities","entities":["light.a"],
               "header":{"type":"buttons","entities":["light.a",{"entity":"scene.movie","name":"Movie"}]}}""",
        ) as LovelaceCard.Entities
        val buttons = c.header as LovelaceHeaderFooter.Buttons
        assertThat(buttons.entries).hasSize(2)
        assertThat(buttons.entries[0].entityId).isEqualTo("light.a")
        assertThat(buttons.entries[1].name).isEqualTo("Movie")
    }

    @Test fun `picture header requires an image and carries alt text and action`() {
        val slot = LovelaceParser.parseHeaderFooter(
            obj("""{"type":"picture","image":"/local/x.png","alt_text":"Banner",
                    "tap_action":{"action":"navigate","navigation_path":"/x"}}"""),
        )
        val picture = slot as LovelaceHeaderFooter.Picture
        assertThat(picture.image).isEqualTo("/local/x.png")
        assertThat(picture.altText).isEqualTo("Banner")
        assertThat(picture.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
    }

    @Test fun `picture header without image degrades to unsupported`() {
        val slot = LovelaceParser.parseHeaderFooter(obj("""{"type":"picture"}"""))
        assertThat(slot).isInstanceOf(LovelaceHeaderFooter.Unsupported::class.java)
    }

    @Test fun `graph detail clamps to one or two`() {
        val slot = LovelaceParser.parseHeaderFooter(
            obj("""{"type":"graph","entity":"sensor.x","detail":7}"""),
        ) as LovelaceHeaderFooter.Graph
        assertThat(slot.detail).isEqualTo(1)
        assertThat(slot.hoursToShow).isEqualTo(24)
    }

    @Test fun `unknown header type degrades to unsupported`() {
        val slot = LovelaceParser.parseHeaderFooter(obj("""{"type":"weird"}"""))
        assertThat(slot).isInstanceOf(LovelaceHeaderFooter.Unsupported::class.java)
    }

    // ── entity card options (parsed via the raw-config path) ───────────────────

    @Test fun `entity card config preserves attribute unit and actions in raw`() {
        // The entity card draws off raw JSON; verify the action parser reads its
        // tap_action and that the option keys survive in raw for the renderer.
        val raw = obj(
            """{"type":"entity","entity":"sensor.x","attribute":"temperature","unit":"C",
               "state_color":true,"tap_action":{"action":"toggle"},
               "footer":{"type":"graph","entity":"sensor.x"}}""",
        )
        assertThat(LovelaceParser.parseActionConfig(raw["tap_action"] as JsonObject))
            .isInstanceOf(LovelaceAction.Builtin::class.java)
        assertThat(LovelaceParser.parseHeaderFooter(raw["footer"]))
            .isInstanceOf(LovelaceHeaderFooter.Graph::class.java)
        assertThat(raw["attribute"].toString()).contains("temperature")
        assertThat(raw["unit"].toString()).contains("C")
    }
}
