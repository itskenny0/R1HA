package com.github.itskenny0.r1ha.ui.layout

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Width-based responsive tier the current composition is rendering in.
 *
 * The R1's native screen reports ~320–340 dp wide depending on the active
 * `wm density`, so anything ≤ 360 dp is treated as **R1** and rendered
 * exactly as before (no regressions on the R1's small portrait display).
 *
 * `PHONE` covers most modern phones (typically 360–500 dp portrait); the
 * layout stays single-column but gets a small breathing-room cap so a
 * landscape phone or a tall narrow tablet column doesn't stretch tiles
 * absurdly wide. `TABLET` is everything above — centred narrow column
 * (or a 2-column dashboard) so an 8″ tablet doesn't render a single card
 * across 1200 dp.
 *
 * Thresholds match the Material 3 window-size-class breakpoints loosely
 * — but we keep the lower one at 360 dp so the R1 sits squarely in the
 * smallest bucket regardless of LineageOS GSI density tweaks.
 */
enum class WidthTier {
    /** ≤ 360 dp — R1 native portrait. Layout is rendered exactly as
     *  written; no max-width clamp, no extra padding. */
    R1,
    /** 361–599 dp — most phones in portrait. Single column with a
     *  light max-width cap so a held landscape phone doesn't get
     *  overstretched rows. */
    PHONE,
    /** ≥ 600 dp — tablets, foldables, large landscape phones. Content
     *  is centred with a tighter max-width cap. Some screens (Cameras
     *  GRID, Dashboard tile row) can also opt into a wider grid. */
    TABLET,
}

/** Reads the current screen width and maps it to a [WidthTier]. Cheap —
 *  pulls from [LocalConfiguration] which is already part of every Compose
 *  call site. */
@Composable
@ReadOnlyComposable
fun currentWidthTier(): WidthTier {
    val w = LocalConfiguration.current.screenWidthDp
    return when {
        w <= 360 -> WidthTier.R1
        w < 600 -> WidthTier.PHONE
        else -> WidthTier.TABLET
    }
}

/** Convenience — `true` when the host is bigger than an R1. Use sparingly;
 *  most screens should route through [currentWidthTier] for the actual
 *  layout decision. */
@Composable
@ReadOnlyComposable
fun isWiderThanR1(): Boolean = currentWidthTier() != WidthTier.R1

/**
 * Passthrough wrapper — all tiers fill the available width without a
 * max-width cap. The card-based UI adapts naturally to any screen width;
 * applying a fixed narrow cap caused the content to occupy only ~1/3 of
 * a large tablet screen in landscape.
 *
 * The function still exists so call sites don't need changing, and the
 * [WidthTier] / [currentWidthTier] helpers remain for the cameras grid
 * which uses them to decide column count.
 */
@Composable
fun ResponsiveColumn(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopCenter,
    content: @Composable () -> Unit,
) {
    content()
}

/**
 * Wraps screen content in a [Column] that fills the available space on
 * every tier — no max-width cap. An earlier version capped tablet content
 * at 800 dp, but that letterboxed list / form screens on wide displays
 * (roughly half the screen on a 1920 dp panel), and the card-based UI
 * already adapts naturally to any width via weight-based and fillMaxWidth
 * interior layouts. Now a pure passthrough — call sites stay unchanged so
 * future tier-specific behaviour can re-land here without touching every
 * screen.
 *
 * The [maxWidth] parameter is retained for API compatibility but ignored.
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") maxWidth: Dp = 800.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) { content() }
}

/** Column count for grid surfaces (Cameras GRID, future favourites
 *  picker grid, etc.). R1 stays at 2 columns; phones widen to 2; tablets
 *  go to 3 so the extra horizontal space is actually used; very wide
 *  screens (≥ 960 dp — 12" tablets in landscape) bump to 4. Returning an
 *  Int keeps call sites pleasingly terse: `columns = GridCells.Fixed(gridColumnsFor(tier))`. */
@Composable
@ReadOnlyComposable
fun gridColumnsFor(tier: WidthTier = currentWidthTier()): Int {
    if (tier == WidthTier.TABLET && LocalConfiguration.current.screenWidthDp >= 960) return 4
    return when (tier) {
        WidthTier.R1 -> 2
        WidthTier.PHONE -> 2
        WidthTier.TABLET -> 3
    }
}
