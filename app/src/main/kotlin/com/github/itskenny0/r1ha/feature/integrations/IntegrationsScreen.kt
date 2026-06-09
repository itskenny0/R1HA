package com.github.itskenny0.r1ha.feature.integrations

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
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
                    pendingCount = ui.pendingCount,
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
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Loading integrations" },
                    ) {
                        SkeletonList()
                    }
                    ui.error != null && ui.all.isEmpty() -> R1ErrorState(
                        title = "COULDN'T LOAD INTEGRATIONS",
                        message = listOfNotNull(
                            ui.error?.takeIf { it.isNotBlank() },
                            "config_entries only flows over the live WebSocket. " +
                                "Retry once it reconnects.",
                        ).joinToString("\n\n"),
                        onRetry = { vm.refresh() },
                    )
                    ui.all.isEmpty() -> R1EmptyState(
                        title = "NO INTEGRATIONS",
                        body = "No integrations configured in HA yet. Add them under " +
                            "Settings, Devices & Services in HA's web UI.",
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
                                    ui.filter == IntegrationsViewModel.Filter.PENDING -> "Nothing setting up or pending."
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
    pendingCount: Int,
    failedCount: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = "SHOW",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = R1.space.s, top = R1.space.xs),
        )
        // Four filter chips overflow the R1's narrow width on one line; let
        // them wrap rather than clip so PENDING stays reachable.
        FlowRow(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            R1Chip(
                text = "ALL $totalCount",
                variant = R1ChipVariant.Filter,
                selected = current == IntegrationsViewModel.Filter.ALL,
                tone = R1.AccentNeutral,
                onClick = { onSelect(IntegrationsViewModel.Filter.ALL) },
            )
            R1Chip(
                text = "LOADED $loadedCount",
                variant = R1ChipVariant.Filter,
                selected = current == IntegrationsViewModel.Filter.LOADED,
                tone = R1.AccentGreen,
                onClick = { onSelect(IntegrationsViewModel.Filter.LOADED) },
            )
            // PENDING surfaces setup_in_progress / not_loaded entries that HA's
            // own list buries: a stuck "SETTING UP" integration is findable here.
            R1Chip(
                text = "PENDING $pendingCount",
                variant = R1ChipVariant.Filter,
                selected = current == IntegrationsViewModel.Filter.PENDING,
                tone = R1.StatusAmber,
                onClick = { onSelect(IntegrationsViewModel.Filter.PENDING) },
            )
            R1Chip(
                text = "FAILED $failedCount",
                variant = R1ChipVariant.Filter,
                selected = current == IntegrationsViewModel.Filter.FAILED,
                tone = R1.StatusRed,
                onClick = { onSelect(IntegrationsViewModel.Filter.FAILED) },
            )
        }
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
    val countSuffix = remember(counts) { IntegrationsViewModel.domainCountsSpoken(counts) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = R1.space.s, bottom = R1.space.xs, start = R1.space.xs, end = R1.space.xs)
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = "$domain, $count entr${if (count == 1) "y" else "ies"}$countSuffix"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = R1Icons.forDomain(domain),
            contentDescription = null,
            tint = R1.AccentWarm,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = domain.uppercase(),
            style = responsiveType(R1.sectionHeader),
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
    val disabled = entry.disabledBy != null
    // When an entry is disabled HA stops loading it, so its raw state is
    // typically "not_loaded". Surfacing that as an amber NOT LOADED chip
    // reads as a fault; HA instead frames a disabled entry by its disabled
    // cause and suppresses the not-loaded text. Mirror that: the state chip
    // on a disabled entry shows DISABLED in the neutral disabled tone, and
    // the per-state coloring only applies to enabled entries.
    val bucket = remember(entry.state) { IntegrationsViewModel.stateRank(entry.state) }
    val stateTone = remember(disabled, bucket) {
        when {
            disabled -> R1.InkMuted
            bucket == IntegrationsViewModel.StateBucket.LOADED -> R1.AccentGreen
            bucket == IntegrationsViewModel.StateBucket.FAILED -> R1.StatusRed
            bucket == IntegrationsViewModel.StateBucket.PENDING -> R1.StatusAmber
            else -> R1.InkMuted
        }
    }
    val stateChipText = remember(entry.state, disabled) {
        if (disabled) "DISABLED" else IntegrationsViewModel.stateLabel(entry.state)
    }
    val disabledLabel = remember(entry.disabledBy) {
        IntegrationsViewModel.disabledLabel(entry.disabledBy)
    }
    val rowDescription = remember(entry, disabled) {
        buildString {
            append(entry.title)
            append(", ")
            append(
                if (disabled) "disabled${entry.disabledBy?.let { " by $it" } ?: ""}"
                else IntegrationsViewModel.stateLabel(entry.state),
            )
            append(", via ")
            append(entry.source)
            if (!entry.reason.isNullOrBlank()) {
                append(". ")
                append(entry.reason)
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (disabled) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m)
            .semantics(mergeDescendants = true) { contentDescription = rowDescription },
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = responsiveType(R1.bodyEmph),
                color = if (disabled) R1.InkMuted else R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = stateChipText,
                variant = R1ChipVariant.Pill,
                tone = stateTone,
            )
        }
        Text(
            text = "via ${entry.source}",
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
        // Pref / status chips plus the reload action wrap rather than clip on
        // the R1's narrow width, so every applicable marker stays visible.
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            if (disabled && disabledLabel != null) {
                R1Chip(text = disabledLabel, variant = R1ChipVariant.Pill, tone = R1.StatusAmber)
            }
            if (entry.prefDisablePolling) {
                R1Chip(text = "NO POLL", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
            }
            if (entry.prefDisableNewEntities) {
                R1Chip(text = "MANUAL ENTITIES", variant = R1ChipVariant.Pill, tone = R1.AccentNeutral)
            }
            ReloadChip(
                // A disabled entry isn't loaded, so reload can't act on it
                // (HA would reject the call). Present the chip inert in that
                // case the same as an entry that doesn't support unload.
                supportsUnload = entry.supportsUnload && !disabled,
                reloading = reloading,
                title = entry.title,
                onClick = onReload,
            )
        }
        if (!entry.reason.isNullOrBlank()) {
            Text(
                text = entry.reason,
                style = R1.labelMicro,
                color = R1.StatusAmber,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun ReloadChip(
    supportsUnload: Boolean,
    reloading: Boolean,
    title: String,
    onClick: () -> Unit,
) {
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
            .let {
                if (enabled) {
                    it
                        .heightIn(min = R1.MinTarget)
                        .r1Pressable(onClick = onClick, contentDescription = "Reload $title")
                } else {
                    it
                }
            }
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
        Text(text = message, style = responsiveType(R1.body), color = R1.InkMuted)
    }
}
