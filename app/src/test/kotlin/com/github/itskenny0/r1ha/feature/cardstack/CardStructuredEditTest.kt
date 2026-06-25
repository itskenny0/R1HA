package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.feature.broadlink.BroadlinkCards
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/**
 * Round-trip contract of [buildStructuredCard]: the structured editor re-emits
 * only the keys its form owns for the card's type and copies everything else
 * through verbatim. The button cases run against the REAL Broadlink card
 * builders, since "never drop a pinned IR button's tap_action" is the whole
 * point of the passthrough rule.
 */
class CardStructuredEditTest {

    private fun broadlinkCommandCard(): JsonObject = BroadlinkCards.commandButtonCard(
        remoteEntityId = "remote.living_room_blaster",
        deviceName = "TV",
        commandName = "power",
        label = "TV Power",
        repeats = 3,
    )

    @Test
    fun buttonEditPreservesTapActionVerbatim() {
        val base = broadlinkCommandCard()
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "button", name = "Telly", icon = "mdi:power"),
        )
        // The call-service block (service, target, data incl. num_repeats)
        // must survive untouched: the form doesn't model it.
        assertThat(edited["tap_action"]).isEqualTo(base["tap_action"])
        assertThat(edited["type"]).isEqualTo(JsonPrimitive("button"))
    }

    @Test
    fun buttonEditAppliesNameAndIcon() {
        val edited = buildStructuredCard(
            broadlinkCommandCard(),
            CardEditorForm(type = "button", name = "Telly", icon = "mdi:power"),
        )
        assertThat(edited["name"]).isEqualTo(JsonPrimitive("Telly"))
        assertThat(edited["icon"]).isEqualTo(JsonPrimitive("mdi:power"))
    }

    @Test
    fun buttonTogglesEmitWhenDeviatingFromHaDefaults() {
        val edited = buildStructuredCard(
            broadlinkCommandCard(),
            CardEditorForm(
                type = "button",
                name = "TV Power",
                toggles = mapOf("show_name" to false, "show_icon" to false, "show_state" to true),
            ),
        )
        assertThat(edited["show_name"]).isEqualTo(JsonPrimitive(false))
        assertThat(edited["show_icon"]).isEqualTo(JsonPrimitive(false))
        assertThat(edited["show_state"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun buttonTogglesAtDefaultStayAbsentUnlessAlreadyStored() {
        // The Broadlink builder stores show_state: false explicitly; show_name /
        // show_icon are absent. Saving with all-default toggles must keep the
        // stored key (no silent drop) and not invent the absent ones.
        val base = broadlinkCommandCard()
        val edited = buildStructuredCard(base, CardEditorForm(type = "button", name = "TV Power"))
        assertThat(edited.containsKey("show_name")).isFalse()
        assertThat(edited.containsKey("show_icon")).isFalse()
        assertThat(edited["show_state"]).isEqualTo(JsonPrimitive(false))
    }

    @Test
    fun buttonClearingNameRemovesTheKey() {
        val edited = buildStructuredCard(
            broadlinkCommandCard(),
            CardEditorForm(type = "button", name = ""),
        )
        assertThat(edited.containsKey("name")).isFalse()
    }

    @Test
    fun buttonLeavesForeignTitleKeyUntouched() {
        // Buttons label via name:, not title:. A stray title in the config is
        // not the form's to rewrite.
        val base = buildJsonObject {
            put("type", "button")
            put("title", "legacy")
            put("name", "TV")
        }
        val edited = buildStructuredCard(base, CardEditorForm(type = "button", name = "TV", title = "ignored"))
        assertThat(edited["title"]).isEqualTo(JsonPrimitive("legacy"))
    }

    @Test
    fun buttonAutomationTriggerCardRoundTrips() {
        // The ×1 pin shape: automation.trigger with skip_condition. Same
        // passthrough guarantee as the send_command shape.
        val base = BroadlinkCards.automationButtonCard(
            automationEntityId = "automation.tv_power",
            label = "TV Power",
        )
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "button", name = "TV", icon = "mdi:television"),
        )
        assertThat(edited["tap_action"]).isEqualTo(base["tap_action"])
    }

    @Test
    fun unknownKeysPassThroughVerbatim() {
        val base = buildJsonObject {
            put("type", "button")
            put("name", "TV")
            putJsonObject("grid_options") { put("columns", 6) }
            putJsonObject("hold_action") { put("action", "more-info") }
        }
        val edited = buildStructuredCard(base, CardEditorForm(type = "button", name = "TV"))
        assertThat(edited["grid_options"]).isEqualTo(base["grid_options"])
        assertThat(edited["hold_action"]).isEqualTo(base["hold_action"])
    }

    @Test
    fun titleAppliesToNonButtonTypes() {
        val base = buildJsonObject {
            put("type", "tile")
            put("entity", "light.kitchen")
        }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "tile", title = "Kitchen", entity = "light.kitchen"),
        )
        assertThat(edited["title"]).isEqualTo(JsonPrimitive("Kitchen"))
        assertThat(edited["entity"]).isEqualTo(JsonPrimitive("light.kitchen"))
    }

    @Test
    fun historyGraphKeepsItsEntitiesArray() {
        // history-graph is a single-entity form type but legally carries an
        // entities: array; the form doesn't own that key for it, so the array
        // must survive a structured save.
        val base = buildJsonObject {
            put("type", "history-graph")
            put(
                "entities",
                kotlinx.serialization.json.JsonArray(
                    listOf(JsonPrimitive("sensor.a"), JsonPrimitive("sensor.b")),
                ),
            )
        }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "history-graph", title = "Temps"),
        )
        assertThat(edited["entities"]).isEqualTo(base["entities"])
    }

    @Test
    fun multiEntityListRebuildsFromTheForm() {
        val base = buildJsonObject {
            put("type", "entities")
            put(
                "entities",
                kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("light.old"))),
            )
        }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "entities", entities = listOf("light.a", "", "switch.b")),
        )
        assertThat(edited["entities"]).isEqualTo(
            kotlinx.serialization.json.JsonArray(
                listOf(JsonPrimitive("light.a"), JsonPrimitive("switch.b")),
            ),
        )
    }

    @Test
    fun boolOrReadsExplicitAndDefaultedToggles() {
        val obj = buildJsonObject {
            put("show_state", false)
            put("show_icon", true)
        }
        assertThat(obj.boolOr("show_state", true)).isFalse()
        assertThat(obj.boolOr("show_icon", false)).isTrue()
        assertThat(obj.boolOr("show_name", true)).isTrue()
        assertThat((null as JsonObject?).boolOr("show_name", false)).isFalse()
    }

    @Test
    fun glanceTogglesEmitWhenDeviating() {
        val base = buildJsonObject {
            put("type", "glance")
            put("entities", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("light.a"))))
        }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "glance", entities = listOf("light.a"), toggles = mapOf("show_name" to false)),
        )
        assertThat(edited["show_name"]).isEqualTo(JsonPrimitive(false))
        // show_state default (true) untouched and absent in base -> stays absent.
        assertThat(edited.containsKey("show_state")).isFalse()
    }

    @Test
    fun tileHideStateEmitsRealKey() {
        // The editor stores the real key value; hide_state default is false.
        val base = buildJsonObject { put("type", "tile"); put("entity", "light.k") }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "tile", entity = "light.k", toggles = mapOf("hide_state" to true)),
        )
        assertThat(edited["hide_state"]).isEqualTo(JsonPrimitive(true))
    }
}
