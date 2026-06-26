package com.github.itskenny0.r1ha.feature.cardstack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
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

/** Single-entity config key applies to these card types. history-graph is
 *  deliberately excluded: the renderer reads `entities:` (a list), not `entity:`,
 *  so a single-entity picker there would write a dead key and hide the real
 *  series. It is treated like the other entity-list cards (title + options in the
 *  form, entities array passed through). */
internal val SINGLE_ENTITY_TYPES = setOf(
    "tile", "light", "gauge", "button", "sensor", "thermostat", "humidifier",
    "media-control", "alarm-panel", "weather-forecast", "picture-entity",
    "statistic",
)

/** Multi-entity list (plain `entities:` array) applies to these. */
internal val MULTI_ENTITY_TYPES = setOf("entities", "glance")

/** Row-list keys the editor models; everything else on a row is passthrough. */
private val ROW_OWNED_KEYS = setOf("entity", "secondary_info", "show_state", "state_color", "show_last_changed")

/** Special (non-entity) entities-row types preserved verbatim, mirroring the parser. */
private val SPECIAL_ROW_TYPES_EDIT = setOf(
    "section", "divider", "attribute", "button", "buttons",
    "call-service", "perform-action", "conditional", "text", "weblink", "cast",
)

internal data class CardEntityRow(
    val entityId: String,
    val secondaryInfo: String? = null,
    val showState: Boolean? = null,
    val stateColor: Boolean? = null,
    val showLastChanged: Boolean? = null,
    /** Every non-owned key on the row object, kept for lossless round-trip. */
    val passthrough: JsonObject = JsonObject(emptyMap()),
    /** A non-entity special/divider/section row, kept verbatim and non-editable. */
    val special: JsonObject? = null,
)

/** Parse the `entities:` array into the editor's row model. Bare string ids,
 *  entity-row objects (owned sub-keys lifted out, the rest stashed in
 *  passthrough), and special/non-entity rows (kept verbatim in [CardEntityRow.special]). */
internal fun parseEntityRows(base: JsonObject): List<CardEntityRow> {
    val arr = base["entities"] as? JsonArray ?: return emptyList()
    fun bool(o: JsonObject, k: String): Boolean? =
        (o[k] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
    fun str(o: JsonObject, k: String): String? = (o[k] as? JsonPrimitive)?.content
    return arr.mapNotNull { el ->
        when (el) {
            is JsonPrimitive -> if (el.isString) CardEntityRow(entityId = el.content) else null
            is JsonObject -> {
                val rowType = (el["type"] as? JsonPrimitive)?.content?.lowercase()
                val entity = str(el, "entity")
                if (entity == null || (rowType != null && rowType in SPECIAL_ROW_TYPES_EDIT)) {
                    CardEntityRow(entityId = entity.orEmpty(), special = el)
                } else {
                    CardEntityRow(
                        entityId = entity,
                        secondaryInfo = str(el, "secondary_info"),
                        showState = bool(el, "show_state"),
                        stateColor = bool(el, "state_color"),
                        showLastChanged = bool(el, "show_last_changed"),
                        passthrough = JsonObject(el.filterKeys { it !in ROW_OWNED_KEYS }),
                    )
                }
            }
            else -> null
        }
    }
}

/** Re-emit the `entities:` array from the row model. Bare rows stay bare
 *  strings; rows with owned sub-keys or passthrough become objects (passthrough
 *  first, owned values last so edits win); special rows pass through verbatim. */
internal fun buildEntitiesArray(form: CardEditorForm): JsonArray {
    val out = form.rows.filter { it.special != null || it.entityId.isNotBlank() }.map { row ->
        row.special?.let { return@map it }
        val owned = buildJsonObject {
            row.secondaryInfo?.takeIf { it.isNotBlank() }?.let { put("secondary_info", JsonPrimitive(it)) }
            row.showState?.let { put("show_state", JsonPrimitive(it)) }
            row.stateColor?.let { put("state_color", JsonPrimitive(it)) }
            row.showLastChanged?.let { put("show_last_changed", JsonPrimitive(it)) }
        }
        if (owned.isEmpty() && row.passthrough.isEmpty()) {
            JsonPrimitive(row.entityId)
        } else {
            buildJsonObject {
                put("entity", JsonPrimitive(row.entityId))
                row.passthrough.forEach { (k, v) -> put(k, v) }
                owned.forEach { (k, v) -> put(k, v) }
            }
        }
    }
    return JsonArray(out)
}

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
    val rows: List<CardEntityRow> = emptyList(),
    val name: String = "",
    val icon: String = "",
    /** Real config-key -> value (HIDE-sense already resolved). Driven by [cardTogglesFor]. */
    val toggles: Map<String, Boolean> = emptyMap(),
    /** Real config-key -> raw value for the generic field schema ([cardFieldsFor]):
     *  text/number(as text)/enum/colour/icon as string primitives, bools as
     *  boolean primitives, actions as objects. Emitted by [emitCardField]. */
    val values: Map<String, JsonElement> = emptyMap(),
)

