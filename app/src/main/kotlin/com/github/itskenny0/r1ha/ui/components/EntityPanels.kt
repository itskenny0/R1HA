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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import com.github.itskenny0.r1ha.core.ha.AlarmAction
import com.github.itskenny0.r1ha.core.util.optionLabel
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
                        label = speed.uppercase(java.util.Locale.US),
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
                // Low charge reads amber / red so a vacuum that needs docking stands out,
                // matching the battery-sensor severity colours.
                Text(
                    text = "$battery%",
                    style = R1.labelMicro,
                    color = batteryLevelColor(battery.toDouble()) ?: accent,
                )
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
    val overrides = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current
    val override = overrides[state.id.value]
    val needsServerCode = !state.lockCodeFormat.isNullOrBlank()
    val needsClientPin = override?.requirePinToUnlock == true
    val needsCode = needsServerCode || needsClientPin
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
                text = "BY ${state.lockChangedBy.uppercase(java.util.Locale.US)}",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }

    if (showKeypad) {
        // Client-side PIN check when the gate is configured locally. Skip the
        // server-code path because the lock doesn't accept one anyway — sending
        // would just earn a HA validation toast. When [requirePinHash] is set
        // we compare the entered digits against the stored SHA-256 before
        // firing; when it's null we accept any non-empty entry as the
        // 'deliberate gesture' that the gate provides.
        val expectedHash = override?.requirePinHash
        PinKeypadDialog(
            title = if (pendingLock) "LOCK" else "UNLOCK",
            // For client-side gates with a stored hash, validate against
            // any-digit-sequence at typing time; the actual hash comparison
            // happens on OK so the keypad reflects "any PIN you've entered
            // could be the right one".
            codeFormat = if (needsClientPin) null else state.lockCodeFormat,
            accent = accent,
            onDismiss = { showKeypad = false },
            onConfirm = { code ->
                if (needsClientPin && !needsServerCode) {
                    val ok = if (expectedHash.isNullOrBlank()) code.isNotEmpty()
                             else sha256Hex(code).equals(expectedHash, ignoreCase = true)
                    if (!ok) {
                        com.github.itskenny0.r1ha.core.util.Toaster.error("Wrong PIN")
                        return@PinKeypadDialog
                    }
                    showKeypad = false
                    dispatch?.invoke(ServiceCall.lockSet(state.id, pendingLock, code = null))
                } else {
                    showKeypad = false
                    dispatch?.invoke(ServiceCall.lockSet(state.id, pendingLock, code))
                }
            },
        )
    }
}

/** SHA-256 of [input] as lowercase hex. Used by the client-side lock PIN gate
 *  so the user's PIN never sits in plaintext on disk; the keypad rehashes the
 *  entered digits and compares against the stored hex. */
internal fun sha256Hex(input: String): String {
    val md = java.security.MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(input.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
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
                        label = optionLabel(mode),
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
                        label = optionLabel(preset),
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
                        label = optionLabel(fan),
                        accent = accent,
                        selected = state.climateFanMode.equals(fan, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.setFanMode(state.id, fan))
                        },
                    )
                }
            }
        }
        // Swing mode — HA's more-info-climate surfaces `swing_modes` whenever the
        // thermostat advertises the SWING_MODE feature. The repository doesn't parse
        // swing into a typed field, so we read `swing_modes` / `swing_mode` from the
        // raw attribute map (same approach the cover/humidifier panels use) and fire
        // climate.set_swing_mode directly.
        val swingModes = state.attrStringList("swing_modes")
        if (swingModes.isNotEmpty()) {
            val currentSwing = state.attrString("swing_mode")
            Spacer(Modifier.height(8.dp))
            Text(text = "SWING", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                swingModes.forEach { swing ->
                    PanelChip(
                        label = optionLabel(swing),
                        accent = accent,
                        selected = currentSwing.equals(swing, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(
                                ServiceCall(
                                    state.id,
                                    "set_swing_mode",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "swing_mode",
                                            kotlinx.serialization.json.JsonPrimitive(swing),
                                        )
                                    },
                                ),
                            )
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
                // What the equipment is actively doing right now (HEATING / IDLE / etc.),
                // distinct from the mode: answers "is the boiler running?" at a glance. The
                // colour reinforces it: accent when actively running, muted when idle / off.
                hvacActionLabel(state.climateHvacAction)?.let { action ->
                    Spacer(Modifier.width(6.dp))
                    Text(text = "·", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = action,
                        style = R1.labelMicro,
                        color = if (hvacActionIsActive(state.climateHvacAction)) accent else R1.InkMuted,
                    )
                }
            }
        }
    }
}

