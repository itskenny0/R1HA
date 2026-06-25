package com.github.itskenny0.r1ha.feature.cardstack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Pure write-back logic for the structured card editor ([CardMiniEditor]).
 *
 * The contract that keeps structured edits safe on configs the form doesn't
 * fully model (a Broadlink button's call-service `tap_action`, grid options,
 * custom keys): the form OWNS a small per-type key set and re-emits only
 * those; every other key passes through verbatim from the original config.
 * Hoisted out of the composable so the round-trip is unit-testable.
 */

/** Single-entity config key applies to these card types. */
internal val SINGLE_ENTITY_TYPES = setOf(
    "tile", "light", "gauge", "button", "sensor", "thermostat", "humidifier",
    "media-control", "alarm-panel", "weather-forecast", "picture-entity",
    "statistic", "history-graph",
)

/** Multi-entity list (plain `entities:` array) applies to these. */
internal val MULTI_ENTITY_TYPES = setOf("entities", "glance")

/**
 * Everything the structured form can hold, regardless of card type;
 * [buildStructuredCard] only consults the fields the [type] actually edits.
 * Show/hide toggles live in [toggles], keyed by real config key; their per-key
 * defaults come from [cardTogglesFor] so a config that omits a key emits nothing.
 */
internal data class CardEditorForm(
    val type: String,
    val title: String = "",
    val heading: String = "",
    val entity: String = "",
    val url: String = "",
    val aspect: String = "",
    val content: String = "",
    val entities: List<String> = emptyList(),
    val name: String = "",
    val icon: String = "",
    /** Real config-key -> value (HIDE-sense already resolved). Driven by [cardTogglesFor]. */
    val toggles: Map<String, Boolean> = emptyMap(),
)

/** The config keys the form owns (re-emits) for this card type. */
private fun editedKeysFor(type: String): Set<String> = buildSet {
    when (type) {
        // Headings label via `heading:`; buttons via `name:` (the button card
        // has no `title:` key, so a stray one passes through untouched).
        "heading" -> add("heading")
        "button" -> Unit
        else -> add("title")
    }
    if (type in SINGLE_ENTITY_TYPES) add("entity")
    if (type == "iframe") {
        add("url")
        add("aspect_ratio")
    }
    if (type == "markdown") add("content")
    if (type in MULTI_ENTITY_TYPES) add("entities")
    if (type == "button") {
        add("name")
        add("icon")
    }
    cardTogglesFor(type).forEach { add(it.key) }
}

/**
 * Re-build a card config from its original [base] plus the edited [form]:
 * unowned keys (tap_action and friends) copy through verbatim, owned keys are
 * re-emitted from the form (blank string = key removed, matching how the
 * form clears a field).
 *
 * Button toggles emit when they deviate from HA's default OR the key was
 * already present (so flipping a stored `show_state: false` to true and back
 * keeps the explicit key instead of silently dropping it).
 */
internal fun buildStructuredCard(base: JsonObject, form: CardEditorForm): JsonObject {
    val type = form.type
    val edited = editedKeysFor(type)
    return buildJsonObject {
        base.forEach { (k, v) ->
            if (k !in edited) put(k, v)
        }
        fun putIfSet(key: String, value: String) {
            if (value.isNotBlank()) put(key, JsonPrimitive(value))
        }
        when (type) {
            "heading" -> putIfSet("heading", form.heading)
            "button" -> Unit
            else -> putIfSet("title", form.title)
        }
        if (type in SINGLE_ENTITY_TYPES) putIfSet("entity", form.entity)
        if (type == "iframe") {
            putIfSet("url", form.url)
            putIfSet("aspect_ratio", form.aspect)
        }
        if (type == "markdown") putIfSet("content", form.content)
        if (type in MULTI_ENTITY_TYPES) {
            put(
                "entities",
                JsonArray(form.entities.filter { it.isNotBlank() }.map { JsonPrimitive(it) }),
            )
        }
        if (type == "button") {
            putIfSet("name", form.name)
            putIfSet("icon", form.icon)
        }
        // Generic native show/hide toggles for the card type. Emit only when the
        // value deviates from the app default OR the key was already present, so
        // round-trips stay lossless and configs stay clean.
        for (t in cardTogglesFor(type)) {
            val v = form.toggles[t.key] ?: t.default
            if (v != t.default || base.containsKey(t.key)) put(t.key, JsonPrimitive(v))
        }
    }
}

/** Boolean config read with an HA-style default for an absent key. */
internal fun JsonObject?.boolOr(key: String, default: Boolean): Boolean =
    (this?.get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
