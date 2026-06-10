package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Tests for the hand-rolled album-art dominant-colour sampler.
 */
class AlbumArtColorTest {

    private fun argb(r: Int, g: Int, b: Int, a: Int = 255): Int =
        (a shl 24) or (r shl 16) or (g shl 8) or b

    @Test fun `empty pixels yield null`() {
        assertThat(dominantAccentFromPixels(IntArray(0))).isNull()
    }

    @Test fun `all transparent yields null`() {
        val pixels = IntArray(16) { argb(255, 0, 0, a = 0) }
        assertThat(dominantAccentFromPixels(pixels)).isNull()
    }

    @Test fun `greyscale art yields null (no usable accent)`() {
        val pixels = IntArray(16) { argb(120, 120, 120) }
        assertThat(dominantAccentFromPixels(pixels)).isNull()
    }

    @Test fun `dominant red bucket wins over a few blue pixels`() {
        val pixels = IntArray(20) { i -> if (i < 16) argb(200, 30, 30) else argb(30, 30, 200) }
        val accent = dominantAccentFromPixels(pixels)
        assertThat(accent).isNotNull()
        // The extracted accent should be reddish: red channel dominant.
        val c = accent!!
        assertThat(c.red).isGreaterThan(c.blue)
        assertThat(c.red).isGreaterThan(c.green)
    }

    @Test fun `extracted accent is clamped into a legible band`() {
        // A near-black saturated red should be lifted into a mid-bright band.
        val pixels = IntArray(16) { argb(60, 5, 5) }
        val accent = dominantAccentFromPixels(pixels)
        assertThat(accent).isNotNull()
        val c = accent!!
        // Value (max channel) is clamped to <= 0.85 and >= 0.55 in HSV terms;
        // the brightest channel lands in that band.
        val maxChannel = maxOf(c.red, c.green, c.blue)
        // ~0.55..0.85 in HSV value terms (allowing for the int-rounding in hsvToRgb).
        assertThat(maxChannel).isAtLeast(0.54f)
        assertThat(maxChannel).isAtMost(0.86f)
    }

    @Test fun `hue computes primary colours`() {
        assertThat(hue(255, 0, 0)).isEqualTo(0)
        assertThat(hue(0, 255, 0)).isEqualTo(120)
        assertThat(hue(0, 0, 255)).isEqualTo(240)
        assertThat(hue(100, 100, 100)).isEqualTo(0) // greyscale
    }
}
