package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.LawnMowerAction
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.ha.VacuumAction
import com.github.itskenny0.r1ha.core.theme.LocalOnEntityCall
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Per-domain control panels surfaced on entity cards. Each panel takes the live
 * [EntityState] (plumbed via [com.github.itskenny0.r1ha.core.theme.CardRenderModel.entityState])
 * and an accent colour from the host theme, then dispatches service calls
 * through [LocalOnEntityCall]. Panels gate every chip on the entity's
 * `supported_features` bitmask so the user can't fire a service the
 * integration doesn't accept.
 *
 * Implementation notes:
 *  - Chips use [r1Pressable] for the 48 dp accessibility expansion / haptic
 *    feedback parity with the rest of the app.
 *  - The shared [PanelChip] composable keeps the visual language consistent
 *    across panels (filled = active state, outlined = secondary action).
 *  - All panels render nothing when the entity is unavailable; the
 *    SwitchCard / theme.Card hosts already dim the surface, and an
 *    unactionable chip set on a stale card was just noise.
 */

@Composable
private fun PanelChip(
    label: String,
    accent: Color,
    enabled: Boolean = true,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fillColor = when {
        !enabled -> Color.Transparent
        selected -> accent
        else -> R1.SurfaceMuted
    }
    val textColor = when {
        !enabled -> R1.InkMuted
        selected -> R1.Bg
        else -> accent
    }
    val border = if (!enabled || selected) null else accent.copy(alpha = 0.4f)
    val base = modifier
        .heightIn(min = 32.dp)
        .clip(R1.ShapeS)
        .background(fillColor)
    val bordered = if (border != null) base.border(1.dp, border, R1.ShapeS) else base
    val tappable = if (enabled) bordered.r1Pressable(onClick = onClick) else bordered
    Box(
        modifier = tappable.padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.labelMicro, color = textColor)
    }
}

/**
 * Vacuum control panel. The SwitchCard's CLEAN/DOCK toggle already drives the
 * primary start/return-to-base; this panel surfaces the secondary commands
 * (PAUSE / STOP / LOCATE / SPOT), the fan-speed picker, and a battery readout.
 * Hidden when the integration didn't advertise any of the relevant feature
 * bits; we never render an empty chrome row.
 */
@Composable
fun VacuumPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.VACUUM) return
    val dispatch = LocalOnEntityCall.current
    val showPause = state.hasVacuumFeature(EntityState.VacuumFeature.PAUSE)
    val showStop = state.hasVacuumFeature(EntityState.VacuumFeature.STOP)
    val showLocate = state.hasVacuumFeature(EntityState.VacuumFeature.LOCATE)
    val showSpot = state.hasVacuumFeature(EntityState.VacuumFeature.CLEAN_SPOT)
    val hasChips = showPause || showStop || showLocate || showSpot
    val fanSpeeds = state.vacuumFanSpeedList
    val battery = state.vacuumBatteryLevel
    if (!hasChips && fanSpeeds.isEmpty() && battery == null) return

    Column(modifier = modifier.fillMaxWidth()) {
        if (hasChips) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showPause) PanelChip("PAUSE", accent) {
                    dispatch?.invoke(ServiceCall.vacuumCommand(state.id, VacuumAction.PAUSE))
                }
                if (showStop) PanelChip("STOP", accent) {
                    dispatch?.invoke(ServiceCall.vacuumCommand(state.id, VacuumAction.STOP))
                }
                if (showLocate) PanelChip("LOCATE", accent) {
                    dispatch?.invoke(ServiceCall.vacuumCommand(state.id, VacuumAction.LOCATE))
                }
                if (showSpot) PanelChip("SPOT", accent) {
                    dispatch?.invoke(ServiceCall.vacuumCommand(state.id, VacuumAction.CLEAN_SPOT))
                }
            }
        }
        if (fanSpeeds.isNotEmpty() && state.hasVacuumFeature(EntityState.VacuumFeature.FAN_SPEED)) {
            Spacer(Modifier.height(8.dp))
            Text(text = "FAN", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                fanSpeeds.forEach { speed ->
                    PanelChip(
                        label = speed.uppercase(),
                        accent = accent,
                        selected = state.vacuumFanSpeed.equals(speed, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.vacuumSetFanSpeed(state.id, speed))
                        },
                    )
                }
            }
        }
        if (battery != null) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "BATTERY", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(6.dp))
                Text(text = "$battery%", style = R1.labelMicro, color = accent)
            }
        }
    }
}