/**
 * Fan control panel — gives the HA web-UI's preset-mode picker, oscillate
 * toggle, and direction toggle to fans on the card stack. The percentage
 * setpoint is still wheel-driven via the SensorCard meter that sits above
 * this panel; this composable only adds the discrete controls HA exposes
 * alongside `set_percentage`.
 *
 * Each chip is gated on the corresponding bit of `supported_features`. A
 * fan that advertises SET_SPEED only (no preset / direction / oscillate)
 * renders nothing here, matching the existing meter-only experience.
 */
@Composable
fun FanPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.FAN) return
    val dispatch = LocalOnEntityCall.current
    val hasPreset = state.fanPresetModes.isNotEmpty() &&
        state.hasFanFeature(EntityState.FanFeature.PRESET_MODE)
    val hasOscillate = state.fanOscillating != null ||
        state.hasFanFeature(EntityState.FanFeature.OSCILLATE)
    val hasDirection = state.fanDirection != null ||
        state.hasFanFeature(EntityState.FanFeature.DIRECTION)
    if (!hasPreset && !hasOscillate && !hasDirection) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (hasPreset) {
            // Single button that opens a fullscreen preset picker instead of a
            // horizontally-scrolling chip row. The chip row competed with the
            // card stack's left/right tab-swipe gesture; the picker sheet is
            // the same pattern the light FX button uses (LocalOnOpenEffectPicker)
            // so users only learn one popup shape.
            val openPicker = com.github.itskenny0.r1ha.core.theme.LocalOnOpenFanPresetPicker.current
            val currentLabel = state.fanPresetMode?.let { optionLabel(it) } ?: "—"
            Text(text = "PRESET", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelChip(
                    label = currentLabel,
                    accent = accent,
                    selected = state.fanPresetMode != null,
                    onClick = { openPicker?.invoke(state.id) },
                )
            }
        }
        if (hasOscillate) {
            if (hasPreset) Spacer(Modifier.height(8.dp))
            val on = state.fanOscillating == true
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelChip(
                    label = if (on) "OSCILLATING" else "OSCILLATE",
                    accent = accent,
                    selected = on,
                    onClick = {
                        dispatch?.invoke(ServiceCall.fanOscillate(state.id, !on))
                    },
                )
            }
        }
        if (hasDirection) {
            if (hasPreset || hasOscillate) Spacer(Modifier.height(8.dp))
            Text(text = "DIRECTION", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            // Two explicit chips rather than a single toggle: matches HA web
            // dashboards that wire one fan.set_direction button per direction
            // (forward / reverse), so a tap is always unambiguous about which
            // way the fan ends up spinning even if the current attribute is
            // stale.
            val current = state.fanDirection?.lowercase()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PanelChip(
                    label = "FORWARD",
                    accent = accent,
                    selected = current == "forward",
                    onClick = {
                        dispatch?.invoke(ServiceCall.fanSetDirection(state.id, "forward"))
                    },
                )
                PanelChip(
                    label = "REVERSE",
                    accent = accent,
                    selected = current == "reverse",
                    onClick = {
                        dispatch?.invoke(ServiceCall.fanSetDirection(state.id, "reverse"))
                    },
                )
            }
        }
    }
}

/**
 * Remote / IR blaster panel. Two shapes depending on what HA's integration
 * exposes:
 *  - Activity-based remotes (Harmony Hub, ESPHome IR with activities) — render
 *    one chip per `activity_list` entry; tap fires `remote.turn_on` with the
 *    activity name. Current activity highlights.
 *  - Learned-command blasters (Broadlink RM Mini, Xiaomi IR) — HA doesn't
 *    expose learned commands as state attributes, so we render a hint pointing
 *    the user at the per-card custom buttons feature (long-press card →
 *    CUSTOMIZE → CUSTOM BUTTONS). Each chip becomes a `remote.send_command`.
 */
