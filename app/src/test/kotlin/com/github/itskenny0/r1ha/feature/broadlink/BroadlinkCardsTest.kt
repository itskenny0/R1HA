package com.github.itskenny0.r1ha.feature.broadlink

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Test

class BroadlinkCardsTest {

    private fun JsonObject.obj(key: String): JsonObject = this[key] as JsonObject
    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content

    @Test fun `command button card carries the full send_command call`() {
        val card = BroadlinkCards.commandButtonCard(
            remoteEntityId = "remote.rm4",
            deviceName = "tv",
            commandName = "power",
            label = "TV POWER",
            repeats = 3,
        )
        assertThat(card.str("type")).isEqualTo("button")
        assertThat(card.str("name")).isEqualTo("TV POWER")
        val tap = card.obj("tap_action")
        assertThat(tap.str("action")).isEqualTo("call-service")
        assertThat(tap.str("service")).isEqualTo("remote.send_command")
        assertThat(tap.obj("target").str("entity_id")).isEqualTo("remote.rm4")
        val data = tap.obj("data")
        assertThat(data.str("device")).isEqualTo("tv")
        assertThat(data.str("command")).isEqualTo("power")
        assertThat(data.str("num_repeats")).isEqualTo("3")
    }

    @Test fun `single-shot card omits num_repeats`() {
        val card = BroadlinkCards.commandButtonCard(
            remoteEntityId = "remote.rm4",
            deviceName = "tv",
            commandName = "power",
            label = "power",
        )
        assertThat(card.obj("tap_action").obj("data").containsKey("num_repeats")).isFalse()
    }

    @Test fun `labels with quotes survive the JSON encoding`() {
        val card = BroadlinkCards.commandButtonCard(
            remoteEntityId = "remote.rm4",
            deviceName = "amp \"living\"",
            commandName = "vol+",
            label = "Bob's \"loud\" button",
        )
        val text = card.toString()
        val parsed = kotlinx.serialization.json.Json.parseToJsonElement(text) as JsonObject
        assertThat(parsed.str("name")).isEqualTo("Bob's \"loud\" button")
        assertThat(parsed.obj("tap_action").obj("data").str("device")).isEqualTo("amp \"living\"")
    }

    @Test fun `automation button card triggers the automation`() {
        val card = BroadlinkCards.automationButtonCard("automation.evening_tv", "EVENING TV")
        val tap = card.obj("tap_action")
        assertThat(tap.str("service")).isEqualTo("automation.trigger")
        assertThat(tap.obj("target").str("entity_id")).isEqualTo("automation.evening_tv")
        assertThat(tap.obj("data").str("skip_condition")).isEqualTo("true")
    }

    @Test fun `both generated button cards wear the remote icon`() {
        // mdi:remote resolves to a real remote glyph in the app icon set;
        // mdi:robot used to fall back to a cog, which read as a settings tile.
        val command = BroadlinkCards.commandButtonCard(
            remoteEntityId = "remote.rm4",
            deviceName = "tv",
            commandName = "power",
            label = "TV POWER",
        )
        val automation = BroadlinkCards.automationButtonCard("automation.evening_tv", "EVENING TV")
        assertThat(command.str("icon")).isEqualTo("mdi:remote")
        assertThat(automation.str("icon")).isEqualTo("mdi:remote")
        // Fire-and-forget buttons must not advertise a live state line.
        assertThat(command.str("show_state")).isEqualTo("false")
        assertThat(automation.str("show_state")).isEqualTo("false")
    }

    @Test fun `time-trigger automation config has the classic shape`() {
        val cfg = BroadlinkCards.automationConfig(
            alias = "AC on at seven",
            trigger = BroadlinkCards.Trigger.AtTime("07:00:00"),
            remoteEntityId = "remote.rm4",
            deviceName = "ac",
            commandName = "on",
            repeats = 2,
        )
        assertThat(cfg.str("alias")).isEqualTo("AC on at seven")
        assertThat(cfg.str("mode")).isEqualTo("single")
        val trigger = (cfg["trigger"] as JsonArray).single() as JsonObject
        assertThat(trigger.str("platform")).isEqualTo("time")
        assertThat(trigger.str("at")).isEqualTo("07:00:00")
        assertThat(cfg["condition"]).isEqualTo(JsonArray(emptyList()))
        val action = (cfg["action"] as JsonArray).single() as JsonObject
        assertThat(action.str("service")).isEqualTo("remote.send_command")
        assertThat(action.obj("target").str("entity_id")).isEqualTo("remote.rm4")
        assertThat(action.obj("data").str("device")).isEqualTo("ac")
        assertThat(action.obj("data").str("command")).isEqualTo("on")
        assertThat(action.obj("data").str("num_repeats")).isEqualTo("2")
    }

