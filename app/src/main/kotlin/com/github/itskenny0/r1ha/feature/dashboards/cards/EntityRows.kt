package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.ActionConfirmation
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.attrInt
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale

/**
 * Per-domain interactive entity-card rows. Each composes [EntityRowScaffold]
 * (the generic name/badge/more-info contract) and supplies its trailing
 * controls. The control sets and their gating come from the pure helpers in
 * EntityRowLogic.kt; service calls ride the standard [LovelaceAction.CallService]
 * path so the screen's dispatcher (confirmation gate + WS plumbing) handles them.
 *
 * Where a row's run/lock service carries the row's `confirmation:`, it is copied
 * onto the [LovelaceAction.CallService] so the dispatcher gate prompts.
 */

@Composable
internal fun ToggleEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    val raw = state?.rawState?.lowercase()
    val showToggle = raw == "on" || raw == "off" || raw == "unavailable" || raw == "unknown" || state == null
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (showToggle) {
            val on = state?.isOn == true
            val service = if (on) "homeassistant.turn_off" else "homeassistant.turn_on"
            ToggleSwitch(checked = on) {
                onAction(LovelaceAction.CallService(service, row.entityId, null))
            }
        } else {
            StateText(state?.let { compactStateText(it) }.orEmpty(), accent)
        }
    }
}

@Composable
internal fun ButtonEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
    pressService: String,
) {
    val enabled = state?.isAvailable != false
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        RowActionButton(label = row.actionName ?: "PRESS", accent = accent, enabled = enabled) {
            onAction(LovelaceAction.CallService(pressService, row.entityId, null, confirmation = row.confirmation))
        }
    }
}

@Composable
internal fun SceneEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    val enabled = state?.isAvailable != false
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        RowActionButton(label = row.actionName ?: "ACTIVATE", accent = accent, enabled = enabled) {
            onAction(LovelaceAction.CallService("scene.turn_on", row.entityId, null, confirmation = row.confirmation))
        }
    }
}

@Composable
internal fun ScriptEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val running = scriptIsRunning(state)
    val count = scriptRunningCount(state)
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (running) {
            val label = count?.let { "CANCEL $it" } ?: "CANCEL"
            RowActionButton(label = label, accent = R1.StatusRed, enabled = true) {
                onAction(LovelaceAction.CallService("script.turn_off", row.entityId, null))
            }
        }
        if (scriptShowsRun(state)) {
            RowActionButton(label = row.actionName ?: "RUN", accent = accent, enabled = scriptCanRun(state)) {
                onAction(LovelaceAction.CallService("script.turn_on", row.entityId, null, confirmation = row.confirmation))
            }
        }
    }
}

@Composable
internal fun LockEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val locked = state.rawState.equals("locked", ignoreCase = true)
    val service = lockToggleService(state)
    var promptCode by remember { mutableStateOf(false) }
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        RowActionButton(
            label = if (locked) "UNLOCK" else "LOCK",
            accent = accent,
            enabled = state.isAvailable,
        ) {
            if (lockRequiresCode(state)) {
                promptCode = true
            } else {
                onAction(LovelaceAction.CallService(service, row.entityId, null, confirmation = row.confirmation))
            }
        }
    }
    if (promptCode) {
        LockCodeDialog(
            codeFormat = state.lockCodeFormat,
            onDismiss = { promptCode = false },
            onSubmit = { code ->
                promptCode = false
                onAction(
                    LovelaceAction.CallService(
                        service = service,
                        entityId = row.entityId,
                        data = buildJsonObject { put("code", JsonPrimitive(code)) },
                        confirmation = row.confirmation,
                    ),
                )
            },
        )
    }
}

@Composable
internal fun CoverEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val tiltOnly = coverIsTiltOnly(state)
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (tiltOnly) {
            IconBtn("▲", accent, coverCanOpenTilt(state)) {
                onAction(LovelaceAction.CallService("cover.open_cover_tilt", row.entityId, null))
            }
            IconBtn("■", accent, coverCanStop(state)) {
                onAction(LovelaceAction.CallService("cover.stop_cover_tilt", row.entityId, null))
            }
            IconBtn("▼", accent, coverCanCloseTilt(state)) {
                onAction(LovelaceAction.CallService("cover.close_cover_tilt", row.entityId, null))
            }
        } else {
            IconBtn("▲", accent, coverCanOpen(state)) {
                onAction(LovelaceAction.CallService("cover.open_cover", row.entityId, null))
            }
            if (coverHasStop(state)) {
                IconBtn("■", accent, coverCanStop(state)) {
                    onAction(LovelaceAction.CallService("cover.stop_cover", row.entityId, null))
                }
            }
            IconBtn("▼", accent, coverCanClose(state)) {
                onAction(LovelaceAction.CallService("cover.close_cover", row.entityId, null))
            }
        }
    }
}

