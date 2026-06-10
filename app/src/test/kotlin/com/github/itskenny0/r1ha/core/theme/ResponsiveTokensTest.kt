package com.github.itskenny0.r1ha.core.theme

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.UiTextScale
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the tier → dimensions mapping: column counts step up monotonically, the R1 keeps a
 * 1.0 type scale and no width cap (so it renders exactly as before), and larger tiers cap +
 * scale. These are pure look-ups, so testing them here guards the responsive tokens against
 * accidental drift without needing a Compose harness.
 */
class ResponsiveTokensTest {

    @Test fun `dashboard columns step up by tier`() {
        assertThat(R1Responsive.dashboardColumns(WindowTier.R1)).isEqualTo(1)
        assertThat(R1Responsive.dashboardColumns(WindowTier.COMPACT)).isEqualTo(1)
        assertThat(R1Responsive.dashboardColumns(WindowTier.MEDIUM)).isEqualTo(2)
        assertThat(R1Responsive.dashboardColumns(WindowTier.EXPANDED)).isEqualTo(2)
        assertThat(R1Responsive.dashboardColumns(WindowTier.EXTRA_LARGE)).isEqualTo(3)
    }

    @Test fun `grid columns step up by tier`() {
        assertThat(R1Responsive.gridColumns(WindowTier.R1)).isEqualTo(2)
        assertThat(R1Responsive.gridColumns(WindowTier.COMPACT)).isEqualTo(2)
        assertThat(R1Responsive.gridColumns(WindowTier.MEDIUM)).isEqualTo(3)
        assertThat(R1Responsive.gridColumns(WindowTier.EXPANDED)).isEqualTo(4)
        assertThat(R1Responsive.gridColumns(WindowTier.EXTRA_LARGE)).isEqualTo(5)
    }

    @Test fun `column counts never decrease as the window grows`() {
        val tiers = WindowTier.entries
        for (i in 1 until tiers.size) {
            assertThat(R1Responsive.dashboardColumns(tiers[i]))
                .isAtLeast(R1Responsive.dashboardColumns(tiers[i - 1]))
            assertThat(R1Responsive.gridColumns(tiers[i]))
                .isAtLeast(R1Responsive.gridColumns(tiers[i - 1]))
        }
    }

    @Test fun `R1 and compact never cap content width`() {
        assertThat(R1Responsive.of(WindowTier.R1).capsContentWidth).isFalse()
        assertThat(R1Responsive.of(WindowTier.R1).maxContentWidth).isEqualTo(Dp.Unspecified)
        assertThat(R1Responsive.of(WindowTier.COMPACT).capsContentWidth).isFalse()
    }

    @Test fun `medium and up cap content width`() {
        assertThat(R1Responsive.of(WindowTier.MEDIUM).capsContentWidth).isTrue()
        assertThat(R1Responsive.of(WindowTier.EXPANDED).capsContentWidth).isTrue()
        assertThat(R1Responsive.of(WindowTier.EXTRA_LARGE).capsContentWidth).isTrue()
    }

    @Test fun `type scale is exactly 1 on R1 and compact then grows`() {
        assertThat(R1Responsive.of(WindowTier.R1).typeScale).isEqualTo(1.0f)
        assertThat(R1Responsive.of(WindowTier.COMPACT).typeScale).isEqualTo(1.0f)
        assertThat(R1Responsive.of(WindowTier.MEDIUM).typeScale).isGreaterThan(1.0f)
        assertThat(R1Responsive.of(WindowTier.EXTRA_LARGE).typeScale)
            .isGreaterThan(R1Responsive.of(WindowTier.MEDIUM).typeScale)
    }

    @Test fun `scaleType leaves R1 styles byte-for-byte unchanged`() {
        val base = R1.body
        // Identity (===) — the R1 path returns the SAME instance, proving no scaling happens.
        assertThat(R1Responsive.scaleType(base, WindowTier.R1)).isSameInstanceAs(base)
        assertThat(R1Responsive.scaleType(base, WindowTier.COMPACT)).isSameInstanceAs(base)
    }

    @Test fun `scaleType grows the font size on large tiers`() {
        val base = R1.body.copy(fontSize = 14.sp)
        val scaled = R1Responsive.scaleType(base, WindowTier.EXTRA_LARGE)
        assertThat(scaled.fontSize.value).isGreaterThan(base.fontSize.value)
    }

    @Test fun `scaledFontDensity at the default step returns the base instance`() {
        val base = Density(2.0f, 1.0f)
        // Identity (===) — DEFAULT must be a true no-op so providing the result
        // as LocalDensity can never perturb composition for unchanged installs.
        assertThat(scaledFontDensity(base, UiTextScale.DEFAULT.factor)).isSameInstanceAs(base)
    }

    @Test fun `scaledFontDensity multiplies only the font axis`() {
        val base = Density(2.0f, 1.1f)
        val scaled = scaledFontDensity(base, UiTextScale.EXTRA_LARGE.factor)
        // dp axis untouched: touch targets and gutters keep their size.
        assertThat(scaled.density).isEqualTo(base.density)
        // sp axis multiplied on top of whatever system font scale was active.
        assertThat(scaled.fontScale).isWithin(1e-6f).of(1.1f * 1.3f)
    }

    @Test fun `text scale steps bracket the default`() {
        assertThat(UiTextScale.COMPACT.factor).isLessThan(1.0f)
        assertThat(UiTextScale.DEFAULT.factor).isEqualTo(1.0f)
        assertThat(UiTextScale.LARGE.factor).isGreaterThan(1.0f)
        assertThat(UiTextScale.EXTRA_LARGE.factor).isGreaterThan(UiTextScale.LARGE.factor)
    }
}