@Composable
fun RemotePanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.REMOTE) return
    val dispatch = LocalOnEntityCall.current
    Column(modifier = modifier.fillMaxWidth()) {
        if (state.remoteActivityList.isNotEmpty()) {
            Text(text = "ACTIVITY", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                state.remoteActivityList.forEach { activity ->
                    PanelChip(
                        label = activity.uppercase(java.util.Locale.US),
                        accent = accent,
                        selected = state.remoteCurrentActivity.equals(activity, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.remoteActivate(state.id, activity))
                        },
                    )
                }
            }
        } else {
            // No activity list — likely a learned-command blaster. Surface the
            // discovery hint once below the card so users find the custom
            // buttons path rather than assuming the app can't drive IR at all.
            Text(
                text = "Add IR commands via long-press → CUSTOMIZE → CUSTOM BUTTONS. Service: remote.send_command, data: {\"command\":\"<learned name>\"}.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

/**
 * Per-card user-defined action buttons. Reads the entity's [EntityOverride.customActions]
 * from the CompositionLocal map and renders one chip per entry; tap fires the configured
 * service via [LocalOnCustomServiceCall].
 *
 * Lives below the per-domain panel so vendor-specific or one-off actions (e.g.
 * `xiaomi_miio_fan.fan_set_natural_mode_on` on a fan card) sit next to the standard
 * controls without polluting the per-domain panel's gating logic — every fan card
 * shows the same standard chips; only fans the user has configured custom actions
 * for show extras here.
 */
@Composable
fun CustomActionsPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    val overrides = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current
    val actions = overrides[state.id.value]?.customActions.orEmpty()
    if (actions.isEmpty()) return
    val dispatch = com.github.itskenny0.r1ha.core.theme.LocalOnCustomServiceCall.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "CUSTOM", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(4.dp))
        // Wrap chips into a flow row so a deck with five or six custom actions
        // doesn't push the layout off-screen. Fall back to a Row with horizontal
        // scroll on older Compose versions — Compose 1.7+ ships FlowRow in the
        // foundation.layout package which is what we use here.
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            actions.forEach { action ->
                PanelChip(
                    label = action.label.uppercase(java.util.Locale.US),
                    accent = accent,
                    selected = false,
                    onClick = {
                        val (domain, service) = parseDottedService(action.service) ?: return@PanelChip
                        // Merge entity_id into the data payload — required by HA
                        // for the vast majority of services. If the action
                        // already specifies entity_id in its data, the explicit
                        // value wins (overwrite semantics).
                        val targetId = action.targetEntityId?.takeIf { it.isNotBlank() }
                            ?: state.id.value
                        val baseData = action.dataJson
                            ?.takeIf { it.isNotBlank() }
                            ?.let { runCatching {
                                kotlinx.serialization.json.Json.parseToJsonElement(it)
                                    as? kotlinx.serialization.json.JsonObject
                            }.getOrNull() }
                            ?: kotlinx.serialization.json.JsonObject(emptyMap())
                        val data = kotlinx.serialization.json.buildJsonObject {
                            put("entity_id", kotlinx.serialization.json.JsonPrimitive(targetId))
                            baseData.forEach { (k, v) -> put(k, v) }
                        }
                        dispatch?.invoke(domain, service, data)
                    },
                )
            }
        }
    }
}

/** Split "domain.service" into the pair, or null if the input is malformed
 *  (no dot, empty halves). Used by the custom-action chip dispatcher so a
 *  user typo doesn't crash; the chip just no-ops until they fix it. */
