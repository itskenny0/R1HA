package com.github.itskenny0.r1ha.feature.floors

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
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Floors registry browser — lists HA's floor primitives (groupings of
 * areas) with the constituent areas and their entity counts rolled up.
 * Useful at-a-glance overview of "what's installed where" on a multi-
 * storey install.
 */
@Composable
fun FloorsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: FloorsViewModel = viewModel(factory = FloorsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedFloorName by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "FLOORS", onBack = onBack)
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
                ui.error != null && ui.floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = ui.error ?: "Error", style = R1.body, color = R1.StatusRed)
                }
                ui.floors.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No floors defined in HA. Settings → Areas & Zones → Floors.",
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
                        items(items = ui.floors, key = { it.name }) { floor ->
                            FloorRow(
                                floor = floor,
                                expanded = expandedFloorName == floor.name,
                                onToggle = {
                                    expandedFloorName = if (expandedFloorName == floor.name) null else floor.name
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FloorRow(
    floor: FloorsViewModel.Floor,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    val totalEntities = floor.areas.sumOf { it.entityCount }
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
            Text(text = floor.name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${floor.areas.size} areas · $totalEntities entities",
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
        if (expanded && floor.areas.isNotEmpty()) {
            Spacer(Modifier.size(6.dp))
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                for (a in floor.areas) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = a.name,
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "${a.entityCount}",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
            }
        }
        if (expanded && floor.areas.isEmpty()) {
            Spacer(Modifier.size(6.dp))
            Text(
                text = "No areas assigned to this floor.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}