@Composable
internal fun ValveEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val opening = state.rawState.equals("opening", ignoreCase = true)
    val closing = state.rawState.equals("closing", ignoreCase = true)
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        IconBtn("▲", accent, state.isAvailable && !state.rawState.equals("open", true) && !opening) {
            onAction(LovelaceAction.CallService("valve.open_valve", row.entityId, null))
        }
        IconBtn("■", accent, state.isAvailable) {
            onAction(LovelaceAction.CallService("valve.stop_valve", row.entityId, null))
        }
        IconBtn("▼", accent, state.isAvailable && !state.rawState.equals("closed", true) && !closing) {
            onAction(LovelaceAction.CallService("valve.close_valve", row.entityId, null))
        }
    }
}

@Composable
internal fun ClimateEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val secondary = climateRowSecondary(state)
    val target = state.climateTargetTemperature
    val unit = state.temperatureUnit?.takeUnless { it.isBlank() }
        ?: state.unit?.takeUnless { it.isBlank() } ?: "°"
    val step = state.climateTempStep?.takeIf { it > 0 } ?: 0.5
    EntityRowScaffold(row, state, accent, onAction, stateColor, secondaryOverride = secondary) {
        if (target != null) {
            IconBtn("−", accent, state.isAvailable) {
                val next = (target - step).let { if (state.climateMinTemp != null) it.coerceAtLeast(state.climateMinTemp) else it }
                onAction(setTemperatureRowAction(domainOf(row.entityId), row.entityId, next))
            }
            Text(
                text = "${fmtTemp(target)}$unit",
                style = R1.numeralM,
                color = accent,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            IconBtn("+", accent, state.isAvailable) {
                val next = (target + step).let { if (state.climateMaxTemp != null) it.coerceAtMost(state.climateMaxTemp) else it }
                onAction(setTemperatureRowAction(domainOf(row.entityId), row.entityId, next))
            }
        } else {
            StateText(state.rawState.orEmpty(), accent)
        }
    }
}

@Composable
internal fun HumidifierEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val secondary = humidifierRowSecondary(state)
    val target = state.attrInt("humidity")
    EntityRowScaffold(row, state, accent, onAction, stateColor, secondaryOverride = secondary) {
        ToggleSwitch(checked = state.isOn) {
            val service = if (state.isOn) "humidifier.turn_off" else "humidifier.turn_on"
            onAction(LovelaceAction.CallService(service, row.entityId, null))
        }
        if (target != null) {
            Spacer(Modifier.width(4.dp))
            IconBtn("−", accent, state.isAvailable) {
                onAction(setHumidityRowAction(row.entityId, (target - 5).coerceAtLeast(0)))
            }
            Text("$target%", style = R1.numeralM, color = accent, modifier = Modifier.widthIn(min = 40.dp))
            IconBtn("+", accent, state.isAvailable) {
                onAction(setHumidityRowAction(row.entityId, (target + 5).coerceAtMost(100)))
            }
        }
    }
}

@Composable
internal fun UpdateEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (updateCanInstall(state) && !updateIsInstalling(state)) {
            RowActionButton(label = "INSTALL", accent = accent, enabled = state.isAvailable) {
                onAction(LovelaceAction.CallService("update.install", row.entityId, null, confirmation = row.confirmation))
            }
        } else {
            StateText(updateStateLine(state), accent)
        }
    }
}

@Composable
internal fun MediaPlayerEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val description = mediaDescription(state).ifBlank { state.rawState.orEmpty() }
    val controls = mediaControlSet(state)
    EntityRowScaffold(row, state, accent, onAction, stateColor, secondaryOverride = description) {
        controls.forEach { c -> MediaButton(c, state, accent, row.entityId, onAction) }
    }
    // Volume control row, below the transport row (HA renders it as a separate line).
    if (mediaShowsVolume(state)) {
        MediaVolumeRow(row.entityId, state, accent, onAction)
    }
}

