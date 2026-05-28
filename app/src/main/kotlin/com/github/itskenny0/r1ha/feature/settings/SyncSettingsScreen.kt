package com.github.itskenny0.r1ha.feature.settings

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.sync.HaSettingsSync
import com.github.itskenny0.r1ha.core.sync.SyncCategory
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Multi-device settings sync surface. Top-level Settings subpage because
 * the feature has cross-device implications (last-write-wins, opt-out
 * categories) and the diagnostic block needs room to breathe.
 *
 * Layout shape:
 *   1. Master toggle + summary line.
 *   2. Conflict-resolution model spelled out (last-write-wins).
 *   3. SYNC NOW chip + manual-only escape hatch.
 *   4. Live stats card (state pill, last pull/push, last error).
 *   5. Reset-and-rebroadcast (push local snapshot, overwriting remote).
 *   6. WHAT TO SYNC per-category opt-out list.
 */
@Composable
fun SyncSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val syncManager: HaSettingsSync? = remember(context) {
        (context.applicationContext as? App)?.graph?.haSettingsSync
    }
    val stats by (syncManager?.stats?.collectAsState()
        ?: remember { mutableStateOf(HaSettingsSync.Stats()) })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        R1TopBar(title = "SYNC", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Mirror your preferences to and from Home Assistant " +
                        "so multiple R1 or phone installs signed into the " +
                        "same HA user stay in sync. Storage is HA's per-user " +
                        "JSON bucket; no add-on or integration to install. " +
                        "Server URL, iBeacon, webhook, and MQTT are " +
                        "device-local and never synced.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            // ── Master toggle ─────────────────────────────────────────
            item {
                MasterRow(
                    label = "Enable",
                    description = if (s.integrations.haSyncEnabled) {
                        if (s.integrations.haSyncManualOnly) {
                            "Manual only. PULL / PUSH chips below."
                        } else {
                            "Auto. Every edit + every ${s.integrations.haSyncIntervalSec}s."
                        }
                    } else {
                        "Off. Settings stay local to this device."
                    },
                    checked = s.integrations.haSyncEnabled,
                    onCheckedChange = { v ->
                        vm.updateIntegrations { it.copy(haSyncEnabled = v) }
                    },
                )
            }

            if (s.integrations.haSyncEnabled) {
                // ── Conflict model ────────────────────────────────────
                item { ConflictModelCard() }

                // ── Live stats ────────────────────────────────────────
                item { SubHeader("STATUS") }
                item { StatsCard(stats) }

                // ── Manual triggers ───────────────────────────────────
                item { SubHeader("MANUAL") }
                item {
                    SyncActionsRow(
                        onSyncNow = { syncManager?.pullNow() },
                        onPushNow = { syncManager?.pushNow() },
                    )
                }
                item {
                    SwitchInlineRow(
                        title = "Manual only",
                        subtitle = if (s.integrations.haSyncManualOnly) {
                            "No auto-pull or auto-push. SYNC / PUSH chips still work."
                        } else {
                            "Sync runs automatically on edit and on interval."
                        },
                        checked = s.integrations.haSyncManualOnly,
                        onCheckedChange = { v ->
                            vm.updateIntegrations { it.copy(haSyncManualOnly = v) }
                        },
                    )
                }

                // ── Interval ──────────────────────────────────────────
                item { SubHeader("INTERVAL") }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                    ) {
                        Text(
                            text = "How often to check HA for changes from other " +
                                "devices. Pushes from this device happen on " +
                                "every edit (debounced ~5s).",
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(bottom = 6.dp),
                        )
                        IntervalStepperRow(
                            seconds = s.integrations.haSyncIntervalSec,
                            onChange = { v ->
                                vm.updateIntegrations { it.copy(haSyncIntervalSec = v) }
                            },
                        )
                    }
                }

                // ── Reset and rebroadcast ─────────────────────────────
                item { SubHeader("RESET") }
                item {
                    ResetRebroadcastRow(
                        onConfirm = { syncManager?.pushNow() },
                    )
                }

                // ── What to sync ──────────────────────────────────────
                item { SubHeader("WHAT TO SYNC") }
                item {
                    Text(
                        text = "All categories sync by default. Switch a category " +
                            "off to keep its values local to this device; the " +
                            "remote value won't overwrite local on pull, and " +
                            "local edits in that category won't be pushed to HA.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                }
                items(SyncCategory.entries) { category ->
                    val included = !s.integrations.haSyncExcludedCategories.contains(category.name)
                    CategoryRow(
                        category = category,
                        included = included,
                        onChange = { newIncluded ->
                            vm.updateIntegrations { prev ->
                                val next = prev.haSyncExcludedCategories.toMutableSet()
                                if (newIncluded) next.remove(category.name)
                                else next.add(category.name)
                                prev.copy(haSyncExcludedCategories = next)
                            }
                        },
                    )
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        }
    }
}

