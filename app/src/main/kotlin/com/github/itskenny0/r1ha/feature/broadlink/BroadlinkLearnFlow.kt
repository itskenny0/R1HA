package com.github.itskenny0.r1ha.feature.broadlink

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import java.util.Locale

/**
 * Guided capture stepper. One screen, four phases driven by
 * [BroadlinkViewModel.LearnState]:
 *
 *  FORM      -> blaster / device / command / type slots + START CAPTURE
 *  CAPTURING -> the broadcast-console moment: pulsing emitter, press-now
 *               instructions (two-step dance for RF), elapsed readout,
 *               CANCEL
 *  CAPTURED  -> signature trace + TEST FIRE / SAVE / LEARN ANOTHER
 *  FAILED    -> the error verbatim + RETRY
 */
@Composable
internal fun BroadlinkLearnFlow(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onClose: () -> Unit,
) {
    val learn = ui.learn
    // Default the blaster slot to the catalog's current selection once.
    LaunchedEffect(Unit) {
        if (learn.remoteEntityId.isBlank() && ui.selectedRemote.isNotBlank()) {
            vm.updateLearnForm { it.copy(remoteEntityId = ui.selectedRemote) }
        }
    }
    when (learn.phase) {
        BroadlinkViewModel.LearnPhase.FORM -> LearnForm(
            vm = vm,
            ui = ui,
            settings = settings,
            wheelInput = wheelInput,
            onClose = onClose,
        )
        BroadlinkViewModel.LearnPhase.CAPTURING -> CapturePhase(vm = vm, learn = learn)
        BroadlinkViewModel.LearnPhase.CAPTURED -> CapturedPhase(
            vm = vm,
            ui = ui,
            onClose = onClose,
        )
        BroadlinkViewModel.LearnPhase.FAILED -> FailedPhase(vm = vm, learn = learn, onClose = onClose)
    }
}

@Composable
private fun LearnForm(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onClose: () -> Unit,
) {
    val learn = ui.learn
    val dimens = rememberResponsiveDimens()
    val scroll = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scroll, settings = settings)
    val existingDevices = remember(ui.catalog, learn.remoteEntityId) {
        BroadlinkCatalog.deviceNamesFor(ui.catalog, learn.remoteEntityId)
    }
    R1TopBar(title = "LEARN COMMAND", onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
    ) {
        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            Column {
                BroadlinkSectionLabel("BLASTER")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    ui.remotes.forEach { remote ->
                        R1Chip(
                            text = remote.name.uppercase(),
                            variant = R1ChipVariant.Filter,
                            selected = remote.entityId == learn.remoteEntityId,
                            onClick = {
                                vm.updateLearnForm { it.copy(remoteEntityId = remote.entityId) }
                            },
                            contentDescription = "Learn via ${remote.name}",
                        )
                    }
                }
                BroadlinkSectionLabel("DEVICE")
                R1TextField(
                    value = learn.deviceName,
                    onValueChange = { v -> vm.updateLearnForm { it.copy(deviceName = v) } },
                    placeholder = "tv, amp, fan…",
                    monospace = false,
                )
                if (existingDevices.isNotEmpty()) {
                    Spacer(Modifier.height(R1.space.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        existingDevices.forEach { name ->
                            R1Chip(
                                text = name.uppercase(),
                                variant = R1ChipVariant.Filter,
                                selected = learn.deviceName == name,
                                onClick = { vm.updateLearnForm { it.copy(deviceName = name) } },
                                contentDescription = "Use existing device $name",
                            )
                        }
                    }
                }
                BroadlinkSectionLabel("COMMAND")
                R1TextField(
                    value = learn.commandName,
                    onValueChange = { v -> vm.updateLearnForm { it.copy(commandName = v) } },
                    placeholder = "power, vol_up, mute…",
                    monospace = false,
                )
                BroadlinkSectionLabel("SIGNAL")
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "IR · 38 KHZ",
                        variant = R1ChipVariant.Filter,
                        selected = learn.type == "ir",
                        onClick = { vm.updateLearnForm { it.copy(type = "ir") } },
                        contentDescription = "Infrared capture",
                    )
                    R1Chip(
                        text = "RF · 433 MHZ",
                        variant = R1ChipVariant.Filter,
                        selected = learn.type == "rf",
                        tone = R1.AccentCool,
                        onClick = { vm.updateLearnForm { it.copy(type = "rf") } },
                        contentDescription = "Radio frequency capture",
                    )
                    R1Chip(
                        text = "ALT",
                        variant = R1ChipVariant.Filter,
                        selected = learn.alternative,
                        tone = R1.AccentNeutral,
                        onClick = { vm.updateLearnForm { it.copy(alternative = !it.alternative) } },
                        contentDescription = "Toggle alternating-code capture",
                    )
                }
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = if (learn.type == "rf") {
                        "RF captures in two steps: hold the button through the frequency sweep, then press it once more when prompted."
                    } else {
                        "Point the remote at the blaster; one button press captures the code."
                    } + if (learn.alternative) " ALT: for remotes that alternate two codes per button." else "",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(R1.space.xl))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(R1.Bg),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    R1Chip(
                        text = "START CAPTURE",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        onClick = { vm.startCapture() },
                        contentDescription = "Start capturing the command",
                    )
                }
                Spacer(Modifier.height(R1.space.xl))
            }
        }
    }
}

