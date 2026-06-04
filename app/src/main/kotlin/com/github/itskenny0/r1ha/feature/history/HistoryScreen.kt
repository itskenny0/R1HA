package com.github.itskenny0.r1ha.feature.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
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
import androidx.compose.foundation.layout.sizeIn
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
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
                    modifier = Modifier.semantics { heading() },
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
            title = ellipsize(ui.displayName.uppercase(), 22),
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() }, contentDescription = "Refresh history")
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (ui.loading) "LOADING" else "REFRESH",
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
            val dimens = rememberResponsiveDimens()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              // Centre + width-cap the body on medium/expanded/extra-large tiers so the
              // chart and summary panels read as a centred column rather than one
              // wall-wide line on a big panel; on R1 / compact the cap is unspecified so
              // every pixel of the narrow panel is kept.
              Column(
                modifier = Modifier
                    .then(
                        if (dimens.capsContentWidth) Modifier.widthIn(max = dimens.maxContentWidth)
                        else Modifier,
                    )
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = R1Icons.forEntity(entityId),
                        contentDescription = null,
                        tint = R1.InkMuted,
                        modifier = Modifier.padding(end = 6.dp).size(14.dp),
                    )
                    Text(
                        text = entityId,
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
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
                            style = responsiveType(R1.labelMicro),
                            color = R1.StatusRed,
                        )
                    }
                }
                Spacer(Modifier.size(24.dp))
              }
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
                    .sizeIn(minHeight = 48.dp)
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(
                        onClick = { onSelect(w) },
                        contentDescription = "Show ${windowAccessibleLabel(w)} of history",
                    )
                    .padding(vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = w.label,
                    style = responsiveType(R1.labelMicro),
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

/** Tier-aware chart height. The base 180 dp is right for the R1 / phone, but
 *  on a roomy tablet or desktop panel the same 180 dp leaves the line marooned
 *  in a thin strip; step it up so the chart uses the extra vertical room. Pure
 *  in the tier so it stays testable. */
internal fun chartHeightDp(tier: WindowTier): androidx.compose.ui.unit.Dp = when (tier) {
    WindowTier.R1, WindowTier.COMPACT -> 180.dp
    WindowTier.MEDIUM -> 220.dp
    WindowTier.EXPANDED -> 260.dp
    WindowTier.EXTRA_LARGE -> 300.dp
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
        val chartHeight = chartHeightDp(LocalWindowTier.current.tier)
        val anyPoints = ui.series.any { it.points.isNotEmpty() }
        if (ui.loading && !anyPoints) {
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
        // Project every series onto a shared time axis. Each series
        // normalizes to ITS OWN min/max (per-series vertical scale) so
        // entities with different units / magnitudes overlay legibly on a
        // single 0..1 axis; the absolute values live in the legend. The
        // x-axis is shared: all series map onto the union [tStart, tEnd].
        val multi = androidx.compose.runtime.remember(ui.series) {
            buildMultiProjection(ui.series)
        }
        if (multi == null) {
            // No numeric line to draw. For a single non-numeric entity (the
            // common drill-in: a binary_sensor, person, climate mode, text
            // sensor), fall back to HA's state-timeline rendering instead of
            // an empty message. Overlays stay numeric-only (a shared line
            // chart can't host categorical states), so they keep the prompt.
            val primaryPoints = ui.primary?.points.orEmpty()
            val numericPoint = primaryPoints.lastOrNull { it.numeric != null }
            when {
                // A numeric sensor with exactly one reading in the window can't draw
                // a line, but rendering it as a categorical timeline (one full-width
                // band of "23.4") is misleading. Show the single reading as a numeric
                // readout instead, so the user sees the value rather than a state band.
                !ui.isOverlay && numericPoint != null && isSingleNumericPoint(primaryPoints) ->
                    SingleValuePanel(
                        value = numericPoint.numeric,
                        unit = ui.unit,
                        timestamp = numericPoint.timestamp,
                        height = chartHeight,
                    )
                !ui.isOverlay && isCategoricalHistory(primaryPoints) && primaryPoints.isNotEmpty() ->
                    StateTimelinePanel(name = ui.displayName, points = primaryPoints)
                else -> {
                Box(
                    modifier = Modifier.fillMaxWidth().height(chartHeight),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (ui.isOverlay) "NO NUMERIC HISTORY YET" else "NOT ENOUGH HISTORY YET",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                    )
                }
                }
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
        // Text alternative for the hand-drawn Canvas: screen readers can't
        // see the line, so summarize each series (min, max, avg, trend) and,
        // while the user is scrubbing, the value under the scrub guide.
        val chartDescription = buildHistoryChartContentDescription(multi, scrubX.value)
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
                        .scrubGesture(multi) { frac -> scrubX.value = frac },
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
                        style = responsiveType(R1.labelMicro),
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
                                style = responsiveType(R1.labelMicro),
                                color = R1.InkSoft,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            )
                            Text(
                                text = sample?.let { "${formatNum(it.second)}${s.unit?.let { u -> " $u" } ?: ""}" } ?: "n/a",
                                style = responsiveType(R1.labelMicro),
                                color = seriesColor(s.colorIndex),
                                maxLines = 1,
                            )
                        }
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
            // Inline Y-axis labels only make sense for a single series — a
            // shared 0..1 axis carrying multiple units can't have one
            // numeric label. With overlays the per-series min/max moves to
            // the legend instead.
            if (single) {
                val s = multi.series[0]
                Spacer(Modifier.width(8.dp))
                // Y-axis label gutter widens a touch on roomy tiers so the scaled-up
                // type isn't ellipsized into the unit on a big panel.
                val axisWidth = when (LocalWindowTier.current.tier) {
                    WindowTier.R1, WindowTier.COMPACT -> 56.dp
                    WindowTier.MEDIUM -> 64.dp
                    else -> 72.dp
                }
                Column(modifier = Modifier.width(axisWidth), verticalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = "${formatNum(s.yMax)}${s.unit?.let { " $it" } ?: ""}",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = "${formatNum(s.yMin)}${s.unit?.let { " $it" } ?: ""}",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * State-timeline rendering for a non-numeric entity: HA's categorical history
 * view. Draws one coloured horizontal band per run of identical consecutive
 * states across the window, with axis time labels and a press-to-read scrub
 * that names the state under the guide. A swatch legend below maps each colour
 * to its state. This is what makes the History surface complete for the many
 * entities (binary_sensor, person, climate mode, text sensors) that have no
 * line to plot.
 */
@Composable
private fun StateTimelinePanel(name: String, points: List<HistoryPoint>) {
    val timeline = androidx.compose.runtime.remember(points) { buildTimelineProjection(points) }
    if (timeline == null) {
        Box(
            modifier = Modifier.fillMaxWidth().height(180.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "NO HISTORY YET", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        }
        return
    }
    // Stable colour slot per distinct state, by first-seen order.
    val colorOf = androidx.compose.runtime.remember(timeline) {
        timeline.distinctStates.withIndex().associate { (slot, st) ->
            st to timelineStateColor(st, slot)
        }
    }
    val zone = ZoneId.systemDefault()
    val span = Duration.between(timeline.tStart, timeline.tEnd).toMillis()
    val fmt = if (span < Duration.ofHours(36).toMillis()) {
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    } else {
        DateTimeFormatter.ofPattern("d MMM").withZone(zone)
    }
    val scrubX = androidx.compose.runtime.remember(timeline) {
        androidx.compose.runtime.mutableStateOf<Float?>(null)
    }
    val chartDescription = buildTimelineContentDescription(name, timeline)
    // The state-timeline band is intentionally thin (one stacked run of
    // coloured segments), but on roomy tiers a little extra height keeps it
    // legible rather than a hairline strip on a big panel.
    val bandHeight = when (LocalWindowTier.current.tier) {
        WindowTier.R1, WindowTier.COMPACT -> R1.space.xxl + R1.space.l
        WindowTier.MEDIUM -> R1.space.xxl + R1.space.xl
        else -> R1.space.xxl + R1.space.xxl
    }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(bandHeight)
            .clip(R1.ShapeS)
            .background(R1.Surface)
            .semantics { contentDescription = chartDescription }
            .scrubGesture(timeline) { frac -> scrubX.value = frac },
    ) {
        val w = size.width
        val h = size.height
        for (seg in timeline.segments) {
            val x0 = seg.startFrac * w
            val x1 = seg.endFrac * w
            drawRect(
                color = colorOf[seg.state] ?: R1.AccentNeutral,
                topLeft = Offset(x0, 0f),
                size = androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), h),
            )
        }
        val frac = scrubX.value
        if (frac != null) {
            drawLine(
                color = R1.Ink,
                start = Offset(frac * w, 0f),
                end = Offset(frac * w, h),
                strokeWidth = 1f,
            )
        }
    }
    Spacer(Modifier.height(R1.space.xs))
    val frac = scrubX.value
    if (frac != null) {
        val seg = timeline.segments.firstOrNull { frac >= it.startFrac && frac <= it.endFrac }
            ?: timeline.segments.lastOrNull()
        val scrubTime = Instant.ofEpochMilli(
            timeline.tStart.toEpochMilli() + (frac.toDouble() * span).toLong(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = fmt.format(scrubTime),
                style = responsiveType(R1.labelMicro),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = seg?.state?.let { formatStateLabel(it) } ?: "n/a",
                style = responsiveType(R1.labelMicro),
                color = seg?.let { colorOf[it.state] } ?: R1.InkSoft,
            )
        }
    } else {
        Row {
            Text(
                text = fmt.format(timeline.tStart),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(text = fmt.format(timeline.tEnd), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        }
    }
    // State legend: swatch + label per distinct state, so colours are named.
    Spacer(Modifier.height(R1.space.s))
    timeline.distinctStates.forEach { st ->
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .padding(top = R1.space.xxs)
                .semantics(mergeDescendants = true) {
                    contentDescription = "State ${formatStateLabel(st)}"
                },
        ) {
            Box(
                modifier = Modifier
                    .padding(end = R1.space.s)
                    .size(R1.space.m)
                    .clip(R1.ShapeS)
                    .background(colorOf[st] ?: R1.AccentNeutral),
            )
            Text(
                text = formatStateLabel(st),
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
            )
        }
    }
}

/**
 * Single-reading readout for a numeric sensor with exactly one sample in the
 * window. Replaces the misleading state-timeline band a one-point numeric
 * sensor would otherwise get: shows the value large, the unit beside it, and
 * the sample's timestamp, so the user reads the number rather than a coloured
 * bar labelled with a number.
 */
@Composable
private fun SingleValuePanel(
    value: Double?,
    unit: String?,
    timestamp: Instant,
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val zone = ZoneId.systemDefault()
    val fmt = remember { DateTimeFormatter.ofPattern("d MMM HH:mm").withZone(zone) }
    Box(
        modifier = Modifier.fillMaxWidth().height(height),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "ONLY ONE READING IN WINDOW", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "${value?.let { formatNum(it) } ?: "—"}${unit?.let { " $it" } ?: ""}",
                style = responsiveType(R1.numeralM),
                color = R1.Ink,
                maxLines = 1,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = fmt.format(timestamp), style = responsiveType(R1.labelMicro), color = R1.InkSoft, maxLines = 1)
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
            text = "OVERLAY, ${ui.series.size} of ${HistoryViewModel.MAX_SERIES}",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            modifier = Modifier.semantics { heading() },
        )
        ui.series.forEachIndexed { index, s ->
            // The swatch only conveys colour, which a screen reader can't read.
            // Merge the row into one spoken label naming the series, its colour
            // slot, and its value range so colour is described, not implied.
            val rowLabel = legendRowContentDescription(
                name = s.displayName,
                colorIndex = s.colorIndex,
                min = s.min,
                max = s.max,
                unit = s.unit,
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Merge only the swatch + name + range into one spoken label so
                // the colour is described, not implied. Crucially this merge does
                // NOT wrap the remove button: a row-level mergeDescendants would
                // swallow the button's own contentDescription / click action,
                // leaving it unreachable to a screen reader. Scoping the merge to
                // the descriptive cluster keeps the button a separate, operable node.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .semantics(mergeDescendants = true) {
                            contentDescription = rowLabel
                        },
                ) {
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
                            style = responsiveType(R1.body),
                            color = R1.Ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                        val range = if (s.min != null && s.max != null) {
                            "${formatNum(s.min)} to ${formatNum(s.max)}${s.unit?.let { " $it" } ?: ""}"
                        } else {
                            s.entityId.value
                        }
                        Text(
                            text = range,
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                // Index 0 is the primary drill-in entity, which stays pinned.
                if (index != 0) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .r1Pressable(
                                onClick = { onRemove(s.entityId) },
                                contentDescription = "Remove ${s.displayName} from chart",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "X", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
        }
        // ADD affordance, disabled once the cap is reached, with copy
        // explaining why.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .sizeIn(minHeight = 48.dp)
                .clip(R1.ShapeS)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .then(
                    if (ui.atCap) {
                        Modifier
                    } else {
                        Modifier.r1Pressable(onClick = onAdd, contentDescription = "Add entity to chart")
                    },
                )
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ui.atCap) "OVERLAY FULL (${HistoryViewModel.MAX_SERIES} MAX)" else "+ ADD ENTITY",
                style = responsiveType(R1.labelMicro),
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
            text = "SUMMARY, ${ui.window.label}",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            modifier = Modifier.semantics { heading() },
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
        val points = ui.points
        val categorical = isCategoricalHistory(points) && points.isNotEmpty()
        SummaryRow(
            label = "CURRENT",
            value = ui.current?.let {
                if (categorical) formatStateLabel(it)
                else "$it${unit?.let { u -> " $u" } ?: ""}"
            } ?: "—",
            accent = R1.Ink,
        )
        if (categorical) {
            // Non-numeric entity: min/max/avg are meaningless. Report the
            // categorical equivalents HA's timeline conveys: how many state
            // changes happened and how many distinct states were seen.
            val timeline = remember(points) { buildTimelineProjection(points) }
            val changes = (timeline?.segments?.size ?: 1) - 1
            SummaryRow(
                label = "CHANGES",
                value = "${changes.coerceAtLeast(0)}",
                accent = R1.AccentWarm,
            )
            SummaryRow(
                label = "STATES",
                value = "${timeline?.distinctStates?.size ?: 0}",
                accent = R1.AccentCool,
            )
        } else {
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
        }
        SummaryRow(
            label = "SAMPLES",
            value = "${points.size}",
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
    // Anchor offsets to the data's own end time, not the wall clock. A
    // slow-changing sensor can have its last sample sit hours stale; anchoring
    // to Instant.now() then makes every short offset row resolve to that same
    // most-recent sample (15 MIN, 1 HR, 6 HR all collapse to one value). Walking
    // back from the last sample's timestamp keeps each offset distinct and matches
    // the window the chart draws.
    val anchor = points.maxByOrNull { it.timestamp }?.timestamp ?: java.time.Instant.now()
    // Curated offsets. Capped to the loaded window so the user doesn't see
    // a row that says "1d ago — —" when only an hour of data is available.
    val candidateOffsets = listOf(
        "15 MIN" to 15L * 60,
        "1 HR" to 60L * 60,
        "6 HR" to 6L * 3600,
        "24 HR" to 24L * 3600,
    )
    // Gate offsets on the SELECTED window, not the span between the first and
    // last sample. A sensor whose value rarely changes can have its last point
    // hours stale; deriving the window from the data then wrongly hides valid
    // rewind rows (e.g. "6 HR ago" on a 24 h window). The loaded data always
    // covers the selected window, so that's the correct bound.
    val windowSeconds = ui.window.hours.toLong() * 3600L
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
        Text(
            text = "REWIND",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            modifier = Modifier.semantics { heading() },
        )
        for ((label, offsetSec) in applicable) {
            val target = anchor.minusSeconds(offsetSec)
            // Pick the latest point at or before the target time, falling back
            // to the earliest if nothing is found (which shouldn't happen given
            // the applicable filter above, but stays defensive).
            val prior = points.filter { !it.timestamp.isAfter(target) }
                .maxByOrNull { it.timestamp }
                ?: points.first()
            val unit = ui.unit?.let { " $it" } ?: ""
            val displayValue = prior.numeric?.let { formatNum(it) + unit }
                ?: prior.state.takeIf { it.isNotBlank() }?.let { formatStateLabel(it) }
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
        Text(text = label, style = responsiveType(R1.labelMicro), color = R1.InkSoft, modifier = Modifier.width(80.dp))
        Text(
            text = value,
            style = responsiveType(R1.body).copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

/** Drop unhelpful trailing decimals: 23.0 -> "23", 23.45 -> "23.45".
 *  Locale.US so the decimal separator is a point in every locale, matching
 *  the rest of the app's numeric formatting (and the shared snapshots). Kept in lockstep
 *  with StatisticsViewModel.formatStatNum, including the -0 normalisation: a value that
 *  rounds to zero from below renders "0", not "-0.00". */
private fun formatNum(v: Double): String {
    if (kotlin.math.abs(v - v.toLong()) < 1e-9) return "${v.toLong()}"
    val s = "%.2f".format(java.util.Locale.US, v)
    return if (s.startsWith("-") && s.drop(1).all { it == '0' || it == '.' }) s.drop(1) else s
}

/**
 * One overlaid series, pre-projected into [0..1] space. x is normalized to the
 * SHARED time axis [tStart, tEnd] across all series; y is normalized to this
 * series' OWN [yMin, yMax] so differing units / magnitudes stay legible on one
 * chart. Stored as FloatArrays so the per-frame Canvas draw is allocation-free.
 */
// ---------------------------------------------------------------------------
// Accessibility text helpers. Pure and deterministic so they can be unit
// tested without a Compose runtime, and reused by the screen's semantics.
// ---------------------------------------------------------------------------

/** Truncate to [max] characters with a trailing ellipsis instead of a hard cut,
 *  so an over-long title reads as "Living Room Temp…" rather than losing its tail
 *  silently. R1TopBar draws the title single-line without its own overflow, so the
 *  ellipsis has to live in the string. */
internal fun ellipsize(text: String, max: Int): String =
    if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"

/** Spoken-out window label, e.g. "24H" reads as "1 day" rather than letters. */
internal fun windowAccessibleLabel(window: HistoryViewModel.Window): String =
    if (window.hours % 24 == 0) {
        val days = window.hours / 24
        if (days == 1) "1 day" else "$days days"
    } else {
        if (window.hours == 1) "1 hour" else "${window.hours} hours"
    }

/** Human colour name for a series accent slot, so legend rows describe the
 *  colour instead of relying on the swatch alone. Slots beyond the named set
 *  fall back to a numbered label. */
internal fun seriesColorName(colorIndex: Int): String = when (colorIndex) {
    0 -> "orange"
    1 -> "blue"
    2 -> "green"
    3 -> "amber"
    4 -> "grey"
    else -> "series ${colorIndex + 1}"
}

/**
 * Merged spoken label for one legend row: the series name, its colour, and the
 * value range it spans. Without this a screen reader would announce only a bare
 * coloured box and an unlabeled name.
 */
internal fun legendRowContentDescription(
    name: String,
    colorIndex: Int,
    min: Double?,
    max: Double?,
    unit: String?,
): String {
    val color = seriesColorName(colorIndex)
    val unitSuffix = unit?.let { " $it" } ?: ""
    val range = if (min != null && max != null) {
        ", range ${formatNum(min)} to ${formatNum(max)}$unitSuffix"
    } else {
        ""
    }
    return "$name, $color line$range"
}

/**
 * Text alternative for the chart Canvas. Summarizes every plotted series
 * (colour, min, max, average, trend) and, while the user is scrubbing, the
 * value each series reports under the scrub guide. Returns a short sentence
 * when nothing is chartable so the Canvas is never an unlabeled element.
 */
internal fun buildHistoryChartContentDescription(
    multi: MultiProjection?,
    scrubFrac: Float?,
): String {
    if (multi == null || multi.series.isEmpty()) {
        return "Line chart with no numeric history to display."
    }
    val count = multi.series.size
    val header = if (count == 1) "Line chart, 1 series." else "Line chart, $count series."
    val bodies = multi.series.map { s ->
        val values = s.samples.map { it.second }
        val avg = if (values.isNotEmpty()) values.average() else 0.0
        val trend = when {
            values.size < 2 -> "flat"
            values.last() > values.first() -> "rising"
            values.last() < values.first() -> "falling"
            else -> "flat"
        }
        val unitSuffix = s.unit?.let { " $it" } ?: ""
        "${s.displayName}, ${seriesColorName(s.colorIndex)}: " +
            "minimum ${formatNum(s.yMin)}$unitSuffix, " +
            "maximum ${formatNum(s.yMax)}$unitSuffix, " +
            "average ${formatNum(avg)}$unitSuffix, $trend"
    }
    val scrub = if (scrubFrac != null) {
        val parts = multi.series.mapNotNull { s ->
            val idx = nearestIndex(s.xsNorm, scrubFrac)
            val sample = if (idx >= 0) s.samples.getOrNull(idx) else null
            sample?.let {
                "${s.displayName} ${formatNum(it.second)}${s.unit?.let { u -> " $u" } ?: ""}"
            }
        }
        if (parts.isEmpty()) "" else "Selected point: ${parts.joinToString(separator = ", ")}."
    } else {
        ""
    }
    return listOf(header, bodies.joinToString(separator = " "), scrub)
        .filter { it.isNotBlank() }
        .joinToString(separator = " ")
}

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
        // A flat series (min == max) has no vertical range to normalize against.
        // Center it at mid-height instead of pinning it to the bottom edge, which
        // is how HA draws a constant value: a line through the middle of the band.
        val flat = (yMax0 - yMin0) <= 1e-9
        val yRange0 = if (flat) 1.0 else (yMax0 - yMin0)
        val xs = FloatArray(numeric.size)
        val ysn = FloatArray(numeric.size)
        for (i in numeric.indices) {
            val (ts, v) = numeric[i]
            xs[i] = Duration.between(tStart, ts).toMillis().toFloat() / tSpan
            ysn[i] = if (flat) 0.5f else 1f - (((v - yMin0) / yRange0).toFloat())
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

/**
 * Press-and-drag scrub gesture for a chart Canvas. Reports the touched x as a
 * fraction [0..1] of the canvas width on the initial press, tracks it as the
 * finger slides (so the user can slide to read values rather than re-tapping),
 * and reports null on release. [key] re-arms the gesture when the projection
 * changes. Replaces the old press-and-hold-only handler that ignored drag.
 */
internal fun Modifier.scrubGesture(
    key: Any?,
    onScrub: (Float?) -> Unit,
): Modifier = this.pointerInput(key) {
    val canvasW = size.width.toFloat().coerceAtLeast(1f)
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onScrub((down.position.x / canvasW).coerceIn(0f, 1f))
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: event.changes.firstOrNull()
            if (change == null || !change.pressed) break
            if (change.positionChange() != Offset.Zero) change.consume()
            onScrub((change.position.x / canvasW).coerceIn(0f, 1f))
        }
        onScrub(null)
    }
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

// ---------------------------------------------------------------------------
// Categorical / state-timeline projection. HA renders non-numeric entities
// (binary_sensor, person, climate hvac_action, text sensors, ...) not as a
// line but as a timeline of coloured segments: one band per run of identical
// consecutive states, deduped the way state-history-chart-timeline.ts does.
// The numeric line chart can't represent these, so the History surface needs
// its own segment renderer to be complete versus Lovelace.
// ---------------------------------------------------------------------------

/** One coloured run on the state timeline: a state that held from [start] to
 *  [end] of the shared window, expressed as [0..1] fractions of the span. */
internal data class TimelineSegment(
    val state: String,
    val startFrac: Float,
    val endFrac: Float,
    val start: Instant,
    val end: Instant,
)

/** A built state timeline: the deduped segments plus the distinct states in
 *  first-seen order, so the legend assigns each a stable colour slot. */
internal data class TimelineProjection(
    val segments: List<TimelineSegment>,
    val distinctStates: List<String>,
    val tStart: Instant,
    val tEnd: Instant,
)

/**
 * Build a state timeline from raw history points. Collapses runs of identical
 * consecutive states into a single segment (matching HA's dedup) and projects
 * each onto a [0..1] fraction of the window. The final segment runs to the
 * last sample's timestamp. Returns null when there's nothing to draw (fewer
 * than one state, or a zero-width window).
 */
internal fun buildTimelineProjection(points: List<HistoryPoint>): TimelineProjection? {
    if (points.isEmpty()) return null
    val sorted = points.sortedBy { it.timestamp }
    val tStart = sorted.first().timestamp
    val tEnd = sorted.last().timestamp
    val spanMs = Duration.between(tStart, tEnd).toMillis()
    if (spanMs <= 0L) {
        // Single instant of history: render one full-width band so the user
        // still sees the current state rather than an empty chart.
        val only = sorted.first().state
        return TimelineProjection(
            segments = listOf(TimelineSegment(only, 0f, 1f, tStart, tEnd)),
            distinctStates = listOf(only),
            tStart = tStart,
            tEnd = tEnd,
        )
    }
    // Collapse consecutive duplicates into runs.
    data class Run(val state: String, val start: Instant)
    val runs = ArrayList<Run>()
    for (p in sorted) {
        if (runs.isEmpty() || runs.last().state != p.state) {
            runs.add(Run(p.state, p.timestamp))
        }
    }
    val segments = runs.mapIndexed { i, run ->
        val end = if (i + 1 < runs.size) runs[i + 1].start else tEnd
        TimelineSegment(
            state = run.state,
            startFrac = Duration.between(tStart, run.start).toMillis().toFloat() / spanMs,
            endFrac = Duration.between(tStart, end).toMillis().toFloat() / spanMs,
            start = run.start,
            end = end,
        )
    }
    val distinct = runs.map { it.state }.distinct()
    return TimelineProjection(segments, distinct, tStart, tEnd)
}

/** True when the primary entity has no chartable numeric history but does have
 *  state changes worth showing as a timeline. Drives the numeric-line vs
 *  state-timeline fork. */
internal fun isCategoricalHistory(points: List<HistoryPoint>): Boolean {
    if (points.isEmpty()) return false
    val numericCount = points.count { it.numeric != null }
    // Two-plus numeric samples is enough for a line; otherwise treat as
    // categorical so a mostly-text entity still gets a timeline.
    return numericCount < 2
}

/** True when the window holds exactly one numeric reading and no other numeric
 *  samples: a numeric sensor that simply hasn't changed (or only just started
 *  reporting). Such an entity can't draw a line, but it's numeric, not
 *  categorical, so it deserves a single-value readout rather than a state band. */
internal fun isSingleNumericPoint(points: List<HistoryPoint>): Boolean =
    points.count { it.numeric != null } == 1

/** Human-readable spoken label for the state timeline, for the Canvas
 *  contentDescription. Names the current state, how many changes occurred,
 *  and the distinct states seen. Pure for unit testing. */
internal fun buildTimelineContentDescription(
    name: String,
    timeline: TimelineProjection?,
): String {
    if (timeline == null || timeline.segments.isEmpty()) {
        return "State timeline for $name with no history to display."
    }
    val current = formatStateLabel(timeline.segments.last().state)
    val changes = timeline.segments.size - 1
    val changesPart = when {
        changes <= 0 -> "no changes"
        changes == 1 -> "1 change"
        else -> "$changes changes"
    }
    val states = timeline.distinctStates.joinToString(separator = ", ") { formatStateLabel(it) }
    return "State timeline for $name. Currently $current, $changesPart in window. States seen: $states."
}

/** Title-case a raw HA state token for display: snake_case to Title Case,
 *  with the common unavailable / unknown tokens spelled out. */
internal fun formatStateLabel(raw: String): String = when (raw) {
    "unavailable" -> "Unavailable"
    "unknown" -> "Unknown"
    "" -> "Empty"
    else -> raw.split('_').joinToString(" ") { word ->
        word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(java.util.Locale.US) else it.toString() }
    }
}

/** Stable colour for a distinct state, indexed by its slot in the distinct-
 *  state list. Reuses the series accent palette but with semantic overrides
 *  for the on/off/unavailable/unknown tokens the way HA's timeline colours do. */
internal fun timelineStateColor(state: String, slot: Int): Color = when (state) {
    "unavailable" -> R1.InkMuted
    "unknown" -> R1.Hairline
    "on", "open", "home", "active", "detected", "wet", "motion" -> R1.AccentGreen
    "off", "closed", "away", "idle", "clear", "dry" -> R1.AccentNeutral
    else -> SERIES_COLORS.getOrElse(slot % SERIES_COLORS.size) { R1.AccentNeutral }
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
            Text(
                text = "ADD TO CHART",
                style = R1.sectionHeader,
                color = R1.AccentWarm,
                modifier = Modifier.semantics { heading() },
            )
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
                        placeholder = "temperature, power",
                        monospace = false,
                        focusRequester = focus,
                    )
                }
                if (query.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .r1Pressable(onClick = { query = "" }, contentDescription = "Clear search"),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "X", style = R1.labelMicro, color = R1.InkSoft)
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
