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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.produceState
import androidx.compose.ui.focus.FocusRequester
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Duration
import java.time.Instant
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
    // Overlay entity picker visibility. When set, a full-screen numeric
    // entity picker sheet floats above the chart so the user can add
    // another series.
    var pickerOpen by remember { mutableStateOf(false) }
    Box(modifier = Modifier.fillMaxSize()) {
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
                OverlayLegend(
                    ui = ui,
                    onAdd = { pickerOpen = true },
                    onRemove = { vm.removeEntity(it) },
                )
                // Per-series numeric summary + rewind only apply to the
                // primary entity. When extra series are overlaid they're
                // summarized in the legend instead, so the single-entity
                // summary stays focused on the entity the user drilled into.
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
        // Numeric entity picker for adding overlay series. Floats above the
        // whole screen; dismiss restores the chart untouched.
        if (pickerOpen) {
            val existing = remember(ui.series) { ui.series.map { it.entityId.value }.toSet() }
            NumericEntityPickerSheet(
                haRepository = haRepository,
                excludeIds = existing,
                onPick = { picked ->
                    runCatching { EntityId(picked) }.getOrNull()?.let { vm.addEntity(it) }
                    pickerOpen = false
                },
                onDismiss = { pickerOpen = false },
            )
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

/** Series accent palette, indexed by Series.colorIndex. Distinct hues so
 *  overlaid lines stay separable; capped at MAX_SERIES entries. */
internal val SERIES_COLORS: List<Color> = listOf(
    R1.AccentWarm,
    R1.AccentCool,
    R1.AccentGreen,
    R1.StatusAmber,
    R1.AccentNeutral,
)

internal fun seriesColor(colorIndex: Int): Color =
    SERIES_COLORS.getOrElse(colorIndex) { R1.AccentNeutral }

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
        val anyPoints = ui.series.any { it.points.isNotEmpty() }
        if (ui.loading && !anyPoints) {
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
        // Project every series onto a shared time axis. Each series
        // normalizes to ITS OWN min/max (per-series vertical scale) so
        // entities with different units / magnitudes overlay legibly on a
        // single 0..1 axis; the absolute values live in the legend. The
        // x-axis is shared: all series map onto the union [tStart, tEnd].
        val multi = androidx.compose.runtime.remember(ui.series) {
            buildMultiProjection(ui.series)
        }
        if (multi == null) {
            Box(
                modifier = Modifier.fillMaxWidth().height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.isOverlay) "NO NUMERIC HISTORY YET" else "NOT ENOUGH HISTORY YET",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            return@Column
        }
        val tStart = multi.tStart
        val tEnd = multi.tEnd
        val tSpan = multi.tSpan
        val zone = ZoneId.systemDefault()
        // Pick an axis-label format that scales with the window — for
        // sub-day windows we show HH:mm; for multi-day windows we drop
        // the colon for compactness.
        val fmt = if (tSpan < Duration.ofHours(36).toMillis()) {
            DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        } else {
            DateTimeFormatter.ofPattern("d MMM").withZone(zone)
        }
        // Tap-to-scrub: store the scrubbed x as a fraction [0..1] of the
        // shared time axis. Per-series nearest-sample lookup happens at
        // read time so each overlaid line reports its own value. Press-
        // and-hold sets the fraction; release clears it.
        val scrubX = androidx.compose.runtime.remember(multi) {
            androidx.compose.runtime.mutableStateOf<Float?>(null)
        }
        val single = multi.series.size == 1
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Canvas(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(R1.Surface)
                        .padding(horizontal = 6.dp, vertical = 6.dp)
                        .pointerInput(multi) {
                            val canvasW = size.width.toFloat()
                            detectTapGestures(
                                onPress = { pressOffset ->
                                    scrubX.value = (pressOffset.x / canvasW).coerceIn(0f, 1f)
                                    tryAwaitRelease()
                                    scrubX.value = null
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
                    // Draw each series with its accent. Pre-projected normalized
                    // points scale by canvas size each draw; zero allocation in
                    // the draw phase.
                    for (s in multi.series) {
                        val color = seriesColor(s.colorIndex)
                        val xs = s.xsNorm
                        val ysn = s.ysNorm
                        val n = xs.size
                        for (i in 0 until n - 1) {
                            drawLine(
                                color = color,
                                start = Offset(xs[i] * w, ysn[i] * h),
                                end = Offset(xs[i + 1] * w, ysn[i + 1] * h),
                                strokeWidth = 2f,
                                cap = StrokeCap.Round,
                            )
                        }
                        if (n > 0) {
                            drawCircle(color = color, radius = 3f, center = Offset(xs[0] * w, ysn[0] * h))
                            drawCircle(color = color, radius = 3f, center = Offset(xs[n - 1] * w, ysn[n - 1] * h))
                        }
                    }
                    // Scrub guide + per-series sample dots. Drawn on top.
                    val frac = scrubX.value
                    if (frac != null) {
                        val sx = frac * w
                        drawLine(
                            color = R1.InkSoft,
                            start = Offset(sx, 0f),
                            end = Offset(sx, h),
                            strokeWidth = 1f,
                        )
                        for (s in multi.series) {
                            val idx = nearestIndex(s.xsNorm, frac)
                            if (idx >= 0) {
                                drawCircle(
                                    color = seriesColor(s.colorIndex),
                                    radius = 4f,
                                    center = Offset(s.xsNorm[idx] * w, s.ysNorm[idx] * h),
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                // X-axis labels collapse into per-series "press-to-read"
                // readouts while scrubbing. Each overlaid line reports the
                // value of its nearest sample to the scrubbed time.
                val frac = scrubX.value
                if (frac != null) {
                    val scrubTime = Instant.ofEpochMilli(
                        tStart.toEpochMilli() + (frac.toDouble() * tSpan).toLong(),
                    )
                    Text(
                        text = fmt.format(scrubTime),
                        style = R1.labelMicro,
                        color = R1.Ink,
                    )
                    for (s in multi.series) {
                        val idx = nearestIndex(s.xsNorm, frac)
                        val sample = if (idx >= 0) s.samples.getOrNull(idx) else null
                        Row {
                            if (!single) {
                                Box(
                                    modifier = Modifier
                                        .padding(end = 6.dp)
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(seriesColor(s.colorIndex)),
                                )
                            }
                            Text(
                                text = s.displayName,
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                text = sample?.let { "${formatNum(it.second)}${s.unit?.let { u -> " $u" } ?: ""}" } ?: "—",
                                style = R1.labelMicro,
                                color = seriesColor(s.colorIndex),
                            )
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
            // Inline Y-axis labels only make sense for a single series — a
            // shared 0..1 axis carrying multiple units can't have one
            // numeric label. With overlays the per-series min/max moves to
            // the legend instead.
            if (single) {
                val s = multi.series[0]
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.width(56.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatNum(s.yMax)}${s.unit?.let { " $it" } ?: ""}",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        maxLines = 1,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${formatNum(s.yMin)}${s.unit?.let { " $it" } ?: ""}",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Legend + overlay management. Lists every loaded series with its accent
 * swatch, friendly name, and per-series min..max (the absolute scale the
 * chart's shared 0..1 axis hides). The primary entity (index 0) can't be
 * removed; extra series get an ✕ to drop them. An ADD ENTITY row opens the
 * numeric picker, capped at [HistoryViewModel.MAX_SERIES].
 */
@Composable
private fun OverlayLegend(
    ui: HistoryViewModel.UiState,
    onAdd: () -> Unit,
    onRemove: (EntityId) -> Unit,
) {
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
            text = "OVERLAY · ${ui.series.size}/${HistoryViewModel.MAX_SERIES}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        ui.series.forEachIndexed { index, s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(seriesColor(s.colorIndex)),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = s.displayName,
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                    )
                    val range = if (s.min != null && s.max != null) {
                        "${formatNum(s.min)} – ${formatNum(s.max)}${s.unit?.let { " $it" } ?: ""}"
                    } else {
                        s.entityId.value
                    }
                    Text(text = range, style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
                }
                // Index 0 is the primary drill-in entity — keep it pinned.
                if (index != 0) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .r1Pressable(onClick = { onRemove(s.entityId) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
        }
        // ADD affordance — disabled once the cap is reached, with copy
        // explaining why.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .then(if (ui.atCap) Modifier else Modifier.r1Pressable(onClick = onAdd))
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ui.atCap) "OVERLAY FULL (${HistoryViewModel.MAX_SERIES} MAX)" else "+ ADD ENTITY",
                style = R1.labelMicro,
                color = if (ui.atCap) R1.InkMuted else R1.AccentWarm,
            )
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
        // entities (text sensors) suppress the numeric rows. Bound to
        // locals because ui.min/max/avg are computed getters (no smart
        // cast). Summary tracks the primary entity only — overlaid
        // series are summarized in the legend.
        val unit = ui.unit
        val pmin = ui.min
        val pmax = ui.max
        val pavg = ui.avg
        SummaryRow(
            label = "CURRENT",
            value = ui.current?.let { "$it${unit?.let { u -> " $u" } ?: ""}" } ?: "—",
            accent = R1.Ink,
        )
        if (pmin != null) SummaryRow(
            label = "MIN",
            value = "${formatNum(pmin)}${unit?.let { " $it" } ?: ""}",
            accent = R1.AccentCool,
        )
        if (pmax != null) SummaryRow(
            label = "MAX",
            value = "${formatNum(pmax)}${unit?.let { " $it" } ?: ""}",
            accent = R1.AccentWarm,
        )
        if (pavg != null) SummaryRow(
            label = "AVG",
            value = "${formatNum(pavg)}${unit?.let { " $it" } ?: ""}",
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

/** Drop unhelpful trailing decimals: 23.0 -> "23", 23.45 -> "23.45".
 *  Locale.US so the decimal separator is a point in every locale, matching
 *  the rest of the app's numeric formatting (and the shared snapshots). */
private fun formatNum(v: Double): String =
    if (kotlin.math.abs(v - v.toLong()) < 1e-9) "${v.toLong()}"
    else "%.2f".format(java.util.Locale.US, v)

/**
 * One overlaid series, pre-projected into [0..1] space. x is normalized to the
 * SHARED time axis [tStart, tEnd] across all series; y is normalized to this
 * series' OWN [yMin, yMax] so differing units / magnitudes stay legible on one
 * chart. Stored as FloatArrays so the per-frame Canvas draw is allocation-free.
 */
internal data class SeriesProjection(
    val colorIndex: Int,
    val displayName: String,
    val unit: String?,
    val xsNorm: FloatArray,
    val ysNorm: FloatArray,
    val yMin: Double,
    val yMax: Double,
    /** (timestamp, value) for each numeric sample, in xsNorm/ysNorm order, so the
     *  scrub readout can index a sample without re-deriving it. */
    val samples: List<Pair<Instant, Double>>,
)

/** All overlaid series sharing one time axis. tStart/tEnd/tSpan span the union
 *  of every series so the lines line up in time even when their windows differ
 *  slightly (e.g. a sensor that started reporting later). */
internal data class MultiProjection(
    val series: List<SeriesProjection>,
    val tStart: Instant,
    val tEnd: Instant,
    val tSpan: Long,
)

/**
 * Build the shared-axis projection for the given series. Returns null when no
 * series has >= 2 numeric samples (nothing chartable). Series with fewer than 2
 * numeric points are dropped from the overlay but don't block the others.
 */
internal fun buildMultiProjection(series: List<HistoryViewModel.Series>): MultiProjection? {
    // Per-series numeric samples.
    val perSeries = series.map { s ->
        s to s.points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
    }.filter { it.second.size >= 2 }
    if (perSeries.isEmpty()) return null
    // Shared time axis = union of all series' spans.
    val tStart = perSeries.minOf { it.second.first().first }
    val tEnd = perSeries.maxOf { it.second.last().first }
    val tSpan = Duration.between(tStart, tEnd).toMillis().coerceAtLeast(1L)
    val projected = perSeries.map { (s, numeric) ->
        val ys = numeric.map { it.second }
        val yMin0 = ys.min()
        val yMax0 = ys.max()
        val yRange0 = (yMax0 - yMin0).takeIf { it > 1e-9 } ?: 1.0
        val xs = FloatArray(numeric.size)
        val ysn = FloatArray(numeric.size)
        for (i in numeric.indices) {
            val (ts, v) = numeric[i]
            xs[i] = Duration.between(tStart, ts).toMillis().toFloat() / tSpan
            ysn[i] = 1f - (((v - yMin0) / yRange0).toFloat())
        }
        SeriesProjection(
            colorIndex = s.colorIndex,
            displayName = s.displayName,
            unit = s.unit,
            xsNorm = xs,
            ysNorm = ysn,
            yMin = yMin0,
            yMax = yMax0,
            samples = numeric,
        )
    }
    return MultiProjection(projected, tStart, tEnd, tSpan)
}

/** Nearest sample index to a normalized x fraction, or -1 if empty. Linear
 *  scan; sample counts are bounded by the History fetch's downsampling. */
internal fun nearestIndex(xsNorm: FloatArray, frac: Float): Int {
    if (xsNorm.isEmpty()) return -1
    var bestI = 0
    var bestD = Float.POSITIVE_INFINITY
    for (i in xsNorm.indices) {
        val d = kotlin.math.abs(xsNorm[i] - frac)
        if (d < bestD) {
            bestD = d
            bestI = i
        }
    }
    return bestI
}

/** True for entities worth overlaying on a numeric chart: sensors / numbers /
 *  counters whose live state parses as a number. Toggles, scenes, and text
 *  entities are filtered out — they'd have no line to draw. */
internal fun EntityState.isNumericChartable(): Boolean {
    val numericDomain = id.domain == com.github.itskenny0.r1ha.core.ha.Domain.SENSOR ||
        id.domain == com.github.itskenny0.r1ha.core.ha.Domain.NUMBER ||
        id.domain == com.github.itskenny0.r1ha.core.ha.Domain.INPUT_NUMBER ||
        id.domain == com.github.itskenny0.r1ha.core.ha.Domain.COUNTER
    val stateNumeric = rawState?.toDoubleOrNull()?.isFinite() == true
    return numericDomain && (stateNumeric || unit != null)
}

/**
 * Numeric entity picker for adding overlay series. Mirrors the Settings entity
 * picker's layout (translucent backdrop, centred card, search + scrollable
 * list) but filters to numeric-chartable entities and hides ones already on
 * the chart. Kept local to feature/history so the overlay owns its own
 * filtering without coupling to the Settings picker.
 */
@Composable
private fun NumericEntityPickerSheet(
    haRepository: HaRepository,
    excludeIds: Set<String>,
    onPick: (entityId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val entities by produceState<List<EntityState>?>(null, excludeIds) {
        value = haRepository.listAllEntities().getOrNull().orEmpty()
            .filter { it.isNumericChartable() && it.id.value !in excludeIds }
            .sortedBy { it.friendlyName.lowercase() }
    }
    var query by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(80)
        runCatching { focus.requestFocus() }
    }
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
            Text(text = "ADD TO CHART", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = "Numeric sensors only",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "temperature, power…",
                        monospace = false,
                        focusRequester = focus,
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
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
            val all = entities
            when {
                all == null -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                all.isEmpty() -> Box(
                    modifier = Modifier.fillMaxWidth().height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No numeric sensors available to add.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                else -> {
                    val filtered = remember(query, all) {
                        val q = query.trim().lowercase()
                        if (q.isBlank()) all
                        else all.filter {
                            it.friendlyName.lowercase().contains(q) ||
                                it.id.value.lowercase().contains(q)
                        }
                    }
                    if (filtered.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(120.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "No matches for '$query'.",
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
                            items(items = filtered, key = { it.id.value }) { entity ->
                                NumericPickRow(entity = entity, onPick = { onPick(entity.id.value) })
                            }
                        }
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
private fun NumericPickRow(entity: EntityState, onPick: () -> Unit) {
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
            Text(text = entity.friendlyName, style = R1.body, color = R1.Ink, maxLines = 1)
            Text(
                text = entity.id.value,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(6.dp))
        Text(
            text = entity.unit ?: entity.id.domain.prefix.uppercase().take(6),
            style = R1.labelMicro,
            color = R1.AccentNeutral,
        )
    }
}
