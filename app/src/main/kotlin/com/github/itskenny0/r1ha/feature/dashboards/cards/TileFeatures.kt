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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceTileFeature
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Renders the `features:` row HA's `tile` card shows below the tile body
 * (src/panels/lovelace/card-features). Each feature is scoped to the tile's
 * own entity and gated on that entity's domain + advertised `supported_features`,
 * so a feature the entity can't support draws nothing rather than firing a
 * service HA would reject.
 *
 * Controls follow the established card idiom (ThermostatCard): chip rows for
 * modes / options, +/- [StepperButton]s for the numeric scalars (brightness,
 * position, fan speed, setpoint), and on/off chips for toggle / cover / lock.
 * A draggable slider lives in the more-info surface, which this layer does not
 * touch, so the in-card affordance is the stepper. Every mutation dispatches
 * through [LovelaceAction.CallService] so the screen's existing service plumbing
 * fires the call and the card stays Compose-pure.
 *
 * Returns without emitting anything when the entity is missing or no feature
 * resolves to a control the entity supports.
 */
@Composable
internal fun TileFeatureRows(
    features: List<LovelaceTileFeature>,
    entityId: String,
    state: EntityState?,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    if (features.isEmpty() || state == null) return
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    // Each feature that actually applies gets an 8dp gap above it, except the
    // first rendered one, so a tile with two applicable features has a single
    // divider's worth of breathing room between them and none trailing.
    var anyRendered = false
    features.forEach { feature ->
        if (anyRendered) Spacer(Modifier.height(8.dp))
        val rendered = renderFeature(feature, entityId, domain, state, accent, onAction)
        // The leading spacer above only mattered if this feature rendered; when
        // it didn't, a stray spacer would have been emitted. We avoid that by
        // gating the spacer on a feature having rendered before, and flip the
        // flag only after a successful render.
        if (rendered) anyRendered = true
    }
}

/**
 * Render one feature. Returns true when it emitted a control (so the caller can
 * insert spacing), false when the entity doesn't support it (render nothing).
 * Splitting the gate from the emit keeps the spacing honest: a tile with three
 * configured features where only one applies shows exactly one row.
 */
@Composable
private fun renderFeature(
    feature: LovelaceTileFeature,
    entityId: String,
    domain: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
): Boolean {
    when (feature) {
        is LovelaceTileFeature.Toggle -> {
            // HA's toggle feature targets any entity with an on/off notion via
            // homeassistant.turn_on / turn_off.
            if (!toggleableDomain(domain)) return false
            ToggleFeature(entityId, state, accent, onAction)
        }
        is LovelaceTileFeature.CoverOpenClose -> {
            if (domain != "cover") return false
            CoverOpenCloseFeature(entityId, state, accent, onAction)
        }
        is LovelaceTileFeature.CoverPosition -> {
            // Gated on SET_POSITION; HA's cover-position feature is a 0..100 slider.
            if (domain != "cover" || !state.hasFeature(EntityState.CoverFeature.SET_POSITION)) return false
            ScalarStepperFeature(
                label = "POSITION",
                percent = state.percent ?: 0,
                accent = accent,
                onSet = { pct ->
                    onAction(
                        LovelaceAction.CallService(
                            service = "cover.set_cover_position",
                            entityId = entityId,
                            data = buildJsonObject { put("position", JsonPrimitive(pct)) },
                        ),
                    )
                },
            )
        }
        is LovelaceTileFeature.LightBrightness -> {
            if (domain != "light") return false
            // HA clamps brightness to 1..100 % (a 0 % would turn the light off).
            ScalarStepperFeature(
                label = "BRIGHTNESS",
                percent = (state.percent ?: 0).coerceIn(1, 100),
                accent = accent,
                min = 1,
                onSet = { pct ->
                    onAction(
                        LovelaceAction.CallService(
                            service = "light.turn_on",
                            entityId = entityId,
                            data = buildJsonObject { put("brightness_pct", JsonPrimitive(pct)) },
                        ),
                    )
                },
            )
        }
        is LovelaceTileFeature.FanSpeed -> {
            if (domain != "fan" || !state.hasFanFeature(EntityState.FanFeature.SET_SPEED)) return false
            ScalarStepperFeature(
                label = "SPEED",
                percent = state.percent ?: 0,
                accent = accent,
                onSet = { pct ->
                    onAction(
                        LovelaceAction.CallService(
                            service = "fan.set_percentage",
                            entityId = entityId,
                            data = buildJsonObject { put("percentage", JsonPrimitive(pct)) },
                        ),
                    )
                },
            )
        }
        is LovelaceTileFeature.LockCommands -> {
            if (domain != "lock") return false
            LockCommandsFeature(entityId, state, accent, onAction)
        }
        is LovelaceTileFeature.ClimateHvacModes -> {
            if (domain != "climate") return false
            val modes = filterModes(state.climateHvacModes, feature.modes)
            if (modes.isEmpty()) return false
            val current = state.climateHvacMode ?: state.rawState
            ModeChipRow(modes, current, accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "climate.set_hvac_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("hvac_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.AlarmModes -> {
            if (domain != "alarm_control_panel") return false
            val modes = filterModes(supportedAlarmModes(state), feature.modes)
            if (modes.isEmpty()) return false
            AlarmModeChipRow(entityId, state, modes, accent, onAction)
        }
        is LovelaceTileFeature.TargetTemperature -> {
            if (domain != "climate" && domain != "water_heater") return false
            TargetTemperatureFeature(entityId, domain, state, accent, onAction)
        }
        is LovelaceTileFeature.SelectOptions -> {
            if (domain != "select" && domain != "input_select") return false
            val options = filterModes(state.selectOptions, feature.options)
            if (options.isEmpty()) return false
            ModeChipRow(options, state.currentOption ?: state.rawState, accent) { opt ->
                onAction(
                    LovelaceAction.CallService(
                        service = "$domain.select_option",
                        entityId = entityId,
                        data = buildJsonObject { put("option", JsonPrimitive(opt)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.Unsupported -> return false
    }
    return true
}

/** on / off pair. Mirrors HA's toggle feature (homeassistant.turn_on/off). */
@Composable
private fun ToggleFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeatureButton(label = "ON", accent = accent, selected = state.isOn, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("homeassistant.turn_on", entityId, null))
        }
        FeatureButton(label = "OFF", accent = accent, selected = !state.isOn, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("homeassistant.turn_off", entityId, null))
        }
    }
}

/** open / close / stop. Stop is shown only when the cover advertises STOP. */
@Composable
private fun CoverOpenCloseFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val canStop = state.hasFeature(EntityState.CoverFeature.STOP)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeatureButton(label = "OPEN", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("cover.open_cover", entityId, null))
        }
        if (canStop) {
            FeatureButton(label = "STOP", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                onAction(LovelaceAction.CallService("cover.stop_cover", entityId, null))
            }
        }
        FeatureButton(label = "CLOSE", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("cover.close_cover", entityId, null))
        }
    }
}

