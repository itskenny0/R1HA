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
    // water_heater shares the climate parser branch: its operation_mode lands in
    // climateHvacMode and its operation_list in climateHvacModes. The service
    // domain differs, so branch on the entity domain for the call namespace.
    val domain = card.entityId.substringBefore('.', missingDelimiterValue = "climate")
    val isWaterHeater = domain == "water_heater"
    val mode = state?.climateHvacMode ?: state?.rawState
    val active = mode != null && !mode.equals("off", ignoreCase = true) && !mode.equals("unavailable", ignoreCase = true)
    val accent = if (active) R1.AccentWarm else R1.InkSoft
    val unit = state?.temperatureUnit?.takeUnless { it.isBlank() } ?: state?.unit?.takeUnless { it.isBlank() } ?: "°"
    val step = state?.climateTempStep?.takeIf { it > 0 } ?: 0.5
    val target = state?.climateTargetTemperature
    val current = state?.climateCurrentTemperature
    val minTemp = state?.climateMinTemp
    val maxTemp = state?.climateMaxTemp
    // Dual setpoint (heat_cool): two independent low / high bounds. Active only
    // when the entity reports both target_temp_low and target_temp_high.
    val targetLow = state?.climateTargetTempLow
    val targetHigh = state?.climateTargetTempHigh
    val dualSetpoint = targetLow != null && targetHigh != null

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
            StateChip(text = (mode?.takeUnless { it.isBlank() } ?: "-").replace('_', ' '), accent = accent)
            // HA's more-info affordance: a small button opening the detail sheet.
            Spacer(Modifier.width(8.dp))
            MoreInfoDot(accent = accent) { onAction(LovelaceAction.Builtin("more-info", card.entityId)) }
        }
        Spacer(Modifier.height(12.dp))
        when {
            // ── Dual setpoint (heat_cool): a low and a high stepper. ──────────
            dualSetpoint -> {
                if (card.showCurrentTemperature || card.showCurrentAsPrimary) {
                    CurrentReadout(current, unit, primary = card.showCurrentAsPrimary)
                    Spacer(Modifier.height(10.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DualSetpointColumn(
                        label = "LOW",
                        value = targetLow!!,
                        unit = unit,
                        accent = R1.AccentCool,
                        enabled = active,
                        modifier = Modifier.weight(1f),
                        onNudge = { dir ->
                            val next = nudgeDualSetpoint(targetLow, targetHigh!!, editingLow = true, direction = dir, step = step, min = minTemp, max = maxTemp)
                            onAction(setTempRangeAction(domain, card.entityId, low = next, high = targetHigh))
                        },
                    )
                    Spacer(Modifier.width(10.dp))
                    DualSetpointColumn(
                        label = "HIGH",
                        value = targetHigh!!,
                        unit = unit,
                        accent = R1.StatusRed,
                        enabled = active,
                        modifier = Modifier.weight(1f),
                        onNudge = { dir ->
                            val next = nudgeDualSetpoint(targetLow, targetHigh, editingLow = false, direction = dir, step = step, min = minTemp, max = maxTemp)
                            onAction(setTempRangeAction(domain, card.entityId, low = targetLow, high = next))
                        },
                    )
                }
            }
            // ── Single setpoint stepper. ─────────────────────────────────────
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                // The current reading is the dominant value when
                // show_current_as_primary, else the secondary left column.
                if (card.showCurrentTemperature || card.showCurrentAsPrimary) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(text = "CURRENT", style = R1.labelMicro, color = R1.InkMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = current?.let { "${fmtTemp(it)}$unit" } ?: "-",
                            style = if (card.showCurrentAsPrimary) R1.numeralM else R1.numeralM,
                            color = if (card.showCurrentAsPrimary) accent else R1.Ink,
                        )
                    }
                }
                if (target != null) {
                    StepperButton(label = "−", accent = accent, enabled = active) {
                        val next = (target - step).let { if (minTemp != null) it.coerceAtLeast(minTemp) else it }
                        onAction(setTemperatureAction(domain, card.entityId, next))
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = if (!card.showCurrentTemperature && !card.showCurrentAsPrimary) Modifier.weight(1f) else Modifier,
                    ) {
                        Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(text = "${fmtTemp(target)}$unit", style = R1.numeralM, color = if (card.showCurrentAsPrimary) R1.Ink else accent)
                    }
                    Spacer(Modifier.width(10.dp))
                    StepperButton(label = "+", accent = accent, enabled = active) {
                        val next = (target + step).let { if (maxTemp != null) it.coerceAtMost(maxTemp) else it }
                        onAction(setTemperatureAction(domain, card.entityId, next))
                    }
                } else if (!card.showCurrentTemperature && !card.showCurrentAsPrimary) {
                    // No target temperature known; show placeholder centred.
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
                        Spacer(Modifier.height(2.dp))
                        Text(text = "-", style = R1.numeralM, color = accent)
                    }
                }
            }
        }
        // HVAC modes (climate) or operation list (water_heater): the same chip row,
        // dispatching to the domain-appropriate set service.
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
                    ) { onAction(setModeAction(domain, card.entityId, m, isWaterHeater)) }
                }
            }
        }
        // HA 2023.12: tile features rendered below the body (e.g. target-temperature
        // stepper, hvac-modes chip row). Only render when present and state is live.
        val hasFeatures = card.features.any { it !is com.github.itskenny0.r1ha.core.lovelace.LovelaceTileFeature.Unsupported }
        if (hasFeatures && state != null) {
            Spacer(Modifier.height(10.dp))
            TileFeatureRows(
                features = card.features,
                entityId = card.entityId,
                state = state,
                accent = accent,
                onAction = onAction,
            )
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

/** The current-temperature readout, rendered large when [primary]. */
@Composable
private fun CurrentReadout(current: Double?, unit: String, primary: Boolean) {
    Column {
        Text(text = "CURRENT", style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(2.dp))
        Text(
            text = current?.let { "${fmtTemp(it)}$unit" } ?: "-",
            style = R1.numeralM,
            color = if (primary) R1.AccentWarm else R1.Ink,
        )
    }
}

/** A single low/high setpoint column with its own −/+ steppers. [onNudge] is
 *  called with -1 / +1 for the requested direction. */
@Composable
private fun DualSetpointColumn(
    label: String,
    value: Double,
    unit: String,
    accent: Color,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onNudge: (Int) -> Unit,
) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = R1.labelMicro, color = R1.InkMuted)
        Spacer(Modifier.height(2.dp))
        Text(text = "${fmtTemp(value)}$unit", style = R1.numeralM, color = accent)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton(label = "−", accent = accent, enabled = enabled) { onNudge(-1) }
            Spacer(Modifier.width(8.dp))
            StepperButton(label = "+", accent = accent, enabled = enabled) { onNudge(+1) }
        }
    }
}

