package com.github.itskenny0.r1ha.feature.broadlink

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.ChevronBack
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1ListDetailPane
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.isTwoPane
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Broadlink IR/RF console. One nav route hosting four internal sections:
 *
 *  - CATALOG: remotes + the HA-resident command catalog (one R1HA-tagged
 *    automation per command), with a list/detail split that goes two-pane
 *    on wide windows. Tap a command tile to fire it; long-press for the
 *    action sheet (repeats, pin, rename, delete).
 *  - LEARN: the guided capture stepper ([BroadlinkLearnFlow]).
 *  - REGISTER: catalog a code learned outside the app.
 *  - AUTOMATIONS: browse / filter / create remote.send_command rules
 *    ([BroadlinkAutomationsPane]).
 *
 * Internal sections instead of four nav routes keeps the AppNavGraph diff
 * to a single registration block.
 */
@Composable
fun BroadlinkScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: BroadlinkViewModel = viewModel(
        factory = BroadlinkViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
    val appSettings by settings.settings.collectAsState(initial = AppSettings())
    var section by remember { mutableStateOf(BroadlinkSection.CATALOG) }
    // Selected catalog device as (remoteEntityId, deviceName); null = list.
    var selectedDevice by remember { mutableStateOf<Pair<String, String>?>(null) }

    LaunchedEffect(Unit) {
        vm.refreshRemotes()
        // The catalog is read from HA (tagged automations), so it loads
        // alongside the remotes rather than waiting for the pane.
        vm.loadAutomations()
    }

    BackHandler {
        when {
            // Backing out of an in-flight capture stops the client-side wait
            // first; the next press leaves the learn section.
            section == BroadlinkSection.LEARN &&
                ui.learn.phase == BroadlinkViewModel.LearnPhase.CAPTURING ->
                vm.cancelCapture()
            section != BroadlinkSection.CATALOG -> section = BroadlinkSection.CATALOG
            selectedDevice != null -> selectedDevice = null
            else -> onBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        when (section) {
            BroadlinkSection.CATALOG -> CatalogSection(
                vm = vm,
                ui = ui,
                appSettings = appSettings,
                settings = settings,
                wheelInput = wheelInput,
                selectedDevice = selectedDevice,
                onSelectDevice = { selectedDevice = it },
                onOpenLearn = { prefillDevice ->
                    vm.resetLearn(prefillDevice = prefillDevice.orEmpty())
                    section = BroadlinkSection.LEARN
                },
                onOpenRegister = { section = BroadlinkSection.REGISTER },
                onOpenAutomations = { section = BroadlinkSection.AUTOMATIONS },
                onBack = {
                    if (selectedDevice != null) selectedDevice = null else onBack()
                },
            )
            BroadlinkSection.LEARN -> BroadlinkLearnFlow(
                vm = vm,
                ui = ui,
                settings = settings,
                wheelInput = wheelInput,
                onClose = { section = BroadlinkSection.CATALOG },
            )
            BroadlinkSection.REGISTER -> BroadlinkRegisterForm(
                vm = vm,
                ui = ui,
                settings = settings,
                wheelInput = wheelInput,
                onClose = { section = BroadlinkSection.CATALOG },
            )
            BroadlinkSection.AUTOMATIONS -> BroadlinkAutomationsPane(
                vm = vm,
                ui = ui,
                haRepository = haRepository,
                appSettings = appSettings,
                settings = settings,
                wheelInput = wheelInput,
                onClose = { section = BroadlinkSection.CATALOG },
            )
        }
    }
}

internal enum class BroadlinkSection { CATALOG, LEARN, REGISTER, AUTOMATIONS }

// ── Catalog ─────────────────────────────────────────────────────────────

@Composable
private fun CatalogSection(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    appSettings: AppSettings,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    selectedDevice: Pair<String, String>?,
    onSelectDevice: (Pair<String, String>?) -> Unit,
    onOpenLearn: (prefillDevice: String?) -> Unit,
    onOpenRegister: () -> Unit,
    onOpenAutomations: () -> Unit,
    onBack: () -> Unit,
) {
    val twoPane = isTwoPane()
    // Clear a stale selection once a completed catalog read no longer
    // contains the device (deleted here or on another install).
    LaunchedEffect(ui.catalog, ui.catalogLoaded, selectedDevice) {
        val sel = selectedDevice ?: return@LaunchedEffect
        if (!ui.catalogLoaded) return@LaunchedEffect
        val present = BroadlinkCatalog.devicesFor(ui.catalog, sel.first)
            .any { it.name == sel.second }
        if (!present) onSelectDevice(null)
    }
    R1TopBar(
        title = "BROADLINK",
        onBack = onBack,
        action = {
            R1Chip(
                text = "AUTOMATIONS",
                variant = R1ChipVariant.Action,
                onClick = onOpenAutomations,
                contentDescription = "Open Broadlink automations",
            )
        },
    )
    com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.fillMaxSize()) {
        R1ListDetailPane(
            hasSelection = selectedDevice != null,
            list = {
                CatalogList(
                    vm = vm,
                    ui = ui,
                    settings = settings,
                    wheelInput = wheelInput,
                    wheelEnabled = twoPane || selectedDevice == null,
                    selectedDevice = selectedDevice,
                    onSelectDevice = onSelectDevice,
                    onOpenLearn = onOpenLearn,
                    onOpenRegister = onOpenRegister,
                )
            },
            detail = {
                val sel = selectedDevice
                val group = sel?.let { s ->
                    BroadlinkCatalog.devicesFor(ui.catalog, s.first)
                        .firstOrNull { it.name == s.second }
                }
                if (group != null) {
                    DeviceDetail(
                        vm = vm,
                        ui = ui,
                        group = group,
                        appSettings = appSettings,
                        settings = settings,
                        wheelInput = wheelInput,
                        showBackHeader = !twoPane,
                        onClearSelection = { onSelectDevice(null) },
                        onLearnIntoDevice = { onOpenLearn(group.name) },
                    )
                }
            },
        )
    }
}