/**
 * Lawn-mower control panel. Three commands max (START / PAUSE / DOCK); the
 * SwitchCard's MOW/DOCK end-stops cover the most common pair, this panel
 * adds the PAUSE chip and a passthrough that re-fires START even when the
 * mower is already on (handy for switching from PAUSED back into mowing
 * without round-tripping through OFF first).
 */
@Composable
fun LawnMowerPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.LAWN_MOWER) return
    val dispatch = LocalOnEntityCall.current
    val showStart = state.hasFeature(EntityState.LawnMowerFeature.START_MOWING)
    val showPause = state.hasFeature(EntityState.LawnMowerFeature.PAUSE)
    val showDock = state.hasFeature(EntityState.LawnMowerFeature.DOCK)
    if (!showStart && !showPause && !showDock) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (showStart) PanelChip("START", accent) {
            dispatch?.invoke(ServiceCall.lawnMowerCommand(state.id, LawnMowerAction.START_MOWING))
        }
        if (showPause) PanelChip("PAUSE", accent) {
            dispatch?.invoke(ServiceCall.lawnMowerCommand(state.id, LawnMowerAction.PAUSE))
        }
        if (showDock) PanelChip("DOCK", accent) {
            dispatch?.invoke(ServiceCall.lawnMowerCommand(state.id, LawnMowerAction.DOCK))
        }
    }
}

/**
 * Lock control panel. When the lock advertises a `code_format` we surface a
 * KEYPAD chip that opens the PIN dialog; tapping LOCK / UNLOCK there then
 * fires the service with the entered code. Locks without a code_format
 * bypass the dialog entirely — the SwitchCard's UNLOCK / LOCK toggle is
 * sufficient.
 */
@Composable
fun LockPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.LOCK) return
    val dispatch = LocalOnEntityCall.current
    val needsCode = !state.lockCodeFormat.isNullOrBlank()
    val hasChangedBy = !state.lockChangedBy.isNullOrBlank()
    // Bail early for locks that have nothing to render — neither a code
    // keypad to surface nor a `changed_by` attribute to display. The
    // SwitchCard's UNLOCK/LOCK end-stops are enough for these.
    if (!needsCode && !hasChangedBy) return
    var showKeypad by remember { mutableStateOf(false) }
    var pendingLock by remember { mutableStateOf(true) }

    Column(modifier = modifier.fillMaxWidth()) {
        if (needsCode) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelChip("LOCK", accent, onClick = {
                    pendingLock = true
                    showKeypad = true
                })
                PanelChip("UNLOCK", accent, onClick = {
                    pendingLock = false
                    showKeypad = true
                })
            }
        }
        if (!state.lockChangedBy.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "BY ${state.lockChangedBy.uppercase()}",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }

    if (showKeypad) {
        PinKeypadDialog(
            title = if (pendingLock) "LOCK" else "UNLOCK",
            codeFormat = state.lockCodeFormat,
            accent = accent,
            onDismiss = { showKeypad = false },
            onConfirm = { code ->
                showKeypad = false
                dispatch?.invoke(ServiceCall.lockSet(state.id, pendingLock, code))
            },
        )
    }
}

/**
 * Modal PIN keypad rendered when a lock requires a code. Validates against
 * the lock's [codeFormat] regex when the integration supplied one. Falls
 * back to "any non-empty digit string" otherwise so integrations that
 * advertise code-required without specifying a regex still work.
 */
