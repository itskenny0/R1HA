package com.github.itskenny0.r1ha.core.theme

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaletteDeriveTest {

    private val orange = 0xFFF36F21.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()

    @Test fun `derived gradient is bright to base to deep`() {
        val (bright, base, anchor) = overrideGradientArgb(orange).toList()
        assertThat(base).isEqualTo(orange)
        assertThat(relativeLuminance(bright)).isGreaterThan(relativeLuminance(base))
        assertThat(relativeLuminance(anchor)).isLessThan(relativeLuminance(base))
    }

    @Test fun `deep anchor keeps white text readable`() {
        // Matches the hand-tuned palettes' deepest stops (navy 0x0D3B66 sits
        // around 0.04): the derived anchor must land at or below the ceiling
        // that keeps white text near the 6:1 contrast the theme aims for.
        for (base in listOf(orange, white, 0xFF41BDF5.toInt(), 0xFFFFEB3B.toInt())) {
            assertThat(relativeLuminance(overrideGradientArgb(base)[2]))
                .isAtMost(ANCHOR_LUMINANCE_CEILING)
        }
    }

    @Test fun `anchor preserves the base hue direction`() {
        // Darkening scales channels uniformly, so the dominant channel must
        // stay dominant: an orange override should not anchor to blue.
        val anchor = overrideGradientArgb(orange)[2]
        val r = (anchor shr 16) and 0xFF
        val g = (anchor shr 8) and 0xFF
        val b = anchor and 0xFF
        assertThat(r).isAtLeast(g)
        assertThat(g).isAtLeast(b)
    }

    @Test fun `near-black base terminates and stays opaque`() {
        val out = overrideGradientArgb(black)
        assertThat(out).hasLength(3)
        out.forEach { assertThat((it ushr 24) and 0xFF).isEqualTo(0xFF) }
    }

    @Test fun `luminance is gamma aware`() {
        // Mid grey 0x808080 linearizes to ~0.22, not 0.5: a plain channel
        // average would get this wrong and mis-place the anchor cutoff.
        val lum = relativeLuminance(0xFF808080.toInt())
        assertThat(lum).isWithin(0.03f).of(0.216f)
        assertThat(relativeLuminance(white)).isWithin(0.001f).of(1.0f)
        assertThat(relativeLuminance(black)).isWithin(0.001f).of(0.0f)
    }

    @Test fun `scrim alpha stays within the tuned band`() {
        // The header band must never go fully opaque (that would kill the gradient
        // identity) nor below the navy floor (white text would smear on a bright stop).
        for (stop in listOf(white, black, orange, 0xFF41BDF5.toInt(), 0xFFFFB347.toInt())) {
            val a = topScrimAlpha(stop)
            assertThat(a).isAtLeast(SCRIM_ALPHA_MIN)
            assertThat(a).isAtMost(SCRIM_ALPHA_MAX)
        }
    }

    @Test fun `brighter stops get a heavier scrim`() {
        // The amber palette's lifted stop (0xFFB347) is far brighter than the navy
        // palette's (0x41BDF5), so it must earn more darkening behind white ink.
        val amberHead = 0xFFFFB347.toInt()
        val skyHead = 0xFF41BDF5.toInt()
        assertThat(topScrimAlpha(amberHead)).isGreaterThan(topScrimAlpha(skyHead))
    }

    @Test fun `a near-white stop reaches the ceiling and black bottoms out`() {
        // Monotonic endpoints: white pins to MAX, black to MIN. Guards against a
        // future ramp tweak silently inverting the relationship.
        assertThat(topScrimAlpha(white)).isWithin(0.001f).of(SCRIM_ALPHA_MAX)
        assertThat(topScrimAlpha(black)).isWithin(0.001f).of(SCRIM_ALPHA_MIN)
    }
}