@Composable
internal fun SelectEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
    service: String,
) {
    if (state == null || state.selectOptions.isEmpty()) {
        EntityRowScaffold(row, state, accent, onAction, stateColor) {
            StateText(state?.let { compactStateText(it) }.orEmpty(), accent)
        }
        return
    }
    var open by remember { mutableStateOf(false) }
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        RowActionButton(label = (state.currentOption ?: "—").uppercase(Locale.US), accent = accent, enabled = state.isAvailable) {
            open = true
        }
    }
    if (open) {
        OptionPickerDialog(
            title = resolveDisplayName(row.name, row.nameType, state, row.entityId),
            options = state.selectOptions,
            current = state.currentOption,
            onDismiss = { open = false },
            onPick = { option ->
                open = false
                onAction(
                    LovelaceAction.CallService(
                        service = service,
                        entityId = row.entityId,
                        data = buildJsonObject { put("option", JsonPrimitive(option)) },
                    ),
                )
            },
        )
    }
}

@Composable
internal fun NumberEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
    isInputNumber: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val value = state.rawState?.toDoubleOrNull()
    val step = state.attrString("step")?.toDoubleOrNull() ?: state.step ?: 1.0
    val min = state.attrString("min")?.toDoubleOrNull() ?: state.minRaw
    val max = state.attrString("max")?.toDoubleOrNull() ?: state.maxRaw
    val domain = domainOf(row.entityId)
    val unit = state.unit?.takeUnless { it.isBlank() }.orEmpty()
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (value != null) {
            IconBtn("−", accent, state.isAvailable) {
                val next = ((value - step).let { if (min != null) it.coerceAtLeast(min) else it })
                onAction(setNumberRowAction(domain, row.entityId, roundStep(next)))
            }
            Text(
                text = "${fmtNumber(value)}$unit",
                style = R1.numeralM,
                color = accent,
                modifier = Modifier.widthIn(min = 44.dp),
            )
            IconBtn("+", accent, state.isAvailable) {
                val next = ((value + step).let { if (max != null) it.coerceAtMost(max) else it })
                onAction(setNumberRowAction(domain, row.entityId, roundStep(next)))
            }
        } else {
            StateText(state.rawState.orEmpty(), accent)
        }
    }
}

@Composable
internal fun InputTextEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val minLen = state.attrInt("min") ?: 0
    val maxLen = state.attrInt("max") ?: 255
    val password = state.attrString("mode").equals("password", ignoreCase = true)
    val pattern = state.attrString("pattern")
    var text by remember(state.rawState) { mutableStateOf(state.rawState.orEmpty()) }
    val commit = {
        val matches = pattern?.let { runCatching { Regex(it).matches(text) }.getOrDefault(true) } ?: true
        if (text.length in minLen..maxLen && matches && text != state.rawState) {
            // input_text uses input_text.set_value; the standalone text domain
            // uses text.set_value. Both take a `value`.
            onAction(
                LovelaceAction.CallService(
                    service = "${domainOf(row.entityId)}.set_value",
                    entityId = row.entityId,
                    data = buildJsonObject { put("value", JsonPrimitive(text)) },
                ),
            )
        }
    }
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        Box(modifier = Modifier.widthIn(min = 120.dp)) {
            R1TextField(
                value = text,
                onValueChange = { if (it.length <= maxLen) text = it },
                enabled = state.isAvailable,
                monospace = false,
                visualTransformation = if (password) {
                    androidx.compose.ui.text.input.PasswordVisualTransformation()
                } else {
                    androidx.compose.ui.text.input.VisualTransformation.None
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (password) KeyboardType.Password else KeyboardType.Text,
                    imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                ),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { commit() }),
            )
        }
    }
}