@Composable
private fun CapturePhase(vm: BroadlinkViewModel, learn: BroadlinkViewModel.LearnState) {
    // Elapsed-seconds readout; ticks locally, anchored at the dispatch.
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(learn.startedAtMillis) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            kotlinx.coroutines.delay(250L)
        }
    }
    val elapsed = ((nowMillis - learn.startedAtMillis) / 1000L).coerceAtLeast(0L)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(R1.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (learn.type == "rf") "RF CAPTURE · 433.92 MHZ" else "IR CAPTURE · 38.0 KHZ",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(R1.space.l))
        CaptureIndicator()
        Spacer(Modifier.height(R1.space.l))
        Text(
            text = "'${learn.commandName.uppercase()}'",
            style = responsiveType(R1.screenTitle),
            color = R1.Ink,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(R1.space.s))
        Text(
            text = if (learn.type == "rf") {
                "LED ON: HOLD THE BUTTON TO SWEEP.\nLED BLINKS AGAIN: PRESS ONCE MORE."
            } else {
                "LED ON: POINT THE REMOTE AT THE BLASTER\nAND PRESS THE BUTTON NOW."
            },
            style = responsiveType(R1.labelMicro),
            color = R1.AccentWarm,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(R1.space.l))
        Text(
            text = String.format(Locale.US, "T+%02d:%02d", elapsed / 60, elapsed % 60),
            style = responsiveType(R1.numeralM),
            color = R1.InkSoft,
        )
        Spacer(Modifier.height(R1.space.xl))
        R1Chip(
            text = "CANCEL",
            variant = R1ChipVariant.Action,
            onClick = { vm.cancelCapture() },
            contentDescription = "Stop waiting for the capture",
        )
    }
}

@Composable
private fun CapturedPhase(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    onClose: () -> Unit,
) {
    val learn = ui.learn
    val firing = BroadlinkViewModel.firingKey(learn.deviceName.trim(), learn.commandName.trim()) in ui.firing
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(R1.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = "CODE CAPTURED",
                    style = responsiveType(R1.sectionHeader),
                    color = R1.AccentGreen,
                )
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = "${learn.deviceName.trim()} / ${learn.commandName.trim()} · stored on HA",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(R1.space.l))
                SignatureTrace(
                    deviceName = learn.deviceName.trim(),
                    commandName = learn.commandName.trim(),
                    type = learn.type,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(R1.space.l))
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = if (firing) "TX…" else "TEST FIRE",
                        variant = R1ChipVariant.Action,
                        onClick = { if (!firing) vm.testFireLearned() },
                        contentDescription = "Test fire the captured command",
                    )
                    R1Chip(
                        // Saving now creates the tagged automation on HA,
                        // so it has an in-flight state worth showing.
                        text = when {
                            learn.saved -> "SAVED ✓"
                            ui.savingCommand -> "SAVING…"
                            else -> "SAVE TO CATALOG"
                        },
                        variant = R1ChipVariant.Action,
                        selected = !learn.saved,
                        tone = R1.AccentGreen,
                        onClick = { vm.saveLearned() },
                        contentDescription = "Save the command to the catalog",
                    )
                }
                Spacer(Modifier.height(R1.space.m))
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "LEARN ANOTHER",
                        variant = R1ChipVariant.Action,
                        onClick = { vm.learnAnother() },
                        contentDescription = "Learn another command into the same device",
                    )
                    R1Chip(
                        text = "DONE",
                        variant = R1ChipVariant.Action,
                        onClick = {
                            if (!learn.saved) vm.saveLearned()
                            onClose()
                        },
                        contentDescription = "Finish learning",
                    )
                }
                if (!learn.saved) {
                    Spacer(Modifier.height(R1.space.s))
                    Text(
                        text = "DONE ALSO SAVES. THE CODE ALREADY LIVES ON HA EITHER WAY.",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun FailedPhase(
    vm: BroadlinkViewModel,
    learn: BroadlinkViewModel.LearnState,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(R1.space.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "CAPTURE FAILED",
            style = responsiveType(R1.sectionHeader),
            color = R1.StatusRed,
        )
        Spacer(Modifier.height(R1.space.s))
        Text(
            text = learn.error ?: "No code arrived before the timeout.",
            style = responsiveType(R1.body),
            color = R1.InkSoft,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 480.dp),
        )
        Spacer(Modifier.height(R1.space.xs))
        Text(
            text = "TIMEOUTS USUALLY MEAN NO BUTTON PRESS REACHED THE BLASTER.",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(R1.space.l))
        Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
            R1Chip(
                text = "RETRY",
                variant = R1ChipVariant.Action,
                selected = true,
                onClick = { vm.retryCapture() },
                contentDescription = "Back to the capture form",
            )
            R1Chip(
                text = "CLOSE",
                variant = R1ChipVariant.Action,
                onClick = onClose,
                contentDescription = "Leave the learn flow",
            )
        }
    }
}

