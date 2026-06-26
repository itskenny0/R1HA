package com.github.itskenny0.r1ha.feature.cardstack

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Declarative field schema for the structured card editor ([CardMiniEditor]),
 * the generalisation of the boolean-only [CardToggle] table to the full set of
 * scalar / enum / entity / action options a card type accepts.
 *
 * Together [cardFieldsFor] (here) and [cardTogglesFor] ([CardToggleSpec]) are the
 * single source of truth for what the editor exposes per type:
 *  - [cardTogglesFor] owns the SHOW/HIDE visibility chips (show_name, hide_state…);
 *  - [cardFieldsFor] owns everything else (name, colour, min/max, actions…).
 * The two key sets, plus the editor's hand-rendered primary fields (title /
 * entity / rows / url / content), are disjoint per type so no key is emitted
 * twice (asserted by CardFieldSpecTest). Anything modelled by neither passes
 * through verbatim via [buildStructuredCard].
 *
 * Every key here is one the app's Lovelace parser ([LovelaceParser]) actually
 * reads, so the visual control maps to real rendered behaviour rather than a
 * dead YAML key.
 */

/** A visual grouping header the editor renders the fields under, in this order. */
internal object FieldSection {
    const val BASICS = "BASICS"
    const val APPEARANCE = "APPEARANCE"
    const val ACTIONS = "ACTIONS"
    const val ADVANCED = "ADVANCED"
}

/** One editable, non-boolean-visibility option of a card type. */
internal sealed interface CardField {
    /** Real config key (e.g. "color", "min", "tap_action"). */
    val key: String

    /** Short all-caps control label. */
    val label: String

    /** Grouping header ([FieldSection]). */
    val section: String
}

/** Free text. [monospace] for ids / CSS lengths; [placeholder] hints the format. */
internal data class TextFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.BASICS,
    val monospace: Boolean = false,
    val placeholder: String? = null,
) : CardField

/** A numeric value. Emitted as a real JSON number. [integer] rejects decimals. */
internal data class NumberFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.APPEARANCE,
    val integer: Boolean = false,
    val placeholder: String? = null,
) : CardField

/** One choice from a fixed list, rendered as segmented chips. The empty-value
 *  option (when [allowUnset]) clears the key back to the card's own default. */
internal data class EnumFieldSpec(
    override val key: String,
    override val label: String,
    val options: List<EnumOption>,
    override val section: String = FieldSection.APPEARANCE,
    /** The key's value when absent; used to keep configs clean (a choice equal to
     *  [default] that was not already present is not emitted). Null = no default. */
    val default: String? = null,
    val allowUnset: Boolean = true,
) : CardField

internal data class EnumOption(val value: String, val label: String)

/** A comma/space-separated list of strings, emitted as a real JSON array (the
 *  shape the parser reads for `state_content`, alarm `states`, mode lists…). The
 *  stored value is the raw editing TEXT; it is split to an array only on emit, so
 *  typing a separator never fights a re-joined display. */
internal data class ListFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.APPEARANCE,
    val placeholder: String? = null,
) : CardField

/** A non-visibility boolean (vertical layout, logarithmic scale…). Distinct from
 *  the SHOW/HIDE chips in [cardTogglesFor], which are visibility-specific. */
internal data class BoolFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.APPEARANCE,
    val default: Boolean = false,
) : CardField

/** A single entity id, rendered with the entity picker. [domains] filters the
 *  picker when non-empty (e.g. only `light.` entities). */
internal data class EntityFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.BASICS,
    val domains: List<String> = emptyList(),
) : CardField

/** An MDI icon name (`mdi:lightbulb`). Rendered as a monospace text field with a
 *  glyph preview; a full visual picker is a later bespoke pass. */
internal data class IconFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.BASICS,
) : CardField

/** A colour: an HA named theme colour or `#rrggbb`. Rendered with named-colour
 *  swatch quick-picks plus a hex/name text field. */
internal data class ColorFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.APPEARANCE,
) : CardField

