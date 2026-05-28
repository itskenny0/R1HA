package com.github.itskenny0.r1ha.feature.sync

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.sync.HaSettingsSync
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * First-run prompt that introduces multi-device sync after onboarding has
 * completed and the WS is connected. Lets the user either pull an existing
 * payload (importing settings from another device) or seed HA with this
 * device's current state. Renders only when:
 *
 *   - the user has a server configured,
 *   - the WS is currently Connected (so the probe can run),
 *   - the user hasn't dismissed or accepted the prompt before
 *     (haSyncPromptSeen flips true on any choice).
 *
 * Inline full-screen overlay rather than a Compose Dialog so it sits in
 * the activity's window and doesn't suffer the key-routing quirks Dialog
 * has with hardware key events.
 */
@Composable
fun HaSyncOnboardingPrompt(
    settings: AppSettings,
    connection: ConnectionState,
    onMarkSeen: () -> Unit,
    onChooseImport: (excludedCategories: Set<String>) -> Unit,
    onChoosePush: (excludedCategories: Set<String>) -> Unit,
) {
    val visible = settings.server != null &&
        !settings.integrations.haSyncPromptSeen &&
        connection is ConnectionState.Connected
    if (!visible) return

    val context = LocalContext.current
    val syncManager: HaSettingsSync? = remember(context) {
        (context.applicationContext as? App)?.graph?.haSettingsSync
    }
    var remoteTimestamp by remember { mutableStateOf<Long?>(null) }
    var probed by remember { mutableStateOf(false) }
    var probeError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Probe HA on first show so the prompt can offer IMPORT only when a
    // remote payload actually exists. Bounded by the probe call's own
    // timeout (callWsExpectingPayload caps at 15s); UI is not blocked.
    // probeError is surfaced inline so the user knows whether the network
    // round-trip succeeded vs. just returned "no payload".
    LaunchedEffect(Unit) {
        if (syncManager != null) {
            val result = runCatching { syncManager.probeRemoteExists() }
            remoteTimestamp = result.getOrNull()
            probeError = result.exceptionOrNull()?.message
        }
        probed = true
    }

    val excluded = remember {
        mutableStateOf(settings.integrations.haSyncExcludedCategories)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.95f))
            // Eat taps on the backdrop so the underlying card stack doesn't
            // receive them while the overlay is up. No-op handler; the user
            // dismisses by tapping NOT NOW.
            .r1Pressable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // Cap to the available height so the R1's 320dp tall display
                // doesn't get a prompt whose buttons fall off the bottom.
                // Inner content scrolls if it overflows.
                .heightIn(max = 600.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, RoundedCornerShape(12.dp))
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = "SYNC ACROSS DEVICES", style = R1.labelMicro, color = R1.AccentWarm)
            Text(
                text = "Mirror your preferences via Home Assistant so any R1 " +
                    "or phone signed into the same HA user shares the same " +
                    "theme, pages, favourites, and overrides.",
                style = R1.body,
                color = R1.Ink,
            )
            Text(
                text = "Storage is HA's per-user JSON bucket; no add-on " +
                    "needed. Server URL, iBeacon, webhook, and MQTT stay " +
                    "device-local.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(2.dp))

            // Probe-state pill keeps the user oriented while the network
            // call is running, and reads as a finished decision once we
            // know whether a remote payload exists.
            ProbeStatusRow(
                probed = probed,
                hasRemote = remoteTimestamp != null,
                error = probeError,
            )

            Spacer(Modifier.height(4.dp))
            Text(text = "INCLUDE", style = R1.labelMicro, color = R1.InkSoft)
            // Per-category opt-out chips inside the prompt; the user can
            // pre-trim what gets shared before committing to import/push.
            SyncCategory.entries.forEach { category ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .r1Pressable(
                            onClick = {
                                excluded.value = excluded.value.toMutableSet().apply {
                                    if (contains(category.name)) remove(category.name)
                                    else add(category.name)
                                }
                            },
                            hapticOnClick = false,
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = category.displayLabel,
                        style = R1.body,
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                    )
                    val included = !excluded.value.contains(category.name)
                    R1Switch(
                        checked = included,
                        onCheckedChange = { v ->
                            excluded.value = excluded.value.toMutableSet().apply {
                                if (v) remove(category.name) else add(category.name)
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            // Action row. IMPORT only renders when probe found a remote; it
            // would be a no-op otherwise.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (probed && remoteTimestamp != null) {
                    PromptButton(
                        text = "IMPORT FROM HA",
                        tint = R1.AccentWarm,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            onChooseImport(excluded.value)
                            onMarkSeen()
                        },
                    )
                }
                PromptButton(
                    text = if (remoteTimestamp != null) "PUSH THIS DEVICE" else "ENABLE SYNC",
                    tint = R1.AccentGreen,
                    modifier = Modifier.weight(1f),
                    onClick = {
                        onChoosePush(excluded.value)
                        onMarkSeen()
                    },
                )
            }
            Spacer(Modifier.height(2.dp))
            PromptButton(
                text = "NOT NOW",
                tint = R1.InkMuted,
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    onMarkSeen()
                },
            )
        }
    }
}

@Composable
private fun ProbeStatusRow(probed: Boolean, hasRemote: Boolean, error: String?) {
    val (label, tint, body) = when {
        !probed -> Triple("CHECKING", R1.StatusAmber, "Probing HA for an existing sync payload…")
        error != null -> Triple("PROBE FAILED", R1.StatusRed, error)
        hasRemote -> Triple(
            "REMOTE FOUND",
            R1.AccentGreen,
            "Import the existing payload, or replace it with this device.",
        )
        else -> Triple(
            "NEW HOUSE",
            R1.AccentWarm,
            "No payload on HA yet. Pushing this device seeds the shared state.",
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(R1.ShapeRound)
                .background(tint),
        )
        Spacer(Modifier.size(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = R1.labelMicro, color = tint)
            Spacer(Modifier.height(2.dp))
            Text(text = body, style = R1.body, color = R1.InkSoft)
        }
    }
}

@Composable
private fun PromptButton(
    text: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.labelMicro, color = tint)
    }
}
