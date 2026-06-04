package com.github.itskenny0.r1ha.feature.dashboards.cards

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import java.time.Duration

/**
 * Renderer for HA's `history-graph` card. Fetches each configured entity's
 * history off [LocalHaRepository] and overlays the numeric series on a
 * single shared canvas, each line a distinct accent so they're tellable
 * apart, with a small colour-coded legend underneath.
 *
 * Mirrors the [com.github.itskenny0.r1ha.ui.components.SensorHistoryChart]
 * canvas idiom (faint baseline + midline, accent stroke) but plots multiple
 * series against a common time + value axis. Non-numeric entities are
 * skipped from the line plot (HA renders those as state-timeline bars; we
 * leave them out rather than fake a meaningless line).
 */
@Composable
fun HistoryGraphCard(
    card: LovelaceCard.HistoryGraph,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val ids = remember(card.entities) { card.entities.map { it.entityId } }
    var series by remember(ids, card.hoursToShow) {
        mutableStateOf<List<EntitySeries>>(emptyList())
    }
    if (repo != null) {
        LaunchedEffect(ids, card.hoursToShow) {
            val out = ArrayList<EntitySeries>(ids.size)
            ids.forEachIndexed { idx, raw ->
                val eid = safeEntityId(raw) ?: return@forEachIndexed
                val name = resolveName(card.entities[idx].name, stateMap[eid], raw)
                repo.fetchHistory(eid, hours = card.hoursToShow)
                    .onSuccess { pts ->
                        out.add(EntitySeries(name, pts, lineColor(idx)))
                    }
            }
            series = out
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            val numericSeries = series.filter { it.points.count { p -> p.numeric != null } >= 2 }
            if (numericSeries.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (repo == null) "HISTORY UNAVAILABLE" else "WAITING FOR HISTORY",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            } else {
                MultiLineChart(numericSeries)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    numericSeries.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(s.color),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = s.name,
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiLineChart(series: List<EntitySeries>) {
    // Shared bounds across every numeric series so the lines share a frame.
    val numeric = series.map { s -> s.points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } } }
    val allValues = numeric.flatten().map { it.second }
    if (allValues.size < 2) return
    val yMin = allValues.min()
    val yMax = allValues.max()
    val allTimes = numeric.flatten().map { it.first }
    val tStart = allTimes.min()
    val tEnd = allTimes.max()
    val tSpan = Duration.between(tStart, tEnd).toMillis().coerceAtLeast(1L)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(R1.Surface)
            .padding(horizontal = 6.dp, vertical = 6.dp),
    ) {
        val w = size.width
        val h = size.height
        drawLine(R1.Hairline, Offset(0f, h), Offset(w, h), strokeWidth = 1.dp.toPx())
        drawLine(
            R1.Hairline,
            Offset(0f, h / 2f),
            Offset(w, h / 2f),
            strokeWidth = 1.dp.toPx(),
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f), 0f),
        )
        series.forEachIndexed { idx, _ ->
            val pts = numeric[idx]
            if (pts.size < 2) return@forEachIndexed
            val path = Path()
            pts.forEachIndexed { i, (instant, value) ->
                val elapsed = Duration.between(tStart, instant).toMillis().toFloat()
                val x = (elapsed / tSpan) * w
                val yFrac = com.github.itskenny0.r1ha.ui.components.chartYFraction(value, yMin, yMax)
                val y = h - (yFrac * h)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(
                path = path,
                color = series[idx].color,
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Butt),
            )
        }
    }
}

private data class EntitySeries(
    val name: String,
    val points: List<HistoryPoint>,
    val color: Color,
)

/** Cycle a small palette so each series in the graph reads distinctly. */
private fun lineColor(index: Int): Color = when (index % 4) {
    0 -> R1.AccentWarm
    1 -> R1.AccentCool
    2 -> R1.AccentGreen
    else -> R1.StatusAmber
}
