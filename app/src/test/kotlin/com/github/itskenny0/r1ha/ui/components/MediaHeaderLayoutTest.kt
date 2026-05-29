package com.github.itskenny0.r1ha.ui.components

import androidx.compose.ui.unit.dp
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the per-tier geometry of the now-playing header. The mapping is the one
 * thing that makes the media block responsive, so a stray edit (e.g. oversizing
 * the phone art back toward a full-width square) would silently regress the exact
 * problem this redesign fixed.
 */
class MediaHeaderLayoutTest {

    @Test fun `R1 uses the smallest art`() {
        assertThat(mediaHeaderLayoutFor(WindowTier.R1).artwork).isEqualTo(48.dp)
    }

    @Test fun `compact bumps the art up a notch`() {
        assertThat(mediaHeaderLayoutFor(WindowTier.COMPACT).artwork).isEqualTo(56.dp)
    }

    @Test fun `expanded and extra-large share the roomiest geometry`() {
        assertThat(mediaHeaderLayoutFor(WindowTier.EXPANDED))
            .isEqualTo(mediaHeaderLayoutFor(WindowTier.EXTRA_LARGE))
    }

    @Test fun `art never grows as the window shrinks`() {
        val r1 = mediaHeaderLayoutFor(WindowTier.R1).artwork
        val compact = mediaHeaderLayoutFor(WindowTier.COMPACT).artwork
        val medium = mediaHeaderLayoutFor(WindowTier.MEDIUM).artwork
        val expanded = mediaHeaderLayoutFor(WindowTier.EXPANDED).artwork
        assertThat(r1.value).isAtMost(compact.value)
        assertThat(compact.value).isAtMost(medium.value)
        assertThat(medium.value).isAtMost(expanded.value)
    }

    @Test fun `gutter never grows as the window shrinks`() {
        val r1 = mediaHeaderLayoutFor(WindowTier.R1).gap
        val compact = mediaHeaderLayoutFor(WindowTier.COMPACT).gap
        val medium = mediaHeaderLayoutFor(WindowTier.MEDIUM).gap
        val expanded = mediaHeaderLayoutFor(WindowTier.EXPANDED).gap
        assertThat(r1.value).isAtMost(compact.value)
        assertThat(compact.value).isAtMost(medium.value)
        assertThat(medium.value).isAtMost(expanded.value)
    }

    @Test fun `every tier resolves to a usable layout`() {
        for (tier in WindowTier.entries) {
            val layout = mediaHeaderLayoutFor(tier)
            assertThat(layout.artwork.value).isGreaterThan(0f)
            assertThat(layout.gap.value).isGreaterThan(0f)
        }
    }
}