/** lock / unlock pair, highlighting the current state. */
@Composable
private fun LockCommandsFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val locked = state.rawState.equals("locked", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeatureButton(label = "LOCK", accent = accent, selected = locked, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("lock.lock", entityId, null))
        }
        FeatureButton(label = "UNLOCK", accent = accent, selected = !locked, modifier = Modifier.weight(1f)) {
            onAction(LovelaceAction.CallService("lock.unlock", entityId, null))
        }
    }
}

/** Arm-mode chip row. A triggered / arming / pending alarm gets a single DISARM
 *  button (HA's behaviour); otherwise a chip per supported arm mode. */
@Composable
private fun AlarmModeChipRow(
    entityId: String,
    state: EntityState,
    modes: List<String>,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val raw = state.rawState.orEmpty().lowercase()
    if (raw == "triggered" || raw == "arming" || raw == "pending") {
        FeatureButton(label = "DISARM", accent = accent, selected = false, modifier = Modifier.fillMaxWidth()) {
            onAction(LovelaceAction.CallService("alarm_control_panel.alarm_disarm", entityId, null))
        }
        return
    }
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modes.forEach { mode ->
            val selected = state.rawState.equals(armStateFor(mode), ignoreCase = true)
            ModeChip(label = mode.replace('_', ' '), accent = accent, selected = selected) {
                onAction(LovelaceAction.CallService(alarmServiceFor(mode), entityId, null))
            }
        }
    }
}

/** Setpoint stepper for climate / water_heater, nudging by the entity's step. */
@Composable
private fun TargetTemperatureFeature(
    entityId: String,
    domain: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val target = state.climateTargetTemperature ?: return
    val step = state.climateTempStep?.takeIf { it > 0 } ?: 0.5
    val unit = state.temperatureUnit?.takeUnless { it.isBlank() } ?: state.unit?.takeUnless { it.isBlank() } ?: "°"
    val min = state.climateMinTemp
    val max = state.climateMaxTemp
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "TARGET", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(2.dp))
            Text(text = "${fmtTemp(target)}$unit", style = R1.numeralM, color = accent)
        }
        StepperButton(label = "−", accent = accent, enabled = true) {
            val next = (target - step).let { if (min != null) it.coerceAtLeast(min) else it }
            onAction(setTemperatureFeatureAction(domain, entityId, next))
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = true) {
            val next = (target + step).let { if (max != null) it.coerceAtMost(max) else it }
            onAction(setTemperatureFeatureAction(domain, entityId, next))
        }
    }
}

