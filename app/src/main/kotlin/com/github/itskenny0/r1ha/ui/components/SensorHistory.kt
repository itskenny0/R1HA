package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mini line chart for a numeric sensor's history. Pure Canvas — no charting library —
 * because the rendering is dead simple (one line, one min/max pair) and pulling in a
 * library would bloat the APK by ~500 KB for this single use case.
 *
 * Axes are deliberately minimal: just a min and max Y label on the right edge so the
 * user can interpret the line's amplitude, and start/end timestamps on the bottom for
 * orientation. The line itself is the accent colour, 1.5 dp stroke, butt caps so it
 * reads as a precise reading rather than an organic curve.
 */
@Composable
fun SensorHistoryChart(
    points: List<HistoryPoint>,
    accent: Color,
    unit: String?,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        ChartHint(text = "NOT ENOUGH HISTORY YET", modifier = modifier)
        return
    }
    val numeric = points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
    if (numeric.size < 2) {
        ChartHint(text = "HISTORY ISN'T NUMERIC", modifier = modifier)
        return
    }
    val ys = numeric.map { it.second }
    val yMin = ys.min()
    val yMax = ys.max()
    // If the line is dead flat the chart degenerates into a horizontal stripe; fake a
    // tiny range so the line still shows in the middle of the chart area.
    val yRange = (yMax - yMin).takeIf { it > 1e-9 } ?: 1.0
    val tStart = numeric.first().first
    val tEnd = numeric.last().first
    val tSpan = Duration.between(tStart, tEnd).toMillis().coerceAtLeast(1L)

    Column(modifier = modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "LAST ${formatSpan(tSpan)}", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.weight(1f))
            // min/max labels — show the y range so the user can interpret the line.
            Text(
                text = "${formatSensorValue(yMin.toString())}–${formatSensorValue(yMax.toString())}${unit?.let { " $it" } ?: ""}",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
        Spacer(Modifier.height(4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(R1.Surface)
                .padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            val w = size.width
            val h = size.height
            // Faint baseline gridline at the bottom so the chart has visual grounding even
            // when the line itself is near zero.
            drawLine(
                color = R1.Hairline,
                start = Offset(0f, h),
                end = Offset(w, h),
                strokeWidth = 1.dp.toPx(),
            )
            // Midline gridline — dashed, so it's discernibly secondary to the data line.
            drawLine(
                color = R1.Hairline,
                start = Offset(0f, h / 2f),
                end = Offset(w, h / 2f),
                strokeWidth = 1.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
            )

            // Build the line path. Time → X (proportional to time since start); value → Y
            // (inverted because canvas Y grows down).
            val path = Path()
            numeric.forEachIndexed { idx, (instant, value) ->
                val elapsed = Duration.between(tStart, instant).toMillis().toFloat()
                val x = (elapsed / tSpan) * w
                val yFrac = ((value - yMin) / yRange).toFloat()
                val y = h - (yFrac * h)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = accent,
                style = Stroke(
                    width = 1.5.dp.toPx(),
                    cap = StrokeCap.Butt,
                ),
            )

            // Latest-value dot — gives the eye an anchor for "where the reading is right
            // now" without having to mentally trace the line all the way to the right edge.
            val (_, lastVal) = numeric.last()
            val lastYFrac = ((lastVal - yMin) / yRange).toFloat()
            drawCircle(
                color = accent,
                radius = 2.dp.toPx(),
                center = Offset(w, h - lastYFrac * h),
            )
        }
        Spacer(Modifier.height(2.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = formatTime(tStart), style = R1.labelMicro, color = R1.InkMuted)
            Text(text = formatTime(tEnd), style = R1.labelMicro, color = R1.InkMuted)
        }
    }
}

/**
 * Text history for non-numeric or low-cardinality sensors — binary sensors, enum sensors,
 * weather-condition sensors, etc. A scrolling list of recent state changes with their
 * timestamps. Configurable length via the global `textHistoryLength` setting; the list
 * is reversed so the most recent change is at the top (the user's eye lands there first).
 */
@Composable
fun SensorHistoryList(
    points: List<HistoryPoint>,
    accent: Color,
    maxEntries: Int,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) {
        ChartHint(text = "NO HISTORY YET", modifier = modifier)
        return
    }
    // Newest-first, then capped to the user's preferred length.
    val recent = points.asReversed().take(maxEntries)
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "LAST ${recent.size} CHANGES",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(4.dp))
        // Bounded height so the list scrolls within the card rather than pushing the
        // READ-ONLY footer off-screen. 120 dp ≈ 5 rows of body text on the R1.
        LazyColumn(modifier = Modifier.fillMaxWidth().height(120.dp)) {
            items(recent, key = { p -> p.timestamp.toEpochMilli() }) { p ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 2.dp),
                ) {
                    // Tiny accent dot — visual anchor + state-on/off colour cue.
                    Box(
                        modifier = Modifier
                            .size(5.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (p.state.equals("off", ignoreCase = true)) R1.InkMuted else accent),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = formatTime(p.timestamp),
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        modifier = Modifier.width(56.dp),
                    )
                    Text(
                        text = formatSensorValue(p.state).uppercase(),
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun ChartHint(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(R1.Surface),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.labelMicro, color = R1.InkMuted)
    }
}

private val timeFmt = DateTimeFormatter.ofPattern("HH:mm")

private fun formatTime(instant: Instant): String =
    LocalTime.ofInstant(instant, ZoneId.systemDefault()).format(timeFmt)

private fun formatSpan(millis: Long): String {
    val hours = millis / 3_600_000L
    val days = hours / 24L
    return when {
        days >= 1 -> "${days}D"
        hours >= 1 -> "${hours}H"
        else -> "${millis / 60_000L}M"
    }
}
