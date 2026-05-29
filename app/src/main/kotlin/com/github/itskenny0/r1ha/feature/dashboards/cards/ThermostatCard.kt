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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.util.Locale
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `thermostat` card. Bound to one `climate.*` entity, it
 * shows the live current-temperature reading, a target-setpoint stepper, and
 * the HVAC-mode chip row gated on the entity's advertised `hvac_modes`.
 *
 * Mirrors the card-stack climate idiom: a "−" / "+" stepper nudges the target
 * by the entity's `target_temp_step` (default 0.5) via `climate.set_temperature`,
 * and each mode chip fires `climate.set_hvac_mode`. Everything dispatches through
 * the standard [LovelaceAction.CallService] path so the screen's existing
 * service plumbing fires the calls; the card stays Compose-pure.
 */
@Composable
fun ThermostatCard(
    card: LovelaceCard.Thermostat,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val mode = state?.climateHvacMode ?: state?.rawState
    val active = mode != null && !mode.equals("off", ignoreCase = true) && !mode.equals("unavailable", ignoreCase = true)
    val accent = if (active) R1.AccentWarm else R1.InkSoft
    val unit = state?.temperatureUnit?.takeUnless { it.isBlank() } ?: state?.unit?.takeUnless { it.isBlank() } ?: "°"
    val step = state?.climateTempStep?.takeIf { it > 0 } ?: 0.5
    val target = state?.climateTargetTemperature
    val current = state?.climateCurrentTemperature
    val minTemp = state?.climateMinTemp
    val maxTemp = state?.climateMaxTemp

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
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            StateChip(text = (mode ?: ". ").replace('_', ' '), accent = accent)
        }
        Spacer(Modifier.height(12.dp))
        // Setpoint stepper: current reading on the left, target +/- on the right.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "CURRENT", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = current?.let { "${fmtTemp(it)}$unit" } ?: ". ",
                    style = R1.numeralM,
                    color = R1.Ink,
                )
            }
            if (target != null) {
                StepperButton(label = "−", accent = accent, enabled = active) {
                    val next = (target - step).let { if (minTemp != null) it.coerceAtLeast(minTemp) else it }
                    onAction(setTemperatureAction(card.entityId, next))
                }
                Spacer(Modifier.width(10.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "${fmtTemp(target)}$unit", style = R1.numeralM, color = accent)
                }
                Spacer(Modifier.width(10.dp))
                StepperButton(label = "+", accent = accent, enabled = active) {
                    val next = (target + step).let { if (maxTemp != null) it.coerceAtMost(maxTemp) else it }
                    onAction(setTemperatureAction(card.entityId, next))
                }
            }
        }
        val modes = state?.climateHvacModes.orEmpty()
        if (modes.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                modes.forEach { m ->
                    ModeChip(
                        label = m.replace('_', ' '),
                        accent = accent,
                        selected = m.equals(mode, ignoreCase = true),
                    ) { onAction(setHvacModeAction(card.entityId, m)) }
                }
            }
        }
    }
}

@Composable
internal fun StepperButton(label: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val tint = if (enabled) accent else R1.InkMuted
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(R1.SurfaceMuted)
            .border(1.dp, tint.copy(alpha = 0.6f), CircleShape)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.numeralM, color = tint)
    }
}

@Composable
internal fun ModeChip(label: String, accent: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(if (selected) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) accent else R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text = label.uppercase(Locale.US), style = R1.labelMicro, color = if (selected) accent else R1.Ink)
    }
}

/** Trim a trailing ".0" so whole-degree setpoints read as "21" not "21.0". */
internal fun fmtTemp(value: Double): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded % 1.0 == 0.0) rounded.toInt().toString()
    else String.format(Locale.US, "%.1f", rounded)
}

private fun setTemperatureAction(entityId: String, temperature: Double): LovelaceAction.CallService {
    val clean = Math.round(temperature * 10.0) / 10.0
    return LovelaceAction.CallService(
        service = "climate.set_temperature",
        entityId = entityId,
        data = buildJsonObject { put("temperature", JsonPrimitive(clean)) },
    )
}

private fun setHvacModeAction(entityId: String, mode: String): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "climate.set_hvac_mode",
        entityId = entityId,
        data = buildJsonObject { put("hvac_mode", JsonPrimitive(mode)) },
    )
