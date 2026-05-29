package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `gauge` card. A 180° arc that fills clockwise from
 * left → top → right as the value approaches `max`, with optional
 * severity bands that recolour the needle / arc when the value passes
 * configured thresholds.
 *
 * The arc lives in a fixed-height Box so a row of gauges in a stack
 * card don't shrink to invisibility on narrow widths. Min / max ticks
 * sit under the arc, the value + unit overlap the centre, the name
 * sits underneath.
 */
@Composable
fun GaugeCard(
    card: LovelaceCard.Gauge,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    // EntityState.raw is Number? (HA reports brightness as Int, volume as Double, etc.).
    // Coerce to Double for the gauge math; fall back to parsing rawState for sensors
    // whose raw payload didn't decode to a number (string-typed numeric sensors).
    val rawValue: Double? = state?.raw?.toDouble() ?: state?.rawState?.toDoubleOrNull()
    val name = resolveName(card.name, state, card.entityId)
    val unit = card.unit ?: state?.unit
    val severityColor = severityBandFor(rawValue, card.severity)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp),
            contentAlignment = Alignment.Center,
        ) {
            GaugeArc(
                fraction = computeFraction(rawValue, card.min, card.max),
                accent = severityColor ?: R1.AccentWarm,
                needle = card.needle,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(28.dp))
                Text(
                    text = rawValue?.let { formatGaugeNumber(it) } ?: ". ",
                    style = R1.numeralXl.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified),
                    color = R1.Ink,
                    fontWeight = FontWeight.Medium,
                )
                if (!unit.isNullOrBlank()) {
                    Text(unit, style = R1.numeralM, color = R1.InkSoft)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        // min .. max ticks under the arc.
        androidx.compose.foundation.layout.Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween,
        ) {
            Text(text = formatGaugeNumber(card.min), style = R1.numeralS, color = R1.InkMuted)
            Text(text = formatGaugeNumber(card.max), style = R1.numeralS, color = R1.InkMuted)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun GaugeArc(fraction: Float, accent: Color, needle: Boolean) {
    val safeFraction = fraction.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.size(200.dp, 100.dp)) {
        val strokeWidth = 14.dp.toPx()
        val arcSize = Size(size.width - strokeWidth, size.width - strokeWidth)
        val arcOffset = Offset(strokeWidth / 2f, strokeWidth / 2f)
        // Track (background). full 180°, dim.
        drawArc(
            color = R1.SurfaceMuted,
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = arcOffset,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        // Value arc. fills clockwise from the left edge.
        drawArc(
            color = accent,
            startAngle = 180f,
            sweepAngle = 180f * safeFraction,
            useCenter = false,
            topLeft = arcOffset,
            size = arcSize,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
        if (needle) {
            // Needle line from the centre to the arc at the current angle.
            val angleRad = Math.toRadians((180.0 + 180.0 * safeFraction))
            val cx = size.width / 2f
            val cy = size.height
            val radius = (size.width - strokeWidth) / 2f
            val nx = cx + (radius * 0.92f) * kotlin.math.cos(angleRad).toFloat()
            val ny = cy + (radius * 0.92f) * kotlin.math.sin(angleRad).toFloat()
            drawLine(
                color = R1.Ink,
                start = Offset(cx, cy),
                end = Offset(nx, ny),
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round,
            )
        }
    }
}

internal fun computeFraction(value: Double?, min: Double, max: Double): Float {
    if (value == null || max <= min) return 0f
    return ((value - min) / (max - min)).coerceIn(0.0, 1.0).toFloat()
}

internal fun severityBandFor(value: Double?, severity: com.github.itskenny0.r1ha.core.lovelace.GaugeSeverity?): Color? {
    if (value == null || severity == null) return null
    // Pick the colour for the highest band the value passes. red > yellow > green.
    severity.red?.let { if (value >= it) return R1.StatusRed }
    severity.yellow?.let { if (value >= it) return R1.StatusAmber }
    severity.green?.let { if (value >= it) return R1.AccentGreen }
    return null
}

/**
 * Format a gauge number for display. Whole numbers render without a decimal
 * point; everything else rounds to one decimal place. Always [Locale.US] so the
 * min and max end labels stay "30" and "100" (not a locale-comma "30,0") and a
 * 30..100 range never reads as a mashed-together "30100".
 */
internal fun formatGaugeNumber(d: Double): String {
    val rounded = kotlin.math.round(d * 100.0) / 100.0
    return if (rounded == kotlin.math.floor(rounded)) {
        rounded.toLong().toString()
    } else {
        String.format(java.util.Locale.US, "%.1f", rounded)
    }
}
