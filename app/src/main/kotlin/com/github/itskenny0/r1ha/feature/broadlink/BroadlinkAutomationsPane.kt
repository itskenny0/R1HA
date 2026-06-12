package com.github.itskenny0.r1ha.feature.broadlink

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Automations pane of the Broadlink console. Lists every automation (the
 * heavyweight browse/trace surface stays feature/automations; this pane
 * reuses the same repository reads + service shapes), adds a BROADLINK
 * filter driven by fetched config bodies, pin-to-deck per row, and a
 * focused builder for the common trigger -> send_command rule.
 */
@Composable
internal fun BroadlinkAutomationsPane(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    haRepository: HaRepository,
    appSettings: AppSettings,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onClose: () -> Unit,
) {
    var builderOpen by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (ui.automations.isEmpty()) vm.loadAutomations() }
    if (builderOpen) {
        AutomationBuilder(
            vm = vm,
            ui = ui,
            haRepository = haRepository,
            appSettings = appSettings,
            settings = settings,
            wheelInput = wheelInput,
            onClose = { builderOpen = false },
        )
        return
    }
    AutomationList(
        vm = vm,
        ui = ui,
        appSettings = appSettings,
        settings = settings,
        wheelInput = wheelInput,
        onOpenBuilder = { builderOpen = true },
        onClose = onClose,
    )
}

@Composable
private fun AutomationList(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    appSettings: AppSettings,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenBuilder: () -> Unit,
    onClose: () -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    var broadlinkOnly by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }
    var pinTarget by remember { mutableStateOf<BroadlinkViewModel.AutomationRow?>(null) }
    val knownRemotes = remember(ui.remotes) { ui.remotes.map { it.entityId }.toSet() }
    val rows = remember(ui.automations, broadlinkOnly, query, knownRemotes) {
        ui.automations
            .filter { row ->
                !broadlinkOnly || BroadlinkCards.isBroadlinkRelated(
                    configJson = row.configBody,
                    name = row.name,
                    knownRemoteEntityIds = knownRemotes,
                )
            }
            .filter { row ->
                query.isBlank() ||
                    row.name.contains(query.trim(), ignoreCase = true) ||
                    row.entityId.contains(query.trim(), ignoreCase = true)
            }
    }
    val listState = rememberLazyListState()
    WheelScrollFor(
        wheelInput = wheelInput,
        listState = listState,
        settings = settings,
        enabled = pinTarget == null,
    )
    R1TopBar(
        title = "IR AUTOMATIONS",
        onBack = onClose,
        action = {
            R1Chip(
                text = "NEW",
                variant = R1ChipVariant.Action,
                selected = true,
                onClick = onOpenBuilder,
                contentDescription = "Create a new send-command automation",
            )
        },
    )
    com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            R1Chip(
                text = "BROADLINK",
                variant = R1ChipVariant.Filter,
                selected = broadlinkOnly,
                onClick = { broadlinkOnly = true },
                contentDescription = "Show Broadlink-related automations",
            )
            R1Chip(
                text = "ALL",
                variant = R1ChipVariant.Filter,
                selected = !broadlinkOnly,
                onClick = { broadlinkOnly = false },
                contentDescription = "Show every automation",
            )
            Box(modifier = Modifier.weight(1f)) {
                R1TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = "filter…",
                    monospace = false,
                )
            }
        }
        if (broadlinkOnly) {
            Text(
                text = if (ui.configsFetched) {
                    "UI-MANAGED RULES FILTERED BY CONFIG BODY; YAML RULES BY NAME."
                } else {
                    "FETCHING CONFIG BODIES… FILTER IS NAME-BASED UNTIL DONE."
                },
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.padding(horizontal = dimens.screenGutter),
            )
        }
        when {
            ui.automationsLoading && ui.automations.isEmpty() -> SkeletonList()
            ui.automationsError != null && ui.automations.isEmpty() -> R1ErrorState(
                title = "COULDN'T LOAD AUTOMATIONS",
                message = ui.automationsError,
                onRetry = { vm.loadAutomations() },
            )
            rows.isEmpty() -> R1EmptyState(
                title = if (broadlinkOnly) "NO BROADLINK AUTOMATIONS" else "NO AUTOMATIONS",
                body = if (broadlinkOnly) {
                    "Nothing references remote.send_command or your blasters yet. NEW creates one."
                } else {
                    "No automations matched."
                },
                actionText = "NEW",
                onAction = onOpenBuilder,
            )
            else -> LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = dimens.screenGutter,
                    vertical = R1.space.s,
                ),
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                items(rows, key = { it.entityId }) { row ->
                    AutomationRowItem(
                        row = row,
                        onToggle = { vm.setAutomationEnabled(row, !row.enabled) },
                        onRun = { vm.triggerAutomation(row) },
                        onPin = { pinTarget = row },
                    )
                }
            }
        }
    }
    val target = pinTarget
    if (target != null) {
        PagePickerDialog(
            pages = appSettings.pages,
            onPick = { pageId ->
                vm.pinAutomationToPage(pageId, target.entityId, target.name)
                pinTarget = null
            },
            onDismiss = { pinTarget = null },
        )
    }
}

