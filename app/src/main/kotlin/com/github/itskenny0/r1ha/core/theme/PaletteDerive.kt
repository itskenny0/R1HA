package com.github.itskenny0.r1ha.core.theme

/**
 * Pure colour math for deriving a Colourful-Cards gradient from a single
 * user-chosen colour (the per-card accent override). The hand-tuned palettes
 * run bright → base → deep along the card diagonal with the deepest stop dark
 * enough that white text stays readable; this reproduces that shape from any
 * base. Operates on packed ARGB ints so it unit-tests on a plain JVM.
 */

/** Deepest-stop luminance ceiling. The hand-tuned anchors sit between ~0.02
 *  (navy) and ~0.13 (the warm magenta); deriving to the top of that band keeps
 *  white text at roughly the 6:1 contrast the theme aims for. */
internal const val ANCHOR_LUMINANCE_CEILING = 0.13f

/** WCAG-style relative luminance of an opaque ARGB colour (gamma-linearised). */
internal fun relativeLuminance(argb: Int): Float {
    fun lin(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    val r = lin((argb shr 16) and 0xFF)
    val g = lin((argb shr 8) and 0xFF)
    val b = lin(argb and 0xFF)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** Blend an opaque ARGB colour toward white by [fraction]. */
internal fun lightenArgb(argb: Int, fraction: Float): Int = blendArgb(argb, 0xFFFFFFFF.toInt(), fraction)

/** Blend an opaque ARGB colour toward black by [fraction]. */
internal fun darkenArgb(argb: Int, fraction: Float): Int = blendArgb(argb, 0xFF000000.toInt(), fraction)

private fun blendArgb(from: Int, to: Int, fraction: Float): Int {
    val f = fraction.coerceIn(0f, 1f)
    fun ch(shift: Int): Int {
        val a = (from shr shift) and 0xFF
        val b = (to shr shift) and 0xFF
        return (a + ((b - a) * f)).toInt().coerceIn(0, 255)
    }
    return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
}

/**
 * Three gradient stops derived from [base]: a lifted bright stop, the base
 * itself, and a deep anchor darkened (uniform channel scale, so the hue
 * direction survives) until white text reads against it. The darken loop is
 * bounded: even pure white reaches the ceiling well inside the cap, and a
 * base already below it anchors on the first step.
 */
internal fun overrideGradientArgb(base: Int): IntArray {
    val bright = lightenArgb(base, 0.35f)
    var anchor = darkenArgb(base, 0.25f)
    var guard = 0
    while (relativeLuminance(anchor) > ANCHOR_LUMINANCE_CEILING && guard < 16) {
        anchor = darkenArgb(anchor, 0.25f)
        guard++
    }
    return intArrayOf(bright, base, anchor)
}
