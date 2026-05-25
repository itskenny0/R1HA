package com.github.itskenny0.r1ha.feature.automations

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Automations browser — mirrors HA's frontend Automations panel.
 * Each row carries:
 *  - state chip (ENABLED / DISABLED) coloured per state
 *  - friendly name + a smaller `entity_id` underneath
 *  - mode badge (SINGLE / PARALLEL / QUEUED / RESTART)
 *  - relative `last_triggered` timestamp
 *  - RUN button on the right (fires `automation.trigger` with
 *    `skip_condition: true`, so the conditions block doesn't block a
 *    manual test).
 *
 * Long-press a row to toggle its enabled state — `automation.turn_on`
 * / `automation.turn_off`. Re-fetch is automatic after every dispatch
 * so the row stays in sync.
 *
 * Header chip RELOAD fires `automation.reload` (re-reads
 * `automations.yaml` + the UI editor's storage). Useful after editing
 * the YAML on a tablet and wanting the R1 to pick up the change
 * without restarting HA.
 */
@Composable
fun AutomationsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Optional drill-in to the History surface for this entity.
     *  Wired from AppNavGraph; defaults to a no-op for preview /
     *  test sites that don't care about nav. */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: AutomationsViewModel = viewModel(
        factory = AutomationsViewModel.factory(haRepository, settings),
    )
    // Active-page favourites set — used to swap the ☆ glyph for ★ on
    // rows the user has already pinned (same idiom as the Search
    // screen's filled-when-favourited star).
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val activeFavourites = androidx.compose.runtime.remember(
        appSettings.activePageId, appSettings.pages,
    ) {
        appSettings.pages.firstOrNull { it.id == appSettings.activePageId }
            ?.favorites?.toSet() ?: emptySet()
    }
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "AUTOMATIONS",
            onBack = onBack,
            action = {
                // RELOAD chip — fires automation.reload. Disabled while
                // a previous reload is in flight to stop a rapid
                // double-tap from queueing two reloads back-to-back.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { if (!ui.reloading) vm.reload() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.reloading) "…" else "RELOAD",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
        when {
            ui.loading && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Automations load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No automations defined. Settings → Automations in HA's web UI.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            ui.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matches for '${ui.query}'.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 12.dp, vertical = 8.dp,
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(items = ui.entries, key = { it.id.value }) { entry ->
                            AutomationRow(
                                entry = entry,
                                isFavorite = entry.id.value in activeFavourites,
                                onRun = { vm.trigger(entry) },
                                onToggleEnabled = { vm.setEnabled(entry, !entry.enabled) },
                                onLongPress = { onOpenHistory(entry.id.value) },
                                onFavorite = { vm.addToFavorites(entry) },
                            )
                        }
                    }
                }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "kitchen, away, sunset, ...",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(48.dp).r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun AutomationRow(
    entry: AutomationsViewModel.Entry,
    isFavorite: Boolean,
    onRun: () -> Unit,
    onToggleEnabled: () -> Unit,
    onLongPress: () -> Unit,
    onFavorite: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            // Tap toggles enabled/disabled (state-change verb on the
            // row body), long-press drills into History so the user
            // can see when this automation last fired + how
            // frequently. Separate RUN affordance on the right edge
            // dispatches a manual trigger.
            .r1RowPressable(onTap = onToggleEnabled, onLongPress = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ENABLED / DISABLED chip — green when active, muted when off.
        Text(
            text = if (entry.enabled) "ON" else "OFF",
            style = R1.labelMicro,
            color = if (entry.enabled) R1.AccentGreen else R1.InkMuted,
            modifier = Modifier.width(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = R1.body,
                    color = R1.Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                RelativeTimeLabel(
                    at = entry.lastTriggered,
                    color = R1.InkMuted,
                    style = R1.labelMicro,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.id.value,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (entry.mode != AutomationsViewModel.Mode.UNKNOWN) {
                    Spacer(Modifier.width(6.dp))
                    // Long-press a mode badge surfaces a one-line explainer; HA's
                    // automation-mode jargon (queued/restart/parallel) isn't obvious
                    // even to fairly experienced users.
                    val modeExplain = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(modeExplain.value) {
                        if (modeExplain.value) {
                            kotlinx.coroutines.delay(2_500L)
                            modeExplain.value = false
                        }
                    }
                    Text(
                        text = if (modeExplain.value) modeExplainer(entry.mode) else entry.mode.label,
                        style = R1.labelMicro,
                        color = R1.AccentNeutral,
                        modifier = Modifier.r1RowPressable(
                            onTap = { /* no-op; tap is reserved for the row */ },
                            onLongPress = { modeExplain.value = true },
                        ),
                    )
                }
                if (entry.currentRunning > 0) {
                    Spacer(Modifier.width(6.dp))
                    // "RUNNING ×N" badge — only renders when at least
                    // one instance is live (relevant for parallel /
                    // queued modes that allow concurrent runs).
                    Text(
                        text = "×${entry.currentRunning}",
                        style = R1.labelMicro,
                        color = R1.AccentWarm,
                    )
                }
            }
        }
        Spacer(Modifier.width(6.dp))
        // ☆ pin-to-favourites button — tap to add this automation to
        // the active page's card stack. Glyph swaps to ★ once pinned
        // so the user doesn't fruitlessly re-tap.
        Box(
            modifier = Modifier
                .size(28.dp)
                .r1Pressable(onClick = onFavorite),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = R1.body,
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(2.dp))
        // RUN tap target — fires automation.trigger. Separate from the
        // row's enabled-toggle press handler so a tap here is
        // unambiguously "run now" rather than "toggle on/off".
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.AccentGreen.copy(alpha = 0.18f))
                .border(1.dp, R1.AccentGreen.copy(alpha = 0.4f), R1.ShapeS)
                .r1Pressable(onClick = onRun)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = "RUN", style = R1.labelMicro, color = R1.AccentGreen)
        }
    }
}

private fun modeExplainer(mode: AutomationsViewModel.Mode): String = when (mode) {
    AutomationsViewModel.Mode.SINGLE -> "one at a time"
    AutomationsViewModel.Mode.PARALLEL -> "many at once"
    AutomationsViewModel.Mode.QUEUED -> "queue extras"
    AutomationsViewModel.Mode.RESTART -> "restart on retrigger"
    AutomationsViewModel.Mode.UNKNOWN -> ""
}
