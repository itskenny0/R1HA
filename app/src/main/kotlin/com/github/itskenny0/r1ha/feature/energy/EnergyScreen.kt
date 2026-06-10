package com.github.itskenny0.r1ha.feature.energy

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1CenteredContent
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Energy summary surface, a four-tile readout of the most useful
 * Energy-panel numbers, sized down to fit the R1's portrait display:
 *  - DRAW (current W) + PRODUCTION (W) side-by-side at the top
 *  - TODAY (kWh since midnight) as its own line below
 *  - TOP CONSUMERS list (descending by current W)
 *
 * Pulls live from `/api/template` so no per-second polling. The
 * 30 s auto-refresh ticker keeps it fresh enough to feel live without
 * hammering HA's template render path.
 */
@Composable
fun EnergyScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Tap a TOP CONSUMERS row to open the full-screen History view for
     *  that sensor's entity_id. Default no-op so previews / tests don't
     *  need to thread it through. */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: EnergyViewModel = viewModel(factory = EnergyViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    // 30 s auto-refresh, energy figures change slowly relative to
    // wall-clock so any tighter would be wasted server work.
    AutoRefresh(everyMillis = 30_000L) { vm.refresh() }
    // History pulls from the recorder once on first composition; window
    // flips re-fetch on demand. Recorder statistics only update hourly so
    // the chart deliberately sits outside the 30 s live-tile ticker.
    LaunchedEffect(Unit) { vm.refreshHistory() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        val context = androidx.compose.ui.platform.LocalContext.current
        R1TopBar(
            title = "ENERGY",
            onBack = onBack,
            action = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    R1Chip(
                        text = "SHARE",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        tone = R1.AccentWarm,
                        contentDescription = "Share energy snapshot",
                        onClick = {
                            // Snapshot the current UI state into a square PNG and fire the
                            // share-intent. Done on the UI thread because Canvas-backed
                            // rendering takes ~30 ms on the R1 and the file write is
                            // bounded by cache size; no need for a coroutine.
                            runCatching {
                                val bmp = EnergyShareSnapshot.render(ui)
                                EnergyShareSnapshot.shareAsPng(context, bmp)
                                bmp.recycle()
                            }.onFailure { t ->
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "Share failed: ${t.message ?: "unknown"}",
                                )
                            }
                        },
                    )
                    R1Chip(
                        text = "CSV",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        tone = R1.AccentGreen,
                        contentDescription = "Export energy data as CSV",
                        onClick = {
                            runCatching {
                                val csv = energyCsv(ui)
                                shareCsvText(context, csv)
                            }.onFailure { t ->
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "CSV export failed: ${t.message ?: "unknown"}",
                                )
                            }
                        },
                    )
                    R1Chip(
                        text = if (ui.refreshing) "…" else "REFRESH",
                        variant = R1ChipVariant.Action,
                        onClick = { vm.refresh(indicate = true) },
                        contentDescription = "Refresh energy",
                    )
                }
            },
        )
        // Centre + width-cap the body on medium/expanded/extra-large tiers so the
        // tiles and chart read as a centred column instead of stretching one giant
        // line edge to edge; R1 / compact stay full-bleed (no cap) so the tiny
        // panel keeps every pixel. Gutters and the section gap step up with the
        // tier via the responsive dims rather than a fixed R1.space.
        val dimens = rememberResponsiveDimens()
        R1CenteredContent(modifier = Modifier.weight(1f)) {
            PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh(indicate = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.screenGutter, vertical = R1.space.s)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap),
            ) {
                // ── DRAW + PRODUCTION (+ TODAY on wide tiers) row ──────
                // On expanded panels the TODAY tile joins the top row as a
                // third column so the headline stats read as one band instead
                // of stacking down a mostly-empty wide page. Prefer the
                // recorder-derived today total (HA-accurate sum of per-bucket
                // consumption since midnight); fall back to the live-template
                // sum until the first history fetch lands.
                val today = ui.statsTodayKwh ?: ui.todayKwh
                val wideStats = dimens.tier == com.github.itskenny0.r1ha.ui.components.WindowTier.EXPANDED ||
                    dimens.tier == com.github.itskenny0.r1ha.ui.components.WindowTier.EXTRA_LARGE
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "DRAW",
                        value = ui.currentDrawW?.let { formatWatts(it) } ?: NO_VALUE,
                        accent = drawAccent(ui.currentDrawW),
                        // Lightning bolt: consumption / power draw.
                        icon = R1Icons.forDomain("power"),
                    )
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "PRODUCTION",
                        value = ui.productionW?.let { formatWatts(it) } ?: NO_VALUE,
                        accent = if ((ui.productionW ?: 0.0) > 0) R1.AccentGreen else R1.InkMuted,
                        // Sun for a solar site; battery when the install
                        // exposes a battery power source, so the tile reads as
                        // the real generation source rather than always-solar.
                        icon = if (ui.hasBatterySource) R1IconSet.Battery else R1IconSet.Sun,
                    )
                    if (wideStats) {
                        BigStatTile(
                            modifier = Modifier.weight(1f),
                            label = "TODAY",
                            value = today?.let { formatKwh(it) } ?: NO_VALUE,
                            accent = if ((today ?: 0.0) > 0) R1.AccentWarm else R1.InkMuted,
                        )
                    }
                }
                // ── TODAY (kWh) row (narrow tiers keep the full-width tile) ─
                if (!wideStats) {
                    BigStatTile(
                        modifier = Modifier.fillMaxWidth(),
                        label = "TODAY",
                        value = today?.let { formatKwh(it) } ?: NO_VALUE,
                        accent = if ((today ?: 0.0) > 0) R1.AccentWarm else R1.InkMuted,
                    )
                }
                // ── WATER + GAS tiles (additive: only when sensors exist) ─
                // UNVERIFIED OFFLINE: todayWater / todayGas come from Jinja
                // templates that mirror the kWh path but have not been tested
                // against a live HA with water / gas meters.
                if (ui.todayWater != null || ui.todayGas != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        if (ui.todayWater != null) {
                            BigStatTile(
                                modifier = Modifier.weight(1f),
                                label = "WATER TODAY",
                                value = formatMeter(ui.todayWater, ui.waterUnit),
                                accent = R1.AccentCool,
                                icon = R1IconSet.Moisture,
                            )
                        }
                        if (ui.todayGas != null) {
                            BigStatTile(
                                modifier = Modifier.weight(1f),
                                label = "GAS TODAY",
                                value = formatMeter(ui.todayGas, ui.gasUnit),
                                accent = R1.StatusAmber,
                                icon = R1IconSet.Smoke,
                            )
                        }
                    }
                }
                // ── CONSUMPTION HISTORY ────────────────────────────────
                EnergyHistorySection(
                    ui = ui,
                    onSelectWindow = { vm.setWindow(it) },
                )
                // ── ENERGY FLOW ────────────────────────────────────────
                // Additive section: renders only when draw or production > 0.
                // UNVERIFIED OFFLINE: data comes from live HA templates.
                EnergyFlowSection(ui = ui)
                // ── CONSUMER BREAKDOWN ─────────────────────────────────
                // Additive section: renders only when consumers are present.
                // UNVERIFIED OFFLINE: data comes from live HA templates.
                ConsumerBreakdownSection(ui = ui)
                // ── TOP CONSUMERS ──────────────────────────────────────
                if (ui.topConsumers.isNotEmpty()) {
                    var consumersExpanded by remember {
                        mutableStateOf(false)
                    }
                    R1Section(
                        title = "TOP CONSUMERS",
                        count = ui.topConsumers.size,
                        topSpace = R1.space.s,
                        trailing = if (ui.topConsumers.size > 5) {
                            {
                                R1Chip(
                                    text = if (consumersExpanded) {
                                        "COLLAPSE"
                                    } else {
                                        "SHOW ALL"
                                    },
                                    variant = R1ChipVariant.Action,
                                    onClick = { consumersExpanded = !consumersExpanded },
                                    contentDescription = if (consumersExpanded) {
                                        "Collapse consumers"
                                    } else {
                                        "Show all consumers"
                                    },
                                )
                            }
                        } else {
                            null
                        },
                    ) {
                        val visible = if (consumersExpanded) ui.topConsumers else ui.topConsumers.take(5)
                        for (c in visible) {
                            ConsumerRow(c, onClick = { onOpenHistory(c.entityId) })
                        }
                    }
                } else if (!ui.loading && ui.error == null && ui.currentDrawW == null) {
                    // Empty state when no device_class=power sensors are
                    // configured. Same look as the other 'no data' panels
                    // in the app so it doesn't read as 'load failed'.
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .padding(R1.space.m),
                    ) {
                        Text(
                            text = "No power sensors found. Add a power integration " +
                                "(smart meter, smart plug, energy monitor) and the " +
                                "dashboard will populate.",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                        )
                    }
                }
                val error = ui.error
                if (error != null && ui.currentDrawW == null && ui.todayKwh == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.StatusRed.copy(alpha = 0.12f))
                            .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                            .semantics { contentDescription = "Error. $error" }
                            .padding(horizontal = R1.space.m, vertical = R1.space.s),
                    ) {
                        Text(
                            text = error,
                            style = responsiveType(R1.labelMicro),
                            color = R1.StatusRed,
                        )
                    }
                }
                Spacer(Modifier.height(R1.space.xl))
            }
            } // PullToRefreshBox
        } // R1CenteredContent
    }
}

