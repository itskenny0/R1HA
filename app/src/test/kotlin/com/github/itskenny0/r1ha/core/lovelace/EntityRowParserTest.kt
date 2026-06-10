package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser coverage for the generic-row contract keys added to entities-card rows:
 * per-row confirmation / action_name / image and per-row tap / hold / double-tap
 * on each interactive row domain.
 */
class EntityRowParserTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun rows(entitiesJson: String): List<EntityRow> {
        val cfg = LovelaceParser.parseConfig(
            obj("""{"views":[{"path":"p","cards":[{"type":"entities","entities":$entitiesJson}]}]}"""),
        )
        return (cfg.views.first().cards.first() as LovelaceCard.Entities).entities
    }

    @Test fun `bare string row carries no contract keys`() {
        val row = rows("""["light.kitchen"]""").single()
        assertThat(row.entityId).isEqualTo("light.kitchen")
        assertThat(row.confirmation).isNull()
        assertThat(row.actionName).isNull()
        assertThat(row.image).isNull()
    }

    @Test fun `row confirmation true parses to a generic gate`() {
        val row = rows("""[{"entity":"scene.movie","confirmation":true}]""").single()
        val confirm = row.confirmation
        assertThat(confirm).isNotNull()
        assertThat(confirm!!.text).isNull()
        assertThat(confirm.exemptions).isEmpty()
    }

    @Test fun `row confirmation object parses text and exemptions`() {
        val row = rows(
            """[{"entity":"script.run","confirmation":{"text":"Sure?","exemptions":[{"user":"u1"}]}}]""",
        ).single()
        assertThat(row.confirmation!!.text).isEqualTo("Sure?")
        assertThat(row.confirmation!!.exemptions).containsExactly("u1")
    }

    @Test fun `row action_name parses`() {
        val row = rows("""[{"entity":"script.tidy","action_name":"Tidy up"}]""").single()
        assertThat(row.actionName).isEqualTo("Tidy up")
    }

    @Test fun `row image override parses`() {
        val row = rows("""[{"entity":"sensor.x","image":"/local/pic.png"}]""").single()
        assertThat(row.image).isEqualTo("/local/pic.png")
    }

    @Test fun `row parses per-row tap hold and double_tap actions`() {
        val row = rows(
            """
            [{"entity":"light.k",
              "tap_action":{"action":"more-info"},
              "hold_action":{"action":"toggle"},
              "double_tap_action":{"action":"navigate","navigation_path":"/lovelace/0"}}]
            """.trimIndent(),
        ).single()
        assertThat((row.tapAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat((row.holdAction as LovelaceAction.Builtin).name).isEqualTo("toggle")
        assertThat((row.doubleTapAction as LovelaceAction.Navigate).path).isEqualTo("/lovelace/0")
    }

    @Test fun `confirmation rides each action-bearing domain row`() {
        // button / input_button / lock / scene / script all route confirmation
        // through the row-level key (copied onto the control's service call).
        listOf("button.b", "input_button.ib", "lock.l", "scene.s", "script.sc").forEach { eid ->
            val row = rows("""[{"entity":"$eid","confirmation":true}]""").single()
            assertThat(row.confirmation).isNotNull()
        }
    }
}
