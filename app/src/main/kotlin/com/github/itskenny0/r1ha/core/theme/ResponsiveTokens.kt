package com.github.itskenny0.r1ha.core.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.times
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.WindowTier

/**
 * Tier-aware companions to the static [R1] design tokens. [R1] stays the single source of
 * the brand language (palette, base type ramp, base spacing); this file layers the
 * *responsive* dimension on top so paddings, content width caps, grid columns, and the type
 * scale step up by [WindowTier] without any screen hardcoding per-size dp.
 *
 * Everything here is pure (a [WindowTier] in, a value out) so the column-count and scale
 * maths can be unit-tested, and so a non-composable caller can resolve a value too. The
 * `@Composable` helpers at the bottom are thin sugar that read [LocalWindowTier] for you.
 *
 * Adoption pattern: a screen reads `R1Responsive.of(tier)` once and pulls `screenGutter`,
 * `maxContentWidth`, `dashboardColumns`, etc. from the returned [ResponsiveDimens]; for the
 * type scale it wraps a base [R1] style in [scaleType]. Nothing should reach for a raw dp
 * gutter that varies by size again.
 */
object R1Responsive {

    /** The full bundle of size-dependent dimensions for a [tier]. Value-equal + [Immutable]
     *  so passing it down or remembering it is free. */
    fun of(tier: WindowTier): ResponsiveDimens = when (tier) {
        WindowTier.R1 -> ResponsiveDimens(
            tier = tier,
            screenGutter = R1.space.m,
            sectionGap = R1.space.s,
            cardInset = R1.space.l,
            // No cap on the R1: every pixel of the tiny panel is precious.
            maxContentWidth = Dp.Unspecified,
            dashboardColumns = 1,
            gridColumns = 2,
            typeScale = 1.0f,
        )
        WindowTier.COMPACT -> ResponsiveDimens(
            tier = tier,
            screenGutter = R1.space.l,
            sectionGap = R1.space.m,
            cardInset = R1.space.l,
            maxContentWidth = Dp.Unspecified,
            dashboardColumns = 1,
            gridColumns = 2,
            typeScale = 1.0f,
        )
        WindowTier.MEDIUM -> ResponsiveDimens(
            tier = tier,
            screenGutter = R1.space.xl,
            sectionGap = R1.space.l,
            cardInset = R1.space.l,
            // Keep medium tablets readable but still single-column-ish: cap the column.
            maxContentWidth = 840.dp,
            dashboardColumns = 2,
            gridColumns = 3,
            typeScale = 1.06f,
            chartScale = 1.4f,
        )
        WindowTier.EXPANDED -> ResponsiveDimens(
            tier = tier,
            screenGutter = R1.space.xl,
            sectionGap = R1.space.l,
            cardInset = R1.space.xl,
            maxContentWidth = 1100.dp,
            dashboardColumns = 2,
            gridColumns = 4,
            typeScale = 1.12f,
            chartScale = 1.7f,
        )
        WindowTier.EXTRA_LARGE -> ResponsiveDimens(
            tier = tier,
            screenGutter = R1.space.xxl,
            sectionGap = R1.space.xl,
            cardInset = R1.space.xl,
            // Cap line length / card width on huge windows: centre, don't stretch full-bleed.
            maxContentWidth = 1320.dp,
            dashboardColumns = 3,
            gridColumns = 5,
            typeScale = 1.18f,
            chartScale = 2.0f,
        )
    }

    /**
     * Grid column count for adaptive grids (camera walls, picker grids, a future
     * favourites grid). Pure so it's unit-testable. Mirrors [ResponsiveDimens.gridColumns]
     * but exposed standalone for the terse `GridCells.Fixed(R1Responsive.gridColumns(tier))`
     * call shape.
     */
    fun gridColumns(tier: WindowTier): Int = of(tier).gridColumns

    /**
     * Dashboard tile-column count. The reference (flagship) surface uses this to decide how
     * many tile columns to flow into: one on R1 / compact, two on medium / expanded, three on
     * extra-large. Pure + tested.
     */
    fun dashboardColumns(tier: WindowTier): Int = of(tier).dashboardColumns

    /**
     * Multiplies a base [TextStyle]'s `fontSize` (and `lineHeight`, when set) by the tier's
     * [ResponsiveDimens.typeScale]. The R1 ramp in [R1] is the 1.0 baseline; larger tiers get
     * a gentle step up so a 14sp body doesn't read tiny across a 13" panel, while the R1 keeps
     * its hand-tuned sizes EXACTLY (scale is 1.0 there, so the returned style is unchanged).
     *
     * Letter-spacing and weight are deliberately left untouched: scaling those drifts the
     * chrome's character. Only the point size grows.
     */
    fun scaleType(base: TextStyle, tier: WindowTier): TextStyle {
        val factor = of(tier).typeScale
        if (factor == 1.0f) return base
        val scaledLineHeight = if (base.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified) {
            base.lineHeight * factor
        } else {
            base.lineHeight
        }
        return base.copy(
            fontSize = base.fontSize * factor,
            lineHeight = scaledLineHeight,
        )
    }
}

/**
 * The resolved set of size-dependent dimensions for one [WindowTier]. Read via
 * [R1Responsive.of] or the `@Composable` [rememberResponsiveDimens].
 *
 * @property screenGutter horizontal inset from the window edge to content.
 * @property sectionGap vertical spacing between major sections / cards.
 * @property cardInset internal padding inside a card or prominent row.
 * @property maxContentWidth cap on the centred content column; [Dp.Unspecified] means
 *   "fill the available width" (R1 / compact never letterbox).
 * @property dashboardColumns tile columns for the Today / dashboard surface.
 * @property gridColumns columns for adaptive grids (camera walls, picker grids).
 * @property typeScale multiplier applied to base type sizes via [R1Responsive.scaleType].
 */
@Immutable
data class ResponsiveDimens(
    val tier: WindowTier,
    val screenGutter: Dp,
    val sectionGap: Dp,
    val cardInset: Dp,
    val maxContentWidth: Dp,
    val dashboardColumns: Int,
    val gridColumns: Int,
    val typeScale: Float,
    /** Multiplier for fixed-height canvases (history/energy charts, the zones
     *  map). A 180dp chart that reads fine on the R1 becomes a thin strip on a
     *  13" tablet; scaling height with the tier keeps the aspect sane. */
    val chartScale: Float = 1.0f,
) {
    /** True when content should be centred + width-capped rather than filling full-bleed. */
    val capsContentWidth: Boolean get() = maxContentWidth != Dp.Unspecified
}

/** Resolves [ResponsiveDimens] for the current composition from [LocalWindowTier]. */
@Composable
@ReadOnlyComposable
fun rememberResponsiveDimens(): ResponsiveDimens =
    R1Responsive.of(LocalWindowTier.current.tier)

/** Sugar: scale a base [R1] type style for the current tier. */
@Composable
@ReadOnlyComposable
fun responsiveType(base: TextStyle): TextStyle =
    R1Responsive.scaleType(base, LocalWindowTier.current.tier)