// ── Register existing ───────────────────────────────────────────────────

/**
 * Catalog a code that was learned outside the app (HA dev tools, another
 * frontend). Names must match HA's stored device + command exactly; the
 * TEST button fires send_command so the user can verify before saving.
 */
@Composable
internal fun BroadlinkRegisterForm(
    vm: BroadlinkViewModel,
    ui: BroadlinkViewModel.UiState,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onClose: () -> Unit,
) {
    val dimens = rememberResponsiveDimens()
    val scroll = rememberScrollState()
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scroll, settings = settings)
    var remote by remember { mutableStateOf(ui.selectedRemote) }
    var device by remember { mutableStateOf("") }
    var command by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("ir") }
    var notes by remember { mutableStateOf("") }
    val existingDevices = remember(ui.catalog, remote) {
        BroadlinkCatalog.deviceNamesFor(ui.catalog, remote)
    }
    val complete = remote.isNotBlank() && device.isNotBlank() && command.isNotBlank()
    R1TopBar(title = "REGISTER EXISTING", onBack = onClose)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
            .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
    ) {
        Box(modifier = Modifier.widthIn(max = 560.dp)) {
            Column {
                Text(
                    text = "NAMES MUST MATCH WHAT HA HAS STORED. TEST BEFORE SAVING.",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
                BroadlinkSectionLabel("BLASTER")
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    ui.remotes.forEach { r ->
                        R1Chip(
                            text = r.name.uppercase(),
                            variant = R1ChipVariant.Filter,
                            selected = r.entityId == remote,
                            onClick = { remote = r.entityId },
                            contentDescription = "Register under ${r.name}",
                        )
                    }
                }
                BroadlinkSectionLabel("DEVICE")
                R1TextField(
                    value = device,
                    onValueChange = { device = it },
                    placeholder = "exact HA device name",
                    monospace = false,
                )
                if (existingDevices.isNotEmpty()) {
                    Spacer(Modifier.height(R1.space.xs))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        existingDevices.forEach { name ->
                            R1Chip(
                                text = name.uppercase(),
                                variant = R1ChipVariant.Filter,
                                selected = device == name,
                                onClick = { device = name },
                                contentDescription = "Use existing device $name",
                            )
                        }
                    }
                }
                BroadlinkSectionLabel("COMMAND")
                R1TextField(
                    value = command,
                    onValueChange = { command = it },
                    placeholder = "exact HA command name",
                    monospace = false,
                )
                BroadlinkSectionLabel("SIGNAL")
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "IR",
                        variant = R1ChipVariant.Filter,
                        selected = type == "ir",
                        onClick = { type = "ir" },
                        contentDescription = "Infrared",
                    )
                    R1Chip(
                        text = "RF",
                        variant = R1ChipVariant.Filter,
                        selected = type == "rf",
                        tone = R1.AccentCool,
                        onClick = { type = "rf" },
                        contentDescription = "Radio frequency",
                    )
                }
                BroadlinkSectionLabel("NOTES")
                R1TextField(
                    value = notes,
                    onValueChange = { notes = it },
                    placeholder = "optional",
                    monospace = false,
                )
                Spacer(Modifier.height(R1.space.xl))
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "TEST",
                        variant = R1ChipVariant.Action,
                        onClick = {
                            if (complete) {
                                vm.testFire(remote, device.trim(), command.trim())
                            } else {
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "Blaster, device and command are required",
                                )
                            }
                        },
                        contentDescription = "Test fire before saving",
                    )
                    R1Chip(
                        text = "SAVE",
                        variant = R1ChipVariant.Action,
                        selected = complete,
                        tone = R1.AccentGreen,
                        onClick = {
                            if (complete) {
                                vm.registerExisting(remote, device, command, type, notes)
                                onClose()
                            } else {
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "Blaster, device and command are required",
                                )
                            }
                        },
                        contentDescription = "Save to the catalog",
                    )
                }
                Spacer(Modifier.height(R1.space.xl))
            }
        }
    }
}
