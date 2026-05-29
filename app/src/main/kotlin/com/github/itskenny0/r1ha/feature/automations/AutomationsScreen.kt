package com.github.itskenny0.r1ha.feature.automations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Automations browser, mirrors HA's frontend Automations panel.
 * Each row carries:
 *  - state chip (ENABLED / DISABLED) coloured per state
 *  - friendly name + a smaller `entity_id` underneath
 *  - mode badge (SINGLE / PARALLEL / QUEUED / RESTART)
 *  - relative `last_triggered` timestamp
 *  - RUN button on the right (fires `automation.trigger` with
 *    `skip_condition: true`, so the conditions block doesn't block a
 *    manual test).
 *
 * Long-press a row to toggle its enabled state: `automation.turn_on`
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
    // Active-page favourites set, used to swap the ☆ glyph for ★ on
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
    // entries is a getter that re-filters the full automation set on every read;
    // it's read for the empty-state branch and the items() call, and recompose
    // fires per search keystroke. Memoise against its inputs so the filter runs
    // once per state change rather than twice per frame.
    val entries = androidx.compose.runtime.remember(ui.all, ui.query) { ui.entries }
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
                // RELOAD chip fires automation.reload. While a previous reload
                // is in flight the chip reads "…" and the tap is ignored, so a
                // rapid double-tap can't queue two reloads back-to-back.
                R1Chip(
                    text = if (ui.reloading) "…" else "RELOAD",
                    variant = R1ChipVariant.Action,
                    onClick = { if (!ui.reloading) vm.reload() },
                    contentDescription = "Reload automations",
                )
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
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Automations load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No automations defined. Settings, Automations in HA's web UI.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
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
                        contentPadding = PaddingValues(
                            horizontal = R1.space.m, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        items(items = entries, key = { it.id.value }) { entry ->
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
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = R1.space.s),
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
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier
                    .size(R1.MinTarget)
                    .r1Pressable({ onQueryChange("") }, contentDescription = "Clear search"),
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
    // Bespoke control row: the body toggles enabled state, plus there are
    // independent star + RUN tap targets on the trailing edge, so it can't
    // collapse to a single-onClick R1Row. It does adopt the canonical boxed
    // surface (muted fill + hairline + 48dp min height) and spacing tokens so
    // it sits flush with R1Row instances elsewhere.
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
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // ON / OFF state label, green when active, muted when off.
        Text(
            text = if (entry.enabled) "ON" else "OFF",
            style = R1.labelMicro,
            color = if (entry.enabled) R1.AccentGreen else R1.InkMuted,
            modifier = Modifier.width(R1.space.xl),
        )
        Spacer(Modifier.width(R1.space.s))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(R1.space.s))
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
                    Spacer(Modifier.width(R1.space.s))
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
                        // The badge sits inside the row, so its own gesture
                        // detector swallows taps before they reach the row's
                        // enabled-toggle. Forward a tap to onToggleEnabled so
                        // tapping the badge behaves identically to tapping the
                        // rest of the row; long-press keeps the mode explainer.
                        modifier = Modifier.r1RowPressable(
                            onTap = onToggleEnabled,
                            onLongPress = { modeExplain.value = true },
                        ),
                    )
                }
                if (entry.currentRunning > 0) {
                    Spacer(Modifier.width(R1.space.s))
                    // "RUNNING ×N" badge, only renders when at least
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
        Spacer(Modifier.width(R1.space.s))
        // ☆ pin-to-favourites button, tap to add this automation to
        // the active page's card stack. Glyph swaps to ★ once pinned
        // so the user doesn't fruitlessly re-tap.
        Box(
            modifier = Modifier
                .size(R1.MinTarget)
                .r1Pressable(
                    onClick = onFavorite,
                    contentDescription = if (isFavorite) "Pinned to favourites" else "Pin to favourites",
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = R1.body,
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(R1.space.xs))
        // RUN tap target, fires automation.trigger. Separate from the
        // row's enabled-toggle press handler so a tap here is
        // unambiguously "run now" rather than "toggle on/off".
        R1Chip(
            text = "RUN",
            variant = R1ChipVariant.Action,
            selected = true,
            tone = R1.AccentGreen,
            onClick = onRun,
            contentDescription = "Run ${entry.name}",
        )
    }
}

private fun modeExplainer(mode: AutomationsViewModel.Mode): String = when (mode) {
    AutomationsViewModel.Mode.SINGLE -> "one at a time"
    AutomationsViewModel.Mode.PARALLEL -> "many at once"
    AutomationsViewModel.Mode.QUEUED -> "queue extras"
    AutomationsViewModel.Mode.RESTART -> "restart on retrigger"
    AutomationsViewModel.Mode.UNKNOWN -> ""
}
