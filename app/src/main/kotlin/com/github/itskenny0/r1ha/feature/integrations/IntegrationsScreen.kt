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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
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
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "..." else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
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
                        val sections = ui.sections
                        if (sections.isEmpty()) {
                            EmptyState(
                                message = when (ui.filter) {
                                    IntegrationsViewModel.Filter.LOADED -> "No loaded integrations."
                                    IntegrationsViewModel.Filter.FAILED -> "No failed integrations. Nice."
                                    IntegrationsViewModel.Filter.ALL -> "No matching integrations."
                                },
                            )
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = 12.dp, vertical = 8.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                for ((domain, entries) in sections) {
                                    item(key = "domain/$domain") {
                                        DomainHeader(domain = domain, count = entries.size)
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "SHOW",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        FilterChip(
            label = "ALL",
            count = totalCount,
            selected = current == IntegrationsViewModel.Filter.ALL,
            tone = R1.AccentNeutral,
            onClick = { onSelect(IntegrationsViewModel.Filter.ALL) },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            label = "LOADED",
            count = loadedCount,
            selected = current == IntegrationsViewModel.Filter.LOADED,
            tone = R1.AccentGreen,
            onClick = { onSelect(IntegrationsViewModel.Filter.LOADED) },
        )
        Spacer(Modifier.width(6.dp))
        FilterChip(
            label = "FAILED",
            count = failedCount,
            selected = current == IntegrationsViewModel.Filter.FAILED,
            tone = R1.StatusRed,
            onClick = { onSelect(IntegrationsViewModel.Filter.FAILED) },
        )
    }
}

@Composable
private fun FilterChip(
    label: String,
    count: Int,
    selected: Boolean,
    tone: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (selected) tone.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) tone.copy(alpha = 0.6f) else R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(
            text = "$label $count",
            style = R1.labelMicro,
            color = if (selected) tone else R1.InkSoft,
        )
    }
}

@Composable
private fun DomainHeader(domain: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domain.uppercase(),
            style = R1.labelMicro,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        Text(text = "$count", style = R1.labelMicro, color = R1.InkMuted)
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.title,
                style = R1.bodyEmph,
                color = if (disabled) R1.InkMuted else R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            StateChip(state = entry.state, tone = stateTone)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "via ${entry.source}",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            if (disabled) {
                MicroChip(text = "DISABLED", tone = R1.StatusAmber)
                Spacer(Modifier.width(6.dp))
            }
            if (entry.prefDisablePolling) {
                MicroChip(text = "NO POLL", tone = R1.AccentCool)
                Spacer(Modifier.width(6.dp))
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
private fun StateChip(state: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(tone.copy(alpha = 0.18f))
            .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = state.uppercase(), style = R1.labelMicro, color = tone)
    }
}

@Composable
private fun MicroChip(text: String, tone: Color) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(tone.copy(alpha = 0.18f))
            .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = R1.labelMicro, color = tone)
    }
}

@Composable
private fun ReloadChip(supportsUnload: Boolean, reloading: Boolean, onClick: () -> Unit) {
    val enabled = supportsUnload && !reloading
    val tone = if (supportsUnload) R1.AccentWarm else R1.InkMuted
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (enabled) tone.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(1.dp, if (enabled) tone.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeS)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 4.dp),
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
        modifier = Modifier.fillMaxSize().padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = message, style = R1.body, color = R1.InkMuted)
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD INTEGRATIONS", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.height(6.dp))
        Text(text = message, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "config_entries only flows over the live WebSocket. Retry once it reconnects.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}