@Composable
internal fun InputDatetimeEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityRowScaffold(row, state, accent, onAction, stateColor)
        return
    }
    val domain = domainOf(row.entityId)
    // input_datetime advertises has_date / has_time; the dedicated date / time /
    // datetime domains imply them by domain.
    val hasDate = when (domain) {
        "date", "datetime" -> true
        "time" -> false
        else -> (state.attributesJson?.get("has_date") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    }
    val hasTime = when (domain) {
        "time", "datetime" -> true
        "date" -> false
        else -> (state.attributesJson?.get("has_time") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
    }
    val context = LocalContext.current
    // Normalise the datetime domain's ISO state ("YYYY-MM-DDThh:mm:ss+oo:oo") to
    // the space-separated form the date/time split below expects.
    val raw = state.rawState.orEmpty().replace('T', ' ').substringBefore('+').substringBefore('.').trim()
    val emit: (String?, String?) -> Unit = { date, time ->
        onAction(setDateTimeRowAction(domain, row.entityId, date, time, hasDate, hasTime, raw))
    }
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (hasDate) {
            val datePart = if (hasTime) raw.substringBefore(' ') else raw
            RowActionButton(label = datePart.ifBlank { "DATE" }, accent = accent, enabled = state.isAvailable) {
                showDatePicker(context, datePart) { picked -> emit(picked, null) }
            }
        }
        if (hasTime) {
            val timePart = if (hasDate) raw.substringAfter(' ', "") else raw
            RowActionButton(label = timePart.ifBlank { "TIME" }, accent = accent, enabled = state.isAvailable) {
                showTimePicker(context, timePart) { picked -> emit(null, picked) }
            }
        }
    }
}

@Composable
internal fun GroupEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
    resolveMembers: (String) -> List<String>?,
) {
    val members = groupMembers(state)
    val canToggle = state != null && groupCanToggle(members, resolveMembers)
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (canToggle && state != null) {
            val service = if (state.isOn) "homeassistant.turn_off" else "homeassistant.turn_on"
            ToggleSwitch(checked = state.isOn) {
                onAction(LovelaceAction.CallService(service, row.entityId, null))
            }
        } else {
            StateText(state?.let { compactStateText(it) }.orEmpty(), accent)
        }
    }
}

// ── shared compact controls ─────────────────────────────────────────────────

/** A compact circular icon button used by the transport / cover / stepper rows. */
@Composable
internal fun IconBtn(glyph: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) accent else R1.InkMuted
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(R1.SurfaceMuted)
            .border(1.dp, tint.copy(alpha = 0.5f), CircleShape)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.numeralM, color = tint)
    }
}

/** A labelled pill action button (run / install / lock / select value). */
@Composable
internal fun RowActionButton(label: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) accent else R1.InkMuted
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .border(1.dp, tint.copy(alpha = 0.5f), R1.ShapeM)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(Locale.US),
            style = R1.labelMicro,
            color = tint,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Right-aligned read-only state text (used when a domain row degrades to display). */
@Composable
private fun StateText(text: String, accent: Color) {
    if (text.isBlank()) return
    Text(text = text.uppercase(Locale.US), style = R1.labelMicro, color = accent)
}

@Composable
private fun MediaButton(
    control: MediaControl,
    state: EntityState,
    accent: Color,
    entityId: String,
    onAction: (LovelaceAction) -> Unit,
) {
    val playing = state.rawState.equals("playing", ignoreCase = true)
    val (glyph, service) = when (control) {
        MediaControl.TURN_ON -> "⏻" to "media_player.turn_on"
        MediaControl.TURN_OFF -> "⏻" to "media_player.turn_off"
        MediaControl.PREVIOUS -> "⏮" to "media_player.media_previous_track"
        MediaControl.PLAY_PAUSE -> (if (playing) "⏸" else "▶") to "media_player.media_play_pause"
        MediaControl.PLAY -> "▶" to "media_player.media_play"
        MediaControl.PAUSE -> "⏸" to "media_player.media_pause"
        MediaControl.STOP -> "⏹" to "media_player.media_stop"
        MediaControl.NEXT -> "⏭" to "media_player.media_next_track"
    }
    IconBtn(glyph, accent, state.isAvailable) {
        onAction(LovelaceAction.CallService(service, entityId, null))
    }
}

@Composable
private fun MediaVolumeRow(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val pct = state.percent ?: 0
    Row(
        modifier = Modifier.padding(start = 46.dp, end = 14.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("VOLUME", style = R1.labelMicro, color = R1.InkMuted, modifier = Modifier.weight(1f))
        if (state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_MUTE)) {
            RowActionButton(if (state.isVolumeMuted) "UNMUTE" else "MUTE", accent, state.isAvailable) {
                onAction(
                    LovelaceAction.CallService(
                        "media_player.volume_mute", entityId,
                        buildJsonObject { put("is_volume_muted", JsonPrimitive(!state.isVolumeMuted)) },
                    ),
                )
            }
            Spacer(Modifier.width(6.dp))
        }
        IconBtn("−", accent, state.isAvailable) {
            onAction(volumeRowAction(entityId, (pct - 5).coerceIn(0, 100)))
        }
        Text("$pct%", style = R1.numeralM, color = accent, modifier = Modifier.padding(horizontal = 8.dp))
        IconBtn("+", accent, state.isAvailable) {
            onAction(volumeRowAction(entityId, (pct + 5).coerceIn(0, 100)))
        }
    }
}

