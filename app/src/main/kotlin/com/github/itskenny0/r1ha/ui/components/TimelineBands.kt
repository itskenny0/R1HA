package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Horizontal coloured state-band row for a non-numeric entity, mirroring HA's
 * timeline chart. Each [TimelineBand] paints a segment sized by its duration
 * within the fixed [windowStartMillis]..[windowEndMillis] span; [colorFor]
 * maps a state string to a colour (the caller decides the palette so on/off,
 * open/closed, etc. read sensibly).
 *
 * Band segmentation is done by [segmentTimeline] (pure, tested); this draws the
 * result. Bands narrower than a pixel are still drawn at 1px so brief blips
 * don't vanish.
 */
@Composable
fun TimelineBandRow(
    bands: List<TimelineBand>,
    windowStartMillis: Long,
    windowEndMillis: Long,
    colorFor: (String) -> Color,
    modifier: Modifier = Modifier,
    height: Dp = 14.dp,
) {
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height),
    ) {
        val w = size.width
        val h = size.height
        val span = (windowEndMillis - windowStartMillis).coerceAtLeast(1L).toDouble()
        if (bands.isEmpty()) {
            drawRect(R1.Hairline, topLeft = Offset(0f, 0f), size = Size(w, h))
            return@Canvas
        }
        bands.forEach { band ->
            val x0 = (((band.startMillis - windowStartMillis) / span).toFloat()
                .coerceIn(0f, 1f)) * w
            val x1 = (((band.endMillis - windowStartMillis) / span).toFloat()
                .coerceIn(0f, 1f)) * w
            val bw = (x1 - x0).coerceAtLeast(1f)
            drawRect(
                color = colorFor(band.state),
                topLeft = Offset(x0, 0f),
                size = Size(bw, h),
            )
        }
    }
}

/**
 * Default state-to-colour mapping for timeline bands: active/on states take the
 * accent, inactive/off take a muted hairline, unknown/unavailable take a dim
 * grey. Covers the common binary domains (switch, binary_sensor, light, etc.).
 */
fun defaultTimelineColor(state: String, accent: Color): Color = when (state.lowercase()) {
    "on", "open", "home", "active", "playing", "heat", "cool", "auto", "detected" -> accent
    "off", "closed", "not_home", "away", "idle", "standby", "clear" -> R1.Hairline
    "unavailable", "unknown", "" -> R1.InkMuted.copy(alpha = 0.3f)
    else -> accent.copy(alpha = 0.6f)
}
