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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.Routes
import com.github.itskenny0.r1ha.ui.components.ChartSample
import com.github.itskenny0.r1ha.ui.components.ChartScale
import com.github.itskenny0.r1ha.ui.components.Sparkline
import com.github.itskenny0.r1ha.ui.components.SparklineSeries
import com.github.itskenny0.r1ha.ui.components.computeScale
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.scaleYFraction
import com.github.itskenny0.r1ha.ui.components.selectStatColumn
import com.github.itskenny0.r1ha.ui.components.xFraction
import java.time.Duration
import java.time.Instant

private const val DEFAULT_STAT_DAYS = 30

/**
 * Renderer for HA's `statistics-graph` card. Fetches long-term-statistics
 * buckets over a `days_to_show` window at the configured `period` bucket size
 * and renders each entity's series for every requested stat_type, either as
 * lines (default, through the shared [Sparkline]) or as bars
 * (`chart_type: bar`). Per-entity name and colour overrides are honoured, plus
 * y-axis pinning and legend options. A "history" shortcut opens the Statistics
 * screen.
 *
 * The energy date-range binding (`energy_date_selection` / `collection_key`) is
 * parsed but deferred to the energy batch; until then the window comes from
 * days_to_show.
 */
@Composable
fun StatisticsGraphCard(
    card: LovelaceCard.StatisticsGraph,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    // Energy date-range binding: when collection_key / energy_date_selection is
    // set the window comes from the shared energy period instead of days_to_show,
    // so the card reflows with the dashboard's energy selector.
    val energyPeriod = card.collectionKey?.let {
        val collection =
            com.github.itskenny0.r1ha.feature.dashboards.cards.energy.rememberEnergyCollection(it)
        collection.data.collectAsStateWithLifecycle().value.period
    }
    // One drawable series per (entity, stat_type) pair.
    var series by remember(card.entityIds, card.statTypes, card.period, card.daysToShow, energyPeriod) {
        mutableStateOf<List<StatSeries>>(emptyList())
    }
    var loaded by remember(card.entityIds, card.statTypes, card.period, card.daysToShow, energyPeriod) {
        mutableStateOf(false)
    }
    var nowMillis by remember(card.entityIds, card.period, card.daysToShow, energyPeriod) {
        mutableStateOf(System.currentTimeMillis())
    }

    if (repo != null) {
        LaunchedEffect(card.entityIds, card.statTypes, card.period, card.daysToShow, energyPeriod) {
            while (true) {
                val end = energyPeriod?.end ?: Instant.now()
                val days = card.daysToShow ?: DEFAULT_STAT_DAYS
                val start = energyPeriod?.start ?: end.minus(Duration.ofDays(days.toLong()))
                repo.getStatisticsDuringPeriod(
                    statisticIds = card.entityIds,
                    start = start,
                    end = end,
                    period = card.period,
                ).onSuccess { byId ->
                    val out = ArrayList<StatSeries>()
                    card.entityIds.forEachIndexed { ei, eid ->
                        val buckets = byId[eid] ?: return@forEachIndexed
                        card.statTypes.forEachIndexed { si, statType ->
                            val pts = buckets.mapNotNull { b ->
                                val v = selectStatColumn(
                                    statType, b.mean, b.min, b.max, b.sum, b.state, b.change,
                                ) ?: return@mapNotNull null
                                ChartSample(b.start.toEpochMilli(), v)
                            }
                            if (pts.isNotEmpty()) {
                                val name = card.entityNames[eid]
                                    ?: resolveName(null, stateMap.byRaw(eid), eid)
                                val color = haColorAccent(card.entityColors[eid])
                                    ?: lineColor(ei * card.statTypes.size + si)
                                out.add(StatSeries(eid, statType, name, pts, color))
                            }
                        }
                    }
                    series = out
                }
                loaded = true
                nowMillis = System.currentTimeMillis()
                kotlinx.coroutines.delay(300_000L)
            }
        }
    }

    val windowEnd = energyPeriod?.end?.toEpochMilli() ?: nowMillis
    val windowStart = energyPeriod?.start?.toEpochMilli()
        ?: (windowEnd - (card.daysToShow ?: DEFAULT_STAT_DAYS).toLong() * 86_400_000L)

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StateChip(text = card.statTypes.joinToString("/") { it.uppercase() }, accent = R1.InkSoft)
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
                if (card.chartType == "bar") {
                    StatisticsBarChart(series, windowStart, windowEnd, card.minYAxis, card.maxYAxis)
                } else {
                    Sparkline(
                        series = series.map { SparklineSeries(samples = it.points, color = it.color, fill = false) },
                        height = 96.dp,
                        windowStartMillis = windowStart,
                        windowEndMillis = windowEnd,
                        limitMin = card.minYAxis,
                        limitMax = card.maxYAxis,
                    )
                }
                if (!card.hideLegend) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        series.forEach { s ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(s.color),
                                )
                                Spacer(Modifier.size(4.dp))
                                Text(
                                    text = if (card.statTypes.size > 1) "${s.name} ${s.statType}" else s.name,
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
}

/** One drawable statistics series: an (entity, stat_type) pair's points. */
private data class StatSeries(
    val entityId: String,
    val statType: String,
    val name: String,
    val points: List<ChartSample>,
    val color: Color,
)

/**
 * Bar rendering for `chart_type: bar`: one bar per bucket, grouped per series
 * within each time slot. Bars grow from the scale's zero origin so a mix of
 * positive and negative changes reads correctly.
 */
@Composable
private fun StatisticsBarChart(
    series: List<StatSeries>,
    windowStart: Long,
    windowEnd: Long,
    limitMin: Double?,
    limitMax: Double?,
) {
    val allValues = remember(series, limitMin, limitMax) { series.flatMap { s -> s.points.map { it.value } } }
    if (allValues.size < 2) return
    val scale: ChartScale = remember(allValues, limitMin, limitMax) {
        computeScale(allValues, limitMin, limitMax)
    }
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
        val originY = h - scaleYFraction(0.0, scale) * h
        drawLine(R1.Hairline, Offset(0f, originY), Offset(w, originY), strokeWidth = 1.dp.toPx())
        // Bar slot width: split each bucket's slot across the series count.
        val slotCount = series.firstOrNull()?.points?.size ?: return@Canvas
        if (slotCount == 0) return@Canvas
        val slotW = w / slotCount
        val barW = (slotW / (series.size + 1)).coerceAtLeast(1f)
        series.forEachIndexed { si, s ->
            s.points.forEach { p ->
                val xf = xFraction(p.tMillis, windowStart, windowEnd)
                val cx = xf * w + (si - series.size / 2f) * barW
                val yf = scaleYFraction(p.value, scale)
                val barTop = h - yf * h
                val top = minOf(barTop, originY)
                val barH = kotlin.math.abs(barTop - originY).coerceAtLeast(1f)
                drawRect(
                    color = s.color,
                    topLeft = Offset(cx, top),
                    size = Size(barW.coerceAtLeast(1f), barH),
                )
            }
        }
    }
}
