package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * A single unicode glyph standing in for an entity's domain (and, for a
 * couple of domains, its current state). This is R1HA's in-house, font-free
 * answer to HA's MDI icons: the same idiom the weather screen uses for
 * conditions (☀ ☁ ☂), extended across the domains a dashboard surfaces.
 *
 * Deliberately monochrome line/symbol glyphs (not colour emoji) so they tint
 * with the card's accent colour and read consistently against the dark
 * Mission Control surface. A domain we don't have a glyph for falls back to a
 * neutral dot, which is what the cards rendered before this helper existed.
 *
 * Pure (no Compose) so it stays trivially testable and reusable across the
 * tile / glance / entity renderers.
 */
internal fun domainGlyph(entityId: String, state: EntityState?): String {
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    val raw = state?.rawState?.lowercase().orEmpty()
    return when (domain) {
        "light" -> if (state?.isOn == true) "☀" else "○"
        "switch", "input_boolean", "automation", "script", "siren" ->
            if (state?.isOn == true) "▮" else "▯"
        "fan" -> "✣"
        "lock" -> if (raw == "locked") "▣" else "▢"
        "cover", "garage" -> when {
            raw == "closed" -> "▭"
            raw == "open" -> "▢"
            else -> "▤"
        }
        "binary_sensor" -> if (state?.isOn == true) "●" else "○"
        "sensor" -> "≈"
        "climate", "thermostat" -> "❈"
        "humidifier" -> "≀"
        "media_player" -> "♪"
        "camera" -> "▷"
        "person", "device_tracker" -> if (raw == "home") "⌂" else "↪"
        "sun" -> if (raw.startsWith("above")) "☀" else "☾"
        "weather" -> "☁"
        "alarm_control_panel" -> if (raw.startsWith("armed")) "▣" else "▢"
        "vacuum" -> "◓"
        "lawn_mower", "mower" -> "▤"
        "valve", "water_heater" -> "◍"
        "scene" -> "✦"
        "button", "input_button" -> "◉"
        "select", "input_select" -> "▾"
        "number", "input_number" -> "#"
        "counter" -> "#"
        "timer" -> "◷"
        "calendar", "schedule" -> "▦"
        "update" -> "↑"
        "remote" -> "⎚"
        "zone" -> "⌖"
        else -> "·"
    }
}