/** Wide stat tile, bold value with a small label above. Same shape as the
 *  metric tiles on the TODAY dashboard so the visual language is
 *  consistent. */
@Composable
private fun BigStatTile(
    modifier: Modifier,
    label: String,
    value: String,
    accent: Color,
    /** Optional glyph drawn beside the label to disambiguate the otherwise
     *  near-identical DRAW / PRODUCTION tiles (a lightning bolt vs a sun /
     *  battery). Null on tiles that don't need one (TODAY). Tinted with the
     *  tile's [accent] so the icon reinforces the value's status colour. */
    icon: ImageVector? = null,
) {
    // Merge the label and value into one spoken node so TalkBack announces
    // "DRAW, 1.2 kW" rather than two disconnected fragments. The icon is
    // decorative (null contentDescription) so it adds nothing to the spoken
    // string.
    val spoken = "$label, $value"
    Column(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .semantics { contentDescription = spoken }
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(14.dp),
                )
            }
            Text(text = label, style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        }
        Text(
            text = value,
            style = responsiveType(R1.numeralXl).copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun ConsumerRow(c: EnergyViewModel.Consumer, onClick: () -> Unit) {
    // Canonical boxed row: friendly name primary, entity_id secondary, current
    // draw as the trailing accent value. Tap opens its history so the user can
    // investigate 'what's drawing 1.2 kW right now?' without leaving the app.
    R1Row(
        label = c.name,
        description = c.entityId,
        boxed = true,
        onClick = onClick,
        contentDescription = "Open history for ${c.name}",
        trailing = {
            Text(
                text = formatWatts(c.watts),
                style = responsiveType(R1.bodyEmph),
                color = drawAccent(c.watts),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
    )
}

/**
 * Consumption-history section: a window picker (TODAY / 24H / 7D / 30D)
 * over a bar chart of energy used per recorder bucket. Sits between the
 * live tiles and TOP CONSUMERS so the user can read "what's happening
 * now" and "how the day/week trended" without leaving the screen.
 *
 * The data comes from HA's long-term statistics (the same recorder path
 * the dedicated Statistics surface uses) summed across every energy
 * meter, so it reflects whole-home consumption rather than any single
 * sensor.
 */
@Composable
private fun EnergyHistorySection(
    ui: EnergyViewModel.UiState,
    onSelectWindow: (EnergyViewModel.Window) -> Unit,
) {
    R1Section(
        title = "CONSUMPTION",
        topSpace = R1.space.s,
        trailing = {
            Text(
                text = ui.window.label,
                style = responsiveType(R1.labelMicro),
                color = R1.AccentWarm,
            )
        },
    ) {
        // Window chips first so the picker stays put while the chart below
        // swaps between loading / chart / empty states.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            EnergyViewModel.Window.entries.forEach { w ->
                R1Chip(
                    text = w.label,
                    variant = R1ChipVariant.Filter,
                    selected = w == ui.window,
                    onClick = { onSelectWindow(w) },
                    contentDescription = "Energy window ${w.label}",
                )
            }
        }
        Spacer(Modifier.height(R1.space.s))
        EnergyHistoryPanel(ui = ui)
    }
}

@Composable
private fun EnergyHistoryPanel(ui: EnergyViewModel.UiState) {
    val bars = ui.historyBars
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        when {
            ui.historyLoading && bars.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        // Match the chart's tier-scaled height so load -> data
                        // doesn't jump the layout.
                        .height((160.dp.value * rememberResponsiveDimens().chartScale).dp)
                        .semantics { contentDescription = "Loading energy history" },
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.width(22.dp).height(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
            }
            ui.historyError != null && bars.isEmpty() -> {
                HistoryNote(
                    "Couldn't load energy history: ${ui.historyError}. The recorder " +
                        "may be unavailable; pull REFRESH to retry.",
                )
            }
            ui.historyNoStatistics -> {
                HistoryNote(
                    "No recorder energy statistics found. Add an energy meter " +
                        "(a total-increasing kWh sensor) and Home Assistant will start " +
                        "collecting the long-term history this chart plots.",
                )
            }
            bars.isEmpty() -> {
                HistoryNote(
                    "No consumption recorded in this window yet. The recorder fills " +
                        "buckets hourly, so a freshly added meter takes a little while " +
                        "to populate.",
                )
            }
            else -> EnergyBarChart(bars = bars)
        }
    }
}

