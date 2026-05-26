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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.sync.HaSettingsSync
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Multi-device settings sync surface. Renders the opt-in toggle, the pull
 * interval picker, manual pull/push triggers, and a live diagnostic strip
 * showing the last successful sync / current sync state / last error.
 *
 * Lives as its own top-level Settings subpage so the feature is discoverable
 * without burying it under INTEGRATIONS, and so the stats block has room to
 * breathe.
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
        ?: remember { androidx.compose.runtime.mutableStateOf(HaSettingsSync.Stats()) })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        R1TopBar(title = "SYNC", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Mirror your preferences to/from Home Assistant so multiple " +
                        "R1 / phone installs signed into the same HA user stay in sync. " +
                        "Storage is HA's per-user JSON bucket — no add-on or integration " +
                        "to install. Server URL, iBeacon, webhook, and MQTT are device-" +
                        "local and never synced.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = "Enable", style = R1.bodyEmph, color = R1.Ink)
                            Text(
                                text = if (s.integrations.haSyncEnabled) {
                                    "Syncing every ${s.integrations.haSyncIntervalSec}s + on edit"
                                } else {
                                    "Off — settings stay local to this device"
                                },
                                style = R1.body,
                                color = R1.InkMuted,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        com.github.itskenny0.r1ha.ui.components.R1Switch(
                            checked = s.integrations.haSyncEnabled,
                            onCheckedChange = { v ->
                                vm.updateIntegrations { it.copy(haSyncEnabled = v) }
                            },
                        )
                    }
                }
            }
            if (s.integrations.haSyncEnabled) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                    ) {
                        Text("Pull interval", style = R1.bodyEmph, color = R1.Ink)
                        Text(
                            text = "How often to check HA for changes from other devices. " +
                                "Pushes from this device happen automatically on every " +
                                "edit (debounced ~5 s).",
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 1.dp, bottom = 6.dp),
                        )
                        IntervalStepperRow(
                            seconds = s.integrations.haSyncIntervalSec,
                            onChange = { v ->
                                vm.updateIntegrations { it.copy(haSyncIntervalSec = v) }
                            },
                        )
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable({ syncManager?.pullNow() })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(text = "PULL NOW", style = R1.labelMicro, color = R1.AccentWarm)
                        }
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable({ syncManager?.pushNow() })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(text = "PUSH NOW", style = R1.labelMicro, color = R1.AccentWarm)
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
                item { StatsCard(stats) }
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    Text(
                        text = "WHAT TO SYNC",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                    Text(
                        text = "All categories sync by default. Switch a category off " +
                            "to keep its values local to this device — the remote " +
                            "value won't overwrite local on pull, and local edits in " +
                            "that category won't be pushed to HA.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
                    )
                }
                items(com.github.itskenny0.r1ha.core.sync.SyncCategory.entries) { category ->
                    val included = !s.integrations.haSyncExcludedCategories.contains(category.name)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = category.displayLabel,
                                style = R1.bodyEmph,
                                color = R1.Ink,
                            )
                            Text(
                                text = category.description,
                                style = R1.body,
                                color = R1.InkMuted,
                                modifier = Modifier.padding(top = 1.dp),
                            )
                        }
                        com.github.itskenny0.r1ha.ui.components.R1Switch(
                            checked = included,
                            onCheckedChange = { newIncluded ->
                                vm.updateIntegrations { prev ->
                                    val next = prev.haSyncExcludedCategories.toMutableSet()
                                    if (newIncluded) next.remove(category.name)
                                    else next.add(category.name)
                                    prev.copy(haSyncExcludedCategories = next)
                                }
                            },
                        )
                    }
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

/** Compact stats block: state + last pull/push + last error. Formatted as
 *  relative ages ("12s ago") because absolute timestamps are noise on a
 *  glance-friendly card. */
@Composable
private fun StatsCard(stats: HaSettingsSync.Stats) {
    // 1 Hz tick so the relative ages keep counting up while the user is
    // looking at the screen rather than freezing at the moment of compose.
    val now = androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("STATS", style = R1.labelMicro, color = R1.InkSoft)
        StatRow(
            label = "STATE",
            value = if (stats.inProgress) "Syncing…" else "Idle",
            tint = if (stats.inProgress) R1.AccentWarm else R1.InkSoft,
        )
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
            value = if (stats.lastRemoteTimestampMillis == 0L) "—"
            else relativeAge(stats.lastRemoteTimestampMillis, now.value),
            tint = R1.InkSoft,
        )
        StatRow(
            label = "PULLS / PUSHES",
            value = "${stats.pullCount} / ${stats.pushCount}",
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
private fun StatRow(label: String, value: String, tint: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.weight(1f))
        Text(text = value, style = R1.labelMicro, color = tint)
    }
}

/** "12s ago" / "5m ago" / "2h ago" / "3d ago" / "—" for never. Cap at 99d to
 *  avoid running into year-old formatting we never reach in practice. */
private fun relativeAge(thenMillis: Long, nowMillis: Long): String {
    if (thenMillis <= 0L) return "—"
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
    // Coarse +/- 30 s steppers — most users either want "snappy" (~30 s)
    // or "lazy" (~5-10 min) and don't need fine granularity in between.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable({ onChange((seconds - 30).coerceAtLeast(30)) })
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(text = "−30s", style = R1.labelMicro, color = R1.InkSoft)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${seconds}s",
            style = R1.bodyEmph,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable({ onChange((seconds + 30).coerceAtMost(3600)) })
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(text = "+30s", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}
