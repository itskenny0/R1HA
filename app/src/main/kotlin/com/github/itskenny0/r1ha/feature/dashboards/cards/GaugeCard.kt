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
import androidx.compose.runtime.remember
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
    // Resolve by raw id (the domain-agnostic state-slice path).
    val state = stateMap.byRaw(card.entityId)
    // EntityState.raw is Number? (HA reports brightness as Int, volume as Double, etc.).
    // Coerce to Double for the gauge math; fall back to parsing rawState for sensors
    // whose raw payload didn't decode to a number (string-typed numeric sensors).
    val rawValue: Double? = state?.raw?.toDouble() ?: state?.rawState?.toDoubleOrNull()
    val name = resolveName(card.name, state, card.entityId)
    val unit = card.unit ?: state?.unit
    // Resolve the severity/segment bands once: with `needle: true` HA paints the
    // whole arc into coloured bands behind the needle (segments take precedence
    // over severity, matching hui-gauge-card's `_severityLevels`). Without a
    // needle the gauge keeps the single accent fill, recoloured to the band the
    // value currently sits in.
    val bands = remember(card.segments, card.severity, card.min, card.max) {
        gaugeBands(card.segments, card.severity, card.min, card.max)
    }
    val severityColor = bandColorFor(rawValue, bands)

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
                // Bands paint behind the needle only in needle mode (HA's
                // behaviour). In the plain fill mode the single accent arc already
                // carries the band colour via [severityColor], so no segments.
                bands = if (card.needle) bands else emptyList(),
                min = card.min,
                max = card.max,
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Spacer(Modifier.height(28.dp))
                Text(
                    // A genuinely-absent / non-numeric value shows a single dash
                    // rather than a ". " stub that reads as a render glitch.
                    text = rawValue?.let { formatGaugeNumber(it) } ?: "-",
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
private fun GaugeArc(
    fraction: Float,
    accent: Color,
    needle: Boolean,
    bands: List<GaugeBand> = emptyList(),
    min: Double = 0.0,
    max: Double = 100.0,
) {
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
        if (bands.isNotEmpty() && max > min) {
            // Needle mode: paint the whole arc into coloured severity/segment
            // bands behind the needle. Each band spans from its `from` to the
            // next band's `from` (or `max` for the last), mapped onto the 180°
            // arc. Butt caps so adjacent bands meet cleanly without overlap.
            bands.forEachIndexed { i, band ->
                val startVal = band.from.coerceIn(min, max)
                val endVal = (if (i + 1 < bands.size) bands[i + 1].from else max).coerceIn(min, max)
                if (endVal <= startVal) return@forEachIndexed
                val startFrac = ((startVal - min) / (max - min)).toFloat()
                val endFrac = ((endVal - min) / (max - min)).toFloat()
                drawArc(
                    color = band.color,
                    startAngle = 180f + 180f * startFrac,
                    sweepAngle = 180f * (endFrac - startFrac),
                    useCenter = false,
                    topLeft = arcOffset,
                    size = arcSize,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Butt),
                )
            }
        } else {
            // Plain fill mode: a single value arc filling clockwise from the left.
            drawArc(
                color = accent,
                startAngle = 180f,
                sweepAngle = 180f * safeFraction,
                useCenter = false,
                topLeft = arcOffset,
                size = arcSize,
                style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
            )
        }
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

/** One resolved gauge band: the value it starts at and the colour it paints. */
internal data class GaugeBand(val from: Double, val color: Color)

/**
 * Resolve a gauge's severity/segment config into an ascending list of bands.
 * Segments take precedence over severity (HA's `_severityLevels`): each
 * `{from, color}` becomes a band, colour mapped via [haColorAccent] / the
 * severity palette. The old `severity: {green, yellow, red}` form becomes three
 * bands starting at their thresholds. Returns empty when neither is configured.
 */
internal fun gaugeBands(
    segments: List<com.github.itskenny0.r1ha.core.lovelace.GaugeSegment>,
    severity: com.github.itskenny0.r1ha.core.lovelace.GaugeSeverity?,
    min: Double,
    max: Double,
): List<GaugeBand> {
    if (segments.isNotEmpty()) {
        return segments
            .map { GaugeBand(from = it.from, color = haColorAccent(it.color) ?: severityColor(it.color)) }
            .sortedBy { it.from }
    }
    if (severity != null) {
        val out = mutableListOf<GaugeBand>()
        severity.green?.let { out.add(GaugeBand(it, R1.AccentGreen)) }
        severity.yellow?.let { out.add(GaugeBand(it, R1.StatusAmber)) }
        severity.red?.let { out.add(GaugeBand(it, R1.StatusRed)) }
        out.sortBy { it.from }
        // HA fills the arc from `min` upward; if the lowest band starts above the
        // gauge minimum, prepend a neutral band so the gap before it isn't blank.
        if (out.isNotEmpty() && out.first().from > min) {
            out.add(0, GaugeBand(min, R1.AccentNeutral))
        }
        return out
    }
    return emptyList()
}

/** The colour the [value] falls into for the plain-fill (no-needle) gauge:
 *  the highest band whose `from` the value has reached. Null = no bands. */
internal fun bandColorFor(value: Double?, bands: List<GaugeBand>): Color? {
    if (value == null || bands.isEmpty()) return null
    var picked: Color? = null
    for (band in bands) {
        if (value >= band.from) picked = band.color else break
    }
    return picked
}

/** Map HA's severity colour names (`red` / `green` / `yellow` / `normal`) to the
 *  R1 palette. Used for `segments[].color` values that aren't a theme-colour
 *  name [haColorAccent] handles or a hex literal. */
private fun severityColor(name: String): Color = when (name.trim().lowercase()) {
    "red", "error" -> R1.StatusRed
    "yellow", "warning" -> R1.StatusAmber
    "green", "success" -> R1.AccentGreen
    else -> R1.AccentCool
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
