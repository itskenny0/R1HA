package com.github.itskenny0.r1ha.feature.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
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
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
                                if (ui.selected != null) vm.refreshSeries() else vm.loadCatalogue()
                            })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (ui.seriesLoading || ui.catalogueLoading) "…" else "REFRESH",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                },
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StatisticPickerBar(ui = ui, onOpen = { vm.openPicker() })
                if (ui.selected != null) {
                    WindowChips(current = ui.window, onSelect = { vm.setWindow(it) })
                    AggregationChips(
                        current = ui.aggregation,
                        supported = vm.supportedAggregations(ui),
                        onSelect = { vm.setAggregation(it) },
                    )
                    StatisticsChartPanel(vm = vm, ui = ui)
                    SummaryPanel(vm = vm, ui = ui)
                } else if (!ui.catalogueLoading && ui.catalogueError == null) {
                    EmptyHero()
                }
                if (ui.catalogueError != null && ui.available.isEmpty()) {
                    ErrorPanel(message = ui.catalogueError ?: "")
                }
                if (ui.seriesError != null) {
                    ErrorPanel(message = ui.seriesError ?: "")
                }
                Spacer(Modifier.size(24.dp))
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
            .r1Pressable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = "STATISTIC", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.size(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                if (selected != null) {
                    Text(
                        text = selected.name?.takeIf { it.isNotBlank() } ?: selected.statisticId,
                        style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                        color = R1.Ink,
                        maxLines = 1,
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
                    )
                } else {
                    Text(
                        text = if (ui.catalogueLoading) "Loading recorder catalogue…" else "Pick a statistic",
                        style = R1.body,
                        color = if (ui.catalogueLoading) R1.InkMuted else R1.Ink,
                    )
                    Text(
                        text = if (ui.available.isEmpty() && !ui.catalogueLoading)
                            "Recorder reported no statistics."
                        else
                            "${ui.available.size} series available",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (selected == null) "PICK" else "CHANGE",
                style = R1.labelMicro,
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatisticsViewModel.Window.entries.forEach { w ->
            val active = w == current
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(w) })
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = w.label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
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
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        StatisticsViewModel.Aggregation.entries.forEach { agg ->
            val enabled = agg in supported
            val active = agg == current && enabled
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(
                        when {
                            active -> R1.AccentWarm
                            enabled -> R1.SurfaceMuted
                            else -> R1.Surface
                        },
                    )
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { if (enabled) onSelect(agg) }, hapticOnClick = enabled)
                    .padding(vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = agg.label,
                    style = R1.labelMicro,
                    color = when {
                        active -> R1.Bg
                        enabled -> R1.InkSoft
                        else -> R1.InkMuted
                    },
                )
            }
        }
    }
}