// ── secondary-text builders ─────────────────────────────────────────────────

/** "currently X°" plus the preset, mirroring hui-climate-entity-row's readout. */
internal fun climateRowSecondary(state: EntityState): String? {
    val parts = mutableListOf<String>()
    state.climateHvacAction?.takeUnless { it.isBlank() }?.let { parts += it.replace('_', ' ') }
    state.climatePresetMode?.takeUnless { it.isBlank() || it.equals("none", true) }?.let { parts += it.replace('_', ' ') }
    val unit = state.temperatureUnit?.takeUnless { it.isBlank() } ?: state.unit?.takeUnless { it.isBlank() } ?: "°"
    state.climateCurrentTemperature?.let { parts += "now ${fmtTemp(it)}$unit" }
    return parts.joinToString(" · ").ifBlank { null }
}

/** Mode plus the current humidity reading, mirroring hui-humidifier-entity-row. */
internal fun humidifierRowSecondary(state: EntityState): String? {
    val parts = mutableListOf<String>()
    state.attrString("mode")?.takeUnless { it.isBlank() }?.let { parts += it.replace('_', ' ') }
    state.attrInt("current_humidity")?.let { parts += "now $it%" }
    return parts.joinToString(" · ").ifBlank { null }
}

// ── service-call builders ────────────────────────────────────────────────────

private fun setTemperatureRowAction(domain: String, entityId: String, temperature: Double): LovelaceAction.CallService {
    val clean = Math.round(temperature * 10.0) / 10.0
    return LovelaceAction.CallService(
        service = "$domain.set_temperature",
        entityId = entityId,
        data = buildJsonObject { put("temperature", JsonPrimitive(clean)) },
    )
}

private fun setHumidityRowAction(entityId: String, humidity: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "humidifier.set_humidity",
        entityId = entityId,
        data = buildJsonObject { put("humidity", JsonPrimitive(humidity)) },
    )

private fun setNumberRowAction(domain: String, entityId: String, value: Double): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "$domain.set_value",
        entityId = entityId,
        data = buildJsonObject { put("value", JsonPrimitive(value)) },
    )

private fun volumeRowAction(entityId: String, pct: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.volume_set",
        entityId = entityId,
        data = buildJsonObject { put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(pct))) },
    )

/** Build the input_datetime.set_datetime call, supplying whichever parts exist. */
private fun setInputDatetime(entityId: String, date: String?, time: String?): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "input_datetime.set_datetime",
        entityId = entityId,
        data = buildJsonObject {
            date?.takeUnless { it.isBlank() }?.let { put("date", JsonPrimitive(it)) }
            time?.takeUnless { it.isBlank() }?.let { put("time", JsonPrimitive(it)) }
        },
    )

/**
 * The set call for the picker row, routed by domain: the standalone date / time
 * domains take <domain>.set_value; datetime takes a combined `datetime` value
 * (preserving the un-edited half from [raw]); input_datetime keeps set_datetime
 * with only the edited part.
 */
private fun setDateTimeRowAction(
    domain: String,
    entityId: String,
    date: String?,
    time: String?,
    hasDate: Boolean,
    hasTime: Boolean,
    raw: String,
): LovelaceAction.CallService = when (domain) {
    "date" -> LovelaceAction.CallService(
        service = "date.set_value",
        entityId = entityId,
        data = buildJsonObject { put("date", JsonPrimitive(date ?: raw)) },
    )
    "time" -> LovelaceAction.CallService(
        service = "time.set_value",
        entityId = entityId,
        data = buildJsonObject { put("time", JsonPrimitive(time ?: raw)) },
    )
    "datetime" -> {
        val datePart = date ?: raw.substringBefore(' ')
        val timePart = time ?: raw.substringAfter(' ', "")
        LovelaceAction.CallService(
            service = "datetime.set_value",
            entityId = entityId,
            data = buildJsonObject { put("datetime", JsonPrimitive("$datePart $timePart".trim())) },
        )
    }
    else -> setInputDatetime(entityId, date = date, time = time)
}

private fun roundStep(value: Double): Double = Math.round(value * 1000.0) / 1000.0

private fun fmtNumber(value: Double): String =
    if (value == kotlin.math.floor(value)) value.toLong().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')
