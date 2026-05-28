package com.github.itskenny0.r1ha.feature.devices

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
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Devices browser: surfaces HA's device_registry as a sectioned list
 * (by area or manufacturer), with substring search across name /
 * manufacturer / model / area. Tap a device to drill in and see its
 * registered entities; disabled devices show a subtle chip.
 *
 * Read-only. Editing names / areas / disabled-state stays in HA's web
 * UI; this surface exists so the user can answer "what's HA actually
 * tracking?" without leaving the native app.
 */
@Composable
fun DevicesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: DevicesViewModel = viewModel(factory = DevicesViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    var openedDeviceId by remember { mutableStateOf<String?>(null) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "DEVICES",
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
                SearchAndGroupBar(
                    query = ui.query,
                    onQueryChange = { vm.setQuery(it) },
                    grouping = ui.grouping,
                    onGrouping = { vm.setGrouping(it) },
                )
                when {
                    ui.loading && ui.devices.isEmpty() -> Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = R1.AccentWarm,
                        )
                    }
                    ui.error != null && ui.devices.isEmpty() -> ErrorState(message = ui.error.orEmpty())
                    ui.devices.isEmpty() -> EmptyState(
                        message = "No devices in HA's registry yet.",
                    )
                    else -> PullToRefreshBox(
                        isRefreshing = ui.loading,
                        onRefresh = { vm.refresh() },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val sections = ui.sections
                        if (sections.isEmpty()) {
                            EmptyState(message = "No matches for '${ui.query}'.")
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = 12.dp, vertical = 8.dp,
                                ),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                item {
                                    Text(
                                        text = "${ui.filteredDevices.size} DEVICE" +
                                            if (ui.filteredDevices.size == 1) "" else "S",
                                        style = R1.labelMicro,
                                        color = R1.AccentCool,
                                        modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                }
                                for ((label, devices) in sections) {
                                    item(key = "section/$label") {
                                        SectionHeader(label = label, count = devices.size)
                                    }
                                    for (device in devices) {
                                        item(key = "device/${device.id}") {
                                            DeviceRow(
                                                device = device,
                                                areaName = device.areaId?.let { ui.areaName[it] },
                                                expanded = openedDeviceId == device.id,
                                                entityCount = ui.entitiesFor(device.id).size,
                                                entities = if (openedDeviceId == device.id)
                                                    ui.entitiesFor(device.id) else emptyList(),
                                                onToggle = {
                                                    openedDeviceId = if (openedDeviceId == device.id)
                                                        null else device.id
                                                },
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
private fun SearchAndGroupBar(
    query: String,
    onQueryChange: (String) -> Unit,
    grouping: DevicesViewModel.Grouping,
    onGrouping: (DevicesViewModel.Grouping) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                    placeholder = "name, manufacturer, area...",
                    monospace = false,
                )
            }
            if (query.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .r1Pressable({ onQueryChange("") }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "X", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GROUP",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(end = 8.dp),
            )
            GroupChip(
                label = "AREA",
                selected = grouping == DevicesViewModel.Grouping.AREA,
                onClick = { onGrouping(DevicesViewModel.Grouping.AREA) },
            )
            Spacer(Modifier.width(6.dp))
            GroupChip(
                label = "MAKER",
                selected = grouping == DevicesViewModel.Grouping.MANUFACTURER,
                onClick = { onGrouping(DevicesViewModel.Grouping.MANUFACTURER) },
            )
        }
    }
}

@Composable
private fun GroupChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val tone = if (selected) R1.AccentWarm else R1.InkSoft
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (selected) tone.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) tone.copy(alpha = 0.6f) else R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = tone)
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
            style = R1.labelMicro,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        Text(text = "$count", style = R1.labelMicro, color = R1.InkMuted)
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    areaName: String?,
    expanded: Boolean,
    entityCount: Int,
    entities: List<EntityRegistryEntry>,
    onToggle: () -> Unit,
) {
    val disabled = device.disabledBy != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (disabled) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = device.displayName,
                style = R1.bodyEmph,
                color = if (disabled) R1.InkMuted else R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "$entityCount",
                style = R1.labelMicro,
                color = R1.AccentCool,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (expanded) "v" else ">",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
            val meta = buildList {
                if (!device.manufacturer.isNullOrBlank()) add(device.manufacturer)
                if (!device.model.isNullOrBlank()) add(device.model)
                if (!areaName.isNullOrBlank()) add(areaName)
            }.joinToString(" : ")
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (disabled) {
                Spacer(Modifier.width(8.dp))
                MicroChip(
                    text = "DISABLED",
                    tone = R1.StatusAmber,
                )
            }
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            val versions = listOfNotNull(
                device.swVersion?.let { "sw $it" },
                device.hwVersion?.let { "hw $it" },
            ).joinToString(", ")
            if (versions.isNotBlank()) {
                Text(text = versions, style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.height(4.dp))
            }
            if (!device.configurationUrl.isNullOrBlank()) {
                Text(
                    text = "config: ${device.configurationUrl}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 2,
                )
                Spacer(Modifier.height(4.dp))
            }
            Text(text = "ENTITIES", style = R1.labelMicro, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            if (entities.isEmpty()) {
                Text(
                    text = "No entities registered for this device.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (entity in entities) {
                        EntityRow(entity = entity)
                    }
                }
            }
        }
    }
}

@Composable
private fun EntityRow(entity: EntityRegistryEntry) {
    val disabled = entity.disabledBy != null || entity.hiddenBy != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entity.displayName,
                style = R1.body,
                color = if (disabled) R1.InkMuted else R1.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (entity.platform != null) {
                Text(
                    text = entity.platform.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
        Text(
            text = entity.entityId,
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 1,
        )
    }
}

@Composable
private fun MicroChip(text: String, tone: androidx.compose.ui.graphics.Color) {
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
        Text(text = "COULDN'T LOAD DEVICES", style = R1.labelMicro, color = R1.StatusAmber)
        Spacer(Modifier.height(6.dp))
        Text(text = message, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Device registry only flows over the live WebSocket. Retry once it reconnects.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}