/** A nested HA action object (tap_action / hold_action / …). Rendered by the
 *  bespoke [CardActionEditor]; value is the action [JsonObject] or absent. */
internal data class ActionFieldSpec(
    override val key: String,
    override val label: String,
    override val section: String = FieldSection.ACTIONS,
) : CardField

/** A complex value (HA card-features array, gauge severity object, gauge segments
 *  array) edited by a dedicated bespoke sub-sheet. The stored value IS the JSON to
 *  emit verbatim; the [kind] picks which editor opens. */
internal data class BespokeFieldSpec(
    override val key: String,
    override val label: String,
    val kind: BespokeKind,
    override val section: String = FieldSection.APPEARANCE,
) : CardField

internal enum class BespokeKind { FEATURES, SEVERITY, SEGMENTS }

/** HA's named theme colours offered as swatches on a [ColorFieldSpec]. The hex
 *  values mirror HA's `--*-color` CSS variables closely enough for a preview. */
internal val HA_NAMED_COLORS: List<Pair<String, Long>> = listOf(
    "primary" to 0xFF03A9F4, "accent" to 0xFFFF9800, "red" to 0xFFF44336,
    "pink" to 0xFFE91E63, "purple" to 0xFF926BC7, "deep-purple" to 0xFF6E41AB,
    "indigo" to 0xFF3F51B5, "blue" to 0xFF2196F3, "light-blue" to 0xFF03A9F4,
    "cyan" to 0xFF00BCD4, "teal" to 0xFF009688, "green" to 0xFF4CAF50,
    "light-green" to 0xFF8BC34A, "lime" to 0xFFCDDC39, "yellow" to 0xFFFFEB3B,
    "amber" to 0xFFFFC107, "orange" to 0xFFFF9800, "deep-orange" to 0xFFFF5722,
    "brown" to 0xFF795548, "grey" to 0xFF9E9E9E, "blue-grey" to 0xFF607D8B,
    "black" to 0xFF000000, "white" to 0xFFFFFFFF, "disabled" to 0xFFBDBDBD,
)

/**
 * The non-visibility option fields for [type]. Empty == no extra fields section.
 *
 * Disjoint from [cardTogglesFor] and from the editor's hand-rendered primary
 * fields (title, entity, button name/icon, iframe url/aspect, markdown content,
 * entity rows) so each key has exactly one emit path.
 */