@Composable
private fun MasterRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (checked) R1.AccentGreen.copy(alpha = 0.45f) else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(description, style = R1.body, color = R1.InkMuted)
        }
        R1Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** One-liner explaining how concurrent edits resolve. Keeps users from
 *  fearing a silent merge that doesn't exist. */
@Composable
private fun ConflictModelCard() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 6.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "CONFLICT MODEL",
            style = R1.labelMicro,
            color = R1.AccentWarm,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Last-write-wins by wall-clock timestamp. Whichever device " +
                "edited most recently overwrites the older value on the " +
                "next pull. No per-field merging.",
            style = R1.body,
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun SyncActionsRow(
    onSyncNow: () -> Unit,
    onPushNow: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ActionChip(
            label = "SYNC NOW",
            tint = R1.AccentWarm,
            modifier = Modifier.weight(1f),
            onClick = onSyncNow,
        )
        ActionChip(
            label = "PUSH",
            tint = R1.AccentGreen,
            modifier = Modifier.weight(1f),
            onClick = onPushNow,
        )
    }
}

@Composable
private fun ActionChip(
    label: String,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .height(48.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.labelMicro, color = tint)
    }
}

@Composable
private fun SwitchInlineRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(
                onClick = { onCheckedChange(!checked) },
                hapticOnClick = false,
            )
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = R1.bodyEmph, color = R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted)
        }
        R1Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

/** Two-stage armed/commit reset. First tap arms (label flips, 3s auto-disarm);
 *  second tap fires a push that overwrites the remote payload with this device's
 *  current state. Useful when the remote got into a weird state and the user
 *  wants this device to seed the truth across the fleet. */
