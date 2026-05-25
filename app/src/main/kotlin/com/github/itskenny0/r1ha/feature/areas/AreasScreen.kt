package com.github.itskenny0.r1ha.feature.areas

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Areas browser — lists HA's area registry, with entity count per
 * area and a tappable expansion showing the full entity list.
 *
 * Powered by a server-side Jinja template through HA's
 * `/api/template` endpoint rather than the WebSocket
 * `config/area_registry/list` command — keeps the WS protocol
 * surface small and reuses the existing template REST plumbing.
 */
@Composable
fun AreasScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: AreasViewModel = viewModel(factory = AreasViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedAreaName by remember { mutableStateOf<String?>(null) }
    fun openInHa(entityId: String) {
        scope.launch {
            val server = runCatching { settings.settings.first().server?.url }.getOrNull()
            if (server.isNullOrBlank()) {
                Toaster.error("No HA server configured")
                return@launch
            }
            val url = "${server.trimEnd('/')}/history?entity_id=$entityId"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { t ->
                R1Log.w("Areas", "open-in-HA failed: ${t.message}")
                Toaster.error("No browser to open $url")
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "AREAS",
            onBack = onBack,
            action = {
                // Sort chip: toggles between alphabetical and entity-count. A long
                // tap-cycle reveal would be over-engineered; two states fit fine.
                val nextSort = if (ui.sort == AreasViewModel.Sort.ALPHA)
                    AreasViewModel.Sort.COUNT else AreasViewModel.Sort.ALPHA
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.setSort(nextSort) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.sort == AreasViewModel.Sort.ALPHA) "A→Z" else "BY COUNT",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        val sortedAreas by vm.sortedAreas.collectAsState()
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.areas.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = ui.error ?: "Error", style = R1.body, color = R1.StatusRed)
            }
            ui.areas.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No areas defined in HA. Settings → Areas in HA's web UI.",
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
                    items(items = sortedAreas, key = { it.name }) { area ->
                        AreaRow(
                            area = area,
                            expanded = expandedAreaName == area.name,
                            onToggle = {
                                expandedAreaName = if (expandedAreaName == area.name) null else area.name
                            },
                            onTapEntity = { eid -> openInHa(eid) },
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun AreaRow(
    area: AreasViewModel.Area,
    expanded: Boolean,
    onToggle: () -> Unit,
    onTapEntity: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = area.name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${area.entityIds.size}",
                style = R1.labelMicro,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "▾" else "▸",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
        if (expanded && area.entityIds.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (eid in area.entityIds) {
                    Text(
                        text = eid,
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .r1Pressable(onClick = { onTapEntity(eid) })
                            .padding(vertical = 2.dp),
                    )
                }
            }
        }
        if (expanded && area.entityIds.isEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "No entities assigned to this area.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}