@Composable
private fun AutomationRowItem(
    row: BroadlinkViewModel.AutomationRow,
    onToggle: () -> Unit,
    onRun: () -> Unit,
    onPin: () -> Unit,
) {
    val stateTint = when {
        !row.available -> R1.StatusAmber
        row.enabled -> R1.AccentGreen
        else -> R1.InkMuted
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1RowPressable(
                onTap = onToggle,
                onLongPress = onPin,
                contentDescription = "${row.name}. Tap to toggle, long press to pin",
            )
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when {
                !row.available -> "N/A"
                row.enabled -> "ON"
                else -> "OFF"
            },
            style = responsiveType(R1.labelMicro),
            color = stateTint,
            modifier = Modifier.width(R1.space.xl),
        )
        Spacer(Modifier.width(R1.space.s))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.name,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = row.entityId + if (row.configId == null) " · YAML" else "",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Box(
            modifier = Modifier
                .heightIn(min = R1.MinTarget)
                .r1Pressable(onClick = onPin, contentDescription = "Pin ${row.name} to a page"),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "PIN",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                modifier = Modifier.padding(horizontal = R1.space.s),
            )
        }
        Spacer(Modifier.width(R1.space.xs))
        if (row.available) {
            R1Chip(
                text = "RUN",
                variant = R1ChipVariant.Action,
                selected = true,
                tone = R1.AccentGreen,
                onClick = onRun,
                contentDescription = "Run ${row.name} now",
            )
        } else {
            R1Chip(text = "RUN", variant = R1ChipVariant.Pill, tone = R1.InkMuted)
        }
    }
}

// ── Builder ─────────────────────────────────────────────────────────────

private enum class TriggerKind { TIME, STATE }

/**
 * Focused creator for the common case: one trigger, one
 * remote.send_command action sourced from the catalog. Saves through
 * HA's automation config API; HA reloads automations on save.
 */
