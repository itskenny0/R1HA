package com.github.itskenny0.r1ha.feature.cardstack

/**
 * Single source of truth for the native Home Assistant show/hide config keys
 * the EDIT CARD modal exposes per card type. Every key here is one the app's
 * Lovelace parser actually reads and renders; defaults mirror the parser's
 * render defaults so an omitting config shows the toggles the way the card
 * actually draws. [buildStructuredCard] emits these generically and the editor
 * UI renders one "SHOW" chip section from the same list.
 */

internal enum class ToggleSense {
    /** Chip "on" == key true (show_name, show_state, ...). */
    SHOW,

    /** Chip "on" == key false (hide_state: the chip reads in shown terms). */
    HIDE,
}

internal data class CardToggle(
    val key: String,
    val label: String,
    val default: Boolean,
    val sense: ToggleSense = ToggleSense.SHOW,
)

/** Card-level show/hide toggles for [type]. Empty == no SHOW section. */
internal fun cardTogglesFor(type: String): List<CardToggle> = when (type) {
    "button" -> listOf(
        CardToggle("show_name", "NAME", default = true),
        CardToggle("show_icon", "ICON", default = true),
        CardToggle("show_state", "STATE", default = false),
        CardToggle("state_color", "COLOUR", default = true),
    )
    "glance" -> listOf(
        CardToggle("show_name", "NAME", default = true),
        CardToggle("show_icon", "ICON", default = true),
        CardToggle("show_state", "STATE", default = true),
        CardToggle("state_color", "COLOUR", default = true),
    )
    "entities" -> listOf(
        // Parser default is null/auto; treat "on" as the unwritten default so an
        // untouched card stays auto (not emitted) yet the user can switch it off.
        CardToggle("show_header_toggle", "HEADER TOGGLE", default = true),
    )
    "tile" -> listOf(
        CardToggle("hide_state", "STATE", default = false, sense = ToggleSense.HIDE),
        CardToggle("show_entity_picture", "PICTURE", default = false),
        CardToggle("state_color", "COLOUR", default = true),
    )
    "weather-forecast" -> listOf(
        CardToggle("show_current", "CURRENT", default = true),
        CardToggle("show_forecast", "FORECAST", default = true),
    )
    "picture-entity" -> listOf(
        CardToggle("show_name", "NAME", default = true),
        CardToggle("show_state", "STATE", default = true),
        CardToggle("show_entity_picture", "PICTURE", default = false),
    )
    "thermostat", "humidifier" -> listOf(
        CardToggle("show_current_temperature", "CURRENT", default = true),
        CardToggle("show_current_as_primary", "CURRENT BIG", default = false),
    )
    "gauge" -> listOf(
        CardToggle("needle", "NEEDLE", default = false),
    )
    "sensor" -> listOf(
        CardToggle("state_color", "COLOUR", default = false),
    )
    "history-graph" -> listOf(
        CardToggle("show_names", "NAMES", default = true),
    )
    else -> emptyList()
}

/** Native per-row sub-toggles for the dynamic rows of [type]. */
internal fun rowTogglesFor(type: String): List<CardToggle> = when (type) {
    "entities" -> listOf(
        CardToggle("show_state", "STATE", default = true),
        CardToggle("state_color", "COLOUR", default = false),
        CardToggle("show_last_changed", "LAST CHANGED", default = false),
    )
    "glance" -> listOf(
        CardToggle("show_state", "STATE", default = true),
        CardToggle("state_color", "COLOUR", default = false),
        CardToggle("show_last_changed", "LAST CHANGED", default = false),
    )
    else -> emptyList()
}

/** Map a raw config-key boolean to the chip's "shown" sense. */
internal fun toggleChipShown(raw: Boolean, sense: ToggleSense): Boolean =
    if (sense == ToggleSense.HIDE) !raw else raw

/** Map a chip's "shown" state back to the config-key boolean. */
internal fun toggleStoredValue(chipShown: Boolean, sense: ToggleSense): Boolean =
    if (sense == ToggleSense.HIDE) !chipShown else chipShown

/** Cycle a tri-state row override: inherit (null) -> on -> off -> inherit. */
internal fun triStateNext(current: Boolean?): Boolean? = when (current) {
    null -> true
    true -> false
    false -> null
}
