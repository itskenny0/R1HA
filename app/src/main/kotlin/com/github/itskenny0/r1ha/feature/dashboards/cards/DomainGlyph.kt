package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Resolve the in-house [ImageVector] for a dashboard row/card. Prefers an
 * explicit `icon: mdi:foo` config slug ([configIcon]) when it maps to a curated
 * glyph, otherwise derives one from the entity id + live device-class / state.
 * This is the icon-set replacement for the old text [domainGlyph]; cards keep
 * the same accent/tint they already computed.
 */
internal fun cardEntityIcon(
    entityId: String,
    state: EntityState?,
    configIcon: String? = null,
): ImageVector =
    R1Icons.forMdi(configIcon)
        ?: R1Icons.forEntity(entityId, deviceClass = state?.deviceClass, state = state?.rawState)

/**
 * The round accent-tinted icon disc shared by the tile / button / entity / glance
 * renderers. Centres a fixed-size [Icon] in a circle filled + outlined with the
 * card's [accent]. [showBorder] is dropped for the lighter glance/entity discs.
 */
@Composable
internal fun CardIconDisc(
    icon: ImageVector,
    accent: Color,
    discSize: Dp,
    iconSize: Dp = 22.dp,
    showBorder: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(discSize)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .then(
                if (showBorder) {
                    Modifier.border(1.dp, accent.copy(alpha = 0.4f), CircleShape)
                } else {
                    Modifier
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(iconSize),
        )
    }
}

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
