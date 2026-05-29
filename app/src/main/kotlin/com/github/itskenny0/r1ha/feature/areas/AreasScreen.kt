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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
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
    val drill by vm.drill.collectAsState()
    val listState = rememberLazyListState()
    val drillListState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Wheel scrolls whichever list is in front — the drill-in when open,
    // the area list otherwise.
    WheelScrollFor(
        wheelInput = wheelInput,
        listState = if (drill != null) drillListState else listState,
        settings = settings,
    )
    LaunchedEffect(Unit) { vm.refresh() }
    var expandedAreaName by remember { mutableStateOf<String?>(null) }
    // Back closes the drill-in first, then leaves the screen.
    androidx.activity.compose.BackHandler(enabled = drill != null) { vm.closeArea() }
    drill?.let { d ->
        AreaDrillScreen(
            drill = d,
            listState = drillListState,
            onBack = { vm.closeArea() },
            onRefresh = { vm.refreshDrill() },
            onTapEntity = { vm.activate(it) },
            onRename = { vm.renameArea(it) },
        )
        return
    }
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
                            onOpen = { vm.openArea(area) },
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
    onOpen: () -> Unit,
    onToggle: () -> Unit,
    onTapEntity: (String) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                // Tapping the row body opens the controls drill-in — the
                // primary action. The abstract in-place entity list stays
                // available via the count/▸ region at the trailing edge.
                .r1Pressable(onClick = onOpen)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = area.name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            // Count + expand chevron: a separate tap target that toggles
            // the quick abstract peek without leaving the list. 48 dp so
            // it's a comfortable wheel-tap.
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .r1Pressable(onClick = onToggle)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
            Spacer(Modifier.width(2.dp))
            // Chevron hinting the drill-in opens on a row-body tap.
            Text(text = "›", style = R1.body, color = R1.InkSoft)
        }
        if (expanded && area.entityIds.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            ) {
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
            Text(
                text = "No entities assigned to this area.",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * Per-area drill-in. Shows the area's entities grouped by domain with
 * inline controls: toggleable domains (lights / switches / fans / etc.)
 * flip on tap, scenes / scripts / buttons fire, sensors surface their
 * reading. Reuses the R1Section header + a Search-style row idiom rather
 * than pulling in the card stack.
 */
@Composable
private fun AreaDrillScreen(
    drill: AreasViewModel.DrillState,
    listState: LazyListState,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onTapEntity: (EntityState) -> Unit,
    onRename: (String) -> Unit,
) {
    val matchedCount = drill.groups.sumOf { it.entities.size }
    // The rename affordance only makes sense when HA gave us a stable area_id
    // to key the update on; areas surfaced without one stay refresh-only.
    val canRename = !drill.area.areaId.isNullOrBlank()
    var renaming by remember(drill.area.areaId) { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = drill.area.name.uppercase(),
            onBack = onBack,
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (canRename) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(onClick = { renaming = true })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(text = "RENAME", style = R1.labelMicro, color = R1.InkSoft)
                        }
                        Spacer(Modifier.width(6.dp))
                    }
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = onRefresh)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "REFRESH", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            },
        )
        if (renaming) {
            RenameAreaSheet(
                areaId = drill.area.areaId.orEmpty(),
                currentName = drill.area.name,
                onDismiss = { renaming = false },
                onSave = { name ->
                    renaming = false
                    onRename(name)
                },
            )
        }
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                drill.loading && drill.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                drill.error != null && drill.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Couldn't load this area: ${drill.error}",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                }
                drill.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (drill.unmatchedCount > 0)
                            "No live entities to control here. ${drill.unmatchedCount} assigned entit${if (drill.unmatchedCount == 1) "y is" else "ies are"} not in the current state set."
                        else "No entities assigned to this area.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                    isRefreshing = drill.loading,
                    onRefresh = onRefresh,
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
                        item("__drill_summary") {
                            Text(
                                text = buildString {
                                    append("$matchedCount controllable")
                                    if (drill.unmatchedCount > 0) {
                                        append(" · ${drill.unmatchedCount} other")
                                    }
                                },
                                style = R1.labelMicro,
                                color = R1.InkMuted,
                                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                            )
                        }
                        for (group in drill.groups) {
                            item(key = "__hdr_${group.domain.prefix}") {
                                com.github.itskenny0.r1ha.ui.components.R1Section(
                                    title = group.label,
                                    count = group.entities.size,
                                    topSpace = R1.space.m,
                                ) {}
                            }
                            items(
                                items = group.entities,
                                key = { it.id.value },
                            ) { entity ->
                                AreaEntityRow(entity = entity, onTap = { onTapEntity(entity) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * One entity row inside an area drill-in. Tap fires the domain action
 * (toggle / fire / press / info). The trailing label echoes what the
 * tap will do, and toggleable rows tint their state line by on/off so
 * the current state reads at a glance, like the card-stack mini controls.
 */
@Composable
private fun AreaEntityRow(
    entity: EntityState,
    onTap: () -> Unit,
) {
    val actionLabel = when (entity.id.domain) {
        Domain.SCENE, Domain.SCRIPT -> "FIRE"
        Domain.BUTTON, Domain.INPUT_BUTTON -> "PRESS"
        Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
        Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
        Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
        Domain.LAWN_MOWER, Domain.VALVE -> if (entity.isOn) "TURN OFF" else "TURN ON"
        else -> "INFO"
    }
    val isToggle = actionLabel == "TURN ON" || actionLabel == "TURN OFF"
    val stateLine = buildString {
        entity.rawState?.let { append(it) }
        entity.unit?.let { append(' ').append(it) }
    }.ifBlank { entity.id.value }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entity.friendlyName, style = R1.bodyEmph, color = R1.Ink, maxLines = 1)
            Text(
                text = stateLine,
                style = R1.labelMicro,
                color = if (isToggle && entity.isOn) R1.AccentWarm else R1.InkSoft,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = actionLabel,
            style = R1.labelMicro,
            color = if (isToggle && entity.isOn) R1.AccentWarm else R1.InkSoft,
        )
    }
}

/**
 * Modal sheet for renaming the drilled-in area. Prefilled with the current
 * name; SAVE fires the area_registry update through the view model, which
 * refreshes the list and patches the open drill on success. Mirrors the tag
 * rename sheet idiom so the two read identically.
 */
@Composable
private fun RenameAreaSheet(
    areaId: String,
    currentName: String,
    onDismiss: () -> Unit,
    onSave: (name: String) -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    var name by remember(areaId) { mutableStateOf(currentName) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l),
        ) {
            Text(text = "RENAME AREA", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = areaId,
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(R1.space.m))
            Text(text = "NAME", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.size(R1.space.xs))
            R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Friendly name (e.g. Kitchen)",
                monospace = false,
            )
            Spacer(Modifier.size(R1.space.l))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                R1Button(text = "CANCEL", onClick = onDismiss, variant = R1ButtonVariant.Outlined)
                Spacer(Modifier.width(R1.space.s))
                R1Button(text = "SAVE", onClick = { onSave(name.trim()) })
            }
        }
    }
}