    @Test fun `state-trigger automation config carries entity and to-state`() {
        val cfg = BroadlinkCards.automationConfig(
            alias = "TV off when away",
            trigger = BroadlinkCards.Trigger.EntityState("person.kenny", "not_home"),
            remoteEntityId = "remote.rm4",
            deviceName = "tv",
            commandName = "off",
        )
        val trigger = (cfg["trigger"] as JsonArray).single() as JsonObject
        assertThat(trigger.str("platform")).isEqualTo("state")
        assertThat(trigger.str("entity_id")).isEqualTo("person.kenny")
        assertThat(trigger.str("to")).isEqualTo("not_home")
        val data = ((cfg["action"] as JsonArray).single() as JsonObject).obj("data")
        assertThat(data.containsKey("num_repeats")).isFalse()
    }

    @Test fun `blank to-state means any change and is omitted`() {
        val cfg = BroadlinkCards.automationConfig(
            alias = "x",
            trigger = BroadlinkCards.Trigger.EntityState("binary_sensor.door", ""),
            remoteEntityId = "remote.rm4",
            deviceName = "tv",
            commandName = "off",
        )
        val trigger = (cfg["trigger"] as JsonArray).single() as JsonObject
        assertThat(trigger.containsKey("to")).isFalse()
    }

    @Test fun `catalog automation config stores the command with empty triggers`() {
        val meta = BroadlinkMarker.CommandMeta(
            remote = "remote.rm4",
            device = "tv",
            command = "power",
            type = "ir",
        )
        val cfg = BroadlinkCards.commandAutomationConfig(alias = "TV · Power (R1HA IR)", meta = meta)
        assertThat(cfg.str("alias")).isEqualTo("TV · Power (R1HA IR)")
        assertThat(cfg.str("mode")).isEqualTo("single")
        // Empty trigger list: the automation never self-fires; it exists as
        // the catalog record and the manual-trigger target.
        assertThat(cfg["trigger"]).isEqualTo(JsonArray(emptyList()))
        assertThat(cfg["condition"]).isEqualTo(JsonArray(emptyList()))
        val action = (cfg["action"] as JsonArray).single() as JsonObject
        assertThat(action.str("service")).isEqualTo("remote.send_command")
        assertThat(action.obj("target").str("entity_id")).isEqualTo("remote.rm4")
        assertThat(action.obj("data").str("device")).isEqualTo("tv")
        assertThat(action.obj("data").str("command")).isEqualTo("power")
        // No num_repeats in the stored body: repeats is a fire-time option.
        assertThat(action.obj("data").containsKey("num_repeats")).isFalse()
        // The description marker round-trips through the marker parser.
        val parsed = BroadlinkMarker.parse(cfg.str("description"))
        assertThat(parsed).isEqualTo(BroadlinkMarker.Parsed.Marked(meta))
    }

    @Test fun `marker-tagged config bodies match the broadlink filter exactly`() {
        // The marker alone matches: no send_command action, no known
        // remote ids, even an unknown marker version.
        val body =
            """{"alias":"x","description":"R1HA|Broadlink|v9|{}","action":[{"service":"script.turn_on"}]}"""
        assertThat(BroadlinkCards.isBroadlinkRelated(body, "x", emptySet())).isTrue()
    }

    @Test fun `config bodies referencing send_command are broadlink-related`() {
        val body = """{"alias":"x","action":[{"service":"remote.send_command"}]}"""
        assertThat(BroadlinkCards.isBroadlinkRelated(body, "x", emptySet())).isTrue()
    }

    @Test fun `config bodies referencing a known remote are broadlink-related`() {
        val body = """{"alias":"x","action":[{"service":"remote.turn_on","target":{"entity_id":"remote.rm4"}}]}"""
        assertThat(BroadlinkCards.isBroadlinkRelated(body, "x", setOf("remote.rm4"))).isTrue()
        assertThat(BroadlinkCards.isBroadlinkRelated(body, "x", setOf("remote.other"))).isFalse()
    }

    @Test fun `unrelated config bodies are filtered out`() {
        val body = """{"alias":"lights","action":[{"service":"light.turn_on"}]}"""
        assertThat(BroadlinkCards.isBroadlinkRelated(body, "lights", setOf("remote.rm4"))).isFalse()
    }

    @Test fun `name heuristic applies only when the body is unavailable`() {
        assertThat(BroadlinkCards.isBroadlinkRelated(null, "Broadlink night sweep", emptySet()))
            .isTrue()
        assertThat(
            BroadlinkCards.isBroadlinkRelated(null, "Living room rm4 macro", setOf("remote.living_room_rm4")),
        ).isTrue()
        assertThat(BroadlinkCards.isBroadlinkRelated(null, "Water the plants", setOf("remote.rm4")))
            .isFalse()
    }

    @Test fun `new automation ids are epoch millis`() {
        assertThat(BroadlinkCards.newAutomationId(1760000000123L)).isEqualTo("1760000000123")
    }
}
