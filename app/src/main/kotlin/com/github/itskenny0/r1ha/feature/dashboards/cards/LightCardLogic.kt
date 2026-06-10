package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Pure decision logic for the light card: brightness-control gating, the next
 * brightness-percent value for a stepper nudge, and the icon tint from the
 * bulb's reported colour. No Compose / Android beyond the [Color] value type,
 * so each is unit-tested directly.
 */

/**
 * The brightness step nudge size in percent. HA's circular slider is continuous;
 * the R1 card uses discrete steps following the app's value-bar idiom (a 10%
 * nudge reads clearly on the small screen and reaches both ends in a handful of
 * taps).
 */
internal const val LIGHT_BRIGHTNESS_STEP = 10

/**
 * Whether the light supports a brightness control. HA gates its brightness
 * slider on the light reporting a brightness-capable colour mode: any mode other
 * than the pure on/off mode ("onoff") implies a settable brightness. A light
 * advertising only ["onoff"] (or nothing) gets a plain toggle, no brightness
 * bar.
 */
internal fun lightSupportsBrightness(state: EntityState?): Boolean {
    val modes = state?.supportedColorModes ?: return false
    if (modes.isEmpty()) return false
    return modes.any { !it.equals("onoff", ignoreCase = true) }
}

/**
 * The brightness percent a stepper nudge should send. Clamps into 1..100 (HA's
 * brightness_pct floor is 1; 0 would turn the light off, which is the toggle's
 * job, not the brightness control's). [current] is the present brightness
 * percent (0 when off); [up] selects the direction.
 */
internal fun nextBrightnessPct(current: Int, up: Boolean): Int {
    val base = current.coerceIn(0, 100)
    val next = if (up) base + LIGHT_BRIGHTNESS_STEP else base - LIGHT_BRIGHTNESS_STEP
    return next.coerceIn(1, 100)
}

/**
 * The icon/accent tint for the bulb. When the light is on and reports an
 * `rgb_color`, the icon takes that colour (HA tints the icon with the bulb's
 * live colour). Otherwise [onAccent] is used when on and [offAccent] when off.
 * Pure; the renderer supplies the fallbacks from the theme.
 */
internal fun lightIconTint(state: EntityState?, onAccent: Color, offAccent: Color): Color {
    val on = state?.isOn == true
    if (!on) return offAccent
    return rgbColorOf(state) ?: onAccent
}

/**
 * Parse a light's `rgb_color` attribute ([r, g, b], each 0..255) into a Compose
 * colour, or null when the bulb isn't reporting one. Reused for both the icon
 * tint and the brightness orb glow.
 */
internal fun rgbColorOf(state: EntityState?): Color? {
    val arr = state?.attributesJson?.get("rgb_color") as? JsonArray ?: return null
    if (arr.size < 3) return null
    val r = (arr[0] as? JsonPrimitive)?.intOrNull ?: return null
    val g = (arr[1] as? JsonPrimitive)?.intOrNull ?: return null
    val b = (arr[2] as? JsonPrimitive)?.intOrNull ?: return null
    return Color(
        red = (r.coerceIn(0, 255)) / 255f,
        green = (g.coerceIn(0, 255)) / 255f,
        blue = (b.coerceIn(0, 255)) / 255f,
        alpha = 1f,
    )
}

/**
 * Whether the light entity is in an unavailable / unknown state. Used to render
 * the card greyed out with no actionable control rather than a misleading "off".
 */
internal fun lightIsUnavailable(state: EntityState?): Boolean {
    val raw = state?.rawState?.trim()?.lowercase() ?: return true
    return raw == "unavailable" || raw == "unknown" || raw.isEmpty()
}