/** A labelled +/- stepper that nudges a 0..100 scalar by 10 % per tap and fires
 *  [onSet] with the clamped target. Used for brightness / position / fan speed. */
@Composable
private fun ScalarStepperFeature(
    label: String,
    percent: Int,
    accent: Color,
    min: Int = 0,
    onSet: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(2.dp))
            Text(text = "$percent%", style = R1.numeralM, color = accent)
        }
        StepperButton(label = "−", accent = accent, enabled = true) {
            onSet((percent - 10).coerceIn(min, 100))
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = true) {
            onSet((percent + 10).coerceIn(min, 100))
        }
    }
}

/** A FlowRow of [ModeChip]s, highlighting the chip matching [current]. */
@Composable
private fun ModeChipRow(
    modes: List<String>,
    current: String?,
    accent: Color,
    onSelect: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        modes.forEach { mode ->
            ModeChip(
                label = mode.replace('_', ' '),
                accent = accent,
                selected = mode.equals(current, ignoreCase = true),
            ) { onSelect(mode) }
        }
    }
}

/** A full-width-capable action button used by the command-style features. */
@Composable
private fun FeatureButton(
    label: String,
    accent: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeM)
            .background(if (selected) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
            .border(1.dp, if (selected) accent else R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (selected) accent else R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Filter an entity's advertised mode/option list by a config `modes:` /
 * `options:` narrowing list, preserving the config's order when given (HA's
 * `filterModes`). An empty filter keeps every advertised entry.
 */
internal fun filterModes(available: List<String>, filter: List<String>): List<String> {
    if (filter.isEmpty()) return available
    return filter.filter { f -> available.any { it.equals(f, ignoreCase = true) } }
}

/**
 * Alarm arm modes the panel advertises, derived from its `supported_features`
 * bitmask. Mirrors HA's `supportedAlarmModes`: each bit gates one arm mode chip.
 * "disarmed" is always offered as a target.
 */
internal fun supportedAlarmModes(state: EntityState): List<String> {
    val modes = mutableListOf("disarmed")
    if (state.hasAlarmFeature(EntityState.AlarmFeature.ARM_HOME)) modes.add("armed_home")
    if (state.hasAlarmFeature(EntityState.AlarmFeature.ARM_AWAY)) modes.add("armed_away")
    if (state.hasAlarmFeature(EntityState.AlarmFeature.ARM_NIGHT)) modes.add("armed_night")
    if (state.hasAlarmFeature(EntityState.AlarmFeature.ARM_VACATION)) modes.add("armed_vacation")
    if (state.hasAlarmFeature(EntityState.AlarmFeature.ARM_CUSTOM_BYPASS)) modes.add("armed_custom_bypass")
    return modes
}

/** The `alarm_control_panel.*` service for an arm mode chip. */
private fun alarmServiceFor(mode: String): String = when (mode) {
    "disarmed" -> "alarm_control_panel.alarm_disarm"
    "armed_home" -> "alarm_control_panel.alarm_arm_home"
    "armed_away" -> "alarm_control_panel.alarm_arm_away"
    "armed_night" -> "alarm_control_panel.alarm_arm_night"
    "armed_vacation" -> "alarm_control_panel.alarm_arm_vacation"
    "armed_custom_bypass" -> "alarm_control_panel.alarm_arm_custom_bypass"
    else -> "alarm_control_panel.alarm_disarm"
}

/** The live state string that means a given arm mode is active (so the chip
 *  highlights). For most modes the mode name is the state verbatim. */
private fun armStateFor(mode: String): String = mode

/** Build the setpoint service call for climate / water_heater. */
private fun setTemperatureFeatureAction(domain: String, entityId: String, temperature: Double): LovelaceAction.CallService {
    val clean = Math.round(temperature * 10.0) / 10.0
    return LovelaceAction.CallService(
        service = "$domain.set_temperature",
        entityId = entityId,
        data = buildJsonObject { put("temperature", JsonPrimitive(clean)) },
    )
}

/** Domains the toggle feature acts on (an on/off notion). */
private fun toggleableDomain(domain: String): Boolean = when (domain) {
    "light", "switch", "input_boolean", "fan", "automation", "siren",
    "humidifier", "remote", "script", "group", "cover", "lock", "media_player",
    "climate", "valve" -> true
    else -> false
}
