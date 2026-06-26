package com.github.itskenny0.r1ha.feature.cardstack

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.jupiter.api.Test

/**
 * Contract of the generic field schema ([cardFieldsFor]) and its emit path
 * ([emitCardField] via [buildStructuredCard]): every field maps to a real config
 * key, the per-type key sets stay disjoint from the toggle table and the
 * hand-rendered primaries, and the keep-it-clean / lossless-passthrough rules
 * hold per field kind.
 */
class CardFieldSpecTest {

    private val commonTypes = listOf(
        "button", "tile", "light", "gauge", "sensor", "thermostat",
        "humidifier", "weather-forecast", "entities", "glance", "history-graph",
    )

    /** The keys the editor hand-renders as primary controls for a type (not via
     *  the generic field engine), mirroring [buildStructuredCard]'s ownership. */
    private fun handRenderedPrimaryKeys(type: String): Set<String> = buildSet {
        when {
            type == "heading" -> add("heading")
            type == "button" -> { add("name"); add("icon") }
            typeOwnsNameField(type) -> Unit // name via the engine field
            else -> add("title")
        }
        if (type in SINGLE_ENTITY_TYPES) add("entity")
        if (type == "iframe") { add("url"); add("aspect_ratio") }
        if (type == "markdown") add("content")
        if (type in MULTI_ENTITY_TYPES) add("entities")
    }

    @Test
    fun fieldKeySetsAreDisjointFromTogglesAndPrimaries() {
        for (type in commonTypes) {
            val fieldKeys = cardFieldsFor(type).map { it.key }
            val toggleKeys = cardTogglesFor(type).map { it.key }.toSet()
            val primaryKeys = handRenderedPrimaryKeys(type)
            // Each config key has exactly one emit path: a generic field must not
            // also be a toggle or a hand-rendered primary, or it gets emitted twice
            // (the later write wins and silently reverts the other's edit).
            assertThat(fieldKeys.intersect(toggleKeys)).isEmpty()
            assertThat(fieldKeys.intersect(primaryKeys)).isEmpty()
            assertThat(fieldKeys).containsNoDuplicates()
        }
    }

    @Test
    fun nameFieldImpliesTitleNotOwned() {
        // The two ways a card labels itself are mutually exclusive: a type with a
        // `name` field must not also own `title` (else the editor edits both).
        for (type in commonTypes) {
            if (typeOwnsNameField(type)) {
                assertThat(cardFieldsFor(type).any { it.key == "name" }).isTrue()
            }
        }
        assertThat(typeOwnsNameField("tile")).isTrue()
        assertThat(typeOwnsNameField("entities")).isFalse()
    }

    @Test
    fun textFieldEmitsWhenSetAndDropsWhenBlank() {
        val base = buildJsonObject { put("type", "gauge"); put("entity", "sensor.x") }
        val withName = buildStructuredCard(
            base,
            CardEditorForm(type = "gauge", entity = "sensor.x", values = mapOf("name" to JsonPrimitive("Power"))),
        )
        assertThat(withName["name"]).isEqualTo(JsonPrimitive("Power"))

        val cleared = buildStructuredCard(
            buildJsonObject { put("type", "gauge"); put("entity", "sensor.x"); put("name", "Power") },
            CardEditorForm(type = "gauge", entity = "sensor.x", values = mapOf("name" to JsonPrimitive(""))),
        )
        assertThat(cleared.containsKey("name")).isFalse()
    }