@Composable
private fun StatisticsChartPanel(vm: StatisticsViewModel, ui: StatisticsViewModel.UiState) {
    val points = vm.seriesPoints(ui)
    val unit = ui.selected?.unitOfMeasurement?.takeIf { it.isNotBlank() }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (ui.seriesLoading && points.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
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
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "NO STATISTICS IN WINDOW",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        // Hoist the projection out of the per-frame draw lambda, same trick
        // HistoryChartPanel uses. Keyed on the underlying timed-value list so
        // re-projection only runs when the buckets / aggregation actually
        // change, not on every Canvas invalidation.
        val proj = remember(points) {
            val ys = points.map { it.value }
            val yMin0 = ys.min()
            val yMax0 = ys.max()
            val yRange0 = (yMax0 - yMin0).takeIf { it > 1e-9 } ?: 1.0
            val tStart0 = points.first().timestamp
            val tEnd0 = points.last().timestamp
            val tSpan0 = Duration.between(tStart0, tEnd0).toMillis().coerceAtLeast(1L)
            val xs = FloatArray(points.size)
            val ysn = FloatArray(points.size)
            for (i in points.indices) {
                val p = points[i]
                xs[i] = (Duration.between(tStart0, p.timestamp).toMillis().toFloat() / tSpan0)
                ysn[i] = 1f - (((p.value - yMin0) / yRange0).toFloat())
            }
            ChartProjection(xs, ysn, yMin0, yMax0, tStart0, tEnd0, tSpan0)
        }
        val yMin = proj.yMin
        val yMax = proj.yMax
        val tStart = proj.tStart
        val tEnd = proj.tEnd
        val tSpan = proj.tSpan
        val zone = ZoneId.systemDefault()
        // Window-aware axis label format: HH:mm for short windows, day-of-month
        // for multi-day spans. Matches HistoryChartPanel's behaviour so the two
        // surfaces print timestamps the same way.
        val fmt = if (tSpan < Duration.ofHours(36).toMillis()) {
            DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        } else {
            DateTimeFormatter.ofPattern("d MMM").withZone(zone)
        }
        // Tap-to-scrub state: nullable Int index into proj.xsNorm. Press &
        // hold reveals the precise bucket value; release clears it. Same
        // affordance HistoryChartPanel offers.
        val scrubIdx = remember(proj) { mutableStateOf<Int?>(null) }
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .pointerInput(proj) {
                            val canvasW = size.width.toFloat()
                            detectTapGestures(
                                onPress = { pressOffset ->
                                    val target = (pressOffset.x / canvasW).coerceIn(0f, 1f)
                                    var bestI = 0
                                    var bestD = Float.POSITIVE_INFINITY
                                    for (i in proj.xsNorm.indices) {
                                        val d = kotlin.math.abs(proj.xsNorm[i] - target)
                                        if (d < bestD) {
                                            bestD = d
                                            bestI = i
                                        }
                                    }
                                    scrubIdx.value = bestI
                                    tryAwaitRelease()
                                    scrubIdx.value = null
                                },
                            )
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
                Spacer(Modifier.height(4.dp))
                val si = scrubIdx.value
                if (si != null && si in points.indices) {
                    val sample = points[si]
                    Row {
                        Text(
                            text = fmt.format(sample.timestamp),
                            style = R1.labelMicro,
                            color = R1.Ink,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = "${formatNum(sample.value)}${unit?.let { " $it" } ?: ""}",
                            style = R1.labelMicro,
                            color = R1.AccentWarm,
                        )
                    }
                } else {
                    Row {
                        Text(
                            text = fmt.format(tStart),
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            modifier = Modifier.weight(1f),
                        )
                        Text(text = fmt.format(tEnd), style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(
                modifier = Modifier.width(56.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "${formatNum(yMax)}${unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatNum(yMin)}${unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SummaryPanel(vm: StatisticsViewModel, ui: StatisticsViewModel.UiState) {
    val points = vm.seriesPoints(ui)
    val unit = ui.selected?.unitOfMeasurement?.takeIf { it.isNotBlank() }
    val values = points.map { it.value }
    val current = values.lastOrNull()
    val min = values.minOrNull()
    val max = values.maxOrNull()
    val avg = if (values.isNotEmpty()) values.sum() / values.size else null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = "SUMMARY · ${ui.aggregation.label} · ${ui.window.label}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        SummaryRow(
            label = "CURRENT",
            value = current?.let { "${formatNum(it)}${unit?.let { u -> " $u" } ?: ""}" } ?: "--",
            accent = R1.Ink,
        )
        SummaryRow(
            label = "MIN",
            value = min?.let { "${formatNum(it)}${unit?.let { u -> " $u" } ?: ""}" } ?: "--",
            accent = R1.AccentCool,
        )
        SummaryRow(
            label = "MAX",
            value = max?.let { "${formatNum(it)}${unit?.let { u -> " $u" } ?: ""}" } ?: "--",
            accent = R1.AccentWarm,
        )
        SummaryRow(
            label = "AVG",
            value = avg?.let { "${formatNum(it)}${unit?.let { u -> " $u" } ?: ""}" } ?: "--",
            accent = R1.AccentNeutral,
        )
        SummaryRow(
            label = "BUCKETS",
            value = "${points.size}",
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.width(80.dp))
        Text(
            text = value,
            style = R1.body.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
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
            .padding(horizontal = 16.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "NO STATISTIC PICKED", style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.size(6.dp))
        Text(
            text = "Tap the STATISTIC card above to choose any sensor or " +
                "meter the recorder is tracking.",
            style = R1.body,
            color = R1.InkSoft,
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(text = message, style = R1.labelMicro, color = R1.StatusRed)
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
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(14.dp),
        ) {
            Text(text = "PICK STATISTIC", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "${rows.size} series from HA's recorder",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(10.dp))
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
                    Spacer(Modifier.width(6.dp))
                    // 48 dp tap surface meets Android's interactive-target guidance;
                    // the visible ✕ stays glyph-sized via the inner Text.
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .r1Pressable(onClick = { query = "" }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
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
                        style = R1.labelMicro,
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
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(items = filtered, key = { it.statisticId }) { row ->
                        StatisticPickRow(row = row, onPick = { onPick(row) })
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onDismiss)
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "CANCEL", style = R1.labelMicro, color = R1.InkSoft)
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
            .r1Pressable(onClick = onPick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name?.takeIf { it.isNotBlank() } ?: row.statisticId,
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
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
            )
        }
        Spacer(Modifier.width(6.dp))
        if (row.hasMean) Badge(label = "MEAN", accent = R1.AccentCool)
        if (row.hasMean && row.hasSum) Spacer(Modifier.width(4.dp))
        if (row.hasSum) Badge(label = "SUM", accent = R1.AccentGreen)
    }
}

@Composable
private fun Badge(label: String, accent: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeS)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = accent)
    }
}

/** Drop unhelpful trailing decimals: 23.0 → "23", 23.45 → "23.45". Mirrors
 *  HistoryScreen's formatter so the two surfaces print numbers identically. */
private fun formatNum(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 1e-9) "${v.toLong()}"
    else "%.2f".format(v)

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
)
