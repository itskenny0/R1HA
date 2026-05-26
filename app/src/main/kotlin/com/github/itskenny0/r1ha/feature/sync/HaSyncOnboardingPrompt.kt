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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.sync.HaSettingsSync
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * First-run prompt that introduces multi-device sync and lets the user
 * either pull an existing payload (importing settings from another
 * device) or seed HA with this device's current state. Renders only
 * when:
 *
 *   - the user has a server configured (no point asking before
 *     onboarding finishes),
 *   - the WS is currently Connected (so the probe + first sync can
 *     actually run), and
 *   - the user hasn't dismissed/accepted the prompt before (the
 *     [AppSettings.integrations.haSyncPromptSeen] flag flips true on
 *     any choice).
 *
 * The prompt is an inline full-screen overlay rather than a Compose
 * `Dialog` so it sits in the activity's window and doesn't suffer the
 * same key-routing quirks Dialog has with hardware key events.
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
    val scope = rememberCoroutineScope()

    // Probe HA on first show so the prompt can offer "import" only when
    // a remote payload actually exists. Bounded by the probe call's
    // own timeout (callWsExpectingPayload caps at 15 s); we don't block
    // the UI — null = "we'll just show push as the only path".
    LaunchedEffect(Unit) {
        if (syncManager != null) {
            remoteTimestamp = runCatching { syncManager.probeRemoteExists() }.getOrNull()
        }
        probed = true
    }

    // Per-category exclude set, locally edited inside the prompt. Seeded
    // from current settings (typically empty on first run).
    val excluded = remember {
        mutableStateOf(settings.integrations.haSyncExcludedCategories)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.95f))
            // Eat taps on the backdrop so the underlying card stack doesn't
            // receive them while the overlay is up. No-op handler — the user
            // dismisses by tapping NOT NOW, not by tapping outside.
            .r1Pressable(onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // Cap the card to the available height so the R1's 320 dp
                // tall display doesn't get a prompt whose buttons fall off
                // the bottom. The inner content scrolls if it overflows.
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
                text = "Mirror your preferences via Home Assistant so any R1 or " +
                    "phone signed into the same HA user shares the same theme, " +
                    "pages, favourites, and overrides.",
                style = R1.body,
                color = R1.Ink,
            )
            Text(
                text = "Storage is HA's per-user JSON bucket — no add-on needed. " +
                    "Server URL, iBeacon, webhook, and MQTT stay device-local.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = "INCLUDE", style = R1.labelMicro, color = R1.InkSoft)
            // Per-category opt-out chips inside the prompt — the user can
            // pre-trim what gets shared before they commit to import/push.
            SyncCategory.entries.forEach { category ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
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
            Spacer(Modifier.height(8.dp))
            if (!probed) {
                Text(
                    text = "Checking HA for existing settings…",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            } else if (remoteTimestamp != null) {
                Text(
                    text = "Found a sync payload on HA. Import it, or replace it " +
                        "with this device's settings.",
                    style = R1.body,
                    color = R1.InkSoft,
                )
            } else {
                Text(
                    text = "No sync payload on HA yet. Pushing this device will " +
                        "seed the shared state.",
                    style = R1.body,
                    color = R1.InkSoft,
                )
            }
            Spacer(Modifier.height(6.dp))
            // Action row. IMPORT only renders when probe found a remote — it
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
private fun PromptButton(
    text: String,
    tint: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
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