/** HA's more-info affordance: a small "i" dot opening the entity's detail sheet. */
@Composable
internal fun MoreInfoDot(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .background(R1.SurfaceMuted)
            .border(1.dp, accent.copy(alpha = 0.5f), CircleShape)
            .r1Pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "i", style = R1.labelMicro, color = accent)
    }
}

private fun setTemperatureAction(domain: String, entityId: String, temperature: Double): LovelaceAction.CallService {
    val clean = Math.round(temperature * 10.0) / 10.0
    return LovelaceAction.CallService(
        service = "$domain.set_temperature",
        entityId = entityId,
        data = buildJsonObject { put("temperature", JsonPrimitive(clean)) },
    )
}

/** Dual-setpoint (heat_cool) call: both bounds in one set_temperature call. */
private fun setTempRangeAction(domain: String, entityId: String, low: Double, high: Double): LovelaceAction.CallService {
    val cleanLow = Math.round(low * 10.0) / 10.0
    val cleanHigh = Math.round(high * 10.0) / 10.0
    return LovelaceAction.CallService(
        service = "$domain.set_temperature",
        entityId = entityId,
        data = buildJsonObject {
            put("target_temp_low", JsonPrimitive(cleanLow))
            put("target_temp_high", JsonPrimitive(cleanHigh))
        },
    )
}

/** Set the HVAC mode (climate) or operation mode (water_heater). */
private fun setModeAction(domain: String, entityId: String, mode: String, isWaterHeater: Boolean): LovelaceAction.CallService =
    if (isWaterHeater) {
        LovelaceAction.CallService(
            service = "water_heater.set_operation_mode",
            entityId = entityId,
            data = buildJsonObject { put("operation_mode", JsonPrimitive(mode)) },
        )
    } else {
        LovelaceAction.CallService(
            service = "$domain.set_hvac_mode",
            entityId = entityId,
            data = buildJsonObject { put("hvac_mode", JsonPrimitive(mode)) },
        )
    }