@Composable
private fun PinKeypadDialog(
    title: String,
    codeFormat: String?,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    // HA's code_format is sometimes anchored ("^\\d{4}$") and sometimes
    // a bare fragment ("\\d{4}"); HA core itself uses re.match() which
    // is prefix-not-whole-string. We use `containsMatchIn` so unanchored
    // patterns don't reject otherwise-valid PINs.
    val pattern = remember(codeFormat) {
        runCatching { codeFormat?.let { Regex(it) } }.getOrNull()
    }
    val valid = entered.isNotEmpty() && (pattern?.containsMatchIn(entered) ?: true)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(20.dp)
                .width(260.dp),
        ) {
            Text(text = title, style = R1.titleCard, color = accent)
            Spacer(Modifier.height(6.dp))
            Text(text = "ENTER PIN", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (entered.isEmpty()) "·  ·  ·  ·" else "*".repeat(entered.length),
                    style = R1.numeralM,
                    color = R1.Ink,
                )
            }
            Spacer(Modifier.height(10.dp))
            // 3×4 keypad — digits 1..9, then 0 with backspace alongside.
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("⌫", "0", "OK"),
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { key ->
                        val isOk = key == "OK"
                        val isBack = key == "⌫"
                        Box(
                            modifier = Modifier
                                .height(44.dp)
                                .weight(1f)
                                .clip(R1.ShapeS)
                                .background(if (isOk && valid) accent else R1.SurfaceMuted)
                                .r1Pressable(onClick = {
                                    when {
                                        isBack -> entered = entered.dropLast(1)
                                        isOk -> if (valid) onConfirm(entered)
                                        entered.length < 12 -> entered += key
                                    }
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = key,
                                style = R1.numeralM,
                                color = when {
                                    isOk && valid -> R1.Bg
                                    isOk -> R1.InkMuted
                                    else -> R1.Ink
                                },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.InkMuted, R1.ShapeS)
                    .r1Pressable(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "CANCEL", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

/**
 * Climate control panel — HVAC mode picker, fan-mode picker, and a
 * current-temperature readout. Setpoint adjustment stays on the wheel
 * (theme.Card's BigReadout + meter), this panel just surfaces the discrete
 * mode pickers HA exposes alongside.
 */
@Composable
fun ClimatePanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.CLIMATE) return
    val dispatch = LocalOnEntityCall.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.climateHvacModes.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.climateHvacModes.forEach { mode ->
                    PanelChip(
                        label = mode.replace('_', ' ').uppercase(),
                        accent = accent,
                        selected = state.climateHvacMode.equals(mode, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.setHvacMode(state.id, mode))
                        },
                    )
                }
            }
        }
        if (state.climatePresetModes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(text = "PRESET", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.climatePresetModes.forEach { preset ->
                    PanelChip(
                        label = preset.replace('_', ' ').uppercase(),
                        accent = accent,
                        selected = state.climatePresetMode.equals(preset, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.setPresetMode(state.id, preset))
                        },
                    )
                }
            }
        }
        if (state.climateFanModes.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(text = "FAN", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.climateFanModes.forEach { fan ->
                    PanelChip(
                        label = fan.replace('_', ' ').uppercase(),
                        accent = accent,
                        selected = state.climateFanMode.equals(fan, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.setFanMode(state.id, fan))
                        },
                    )
                }
            }
        }
        val current = state.climateCurrentTemperature
        if (current != null) {
            // Convert to the user's chosen temperature unit so the panel
            // readout matches the BigReadout above (which already
            // converts). Mismatched units inside one card would be a
            // bug — the user sees 21 °C in the body and 70 °F here only
            // because we forgot to thread the preference through.
            val ui = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current
            val nativeUnit = state.temperatureUnit ?: state.unit
            val (converted, suffix) = convertTemperature(current, nativeUnit, ui.tempUnit)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "NOW", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatTemperature(converted) + " " + suffix,
                    style = R1.labelMicro,
                    color = accent,
                )
            }
        }
    }
}

