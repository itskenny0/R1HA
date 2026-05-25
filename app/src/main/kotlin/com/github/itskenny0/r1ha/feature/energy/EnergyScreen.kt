package com.github.itskenny0.r1ha.feature.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Energy summary surface — a four-tile readout of the most useful
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
    /** Tap a TOP CONSUMERS row → open the full-screen History view for
     *  that sensor's entity_id. Default no-op so previews / tests don't
     *  need to thread it through. */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: EnergyViewModel = viewModel(factory = EnergyViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)
    // 30 s auto-refresh — energy figures change slowly relative to
    // wall-clock so any tighter would be wasted server work.
    AutoRefresh(everyMillis = 30_000L) { vm.refresh() }
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
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
                            })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "SHARE", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                    Spacer(Modifier.width(6.dp))
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
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // ── DRAW + PRODUCTION row ──────────────────────────────
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "DRAW",
                        value = ui.currentDrawW?.let { formatWatts(it) } ?: "—",
                        accent = drawAccent(ui.currentDrawW),
                    )
                    BigStatTile(
                        modifier = Modifier.weight(1f),
                        label = "PRODUCTION",
                        value = ui.productionW?.let { formatWatts(it) } ?: "—",
                        accent = if ((ui.productionW ?: 0.0) > 0) R1.AccentGreen else R1.InkMuted,
                    )
                }
                // ── TODAY (kWh) row ────────────────────────────────────
                BigStatTile(
                    modifier = Modifier.fillMaxWidth(),
                    label = "TODAY",
                    value = ui.todayKwh?.let { "${"%.2f".format(it)} kWh" } ?: "—",
                    accent = if ((ui.todayKwh ?: 0.0) > 0) R1.AccentWarm else R1.InkMuted,
                )
                // ── TOP CONSUMERS ──────────────────────────────────────
                if (ui.topConsumers.isNotEmpty()) {
                    Spacer(Modifier.size(4.dp))
                    var consumersExpanded by androidx.compose.runtime.remember {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "TOP CONSUMERS",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            modifier = Modifier.weight(1f),
                        )
                        if (ui.topConsumers.size > 5) {
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable(onClick = { consumersExpanded = !consumersExpanded })
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = if (consumersExpanded) {
                                        "COLLAPSE"
                                    } else {
                                        "SHOW ALL (${ui.topConsumers.size})"
                                    },
                                    style = R1.labelMicro,
                                    color = R1.InkSoft,
                                )
                            }
                        }
                    }
                    val visible = if (consumersExpanded) ui.topConsumers else ui.topConsumers.take(5)
                    for (c in visible) {
                        ConsumerRow(c, onClick = { onOpenHistory(c.entityId) })
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
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "No `device_class=power` sensors found. Add a power " +
                                "integration (smart meter, smart plug, energy monitor) and " +
                                "the dashboard will populate.",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
                if (ui.error != null && ui.currentDrawW == null && ui.todayKwh == null) {
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
        } // AdaptiveContent
    }
}

/** Wide stat tile — bold value, small label above. Same shape as the
 *  metric tiles on the TODAY dashboard so the visual language is
 *  consistent. */
@Composable
private fun BigStatTile(
    modifier: Modifier,
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
) {
    Column(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            // Tap a consumer → open its history. Lets the user investigate
            // 'what's drawing 1.2 kW right now?' without leaving the app
            // to dig through HA's web UI.
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = c.name, style = R1.body, color = R1.Ink, maxLines = 1)
            Text(
                text = c.entityId,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatWatts(c.watts),
            style = R1.body.copy(fontWeight = FontWeight.SemiBold),
            color = drawAccent(c.watts),
        )
    }
}

/** Format watts as "Nw" up to ~999 W, switching to kW above. The
 *  unit suffix is uppercase to match the rest of the app's all-caps
 *  metric language. */
private fun formatWatts(w: Double): String =
    if (kotlin.math.abs(w) >= 1000) "${"%.1f".format(w / 1000.0)} kW"
    else "${w.toInt()} W"

/** Three-band accent for draw values — green under 200 W (idle
 *  household), amber up to 1500 W (typical mid-load), red beyond
 *  (heavy load like an electric kettle or EV charging). The
 *  thresholds are deliberate guesses and could become settings. */
private fun drawAccent(w: Double?): androidx.compose.ui.graphics.Color = when {
    w == null -> R1.InkMuted
    w < 200 -> R1.AccentGreen
    w < 1500 -> R1.StatusAmber
    else -> R1.StatusRed
}