/** Small muted note shared by the empty / no-statistics / error states so
 *  they read the same and don't look like a load failure. */
@Composable
private fun HistoryNote(text: String) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.s),
    ) {
        Text(text = text, style = responsiveType(R1.labelMicro), color = R1.InkSoft)
    }
}

/**
 * Bar chart of per-bucket consumption (kWh). Bars share a y-scale fixed
 * to the window's peak so relative load is readable at a glance; a
 * press-and-hold scrub reveals the precise bucket value, mirroring the
 * History / Statistics chart affordance. FloatArray projection keeps the
 * draw phase allocation-free.
 */
@Composable
private fun EnergyBarChart(bars: List<EnergyViewModel.HistoryBar>) {
    val proj = remember(bars) {
        val values = bars.map { it.kwh }
        val peak = (values.maxOrNull() ?: 0.0).takeIf { it > 1e-9 } ?: 1.0
        val heights = FloatArray(bars.size) { i ->
            ((bars[i].kwh / peak).toFloat()).coerceIn(0f, 1f)
        }
        EnergyBarProjection(heights = heights, peak = peak, total = values.sum())
    }
    val zone = ZoneId.systemDefault()
    val firstStart = bars.first().timestamp
    val lastStart = bars.last().timestamp
    val spanMs = java.time.Duration.between(firstStart, lastStart).toMillis()
    // The user's clock format for sub-day spans, day-of-month for multi-day
    // windows. Matches the History / Statistics surfaces' axis formatting.
    val use24h = com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock()
    val fmt = if (spanMs < java.time.Duration.ofHours(36).toMillis()) {
        DateTimeFormatter.ofPattern(
            com.github.itskenny0.r1ha.ui.components.clockPattern(use24h),
            java.util.Locale.US,
        ).withZone(zone)
    } else {
        DateTimeFormatter.ofPattern("d MMM", java.util.Locale.US).withZone(zone)
    }
    val scrubIdx = remember(proj) { mutableStateOf<Int?>(null) }
    // Text alternative announced by TalkBack in place of the invisible Canvas.
    val chartDescription = remember(proj) {
        energyChartDescription(proj.heights.size, proj.total, proj.peak)
    }
    // The plot takes the flexible width (weight) so it scales with the tier; the
    // y-axis label gutter grows in step with the type scale so the larger
    // "Σ X.X kWh" still fits without clipping on big panels.
    val dimens = rememberResponsiveDimens()
    val axisWidth = (64.dp.value * dimens.typeScale).dp
    // Height steps with the tier: 160dp suits the R1, but reads as a thin
    // strip across a tablet-width panel.
    val chartHeight = (160.dp.value * dimens.chartScale).dp
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
                        val canvasW = size.width.toFloat()
                        detectTapGestures(
                            onPress = { pressOffset ->
                                val n = proj.heights.size
                                if (n > 0) {
                                    val idx = ((pressOffset.x / canvasW) * n)
                                        .toInt().coerceIn(0, n - 1)
                                    scrubIdx.value = idx
                                }
                                tryAwaitRelease()
                                scrubIdx.value = null
                            },
                        )
                    },
            ) {
                val w = size.width
                val h = size.height
                // Baseline.
                drawLine(
                    color = R1.Hairline,
                    start = Offset(0f, h - 1f),
                    end = Offset(w, h - 1f),
                    strokeWidth = 1f,
                )
                val n = proj.heights.size
                if (n == 0) return@Canvas
                // Thin gap between bars; bars get the rest. Cap the bar
                // width so a TODAY view with 2 buckets doesn't draw two
                // slabs the width of the panel.
                val slot = w / n
                val gap = (slot * 0.18f).coerceAtMost(4f)
                val barW = (slot - gap).coerceAtLeast(1f)
                val si = scrubIdx.value
                for (i in 0 until n) {
                    val bh = proj.heights[i] * (h - 2f)
                    val x = i * slot + gap / 2f
                    val top = (h - 1f) - bh
                    drawRect(
                        color = if (i == si) R1.Ink else R1.AccentWarm,
                        topLeft = Offset(x, top),
                        size = Size(barW, bh.coerceAtLeast(0f)),
                    )
                }
            }
            Spacer(Modifier.height(R1.space.xs))
            val si = scrubIdx.value
            if (si != null && si in bars.indices) {
                val sample = bars[si]
                Row {
                    Text(
                        text = fmt.format(sample.timestamp),
                        style = responsiveType(R1.labelMicro),
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatKwh(sample.kwh),
                        style = responsiveType(R1.labelMicro),
                        color = R1.AccentWarm,
                    )
                }
            } else {
                Row {
                    Text(
                        text = fmt.format(firstStart),
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = fmt.format(lastStart), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                }
            }
        }
        Spacer(Modifier.width(R1.space.s))
        Column(
            modifier = Modifier.width(axisWidth),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatKwh(proj.peak),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // Window total: the headline number a user actually wants from a
            // consumption history, distinct from the per-bucket peak above.
            Text(text = "Σ ${formatKwh(proj.total)}", style = responsiveType(R1.labelMicro), color = R1.AccentWarm, maxLines = 1)
            Text(text = "0", style = responsiveType(R1.labelMicro), color = R1.InkSoft, maxLines = 1)
        }
    }
}

/** Pre-projected bar heights (0..1) plus the window peak and total, so the
 *  draw phase never re-scans the bar list. */
private data class EnergyBarProjection(
    val heights: FloatArray,
    val peak: Double,
    val total: Double,
)

/** Placeholder shown in a stat tile when the figure is unavailable. Plain
 *  "n/a" rather than an em-dash so screen readers announce something
 *  meaningful and the copy stays em-dash-free. */
private const val NO_VALUE = "n/a"

/** Format kWh with adaptive precision: sub-kWh keeps two decimals (12 Wh
 *  reads as 0.01 kWh), larger values one, matching the rest of the app's
 *  Locale.US number formatting. */
private fun formatKwh(kwh: Double): String =
    if (kotlin.math.abs(kwh) < 10) "${"%.2f".format(java.util.Locale.US, kwh)} kWh"
    else "${"%.1f".format(java.util.Locale.US, kwh)} kWh"

/**
 * Spoken text alternative for the consumption bar chart, which is otherwise an
 * invisible [Canvas] to TalkBack. Summarises the window total, the peak bucket,
 * and the bar count so a non-sighted user gets the same headline figures a
 * sighted user reads off the chart. Pure so it is unit-tested.
 *
 * Example: "Consumption chart, 24 bars, total 12.3 kWh, peak 1.4 kWh".
 */
internal fun energyChartDescription(barCount: Int, totalKwh: Double, peakKwh: Double): String =
    "Consumption chart, $barCount bars, total ${formatKwh(totalKwh)}, peak ${formatKwh(peakKwh)}"

/** Format watts as "N W" up to ~999 W, switching to kW above. The
 *  unit suffix is uppercase to match the rest of the app's all-caps
 *  metric language. */
private fun formatWatts(w: Double): String =
    if (kotlin.math.abs(w) >= 1000) "${"%.1f".format(java.util.Locale.US, w / 1000.0)} kW"
    else "${w.toInt()} W"

/** Format a water/gas meter value with its unit. Two decimals for values
 *  below 10, one decimal above, with the unit appended. Falls back to
 *  "n/a" when the value is null. Unit defaults to "m3" when absent so the
 *  tile always reads cleanly even if the template didn't return a unit. */
private fun formatMeter(value: Double?, unit: String?): String {
    if (value == null) return NO_VALUE
    val u = unit?.takeIf { it.isNotBlank() } ?: "m3"
    return if (kotlin.math.abs(value) < 10) {
        String.format(java.util.Locale.US, "%.2f %s", value, u)
    } else {
        String.format(java.util.Locale.US, "%.1f %s", value, u)
    }
}

/** Three-band accent for draw values: green under 200 W (idle
 *  household), amber up to 1500 W (typical mid-load), red beyond
 *  (heavy load like an electric kettle or EV charging). The
 *  thresholds are deliberate guesses and could become settings. */
private fun drawAccent(w: Double?): Color = when {
    w == null -> R1.InkMuted
    w < 200 -> R1.AccentGreen
    w < 1500 -> R1.StatusAmber
    else -> R1.StatusRed
}

// ─────────────────────────────────────────────────────────────────────────────
// Energy flow (Sankey-style) visualization
//
// Additive section: renders only when there is usable source data (draw > 0
// or production > 0). When both are absent or zero the section is silent.
//
// UNVERIFIED OFFLINE: all data originates from live HA template renders.
// The Canvas-based rendering degrades silently to nothing when energyFlowBands
// returns an empty list.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Collapsible energy-flow section. Visible only when there is at least one
 * source band (draw or production > 0). Uses [energyFlowBands] to compute
 * proportional band widths from the instantaneous W figures already in
 * [EnergyViewModel.UiState], so no additional network call is made.
 *
 * UNVERIFIED OFFLINE: data is live-HA-only; section is absent when data
 * is not available.
 */
@Composable
private fun EnergyFlowSection(ui: EnergyViewModel.UiState) {
    val bands = remember(ui.productionW, ui.currentDrawW, ui.topConsumers) {
        energyFlowBands(
            productionW = ui.productionW,
            drawW = ui.currentDrawW,
            consumers = ui.topConsumers,
        )
    }
    // Render nothing when there is no usable data.
    if (bands.isEmpty()) return

    R1Section(title = "ENERGY FLOW", topSpace = R1.space.s) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = R1.space.m, vertical = R1.space.m)
                .semantics {
                    contentDescription = "Energy flow diagram, ${bands.size} bands"
                },
        ) {
            EnergyFlowCanvas(bands = bands)
        }
    }
}

/**
 * Canvas-drawn Sankey-style flow diagram. Sources appear on the left as
 * proportional-height blocks, HOME in the centre, and consumers on the
 * right. Each band is a filled trapezoid (straight-line segmented band)
 * running from source to HOME (left-side bands) or from HOME to consumer
 * (right-side bands). Band height is proportional to [FlowBand.frac].
 *
 * The palette cycles through a fixed set of accent colours so each band
 * gets a distinct tint without needing a dynamic palette. Both the left-
 * side (source) and right-side (consumer) bands share the same total
 * height (the Canvas height), so the drawing stays compact.
 *
 * UNVERIFIED OFFLINE: rendering is exercised only at compile time; live
 * visual appearance requires a device with real HA data.
 */
@Composable
private fun EnergyFlowCanvas(bands: List<FlowBand>) {
    val palette = flowPalette()
    val flowHeight = (120.dp.value * rememberResponsiveDimens().chartScale).dp
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(flowHeight),
    ) {
        val w = size.width
        val h = size.height
        val nodeW = w * 0.12f
        val leftX = 0f
        val midX = w * 0.44f
        val rightX = w * 0.88f
        val gap = h * 0.06f

        // Separate source (left) and consumer (right) bands.
        val sourceBands = bands.filter { it.destLabel == "HOME" }
        val consumerBands = bands.filter { it.sourceLabel == "HOME" }

        // Layout helper: assign y-start for each band stacked top-to-bottom
        // with a small gap between bands.
        fun layoutBands(list: List<FlowBand>): List<Float> {
            if (list.isEmpty()) return emptyList()
            val totalGap = gap * (list.size - 1)
            val usable = h - totalGap
            val starts = mutableListOf<Float>()
            var y = 0f
            for (band in list) {
                starts += y
                y += (band.frac * usable).toFloat() + gap
            }
            return starts
        }

        val sourceStarts = layoutBands(sourceBands)
        val consumerStarts = layoutBands(consumerBands)

        // Draw source-node blocks on the left.
        sourceBands.forEachIndexed { i, band ->
            val color = palette[band.colorIndex % palette.size]
            val bh = (band.frac * h).toFloat()
            drawRect(
                color = color,
                topLeft = Offset(leftX, sourceStarts[i]),
                size = Size(nodeW, bh),
            )
        }

        // HOME node in the centre: full-height neutral block.
        drawRect(
            color = R1.AccentNeutral.copy(alpha = 0.25f),
            topLeft = Offset(midX, 0f),
            size = Size(nodeW, h),
        )

        // Draw consumer-node blocks on the right.
        consumerBands.forEachIndexed { i, band ->
            val color = palette[band.colorIndex % palette.size]
            val bh = (band.frac * h).toFloat()
            drawRect(
                color = color,
                topLeft = Offset(rightX, consumerStarts[i]),
                size = Size(nodeW, bh),
            )
        }

        // Draw connecting bands: source -> HOME (left half of canvas).
        // Each band is a filled quadrilateral (two top points, two bottom points)
        // from the source node's right edge to the HOME node's left edge, with
        // the y coordinates matching the source-side stacked layout on the left
        // and the same stacked layout mirrored on the HOME node left edge.
        val usableLeft = h - gap * (sourceBands.size - 1).coerceAtLeast(0)
        var homeLY = 0f
        sourceBands.forEachIndexed { i, band ->
            val color = palette[band.colorIndex % palette.size].copy(alpha = 0.3f)
            val srcBH = (band.frac * h).toFloat()
            val homeBH = (band.frac * usableLeft).toFloat()
            val srcTop = sourceStarts[i]
            val srcBot = srcTop + srcBH
            val homeTop = homeLY
            val homeBot = homeLY + homeBH
            homeLY = homeBot + gap
            val path = Path().apply {
                moveTo(leftX + nodeW, srcTop)
                lineTo(midX, homeTop)
                lineTo(midX, homeBot)
                lineTo(leftX + nodeW, srcBot)
                close()
            }
            drawPath(path = path, color = color, style = Fill)
        }

        // Draw connecting bands: HOME -> consumer (right half of canvas).
        val usableRight = h - gap * (consumerBands.size - 1).coerceAtLeast(0)
        var homeRY = 0f
        consumerBands.forEachIndexed { i, band ->
            val color = palette[band.colorIndex % palette.size].copy(alpha = 0.3f)
            val consBH = (band.frac * h).toFloat()
            val homeBH = (band.frac * usableRight).toFloat()
            val homeTop = homeRY
            val homeBot = homeRY + homeBH
            homeRY = homeBot + gap
            val consTop = consumerStarts[i]
            val consBot = consTop + consBH
            val path = Path().apply {
                moveTo(midX + nodeW, homeTop)
                lineTo(rightX, consTop)
                lineTo(rightX, consBot)
                lineTo(midX + nodeW, homeBot)
                close()
            }
            drawPath(path = path, color = color, style = Fill)
        }
    }
    // Label row below the canvas: sources on left, HOME centre, top consumer on right.
    Spacer(Modifier.height(R1.space.xs))
    Row(modifier = Modifier.fillMaxWidth()) {
        val sourceBands = bands.filter { it.destLabel == "HOME" }
        val consumerBands = bands.filter { it.sourceLabel == "HOME" }
        Column(modifier = Modifier.weight(1f)) {
            for (b in sourceBands) {
                Text(
                    text = b.sourceLabel,
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text(
            text = "HOME",
            style = responsiveType(R1.labelMicro),
            color = R1.AccentNeutral,
            modifier = Modifier.weight(1f),
        )
        Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.End) {
            for (b in consumerBands.take(3)) {
                Text(
                    text = b.destLabel,
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Consumer distribution bar
//
// Additive section: proportional horizontal bar of top consumers.
// Renders only when consumers are present. No new data fetch.
//
// UNVERIFIED OFFLINE: data originates from live HA template renders.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Proportional horizontal bar showing each top consumer's share of the total
 * measured consumer wattage. Each consumer is a coloured segment; a compact
 * legend maps colours to names.
 *
 * Renders nothing when [EnergyViewModel.UiState.topConsumers] is empty.
 * UNVERIFIED OFFLINE: data is live-HA-only.
 */
@Composable
private fun ConsumerBreakdownSection(ui: EnergyViewModel.UiState) {
    val segs = remember(ui.topConsumers) {
        consumerDistributionSegments(ui.topConsumers)
    }
    if (segs.isEmpty()) return

    val palette = flowPalette()

    R1Section(title = "CONSUMER BREAKDOWN", topSpace = R1.space.s) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = R1.space.m, vertical = R1.space.m),
            verticalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            // Distribution bar.
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(16.dp)
                    .clip(R1.ShapeS)
                    .semantics {
                        contentDescription = "Consumer distribution bar, ${segs.size} segments"
                    },
            ) {
                val w = size.width
                val h = size.height
                var x = 0f
                for (seg in segs) {
                    val segW = (seg.share * w).toFloat()
                    val color = palette[seg.colorIndex % palette.size]
                    drawRect(color = color, topLeft = Offset(x, 0f), size = Size(segW, h))
                    x += segW
                }
            }
            // Compact legend: colour dot + name + share%.
            Column(verticalArrangement = Arrangement.spacedBy(R1.space.xxs)) {
                for (seg in segs) {
                    val color = palette[seg.colorIndex % palette.size]
                    val pct = String.format(java.util.Locale.US, "%.0f%%", seg.share * 100.0)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(color, R1.ShapeRound),
                        )
                        Text(
                            text = seg.name,
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = pct,
                            style = responsiveType(R1.labelMicro),
                            color = color,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Fixed accent palette for flow bands and consumer segments. Cycles when
 * there are more bands than colours. Chosen to be legible on the dark
 * background and distinct from each other.
 *
 * Extracted as a function (not a top-level val) so Compose's Color
 * objects are allocated lazily only when the section is rendered.
 */
@Composable
private fun flowPalette(): List<Color> = listOf(
    R1.AccentGreen,
    R1.AccentCool,
    R1.AccentWarm,
    R1.StatusAmber,
    R1.AccentNeutral,
    R1.StatusRed,
)

/**
 * Write [csv] to the app's cache/share directory and fire an ACTION_SEND
 * intent so the user can route the file to any app that accepts text/csv.
 * Mirrors [EnergyShareSnapshot.shareAsPng]: reuses the same FileProvider
 * authority and cache/share directory so no manifest changes are needed.
 */
private fun shareCsvText(context: android.content.Context, csv: String) {
    val cache = context.cacheDir.resolve("share")
    if (!cache.exists()) cache.mkdirs()
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    val file = File(cache, "r1ha-energy-$stamp.csv")
    FileOutputStream(file).use { out -> out.write(csv.toByteArray(Charsets.UTF_8)) }
    val authority = "${context.packageName}.updates"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_STREAM, uri)
        putExtra(Intent.EXTRA_TITLE, "R1HA energy export")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(intent, "Export energy CSV").apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(chooser)
}