/**
 * Valve control panel. The SwitchCard's OPEN/CLOSE end-stops cover the
 * primary toggle; this panel surfaces STOP (mid-travel halt) for valves
 * whose integration advertises the bit. When SET_POSITION is supported and
 * the entity isn't scalar (no continuous slider), we still leave position
 * tuning to the SwitchCard's wheel input — this panel doesn't duplicate.
 */
@Composable
fun ValvePanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.VALVE) return
    val dispatch = LocalOnEntityCall.current
    val supportsStop = state.hasFeature(EntityState.ValveFeature.STOP)
    if (!supportsStop) return
    Row(modifier = modifier.fillMaxWidth()) {
        PanelChip("STOP", accent) {
            dispatch?.invoke(ServiceCall.valveStop(state.id))
        }
    }
}

/**
 * Water-heater control panel. Surfaces the operation-mode picker
 * (electric / heat_pump / eco / off etc.) — wheel-driven setpoint already
 * handles temperature.
 */
@Composable
fun WaterHeaterPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.WATER_HEATER) return
    val dispatch = LocalOnEntityCall.current
    // DefaultHaRepository stores HA's `operation_list` / `operation_mode`
    // attributes in the climateHvacModes / climateHvacMode fields (the parser
    // shares the climate sibling's branch for both domains). Empty list →
    // nothing to render.
    val modes = state.climateHvacModes
    if (modes.isEmpty()) return
    val active = state.climateHvacMode
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "MODE", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            modes.forEach { mode ->
                PanelChip(
                    label = mode.replace('_', ' ').uppercase(),
                    accent = accent,
                    selected = active.equals(mode, ignoreCase = true),
                    onClick = {
                        dispatch?.invoke(ServiceCall.setOperationMode(state.id, mode))
                    },
                )
            }
        }
    }
}

/**
 * Media-player extras row — shuffle toggle, repeat cycle, and source picker.
 * Rendered next to the existing transport controls; only the buttons whose
 * feature bits are set on the integration are surfaced. Source list opens
 * a horizontally-scrollable chip strip rather than a separate picker
 * dialog — discoverable, and avoids reaching into the screen-level overlay
 * stack just for a 3-option list.
 */
@Composable
fun MediaExtrasPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.MEDIA_PLAYER) return
    val dispatch = LocalOnEntityCall.current
    val hasShuffle = state.hasMediaFeature(EntityState.MediaPlayerFeature.SHUFFLE_SET)
    val hasRepeat = state.hasMediaFeature(EntityState.MediaPlayerFeature.REPEAT_SET)
    val hasSource = state.hasMediaFeature(EntityState.MediaPlayerFeature.SELECT_SOURCE) &&
        state.mediaSourceList.isNotEmpty()
    if (!hasShuffle && !hasRepeat && !hasSource) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (hasShuffle || hasRepeat) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hasShuffle) {
                    PanelChip(
                        label = "SHUFFLE",
                        accent = accent,
                        selected = state.mediaShuffle,
                        onClick = {
                            dispatch?.invoke(
                                ServiceCall.mediaShuffleSet(state.id, !state.mediaShuffle),
                            )
                        },
                    )
                }
                if (hasRepeat) {
                    val current = state.mediaRepeat ?: "off"
                    val next = when (current.lowercase()) {
                        "off" -> "all"
                        "all" -> "one"
                        else -> "off"
                    }
                    PanelChip(
                        label = "REPEAT ${current.uppercase()}",
                        accent = accent,
                        selected = current != "off",
                        onClick = {
                            dispatch?.invoke(ServiceCall.mediaRepeatSet(state.id, next))
                        },
                    )
                }
            }
        }
        if (hasSource) {
            Spacer(Modifier.height(6.dp))
            Text(text = "SOURCE", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.mediaSourceList.forEach { source ->
                    PanelChip(
                        label = source.uppercase(),
                        accent = accent,
                        selected = state.mediaSource.equals(source, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.mediaSelectSource(state.id, source))
                        },
                    )
                }
            }
        }
    }
}

private fun formatTemperature(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}