private fun parseDottedService(dotted: String): Pair<String, String>? {
    val idx = dotted.indexOf('.')
    if (idx <= 0 || idx == dotted.length - 1) return null
    return dotted.substring(0, idx) to dotted.substring(idx + 1)
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
    val favoritesEmpty = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current[state.id.value]?.favoritePositions.orEmpty().isEmpty()
    if (!supportsStop && favoritesEmpty) return
    Column(modifier = modifier.fillMaxWidth()) {
        if (supportsStop) {
            Row(modifier = Modifier.fillMaxWidth()) {
                PanelChip("STOP", accent) {
                    dispatch?.invoke(ServiceCall.valveStop(state.id))
                }
            }
        }
        FavoritePositionChips(state, accent) { pos ->
            dispatch?.invoke(ServiceCall.valveSetPosition(state.id, pos))
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
                    label = optionLabel(mode),
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
 * Alarm control panel — surfaces DISARM plus one chip per arm mode the
 * integration advertises (AWAY / HOME / NIGHT / VACATION / BYPASS), gated on
 * [EntityState.AlarmFeature]. Every chip opens the PIN keypad when the
 * integration sets `code_format`; arm chips skip the keypad when
 * `code_arm_required` is false. The currently-active arm state highlights as
 * selected so the user can tell at a glance which mode the panel is in.
 */
@Composable
fun AlarmPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.ALARM_CONTROL_PANEL) return
    val dispatch = LocalOnEntityCall.current
    val codeRequired = !state.alarmCodeFormat.isNullOrBlank()
    val armNeedsCode = codeRequired && state.alarmCodeArmRequired

    var pendingAction by remember { mutableStateOf<AlarmAction?>(null) }
    val raw = state.rawState?.lowercase().orEmpty()
    val selectedFor: (AlarmAction) -> Boolean = { action ->
        when (action) {
            AlarmAction.DISARM -> raw == "disarmed"
            AlarmAction.ARM_AWAY -> raw == "armed_away"
            AlarmAction.ARM_HOME -> raw == "armed_home"
            AlarmAction.ARM_NIGHT -> raw == "armed_night"
            AlarmAction.ARM_VACATION -> raw == "armed_vacation"
            AlarmAction.ARM_CUSTOM_BYPASS -> raw == "armed_custom_bypass"
            AlarmAction.TRIGGER -> raw == "triggered"
        }
    }
    val fire: (AlarmAction) -> Unit = { action ->
        val needCode = when (action) {
            AlarmAction.DISARM -> codeRequired
            AlarmAction.TRIGGER -> false
            else -> armNeedsCode
        }
        if (needCode) {
            pendingAction = action
        } else {
            dispatch?.invoke(ServiceCall.alarmAction(state.id, action, code = null))
        }
    }

    val showAway = state.hasAlarmFeature(EntityState.AlarmFeature.ARM_AWAY)
    val showHome = state.hasAlarmFeature(EntityState.AlarmFeature.ARM_HOME)
    val showNight = state.hasAlarmFeature(EntityState.AlarmFeature.ARM_NIGHT)
    val showVacation = state.hasAlarmFeature(EntityState.AlarmFeature.ARM_VACATION)
    val showBypass = state.hasAlarmFeature(EntityState.AlarmFeature.ARM_CUSTOM_BYPASS)

    Column(modifier = modifier.fillMaxWidth()) {
        Text(text = "MODE", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            PanelChip(
                label = "DISARM",
                accent = accent,
                selected = selectedFor(AlarmAction.DISARM),
                onClick = { fire(AlarmAction.DISARM) },
            )
            if (showAway) PanelChip(
                label = "AWAY",
                accent = accent,
                selected = selectedFor(AlarmAction.ARM_AWAY),
                onClick = { fire(AlarmAction.ARM_AWAY) },
            )
            if (showHome) PanelChip(
                label = "HOME",
                accent = accent,
                selected = selectedFor(AlarmAction.ARM_HOME),
                onClick = { fire(AlarmAction.ARM_HOME) },
            )
            if (showNight) PanelChip(
                label = "NIGHT",
                accent = accent,
                selected = selectedFor(AlarmAction.ARM_NIGHT),
                onClick = { fire(AlarmAction.ARM_NIGHT) },
            )
            if (showVacation) PanelChip(
                label = "VACATION",
                accent = accent,
                selected = selectedFor(AlarmAction.ARM_VACATION),
                onClick = { fire(AlarmAction.ARM_VACATION) },
            )
            if (showBypass) PanelChip(
                label = "BYPASS",
                accent = accent,
                selected = selectedFor(AlarmAction.ARM_CUSTOM_BYPASS),
                onClick = { fire(AlarmAction.ARM_CUSTOM_BYPASS) },
            )
        }
        if (!state.alarmChangedBy.isNullOrBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = "BY ${state.alarmChangedBy.uppercase(java.util.Locale.US)}",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }

    val pending = pendingAction
    if (pending != null) {
        PinKeypadDialog(
            title = when (pending) {
                AlarmAction.DISARM -> "DISARM"
                AlarmAction.ARM_AWAY -> "ARM AWAY"
                AlarmAction.ARM_HOME -> "ARM HOME"
                AlarmAction.ARM_NIGHT -> "ARM NIGHT"
                AlarmAction.ARM_VACATION -> "ARM VACATION"
                AlarmAction.ARM_CUSTOM_BYPASS -> "ARM BYPASS"
                AlarmAction.TRIGGER -> "TRIGGER"
            },
            codeFormat = state.alarmCodeFormat,
            accent = accent,
            onDismiss = { pendingAction = null },
            onConfirm = { code ->
                pendingAction = null
                dispatch?.invoke(ServiceCall.alarmAction(state.id, pending, code))
            },
        )
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
    // Sound mode + group members aren't parsed into typed EntityState fields, so we
    // read them straight off the raw attribute map (mirroring HA's more-info names).
    val soundModes = state.attrStringList("sound_mode_list")
    val currentSound = state.attrString("sound_mode")
    val hasSound = state.hasMediaFeature(EntityState.MediaPlayerFeature.SELECT_SOUND_MODE) &&
        soundModes.isNotEmpty()
    val groupMembers = state.attrStringList("group_members")
    val hasGroup = state.hasMediaFeature(EntityState.MediaPlayerFeature.GROUPING) &&
        groupMembers.size > 1
    if (!hasShuffle && !hasRepeat && !hasSource && !hasSound && !hasGroup) return
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
                        label = "REPEAT ${current.uppercase(java.util.Locale.US)}",
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
                        label = source.uppercase(java.util.Locale.US),
                        accent = accent,
                        selected = state.mediaSource.equals(source, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.mediaSelectSource(state.id, source))
                        },
                    )
                }
            }
        }
        if (hasSound) {
            Spacer(Modifier.height(6.dp))
            Text(text = "SOUND MODE", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                soundModes.forEach { mode ->
                    PanelChip(
                        label = mode.uppercase(java.util.Locale.US),
                        accent = accent,
                        selected = currentSound.equals(mode, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(
                                ServiceCall(
                                    state.id,
                                    "select_sound_mode",
                                    kotlinx.serialization.json.buildJsonObject {
                                        put(
                                            "sound_mode",
                                            kotlinx.serialization.json.JsonPrimitive(mode),
                                        )
                                    },
                                ),
                            )
                        },
                    )
                }
            }
        }
        if (hasGroup) {
            // Read-only readout of the active group. HA's more-info offers a full
            // join/unjoin dialog; on the R1's screen a count plus an UNGROUP action
            // (media_player.unjoin on this member) is the high-value slice.
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "GROUPED", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(6.dp))
                Text(text = "${groupMembers.size}", style = R1.labelMicro, color = accent)
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PanelChip("UNGROUP", accent) {
                    dispatch?.invoke(
                        ServiceCall(
                            state.id,
                            "unjoin",
                            kotlinx.serialization.json.JsonObject(emptyMap()),
                        ),
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

// ── Raw-attribute readers ────────────────────────────────────────────────────────────────
// The cover/humidifier panels read straight from the entity's raw attributes map
// rather than relying on typed EntityState fields, because the repository doesn't
// parse these particular attributes into dedicated fields (and this slice doesn't
// touch the repository). attributesJson is the verbatim HA attributes object, so
// these readers mirror HA's own attribute names exactly.

internal fun EntityState.attrInt(key: String): Int? =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()

internal fun EntityState.attrString(key: String): String? =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content

internal fun EntityState.attrStringList(key: String): List<String> =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?: emptyList()

/**
 * `supported_features` bitmask read straight from the entity's raw attributes.
 * EntityState.supportedFeatures is only populated by the repository for a handful
 * of domains (climate / valve / water_heater / lawn_mower / alarm); cover and
 * humidifier aren't among them, so the dedicated panels read the bit directly.
 * Returns 0 when the attribute is absent, which the panels treat as
 * "forgive the omission" the same way [EntityState.hasFeature] does.
 */
private fun EntityState.rawSupportedFeatures(): Int = attrInt("supported_features") ?: 0

/** True when [bit] is set in the raw `supported_features`, or when the integration
 *  didn't advertise a bitmask at all (== 0) — same forgive-an-omission rule the
 *  typed [EntityState.hasFeature] helpers use. */
private fun EntityState.rawHasFeature(bit: Int): Boolean {
    val sf = rawSupportedFeatures()
    return sf == 0 || (sf and bit) != 0
}

/**
 * Cover control panel — tilt controls for venetian blinds / shutters. The
 * SwitchCard's OPEN/CLOSE end-stops (and the scalar card's wheel) already drive
 * the main position; this panel adds the slat-tilt actions HA exposes alongside.
 *
 * Each chip is gated on the corresponding tilt bit of `supported_features`
 * (OPEN_TILT / CLOSE_TILT / STOP_TILT). A plain roller blind that advertises no
 * tilt bits renders nothing here. The current tilt position (when the cover
 * reports `current_tilt_position`) shows as a small readout so the user can see
 * where the slats sit without a separate more-info pop.
 */
@Composable
fun CoverPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.COVER) return
    val dispatch = LocalOnEntityCall.current
    val showOpenTilt = state.rawHasFeature(EntityState.CoverFeature.OPEN_TILT)
    val showCloseTilt = state.rawHasFeature(EntityState.CoverFeature.CLOSE_TILT)
    val showStopTilt = state.rawHasFeature(EntityState.CoverFeature.STOP_TILT)
    // Tilt position-stepping chips only make sense when the integration accepts an
    // explicit tilt position. We surface a coarse -/+ pair (10% steps) rather than a
    // second wheel binding — the wheel already drives the cover's main position, and
    // a duplicate wheel mode would need theme-side plumbing this slice doesn't own.
    val hasTiltPosition = state.rawHasFeature(EntityState.CoverFeature.SET_TILT_POSITION) &&
        state.attrInt("current_tilt_position") != null
    // Gate the tilt section on at least one tilt capability being advertised. We
    // require an explicit tilt bit (not the forgive-omission default) here so a
    // plain blind with supported_features == 0 doesn't sprout a dead tilt row.
    val sf = state.rawSupportedFeatures()
    val anyTiltBit = sf != 0 && (sf and (
        EntityState.CoverFeature.OPEN_TILT or
            EntityState.CoverFeature.CLOSE_TILT or
            EntityState.CoverFeature.STOP_TILT or
            EntityState.CoverFeature.SET_TILT_POSITION
        )) != 0
    val hasFavorites = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current[state.id.value]?.favoritePositions.orEmpty().isNotEmpty()
    if (!anyTiltBit && !hasFavorites) return
    val tiltPos = state.attrInt("current_tilt_position")

    Column(modifier = modifier.fillMaxWidth()) {
        if (anyTiltBit) {
            Text(text = "TILT", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showOpenTilt) PanelChip("OPEN", accent) {
                    dispatch?.invoke(ServiceCall.coverOpenTilt(state.id))
                }
                if (showCloseTilt) PanelChip("CLOSE", accent) {
                    dispatch?.invoke(ServiceCall.coverCloseTilt(state.id))
                }
                if (showStopTilt) PanelChip("STOP", accent) {
                    dispatch?.invoke(ServiceCall.coverStopTilt(state.id))
                }
                if (hasTiltPosition) {
                    val current = tiltPos ?: 0
                    PanelChip("TILT −", accent) {
                        dispatch?.invoke(
                            ServiceCall.coverSetTiltPosition(state.id, (current - 10).coerceIn(0, 100)),
                        )
                    }
                    PanelChip("TILT +", accent) {
                        dispatch?.invoke(
                            ServiceCall.coverSetTiltPosition(state.id, (current + 10).coerceIn(0, 100)),
                        )
                    }
                }
            }
            if (tiltPos != null) {
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "AT", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.width(6.dp))
                    Text(text = "$tiltPos%", style = R1.labelMicro, color = accent)
                }
            }
        }
        FavoritePositionChips(state, accent) { pos ->
            dispatch?.invoke(ServiceCall.setPercent(state.id, pos))
        }
    }
}

