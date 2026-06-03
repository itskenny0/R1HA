package com.github.itskenny0.r1ha.ui.layout

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
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
 * Wraps screen content in a [Column], centred and width-capped on roomy tiers and full-bleed on
 * the small ones. On R1 / compact the responsive [maxContentWidth] is unspecified, so content
 * fills the panel exactly as before (every pixel kept). On medium and larger tiers it is centred
 * and capped at the tier's [ResponsiveDimens.maxContentWidth] (840 / 1100 / 1320 dp) so list and
 * form screens read as a centred column instead of one wall-wide line on a 13in panel. The cap
 * is tier-aware and generous, so it does not reproduce the old fixed-800dp letterboxing.
 *
 * The [maxWidth] parameter is retained for API compatibility but the tier cap is used instead.
 */
@Composable
fun AdaptiveContent(
    modifier: Modifier = Modifier,
    @Suppress("UNUSED_PARAMETER") maxWidth: Dp = 800.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
    if (!dimens.capsContentWidth) {
        Column(modifier = modifier.fillMaxSize()) { content() }
        return
    }
    Row(modifier = modifier.fillMaxSize(), horizontalArrangement = Arrangement.Center) {
        Column(
            modifier = Modifier
                .widthIn(max = dimens.maxContentWidth)
                .fillMaxWidth()
                .fillMaxHeight(),
        ) { content() }
    }
}
