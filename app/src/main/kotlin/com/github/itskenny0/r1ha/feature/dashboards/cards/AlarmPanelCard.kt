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
    val codeRequired = !state?.alarmCodeFormat.isNullOrBlank()
    val armNeedsCode = codeRequired && (state?.alarmCodeArmRequired ?: true)

    // Restrict to the config's `states` list when present; otherwise show the
    // common arm modes. HA's alarm card defaults to [arm_home, arm_away].
    val configStates = card.states.map { it.lowercase() }
    fun shows(mode: String) = configStates.isEmpty() || configStates.contains(mode)

    var pending by remember { mutableStateOf<AlarmChip?>(null) }

    val fire: (AlarmChip) -> Unit = { chip ->
        val needCode = if (chip == AlarmChip.DISARM) codeRequired else armNeedsCode
        if (needCode) pending = chip else onAction(chip.action(card.entityId, null))
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
            if (shows("arm_away")) AlarmChipButton("AWAY", accent, raw == "armed_away") { fire(AlarmChip.ARM_AWAY) }
            if (shows("arm_home")) AlarmChipButton("HOME", accent, raw == "armed_home") { fire(AlarmChip.ARM_HOME) }
            if (shows("arm_night")) AlarmChipButton("NIGHT", accent, raw == "armed_night") { fire(AlarmChip.ARM_NIGHT) }
            if (shows("arm_vacation")) AlarmChipButton("VACATION", accent, raw == "armed_vacation") { fire(AlarmChip.ARM_VACATION) }
            if (shows("arm_custom_bypass")) AlarmChipButton("BYPASS", accent, raw == "armed_custom_bypass") { fire(AlarmChip.ARM_BYPASS) }
        }
    }

    val p = pending
    if (p != null) {
        AlarmPinDialog(
            title = p.label,
            codeFormat = state?.alarmCodeFormat,
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

/** Arm/disarm chips and the service each maps to. */
private enum class AlarmChip(val label: String, val service: String) {
    DISARM("DISARM", "alarm_disarm"),
    ARM_AWAY("ARM AWAY", "alarm_arm_away"),
    ARM_HOME("ARM HOME", "alarm_arm_home"),
    ARM_NIGHT("ARM NIGHT", "alarm_arm_night"),
    ARM_VACATION("ARM VACATION", "alarm_arm_vacation"),
    ARM_BYPASS("ARM BYPASS", "alarm_arm_custom_bypass");

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
}

/** Compact 3x4 PIN keypad for code-gated alarm actions. Self-contained so
 *  the dashboards layer doesn't reach into the private cardstack dialog. */
@Composable
private fun AlarmPinDialog(
    title: String,
    codeFormat: String?,
    accent: Color,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var entered by remember { mutableStateOf("") }
    val pattern = remember(codeFormat) { runCatching { codeFormat?.let { Regex(it) } }.getOrNull() }
    val valid = entered.isNotEmpty() && (pattern?.containsMatchIn(entered) ?: true)
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
