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
 * Column math for the dashboard's camera grid, the one surface that wants a flat 2/2/3/4
 * column progression keyed off raw width rather than the structural decisions the rest of
 * the app makes through [com.github.itskenny0.r1ha.ui.components.WindowTier].
 *
 * The progression is intentionally width-driven (not tier-driven) so the boundaries stay
 * exactly where the camera grid has always placed them, independent of the WindowTier
 * thresholds used elsewhere.
 */

/**
 * Pure width to camera-grid-column mapping. The single place the column breakpoints live, so
 * the composable reader and the unit tests both route through here.
 *
 *  - `<= 360 dp` (Rabbit R1 + tiny phones): 2 columns
 *  - `361 .. 599 dp` (ordinary phones): 2 columns
 *  - `600 .. 959 dp` (tablets / large landscape): 3 columns
 *  - `>= 960 dp` (very wide windows): 4 columns
 */
fun cameraGridColumnsForWidthDp(widthDp: Int): Int = when {
    widthDp >= 960 -> 4
    widthDp >= 600 -> 3
    else -> 2
}

/**
 * Column count for the cameras grid at the current width. Reads [LocalConfiguration] and
 * delegates to [cameraGridColumnsForWidthDp]; cheap, configuration is already part of every
 * composition.
 */
@Composable
@ReadOnlyComposable
fun gridColumnsFor(): Int = cameraGridColumnsForWidthDp(LocalConfiguration.current.screenWidthDp)

/**
 * Passthrough wrapper: all widths fill the available space without a max-width cap. The
 * card-based UI adapts naturally to any screen width; applying a fixed narrow cap caused the
 * content to occupy only about a third of a large tablet screen in landscape.
 *
 * The function still exists so call sites stay unchanged and future width-specific behaviour
 * can re-land here without touching every screen.
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
 * Wraps screen content in a [Column] that fills the available space on every width, with no
 * max-width cap. An earlier version capped tablet content at 800 dp, but that letterboxed
 * list / form screens on wide displays (roughly half the screen on a 1920 dp panel), and the
 * card-based UI already adapts naturally to any width via weight-based and fillMaxWidth
 * interior layouts. Now a pure passthrough so call sites stay unchanged and future
 * width-specific behaviour can re-land here without touching every screen.
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
