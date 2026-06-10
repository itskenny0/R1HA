package com.github.itskenny0.r1ha.feature.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1CenteredContent
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Long-term statistics surface: picks any series HA's recorder is
 * collecting and renders a Compose Canvas chart of the chosen
 * aggregation (mean / min / max / sum / change) over a window.
 *
 * Distinct from the History drill-in: that screen shows the raw
 * `state_changed` stream (every reading the integration emitted) over a
 * short window. Statistics goes further back at lower resolution by
 * reading from the recorder's pre-aggregated buckets, which is the only
 * way to plot 30 days of a 1 Hz sensor without melting the device.
 *
 * The chart Composable mirrors HistoryScreen's `HistoryChartPanel` so
 * the two surfaces feel like siblings rather than separate inventions.
 */
@Composable
fun StatisticsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: StatisticsViewModel = viewModel(factory = StatisticsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    LaunchedEffect(Unit) { vm.loadCatalogue() }
    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
        ) {
            R1TopBar(
                title = "STATISTICS",
                onBack = onBack,
                action = {
                    R1Chip(
                        text = if (ui.seriesLoading || ui.catalogueLoading) "…" else "REFRESH",
                        variant = R1ChipVariant.Action,
                        onClick = {
                            if (ui.selected != null) vm.refreshSeries() else vm.loadCatalogue()
                        },
                        contentDescription = "Refresh statistics",
                    )
                },
            )
            // Centre + width-cap the body on medium/expanded/extra-large tiers so the
            // pickers, chart, and summary read as a centred column instead of stretching
            // one giant line edge to edge; R1 / compact stay full-bleed (no cap) so the
            // tiny panel keeps every pixel. Gutters and the section gap step up with the
            // tier via the responsive dims rather than a fixed R1.space.
            val dimens = rememberResponsiveDimens()
            R1CenteredContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.screenGutter, vertical = R1.space.s)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap),
            ) {
                StatisticPickerBar(ui = ui, onOpen = { vm.openPicker() })
                if (ui.selected != null) {
                    if (vm.hasNoPlottableAggregation(ui)) {
                        // A statistic with neither mean nor sum can't drive any
                        // aggregation chip; spell that out rather than leaving
                        // an inert chip row over an empty chart.
                        NoPlottablePanel()
                    } else {
                        WindowChips(current = ui.window, onSelect = { vm.setWindow(it) })
                        PeriodChips(
                            current = ui.period,
                            window = ui.window,
                            isAllowed = { p -> vm.periodAllowedFor(p, ui.window) },
                            onSelect = { vm.setPeriod(it) },
                        )
                        AggregationChips(
                            current = ui.aggregation,
                            supported = vm.supportedAggregations(ui),
                            onSelect = { vm.setAggregation(it) },
                        )
                        StatisticsChartPanel(vm = vm, ui = ui)
                        SummaryPanel(vm = vm, ui = ui)
                    }
                } else if (!ui.catalogueLoading && ui.catalogueError == null) {
                    EmptyHero()
                }
                if (ui.catalogueError != null && ui.available.isEmpty()) {
                    ErrorPanel(message = ui.catalogueError ?: "")
                }
                if (ui.seriesError != null) {
                    ErrorPanel(message = ui.seriesError ?: "")
                }
                Spacer(Modifier.size(R1.space.xl))
            }
            }
        }
        if (ui.pickerOpen) {
            StatisticPickerSheet(
                rows = ui.available,
                onPick = { vm.selectStatistic(it) },
                onDismiss = { vm.closePicker() },
            )
        }
    }
}

