package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlin.math.max
import kotlin.math.min

/**
 * Hand-rolled dominant-colour extractor for album art (no new dependency, no
 * androidx.palette). The core works on a list of packed ARGB ints so it is fully
 * unit-testable on the host JVM; the Compose wrapper [dominantAccentFromPixels]
 * adapts an already-sampled pixel array.
 *
 * Approach: coarse colour bucketing. Each opaque, non-extreme pixel is quantised
 * into a small HSV-ish bucket (by quantised hue + a light/dark band); the most
 * populated bucket wins. The bucket's averaged colour is then clamped for
 * legibility (saturation floored, lightness pulled into a mid band) so the tint
 * reads on the R1's panel rather than washing out or going muddy. This mirrors
 * the intent of HA's extractColors without porting node-vibrant.
 */

/** Number of hue buckets the quantiser uses (12 = 30-degree wedges). */
private const val HUE_BUCKETS = 12

/**
 * Extract a legible accent [Color] from a list of packed ARGB pixels, or null
 * when no usable colour is found (all transparent / greyscale / extreme). The
 * caller falls back to its default accent on null.
 */
fun dominantAccentFromPixels(pixels: IntArray): Color? {
    if (pixels.isEmpty()) return null
    // bucket key -> (count, sumR, sumG, sumB)
    val counts = HashMap<Int, IntArray>(64)
    for (argb in pixels) {
        val a = (argb ushr 24) and 0xFF
        if (a < 128) continue // skip mostly-transparent pixels
        val r = (argb ushr 16) and 0xFF
        val g = (argb ushr 8) and 0xFF
        val b = argb and 0xFF
        val maxc = max(r, max(g, b))
        val minc = min(r, min(g, b))
        val sat = if (maxc == 0) 0 else (maxc - minc) * 255 / maxc
        // Skip near-greyscale and near-black/near-white pixels: they make a poor,
        // muddy accent and dominate art that is mostly a photo or text.
        if (sat < 40) continue
        if (maxc < 40 || minc > 220) continue
        val hueBucket = (hue(r, g, b) * HUE_BUCKETS / 360).coerceIn(0, HUE_BUCKETS - 1)
        // Two lightness bands so a vivid dark and a vivid light of the same hue
        // don't average into a flat mid.
        val lightBand = if (maxc >= 160) 1 else 0
        val key = hueBucket * 2 + lightBand
        val acc = counts.getOrPut(key) { IntArray(4) }
        acc[0]++; acc[1] += r; acc[2] += g; acc[3] += b
    }
    val best = counts.values.maxByOrNull { it[0] } ?: return null
    val n = best[0]
    if (n == 0) return null
    return clampForLegibility(best[1] / n, best[2] / n, best[3] / n)
}

/**
 * Sample an [ImageBitmap] down to a small grid of pixels and extract a legible
 * accent. Downscaling to a [sampleSize]x[sampleSize] grid keeps the per-load work
 * tiny (a 24x24 = 576-pixel scan) while still capturing the art's palette. Runs
 * on the Android bitmap directly; returns null on any failure so the caller keeps
 * its default accent.
 */
fun dominantAccentFromArt(image: ImageBitmap, sampleSize: Int = 24): Color? = runCatching {
    val src = image.asAndroidBitmap()
    val scaled = android.graphics.Bitmap.createScaledBitmap(src, sampleSize, sampleSize, false)
    val pixels = IntArray(sampleSize * sampleSize)
    scaled.getPixels(pixels, 0, sampleSize, 0, 0, sampleSize, sampleSize)
    dominantAccentFromPixels(pixels)
}.getOrNull()

/** Integer hue in degrees [0, 360) from an RGB triple. */
internal fun hue(r: Int, g: Int, b: Int): Int {
    val maxc = max(r, max(g, b))
    val minc = min(r, min(g, b))
    val delta = maxc - minc
    if (delta == 0) return 0
    val h = when (maxc) {
        r -> 60.0 * (((g - b).toDouble() / delta) % 6)
        g -> 60.0 * (((b - r).toDouble() / delta) + 2)
        else -> 60.0 * (((r - g).toDouble() / delta) + 4)
    }
    val deg = ((h % 360) + 360) % 360
    return deg.toInt()
}

/**
 * Clamp an averaged RGB into a legible accent: floor the saturation so a washed
 * colour still reads, and pull the value into a mid-bright band so the tint sits
 * comfortably against the dark R1 surface without glaring or disappearing.
 */
internal fun clampForLegibility(r: Int, g: Int, b: Int): Color {
    val hsv = rgbToHsv(r, g, b)
    val h = hsv[0]
    val s = hsv[1].coerceIn(0.45f, 0.9f)
    val v = hsv[2].coerceIn(0.55f, 0.85f)
    val (cr, cg, cb) = hsvToRgb(h, s, v)
    return Color(red = cr / 255f, green = cg / 255f, blue = cb / 255f, alpha = 1f)
}

/** RGB (0..255) to HSV (h in degrees, s/v in 0..1). */
internal fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
    val rf = r / 255f; val gf = g / 255f; val bf = b / 255f
    val maxc = max(rf, max(gf, bf)); val minc = min(rf, min(gf, bf))
    val delta = maxc - minc
    val h = hue(r, g, b).toFloat()
    val s = if (maxc == 0f) 0f else delta / maxc
    return floatArrayOf(h, s, maxc)
}

/** HSV (h in degrees, s/v in 0..1) to an RGB triple (0..255). */
internal fun hsvToRgb(h: Float, s: Float, v: Float): Triple<Int, Int, Int> {
    val c = v * s
    val x = c * (1 - kotlin.math.abs((h / 60f % 2) - 1))
    val m = v - c
    val (r1, g1, b1) = when {
        h < 60 -> Triple(c, x, 0f)
        h < 120 -> Triple(x, c, 0f)
        h < 180 -> Triple(0f, c, x)
        h < 240 -> Triple(0f, x, c)
        h < 300 -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Triple(
        ((r1 + m) * 255).toInt().coerceIn(0, 255),
        ((g1 + m) * 255).toInt().coerceIn(0, 255),
        ((b1 + m) * 255).toInt().coerceIn(0, 255),
    )
}