@Composable
private fun ResetRebroadcastRow(onConfirm: () -> Unit) {
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) {
        if (armed) {
            kotlinx.coroutines.delay(3_000)
            armed = false
        }
    }
    Column(modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(R1.ShapeS)
                .background(if (armed) R1.StatusAmber.copy(alpha = 0.22f) else R1.SurfaceMuted)
                .border(
                    1.dp,
                    if (armed) R1.StatusAmber else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable(onClick = {
                    if (armed) {
                        armed = false
                        onConfirm()
                    } else {
                        armed = true
                    }
                }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (armed) "CONFIRM, OVERWRITE REMOTE" else "PUSH LOCAL, OVERWRITE REMOTE",
                style = R1.labelMicro,
                color = if (armed) R1.StatusAmber else R1.AccentWarm,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (armed) {
                "Tap again to push this device's snapshot. Other devices " +
                    "will adopt it on their next pull."
            } else {
                "Use after fixing settings on this device; tells the fleet " +
                    "to take its values from here on the next pull."
            },
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun CategoryRow(
    category: SyncCategory,
    included: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(
                onClick = { onChange(!included) },
                hapticOnClick = false,
            )
            .padding(horizontal = 22.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(category.displayLabel, style = R1.bodyEmph, color = R1.Ink)
            Text(
                text = category.description,
                style = R1.body,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        R1Switch(checked = included, onCheckedChange = onChange)
    }
}

/** Compact stats block: state pill, last pull / push, last error. Formatted
 *  as relative ages because absolute timestamps are noise on a glance card. */
@Composable
private fun StatsCard(stats: HaSettingsSync.Stats) {
    // 1Hz tick keeps the relative ages counting up while the screen is open
    // instead of freezing at the moment of compose.
    val now = androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val (pillLabel, pillTint) = when {
        stats.inProgress -> "SYNCING" to R1.StatusAmber
        stats.lastErrorMessage != null && stats.lastErrorAtMillis >
            maxOf(stats.lastPullAtMillis, stats.lastPushAtMillis) ->
            "ERROR" to R1.StatusRed
        stats.lastPullAtMillis > 0 || stats.lastPushAtMillis > 0 ->
            "IDLE" to R1.AccentGreen
        else -> "WAITING" to R1.InkSoft
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 6.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatePill(label = pillLabel, tint = pillTint)
            Spacer(Modifier.weight(1f))
            Text(
                text = "${stats.pullCount} pulls · ${stats.pushCount} pushes",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        Spacer(Modifier.height(2.dp))
        StatRow(
            label = "LAST PULL",
            value = relativeAge(stats.lastPullAtMillis, now.value),
            tint = R1.Ink,
        )
        StatRow(
            label = "LAST PUSH",
            value = relativeAge(stats.lastPushAtMillis, now.value),
            tint = R1.Ink,
        )
        StatRow(
            label = "REMOTE TIMESTAMP",
            value = if (stats.lastRemoteTimestampMillis == 0L) {
                "none yet"
            } else {
                relativeAge(stats.lastRemoteTimestampMillis, now.value)
            },
            tint = R1.InkSoft,
        )
        if (stats.lastErrorMessage != null) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = "LAST ERROR · ${relativeAge(stats.lastErrorAtMillis, now.value)}",
                style = R1.labelMicro,
                color = R1.StatusRed,
            )
            Text(
                text = stats.lastErrorMessage,
                style = R1.body,
                color = R1.StatusRed,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun StatePill(label: String, tint: Color) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(tint.copy(alpha = 0.16f))
            .border(1.dp, tint.copy(alpha = 0.55f), R1.ShapeRound)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = tint)
    }
}

@Composable
private fun StatRow(label: String, value: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.weight(1f))
        Text(text = value, style = R1.labelMicro, color = tint)
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(
        text = text,
        style = R1.sectionHeader,
        color = R1.AccentWarm,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 4.dp),
    )
}

/** "12s ago" / "5m ago" / "2h ago" / "3d ago" / "never" for never-fired
 *  timestamps. Cap at 99d to avoid running into year-long formatting we
 *  never reach in practice. */
private fun relativeAge(thenMillis: Long, nowMillis: Long): String {
    if (thenMillis <= 0L) return "never"
    val deltaMs = (nowMillis - thenMillis).coerceAtLeast(0L)
    val s = deltaMs / 1000L
    return when {
        s < 60 -> "${s}s ago"
        s < 3_600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3_600}h ago"
        else -> "${(s / 86_400).coerceAtMost(99)}d ago"
    }
}

@Composable
private fun IntervalStepperRow(seconds: Int, onChange: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepChip(
            label = "−30s",
            enabled = seconds > 30,
            onClick = { onChange((seconds - 30).coerceAtLeast(30)) },
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${seconds}s",
            style = R1.bodyEmph,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        StepChip(
            label = "+30s",
            enabled = seconds < 3600,
            onClick = { onChange((seconds + 30).coerceAtMost(3600)) },
        )
    }
}

@Composable
private fun StepChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .height(48.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = { if (enabled) onClick() })
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (enabled) R1.InkSoft else R1.InkMuted,
        )
    }
}
