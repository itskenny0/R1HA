package com.github.itskenny0.r1ha.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Duration
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * History drill-in surface — full-screen view of one entity's
 * state-change history. Pairs with the per-card sparkline on
 * SensorCard, which previews 24 h at 72 dp; this surface is what the
 * user gets when they want a closer look (longer window, larger chart,
 * numeric summary).
 *
 * The chart itself is a hand-drawn Canvas — same line-stroke
 * conventions as SensorHistoryChart, but bigger (180 dp tall), with
 * explicit axis labels (start time, mid, end), a horizontal mid-line
 * for orientation, and a faint band marking the min..max envelope.
 *
 * Time-window picker chips at the top flip between 1 h / 6 h / 24 h /
 * 7 d; each selection re-fetches via /api/history/period/<since> with
 * the new window.
 */
@Composable
fun HistoryScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    entityId: String,
    onBack: () -> Unit,
) {
    val parsedId = remember(entityId) { runCatching { EntityId(entityId) }.getOrNull() }
    if (parsedId == null) {
        // Defensive — invalid entity_id (shouldn't happen via legitimate
        // nav, but a deep-link could try). Surface a clean error rather
        // than crashing the VM factory.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(R1.Bg)
                .systemBarsPadding(),
        ) {
            R1TopBar(title = "HISTORY", onBack = onBack)
            Box(modifier = Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    text = "Invalid entity_id: $entityId",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
        }
        return
    }
    val vm: HistoryViewModel = viewModel(
        key = entityId,
        factory = HistoryViewModel.factory(haRepository, parsedId),
    )
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    LaunchedEffect(entityId) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = ui.displayName.uppercase().take(22),
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = entityId,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                WindowChips(current = ui.window, onSelect = { vm.setWindow(it) })
                HistoryChartPanel(ui)
                SummaryPanel(ui)
                RewindPanel(ui)
                // Surface refresh errors even when the chart still has stale points; the
                // prior gate of `ui.points.isEmpty()` silently swallowed errors during
                // routine re-fetches, so a user staring at an old line had no way to
                // know the refresh failed.
                if (ui.error != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.StatusRed.copy(alpha = 0.12f))
                            .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                    ) {
                        Text(
                            text = ui.error ?: "",
                            style = R1.labelMicro,
                            color = R1.StatusRed,
                        )
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun WindowChips(
    current: HistoryViewModel.Window,
    onSelect: (HistoryViewModel.Window) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HistoryViewModel.Window.entries.forEach { w ->
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
private fun HistoryChartPanel(ui: HistoryViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        if (ui.loading && ui.points.isEmpty()) {
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
        if (ui.points.size < 2) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "NOT ENOUGH HISTORY YET",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        // Hoist the numeric projection out of the per-frame draw lambda. Previously
        // the Canvas block recomputed numeric / yMin / yMax / tStart / tSpan and
        // allocated a fresh List<Offset> on every invalidation. With remember
        // keyed on ui.points, projection runs once per data refresh and the
        // draw phase becomes a tight loop over precomputed normalized x/y.
        val proj = androidx.compose.runtime.remember(ui.points) {
            val numeric = ui.points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
            if (numeric.size < 2) return@remember null
            val ys = numeric.map { it.second }
            val yMin0 = ys.min()
            val yMax0 = ys.max()
            val yRange0 = (yMax0 - yMin0).takeIf { it > 1e-9 } ?: 1.0
            val tStart0 = numeric.first().first
            val tEnd0 = numeric.last().first
            val tSpan0 = Duration.between(tStart0, tEnd0).toMillis().coerceAtLeast(1L)
            val xs = FloatArray(numeric.size)
            val ysn = FloatArray(numeric.size)
            for (i in numeric.indices) {
                val (ts, v) = numeric[i]
                xs[i] = (Duration.between(tStart0, ts).toMillis().toFloat() / tSpan0)
                ysn[i] = 1f - (((v - yMin0) / yRange0).toFloat())
            }
            ChartProjection(xs, ysn, yMin0, yMax0, tStart0, tEnd0, tSpan0)
        }
        if (proj == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "HISTORY ISN'T NUMERIC",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        val yMin = proj.yMin
        val yMax = proj.yMax
        val tStart = proj.tStart
        val tEnd = proj.tEnd
        val tSpan = proj.tSpan
        val zone = ZoneId.systemDefault()
        // Pick an axis-label format that scales with the window — for
        // sub-day windows we show HH:mm; for multi-day windows we drop
        // the colon for compactness.
        val fmt = if (tSpan < Duration.ofHours(36).toMillis()) {
            DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        } else {
            DateTimeFormatter.ofPattern("d MMM").withZone(zone)
        }
        // Tap-to-scrub state: nullable Int index into proj.xsNorm. Press-and-hold
        // on the chart sets this to the nearest sample index; release clears it.
        // Drawing a vertical guide + dot at the scrubbed sample plus a textual
        // readout below the chart lets users read a precise value off the line
        // without dropping into the table view.
        val scrubIdx = androidx.compose.runtime.remember(proj) {
            androidx.compose.runtime.mutableStateOf<Int?>(null)
        }
        Row {
            // Y-axis labels on the right edge — min/max with units.
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
                                    // Linear scan over normalized xs; chart sample count
                                    // is bounded by HistoryVM downsampling (~250).
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
                    // Faint horizontal mid-line for orientation
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h * 0.5f),
                        end = Offset(w, h * 0.5f),
                        strokeWidth = 1f,
                    )
                    // Faint baseline gridline
                    drawLine(
                        color = R1.Hairline,
                        start = Offset(0f, h - 1f),
                        end = Offset(w, h - 1f),
                        strokeWidth = 1f,
                    )
                    // Pre-projected normalized points scale by canvas size each draw.
                    // Zero allocation in the draw phase; segment count is bounded by
                    // the History VM's downsample step (the lambda below was previously
                    // building a List<Offset> of length n on every invalidation).
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
                    // Scrub guide + sample dot. Drawn on top of the line so it
                    // remains visible against the warm accent stroke.
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
                // X-axis labels collapse into a "press-to-read" readout while the
                // user is scrubbing. Two derived references (the value at the
                // scrub index, and the closest sample timestamp) make the chart
                // readable without a full table jump.
                val si = scrubIdx.value
                if (si != null && si in proj.xsNorm.indices) {
                    val sample = ui.points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
                        .getOrNull(si)
                    if (sample != null) {
                        Row {
                            Text(
                                text = fmt.format(sample.first),
                                style = R1.labelMicro,
                                color = R1.Ink,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = "${formatNum(sample.second)}${ui.unit?.let { " $it" } ?: ""}",
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
            // Inline Y-axis labels — just min and max, anchored to
            // top and bottom respectively. The mid-line we drew in
            // the canvas reads as the midpoint without a label.
            Column(modifier = Modifier.width(56.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(
                    text = "${formatNum(yMax)}${ui.unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${formatNum(yMin)}${ui.unit?.let { " $it" } ?: ""}",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun SummaryPanel(ui: HistoryViewModel.UiState) {
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
            text = "SUMMARY · ${ui.window.label}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        // 4-cell summary grid: CURRENT / MIN / MAX / AVG. Each cell
        // shows the readout with the unit appended; non-numeric
        // entities (text sensors) suppress the numeric rows.
        SummaryRow(
            label = "CURRENT",
            value = ui.current?.let { "$it${ui.unit?.let { u -> " $u" } ?: ""}" } ?: "—",
            accent = R1.Ink,
        )
        if (ui.min != null) SummaryRow(
            label = "MIN",
            value = "${formatNum(ui.min)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentCool,
        )
        if (ui.max != null) SummaryRow(
            label = "MAX",
            value = "${formatNum(ui.max)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentWarm,
        )
        if (ui.avg != null) SummaryRow(
            label = "AVG",
            value = "${formatNum(ui.avg)}${ui.unit?.let { " $it" } ?: ""}",
            accent = R1.AccentNeutral,
        )
        SummaryRow(
            label = "SAMPLES",
            value = "${ui.points.size}",
            accent = R1.InkSoft,
        )
    }
}

/**
 * "What was this reading N minutes ago?" — at-a-glance rewind for sensors,
 * separate from the chart-scrub interaction. Picks a small set of preset
 * offsets so a tap gives an instant answer without scrubbing or zooming.
 * Offsets that fall outside the loaded window are skipped (no point
 * showing "1d ago" when only 1h of data is loaded) so the panel only
 * surfaces meaningful values.
 */
@Composable
private fun RewindPanel(ui: HistoryViewModel.UiState) {
    val points = ui.points
    if (points.size < 2) return
    val now = java.time.Instant.now()
    // Curated offsets. Capped to the loaded window so the user doesn't see
    // a row that says "1d ago — —" when only an hour of data is available.
    val candidateOffsets = listOf(
        "15 MIN" to 15L * 60,
        "1 HR" to 60L * 60,
        "6 HR" to 6L * 3600,
        "24 HR" to 24L * 3600,
    )
    val windowSeconds = java.time.Duration.between(points.first().timestamp, points.last().timestamp).seconds
    val applicable = candidateOffsets.filter { it.second <= windowSeconds }
    if (applicable.isEmpty()) return
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(text = "REWIND", style = R1.labelMicro, color = R1.InkSoft)
        for ((label, offsetSec) in applicable) {
            val target = now.minusSeconds(offsetSec)
            // Pick the latest point at or before the target time, falling back
            // to the earliest if nothing is found (which shouldn't happen given
            // the applicable filter above, but stays defensive).
            val prior = points.filter { !it.timestamp.isAfter(target) }
                .maxByOrNull { it.timestamp }
                ?: points.first()
            val unit = ui.unit?.let { " $it" } ?: ""
            val displayValue = prior.numeric?.let { formatNum(it) + unit }
                ?: prior.state.takeIf { it.isNotBlank() }
                ?: "—"
            SummaryRow(label = label, value = displayValue, accent = R1.InkSoft)
        }
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

/** Drop unhelpful trailing decimals: 23.0 → "23", 23.45 → "23.45". */
private fun formatNum(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 1e-9) "${v.toLong()}"
    else "%.2f".format(v)

/**
 * Pre-projected chart data: x/y in [0..1] space so the per-frame Canvas draw
 * can scale by canvas size without re-running the full mapNotNull + min/max
 * + per-point projection pipeline on every invalidation. Stored as FloatArrays
 * (not List<Offset>) so iteration is allocation-free.
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
