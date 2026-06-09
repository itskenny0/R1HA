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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid
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
    val gridState = rememberLazyGridState()
    WheelScrollForGrid(wheelInput = wheelInput, gridState = gridState, settings = settings)
    val dimens = rememberResponsiveDimens()
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
                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        liveRegion = LiveRegionMode.Polite
                        contentDescription = "Loading automations"
                    },
            ) {
                SkeletonList()
            }
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(R1.space.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Automations load failed: ${ui.error}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(R1.space.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No automations defined. Settings, Automations in HA's web UI.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            entries.isEmpty() -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(R1.space.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No matches for '${ui.query}'. Clear the search or try different terms.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    // dashboardColumns is 1 on mini / compact / phone (the stacked
                    // list, unchanged) and steps to 2 on tablet / expanded and 3 on
                    // extra-large, so a wide panel flows the automation rows into a
                    // multi-column grid instead of one stretched column. The grid is
                    // already centred + width-capped by the enclosing AdaptiveContent.
                    LazyVerticalGrid(
                        state = gridState,
                        columns = GridCells.Fixed(dimens.dashboardColumns),
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            horizontal = dimens.screenGutter, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        items(
                            items = entries,
                            key = { it.id.value },
                            span = { GridItemSpan(1) },
                        ) { entry ->
                            AutomationRow(
                                entry = entry,
                                isFavorite = entry.id.value in activeFavourites,
                                // In-flight flag is driven by the ViewModel's `firing`
                                // set (set around the real haRepository.call, cleared
                                // on success or failure) so the RUN indicator tracks
                                // the actual dispatch rather than a fixed timer that
                                // lied on slow / failed calls.
                                running = entry.id in ui.firing,
                                onRun = { vm.trigger(entry) },
                                onToggleEnabled = { vm.setEnabled(entry, !entry.enabled) },
                                onOpenHistory = { onOpenHistory(entry.id.value) },
                                // Tapping an unavailable row's body: explain the inert
                                // state (toast) and still drill into History, rather
                                // than silently opening History as if nothing happened.
                                onUnavailable = {
                                    vm.explainUnavailable(entry)
                                    onOpenHistory(entry.id.value)
                                },
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
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            modifier = Modifier
                .padding(end = R1.space.s)
                .semantics { heading() },
        )
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.semantics {
                    contentDescription = "Filter automations by name or entity id"
                },
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
                Text(text = "✕", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun AutomationRow(
    entry: AutomationsViewModel.Entry,
    isFavorite: Boolean,
    running: Boolean,
    onRun: () -> Unit,
    onToggleEnabled: () -> Unit,
    /** Drill into the History surface for this automation; wired to long-press. */
    onOpenHistory: () -> Unit,
    /** Tap on an unavailable row's body: explain the inert state (and still
     *  open History). Distinct from [onToggleEnabled] so the unavailable tap
     *  doesn't silently fall through to a doomed toggle / a bare History open. */
    onUnavailable: () -> Unit,
    onFavorite: () -> Unit,
) {
    // Resolve the relative last-triggered phrase once so the visible label and the
    // spoken row description stay in lockstep. Empty when the automation has never
    // fired or the timestamp is unknown.
    val relative = entry.lastTriggered?.let {
        com.github.itskenny0.r1ha.ui.components.rememberRelativeTime(it)
    }.orEmpty()
    // Merged spoken label so TalkBack reads the row as one unit instead of
    // announcing the ON/OFF glyph, name, entity id, mode badge, and timestamp as
    // disconnected fragments. While a manual RUN is in flight the label flips to
    // "Running <name>" and the row becomes a polite live region so the trigger is
    // announced without re-focusing.
    val rowLabel = if (running) {
        automationRunInFlightLabel(entry.name)
    } else {
        automationRowLabel(
            name = entry.name,
            enabled = entry.enabled,
            mode = entry.mode,
            runningInstances = entry.currentRunning,
            lastTriggeredSpoken = relative.ifBlank { null },
            available = entry.available,
        )
    }
    val rowSemantics = if (running) {
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = rowLabel
        }
    } else {
        Modifier
    }
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
            .then(rowSemantics)
            // Tap toggles enabled/disabled (state-change verb on the
            // row body), long-press drills into History so the user
            // can see when this automation last fired + how
            // frequently. Separate RUN affordance on the right edge
            // dispatches a manual trigger. When the automation is
            // unavailable there's no on/off to flip, so the tap routes
            // to onUnavailable, which explains the inert state (toast)
            // and still opens History, rather than silently opening
            // History as if the tap did nothing.
            .r1RowPressable(
                onTap = if (entry.available) onToggleEnabled else onUnavailable,
                onLongPress = onOpenHistory,
                contentDescription = rowLabel,
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Leading domain glyph (the in-house automation icon), tinted by state:
        // green when enabled, muted when disabled, amber when unavailable. This
        // is the primary at-a-glance cue; the ON / OFF / N-A token below is kept
        // as a compact secondary label, and the spoken state lives in the merged
        // row description (the icon itself carries no contentDescription so it
        // isn't announced twice).
        val stateTint = when {
            !entry.available -> R1.StatusAmber
            entry.enabled -> R1.AccentGreen
            else -> R1.InkMuted
        }
        androidx.compose.material3.Icon(
            imageVector = com.github.itskenny0.r1ha.ui.icons.R1Icons.forDomain("automation"),
            contentDescription = null,
            tint = stateTint,
            modifier = Modifier.size(R1.space.l),
        )
        Spacer(Modifier.width(R1.space.s))
        // State label: ON green when active, OFF muted when disabled, and an
        // amber "N/A" for an unavailable automation (matches the rest of the
        // app's StatusAmber 'unavailable' treatment). Secondary to the icon now;
        // sized to fit the longest token without pushing the name column around.
        Text(
            text = when {
                !entry.available -> "N/A"
                entry.enabled -> "ON"
                else -> "OFF"
            },
            style = responsiveType(R1.labelMicro),
            color = stateTint,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.width(R1.space.xl),
        )
        Spacer(Modifier.width(R1.space.s))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = responsiveType(R1.bodyEmph),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (entry.mode != AutomationsViewModel.Mode.UNKNOWN) {
                    Spacer(Modifier.width(R1.space.s))
                    // Long-press a mode badge surfaces a one-line explainer; HA's
                    // automation-mode jargon (queued/restart/parallel) isn't obvious
                    // even to fairly experienced users.
                    val modeExplain = androidx.compose.runtime.remember(entry.id) { androidx.compose.runtime.mutableStateOf(false) }
                    androidx.compose.runtime.LaunchedEffect(modeExplain.value) {
                        if (modeExplain.value) {
                            kotlinx.coroutines.delay(2_500L)
                            modeExplain.value = false
                        }
                    }
                    Text(
                        text = if (modeExplain.value) modeExplainer(entry.mode) else entry.mode.label,
                        style = responsiveType(R1.labelMicro),
                        color = R1.AccentNeutral,
                        // The badge sits inside the row, so its own gesture
                        // detector swallows taps before they reach the row's
                        // enabled-toggle. Forward a tap so tapping the badge
                        // behaves identically to tapping the rest of the row:
                        // toggle when available, explain the inert state when
                        // unavailable (the badge previously toggled enable even
                        // on unavailable rows). Long-press keeps the mode
                        // explainer. clearAndSetSemantics keeps the badge from
                        // registering as a separate bare-text focus node: the run
                        // mode is already spoken as part of the merged row
                        // description, so the reader hears it once in context
                        // rather than twice.
                        modifier = Modifier
                            .clearAndSetSemantics {}
                            .r1RowPressable(
                                onTap = if (entry.available) onToggleEnabled else onUnavailable,
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
                        style = responsiveType(R1.labelMicro),
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
                    contentDescription = automationFavoriteLabel(entry.name, isFavorite),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFavorite) "★" else "☆",
                style = responsiveType(R1.body),
                color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
            )
        }
        Spacer(Modifier.width(R1.space.xs))
        // RUN tap target, fires automation.trigger. Separate from the
        // row's enabled-toggle press handler so a tap here is
        // unambiguously "run now" rather than "toggle on/off".
        //
        // While a manual trigger is in flight the chip reads "RUN…" so the
        // tap has a visible acknowledgement (the polite live region covers
        // screen-reader users; this covers everyone else). An unavailable
        // automation can't be fired, so the chip renders as a dimmed,
        // non-interactive Pill instead of a live Action.
        if (entry.available) {
            R1Chip(
                text = if (running) "RUN…" else "RUN",
                variant = R1ChipVariant.Action,
                selected = true,
                tone = R1.AccentGreen,
                onClick = onRun,
                contentDescription = automationRunActionLabel(entry.name),
            )
        } else {
            R1Chip(
                text = "RUN",
                variant = R1ChipVariant.Pill,
                tone = R1.InkMuted,
            )
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