internal fun cardFieldsFor(type: String): List<CardField> = when (type) {
    "button" -> listOf(
        // name + icon are hand-rendered primaries for the button card; only the
        // extra options live here (keep this list disjoint from those keys).
        ColorFieldSpec("color", "COLOUR"),
        TextFieldSpec("icon_height", "ICON HEIGHT", FieldSection.APPEARANCE, monospace = true, placeholder = "48px / 2.5em"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "tile" -> listOf(
        TextFieldSpec("name", "NAME"),
        IconFieldSpec("icon", "ICON"),
        ColorFieldSpec("color", "COLOUR"),
        BoolFieldSpec("vertical", "VERTICAL", FieldSection.APPEARANCE, default = false),
        ListFieldSpec("state_content", "STATE CONTENT", FieldSection.APPEARANCE, placeholder = "state, last_changed…"),
        EnumFieldSpec(
            "features_position", "FEATURES POSITION",
            options = listOf(EnumOption("bottom", "BOTTOM"), EnumOption("inline", "INLINE")),
            default = "bottom",
        ),
        BespokeFieldSpec("features", "FEATURES", BespokeKind.FEATURES),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
        ActionFieldSpec("icon_tap_action", "ICON TAP", FieldSection.ADVANCED),
        ActionFieldSpec("icon_hold_action", "ICON HOLD", FieldSection.ADVANCED),
        ActionFieldSpec("icon_double_tap_action", "ICON DBL TAP", FieldSection.ADVANCED),
    )
    "light" -> listOf(
        TextFieldSpec("name", "NAME"),
        IconFieldSpec("icon", "ICON"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "gauge" -> listOf(
        TextFieldSpec("name", "NAME"),
        TextFieldSpec("unit", "UNIT", FieldSection.APPEARANCE),
        NumberFieldSpec("min", "MIN", FieldSection.APPEARANCE),
        NumberFieldSpec("max", "MAX", FieldSection.APPEARANCE),
        TextFieldSpec("attribute", "ATTRIBUTE", FieldSection.ADVANCED, placeholder = "current_temperature"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        BespokeFieldSpec("severity", "SEVERITY BANDS", BespokeKind.SEVERITY),
        BespokeFieldSpec("segments", "SEGMENTS", BespokeKind.SEGMENTS),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "sensor" -> listOf(
        TextFieldSpec("name", "NAME"),
        IconFieldSpec("icon", "ICON"),
        TextFieldSpec("unit", "UNIT", FieldSection.APPEARANCE),
        EnumFieldSpec(
            "graph", "GRAPH",
            options = listOf(EnumOption("none", "NONE"), EnumOption("line", "LINE")),
            default = "none",
        ),
        NumberFieldSpec("hours_to_show", "HOURS", FieldSection.APPEARANCE, integer = true),
        EnumFieldSpec(
            "detail", "DETAIL",
            options = listOf(EnumOption("1", "1"), EnumOption("2", "2")),
            default = "1",
        ),
        TextFieldSpec("attribute", "ATTRIBUTE", FieldSection.ADVANCED),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "thermostat" -> listOf(
        TextFieldSpec("name", "NAME"),
        BespokeFieldSpec("features", "FEATURES", BespokeKind.FEATURES),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "humidifier" -> listOf(
        TextFieldSpec("name", "NAME"),
        BespokeFieldSpec("features", "FEATURES", BespokeKind.FEATURES),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "weather-forecast" -> listOf(
        TextFieldSpec("name", "NAME"),
        EnumFieldSpec(
            "forecast_type", "FORECAST",
            options = listOf(
                EnumOption("daily", "DAILY"),
                EnumOption("hourly", "HOURLY"),
                EnumOption("twice_daily", "TWICE DAILY"),
            ),
            default = "daily",
        ),
        TextFieldSpec("secondary_info_attribute", "SECONDARY", FieldSection.APPEARANCE, placeholder = "humidity / wind_speed"),
        BoolFieldSpec("round_temperature", "ROUND TEMP", FieldSection.APPEARANCE, default = false),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "entities" -> listOf(
        IconFieldSpec("icon", "ICON"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "glance" -> listOf(
        NumberFieldSpec("columns", "COLUMNS", FieldSection.APPEARANCE, integer = true),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "picture-entity" -> listOf(
        TextFieldSpec("name", "NAME"),
        TextFieldSpec("image", "IMAGE URL", FieldSection.APPEARANCE, monospace = true),
        EntityFieldSpec("camera_image", "CAMERA ENTITY", FieldSection.APPEARANCE, domains = listOf("camera")),
        TextFieldSpec("aspect_ratio", "ASPECT", FieldSection.APPEARANCE, placeholder = "16:9 / 50%"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "media-control" -> listOf(
        TextFieldSpec("name", "NAME"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "alarm-panel" -> listOf(
        TextFieldSpec("name", "NAME"),
        ListFieldSpec("states", "ARM STATES", FieldSection.APPEARANCE, placeholder = "arm_home, arm_away…"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "statistic" -> listOf(
        TextFieldSpec("name", "NAME"),
        IconFieldSpec("icon", "ICON"),
        EnumFieldSpec(
            "stat_type", "STAT",
            options = listOf(
                EnumOption("mean", "MEAN"), EnumOption("min", "MIN"), EnumOption("max", "MAX"),
                EnumOption("change", "CHANGE"), EnumOption("sum", "SUM"), EnumOption("state", "STATE"),
            ),
        ),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "clock" -> listOf(
        EnumFieldSpec(
            "clock_style", "STYLE",
            options = listOf(EnumOption("digital", "DIGITAL"), EnumOption("analog", "ANALOG")),
            default = "digital",
        ),
        EnumFieldSpec(
            "clock_size", "SIZE",
            options = listOf(EnumOption("small", "SMALL"), EnumOption("medium", "MEDIUM"), EnumOption("large", "LARGE")),
        ),
        BoolFieldSpec("show_seconds", "SECONDS", FieldSection.APPEARANCE, default = false),
        BoolFieldSpec("no_background", "NO BACKGROUND", FieldSection.APPEARANCE, default = false),
        TextFieldSpec("time_format", "TIME FORMAT", FieldSection.ADVANCED, placeholder = "24 / 12"),
        TextFieldSpec("time_zone", "TIME ZONE", FieldSection.ADVANCED, placeholder = "Europe/Berlin"),
    )
    "map" -> listOf(
        NumberFieldSpec("hours_to_show", "HOURS", FieldSection.APPEARANCE, integer = true),
        EnumFieldSpec(
            "label_mode", "LABELS",
            options = listOf(EnumOption("name", "NAME"), EnumOption("state", "STATE"), EnumOption("attribute", "ATTRIBUTE")),
        ),
        TextFieldSpec("attribute", "ATTRIBUTE", FieldSection.ADVANCED),
        BoolFieldSpec("show_all", "SHOW ALL", FieldSection.APPEARANCE, default = false),
        BoolFieldSpec("fit_zones", "FIT ZONES", FieldSection.APPEARANCE, default = false),
        BoolFieldSpec("cluster", "CLUSTER", FieldSection.APPEARANCE, default = true),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "logbook" -> listOf(
        NumberFieldSpec("hours_to_show", "HOURS", FieldSection.APPEARANCE, integer = true),
        ListFieldSpec("state_filter", "STATE FILTER", FieldSection.APPEARANCE, placeholder = "on, off…"),
        TextFieldSpec("theme", "THEME", FieldSection.ADVANCED),
    )
    "calendar" -> listOf(
        EnumFieldSpec(
            "initial_view", "VIEW",
            options = listOf(
                EnumOption("dayGridMonth", "MONTH"),
                EnumOption("dayGridDay", "DAY"),
                EnumOption("listWeek", "LIST"),
            ),
            default = "dayGridMonth",
        ),
        BoolFieldSpec("vertical", "VERTICAL", FieldSection.APPEARANCE, default = false),
    )
    "shortcut" -> listOf(
        TextFieldSpec("label", "LABEL"),
        TextFieldSpec("name", "NAME (LEGACY)"),
        IconFieldSpec("icon", "ICON"),
        ColorFieldSpec("color", "COLOUR"),
        TextFieldSpec("description", "DESCRIPTION", FieldSection.APPEARANCE),
        BoolFieldSpec("vertical", "VERTICAL", FieldSection.APPEARANCE, default = false),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "area" -> listOf(
        TextFieldSpec("area", "AREA ID", FieldSection.BASICS, monospace = true, placeholder = "living_room"),
        TextFieldSpec("name", "NAME"),
        TextFieldSpec("image", "IMAGE URL", FieldSection.APPEARANCE, monospace = true),
        TextFieldSpec("navigation_path", "NAVIGATE TO", FieldSection.APPEARANCE, monospace = true),
        EnumFieldSpec(
            "display_type", "DISPLAY",
            options = listOf(EnumOption("compact", "COMPACT"), EnumOption("icon", "ICON"), EnumOption("camera", "CAMERA")),
        ),
        ListFieldSpec("sensor_classes", "SENSOR CLASSES", FieldSection.ADVANCED, placeholder = "temperature, humidity…"),
        ListFieldSpec("alert_classes", "ALERT CLASSES", FieldSection.ADVANCED, placeholder = "motion, door…"),
        BoolFieldSpec("show_camera", "SHOW CAMERA", FieldSection.APPEARANCE, default = false),
    )
    "statistics-graph" -> listOf(
        ListFieldSpec("stat_types", "STAT TYPES", FieldSection.APPEARANCE, placeholder = "mean, min, max…"),
        EnumFieldSpec(
            "period", "PERIOD",
            options = listOf(
                EnumOption("5minute", "5 MIN"), EnumOption("hour", "HOUR"),
                EnumOption("day", "DAY"), EnumOption("week", "WEEK"), EnumOption("month", "MONTH"),
            ),
            default = "hour",
        ),
        EnumFieldSpec(
            "chart_type", "CHART",
            options = listOf(EnumOption("line", "LINE"), EnumOption("bar", "BAR")),
            default = "line",
        ),
        NumberFieldSpec("days_to_show", "DAYS", FieldSection.APPEARANCE, integer = true),
    )
    "picture" -> listOf(
        TextFieldSpec("image", "IMAGE URL", FieldSection.BASICS, monospace = true),
        EntityFieldSpec("image_entity", "IMAGE ENTITY", FieldSection.BASICS, domains = listOf("camera", "image", "person")),
        EntityFieldSpec("camera_image", "CAMERA ENTITY", FieldSection.BASICS, domains = listOf("camera")),
        TextFieldSpec("aspect_ratio", "ASPECT", FieldSection.APPEARANCE, placeholder = "16:9 / 50%"),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "picture-glance" -> listOf(
        TextFieldSpec("image", "IMAGE URL", FieldSection.BASICS, monospace = true),
        EntityFieldSpec("camera_image", "CAMERA ENTITY", FieldSection.BASICS, domains = listOf("camera")),
        TextFieldSpec("aspect_ratio", "ASPECT", FieldSection.APPEARANCE, placeholder = "16:9 / 50%"),
        BoolFieldSpec("show_state", "SHOW STATE", FieldSection.APPEARANCE, default = false),
        ActionFieldSpec("tap_action", "TAP"),
        ActionFieldSpec("hold_action", "HOLD"),
        ActionFieldSpec("double_tap_action", "DOUBLE TAP"),
    )
    "picture-elements" -> listOf(
        TextFieldSpec("image", "IMAGE URL", FieldSection.BASICS, monospace = true),
        EntityFieldSpec("camera_image", "CAMERA ENTITY", FieldSection.BASICS, domains = listOf("camera")),
        EntityFieldSpec("image_entity", "IMAGE ENTITY", FieldSection.BASICS, domains = listOf("camera", "image", "person")),
        TextFieldSpec("aspect_ratio", "ASPECT", FieldSection.APPEARANCE, placeholder = "16:9 / 50%"),
        TextFieldSpec("camera_view", "CAMERA VIEW", FieldSection.ADVANCED, placeholder = "auto / live"),
    )
    "history-graph" -> listOf(
        NumberFieldSpec("hours_to_show", "HOURS", FieldSection.APPEARANCE, integer = true),
        NumberFieldSpec("refresh_interval", "REFRESH S", FieldSection.ADVANCED, integer = true),
        BoolFieldSpec("logarithmic_scale", "LOG SCALE", FieldSection.APPEARANCE, default = false),
        BoolFieldSpec("split_device_classes", "SPLIT CLASSES", FieldSection.APPEARANCE, default = false),
        NumberFieldSpec("min_y_axis", "MIN Y", FieldSection.ADVANCED),
        NumberFieldSpec("max_y_axis", "MAX Y", FieldSection.ADVANCED),
    )
    else -> emptyList()
}

/** Ordered section headers a type's fields fall under, for grouped rendering. */
internal val FIELD_SECTION_ORDER = listOf(
    FieldSection.BASICS, FieldSection.APPEARANCE, FieldSection.ACTIONS, FieldSection.ADVANCED,
)

/**
 * Seed the editor's generic-field value map from a parsed [base] config: for each
 * field of [type], copy the raw config value through when present so the controls
 * show the stored config and round-trip losslessly. Absent keys are simply not
 * in the map (the field renders empty / at its default).
 */
internal fun seedFieldValues(base: JsonObject?, type: String): Map<String, JsonElement> {
    if (base == null) return emptyMap()
    val out = LinkedHashMap<String, JsonElement>()
    for (f in cardFieldsFor(type)) {
        val v = base[f.key] ?: continue
        // A list field edits as text; if the config stored an array, join it to
        // the editable comma form so the control shows it and round-trips.
        out[f.key] = if (f is ListFieldSpec && v is JsonArray) JsonPrimitive(listJoin(v)) else v
    }
    return out
}

/** Join a JSON string array to the editor's comma-separated text. */
internal fun listJoin(arr: JsonArray): String =
    arr.mapNotNull { (it as? JsonPrimitive)?.content }.joinToString(", ")

/** Split the editor's comma/space text into a JSON string array (blank -> empty). */
internal fun listSplit(text: String): JsonArray =
    JsonArray(
        text.split(',', '\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { JsonPrimitive(it) },
    )

/** Read a stored list field value as editable text (array -> joined, string -> as-is). */
internal fun listFieldText(v: JsonElement?): String = when (v) {
    is JsonArray -> listJoin(v)
    is JsonPrimitive -> v.content
    else -> ""
}

/** True when [v] is "unset" for emit purposes: absent, JSON null, or a blank string. */
private fun isUnset(v: JsonElement?): Boolean = when (v) {
    null, JsonNull -> true
    is JsonPrimitive -> v.isString && v.content.isBlank()
    else -> false
}

/**
 * Emit one generic field into the card builder, applying the same keep-it-clean
 * rule the toggles use: a value is written when it is set AND (it deviates from
 * the field's own default OR the key was already present in [base]); an unset
 * value drops the key. Number fields coerce their stored text to a real JSON
 * number; an unparseable number drops the key rather than writing a bad type.
 */
internal fun emitCardField(
    builder: kotlinx.serialization.json.JsonObjectBuilder,
    base: JsonObject,
    field: CardField,
    value: JsonElement?,
) {
    if (isUnset(value)) return
    val v = value ?: return
    val present = base.containsKey(field.key)
    when (field) {
        is NumberFieldSpec -> {
            val text = (value as? JsonPrimitive)?.content ?: return
            val num: JsonElement = if (field.integer) {
                text.toLongOrNull()?.let { JsonPrimitive(it) } ?: return
            } else {
                val d = text.toDoubleOrNull() ?: return
                // Emit a whole value as an int (0, not 0.0) so configs stay clean,
                // matching how HA serialises gauge min/max and the like.
                if (d == d.toLong().toDouble()) JsonPrimitive(d.toLong()) else JsonPrimitive(d)
            }
            builder.put(field.key, num)
        }
        is BoolFieldSpec -> {
            val b = (value as? JsonPrimitive)?.booleanOrNull ?: return
            if (b != field.default || present) builder.put(field.key, JsonPrimitive(b))
        }
        is EnumFieldSpec -> {
            val s = (value as? JsonPrimitive)?.content ?: return
            if (s != field.default || present) builder.put(field.key, JsonPrimitive(s))
        }
        is ActionFieldSpec -> builder.put(field.key, v)
        is BespokeFieldSpec -> builder.put(field.key, v)
        is ListFieldSpec -> {
            val text = (v as? JsonPrimitive)?.content ?: (v as? JsonArray)?.let { listJoin(it) } ?: return
            val arr = listSplit(text)
            if (arr.isNotEmpty()) builder.put(field.key, arr)
        }
        is TextFieldSpec, is EntityFieldSpec, is IconFieldSpec, is ColorFieldSpec -> {
            builder.put(field.key, v)
        }
    }
}

/** Coerce a stored number field value to the text the editor displays. */
internal fun numberFieldText(v: JsonElement?): String = when (v) {
    null, JsonNull -> ""
    is JsonPrimitive -> {
        // Show 5 not 5.0 for whole doubles so the field reads cleanly.
        val d = v.doubleOrNull
        when {
            !v.isString && d != null && d == d.toLong().toDouble() -> d.toLong().toString()
            else -> v.content
        }
    }
    else -> ""
}

/** Read a stored string-ish field value as plain text for a control. */
internal fun stringFieldText(v: JsonElement?): String = when (v) {
    null, JsonNull -> ""
    is JsonPrimitive -> v.content
    else -> ""
}

/** Read a stored bool field value, falling back to [default]. */
internal fun boolFieldValue(v: JsonElement?, default: Boolean): Boolean =
    (v as? JsonPrimitive)?.booleanOrNull ?: default

/** Read a stored action field value as its [JsonObject], or null when unset. */
internal fun actionFieldObject(v: JsonElement?): JsonObject? = v as? JsonObject

/** Read a stored enum value, or [default] when unset. */
internal fun enumFieldValue(v: JsonElement?, default: String?): String? =
    (v as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: default

// ── Bespoke: card-features ──────────────────────────────────────────────────

/**
 * The HA card-feature types the features editor can add, as (type, label). Every
 * type the app's [parseTileFeatures] understands is offered; an entity that does
 * not support a feature simply renders nothing for it (HA's own behaviour), so
 * the catalogue is not domain-filtered here.
 */
internal val FEATURE_CATALOG: List<Pair<String, String>> = listOf(
    "toggle" to "Toggle",
    "light-brightness" to "Light brightness",
    "light-color-temp" to "Light colour temp",
    "cover-open-close" to "Cover open/close",
    "cover-position" to "Cover position",
    "cover-tilt" to "Cover tilt",
    "cover-tilt-position" to "Cover tilt position",
    "fan-speed" to "Fan speed",
    "fan-preset-modes" to "Fan preset modes",
    "fan-oscillate" to "Fan oscillate",
    "fan-direction" to "Fan direction",
    "climate-hvac-modes" to "Climate HVAC modes",
    "climate-preset-modes" to "Climate preset modes",
    "climate-fan-modes" to "Climate fan modes",
    "climate-swing-modes" to "Climate swing modes",
    "target-temperature" to "Target temperature",
    "target-humidity" to "Target humidity",
    "humidifier-modes" to "Humidifier modes",
    "humidifier-toggle" to "Humidifier toggle",
    "alarm-modes" to "Alarm modes",
    "lock-commands" to "Lock commands",
    "lock-open-door" to "Lock open door",
    "valve-open-close" to "Valve open/close",
    "valve-position" to "Valve position",
    "select-options" to "Select options",
    "numeric-input" to "Numeric input",
    "water-heater-operation-modes" to "Water-heater modes",
    "vacuum-commands" to "Vacuum commands",
    "lawn-mower-commands" to "Lawn-mower commands",
    "update-actions" to "Update actions",
    "counter-actions" to "Counter actions",
    "date-set" to "Date set",
    "media-player-playback" to "Media playback",
    "media-player-volume-slider" to "Media volume slider",
)

/** Parse a stored `features` value into its raw object list (lossless). */
internal fun parseFeatureObjects(value: JsonElement?): List<JsonObject> =
    (value as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** A fresh feature object for a newly-added [type]. Only the `type` key is set;
 *  the parser supplies sensible defaults for the rest, and the per-feature raw
 *  editor lets the user add options. */
internal fun newFeatureObject(type: String): JsonObject =
    JsonObject(mapOf("type" to JsonPrimitive(type)))

/** Human label for a feature row: the catalogue name, else the prettified type. */
internal fun featureRowLabel(obj: JsonObject): String {
    val type = (obj["type"] as? JsonPrimitive)?.content.orEmpty()
    return FEATURE_CATALOG.firstOrNull { it.first == type }?.second
        ?: type.replace('-', ' ').replaceFirstChar { it.uppercase() }.ifBlank { "Feature" }
}

/** Emit a features list back to a JSON array, or null when empty (clears the key). */
internal fun buildFeaturesArray(features: List<JsonObject>): JsonArray? =
    if (features.isEmpty()) null else JsonArray(features)

/**
 * For features whose only meaningful option is a list of strings (mode pickers,
 * command rows, select options, media controls), the config key that holds that
 * list. The features editor offers a friendly comma field for it instead of
 * forcing raw JSON. Null for features with no list option (or a richer one that
 * stays on the raw-JSON path).
 */
internal fun featureListKey(type: String): String? = when (type) {
    "climate-hvac-modes" -> "hvac_modes"
    "alarm-modes", "humidifier-modes" -> "modes"
    "climate-preset-modes", "fan-preset-modes" -> "preset_modes"
    "climate-fan-modes" -> "fan_modes"
    "climate-swing-modes" -> "swing_modes"
    "climate-swing-horizontal-modes" -> "swing_horizontal_modes"
    "water-heater-operation-modes" -> "operation_modes"
    "lawn-mower-commands", "vacuum-commands" -> "commands"
    "select-options" -> "options"
    "counter-actions" -> "actions"
    "media-player-playback" -> "controls"
    "media-player-source" -> "sources"
    "media-player-sound-mode" -> "sound_modes"
    else -> null
}

/** Set (or clear, on blank) a feature's list-option key from comma text, keeping
 *  every other key on the feature object intact. */
internal fun setFeatureList(feature: JsonObject, key: String, text: String): JsonObject {
    val m = LinkedHashMap<String, JsonElement>(feature)
    val arr = listSplit(text)
    if (arr.isEmpty()) m.remove(key) else m[key] = arr
    return JsonObject(m)
}

/** Read a feature's list-option key as editable comma text. */
internal fun featureListText(feature: JsonObject, key: String): String =
    (feature[key] as? JsonArray)?.let { listJoin(it) }.orEmpty()

// ── Bespoke: gauge severity + segments ──────────────────────────────────────

/** Build a gauge `severity` object from green/yellow/red threshold text; null
 *  when every field is blank (clears the key). Blank individual fields are
 *  omitted so a partial severity round-trips. */
internal fun buildSeverity(green: String, yellow: String, red: String): JsonObject? {
    val m = LinkedHashMap<String, JsonElement>()
    green.trim().toDoubleOrNull()?.let { m["green"] = numberPrimitive(it) }
    yellow.trim().toDoubleOrNull()?.let { m["yellow"] = numberPrimitive(it) }
    red.trim().toDoubleOrNull()?.let { m["red"] = numberPrimitive(it) }
    return if (m.isEmpty()) null else JsonObject(m)
}

/** A JSON number that prints a whole value as an int (0, not 0.0), like HA does. */
private fun numberPrimitive(d: Double): JsonPrimitive =
    if (d == d.toLong().toDouble()) JsonPrimitive(d.toLong()) else JsonPrimitive(d)

/** One editable segment row: from-threshold text, colour, optional label. */
internal data class SegmentRow(val from: String, val color: String, val label: String = "")

/** Build a gauge `segments` array from rows; rows with a non-numeric `from` or a
 *  blank colour are dropped. Null when nothing valid remains (clears the key). */
internal fun buildSegments(rows: List<SegmentRow>): JsonArray? {
    val out = rows.mapNotNull { row ->
        val from = row.from.trim().toDoubleOrNull() ?: return@mapNotNull null
        if (row.color.isBlank()) return@mapNotNull null
        val m = LinkedHashMap<String, JsonElement>()
        m["from"] = numberPrimitive(from)
        m["color"] = JsonPrimitive(row.color.trim())
        row.label.trim().takeIf { it.isNotEmpty() }?.let { m["label"] = JsonPrimitive(it) }
        JsonObject(m)
    }
    return if (out.isEmpty()) null else JsonArray(out)
}

/** Parse a stored `severity` object to (green, yellow, red) display text. */
internal fun parseSeverityText(value: JsonElement?): Triple<String, String, String> {
    val o = value as? JsonObject ?: return Triple("", "", "")
    fun f(k: String) = (o[k] as? JsonPrimitive)?.let { numberFieldText(it) }.orEmpty()
    return Triple(f("green"), f("yellow"), f("red"))
}

/** Parse a stored `segments` array to editable rows. */
internal fun parseSegmentRows(value: JsonElement?): List<SegmentRow> =
    (value as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        SegmentRow(
            from = (o["from"] as? JsonPrimitive)?.let { numberFieldText(it) }.orEmpty(),
            color = (o["color"] as? JsonPrimitive)?.content.orEmpty(),
            label = (o["label"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }
