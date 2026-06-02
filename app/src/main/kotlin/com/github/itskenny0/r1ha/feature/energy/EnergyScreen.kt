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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
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
                        text = if (ui.loading) "…" else "REFRESH",
                        variant = R1ChipVariant.Action,
                        onClick = { vm.refresh() },
                        contentDescription = "Refresh energy",
                    )
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = R1.space.m, vertical = R1.space.s)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                // ── DRAW + PRODUCTION row ──────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "DRAW",
                        value = ui.currentDrawW?.let { formatWatts(it) } ?: NO_VALUE,
                        accent = drawAccent(ui.currentDrawW),
                    )
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "PRODUCTION",
                        value = ui.productionW?.let { formatWatts(it) } ?: NO_VALUE,
                        accent = if ((ui.productionW ?: 0.0) > 0) R1.AccentGreen else R1.InkMuted,
                    )
                }
                // ── TODAY (kWh) row ────────────────────────────────────
                // Prefer the recorder-derived today total (HA-accurate sum of
                // per-bucket consumption since midnight); fall back to the
                // live-template sum until the first history fetch lands.
                val today = ui.statsTodayKwh ?: ui.todayKwh
                BigStatTile(
                    modifier = Modifier.fillMaxWidth(),
                    label = "TODAY",
                    value = today?.let { formatKwh(it) } ?: NO_VALUE,
                    accent = if ((today ?: 0.0) > 0) R1.AccentWarm else R1.InkMuted,
                )
                // ── CONSUMPTION HISTORY ────────────────────────────────
                EnergyHistorySection(
                    ui = ui,
                    onSelectWindow = { vm.setWindow(it) },
                )
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
                            style = R1.labelMicro,
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
                            style = R1.labelMicro,
                            color = R1.StatusRed,
                        )
                    }
                }
                Spacer(Modifier.height(R1.space.xl))
            }
        } // AdaptiveContent
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
) {
    // Merge the label and value into one spoken node so TalkBack announces
    // "DRAW, 1.2 kW" rather than two disconnected fragments.
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
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
        Text(
            text = value,
            style = R1.numeralXl.copy(fontWeight = FontWeight.SemiBold),
            color = accent,
            maxLines = 1,
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
                style = R1.bodyEmph,
                color = drawAccent(c.watts),
                maxLines = 1,
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
                style = R1.labelMicro,
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
                        .height(160.dp)
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
        Text(text = text, style = R1.labelMicro, color = R1.InkSoft)
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
    // HH:mm for sub-day spans, day-of-month for multi-day windows. Matches
    // the History / Statistics surfaces' axis formatting.
    val fmt = if (spanMs < java.time.Duration.ofHours(36).toMillis()) {
        DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
    } else {
        DateTimeFormatter.ofPattern("d MMM").withZone(zone)
    }
    val scrubIdx = remember(proj) { mutableStateOf<Int?>(null) }
    // Text alternative announced by TalkBack in place of the invisible Canvas.
    val chartDescription = remember(proj) {
        energyChartDescription(proj.heights.size, proj.total, proj.peak)
    }
    Row {
        Column(modifier = Modifier.weight(1f)) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp)
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
                        style = R1.labelMicro,
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = formatKwh(sample.kwh),
                        style = R1.labelMicro,
                        color = R1.AccentWarm,
                    )
                }
            } else {
                Row {
                    Text(
                        text = fmt.format(firstStart),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        modifier = Modifier.weight(1f),
                    )
                    Text(text = fmt.format(lastStart), style = R1.labelMicro, color = R1.InkSoft)
                }
            }
        }
        Spacer(Modifier.width(R1.space.s))
        Column(
            modifier = Modifier.width(64.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = formatKwh(proj.peak),
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            // Window total: the headline number a user actually wants from a
            // consumption history, distinct from the per-bucket peak above.
            Text(text = "Σ ${formatKwh(proj.total)}", style = R1.labelMicro, color = R1.AccentWarm, maxLines = 1)
            Text(text = "0", style = R1.labelMicro, color = R1.InkSoft, maxLines = 1)
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