@Composable
private fun StatisticPickerBar(
    ui: StatisticsViewModel.UiState,
    onOpen: () -> Unit,
) {
    val selected = ui.selected
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onOpen, contentDescription = "Pick a statistic")
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Text(text = "STATISTIC", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        Spacer(Modifier.size(R1.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (selected != null) {
                // Leading domain glyph derived from the statistic id (e.g.
                // sensor.kitchen_temperature -> the temperature glyph), so the
                // selected statistic reads at a glance the way dashboard rows do.
                // External statistic ids (domain:object) fall through to the
                // generic glyph rather than mis-resolving.
                androidx.compose.material3.Icon(
                    imageVector = R1Icons.forEntity(selected.statisticId),
                    contentDescription = null,
                    tint = R1.AccentWarm,
                    modifier = Modifier.padding(end = R1.space.s).size(18.dp),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                if (selected != null) {
                    Text(
                        text = selected.name?.takeIf { it.isNotBlank() } ?: selected.statisticId,
                        style = responsiveType(R1.bodyEmph),
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Text(
                        text = buildString {
                            append(selected.statisticId)
                            selected.unitOfMeasurement?.takeIf { it.isNotBlank() }?.let {
                                append("  ·  ")
                                append(it)
                            }
                        },
                        style = R1.labelMicro.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = TextUnit(10f, TextUnitType.Sp),
                        ),
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        text = if (ui.catalogueLoading) "Loading recorder catalogue…" else "Pick a statistic",
                        style = responsiveType(R1.body),
                        color = if (ui.catalogueLoading) R1.InkMuted else R1.Ink,
                    )
                    Text(
                        text = if (ui.available.isEmpty() && !ui.catalogueLoading)
                            "Recorder reported no statistics."
                        else
                            "${ui.available.size} series available",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = if (selected == null) "PICK" else "CHANGE",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentWarm,
            )
        }
    }
}

@Composable
private fun WindowChips(
    current: StatisticsViewModel.Window,
    onSelect: (StatisticsViewModel.Window) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        StatisticsViewModel.Window.entries.forEach { w ->
            R1Chip(
                text = w.label,
                variant = R1ChipVariant.Filter,
                selected = w == current,
                onClick = { onSelect(w) },
                contentDescription = "Window ${w.label}",
            )
        }
    }
}

@Composable
private fun PeriodChips(
    current: StatisticsViewModel.Period,
    window: StatisticsViewModel.Window,
    isAllowed: (StatisticsViewModel.Period) -> Boolean,
    onSelect: (StatisticsViewModel.Period) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        StatisticsViewModel.Period.entries.forEach { p ->
            // 5-minute buckets are only retained for short windows; gate the
            // chip rather than fire a fetch that HA returns empty for. Null
            // onClick reads as inert, matching the aggregation chip row.
            val allowed = isAllowed(p)
            R1Chip(
                text = p.label,
                variant = R1ChipVariant.Filter,
                selected = p == current && allowed,
                onClick = if (allowed) ({ onSelect(p) }) else null,
                contentDescription = if (allowed) {
                    "Period ${p.label}"
                } else {
                    "Period ${p.label}, unavailable for ${window.label} window"
                },
            )
        }
    }
}

@Composable
private fun AggregationChips(
    current: StatisticsViewModel.Aggregation,
    supported: Set<StatisticsViewModel.Aggregation>,
    onSelect: (StatisticsViewModel.Aggregation) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        StatisticsViewModel.Aggregation.entries.forEach { agg ->
            val enabled = agg in supported
            // Unsupported aggregations carry no tap target (onClick = null) so they read as
            // inert; supported ones are the standard filter toggle.
            R1Chip(
                text = agg.label,
                variant = R1ChipVariant.Filter,
                selected = agg == current && enabled,
                onClick = if (enabled) ({ onSelect(agg) }) else null,
                contentDescription = "Aggregation ${agg.label}",
            )
        }
    }
}

