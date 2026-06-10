package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Round-trip coverage for the action-system parser additions: hold_action /
 * double_tap_action on cards, the confirmation key, full service target,
 * the more-info entity override, navigation_replace, and the Invalid /
 * misconfiguration cases.
 */
class LovelaceActionParserTest {

    private fun obj(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    private fun firstCard(cardJson: String): LovelaceCard {
        val cfg = LovelaceParser.parseConfig(
            obj("""{"views":[{"path":"p","cards":[$cardJson]}]}"""),
        )
        return cfg.views.first().cards.first()
    }

    @Test fun `tile parses hold and double_tap actions`() {
        val card = firstCard(
            """
            {"type":"tile","entity":"light.kitchen",
             "tap_action":{"action":"toggle"},
             "hold_action":{"action":"more-info"},
             "double_tap_action":{"action":"navigate","navigation_path":"/lovelace/lights"}}
            """.trimIndent(),
        ) as LovelaceCard.Tile
        assertThat(card.tapAction).isInstanceOf(LovelaceAction.Builtin::class.java)
        assertThat((card.holdAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat((card.doubleTapAction as LovelaceAction.Navigate).path).isEqualTo("/lovelace/lights")
    }

    @Test fun `button parses hold and double_tap actions`() {
        val card = firstCard(
            """
            {"type":"button","entity":"switch.x",
             "hold_action":{"action":"more-info"},
             "double_tap_action":{"action":"toggle"}}
            """.trimIndent(),
        ) as LovelaceCard.Button
        assertThat((card.holdAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat((card.doubleTapAction as LovelaceAction.Builtin).name).isEqualTo("toggle")
    }

    @Test fun `confirmation true parses to a generic gate`() {
        val card = firstCard(
            """{"type":"button","entity":"lock.front","tap_action":{"action":"toggle","confirmation":true}}""",
        ) as LovelaceCard.Button
        val confirm = card.tapAction!!.confirmation
        assertThat(confirm).isNotNull()
        assertThat(confirm!!.text).isNull()
        assertThat(confirm.exemptions).isEmpty()
    }

    @Test fun `confirmation object parses text title buttons and exemptions`() {
        val card = firstCard(
            """
            {"type":"button","entity":"lock.front","tap_action":{"action":"toggle",
             "confirmation":{"text":"Unlock the door?","title":"Front door",
               "confirm_text":"Unlock","dismiss_text":"Keep locked",
               "exemptions":[{"user":"abc123"},{"user":"def456"}]}}}
            """.trimIndent(),
        ) as LovelaceCard.Button
        val c = card.tapAction!!.confirmation!!
        assertThat(c.text).isEqualTo("Unlock the door?")
        assertThat(c.title).isEqualTo("Front door")
        assertThat(c.confirmText).isEqualTo("Unlock")
        assertThat(c.dismissText).isEqualTo("Keep locked")
        assertThat(c.exemptions).containsExactly("abc123", "def456")
    }

    @Test fun `call-service parses full target with lists and single ids`() {
        val card = firstCard(
            """
            {"type":"button","tap_action":{"action":"perform-action","perform_action":"light.turn_on",
             "target":{"entity_id":["light.a","light.b"],"device_id":"dev1",
               "area_id":["area1","area2"],"floor_id":"floor1","label_id":["lab1"]},
             "data":{"brightness":255}}}
            """.trimIndent(),
        ) as LovelaceCard.Button
        val a = card.tapAction as LovelaceAction.CallService
        assertThat(a.service).isEqualTo("light.turn_on")
        val t = a.target!!
        assertThat(t.entityId).containsExactly("light.a", "light.b")
        assertThat(t.deviceId).containsExactly("dev1")
        assertThat(t.areaId).containsExactly("area1", "area2")
        assertThat(t.floorId).containsExactly("floor1")
        assertThat(t.labelId).containsExactly("lab1")
        // Single-entity convenience field still resolves to the first entity id.
        assertThat(a.entityId).isEqualTo("light.a")
    }

    @Test fun `more-info parses action-level entity override`() {
        val card = firstCard(
            """{"type":"button","entity":"light.kitchen","tap_action":{"action":"more-info","entity":"sensor.other"}}""",
        ) as LovelaceCard.Button
        val a = card.tapAction as LovelaceAction.Builtin
        assertThat(a.name).isEqualTo("more-info")
        assertThat(a.entityId).isEqualTo("sensor.other")
    }

    @Test fun `navigate parses navigation_replace`() {
        val card = firstCard(
            """{"type":"button","tap_action":{"action":"navigate","navigation_path":"/lovelace/x","navigation_replace":true}}""",
        ) as LovelaceCard.Button
        val a = card.tapAction as LovelaceAction.Navigate
        assertThat(a.path).isEqualTo("/lovelace/x")
        assertThat(a.replace).isTrue()
    }

    @Test fun `assist parses pipeline_id and start_listening`() {
        val card = firstCard(
            """{"type":"button","tap_action":{"action":"assist","pipeline_id":"pipe-1","start_listening":true}}""",
        ) as LovelaceCard.Button
        val a = card.tapAction as LovelaceAction.Builtin
        assertThat(a.name).isEqualTo("assist")
        assertThat(a.pipelineId).isEqualTo("pipe-1")
        assertThat(a.startListening).isTrue()
    }

    @Test fun `navigate without path parses to Invalid`() {
        val card = firstCard(
            """{"type":"button","tap_action":{"action":"navigate"}}""",
        ) as LovelaceCard.Button
        assertThat(card.tapAction).isInstanceOf(LovelaceAction.Invalid::class.java)
    }

    @Test fun `url without url parses to Invalid`() {
        val card = firstCard(
            """{"type":"button","tap_action":{"action":"url"}}""",
        ) as LovelaceCard.Button
        assertThat(card.tapAction).isInstanceOf(LovelaceAction.Invalid::class.java)
    }

    @Test fun `call-service without service parses to Invalid`() {
        val card = firstCard(
            """{"type":"button","tap_action":{"action":"call-service"}}""",
        ) as LovelaceCard.Button
        assertThat(card.tapAction).isInstanceOf(LovelaceAction.Invalid::class.java)
    }

    @Test fun `entity row parses per-row hold and double_tap`() {
        val card = firstCard(
            """
            {"type":"entities","entities":[
              {"entity":"light.a","hold_action":{"action":"more-info"},
               "double_tap_action":{"action":"toggle"}}]}
            """.trimIndent(),
        ) as LovelaceCard.Entities
        val row = card.entities.first()
        assertThat((row.holdAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat((row.doubleTapAction as LovelaceAction.Builtin).name).isEqualTo("toggle")
    }

    @Test fun `badge parses hold and double_tap`() {
        val cfg = LovelaceParser.parseConfig(
            obj(
                """
                {"views":[{"path":"p","badges":[
                  {"type":"entity","entity":"sensor.t",
                   "hold_action":{"action":"more-info"},
                   "double_tap_action":{"action":"navigate","navigation_path":"/lovelace/y"}}],
                 "cards":[]}]}
                """.trimIndent(),
            ),
        )
        val badge = cfg.views.first().badges.first()
        assertThat((badge.holdAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat((badge.doubleTapAction as LovelaceAction.Navigate).path).isEqualTo("/lovelace/y")
    }
}
