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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.Routes
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Duration
import java.time.Instant

/**
 * Renderer for HA's `statistics-graph` card (HA 2022.11). Fetches long-term-statistics
 * buckets for each configured entity and overlays the requested aggregate series on a
 * shared canvas. Mirrors the multi-series canvas idiom from [HistoryGraphCard] and the
 * fetch pattern from [StatisticCard].
 *
 * Only the first stat_type is plotted per entity (the mean series by default). A
 * "history" shortcut navigates to the Statistics screen. Gracefully shows a placeholder
 * when no numeric data is available.
 */
@Composable
fun StatisticsGraphCard(
    card: LovelaceCard.StatisticsGraph,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val statType = card.statTypes.firstOrNull() ?: "mean"
    var series by remember(card.entityIds, statType, card.period, card.daysToShow) {
        mutableStateOf<List<Pair<String, List<Pair<Instant, Double>>>>>(emptyList())
    }
    var loaded by remember(card.entityIds, statType, card.period, card.daysToShow) { mutableStateOf(false) }

    if (repo != null) {
        LaunchedEffect(card.entityIds, statType, card.period, card.daysToShow) {
            val end = Instant.now()
            val lookback = card.daysToShow?.let { Duration.ofDays(it.toLong()) } ?: lookbackFor(card.period)
            val start = end.minus(lookback)
            repo.getStatisticsDuringPeriod(
                statisticIds = card.entityIds,
                start = start,
                end = end,
                period = bucketPeriodFor(card.period),
            ).onSuccess { byId ->
                series = card.entityIds.mapNotNull { eid ->
                    val buckets = byId[eid] ?: return@mapNotNull null
                    val pts = buckets.mapNotNull { b ->
                        val v = statisticBucketValue(b, statType) ?: return@mapNotNull null
                        b.start to v
                    }
                    if (pts.isEmpty()) null else eid to pts
                }
            }
            loaded = true
        }
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateChip(text = statType.uppercase(), accent = R1.InkSoft)
                Text(
                    text = "HISTORY",
                    style = R1.labelMicro,
                    color = R1.AccentCool,
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .r1Pressable(onClick = { onAction(LovelaceAction.Navigate(Routes.STATISTICS)) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.height(6.dp))
            if (series.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(96.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            repo == null -> "STATISTICS UNAVAILABLE"
                            !loaded -> "LOADING..."
                            else -> "NO STATISTICS"
                        },
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            } else {
                StatisticsMultiLineChart(series)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    series.forEachIndexed { idx, (eid, _) ->
                        val name = resolveName(null, stateMap.byRaw(eid), eid)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(lineColor(idx)),
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = name,
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
private fun StatisticsMultiLineChart(series: List<Pair<String, List<Pair<Instant, Double>>>>) {
    val allPts = series.flatMap { it.second }
    if (allPts.size < 2) return
    val allValues = allPts.map { it.second }
    val yMin = allValues.min()
    val yMax = allValues.max()
    val allTimes = allPts.map { it.first }
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
        series.forEachIndexed { idx, (_, pts) ->
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
                color = lineColor(idx),
                style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Butt),
            )
        }
    }
}

/** Extract one numeric value from a [StatisticsBucket] for a given [statType]. */
private fun statisticBucketValue(b: StatisticsBucket, statType: String): Double? = when (statType.lowercase()) {
    "mean" -> b.mean
    "min" -> b.min
    "max" -> b.max
    "sum" -> b.sum
    "state" -> b.state
    "change" -> b.change
    else -> b.mean ?: b.state
}
