package com.github.itskenny0.r1ha.feature.dashboards.cards

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.Routes
import com.github.itskenny0.r1ha.ui.components.ChartGroupKey
import com.github.itskenny0.r1ha.ui.components.ChartSample
import com.github.itskenny0.r1ha.ui.components.Sparkline
import com.github.itskenny0.r1ha.ui.components.SparklineSeries
import com.github.itskenny0.r1ha.ui.components.TimelineBand
import com.github.itskenny0.r1ha.ui.components.TimelineBandRow
import com.github.itskenny0.r1ha.ui.components.defaultTimelineColor
import com.github.itskenny0.r1ha.ui.components.downSampleLineData
import com.github.itskenny0.r1ha.ui.components.groupSeriesForCharts
import com.github.itskenny0.r1ha.ui.components.maxDetailsFor
import com.github.itskenny0.r1ha.ui.components.purgeToWindow
import com.github.itskenny0.r1ha.ui.components.redrawIntervalMillis
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.segmentTimeline

/**
 * Renderer for HA's `history-graph` card. Fetches each configured entity's
 * history and splits it the way HA does: numeric entities draw as line charts
 * (grouped into separate charts per unit, or per unit+device-class when
 * `split_device_classes` is set), and non-numeric entities (switches,
 * binary_sensors, enum sensors) draw as horizontal coloured state-band
 * timelines.
 *
 * All geometry comes from the shared graph engine (downsample, fixed-window
 * anchoring, timeline segmentation); the window slides live while displayed.
 * The card title is tappable and opens R1HA's native History feature on the
 * first entity. Per-entity colour overrides (`entities: [{entity, color}]`)
 * are honoured.
 */
