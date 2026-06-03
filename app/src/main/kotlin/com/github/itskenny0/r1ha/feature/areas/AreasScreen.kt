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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
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
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Areas browser: lists HA's area registry, with entity count per
 * area and a tappable expansion showing the full entity list.
 *
 * Powered by a server-side Jinja template through HA's
 * `/api/template` endpoint rather than the WebSocket
 * `config/area_registry/list` command; keeps the WS protocol
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
    // The area list flows into a responsive grid (one column on the R1 / compact
    // panel, more on tablet+) so it needs a grid state; the drill stays a single
    // controllable column.
    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val drillListState = rememberLazyListState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    // Wheel scrolls whichever surface is in front: the drill-in when open, the
    // area grid otherwise. Two ScrollableState recipients, so two collectors,
    // each composed only while its surface is the front one.
    if (drill != null) {
        WheelScrollFor(
            wheelInput = wheelInput,
            listState = drillListState,
            settings = settings,
        )
    } else {
        com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid(
            wheelInput = wheelInput,
            gridState = gridState,
            settings = settings,
        )
    }
    LaunchedEffect(Unit) { vm.refresh() }
    // Tracks the expanded row by its stable key (area_id when present), not the
    // display name: two areas can share a name and would otherwise expand together.
    var expandedAreaKey by remember { mutableStateOf<String?>(null) }
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
                // Routed through R1Chip so the current sort + next action is spoken.
                val nextSort = if (ui.sort == AreasViewModel.Sort.ALPHA)
                    AreasViewModel.Sort.COUNT else AreasViewModel.Sort.ALPHA
                val sortSpoken = if (ui.sort == AreasViewModel.Sort.ALPHA) {
                    "Sorted A to Z. Tap to sort by entity count."
                } else {
                    "Sorted by entity count. Tap to sort A to Z."
                }
                R1Chip(
                    text = if (ui.sort == AreasViewModel.Sort.ALPHA) "A-Z" else "BY COUNT",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.setSort(nextSort) },
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .semantics { contentDescription = sortSpoken },
                )
            },
        )
        val sortedAreas by vm.sortedAreas.collectAsState()
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
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
                modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = ui.error ?: "Error",
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.areas.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No areas defined in HA. Settings → Areas in HA's web UI.",
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Multi-column flow on roomy tiers (gridColumns: 2/2/3/4/5) so a wide
                // panel fills with area cards side by side instead of one tall ribbon;
                // the R1 / compact panel stays single column where a row's name +
                // summary + count chevrons need the full width to read.
                val areaColumns = if (dimens.capsContentWidth) dimens.gridColumns else 1
                LazyVerticalGrid(
                    state = gridState,
                    columns = GridCells.Fixed(areaColumns),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = dimens.screenGutter, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(
                        items = sortedAreas,
                        key = { it.key },
                        // An expanded row reveals its full entity list; span every
                        // column so that inline list keeps the whole content width
                        // rather than being squeezed into one grid cell.
                        span = {
                            if (expandedAreaKey == it.key) GridItemSpan(maxLineSpan)
                            else GridItemSpan(1)
                        },
                    ) { area ->
                        AreaRow(
                            area = area,
                            expanded = expandedAreaKey == area.key,
                            onOpen = { vm.openArea(area) },
                            onToggle = {
                                expandedAreaKey = if (expandedAreaKey == area.key) null else area.key
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
                // Tapping the row body opens the controls drill-in: the
                // primary action. The abstract in-place entity list stays
                // available via the count/▸ region at the trailing edge.
                .r1Pressable(onClick = onOpen)
                .heightIn(min = R1.MinTarget)
                .semantics {
                    contentDescription = buildString {
                        append(area.name)
                        append(", ${area.entityIds.size} entities")
                        area.summary?.let { append(", $it") }
                        if (area.activeAlerts > 0) append(", ${area.activeAlerts} active alerts")
                        append(". Opens controls.")
                    }
                }
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = area.name,
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Secondary line: HA's area-card temperature / humidity readout,
                // led by a small thermometer glyph so it reads as a climate row.
                // Only painted once the state snapshot enriched the row.
                area.summary?.takeIf { it.isNotBlank() }?.let { s ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = R1Icons.forDomain("temperature"),
                            contentDescription = null,
                            tint = R1.InkSoft,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(R1.space.xxs))
                        Text(
                            text = s,
                            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
            Spacer(Modifier.width(R1.space.s))
            // Active-alert pip: an amber dot + count, mirroring HA's alert badges
            // (motion / moisture / smoke firing in this area). Hidden when quiet.
            if (area.activeAlerts > 0) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.StatusAmber.copy(alpha = 0.18f))
                        .padding(horizontal = R1.space.xs, vertical = R1.space.xxs),
                ) {
                    Text(
                        text = "! ${area.activeAlerts}",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                        color = R1.StatusAmber,
                    )
                }
                Spacer(Modifier.width(R1.space.xs))
            }
            // Count + expand chevron: a separate tap target that toggles
            // the quick abstract peek without leaving the list. 48 dp so
            // it's a comfortable wheel-tap.
            Row(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = onToggle)
                    .semantics {
                        contentDescription =
                            if (expanded) "Hide ${area.name} entity list"
                            else "Show ${area.name} entity list, ${area.entityIds.size} entities"
                    }
                    .padding(horizontal = R1.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${area.entityIds.size}",
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                    color = R1.AccentWarm,
                )
                Spacer(Modifier.width(R1.space.xs))
                Text(
                    text = if (expanded) "▾" else "▸",
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                )
            }
            Spacer(Modifier.width(R1.space.xxs))
            // Chevron hinting the drill-in opens on a row-body tap.
            Text(
                text = "›",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.InkSoft,
            )
        }
        if (expanded && area.entityIds.isNotEmpty()) {
            Column(
                verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
                modifier = Modifier.padding(start = R1.space.m, end = R1.space.m, bottom = R1.space.s),
            ) {
                for (eid in area.entityIds) {
                    // Prefer the resolved friendly name (folded in once the state
                    // snapshot loaded); fall back to the raw entity_id only for
                    // entities the snapshot didn't carry.
                    val display = area.entityNames[eid] ?: eid
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .r1Pressable(onClick = { onTapEntity(eid) })
                            .padding(vertical = R1.space.xxs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = R1Icons.forEntity(eid),
                            contentDescription = null,
                            tint = R1.InkMuted,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(R1.space.xs))
                        Text(
                            text = display,
                            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        if (expanded && area.entityIds.isEmpty()) {
            Text(
                text = "No entities assigned to this area.",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.padding(start = R1.space.m, end = R1.space.m, bottom = R1.space.s),
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
    val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
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
                                .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                        ) {
                            Text(
                                text = "RENAME",
                                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                                color = R1.InkSoft,
                            )
                        }
                        Spacer(Modifier.width(R1.space.xs))
                    }
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = onRefresh)
                            .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                    ) {
                        Text(
                            text = "REFRESH",
                            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                        )
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
                    modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Couldn't load this area: ${drill.error}",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                        color = R1.StatusRed,
                    )
                }
                drill.groups.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(dimens.screenGutter),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (drill.unmatchedCount > 0)
                            "No live entities to control here. ${drill.unmatchedCount} assigned entit${if (drill.unmatchedCount == 1) "y is" else "ies are"} not in the current state set."
                        else "No entities assigned to this area.",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
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
                            horizontal = dimens.screenGutter, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        item("__drill_summary") {
                            Column(modifier = Modifier.padding(start = R1.space.xxs, bottom = R1.space.xxs)) {
                                Text(
                                    text = buildString {
                                        append("$matchedCount controllable")
                                        if (drill.unmatchedCount > 0) {
                                            append(" · ${drill.unmatchedCount} other")
                                        }
                                        if (drill.activeAlerts > 0) {
                                            append(" · ${drill.activeAlerts} alert")
                                            if (drill.activeAlerts != 1) append("s")
                                        }
                                    },
                                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                                    color = if (drill.activeAlerts > 0) R1.StatusAmber else R1.InkMuted,
                                )
                                // Temperature / humidity readout, matching the list row
                                // and HA's area-card secondary line.
                                drill.summary?.takeIf { it.isNotBlank() }?.let { s ->
                                    Text(
                                        text = s,
                                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                                        color = R1.InkSoft,
                                    )
                                }
                            }
                        }
                        for (group in drill.groups) {
                            item(key = "__hdr_${group.domain.prefix}") {
                                // Trailing domain glyph from the in-house icon set
                                // so the bucket reads by type, not just by label.
                                com.github.itskenny0.r1ha.ui.components.R1Section(
                                    title = group.label,
                                    count = group.entities.size,
                                    topSpace = R1.space.m,
                                    trailing = {
                                        Icon(
                                            imageVector = R1Icons.forDomain(group.domain.prefix),
                                            contentDescription = null,
                                            tint = R1.AccentWarm,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
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
    // Unavailable / unknown entities can't be controlled: HA's own tile dims them
    // and a turn_on/off call would just bounce. Tapping still opens info, but the
    // row reads as inert and the action label says so rather than promising a toggle.
    val unavailable = !entity.isAvailable
    val toggleable = when (entity.id.domain) {
        Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
        Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
        Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
        Domain.LAWN_MOWER, Domain.VALVE -> true
        else -> false
    }
    val actionLabel = when {
        unavailable -> "UNAVAILABLE"
        entity.id.domain == Domain.SCENE || entity.id.domain == Domain.SCRIPT -> "FIRE"
        entity.id.domain == Domain.BUTTON || entity.id.domain == Domain.INPUT_BUTTON -> "PRESS"
        toggleable -> if (entity.isOn) "TURN OFF" else "TURN ON"
        else -> "INFO"
    }
    val isToggle = toggleable && !unavailable
    val stateLine = if (unavailable) {
        "unavailable"
    } else buildString {
        entity.rawState?.let { append(it) }
        entity.unit?.let { append(' ').append(it) }
    }.ifBlank { entity.id.value }
    val stateColor = when {
        unavailable -> R1.InkMuted
        isToggle && entity.isOn -> R1.AccentWarm
        else -> R1.InkSoft
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .heightIn(min = R1.MinTarget)
            .then(if (unavailable) Modifier.alpha(0.55f) else Modifier)
            .semantics {
                contentDescription = buildString {
                    append(entity.friendlyName)
                    append(", ").append(stateLine)
                    if (!unavailable) append(", tap to ${actionLabel.lowercase()}")
                }
            }
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Per-entity glyph, tinted to the row's state colour so an on light reads
        // warm and an inert sensor reads soft, matching the card-stack mini rows.
        Icon(
            imageVector = R1Icons.forEntity(
                entity.id.value,
                deviceClass = entity.deviceClass,
                state = entity.rawState,
            ),
            contentDescription = null,
            tint = stateColor,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(R1.space.s))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entity.friendlyName,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stateLine,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                color = stateColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = actionLabel,
            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
            color = stateColor,
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
            Text(
                text = "RENAME AREA",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.sectionHeader),
                color = R1.AccentWarm,
            )
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = areaId,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body)
                    .copy(fontFamily = FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
            )
            Spacer(Modifier.size(R1.space.m))
            Text(
                text = "NAME",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
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
