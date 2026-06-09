package com.github.itskenny0.r1ha.feature.devices

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
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
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
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
    val detail by vm.detail.collectAsState()
    val dimens = rememberResponsiveDimens()
    val gridState = rememberLazyGridState()
    val detailListState = rememberLazyListState()
    // The wheel drives whichever surface is visible: the detail list while a
    // device is drilled in, the device grid otherwise. The list is a grid (one
    // column on small tiers, more on roomy ones) so it routes through the grid
    // overload; the detail surface stays a single LazyColumn.
    if (detail != null) {
        WheelScrollFor(
            wheelInput = wheelInput,
            listState = detailListState,
            settings = settings,
        )
    } else {
        WheelScrollForGrid(
            wheelInput = wheelInput,
            gridState = gridState,
            settings = settings,
        )
    }
    LaunchedEffect(Unit) { vm.refresh() }
    // Hardware Back: close the drill-in first, only then leave the screen.
    BackHandler(enabled = detail != null) { vm.closeDevice() }
    val openDetail = detail
    if (openDetail != null) {
        DeviceDetailScreen(
            detail = openDetail,
            listState = detailListState,
            onBack = { vm.closeDevice() },
        )
        return
    }
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
                R1Chip(
                    text = if (ui.loading) "..." else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh devices",
                )
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
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics { contentDescription = "Loading devices" },
                    ) {
                        SkeletonList()
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
                        // Derive the grouped/sorted sections and the device->count
                        // lookups once per data/filter/grouping change rather than on
                        // every recomposition (scroll, expand toggle, refresh-spinner
                        // flip). With 1000+ devices/entities the getters on UiState are
                        // O(devices x entities); recomputing them per frame, and the
                        // per-row areaName/entitiesFor lookups inside the loop, was the
                        // dominant render cost on this screen.
                        val sections = remember(ui.devices, ui.areas, ui.query, ui.grouping) {
                            ui.sections
                        }
                        val filteredCount = remember(ui.devices, ui.areas, ui.query) {
                            ui.filteredDevices.size
                        }
                        val areaName = remember(ui.areas) { ui.areaName }
                        // Group the full entity registry by device once so each row is an
                        // O(1) map lookup instead of an O(entities) filter+sort.
                        val entitiesByDevice = remember(ui.entities) {
                            ui.entities
                                .groupBy { it.deviceId }
                                .mapValues { (_, list) -> list.sortedBy { it.displayName.lowercase() } }
                        }
                        if (sections.isEmpty()) {
                            EmptyState(message = "No matches for '${ui.query}'.")
                        } else {
                            // One column on mini / compact / phone; two on tablet /
                            // expanded, three on extra-large (dashboardColumns gives the
                            // 1/1/2/2/3 progression). Section headers and the count line
                            // span the full row so the grouping stays legible across the
                            // columns.
                            val columns = dimens.dashboardColumns
                            LazyVerticalGrid(
                                state = gridState,
                                columns = GridCells.Fixed(columns),
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    horizontal = R1.space.m, vertical = R1.space.s,
                                ),
                                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                                horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                            ) {
                                item(
                                    key = "count-header",
                                    span = { GridItemSpan(maxLineSpan) },
                                ) {
                                    Text(
                                        text = "$filteredCount DEVICE" +
                                            if (filteredCount == 1) "" else "S",
                                        style = responsiveType(R1.labelMicro),
                                        color = R1.AccentCool,
                                        modifier = Modifier.padding(vertical = R1.space.xs),
                                    )
                                }
                                for ((label, devices) in sections) {
                                    item(
                                        key = "section/$label",
                                        span = { GridItemSpan(maxLineSpan) },
                                    ) {
                                        SectionHeader(label = label, count = devices.size)
                                    }
                                    for (device in devices) {
                                        item(key = "device/${device.id}") {
                                            val deviceEntities = entitiesByDevice[device.id].orEmpty()
                                            DeviceRow(
                                                device = device,
                                                areaName = device.areaId?.let { areaName[it] },
                                                entityCount = deviceEntities.size,
                                                onOpen = { vm.openDevice(device.id) },
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
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "FIND",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.padding(end = R1.space.s),
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
                Spacer(Modifier.width(R1.space.xs))
                Box(
                    modifier = Modifier
                        .size(R1.MinTarget)
                        .r1Pressable({ onQueryChange("") }, contentDescription = "Clear search"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "X", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                }
            }
        }
        Spacer(Modifier.height(R1.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "GROUP",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.padding(end = R1.space.s),
            )
            R1Chip(
                text = "AREA",
                variant = R1ChipVariant.Filter,
                selected = grouping == DevicesViewModel.Grouping.AREA,
                onClick = { onGrouping(DevicesViewModel.Grouping.AREA) },
            )
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = "MAKER",
                variant = R1ChipVariant.Filter,
                selected = grouping == DevicesViewModel.Grouping.MANUFACTURER,
                onClick = { onGrouping(DevicesViewModel.Grouping.MANUFACTURER) },
            )
        }
    }
}

@Composable
private fun SectionHeader(label: String, count: Int) {
    // Canonical group-header treatment (matches R1Section's title line): uppercase
    // section-header type in the accent colour, a hairline rule filling the gap, and a
    // count rendered as an R1Chip Pill at the right edge.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = R1.space.s, bottom = R1.space.xs, start = R1.space.xs, end = R1.space.xs)
            // Promote the section to a TalkBack heading so users can jump
            // between area / manufacturer groups, and merge the label + count
            // pill into one spoken phrase ("Kitchen, 3 devices").
            .semantics(mergeDescendants = true) {
                heading()
                contentDescription = DevicesA11y.sectionHeaderDescription(label, count)
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label.uppercase(),
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
        Spacer(Modifier.width(R1.space.s))
        R1Chip(text = "$count", variant = R1ChipVariant.Pill, tone = R1.InkSoft)
    }
}

@Composable
private fun DeviceRow(
    device: DeviceInfo,
    areaName: String?,
    entityCount: Int,
    onOpen: () -> Unit,
) {
    val disabled = device.disabledBy != null
    // Fold the name, entity count, area, maker/model, and disabled-state into
    // one spoken phrase so TalkBack reads the row as a unit and the "disabled"
    // status is announced in words rather than implied by the dimmed fill.
    val rowDescription = DevicesA11y.deviceRowDescription(
        name = device.displayName,
        entityCount = entityCount,
        areaName = areaName,
        manufacturer = device.manufacturer,
        model = device.model,
        disabled = disabled,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (disabled) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onOpen, contentDescription = "Open ${device.displayName}")
            // mergeDescendants keeps the row's click action for TalkBack's
            // double-tap while replacing the child text with one spoken phrase.
            .semantics(mergeDescendants = true) { contentDescription = rowDescription }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = R1IconSet.Generic,
                contentDescription = null,
                tint = if (disabled) R1.InkMuted else R1.AccentWarm,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = device.displayName,
                style = responsiveType(R1.bodyEmph),
                color = if (disabled) R1.InkMuted else R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = "$entityCount",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentCool,
            )
            Spacer(Modifier.width(R1.space.xs))
            Text(
                text = ">",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = R1.space.xxs)) {
            val meta = remember(device.manufacturer, device.model, areaName) {
                buildList {
                    if (!device.manufacturer.isNullOrBlank()) add(device.manufacturer)
                    if (!device.model.isNullOrBlank()) add(device.model)
                    if (!areaName.isNullOrBlank()) add(areaName)
                }.joinToString(" : ")
            }
            if (meta.isNotBlank()) {
                Text(
                    text = meta,
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (disabled) {
                Spacer(Modifier.width(R1.space.s))
                R1Chip(
                    text = "DISABLED",
                    variant = R1ChipVariant.Pill,
                    tone = R1.StatusAmber,
                )
            }
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

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(R1.space.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = "COULDN'T LOAD DEVICES", style = responsiveType(R1.labelMicro), color = R1.StatusAmber)
        Spacer(Modifier.height(R1.space.xs))
        Text(text = message, style = responsiveType(R1.body), color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.m))
        Text(
            text = "Device registry only flows over the live WebSocket. Retry once it reconnects.",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
    }
}