/** True when [type]'s primary label is edited via the engine `name` field (so the
 *  hand-rendered TITLE field is suppressed and `title` is not owned here). */
internal fun typeOwnsNameField(type: String): Boolean =
    cardFieldsFor(type).any { it.key == "name" }

/** Types with NO label key at all (neither `title` nor `name`): the editor shows
 *  no primary label field and never owns `title` for them, so a stray one passes
 *  through. */
internal val NO_TITLE_TYPES = setOf("picture")

/** True when the editor should render and own a `title` field for [type]. */
internal fun typeUsesTitle(type: String): Boolean =
    !typeOwnsNameField(type) && type !in NO_TITLE_TYPES &&
        type != "button" && type != "heading"

/** The config keys the form owns (re-emits) for this card type. */
private fun editedKeysFor(type: String): Set<String> = buildSet {
    when (type) {
        // Headings label via `heading:`; buttons via `name:` (the button card
        // has no `title:` key, so a stray one passes through untouched).
        "heading" -> add("heading")
        "button" -> Unit
        // Name-primary cards (tile, light, gauge…) label via the engine `name`
        // field; label-less cards (picture) have no label key at all. Either way
        // leave a stray `title` to pass through untouched.
        else -> if (typeUsesTitle(type)) add("title")
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
    cardFieldsFor(type).forEach { add(it.key) }
}

/**
 * Re-build a card config from its original [base] plus the edited [form]:
 * unowned keys (tap_action and friends) copy through verbatim, owned keys are
 * re-emitted from the form (blank string = key removed, matching how the
 * form clears a field).
 *
 * Show/hide toggles ([cardTogglesFor]) emit when they deviate from the key's
 * default OR the key was already present (so flipping a stored `show_state: false`
 * to true and back keeps the explicit key instead of silently dropping it).
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
            else -> if (typeUsesTitle(type)) putIfSet("title", form.title)
        }
        if (type in SINGLE_ENTITY_TYPES) putIfSet("entity", form.entity)
        if (type == "iframe") {
            putIfSet("url", form.url)
            putIfSet("aspect_ratio", form.aspect)
        }
        if (type == "markdown") putIfSet("content", form.content)
        if (type in MULTI_ENTITY_TYPES) put("entities", buildEntitiesArray(form))
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
        // Generic schema fields (name, colour, min/max, actions…). A field key
        // the form actually LOADED (present in `values`, even as a cleared blank)
        // is re-emitted by its kind rule; a key the form never modelled passes
        // through verbatim from base, so an editor instance that didn't seed a
        // field (or a programmatic form) never silently drops a stored
        // tap_action / hold_action / colour.
        for (f in cardFieldsFor(type)) {
            if (form.values.containsKey(f.key)) {
                emitCardField(this, base, f, form.values[f.key])
            } else {
                base[f.key]?.let { put(f.key, it) }
            }
        }
    }
}

/** Boolean config read with an HA-style default for an absent key. */
internal fun JsonObject?.boolOr(key: String, default: Boolean): Boolean =
    (this?.get(key) as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: default
