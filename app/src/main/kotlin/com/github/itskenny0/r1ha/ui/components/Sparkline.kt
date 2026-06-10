package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * A single numeric line series ready to draw: pre-scaled samples plus the
 * stroke colour and (optional) gradient stops. The geometry is computed by the
 * [ChartEngine] pure functions; this is just the drawing payload.
 */
data class SparklineSeries(
    val samples: List<ChartSample>,
    val color: Color,
    /** Gradient stroke stops keyed by value (HA trend-graph hook). Empty = flat
     *  [color] stroke. Each pair is (value, colour); the renderer maps the value
     *  to a vertical fraction so the gradient tracks the data, not the pixels. */
    val gradient: List<Pair<Double, Color>> = emptyList(),
    /** Draw a low-opacity area fill under the line. */
    val fill: Boolean = true,
)

/**
 * Shared multi-series sparkline. One Canvas, one shared [ChartScale], the
 * fixed-window X anchoring and the midpoint-bezier smoothing all sourced from
 * [ChartEngine]. Every dashboards graph card draws through this so the line
 * styling (round caps, area fill, smoothing, gradient hook) is defined once.
 *
 * [windowStartMillis]..[windowEndMillis] pin the X axis; when null the series'
 * own span is used (so a standalone chart still fills the width). [limitMin] /
 * [limitMax] pin the Y axis (HA's `limits:` / `min_y_axis` / `max_y_axis`).
 *
 * Animated chart transitions (HA's ApexCharts/ECharts morph-in animation when a
 * series updates) are deliberately NOT implemented and will not be: the R1's
 * e-ink-like reflective panel reads animated curve tweens as smear rather than
 * motion, and a per-frame Compose recomposition of the whole Canvas is pure
 * token cost for no legibility gain on a 640x480 always-on kiosk. New data
 * snaps in on the next state update. This is a documented design decision, not
 * an oversight.
 */
@Composable
fun Sparkline(
    series: List<SparklineSeries>,
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    windowStartMillis: Long? = null,
    windowEndMillis: Long? = null,
    limitMin: Double? = null,
    limitMax: Double? = null,
    showMidline: Boolean = true,
    showLastDot: Boolean = true,
) {
    val allValues = remember(series, limitMin, limitMax) {
        series.flatMap { s -> s.samples.map { it.value } }
    }
    val scale = remember(allValues, limitMin, limitMax) {
        computeScale(allValues, limitMin, limitMax)
    }
    // X window: explicit pins, else the union span of every series.
    val allTimes = series.flatMap { s -> s.samples.map { it.tMillis } }
    val xStart = windowStartMillis ?: allTimes.minOrNull() ?: 0L
    val xEnd = windowEndMillis ?: allTimes.maxOrNull() ?: 1L

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(R1.Surface),
    ) {
        val w = size.width
        val h = size.height
        val pad = 6.dp.toPx()
        val plotW = (w - pad * 2).coerceAtLeast(1f)
        val plotH = (h - pad * 2).coerceAtLeast(1f)

        // Baseline + (optional) midline gridlines.
        drawLine(R1.Hairline, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        if (showMidline) {
            drawLine(
                R1.Hairline,
                Offset(0f, h / 2f),
                Offset(w, h / 2f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
            )
        }

        series.forEach { s ->
            if (s.samples.isEmpty()) return@forEach
            val pts = s.samples.map { sample ->
                val xf = xFraction(sample.tMillis, xStart, xEnd)
                val yf = scaleYFraction(sample.value, scale)
                Offset(pad + xf * plotW, pad + (1f - yf) * plotH)
            }
            // Extend the last sample flat to the right edge ("held until now").
            val drawn = if (extendsToRightEdge && pts.isNotEmpty()) {
                pts + Offset(pad + plotW, pts.last().y)
            } else {
                pts
            }
            if (drawn.size < 2) {
                // Single point: a dot is the honest rendering.
                drawCircle(s.color, radius = 2.dp.toPx(), center = drawn.first())
                return@forEach
            }

            val linePath = buildSmoothPath(drawn)
            if (s.fill) {
                val fillPath = Path().apply {
                    addPath(linePath)
                    lineTo(drawn.last().x, h)
                    lineTo(drawn.first().x, h)
                    close()
                }
                drawPath(fillPath, color = s.color.copy(alpha = 0.12f))
            }
            val brush = strokeBrush(s, scale, pad, plotH, h)
            drawPath(
                path = linePath,
                brush = brush,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                    join = StrokeJoin.Round,
                ),
            )
            if (showLastDot) {
                drawCircle(s.color, radius = 2.dp.toPx(), center = pts.last())
            }
        }
    }
}

/** Build the stroke brush: a vertical gradient when [SparklineSeries.gradient]
 *  stops are set (mapped through the scale so colour tracks value), else a flat
 *  solid colour. */
private fun DrawScope.strokeBrush(
    s: SparklineSeries,
    scale: ChartScale,
    pad: Float,
    plotH: Float,
    h: Float,
): Brush {
    if (s.gradient.isEmpty()) return Brush.verticalGradient(0f to s.color, 1f to s.color)
    val stops = s.gradient
        .sortedByDescending { it.first }
        .map { (value, color) ->
            val yf = scaleYFraction(value, scale)
            val y = pad + (1f - yf) * plotH
            (y / h).coerceIn(0f, 1f) to color
        }
        .sortedBy { it.first }
    return Brush.verticalGradient(colorStops = stops.toTypedArray())
}

/**
 * Midpoint quadratic-bezier smoothing: each segment curves through the midpoint
 * between consecutive samples with the sample itself as the control point, so
 * the line reads as a smooth trend without overshooting past the real readings
 * (a Catmull-Rom would). Two-point series degrade to a straight line.
 */
private fun buildSmoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.isEmpty()) return path
    path.moveTo(points.first().x, points.first().y)
    if (points.size == 2) {
        path.lineTo(points[1].x, points[1].y)
        return path
    }
    for (i in 1 until points.size) {
        val prev = points[i - 1]
        val cur = points[i]
        val midX = (prev.x + cur.x) / 2f
        val midY = (prev.y + cur.y) / 2f
        path.quadraticBezierTo(prev.x, prev.y, midX, midY)
    }
    path.lineTo(points.last().x, points.last().y)
    return path
}

/**
 * Loading placeholder for a sparkline: a faint mid-line with a sweeping shimmer
 * so the card paints instantly (from a known box size) while history loads,
 * rather than reflowing when data lands. [errorText], when set, replaces the
 * shimmer with a quiet message (history unavailable / reconnecting).
 */
@Composable
fun SparklinePlaceholder(
    modifier: Modifier = Modifier,
    height: Dp = 72.dp,
    errorText: String? = null,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(2.dp))
            .background(R1.Surface),
        contentAlignment = Alignment.Center,
    ) {
        if (errorText != null) {
            Text(text = errorText, style = R1.labelMicro, color = R1.InkMuted)
        } else {
            val transition = rememberInfiniteTransition(label = "sparkline-shimmer")
            val phase by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(1400, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart,
                ),
                label = "phase",
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val w = size.width
                val h = size.height
                drawLine(
                    R1.Hairline,
                    Offset(0f, h / 2f),
                    Offset(w, h / 2f),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
                )
                // A short bright segment sweeping left to right.
                val segW = w * 0.25f
                val x = (phase * (w + segW)) - segW
                drawLine(
                    R1.InkMuted.copy(alpha = 0.5f),
                    Offset(x.coerceAtLeast(0f), h / 2f),
                    Offset((x + segW).coerceAtMost(w), h / 2f),
                    strokeWidth = 2.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}