@Composable
private fun StatisticsChartPanel(vm: StatisticsViewModel, ui: StatisticsViewModel.UiState) {
    // seriesPoints allocates a fresh list each call; deriving it only when the
    // buckets or aggregation change keeps its identity stable so the
    // remember(points) chart projection below actually hits its cache instead of
    // reprojecting on every Canvas invalidation / scrub-state change.
    val points = remember(ui.buckets, ui.aggregation) { vm.seriesPoints(ui) }
    // Min/max envelope, only non-empty for measurement statistics with the
    // MEAN series selected (a temperature sensor's hourly spread). Folds the
    // band into the same vertical scale as the mean line below.
    val band = remember(ui.buckets, ui.selected, ui.aggregation) {
        if (ui.aggregation == StatisticsViewModel.Aggregation.MEAN) vm.bandPoints(ui) else emptyList()
    }
    val unit = ui.selected?.unitOfMeasurement?.takeIf { it.isNotBlank() }
    // The chart grows taller on roomier tiers (the band + trend earn the extra
    // vertical room on a 13in panel) while the R1 keeps its hand-tuned 180dp;
    // the y-axis label gutter widens the same way so scaled-up readings still fit.
    val dimens = rememberResponsiveDimens()
    val chartHeight = (180.dp.value * dimens.typeScale).dp
    val axisWidth = (56.dp.value * dimens.typeScale).dp
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        if (ui.seriesLoading && points.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(chartHeight),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        if (points.size < 2) {
            // One bucket can't draw a trend line, and zero means the recorder
            // had nothing for this aggregation/window. Distinguish the two so a
            // user staring at a flat panel knows whether to widen the window or
            // pick a different aggregation.
            Box(
                modifier = Modifier.fillMaxWidth().height(chartHeight),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (points.size == 1) {
                        "ONLY ONE BUCKET IN WINDOW · ${formatNum(points.first().value)}" +
                            (unit?.let { " $it" } ?: "")
                    } else {
                        "NO STATISTICS IN WINDOW"
                    },
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        // Hoist the projection out of the per-frame draw lambda, same trick
        // HistoryChartPanel uses. Keyed on the underlying timed-value list so
        // re-projection only runs when the buckets / aggregation actually
        // change, not on every Canvas invalidation.
        val proj = remember(points, band) {
            // Vertical scale spans both the line and the min/max envelope so
            // the band never spills past the chart edges. Time axis is keyed to
            // the line's span (the band shares the same buckets).
            var yMin0 = points.minOf { it.value }
            var yMax0 = points.maxOf { it.value }
            for (b in band) {
                if (b.min < yMin0) yMin0 = b.min
                if (b.max > yMax0) yMax0 = b.max
            }
            val tStart0 = points.first().timestamp
            val tEnd0 = points.last().timestamp
            val tSpan0 = Duration.between(tStart0, tEnd0).toMillis().coerceAtLeast(1L)
            val xs = FloatArray(points.size)
            val ysn = FloatArray(points.size)
            for (i in points.indices) {
                val p = points[i]
                xs[i] = Duration.between(tStart0, p.timestamp).toMillis().toFloat() / tSpan0
                ysn[i] = 1f - com.github.itskenny0.r1ha.ui.components.chartYFraction(p.value, yMin0, yMax0)
            }
            val bandXs = FloatArray(band.size)
            val bandLo = FloatArray(band.size)
            val bandHi = FloatArray(band.size)
            for (i in band.indices) {
                val b = band[i]
                bandXs[i] = Duration.between(tStart0, b.timestamp).toMillis().toFloat() / tSpan0
                bandHi[i] = 1f - com.github.itskenny0.r1ha.ui.components.chartYFraction(b.max, yMin0, yMax0)
                bandLo[i] = 1f - com.github.itskenny0.r1ha.ui.components.chartYFraction(b.min, yMin0, yMax0)
            }
            ChartProjection(xs, ysn, yMin0, yMax0, tStart0, tEnd0, tSpan0, bandXs, bandLo, bandHi)
        }
        val yMin = proj.yMin
        val yMax = proj.yMax
        val tStart = proj.tStart
        val tEnd = proj.tEnd
        val tSpan = proj.tSpan
        val zone = ZoneId.systemDefault()
        // Window-aware axis label format: the user's clock format for short
        // windows, day-of-month for multi-day spans. Matches HistoryChartPanel's
        // behaviour so the two surfaces print timestamps the same way.
        val use24h = com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock()
        val fmt = if (tSpan < Duration.ofHours(36).toMillis()) {
            DateTimeFormatter.ofPattern(
                com.github.itskenny0.r1ha.ui.components.clockPattern(use24h),
                java.util.Locale.US,
            ).withZone(zone)
        } else {
            DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US).withZone(zone)
        }
        // Tap-to-scrub state: nullable Int index into proj.xsNorm. Press &
        // hold reveals the precise bucket value; release clears it. Same
        // affordance HistoryChartPanel offers.
        val scrubIdx = remember(proj) { mutableStateOf<Int?>(null) }
        // The Canvas is opaque to a screen reader; spell the trend out so it
        // still carries meaning. Built once per projection from the plotted
        // span and range rather than per frame.
        val unitSpoken = unit?.let { " $it" } ?: ""
        val chartDescription = remember(proj, ui.aggregation) {
            "${ui.aggregation.label} trend, ${points.size} buckets, " +
                "from ${formatNum(points.first().value)}$unitSpoken at ${fmt.format(tStart)} " +
                "to ${formatNum(points.last().value)}$unitSpoken at ${fmt.format(tEnd)}, " +
                "range ${formatNum(yMin)}$unitSpoken to ${formatNum(yMax)}$unitSpoken"
        }
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(chartHeight)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface)
                        .semantics { contentDescription = chartDescription }
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .pointerInput(proj) {
                            val canvasW = size.width.toFloat().coerceAtLeast(1f)
                            // Press-and-drag scrub: set the index on press, then
                            // follow the finger as it slides so the user can read
                            // adjacent buckets without lifting and re-tapping. The
                            // old press-and-hold-only handler ignored drag.
                            fun nearest(x: Float): Int {
                                val target = (x / canvasW).coerceIn(0f, 1f)
                                var bestI = 0
                                var bestD = Float.POSITIVE_INFINITY
                                for (i in proj.xsNorm.indices) {
                                    val d = kotlin.math.abs(proj.xsNorm[i] - target)
                                    if (d < bestD) {
                                        bestD = d
                                        bestI = i
                                    }
                                }
                                return bestI
                            }
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                scrubIdx.value = nearest(down.position.x)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id }
                                        ?: event.changes.firstOrNull()
                                    if (change == null || !change.pressed) break
                                    if (change.positionChange() != Offset.Zero) change.consume()
                                    scrubIdx.value = nearest(change.position.x)
                                }
                                scrubIdx.value = null
                            }
                        },
                ) {
                    val w = size.width
                    val h = size.height
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 1f,
                    )
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1f,
                    )
                    // Faint min/max envelope behind the mean line, mirroring
                    // HistoryChartPanel's band. Drawn as a filled polygon: down
                    // the max edge, back along the min edge.
                    val bx = proj.bandXsNorm
                    if (bx.size >= 2) {
                        val hi = proj.bandHiNorm
                        val lo = proj.bandLoNorm
                        val path = androidx.compose.ui.graphics.Path()
                        path.moveTo(bx[0] * w, hi[0] * h)
                        for (i in 1 until bx.size) path.lineTo(bx[i] * w, hi[i] * h)
                        for (i in bx.size - 1 downTo 0) path.lineTo(bx[i] * w, lo[i] * h)
                        path.close()
                        drawPath(path = path, color = R1.AccentWarm.copy(alpha = 0.14f))
                    }
                    val xs = proj.xsNorm
                    val ysn = proj.ysNorm
                    val n = xs.size
                    for (i in 0 until n - 1) {
                        drawLine(
                            color = R1.AccentWarm,
                            start = Offset(xs[i] * w, ysn[i] * h),
                            end = Offset(xs[i + 1] * w, ysn[i + 1] * h),
                            strokeWidth = 2f,
                            cap = StrokeCap.Round,
                        )
                    }
                    drawCircle(
                        color = R1.AccentWarm,
                        radius = 3f,
                        center = Offset(xs[0] * w, ysn[0] * h),
                    )
                    drawCircle(
                        color = R1.AccentWarm,
                        radius = 3f,
                        center = Offset(xs[n - 1] * w, ysn[n - 1] * h),
                    )
                    val si = scrubIdx.value
                    if (si != null && si in 0 until n) {
                        val sx = xs[si] * w
                        val sy = ysn[si] * h
                        drawLine(
                            color = R1.InkSoft,
                            start = Offset(sx, 0f),
                            end = Offset(sx, h),
                            strokeWidth = 1f,
                        )
                        drawCircle(
                            color = R1.Ink,
                            radius = 4f,
                            center = Offset(sx, sy),
                        )
                    }
                }
                Spacer(Modifier.height(R1.space.xs))
                val si = scrubIdx.value
                if (si != null && si in points.indices) {
                    val sample = points[si]
                    Row {
                        Text(
                            text = fmt.format(sample.timestamp),
                            style = responsiveType(R1.labelMicro),
                            color = R1.Ink,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                        )
                        Text(
                            text = "${formatNum(sample.value)}${unit?.let { " $it" } ?: ""}",
                            style = responsiveType(R1.labelMicro),
                            color = R1.AccentWarm,
                            maxLines = 1,
                        )
                    }
                } else {
                    Row {
                        Text(
                            text = fmt.format(tStart),
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = fmt.format(tEnd), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.width(R1.space.s))
            Column(
                modifier = Modifier.width(axisWidth),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatNum(yMax)}${unit?.let { " $it" } ?: ""}",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatNum(yMin)}${unit?.let { " $it" } ?: ""}",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SummaryPanel(vm: StatisticsViewModel, ui: StatisticsViewModel.UiState) {
    val unit = ui.selected?.unitOfMeasurement?.takeIf { it.isNotBlank() }
    // Type-aware summary: metered statistics headline the window total
    // (consumption), measurement statistics headline avg with min/max.
    val summary = remember(ui.buckets, ui.aggregation, ui.selected) { vm.windowSummary(ui) }
    fun withUnit(v: Double): String = "${formatNum(v)}${unit?.let { " $it" } ?: ""}"
    val metered = summary.kind == StatisticsViewModel.StatKind.METERED
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Text(
            text = "SUMMARY · ${ui.aggregation.label} · ${ui.window.label} · ${ui.period.label}",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
        )
        if (metered) {
            // When the SUM (cumulative) series is on screen the chart draws the
            // ever-growing running total, so the summary leads with the latest
            // cumulative reading (the chart's last point) to keep the two in
            // agreement; the per-bucket TOTAL / PER BUCKET / PEAK still describe
            // consumption over the window. Under CHANGE the chart already shows
            // per-bucket deltas, so the consumption headline matches without an
            // extra cumulative row.
            if (ui.aggregation == StatisticsViewModel.Aggregation.SUM) {
                SummaryRow(
                    label = "CUMULATIVE",
                    value = summary.current?.let { withUnit(it) } ?: "--",
                    accent = R1.Ink,
                )
            }
            // Headline a metered series by how much it counted over the window;
            // the cumulative sum is rarely what the user wants to compare.
            SummaryRow(
                label = "TOTAL",
                value = summary.total?.let { withUnit(it) } ?: "--",
                accent = R1.AccentGreen,
            )
            SummaryRow(
                label = "PER BUCKET",
                value = summary.avg?.let { withUnit(it) } ?: "--",
                accent = R1.AccentNeutral,
            )
            SummaryRow(
                label = "PEAK",
                value = summary.max?.let { withUnit(it) } ?: "--",
                accent = R1.AccentWarm,
            )
        } else {
            SummaryRow(
                label = "CURRENT",
                value = summary.current?.let { withUnit(it) } ?: "--",
                accent = R1.Ink,
            )
            SummaryRow(
                label = "MIN",
                value = summary.min?.let { withUnit(it) } ?: "--",
                accent = R1.AccentCool,
            )
            SummaryRow(
                label = "MAX",
                value = summary.max?.let { withUnit(it) } ?: "--",
                accent = R1.AccentWarm,
            )
            SummaryRow(
                label = "AVG",
                value = summary.avg?.let { withUnit(it) } ?: "--",
                accent = R1.AccentNeutral,
            )
        }
        SummaryRow(
            label = "BUCKETS",
            value = "${summary.count}",
            accent = R1.InkSoft,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    val dimens = rememberResponsiveDimens()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            modifier = Modifier.width((80.dp.value * dimens.typeScale).dp),
        )
        Text(
            text = value,
            style = responsiveType(R1.bodyEmph),
            color = accent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun EmptyHero() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "NO STATISTIC PICKED", style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
        Spacer(Modifier.size(R1.space.s))
        Text(
            text = "Tap the STATISTIC card above to choose any sensor or " +
                "meter the recorder is tracking.",
            style = responsiveType(R1.body),
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun NoPlottablePanel() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.l),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Text(text = "NOTHING TO PLOT", style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
        Text(
            text = "The recorder tracks this statistic with neither a mean nor a sum, " +
                "so there's no series any aggregation can chart. It may still feed " +
                "energy totals or diagnostics inside Home Assistant.",
            style = responsiveType(R1.body),
            color = R1.InkSoft,
        )
        Text(
            text = "Pick a different statistic, or one that shows a MEAN or SUM badge in the picker.",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun ErrorPanel(message: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusRed.copy(alpha = 0.12f))
            .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Text(text = message, style = responsiveType(R1.labelMicro), color = R1.StatusRed)
    }
}

/**
 * Modal picker overlay. Filters the recorder catalogue by substring on
 * both the friendly name and the statistic_id, so a user who only
 * remembers "kitchen" finds `sensor.kitchen_temperature` and a user who
 * remembers "kwh" finds every meter.
 *
 * Mirrors EntityPickerSheet's structure (translucent backdrop, centred
 * card, search field, bounded list) but is local to this surface
 * because the row shape differs: we want to surface has_mean / has_sum
 * badges so the user knows up front which aggregations a series
 * supports.
 */
@Composable
private fun StatisticPickerSheet(
    rows: List<StatisticId>,
    onPick: (StatisticId) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    var query by remember { mutableStateOf("") }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l),
        ) {
            Text(text = "PICK STATISTIC", style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = "${rows.size} series from HA's recorder",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(R1.space.m))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "temperature, kwh, kitchen…",
                        monospace = false,
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(R1.space.s))
                    // 48 dp tap surface meets Android's interactive-target guidance;
                    // the visible ✕ stays glyph-sized via the inner Text.
                    Box(
                        modifier = Modifier
                            .size(R1.MinTarget)
                            .r1Pressable(onClick = { query = "" }, contentDescription = "Clear search"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(R1.space.s))
            val filtered = remember(query, rows) {
                val q = query.trim().lowercase()
                if (q.isBlank()) rows
                else rows.filter {
                    (it.name?.lowercase()?.contains(q) == true) ||
                        it.statisticId.lowercase().contains(q)
                }
            }
            if (filtered.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (rows.isEmpty())
                            "Recorder reported no statistics. Enable the recorder integration in HA."
                        else
                            "No matches for '${query}'.",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(360.dp),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(items = filtered, key = { it.statisticId }) { row ->
                        StatisticPickRow(row = row, onPick = { onPick(row) })
                    }
                }
            }
            Spacer(Modifier.height(R1.space.s))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onDismiss, contentDescription = "Cancel")
                    .heightIn(min = R1.MinTarget)
                    .padding(vertical = R1.space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "CANCEL", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun StatisticPickRow(row: StatisticId, onPick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onPick, contentDescription = "Pick ${row.statisticId}")
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            imageVector = R1Icons.forEntity(row.statisticId),
            contentDescription = null,
            tint = R1.AccentNeutral,
            modifier = Modifier.padding(end = R1.space.s).size(18.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name?.takeIf { it.isNotBlank() } ?: row.statisticId,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = buildString {
                    append(row.statisticId)
                    row.unitOfMeasurement?.takeIf { it.isNotBlank() }?.let {
                        append("  ·  ")
                        append(it)
                    }
                },
                style = R1.labelMicro.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = TextUnit(10f, TextUnitType.Sp),
                ),
                color = R1.InkSoft,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        if (row.hasMean) R1Chip(text = "MEAN", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
        if (row.hasMean && row.hasSum) Spacer(Modifier.width(R1.space.xs))
        if (row.hasSum) R1Chip(text = "SUM", variant = R1ChipVariant.Pill, tone = R1.AccentGreen)
    }
}

/** Drop unhelpful trailing decimals: 23.0 -> "23", 23.45 -> "23.45".
 *  Delegates to the shared formatter so the chart and summary print the
 *  same way and stay covered by the formatting unit test. */
private fun formatNum(v: Double): String = formatStatNum(v)

/**
 * Pre-projected chart data: same shape HistoryChartPanel uses, kept
 * private to this file so the two screens can evolve their tweaks
 * independently. FloatArrays (not List<Offset>) so the draw phase is
 * allocation-free.
 */
private data class ChartProjection(
    val xsNorm: FloatArray,
    val ysNorm: FloatArray,
    val yMin: Double,
    val yMax: Double,
    val tStart: java.time.Instant,
    val tEnd: java.time.Instant,
    val tSpan: Long,
    /** Min/max envelope, empty for non-measurement series. */
    val bandXsNorm: FloatArray = FloatArray(0),
    val bandLoNorm: FloatArray = FloatArray(0),
    val bandHiNorm: FloatArray = FloatArray(0),
)