/**
 * One-tap favourite chips. Reads the entity's favourites off
 * [com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides] and renders a chip per value;
 * tapping fires [onPick]. Renders nothing when the entity has no favourites
 * configured, so a user who never sets any sees no change.
 */
@Composable
internal fun FavoritePositionChips(
    state: EntityState,
    accent: Color,
    onPick: (Int) -> Unit,
) {
    val overrides = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current
    val favorites = overrides[state.id.value]?.favoritePositions.orEmpty()
    if (favorites.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(text = "FAVOURITES", style = R1.labelMicro, color = R1.InkMuted)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        favorites.forEach { pos ->
            PanelChip(
                label = "$pos%",
                accent = accent,
                selected = state.percent == pos,
                onClick = { onPick(pos) },
            )
        }
    }
}

/**
 * One-tap favourite-colour swatches for a light. Reads the entity's
 * [com.github.itskenny0.r1ha.core.prefs.EntityOverride.favoriteColors] off
 * [com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides]; each swatch fires
 * `light.turn_on` with the colour's `rgb_color`. Renders nothing when none set.
 */
@Composable
internal fun FavoriteColorChips(
    state: EntityState,
    onPick: (Int) -> Unit,
) {
    val overrides = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current
    val colors = overrides[state.id.value]?.favoriteColors.orEmpty()
    if (colors.isEmpty()) return
    Spacer(Modifier.height(6.dp))
    Text(text = "FAVOURITE COLOURS", style = R1.labelMicro, color = R1.InkMuted)
    Spacer(Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        colors.forEach { argb ->
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(argb))
                    .border(1.dp, R1.Hairline, CircleShape)
                    .r1Pressable(onClick = { onPick(argb) }),
            )
        }
    }
}

