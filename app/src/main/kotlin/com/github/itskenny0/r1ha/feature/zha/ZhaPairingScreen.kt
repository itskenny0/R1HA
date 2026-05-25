package com.github.itskenny0.r1ha.feature.zha

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Zigbee pairing surface — opens the network for joins, surfaces newly-discovered
 * entities as they enrol. Reachable from Settings > POWER TOOLS > Zigbee pair.
 *
 * The screen drives [ZhaPairingViewModel] which auto-detects which Zigbee
 * integration HA has installed (ZHA / Zigbee2MQTT / deCONZ) and fires the
 * appropriate permit-join service.
 */
@Composable
fun ZhaPairingScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: ZhaPairingViewModel = viewModel(factory = ZhaPairingViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.detect() }
    // Configure-entity sheet state — opens when the user taps a new entity row.
    var configuringEntityId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "ZIGBEE PAIR", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.available.isEmpty() -> NoBackendsEmpty(ui.error)
                else -> Body(ui, vm, onConfigure = { configuringEntityId = it })
            }
        }
    }

    // Configure-entity sheet — opens when the user taps a NEW ENTITIES row to
    // rename + assign area server-side. Uses the shared composable so the same
    // flow can be reused from Search and Favourites Picker in a future cycle.
    val target = configuringEntityId
    if (target != null) {
        com.github.itskenny0.r1ha.feature.entityconfig.ConfigureEntitySheet(
            haRepository = haRepository,
            entityId = target,
            // We don't have a fresh friendly_name for newly-discovered entities
            // until the next /api/states tick lands; leave the input empty so
            // the user picks the name they want without a confusing pre-fill
            // (the integration-supplied default is rarely what they want).
            initialName = "",
            onDismiss = { configuringEntityId = null },
        )
    }
}

@Composable
private fun NoBackendsEmpty(error: String?) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "NO ZIGBEE INTEGRATION", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.size(6.dp))
        Text(
            text = error
                ?: "Couldn't find any of: ZHA, Zigbee2MQTT, deCONZ. Install one of them under HA's Settings → Devices & services first.",
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun Body(
    ui: ZhaPairingViewModel.UiState,
    vm: ZhaPairingViewModel,
    onConfigure: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        // Backend chips — render when more than one is detected so the user
        // can pick which network to open. Single-backend installs default to
        // that one and skip the picker.
        if (ui.available.size > 1) {
            Text(text = "BACKEND", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.size(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (b in ui.available) {
                    val active = b == ui.selected
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(if (active) R1.AccentCool.copy(alpha = 0.18f) else R1.SurfaceMuted)
                            .border(
                                1.dp,
                                if (active) R1.AccentCool.copy(alpha = 0.6f) else R1.Hairline,
                                R1.ShapeS,
                            )
                            .r1Pressable(onClick = { vm.setBackend(b) })
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = b.label,
                            style = R1.labelMicro,
                            color = if (active) R1.AccentCool else R1.Ink,
                        )
                    }
                }
            }
            Spacer(Modifier.size(12.dp))
        }

        // Duration picker — preset 60 / 120 / 180 s. Most coordinators cap at
        // 254 s; the presets cover the common cases without a slider's
        // touch-precision penalty on the R1.
        Text(text = "DURATION", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.size(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (preset in listOf(60, 120, 180)) {
                val active = ui.durationSec == preset
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(if (active) R1.AccentWarm.copy(alpha = 0.18f) else R1.SurfaceMuted)
                        .border(
                            1.dp,
                            if (active) R1.AccentWarm.copy(alpha = 0.6f) else R1.Hairline,
                            R1.ShapeS,
                        )
                        .r1Pressable(onClick = { vm.setDuration(preset) })
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "${preset}s",
                        style = R1.labelMicro,
                        color = if (active) R1.AccentWarm else R1.Ink,
                    )
                }
            }
        }
        Spacer(Modifier.size(12.dp))

        // Action row — PERMIT JOIN when window is closed, CANCEL when open.
        // The button label switches based on remainingSec so there's no
        // ambiguity about what tapping it does.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(R1.ShapeS)
                    .background(
                        if (ui.remainingSec > 0) R1.StatusAmber.copy(alpha = 0.18f)
                        else R1.SurfaceMuted,
                    )
                    .border(
                        1.dp,
                        if (ui.remainingSec > 0) R1.StatusAmber.copy(alpha = 0.6f) else R1.Hairline,
                        R1.ShapeS,
                    )
                    .r1Pressable(onClick = {
                        if (ui.remainingSec > 0) vm.cancelPermit() else vm.permitJoin()
                    })
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.remainingSec > 0) "CANCEL · ${ui.remainingSec}s LEFT"
                    else "PERMIT JOIN",
                    style = R1.labelMicro,
                    color = if (ui.remainingSec > 0) R1.StatusAmber else R1.AccentWarm,
                )
            }
        }

        if (ui.info != null) {
            Spacer(Modifier.size(8.dp))
            Text(text = ui.info ?: "", style = R1.labelMicro, color = R1.InkSoft)
        }
        if (ui.error != null) {
            Spacer(Modifier.size(8.dp))
            Text(text = ui.error ?: "", style = R1.body, color = R1.StatusAmber)
        }

        Spacer(Modifier.size(16.dp))
        Text(text = "NEW ENTITIES", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.size(4.dp))
        when {
            ui.newEntityIds.isNotEmpty() -> {
                Text(
                    text = "${ui.newEntityIds.size} new since pairing window opened. Tap a row to rename + assign area.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.size(6.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(ui.newEntityIds, key = { it }) { id ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(onClick = { onConfigure(id) })
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = id,
                                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                                color = R1.Ink,
                                maxLines = 1,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(text = "CONFIGURE", style = R1.labelMicro, color = R1.AccentWarm)
                        }
                    }
                }
            }
            ui.remainingSec > 0 || ui.baselineEntityIds.isNotEmpty() -> {
                Text(
                    text = if (ui.remainingSec > 0)
                        "Waiting for devices… new entities will appear here as HA discovers them."
                    else
                        "Pairing window closed; no new entities detected yet. Some devices take 30-60 s to enrol after joining.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            else -> {
                Text(
                    text = "Tap PERMIT JOIN to open the Zigbee network. Power your new device on while the timer is counting; HA will discover it and surface it here.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}
