package com.github.itskenny0.r1ha.feature.integrations

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Integrations browser: lists HA's configured integration entries
 * (one row per "tile" in the HA frontend's Devices & Services page),
 * grouped by integration domain, with a per-row RELOAD chip for
 * entries that support unload.
 *
 * Setup, removal, and options live in HA's web UI: those flows ship
 * dynamic schemas, OAuth handoffs, and integration-provided dialogs
 * the native client doesn't reimplement. Reload covers the most
 * common operational case ("this integration is wedged, kick it") and
 * is the single mutating action on this surface.
 */
@Composable
fun IntegrationsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: IntegrationsViewModel = viewModel(
        factory = IntegrationsViewModel.factory(haRepository),
    )
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "INTEGRATIONS",
            onBack = onBack,
            action = {
                R1Chip(
                    text = if (ui.loading) "..." else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh integrations",
                )
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                FilterBar(
                    current = ui.filter,
                    onSelect = { vm.setFilter(it) },
                    totalCount = ui.all.size,
                    loadedCount = ui.loadedCount,
                    failedCount = ui.failedCount,
                )
                if (ui.all.isNotEmpty()) {
                    R1TextField(
                        value = ui.query,
                        onValueChange = { vm.setQuery(it) },
                        placeholder = "Filter by domain or title",
                        monospace = false,
                        modifier = Modifier.padding(
                            horizontal = R1.space.m,
                            vertical = R1.space.xs,
                        ),
                    )
                }
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
                    ui.error != null && ui.all.isEmpty() -> ErrorState(message = ui.error.orEmpty())
                    ui.all.isEmpty() -> EmptyState(
                        message = "No integrations configured in HA yet.",
                    )
                    else -> PullToRefreshBox(
                        isRefreshing = ui.loading,
                        onRefresh = { vm.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        // Group + sort only when the entry set or active filter
                        // changes, not on every recomposition (e.g. each per-row
                        // reload spinner flip, which mutates reloadingIds and
                        // re-emits UiState).
                        val sections = remember(ui.all, ui.filter, ui.query) { ui.sections }
                        if (sections.isEmpty()) {
                            EmptyState(
                                message = when {
                                    ui.query.isNotBlank() -> "No integrations match \"${ui.query.trim()}\"."
                                    ui.filter == IntegrationsViewModel.Filter.LOADED -> "No loaded integrations."
                                    ui.filter == IntegrationsViewModel.Filter.FAILED -> "No failed integrations. Nice."
                                    else -> "No matching integrations."
                                },
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = R1.space.m, vertical = R1.space.s,
                                ),
                                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                            ) {
                                for ((domain, entries) in sections) {
                                    item(key = "domain/$domain") {
                                        DomainHeader(
                                            domain = domain,
                                            count = entries.size,
                                            counts = ui.countsByDomain[domain.lowercase(java.util.Locale.US)],
                                        )
                                    }
                                    for (entry in entries) {
                                        item(key = "entry/${entry.entryId}") {
                                            EntryRow(
                                                entry = entry,
                                                reloading = entry.entryId in ui.reloadingIds,
                                                onReload = { vm.reload(entry) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FilterBar(
    current: IntegrationsViewModel.Filter,
    onSelect: (IntegrationsViewModel.Filter) -> Unit,
    totalCount: Int,
    loadedCount: Int,
    failedCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SHOW",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = R1.space.s),
        )
        R1Chip(
            text = "ALL $totalCount",
            variant = R1ChipVariant.Filter,
            selected = current == IntegrationsViewModel.Filter.ALL,
            tone = R1.AccentNeutral,
            onClick = { onSelect(IntegrationsViewModel.Filter.ALL) },
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(
            text = "LOADED $loadedCount",
            variant = R1ChipVariant.Filter,
            selected = current == IntegrationsViewModel.Filter.LOADED,
            tone = R1.AccentGreen,
            onClick = { onSelect(IntegrationsViewModel.Filter.LOADED) },
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(
            text = "FAILED $failedCount",
            variant = R1ChipVariant.Filter,
            selected = current == IntegrationsViewModel.Filter.FAILED,
            tone = R1.StatusRed,
            onClick = { onSelect(IntegrationsViewModel.Filter.FAILED) },
        )
    }
}

@Composable
private fun DomainHeader(
    domain: String,
    count: Int,
    counts: IntegrationsViewModel.DomainCounts?,
) {
    // Canonical group-header treatment (matches R1Section's title line): uppercase
    // section-header type in the accent colour, a hairline rule filling the gap, and a
    // count rendered as an R1Chip Pill at the right edge. When the registries have
    // resolved counts for this domain, a compact "Nd / Ne" tally precedes the entry
    // count so the user can gauge how much the integration brings in.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = R1.space.s, bottom = R1.space.xs, start = R1.space.xs, end = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domain.uppercase(),
            style = R1.sectionHeader,
            color = R1.AccentWarm,
        )
        Spacer(Modifier.width(R1.space.m))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        if (counts != null && (counts.devices > 0 || counts.entities > 0)) {
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = "${counts.devices}d / ${counts.entities}e",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        R1Chip(text = "$count", variant = R1ChipVariant.Pill, tone = R1.InkSoft)
    }
}

@Composable
private fun EntryRow(
    entry: ConfigEntry,
    reloading: Boolean,
    onReload: () -> Unit,
) {
    val bucket = IntegrationsViewModel.stateRank(entry.state)
    val stateTone = when (bucket) {
        IntegrationsViewModel.StateBucket.LOADED -> R1.AccentGreen
        IntegrationsViewModel.StateBucket.FAILED -> R1.StatusRed
        IntegrationsViewModel.StateBucket.PENDING -> R1.StatusAmber
        IntegrationsViewModel.StateBucket.OTHER -> R1.InkMuted
    }
    val disabled = entry.disabledBy != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (disabled) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = R1.bodyEmph,
                color = if (disabled) R1.InkMuted else R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = IntegrationsViewModel.stateLabel(entry.state),
                variant = R1ChipVariant.Pill,
                tone = stateTone,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "via ${entry.source}",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            if (disabled) {
                R1Chip(text = "DISABLED", variant = R1ChipVariant.Pill, tone = R1.StatusAmber)
                Spacer(Modifier.width(R1.space.s))
            }
            if (entry.prefDisablePolling) {
                R1Chip(text = "NO POLL", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
                Spacer(Modifier.width(R1.space.s))
            }
            ReloadChip(
                supportsUnload = entry.supportsUnload,
                reloading = reloading,
                onClick = onReload,
            )
        }
        if (!entry.reason.isNullOrBlank()) {
            Text(
                text = entry.reason,
                style = R1.labelMicro,
                color = R1.StatusAmber,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun ReloadChip(supportsUnload: Boolean, reloading: Boolean, onClick: () -> Unit) {
    // RELOAD is the one mutating action on the surface, so it stays bespoke: it folds a
    // spinner into the chip footprint while in flight and goes inert (no tap target, muted
    // tone) when the integration can't be unloaded.
    val enabled = supportsUnload && !reloading
    val tone = if (supportsUnload) R1.AccentWarm else R1.InkMuted
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (enabled) tone.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(1.dp, if (enabled) tone.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeS)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it }
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        contentAlignment = Alignment.Center,
    ) {
        if (reloading) {
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                strokeWidth = 1.5.dp,
                color = tone,
            )
        } else {
            Text(
                text = if (supportsUnload) "RELOAD" else "NO RELOAD",
                style = R1.labelMicro,
                color = tone,
            )
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = R1.body, color = R1.InkMuted)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD INTEGRATIONS", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.height(R1.space.s))
        Text(text = message, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.m))
        Text(
            text = "config_entries only flows over the live WebSocket. Retry once it reconnects.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}
