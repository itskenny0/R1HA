package com.github.itskenny0.r1ha.ui.components

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlin.math.sin

// Pure geometry + colour math for the HS colour wheel and the colour-temperature
// slider (see ColorWheel.kt for the composables). Kept Compose-free so the mapping
// between touch positions and hue/saturation values is unit-testable on the JVM:
// gesture-feel bugs in a colour picker are invisible in code review, so the
// position<->value contract is the one part we can actually pin down with tests.
//
// Convention: hue 0 (red) sits at 3 o'clock and increases CLOCKWISE in screen
// space (y grows downward, so atan2(dy, dx) is already clockwise). This matches
// Compose's Brush.sweepGradient, which also starts at 3 o'clock and sweeps
// clockwise; the gradient and the math agree by construction, and the layout
// mirrors HA's own hue disc with red on the 0-degree axis.

/**
 * Map a touch position to a (hue 0..360, saturation 0..1) pair on a wheel centred
 * at ([cx], [cy]) with the given [radius]. Positions outside the disc are clamped
 * to the rim (saturation 1, hue from the angle) so a drag that wanders off the
 * wheel keeps tracking the angle instead of going dead. Returns null only for a
 * degenerate (non-positive) radius, i.e. before layout has measured.
 */
internal fun wheelHsAt(x: Float, y: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float>? {
    if (radius <= 0f) return null
    val dx = x - cx
    val dy = y - cy
    val hue = ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
    val sat = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
    return hue to sat
}

/**
 * Inverse of [wheelHsAt]: the pixel position of the thumb for a given
 * (hue 0..360, sat 0..1) on the same wheel. Saturation is clamped into 0..1 so a
 * stale or out-of-range entity echo can't paint the thumb outside the disc.
 */
internal fun wheelOffsetFor(hue: Float, sat: Float, cx: Float, cy: Float, radius: Float): Pair<Float, Float> {
    val rad = Math.toRadians(hue.toDouble())
    val r = sat.coerceIn(0f, 1f) * radius
    return (cx + (cos(rad) * r).toFloat()) to (cy + (sin(rad) * r).toFloat())
}

/**
 * Kelvin value at a 0..1 slider [fraction] across the [minKelvin]..[maxKelvin]
 * range. Fraction 0 = warmest (left end of the bar), 1 = coolest, matching the
 * warm-to-cool gradient the slider draws.
 */
internal fun kelvinFromFraction(fraction: Float, minKelvin: Int, maxKelvin: Int): Int {
    val f = fraction.coerceIn(0f, 1f)
    return (minKelvin + f * (maxKelvin - minKelvin)).roundToInt().coerceIn(minKelvin, maxKelvin)
}

/** Inverse of [kelvinFromFraction]; clamps out-of-range kelvin onto the bar. */
internal fun fractionFromKelvin(kelvin: Int, minKelvin: Int, maxKelvin: Int): Float {
    if (maxKelvin <= minKelvin) return 0f
    return ((kelvin - minKelvin).toFloat() / (maxKelvin - minKelvin)).coerceIn(0f, 1f)
}

/**
 * Approximate sRGB colour of a black-body radiator at [kelvin], packed as opaque
 * ARGB. Tanner Helland's curve fit; the same family of approximation HA's
 * frontend uses for its CT slider gradient; accurate enough for a UI gradient
 * (2000K reads amber, 4000K warm white, 6500K cool white). Clamped to the
 * 1000..40000 K domain the fit is valid over.
 */
internal fun kelvinToArgb(kelvin: Int): Int {
    val t = kelvin.coerceIn(1_000, 40_000) / 100.0
    val r = if (t <= 66.0) 255.0 else 329.698727446 * Math.pow(t - 60.0, -0.1332047592)
    val g = if (t <= 66.0) {
        99.4708025861 * ln(t) - 161.1195681661
    } else {
        288.1221695283 * Math.pow(t - 60.0, -0.0755148492)
    }
    val b = when {
        t >= 66.0 -> 255.0
        t <= 19.0 -> 0.0
        else -> 138.5177312231 * ln(t - 10.0) - 305.0447927307
    }
    val ri = r.roundToInt().coerceIn(0, 255)
    val gi = g.roundToInt().coerceIn(0, 255)
    val bi = b.roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
}

/**
 * Parse HA's `hs_color` attribute ([hue 0..360, saturation 0..100]) into a
 * (hue 0..360, saturation 0..1) pair, normalised to the wheel's sat scale.
 * Null when the attribute is absent, malformed, or the bulb isn't currently in
 * a colour mode; callers fall back to a centred (white) thumb in that case.
 */
internal fun hsFromAttributes(attrs: kotlinx.serialization.json.JsonObject?): Pair<Float, Float>? {
    val arr = attrs?.get("hs_color") as? kotlinx.serialization.json.JsonArray ?: return null
    if (arr.size < 2) return null
    val h = (arr[0] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: return null
    val s = (arr[1] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toFloatOrNull() ?: return null
    return (((h % 360f) + 360f) % 360f) to (s / 100f).coerceIn(0f, 1f)
}