    @Test
    fun numberFieldEmitsAsRealJsonNumber() {
        val base = buildJsonObject { put("type", "gauge"); put("entity", "sensor.x") }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(
                type = "gauge",
                entity = "sensor.x",
                values = mapOf("min" to JsonPrimitive("0"), "max" to JsonPrimitive("100")),
            ),
        )
        // Real numbers, not quoted strings.
        assertThat(edited["min"]).isEqualTo(JsonPrimitive(0L))
        assertThat(edited["max"]).isEqualTo(JsonPrimitive(100L))
        assertThat((edited["min"] as JsonPrimitive).isString).isFalse()
    }

    @Test
    fun numberFieldDecimalAndIntegerCoercion() {
        val base = buildJsonObject { put("type", "history-graph") }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(
                type = "history-graph",
                title = "T",
                values = mapOf(
                    "hours_to_show" to JsonPrimitive("24"),
                    "min_y_axis" to JsonPrimitive("1.5"),
                ),
            ),
        )
        assertThat(edited["hours_to_show"]).isEqualTo(JsonPrimitive(24L))
        assertThat(edited["min_y_axis"]).isEqualTo(JsonPrimitive(1.5))
    }

    @Test
    fun unparseableNumberDropsTheKey() {
        val base = buildJsonObject { put("type", "gauge"); put("entity", "sensor.x") }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "gauge", entity = "sensor.x", values = mapOf("min" to JsonPrimitive("abc"))),
        )
        assertThat(edited.containsKey("min")).isFalse()
    }

    @Test
    fun enumAtDefaultStaysAbsentUnlessStored() {
        val base = buildJsonObject { put("type", "sensor"); put("entity", "sensor.x") }
        val atDefault = buildStructuredCard(
            base,
            CardEditorForm(type = "sensor", entity = "sensor.x", values = mapOf("graph" to JsonPrimitive("none"))),
        )
        assertThat(atDefault.containsKey("graph")).isFalse()

        val deviating = buildStructuredCard(
            base,
            CardEditorForm(type = "sensor", entity = "sensor.x", values = mapOf("graph" to JsonPrimitive("line"))),
        )
        assertThat(deviating["graph"]).isEqualTo(JsonPrimitive("line"))
    }

    @Test
    fun boolFieldFollowsDeviateOrPresentRule() {
        val base = buildJsonObject { put("type", "tile"); put("entity", "light.k") }
        // vertical default false: at false, absent -> stays absent.
        val atDefault = buildStructuredCard(
            base,
            CardEditorForm(type = "tile", entity = "light.k", values = mapOf("vertical" to JsonPrimitive(false))),
        )
        assertThat(atDefault.containsKey("vertical")).isFalse()
        // flipped true -> emitted.
        val flipped = buildStructuredCard(
            base,
            CardEditorForm(type = "tile", entity = "light.k", values = mapOf("vertical" to JsonPrimitive(true))),
        )
        assertThat(flipped["vertical"]).isEqualTo(JsonPrimitive(true))
    }

    @Test
    fun actionFieldEmitsObjectAndRoundTrips() {
        val base = buildJsonObject { put("type", "button"); put("name", "TV") }
        val action = buildJsonObject { put("action", "navigate"); put("navigation_path", "/lovelace/0") }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "button", name = "TV", values = mapOf("tap_action" to action)),
        )
        assertThat(edited["tap_action"]).isEqualTo(action)
    }

    @Test
    fun seedFieldValuesCopiesPresentKeysOnly() {
        val base = buildJsonObject {
            put("type", "tile")
            put("entity", "light.k")
            put("color", "red")
            put("vertical", true)
            putJsonObject("tap_action") { put("action", "toggle") }
        }
        val seeded = seedFieldValues(base, "tile")
        assertThat(seeded["color"]).isEqualTo(JsonPrimitive("red"))
        assertThat(seeded["vertical"]).isEqualTo(JsonPrimitive(true))
        assertThat(seeded["tap_action"]).isEqualTo(base["tap_action"])
        // entity is a hand-rendered primary, not a generic field.
        assertThat(seeded.containsKey("entity")).isFalse()
        assertThat(seeded.containsKey("name")).isFalse()
    }

    @Test
    fun foreignKeysStillPassThroughWithFields() {
        val base = buildJsonObject {
            put("type", "tile")
            put("entity", "light.k")
            putJsonObject("grid_options") { put("columns", 6) }
        }
        val edited = buildStructuredCard(
            base,
            CardEditorForm(type = "tile", entity = "light.k", values = mapOf("color" to JsonPrimitive("blue"))),
        )
        assertThat(edited["grid_options"]).isEqualTo(base["grid_options"])
        assertThat(edited["color"]).isEqualTo(JsonPrimitive("blue"))
    }
}