@Composable
fun HistoryGraphCard(
    card: LovelaceCard.HistoryGraph,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val repo = LocalHaRepository.current
    val ids = remember(card.entities) { card.entities.map { it.entityId } }
    var rawById by remember(ids, card.hoursToShow) {
        mutableStateOf<Map<String, List<HistoryPoint>>>(emptyMap())
    }
    var loaded by remember(ids, card.hoursToShow) { mutableStateOf(false) }
    var nowMillis by remember(ids, card.hoursToShow) { mutableStateOf(System.currentTimeMillis()) }

    if (repo != null) {
        LaunchedEffect(ids, card.hoursToShow) {
            while (true) {
                val out = LinkedHashMap<String, List<HistoryPoint>>(ids.size)
                val windowStartInstant = java.time.Instant.ofEpochMilli(
                    System.currentTimeMillis() - (card.hoursToShowExact * 3_600_000L).toLong(),
                )
                ids.forEach { raw ->
                    val eid = safeEntityId(raw) ?: return@forEach
                    repo.fetchHistory(eid, hours = card.hoursToShow)
                        .onSuccess { history ->
                            // Long-term statistics backfill: when the recorder has
                            // purged the early part of a long window, fill it from
                            // hourly statistics so the graph still draws the full
                            // range (HA's hui-history-graph-card merge).
                            out[raw] = if (needsStatisticsBackfill(history, windowStartInstant)) {
                                val stats = repo.getStatisticsDuringPeriod(
                                    statisticIds = listOf(raw),
                                    start = windowStartInstant,
                                    end = java.time.Instant.now(),
                                    period = "hour",
                                ).getOrNull()?.get(raw).orEmpty()
                                mergeHistoryWithStatistics(history, stats, windowStartInstant)
                            } else {
                                history
                            }
                        }
                }
                rawById = out
                loaded = true
                nowMillis = System.currentTimeMillis()
                kotlinx.coroutines.delay(redrawIntervalMillis(card.hoursToShowExact))
            }
        }
    }

    val windowEnd = nowMillis
    val windowStart = windowEnd - (card.hoursToShowExact * 3_600_000L).toLong()
    val firstEntity = ids.firstOrNull()

    CardSurface(modifier = modifier, title = null) {
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            // Title row: tappable to open native History on the first entity.
            val title = card.title?.takeUnless { it.isBlank() }
            if (title != null) {
                Text(
                    text = title,
                    style = R1.sectionHeader,
                    color = R1.InkSoft,
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .then(
                            if (firstEntity != null) {
                                Modifier.r1Pressable(onClick = {
                                    onAction(LovelaceAction.Navigate(Routes.historyRoute(firstEntity)))
                                })
                            } else Modifier,
                        )
                        .padding(vertical = 4.dp),
                )
                Spacer(Modifier.height(4.dp))
            }

            // Classify each entity: numeric (>= 2 numeric samples) -> line,
            // else -> timeline band.
            data class NumericSeries(
                val name: String, val samples: List<ChartSample>,
                val color: Color, val unit: String?, val deviceClass: String?,
            )
            data class TimelineSeries(val name: String, val bands: List<TimelineBand>, val accent: Color)

            val numeric = ArrayList<NumericSeries>()
            val timelines = ArrayList<TimelineSeries>()
            card.entities.forEachIndexed { idx, row ->
                val raw = rawById[row.entityId] ?: return@forEachIndexed
                val st = stateMap.byRaw(row.entityId)
                val name = resolveName(row.name, st, row.entityId)
                val color = haColorAccent(card.entityColors[row.entityId])
                    ?: lineColor(idx)
                val numericPts = raw.mapNotNull { p ->
                    p.numeric?.let { ChartSample(p.timestamp.toEpochMilli(), it) }
                }
                if (numericPts.size >= 2) {
                    val purged = purgeToWindow(numericPts, windowStart)
                    val sampled = downSampleLineData(
                        purged,
                        maxDetails = maxDetailsFor(card.hoursToShowExact, 1),
                        minX = windowStart,
                        maxX = windowEnd,
                        useMean = true,
                    )
                    numeric.add(NumericSeries(name, sampled, color, st?.unit, st?.deviceClass))
                } else if (raw.isNotEmpty()) {
                    val cat = raw.map { it.timestamp.toEpochMilli() to it.state }
                    val bands = segmentTimeline(cat, windowStart, windowEnd)
                    if (bands.isNotEmpty()) timelines.add(TimelineSeries(name, bands, color))
                }
            }

            if (numeric.isEmpty() && timelines.isEmpty()) {
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
                            repo == null -> "HISTORY UNAVAILABLE"
                            loaded -> "NO HISTORY"
                            else -> "WAITING FOR HISTORY"
                        },
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            } else {
                // Group numeric series into separate charts per unit (+ device
                // class when split_device_classes is set).
                val groupKeys = numeric.map { ChartGroupKey(it.unit, it.deviceClass) }
                val groups = groupSeriesForCharts(groupKeys, card.splitDeviceClasses)
                groups.forEachIndexed { gi, (_, members) ->
                    if (gi > 0) Spacer(Modifier.height(6.dp))
                    Sparkline(
                        series = members.map { mi ->
                            SparklineSeries(samples = numeric[mi].samples, color = numeric[mi].color)
                        },
                        height = 84.dp,
                        windowStartMillis = windowStart,
                        windowEndMillis = windowEnd,
                        limitMin = card.minYAxis,
                        limitMax = card.maxYAxis,
                    )
                }

                // Timeline bands for non-numeric entities, one row each.
                timelines.forEach { tl ->
                    Spacer(Modifier.height(6.dp))
                    if (card.showNames) {
                        Text(text = tl.name, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
                        Spacer(Modifier.height(2.dp))
                    }
                    TimelineBandRow(
                        bands = tl.bands,
                        windowStartMillis = windowStart,
                        windowEndMillis = windowEnd,
                        colorFor = { defaultTimelineColor(it, tl.accent) },
                    )
                }

                // Compact legend for the line series.
                if (card.showNames && numeric.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        numeric.forEach { s ->
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
}

/** Cycle a small palette so each series in the graph reads distinctly. */
internal fun lineColor(index: Int): Color = when (index % 4) {
    0 -> R1.AccentWarm
    1 -> R1.AccentCool
    2 -> R1.AccentGreen
    else -> R1.StatusAmber
}