/** Build the `light.turn_on { rgb_color: [r,g,b] }` call for a favourite swatch. */
internal fun favoriteColorAction(state: EntityState, argb: Int): ServiceCall {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return ServiceCall(
        state.id,
        "turn_on",
        kotlinx.serialization.json.buildJsonObject {
            put(
                "rgb_color",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(r))
                    add(kotlinx.serialization.json.JsonPrimitive(g))
                    add(kotlinx.serialization.json.JsonPrimitive(b))
                },
            )
        },
    )
}

/**
 * Humidifier control panel — surfaces the operating-mode picker
 * (normal / eco / away / boost / sleep / etc.) when the humidifier advertises
 * the MODES feature bit and reports an `available_modes` list. The
 * target-humidity setpoint stays wheel-driven (ServiceCall.setPercent routes
 * humidifier through `set_humidity`), so this panel only adds the discrete
 * mode chips. Renders nothing for humidifiers without modes.
 */
@Composable
fun HumidifierPanel(state: EntityState, accent: Color, modifier: Modifier = Modifier) {
    if (state.id.domain != Domain.HUMIDIFIER) return
    val dispatch = LocalOnEntityCall.current
    val modes = state.attrStringList("available_modes")
    val hasModes = modes.isNotEmpty() &&
        state.rawHasFeature(EntityState.HumidifierFeature.MODES)
    // The actual measured room humidity, distinct from the target the wheel sets — the
    // BigReadout shows the setpoint, this shows what the sensor is reading right now.
    val currentHumidity = state.attrInt("current_humidity")
    if (!hasModes && currentHumidity == null) return
    val currentMode = state.attrString("mode")
    Column(modifier = modifier.fillMaxWidth()) {
        if (currentHumidity != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "NOW", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(6.dp))
                Text(text = "$currentHumidity %", style = R1.labelMicro, color = accent)
            }
            if (hasModes) Spacer(Modifier.height(8.dp))
        }
        if (hasModes) {
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
                        label = optionLabel(mode),
                        accent = accent,
                        selected = currentMode.equals(mode, ignoreCase = true),
                        onClick = {
                            dispatch?.invoke(ServiceCall.humidifierSetMode(state.id, mode))
                        },
                    )
                }
            }
        }
    }
}
