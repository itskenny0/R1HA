package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `alarm-panel` card. Mirrors the cardstack AlarmPanel:
 * a DISARM chip plus arm-mode chips gated on the entity's advertised
 * features, with the currently-active mode highlighted. When the alarm has
 * a `code_format`, the chip opens a PIN keypad and the entered code rides
 * along on the service call's `code` field.
 *
 * Actions dispatch through the standard [LovelaceAction.CallService] path
 * so the dashboards screen's existing service plumbing fires them; the
 * card stays Compose-pure and never touches the repository directly.
 */
@Composable
fun AlarmPanelCard(
    card: LovelaceCard.AlarmPanel,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val raw = state?.rawState?.lowercase().orEmpty()
    val accent = if (raw.startsWith("armed") || raw == "triggered") R1.AccentWarm else R1.AccentGreen
    val codeFormat = state?.alarmCodeFormat
    val codeArmRequired = state?.alarmCodeArmRequired ?: true

    // Which arm-mode chips to show: the config's `states` list (filtered by what
    // the panel advertises) or HA's [arm_home, arm_away] default. "disarm" is
    // always present and rendered separately.
    val armModes = remember(card.states, state?.supportedFeatures) {
        alarmArmModes(card.states, state)
    }

    var pending by remember { mutableStateOf<AlarmChip?>(null) }

    val fire: (AlarmChip) -> Unit = { chip ->
        // code_format drives whether a code is collected; disarm always needs it
        // when a format is set, arming additionally honours code_arm_required.
        val mode = alarmCodeMode(codeFormat, codeArmRequired, arming = chip != AlarmChip.DISARM)
        if (mode == AlarmCodeMode.NONE) onAction(chip.action(card.entityId, null)) else pending = chip
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StateChip(text = if (raw.isBlank()) "-" else raw.replace('_', ' '), accent = accent)
        }
        Spacer(Modifier.height(10.dp))
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            AlarmChipButton("DISARM", accent, raw == "disarmed") { fire(AlarmChip.DISARM) }
            armModes.forEach { mode ->
                val chip = AlarmChip.forMode(mode) ?: return@forEach
                AlarmChipButton(chip.shortLabel, accent, raw == chip.activeState) { fire(chip) }
            }
        }
    }

    val p = pending
    if (p != null) {
        val mode = alarmCodeMode(codeFormat, codeArmRequired, arming = p != AlarmChip.DISARM)
        AlarmCodeDialog(
            title = p.label,
            mode = mode,
            accent = accent,
            onDismiss = { pending = null },
            onConfirm = { code ->
                pending = null
                onAction(p.action(card.entityId, code))
            },
        )
    }
}

@Composable
private fun AlarmChipButton(label: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(if (selected) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) accent else R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = if (selected) accent else R1.Ink)
    }
}

/** Arm/disarm chips and the service each maps to. [shortLabel] is the compact
 *  chip caption; [activeState] is the alarm state that marks the chip selected;
 *  [mode] is the bare arm-mode token used to map from [alarmArmModes]. */
private enum class AlarmChip(
    val label: String,
    val shortLabel: String,
    val service: String,
    val mode: String,
    val activeState: String,
) {
    DISARM("DISARM", "DISARM", "alarm_disarm", "disarm", "disarmed"),
    ARM_AWAY("ARM AWAY", "AWAY", "alarm_arm_away", "arm_away", "armed_away"),
    ARM_HOME("ARM HOME", "HOME", "alarm_arm_home", "arm_home", "armed_home"),
    ARM_NIGHT("ARM NIGHT", "NIGHT", "alarm_arm_night", "arm_night", "armed_night"),
    ARM_VACATION("ARM VACATION", "VACATION", "alarm_arm_vacation", "arm_vacation", "armed_vacation"),
    ARM_BYPASS("ARM BYPASS", "BYPASS", "alarm_arm_custom_bypass", "arm_custom_bypass", "armed_custom_bypass");

    fun action(entityId: String, code: String?): LovelaceAction.CallService {
        val data: JsonObject? = code?.takeUnless { it.isBlank() }?.let {
            buildJsonObject { put("code", JsonPrimitive(it)) }
        }
        return LovelaceAction.CallService(
            service = "alarm_control_panel.$service",
            entityId = entityId,
            data = data,
        )
    }

    companion object {
        fun forMode(mode: String): AlarmChip? = entries.firstOrNull { it.mode == mode }
    }
}

/**
 * Code-entry dialog for code-gated alarm actions. Dispatches on the resolved
 * [AlarmCodeMode]: a 3x4 digit keypad for `code_format: number`, a free-text
 * password field for `code_format: text`. Self-contained so the dashboards layer
 * doesn't reach into the private cardstack dialog.
 */
@Composable
internal fun AlarmCodeDialog(
    title: String,
    mode: AlarmCodeMode,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    if (mode == AlarmCodeMode.TEXT) {
        AlarmTextDialog(title = title, accent = accent, onDismiss = onDismiss, onConfirm = onConfirm)
        return
    }
    var entered by remember { mutableStateOf("") }
    val valid = alarmCodeValid(AlarmCodeMode.NUMBER, entered)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(20.dp)
                .width(260.dp),
        ) {
            Text(text = title, style = R1.bodyEmph, color = accent)
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
            val rows = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("⌫", "0", "OK"),
            )
            rows.forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
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
        }
    }
}

/**
 * Free-text code-entry dialog for a code-protected lock command. Lock
 * `code_format` is a regex pattern (text), so this reuses the alarm text dialog's
 * password field. Used by the tile lock-commands / lock-open-door features.
 */
@Composable
internal fun LockCodeDialog(
    title: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    AlarmTextDialog(title = title, accent = accent, onDismiss = onDismiss, onConfirm = onConfirm)
}

/** Free-text code entry for `code_format: text` panels (a password, not a PIN). */
@Composable
private fun AlarmTextDialog(
    title: String,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    val valid = alarmCodeValid(AlarmCodeMode.TEXT, entered)
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            modifier = Modifier
                .background(R1.Bg)
                .border(1.dp, accent, R1.ShapeM)
                .padding(20.dp)
                .width(260.dp),
        ) {
            Text(text = title, style = R1.bodyEmph, color = accent)
            Spacer(Modifier.height(6.dp))
            Text(text = "ENTER CODE", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(10.dp))
            androidx.compose.material3.OutlinedTextField(
                value = entered,
                onValueChange = { entered = it },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(R1.ShapeS)
                    .background(if (valid) accent else R1.SurfaceMuted)
                    .r1Pressable(onClick = { if (valid) onConfirm(entered) }),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "OK",
                    style = R1.numeralM,
                    color = if (valid) R1.Bg else R1.InkMuted,
                )
            }
        }
    }
}
