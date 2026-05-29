package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the five-tier width thresholds and the ordering helpers. These are the foundation
 * every responsive decision branches on, so a stray edit to a boundary (especially the R1
 * ceiling) would silently regress the Rabbit R1 into a phone layout — exactly what these
 * tests exist to catch.
 */
class WindowTierTest {

    @Test fun `R1 covers below 360`() {
        assertThat(WindowTierBreakpoints.tierForWidthDp(0)).isEqualTo(WindowTier.R1)
        assertThat(WindowTierBreakpoints.tierForWidthDp(240)).isEqualTo(WindowTier.R1)
        assertThat(WindowTierBreakpoints.tierForWidthDp(340)).isEqualTo(WindowTier.R1)
        assertThat(WindowTierBreakpoints.tierForWidthDp(359)).isEqualTo(WindowTier.R1)
    }

    @Test fun `compact is 360 to 599 inclusive`() {
        assertThat(WindowTierBreakpoints.tierForWidthDp(360)).isEqualTo(WindowTier.COMPACT)
        assertThat(WindowTierBreakpoints.tierForWidthDp(411)).isEqualTo(WindowTier.COMPACT)
        assertThat(WindowTierBreakpoints.tierForWidthDp(599)).isEqualTo(WindowTier.COMPACT)
    }

    @Test fun `medium is 600 to 839 inclusive`() {
        assertThat(WindowTierBreakpoints.tierForWidthDp(600)).isEqualTo(WindowTier.MEDIUM)
        assertThat(WindowTierBreakpoints.tierForWidthDp(768)).isEqualTo(WindowTier.MEDIUM)
        assertThat(WindowTierBreakpoints.tierForWidthDp(839)).isEqualTo(WindowTier.MEDIUM)
    }

    @Test fun `expanded is 840 to 1199 inclusive`() {
        assertThat(WindowTierBreakpoints.tierForWidthDp(840)).isEqualTo(WindowTier.EXPANDED)
        assertThat(WindowTierBreakpoints.tierForWidthDp(1024)).isEqualTo(WindowTier.EXPANDED)
        assertThat(WindowTierBreakpoints.tierForWidthDp(1199)).isEqualTo(WindowTier.EXPANDED)
    }

    @Test fun `extra large is 1200 and up`() {
        assertThat(WindowTierBreakpoints.tierForWidthDp(1200)).isEqualTo(WindowTier.EXTRA_LARGE)
        assertThat(WindowTierBreakpoints.tierForWidthDp(1920)).isEqualTo(WindowTier.EXTRA_LARGE)
        assertThat(WindowTierBreakpoints.tierForWidthDp(4000)).isEqualTo(WindowTier.EXTRA_LARGE)
    }

    @Test fun `breakpoint constants line up with the boundaries`() {
        assertThat(
            WindowTierBreakpoints.tierForWidthDp(WindowTierBreakpoints.COMPACT_MIN_DP),
        ).isEqualTo(WindowTier.COMPACT)
        assertThat(
            WindowTierBreakpoints.tierForWidthDp(WindowTierBreakpoints.MEDIUM_MIN_DP),
        ).isEqualTo(WindowTier.MEDIUM)
        assertThat(
            WindowTierBreakpoints.tierForWidthDp(WindowTierBreakpoints.EXPANDED_MIN_DP),
        ).isEqualTo(WindowTier.EXPANDED)
        assertThat(
            WindowTierBreakpoints.tierForWidthDp(WindowTierBreakpoints.EXTRA_LARGE_MIN_DP),
        ).isEqualTo(WindowTier.EXTRA_LARGE)
    }

    @Test fun `tiers are ordered smallest to largest`() {
        assertThat(WindowTier.R1.ordinal).isLessThan(WindowTier.COMPACT.ordinal)
        assertThat(WindowTier.COMPACT.ordinal).isLessThan(WindowTier.MEDIUM.ordinal)
        assertThat(WindowTier.MEDIUM.ordinal).isLessThan(WindowTier.EXPANDED.ordinal)
        assertThat(WindowTier.EXPANDED.ordinal).isLessThan(WindowTier.EXTRA_LARGE.ordinal)
    }

    @Test fun `isAtLeast respects ordering`() {
        assertThat(WindowTier.MEDIUM.isAtLeast(WindowTier.COMPACT)).isTrue()
        assertThat(WindowTier.MEDIUM.isAtLeast(WindowTier.MEDIUM)).isTrue()
        assertThat(WindowTier.COMPACT.isAtLeast(WindowTier.MEDIUM)).isFalse()
        assertThat(WindowTier.R1.isAtLeast(WindowTier.COMPACT)).isFalse()
    }

    @Test fun `info booleans match the tier`() {
        val medium = WindowTierInfo(WindowTier.MEDIUM, widthDp = 700, heightDp = 1000)
        assertThat(medium.isR1).isFalse()
        assertThat(medium.isAtLeastCompact).isTrue()
        assertThat(medium.isAtLeastMedium).isTrue()
        assertThat(medium.isAtLeastExpanded).isFalse()
        assertThat(medium.isExtraLarge).isFalse()

        val r1 = WindowTierInfo(WindowTier.R1, widthDp = 240, heightDp = 282)
        assertThat(r1.isR1).isTrue()
        assertThat(r1.isAtLeastCompact).isFalse()
        assertThat(r1.isLandscape).isFalse()
    }

    @Test fun `landscape and tall flags read off dimensions`() {
        val land = WindowTierInfo(WindowTier.EXPANDED, widthDp = 1280, heightDp = 800)
        assertThat(land.isLandscape).isTrue()
        assertThat(land.isTallEnoughForTwoRows).isTrue()

        val shortLand = WindowTierInfo(WindowTier.MEDIUM, widthDp = 800, heightDp = 400)
        assertThat(shortLand.isLandscape).isTrue()
        assertThat(shortLand.isTallEnoughForTwoRows).isFalse()
    }
}