@Composable
private fun AutomationBuilder(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    haRepository: HaRepository,
    appSettings: AppSettings,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onClose: () -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    val scroll = rememberScrollState()
    var name by remember { mutableStateOf("") }
    var triggerKind by remember { mutableStateOf(TriggerKind.TIME) }
    var timeText by remember { mutableStateOf("07:30") }
    var entityId by remember { mutableStateOf("") }
    var toState by remember { mutableStateOf("") }
    var entityPickerOpen by remember { mutableStateOf(false) }
    val devices = appSettings.broadlink.devices
    var deviceIndex by remember { mutableIntStateOf(0) }
    val device = devices.getOrNull(deviceIndex)
    var commandName by remember(device?.name, device?.remoteEntityId) { mutableStateOf("") }
    var repeats by remember { mutableIntStateOf(1) }
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scroll, settings = settings)
    R1TopBar(title = "NEW AUTOMATION", onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
    ) {
        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            Column {
                BroadlinkSectionLabel("NAME")
                R1TextField(
                    value = name,
                    onValueChange = { name = it },
                    placeholder = "AC on at seven",
                    monospace = false,
                )
                BroadlinkSectionLabel("WHEN")
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "AT TIME",
                        variant = R1ChipVariant.Filter,
                        selected = triggerKind == TriggerKind.TIME,
                        onClick = { triggerKind = TriggerKind.TIME },
                        contentDescription = "Trigger at a time of day",
                    )
                    R1Chip(
                        text = "ENTITY STATE",
                        variant = R1ChipVariant.Filter,
                        selected = triggerKind == TriggerKind.STATE,
                        onClick = { triggerKind = TriggerKind.STATE },
                        contentDescription = "Trigger on an entity state change",
                    )
                }
                Spacer(Modifier.height(R1.space.s))
                when (triggerKind) {
                    TriggerKind.TIME -> {
                        R1TextField(
                            value = timeText,
                            onValueChange = { timeText = it },
                            placeholder = "HH:MM (24h)",
                        )
                        Text(
                            text = "FIRES DAILY AT THIS LOCAL TIME.",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                        )
                    }
                    TriggerKind.STATE -> {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(modifier = Modifier.weight(1f)) {
                                R1TextField(
                                    value = entityId,
                                    onValueChange = { entityId = it },
                                    placeholder = "binary_sensor.door",
                                )
                            }
                            Spacer(Modifier.width(R1.space.s))
                            R1Chip(
                                text = "PICK",
                                variant = R1ChipVariant.Action,
                                onClick = { entityPickerOpen = true },
                                contentDescription = "Pick the trigger entity",
                            )
                        }
                        Spacer(Modifier.height(R1.space.xs))
                        R1TextField(
                            value = toState,
                            onValueChange = { toState = it },
                            placeholder = "to state · empty = any change",
                        )
                    }
                }
                BroadlinkSectionLabel("THEN SEND")
                if (devices.isEmpty()) {
                    Text(
                        text = "The catalog is empty. Learn or register a command first.",
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        devices.forEachIndexed { i, d ->
                            R1Chip(
                                text = d.name.uppercase(),
                                variant = R1ChipVariant.Filter,
                                selected = i == deviceIndex,
                                onClick = { deviceIndex = i },
                                contentDescription = "Send to device ${d.name}",
                            )
                        }
                    }
                    Spacer(Modifier.height(R1.space.xs))
                    val commands = device?.commands.orEmpty()
                    if (commands.isEmpty()) {
                        Text(
                            text = "No commands catalogued for this device.",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                        ) {
                            commands.forEach { c ->
                                R1Chip(
                                    text = c.displayLabel.uppercase(),
                                    variant = R1ChipVariant.Filter,
                                    selected = commandName == c.name,
                                    onClick = { commandName = c.name },
                                    contentDescription = "Send command ${c.displayLabel}",
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(R1.space.s))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        Text(
                            text = "REPEATS",
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkMuted,
                        )
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
                }
                Spacer(Modifier.height(R1.space.xl))
                R1Chip(
                    text = if (ui.creatingAutomation) "CREATING…" else "CREATE",
                    variant = R1ChipVariant.Action,
                    selected = true,
                    tone = R1.AccentGreen,
                    onClick = create@{
                        if (ui.creatingAutomation) return@create
                        val trigger = when (triggerKind) {
                            TriggerKind.TIME -> {
                                val t = parseTimeOfDay(timeText) ?: run {
                                    Toaster.error("Time must be HH:MM (24h)")
                                    return@create
                                }
                                BroadlinkCards.Trigger.AtTime(t)
                            }
                            TriggerKind.STATE -> {
                                if (!entityId.trim().contains('.')) {
                                    Toaster.error("Trigger entity looks wrong (need domain.object)")
                                    return@create
                                }
                                BroadlinkCards.Trigger.EntityState(entityId.trim(), toState.trim())
                            }
                        }
                        val dev = device ?: run {
                            Toaster.error("Pick a device from the catalog")
                            return@create
                        }
                        if (commandName.isBlank()) {
                            Toaster.error("Pick a command")
                            return@create
                        }
                        if (name.isBlank()) {
                            Toaster.error("Name the automation")
                            return@create
                        }
                        vm.createAutomation(
                            alias = name,
                            trigger = trigger,
                            remoteEntityId = dev.remoteEntityId,
                            deviceName = dev.name,
                            commandName = commandName,
                            repeats = repeats,
                            onCreated = onClose,
                        )
                    },
                    contentDescription = "Create the automation",
                )
                Spacer(Modifier.height(R1.space.xl))
            }
        }
    }
    if (entityPickerOpen) {
        // Dialog window: this builder is a Column, so the picker's own
        // fillMaxSize overlay would otherwise get zero remaining height.
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { entityPickerOpen = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            com.github.itskenny0.r1ha.feature.settings.EntityPickerSheet(
                haRepository = haRepository,
                onPick = { picked ->
                    entityId = picked
                    entityPickerOpen = false
                },
                onDismiss = { entityPickerOpen = false },
                domains = setOf(
                    "binary_sensor", "sensor", "person", "switch", "light",
                    "input_boolean", "lock", "cover", "media_player", "climate",
                ),
            )
        }
    }
}

/** "7:30" / "07:30" / "07:30:15" -> canonical "HH:MM:SS"; null when not
 *  a valid 24h time. Internal for unit tests. */
internal fun parseTimeOfDay(raw: String): String? {
    val parts = raw.trim().split(':')
    if (parts.size !in 2..3) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    val s = if (parts.size == 3) parts[2].toIntOrNull() ?: return null else 0
    if (h !in 0..23 || m !in 0..59 || s !in 0..59) return null
    return String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, s)
}