@Composable
private fun CatalogList(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    wheelEnabled: Boolean,
    selectedDevice: Pair<String, String>?,
    onSelectDevice: (Pair<String, String>?) -> Unit,
    onOpenLearn: (prefillDevice: String?) -> Unit,
    onOpenRegister: () -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    val listState = rememberLazyListState()
    WheelScrollFor(
        wheelInput = wheelInput,
        listState = listState,
        settings = settings,
        enabled = wheelEnabled,
    )
    val devices = remember(ui.catalog, ui.selectedRemote) {
        BroadlinkCatalog.devicesFor(ui.catalog, ui.selectedRemote)
    }
    val readOnly = remember(ui.catalog) { BroadlinkCatalog.readOnlyEntries(ui.catalog) }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = dimens.screenGutter, vertical = R1.space.s),
    ) {
        item {
            Text(
                text = "COMMANDS ARE STORED IN HA AS R1HA-TAGGED AUTOMATIONS.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
        item { BroadlinkSectionLabel("BLASTER") }
        item {
            when {
                ui.loadingRemotes -> SkeletonList(rows = 1)
                ui.remotesError != null -> Text(
                    text = "Couldn't list remotes: ${ui.remotesError}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
                ui.remotes.isEmpty() -> Text(
                    text = "No remote.* entities. Add the Broadlink integration in HA first.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
                else -> Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    ui.remotes.forEach { remote ->
                        R1Chip(
                            text = remote.name.uppercase() +
                                (if (!remote.available) " · N/A" else ""),
                            variant = R1ChipVariant.Filter,
                            selected = remote.entityId == ui.selectedRemote,
                            tone = if (remote.available) R1.AccentWarm else R1.InkMuted,
                            onClick = { vm.selectRemote(remote.entityId) },
                            contentDescription = "Select blaster ${remote.name}",
                        )
                    }
                }
            }
        }
        item { BroadlinkSectionLabel("ACTIONS") }
        item {
            ConsoleActionRow(
                label = "LEARN NEW COMMAND",
                description = "Capture a button from the physical remote",
                enabled = ui.selectedRemote.isNotBlank(),
                onClick = { onOpenLearn(null) },
            )
        }
        item { Spacer(Modifier.heightIn(min = R1.space.xs)) }
        item {
            ConsoleActionRow(
                label = "REGISTER EXISTING",
                description = "Catalog a code learned outside this app",
                enabled = ui.selectedRemote.isNotBlank(),
                onClick = onOpenRegister,
            )
        }
        item { BroadlinkSectionLabel("DEVICES") }
        // An unreachable HA must read as such: the error (with retry)
        // always shows, and any cached entries stay listed below it.
        if (ui.automationsError != null) {
            item {
                Column {
                    Text(
                        text = "Couldn't read the catalog: ${ui.automationsError}",
                        style = responsiveType(R1.body),
                        color = R1.StatusRed,
                    )
                    Spacer(Modifier.heightIn(min = R1.space.xs))
                    R1Chip(
                        text = "RETRY",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        onClick = { vm.loadAutomations() },
                        contentDescription = "Retry reading the catalog",
                    )
                    if (devices.isNotEmpty()) {
                        Spacer(Modifier.heightIn(min = R1.space.xs))
                        Text(
                            text = "SHOWING THE LAST SUCCESSFUL READ.",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                        )
                    }
                    Spacer(Modifier.heightIn(min = R1.space.s))
                }
            }
        }
        when {
            !ui.catalogLoaded && ui.automationsError == null -> {
                item { SkeletonList(rows = 3) }
            }
            devices.isEmpty() && ui.automationsError == null -> {
                item {
                    Text(
                        text = "Nothing catalogued for this blaster yet. LEARN or REGISTER to start.",
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                }
            }
            else -> {
                items(devices.size, key = { i -> devices[i].remoteEntityId + "/" + devices[i].name }) { i ->
                    val device = devices[i]
                    val isSelected = selectedDevice?.first == device.remoteEntityId &&
                        selectedDevice.second == device.name
                    DeviceRow(
                        group = device,
                        selected = isSelected,
                        onClick = { onSelectDevice(device.remoteEntityId to device.name) },
                    )
                    Spacer(Modifier.heightIn(min = R1.space.xs))
                }
            }
        }
        if (readOnly.isNotEmpty()) {
            item { BroadlinkSectionLabel("UNRECOGNIZED FORMAT") }
            item {
                Text(
                    text = "R1HA-TAGGED, BUT A NEWER MARKER VERSION. TAP FIRES; EDITING IS DISABLED.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
            items(readOnly.size, key = { i -> "ro/" + readOnly[i].automationId }) { i ->
                val entry = readOnly[i]
                Spacer(Modifier.heightIn(min = R1.space.xs))
                ReadOnlyEntryRow(
                    entry = entry,
                    firing = entry.automationId in ui.firing,
                    onFire = { vm.fireEntry(entry) },
                )
            }
        }
    }
}

@Composable
private fun ConsoleActionRow(
    label: String,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(
                onClick = { if (enabled) onClick() },
                contentDescription = label,
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = label,
            style = responsiveType(R1.bodyEmph),
            color = if (enabled) R1.AccentWarm else R1.InkMuted,
        )
        Text(
            text = description,
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun DeviceRow(
    group: BroadlinkCatalog.DeviceGroup,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (selected) R1.Surface else R1.SurfaceMuted)
            .border(1.dp, if (selected) R1.AccentWarm else R1.Hairline, R1.ShapeS)
            .r1Pressable(
                onClick = onClick,
                contentDescription = "Open device ${group.name}",
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = group.name.uppercase(),
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = group.remoteEntityId,
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = "${group.entries.size}",
            style = responsiveType(R1.numeralM),
            color = R1.AccentWarm,
        )
        Spacer(Modifier.width(R1.space.xs))
        Text(
            text = if (group.entries.size == 1) "CMD" else "CMDS",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun ReadOnlyEntryRow(
    entry: BroadlinkCatalog.Entry,
    firing: Boolean,
    onFire: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, if (firing) R1.AccentWarm else R1.Hairline, R1.ShapeS)
            .r1Pressable(
                onClick = onFire,
                contentDescription = "Fire ${entry.alias}",
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.alias.uppercase(),
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "marker ${entry.markerVersion} · ${entry.entityId}",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = if (firing) "TX…" else "RUN",
            style = responsiveType(R1.labelMicro),
            color = R1.AccentWarm,
        )
    }
}

// ── Device detail (command grid) ────────────────────────────────────────

@Composable
private fun DeviceDetail(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    group: BroadlinkCatalog.DeviceGroup,
    appSettings: AppSettings,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    showBackHeader: Boolean,
    onClearSelection: () -> Unit,
    onLearnIntoDevice: () -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    val gridState = rememberLazyGridState()
    WheelScrollForGrid(
        wheelInput = wheelInput,
        gridState = gridState,
        settings = settings,
        enabled = true,
    )
    var sheetAutomationId by remember { mutableStateOf<String?>(null) }
    val columns = if (dimens.tier == WindowTier.R1 || dimens.tier == WindowTier.COMPACT) {
        1
    } else {
        dimens.gridColumns
    }
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        ) {
            if (showBackHeader) {
                ChevronBack(onClick = onClearSelection)
                Spacer(Modifier.width(R1.space.xs))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = group.name.uppercase(),
                    style = responsiveType(R1.sectionHeader),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = group.remoteEntityId,
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
            R1Chip(
                text = "LEARN +",
                variant = R1ChipVariant.Action,
                onClick = onLearnIntoDevice,
                contentDescription = "Learn a new command into ${group.name}",
            )
        }
        HairlineRule()
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                horizontal = dimens.screenGutter,
                vertical = R1.space.s,
            ),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            items(group.entries, key = { it.automationId }) { entry ->
                CommandTile(
                    entry = entry,
                    firing = entry.automationId in ui.firing,
                    onFire = { vm.fireEntry(entry) },
                    onLongPress = { sheetAutomationId = entry.automationId },
                )
            }
        }
    }
    val sheetId = sheetAutomationId
    if (sheetId != null) {
        // Re-resolve from the live catalog so renames show immediately.
        val live = ui.catalog.firstOrNull { it.automationId == sheetId }
        if (live == null || live.meta == null) {
            sheetAutomationId = null
        } else {
            CommandSheet(
                vm = vm,
                entry = live,
                meta = live.meta,
                pages = appSettings.pages,
                firing = live.automationId in ui.firing,
                onDismiss = { sheetAutomationId = null },
            )
        }
    }
}

@Composable
private fun CommandTile(
    entry: BroadlinkCatalog.Entry,
    firing: Boolean,
    onFire: () -> Unit,
    onLongPress: () -> Unit,
) {
    val meta = entry.meta ?: return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, if (firing) R1.AccentWarm else R1.Hairline, R1.ShapeS)
            .r1RowPressable(
                onTap = onFire,
                onLongPress = onLongPress,
                contentDescription = "Fire ${entry.alias}. Long press for options",
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CommandTypeBadge(type = meta.type)
        Spacer(Modifier.width(R1.space.s))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.alias.uppercase(),
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!entry.alias.equals(meta.command, ignoreCase = true)) {
                Text(
                    text = meta.command,
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(R1.space.s))
        if (firing) {
            Text(
                text = "TX…",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentWarm,
            )
        } else {
            RelativeTimeLabel(
                at = entry.lastTriggered?.let { parseHaInstant(it) },
                color = R1.InkMuted,
                style = R1.labelMicro,
            )
        }
    }
}

// ── Command action sheet ────────────────────────────────────────────────

@Composable
private fun CommandSheet(
    vm: BroadlinkViewModel,
    entry: BroadlinkCatalog.Entry,
    meta: BroadlinkMarker.CommandMeta,
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    firing: Boolean,
    onDismiss: () -> Unit,
) {
    var repeats by remember(entry.automationId) { mutableStateOf(1) }
    var renameOpen by remember(entry.automationId) { mutableStateOf(false) }
    var renameText by remember(entry.automationId) { mutableStateOf(entry.alias) }
    var deleteArmed by remember(entry.automationId) { mutableStateOf(false) }
    var pagePickerOpen by remember(entry.automationId) { mutableStateOf(false) }
    // Dialog window rather than an in-tree overlay: this sheet opens from
    // deep inside the catalog's list/detail Column, where a fillMaxSize Box
    // would only cover the remaining (zero) column height.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
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
                .padding(R1.space.l)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CommandTypeBadge(type = meta.type)
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = entry.alias.uppercase(),
                    style = responsiveType(R1.sectionHeader),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = "${meta.device} / ${meta.command} · ${meta.remote}",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
            Spacer(Modifier.heightIn(min = R1.space.m))
            SignatureTrace(
                deviceName = meta.device,
                commandName = meta.command,
                type = meta.type,
            )
            if (meta.notes.isNotBlank()) {
                Spacer(Modifier.heightIn(min = R1.space.s))
                Text(
                    text = meta.notes,
                    style = responsiveType(R1.body),
                    color = R1.InkSoft,
                )
            }
            Spacer(Modifier.heightIn(min = R1.space.m))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                R1Chip(
                    text = if (firing) "TX…" else "FIRE ×$repeats",
                    variant = R1ChipVariant.Action,
                    selected = true,
                    onClick = {
                        if (!firing) {
                            vm.fireEntry(entry, repeats)
                        }
                    },
                    contentDescription = "Fire ${entry.alias} $repeats times",
                )
                Spacer(Modifier.weight(1f))
                R1Chip(
                    text = "−",
                    variant = R1ChipVariant.Action,
                    onClick = { if (repeats > 1) repeats-- },
                    contentDescription = "Fewer repeats",
                )
                Text(
                    text = "$repeats",
                    style = responsiveType(R1.numeralM),
                    color = R1.Ink,
                )
                R1Chip(
                    text = "+",
                    variant = R1ChipVariant.Action,
                    onClick = { if (repeats < 10) repeats++ },
                    contentDescription = "More repeats",
                )
            }
            Spacer(Modifier.heightIn(min = R1.space.m))
            HairlineRule()
            Spacer(Modifier.heightIn(min = R1.space.m))
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                R1Chip(
                    text = "PIN TO DECK",
                    variant = R1ChipVariant.Action,
                    onClick = { pagePickerOpen = true },
                    contentDescription = "Pin ${entry.alias} to a card stack page",
                )
                R1Chip(
                    text = "RENAME",
                    variant = R1ChipVariant.Action,
                    onClick = { renameOpen = !renameOpen },
                    contentDescription = "Rename the command",
                )
                R1Chip(
                    text = if (deleteArmed) "CONFIRM DELETE" else "DELETE",
                    variant = R1ChipVariant.Action,
                    tone = R1.StatusRed,
                    selected = deleteArmed,
                    onClick = {
                        if (deleteArmed) {
                            vm.deleteEntry(entry)
                            onDismiss()
                        } else {
                            deleteArmed = true
                        }
                    },
                    contentDescription = if (deleteArmed) {
                        "Confirm delete ${entry.alias} from HA"
                    } else {
                        "Delete ${entry.alias}"
                    },
                )
            }
            if (deleteArmed) {
                Spacer(Modifier.heightIn(min = R1.space.xs))
                Text(
                    text = "Deletes the stored code on HA and its R1HA catalog automation.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.StatusRed,
                )
            }
            if (renameOpen) {
                Spacer(Modifier.heightIn(min = R1.space.m))
                Text(
                    text = "RENAMES THE HA AUTOMATION'S ALIAS. THE CODE STAYS UNDER '${meta.command}'.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
                Spacer(Modifier.heightIn(min = R1.space.xs))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.weight(1f)) {
                        R1TextField(
                            value = renameText,
                            onValueChange = { renameText = it },
                            placeholder = meta.command,
                            monospace = false,
                        )
                    }
                    Spacer(Modifier.width(R1.space.s))
                    R1Chip(
                        text = "SAVE",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        onClick = {
                            vm.renameEntry(entry, renameText)
                            renameOpen = false
                        },
                        contentDescription = "Save the new name",
                    )
                }
            }
        }
    }
    } // Dialog
    if (pagePickerOpen) {
        PagePickerDialog(
            pages = pages,
            onPick = { pageId ->
                vm.pinEntryToPage(
                    pageId = pageId,
                    entry = entry,
                    label = entry.alias,
                    repeats = repeats,
                )
                pagePickerOpen = false
                onDismiss()
            },
            onDismiss = { pagePickerOpen = false },
        )
    }
}

// ── Page picker (shared with the automations pane) ──────────────────────

@Composable
internal fun PagePickerDialog(
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    onPick: (pageId: String) -> Unit,
    onDismiss: () -> Unit,
) {
    // Dialog window for the same reason as the command sheet: call sites
    // live inside Columns whose remaining height is zero.
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "PIN TO PAGE",
                style = responsiveType(R1.sectionHeader),
                color = R1.Ink,
            )
            Spacer(Modifier.heightIn(min = R1.space.m))
            pages.forEach { page ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(
                            onClick = { onPick(page.id) },
                            contentDescription = "Pin to page ${page.name}",
                        )
                        .heightIn(min = R1.MinTarget)
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = page.name.uppercase(),
                        style = responsiveType(R1.bodyEmph),
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${page.pinnedCards.size} PINNED",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                    )
                }
                Spacer(Modifier.heightIn(min = R1.space.xs))
            }
            Spacer(Modifier.heightIn(min = R1.space.s))
            R1Chip(
                text = "CANCEL",
                variant = R1ChipVariant.Action,
                onClick = onDismiss,
                contentDescription = "Cancel pinning",
            )
        }
    }
    } // Dialog
}
