package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceTileFeature
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.attrInt
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.attrStringList
import com.github.itskenny0.r1ha.ui.components.chartYFraction
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Duration
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
        is LovelaceTileFeature.ButtonFeature -> {
            // Domains the button feature acts on (HA supportsButtonCardFeature).
            if (domain != "button" && domain != "input_button" &&
                domain != "scene" && domain != "script") return false
            val unavailable = state.rawState == "unavailable"
            val service = if (domain == "scene" || domain == "script") "turn_on" else "press"
            val label = feature.actionName?.uppercase() ?: "PRESS"
            FeatureButton(
                label = label,
                accent = accent,
                selected = false,
                enabled = !unavailable,
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(LovelaceAction.CallService("$domain.$service", entityId, null))
            }
        }
        is LovelaceTileFeature.CoverOpenClose -> {
            if (domain != "cover") return false
            CoverOpenCloseFeature(entityId, state, accent, onAction)
        }
        is LovelaceTileFeature.CoverPosition -> {
            // Gated on SET_POSITION read from the raw bitmask: EntityState.supportedFeatures
            // is never populated for cover, so the typed hasFeature forgives 0 and would
            // render for non-positionable covers HA hides.
            if (domain != "cover" || !state.coverRawSupportsBit(EntityState.CoverFeature.SET_POSITION)) return false
            ScalarStepperFeature(
                label = "POSITION",
                percent = state.percent ?: 0,
                accent = accent,
                enabled = state.rawState != "unavailable",
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
            // Gate on brightness-capable modes (HA's lightSupportsBrightness): an
            // on/off-only light gets no slider. Disable when unavailable.
            if (domain != "light" || !lightSupportsBrightness(state)) return false
            // HA clamps brightness to 1..100 % (a 0 % would turn the light off).
            ScalarStepperFeature(
                label = "BRIGHTNESS",
                percent = (state.percent ?: 0).coerceIn(1, 100),
                accent = accent,
                min = 1,
                enabled = state.rawState != "unavailable",
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
            // Honour the fan's percentage_step so a 3-speed fan nudges by ~33 %
            // (landing on the integration's discrete speeds) rather than a blanket
            // 10 % that lands between speeds the integration would reject.
            val fanStep = state.fanPercentageStep?.takeIf { it > 0 }
                ?.let { kotlin.math.round(it).toInt().coerceIn(1, 100) } ?: 10
            ScalarStepperFeature(
                label = "SPEED",
                percent = state.percent ?: 0,
                accent = accent,
                step = fanStep,
                enabled = state.rawState != "unavailable",
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
        is LovelaceTileFeature.MediaPlayback -> {
            if (domain != "media_player") return false
            MediaPlaybackFeature(entityId, state, feature.controls, accent, onAction)
        }
        is LovelaceTileFeature.MediaSource -> {
            if (domain != "media_player") return false
            val sources = filterModes(state.mediaSourceList, feature.sources)
            if (sources.isEmpty()) return false
            ModeChipRow(sources, state.mediaSource, accent) { src ->
                onAction(
                    LovelaceAction.CallService(
                        service = "media_player.select_source",
                        entityId = entityId,
                        data = buildJsonObject { put("source", JsonPrimitive(src)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.MediaSoundMode -> {
            if (domain != "media_player") return false
            val modes = filterModes(state.attrStringList("sound_mode_list"), feature.soundModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.attrString("sound_mode"), accent) { mode ->
                onAction(
                    LovelaceAction.CallService(
                        service = "media_player.select_sound_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("sound_mode", JsonPrimitive(mode)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.MediaVolumeButtons -> {
            if (domain != "media_player" || !state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET)) return false
            MediaVolumeFeature(entityId, state, feature.step, feature.showMute, accent, onAction)
        }
        is LovelaceTileFeature.MediaVolumeSlider -> {
            if (domain != "media_player" || !state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET)) return false
            MediaVolumeFeature(entityId, state, 5, feature.showMute, accent, onAction)
        }
        is LovelaceTileFeature.TemperatureForecast -> {
            if (domain != "weather") return false
            WeatherForecastFeature(
                entityId = entityId,
                forecastType = feature.forecastType,
                series = ForecastSeries.TEMPERATURE,
                accent = haColorAccent(feature.color) ?: accent,
                showLabels = feature.showLabels,
            )
        }
        is LovelaceTileFeature.PrecipitationForecast -> {
            if (domain != "weather") return false
            WeatherForecastFeature(
                entityId = entityId,
                forecastType = feature.forecastType,
                series = if (feature.precipitationType == "probability") ForecastSeries.PRECIP_PROBABILITY else ForecastSeries.PRECIP_AMOUNT,
                accent = haColorAccent(feature.color) ?: accent,
                showLabels = feature.showLabels,
            )
        }
        // ── Climate mode-pickers ─────────────────────────────────────────────
        is LovelaceTileFeature.ClimateFanModes -> {
            if (domain != "climate") return false
            val modes = filterModes(state.climateFanModes, feature.fanModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.climateFanMode, accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "climate.set_fan_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("fan_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.ClimatePresetModes -> {
            if (domain != "climate") return false
            val modes = filterModes(state.climatePresetModes, feature.presetModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.climatePresetMode, accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "climate.set_preset_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("preset_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.ClimateSwingModes -> {
            if (domain != "climate" || !state.hasClimateFeature(EntityState.ClimateFeature.SWING_MODE)) return false
            val available = state.attrStringList("swing_modes")
            val modes = filterModes(available, feature.swingModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.attrString("swing_mode"), accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "climate.set_swing_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("swing_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.ClimateSwingHorizontalModes -> {
            if (domain != "climate" || !state.hasClimateFeature(EntityState.ClimateFeature.SWING_HORIZONTAL_MODE)) return false
            val available = state.attrStringList("swing_horizontal_modes")
            val modes = filterModes(available, feature.swingModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.attrString("swing_horizontal_mode"), accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "climate.set_swing_horizontal_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("swing_horizontal_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        // ── Fan mode-pickers and toggles ──────────────────────────────────────
        is LovelaceTileFeature.FanPresetModes -> {
            if (domain != "fan" || !state.hasFanFeature(EntityState.FanFeature.PRESET_MODE)) return false
            val modes = filterModes(state.fanPresetModes, feature.presetModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.fanPresetMode, accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "fan.set_preset_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("preset_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.FanDirection -> {
            if (domain != "fan") return false
            // hasFanFeature forgives when supportedFeatures == 0 (never populated for fan);
            // backstop on the attribute so a fan with no direction capability renders nothing.
            if (state.fanDirection == null) return false
            val current = state.fanDirection?.lowercase()
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeatureButton(label = "FORWARD", accent = accent, selected = current == "forward", modifier = Modifier.weight(1f)) {
                    onAction(
                        LovelaceAction.CallService(
                            service = "fan.set_direction",
                            entityId = entityId,
                            data = buildJsonObject { put("direction", JsonPrimitive("forward")) },
                        ),
                    )
                }
                FeatureButton(label = "REVERSE", accent = accent, selected = current == "reverse", modifier = Modifier.weight(1f)) {
                    onAction(
                        LovelaceAction.CallService(
                            service = "fan.set_direction",
                            entityId = entityId,
                            data = buildJsonObject { put("direction", JsonPrimitive("reverse")) },
                        ),
                    )
                }
            }
        }
        is LovelaceTileFeature.FanOscillate -> {
            if (domain != "fan") return false
            // hasFanFeature forgives when supportedFeatures == 0 (never populated for fan);
            // backstop on the attribute so a fan with no oscillation capability renders nothing.
            if (state.fanOscillating == null) return false
            val on = state.fanOscillating == true
            FeatureButton(
                label = if (on) "OSCILLATING" else "OSCILLATE",
                accent = accent,
                selected = on,
                modifier = Modifier.fillMaxWidth(),
            ) {
                onAction(
                    LovelaceAction.CallService(
                        service = "fan.oscillate",
                        entityId = entityId,
                        data = buildJsonObject { put("oscillating", JsonPrimitive(!on)) },
                    ),
                )
            }
        }
        // ── Humidifier ────────────────────────────────────────────────────────
        is LovelaceTileFeature.HumidifierModes -> {
            if (domain != "humidifier") return false
            val available = state.attrStringList("available_modes")
            val modes = filterModes(available, feature.modes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.attrString("mode"), accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "humidifier.set_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        is LovelaceTileFeature.HumidifierToggle -> {
            if (domain != "humidifier") return false
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
        // ── Water heater ──────────────────────────────────────────────────────
        is LovelaceTileFeature.WaterHeaterOperationModes -> {
            if (domain != "water_heater") return false
            // HA's repository stores operation_list / operation_mode in
            // climateHvacModes / climateHvacMode for water_heater (same parser branch).
            val modes = filterModes(state.climateHvacModes, feature.operationModes)
            if (modes.isEmpty()) return false
            ModeChipRow(modes, state.climateHvacMode, accent) { m ->
                onAction(
                    LovelaceAction.CallService(
                        service = "water_heater.set_operation_mode",
                        entityId = entityId,
                        data = buildJsonObject { put("operation_mode", JsonPrimitive(m)) },
                    ),
                )
            }
        }
        // ── Lawn-mower commands ───────────────────────────────────────────────
        is LovelaceTileFeature.LawnMowerCommands -> {
            if (domain != "lawn_mower") return false
            val keys = lawnMowerVisibleCommands(state, feature.commands)
            if (keys.isEmpty()) return false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                keys.forEach { key ->
                    val button = lawnMowerButtonFor(state, key)
                    FeatureButton(
                        label = button.label,
                        accent = accent,
                        selected = false,
                        enabled = button.enabled,
                        modifier = Modifier.weight(1f),
                    ) {
                        onAction(LovelaceAction.CallService("lawn_mower.${button.service}", entityId, null))
                    }
                }
            }
        }
        // ── Vacuum commands ───────────────────────────────────────────────────
        is LovelaceTileFeature.VacuumCommands -> {
            if (domain != "vacuum") return false
            VacuumCommandsFeature(entityId, state, feature.commands, accent, onAction)
        }
        // ── Cover tilt ────────────────────────────────────────────────────────
        is LovelaceTileFeature.CoverTilt -> {
            if (domain != "cover") return false
            // EntityState.supportedFeatures is not populated for cover; read the raw
            // attribute instead (mirrors CoverPanel.rawSupportedFeatures/rawHasFeature).
            val sf = state.coverRawSupportedFeatures()
            // Require an explicit tilt bit — no forgive-on-zero here, mirroring
            // CoverPanel's anyTiltBit gate so a plain blind (sf == 0) renders nothing.
            val anyTiltBit = sf != 0 && (sf and (
                EntityState.CoverFeature.OPEN_TILT or
                    EntityState.CoverFeature.CLOSE_TILT or
                    EntityState.CoverFeature.STOP_TILT
                )) != 0
            if (!anyTiltBit) return false
            val showOpen = state.coverRawHasFeature(EntityState.CoverFeature.OPEN_TILT)
            val showClose = state.coverRawHasFeature(EntityState.CoverFeature.CLOSE_TILT)
            val showStop = state.coverRawHasFeature(EntityState.CoverFeature.STOP_TILT)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showOpen) FeatureButton(label = "OPEN TILT", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("cover.open_cover_tilt", entityId, null))
                }
                if (showStop) FeatureButton(label = "STOP", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("cover.stop_cover_tilt", entityId, null))
                }
                if (showClose) FeatureButton(label = "CLOSE TILT", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("cover.close_cover_tilt", entityId, null))
                }
            }
        }
        // ── Valve open/close ──────────────────────────────────────────────────
        is LovelaceTileFeature.ValveOpenClose -> {
            if (domain != "valve") return false
            val canOpen = state.hasFeature(EntityState.ValveFeature.OPEN)
            val canClose = state.hasFeature(EntityState.ValveFeature.CLOSE)
            val canStop = state.hasFeature(EntityState.ValveFeature.STOP)
            if (!canOpen && !canClose) return false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (canOpen) FeatureButton(label = "OPEN", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("valve.open_valve", entityId, null))
                }
                if (canStop) FeatureButton(label = "STOP", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("valve.stop_valve", entityId, null))
                }
                if (canClose) FeatureButton(label = "CLOSE", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("valve.close_valve", entityId, null))
                }
            }
        }
        // ── Lock open-door ────────────────────────────────────────────────────
        is LovelaceTileFeature.LockOpenDoor -> {
            if (domain != "lock") return false
            // Gate on the raw LockEntityFeature.OPEN bit so the button only shows
            // for locks that actually support opening the door.
            if (!lockSupportsOpen(state)) return false
            LockOpenDoorFeature(entityId, state, accent, onAction)
        }
        // ── Counter actions ───────────────────────────────────────────────────
        is LovelaceTileFeature.CounterActions -> {
            if (domain != "counter") return false
            val allActions = listOf("increment", "decrement", "reset")
            val wanted = if (feature.actions.isEmpty()) allActions else feature.actions.filter { it in allActions }
            if (wanted.isEmpty()) return false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                wanted.forEach { action ->
                    val label = when (action) {
                        "increment" -> "+1"
                        "decrement" -> "-1"
                        else -> action.uppercase()
                    }
                    FeatureButton(label = label, accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                        onAction(LovelaceAction.CallService("counter.$action", entityId, null))
                    }
                }
            }
        }
        // ── Update actions ────────────────────────────────────────────────────
        is LovelaceTileFeature.UpdateActions -> {
            if (domain != "update") return false
            val unavailable = state.rawState == "unavailable"
            // "ask" mode: gate behind a YES/NO dialog before firing install.
            var showBackupAsk by remember(entityId) { mutableStateOf(false) }
            val fireInstall: (Boolean) -> Unit = { withBackup ->
                onAction(
                    LovelaceAction.CallService(
                        service = "update.install",
                        entityId = entityId,
                        data = if (withBackup) buildJsonObject { put("backup", JsonPrimitive(true)) } else null,
                    ),
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeatureButton(label = "INSTALL", accent = accent, selected = false, enabled = !unavailable, modifier = Modifier.weight(1f)) {
                    when (feature.backup) {
                        "yes" -> fireInstall(true)
                        "ask" -> showBackupAsk = true
                        else -> fireInstall(false)
                    }
                }
                FeatureButton(label = "SKIP", accent = accent, selected = false, enabled = !unavailable, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("update.skip", entityId, null))
                }
            }
            if (showBackupAsk) {
                // HA's "ask" mode: prompt whether to backup before installing.
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showBackupAsk = false },
                    containerColor = R1.Bg,
                    title = {
                        androidx.compose.material3.Text(
                            text = "BACKUP BEFORE INSTALL?",
                            style = R1.sectionHeader,
                            color = R1.Ink,
                        )
                    },
                    text = {
                        androidx.compose.material3.Text(
                            text = "Create a backup before installing the update.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    },
                    confirmButton = {
                        com.github.itskenny0.r1ha.ui.components.R1Button(
                            text = "BACKUP + INSTALL",
                            onClick = {
                                showBackupAsk = false
                                fireInstall(true)
                            },
                        )
                    },
                    dismissButton = {
                        com.github.itskenny0.r1ha.ui.components.R1Button(
                            text = "INSTALL",
                            onClick = {
                                showBackupAsk = false
                                fireInstall(false)
                            },
                        )
                    },
                )
            }
        }
        // ── Cover tilt-position scalar ────────────────────────────────────────
        is LovelaceTileFeature.CoverTiltPosition -> {
            if (domain != "cover") return false
            // Gate on the SET_TILT_POSITION bit (read raw, EntityState.supportedFeatures
            // is never populated for cover). HA shows the slider whenever the cover
            // supports it, defaulting to 0 when current_tilt_position is unreported.
            if (!state.coverRawSupportsBit(EntityState.CoverFeature.SET_TILT_POSITION)) return false
            val tiltPos = state.attrInt("current_tilt_position") ?: 0
            ScalarStepperFeature(
                label = "TILT",
                percent = tiltPos,
                accent = accent,
                onSet = { pct ->
                    onAction(
                        LovelaceAction.CallService(
                            service = "cover.set_cover_tilt_position",
                            entityId = entityId,
                            data = buildJsonObject { put("tilt_position", JsonPrimitive(pct)) },
                        ),
                    )
                },
            )
        }
        // ── Valve position scalar ─────────────────────────────────────────────
        is LovelaceTileFeature.ValvePosition -> {
            if (domain != "valve" || !state.hasFeature(EntityState.ValveFeature.SET_POSITION)) return false
            ScalarStepperFeature(
                label = "POSITION",
                percent = state.percent ?: 0,
                accent = accent,
                onSet = { pct ->
                    onAction(
                        LovelaceAction.CallService(
                            service = "valve.set_valve_position",
                            entityId = entityId,
                            data = buildJsonObject { put("position", JsonPrimitive(pct)) },
                        ),
                    )
                },
            )
        }
        // ── Target humidity scalar ────────────────────────────────────────────
        is LovelaceTileFeature.TargetHumidity -> {
            if (domain != "humidifier") return false
            val humidity = state.attrInt("humidity") ?: return false
            val minH = state.attrInt("min_humidity") ?: 0
            val maxH = state.attrInt("max_humidity") ?: 100
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "HUMIDITY", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(text = "$humidity%", style = R1.numeralM, color = accent)
                }
                StepperButton(label = "−", accent = accent, enabled = true) {
                    onAction(
                        LovelaceAction.CallService(
                            service = "humidifier.set_humidity",
                            entityId = entityId,
                            data = buildJsonObject { put("humidity", JsonPrimitive((humidity - 1).coerceIn(minH, maxH))) },
                        ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                StepperButton(label = "+", accent = accent, enabled = true) {
                    onAction(
                        LovelaceAction.CallService(
                            service = "humidifier.set_humidity",
                            entityId = entityId,
                            data = buildJsonObject { put("humidity", JsonPrimitive((humidity + 1).coerceIn(minH, maxH))) },
                        ),
                    )
                }
            }
        }
        // ── Numeric input (number / input_number) ─────────────────────────────
        is LovelaceTileFeature.NumericInput -> {
            if (domain != "number" && domain != "input_number") return false
            val current = state.rawState?.toDoubleOrNull() ?: return false
            val min = state.minRaw
            val max = state.maxRaw
            val step = state.step?.takeIf { it > 0 } ?: 1.0
            NumericStepperFeature(
                label = "VALUE",
                value = current,
                step = step,
                accent = accent,
                onSet = { next ->
                    val clamped = if (min != null && max != null) next.coerceIn(min, max) else next
                    onAction(
                        LovelaceAction.CallService(
                            service = "$domain.set_value",
                            entityId = entityId,
                            data = buildJsonObject { put("value", JsonPrimitive(clamped)) },
                        ),
                    )
                },
            )
        }
        // ── Light colour temperature ──────────────────────────────────────────
        is LovelaceTileFeature.LightColorTemp -> {
            if (domain != "light") return false
            if (!state.supportedColorModes.any { it.equals("color_temp", ignoreCase = true) }) return false
            // 2700..6500 K are HA's DEFAULT_MIN/MAX_KELVIN fallbacks; ~20 nudges
            // span the range. Don't bail when color_temp_kelvin is null (light off
            // or in an RGB mode) - HA still shows the control, anchored at min.
            val minK = state.minColorTempK ?: 2700
            val maxK = state.maxColorTempK ?: 6500
            val currentK = state.colorTempK ?: minK
            val step = ((maxK - minK) / 20.0).let { kotlin.math.round(it).toDouble().coerceAtLeast(1.0) }
            NumericStepperFeature(
                label = "COLOR TEMP",
                value = currentK.toDouble(),
                step = step,
                unit = "K",
                accent = accent,
                onSet = { next ->
                    val clamped = next.coerceIn(minK.toDouble(), maxK.toDouble())
                    onAction(
                        LovelaceAction.CallService(
                            service = "light.turn_on",
                            entityId = entityId,
                            data = buildJsonObject { put("color_temp_kelvin", JsonPrimitive(clamped.toInt())) },
                        ),
                    )
                },
            )
        }
        // ── Registry favorite positions ───────────────────────────────────────
        is LovelaceTileFeature.CoverPositionFavorite -> {
            if (domain != "cover" || !state.coverRawSupportsBit(EntityState.CoverFeature.SET_POSITION)) return false
            FavoritePositionChipsFeature(
                entityId = entityId,
                useTilt = false,
                service = "cover.set_cover_position",
                dataKey = "position",
                accent = accent,
                onAction = onAction,
            )
        }
        is LovelaceTileFeature.CoverTiltFavorite -> {
            if (domain != "cover" || !state.coverRawSupportsBit(EntityState.CoverFeature.SET_TILT_POSITION)) return false
            FavoritePositionChipsFeature(
                entityId = entityId,
                useTilt = true,
                service = "cover.set_cover_tilt_position",
                dataKey = "tilt_position",
                accent = accent,
                onAction = onAction,
            )
        }
        is LovelaceTileFeature.ValvePositionFavorite -> {
            if (domain != "valve" || !state.hasFeature(EntityState.ValveFeature.SET_POSITION)) return false
            FavoritePositionChipsFeature(
                entityId = entityId,
                useTilt = false,
                service = "valve.set_valve_position",
                dataKey = "position",
                accent = accent,
                onAction = onAction,
            )
        }
        // ── Light favorite colours ────────────────────────────────────────────
        is LovelaceTileFeature.LightColorFavorites -> {
            if (domain != "light") return false
            if (!lightSupportsFavoriteColors(state)) return false
            LightColorFavoritesFeature(entityId, state, accent, onAction)
        }
        // ── Area-controls ─────────────────────────────────────────────────────
        is LovelaceTileFeature.AreaControls -> {
            // Area-controls is area-scoped, not entity-scoped: it is rendered by
            // the area card directly (see AreaCard.AreaControlsFeature) with the
            // resolved member states. Routed through the entity path it has no
            // area context, so it draws nothing here.
            return false
        }
        // ── Bar-gauge (HA 2025.9) ─────────────────────────────────────────────
        is LovelaceTileFeature.BarGauge -> {
            // Read value from attribute or entity state; skip when non-numeric.
            val raw = if (feature.attribute != null) {
                state.attributesJson?.get(feature.attribute)
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            } else {
                state.rawState
            }
            val value = raw?.toDoubleOrNull() ?: return false
            val span = (feature.max - feature.min).takeIf { it > 0 } ?: return false
            val fraction = ((value - feature.min) / span).toFloat().coerceIn(0f, 1f)
            val barColor = haColorAccent(feature.color) ?: accent
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = (Math.round(value * 10.0) / 10.0).let {
                        if (it == kotlin.math.floor(it)) it.toLong().toString() else it.toString()
                    },
                    style = R1.labelMicro,
                    color = barColor,
                )
                Spacer(Modifier.height(3.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(R1.ShapeRound)
                        .background(R1.SurfaceMuted),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .clip(R1.ShapeRound)
                            .background(barColor),
                    )
                }
            }
        }
        // ── Trend-graph (HA 2025.9) ──────────────────────────────────────────
        is LovelaceTileFeature.TrendGraph -> {
            val repo = LocalHaRepository.current ?: return false
            var points by remember(entityId, feature.hoursToShow) {
                mutableStateOf<List<HistoryPoint>>(emptyList())
            }
            LaunchedEffect(entityId, feature.hoursToShow) {
                safeEntityId(entityId)?.let { eid ->
                    repo.fetchHistory(eid, hours = feature.hoursToShow)
                        .onSuccess { points = it }
                }
            }
            val numericPts = points.mapNotNull { p -> p.numeric?.let { p.timestamp to it } }
            if (numericPts.size < 2) return false
            val yMin = numericPts.map { it.second }.min()
            val yMax = numericPts.map { it.second }.max()
            val tStart = numericPts.map { it.first }.min()
            val tEnd = numericPts.map { it.first }.max()
            val tSpan = Duration.between(tStart, tEnd).toMillis().coerceAtLeast(1L)
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(R1.Surface)
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            ) {
                val w = size.width
                val h = size.height
                val path = Path()
                numericPts.forEachIndexed { i, (instant, v) ->
                    val elapsed = Duration.between(tStart, instant).toMillis().toFloat()
                    val x = (elapsed / tSpan) * w
                    val yFrac = chartYFraction(v, yMin, yMax)
                    val y = h - (yFrac * h)
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(
                    path = path,
                    color = accent,
                    style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Butt),
                )
            }
        }
        // ── Date-set (HA 2025.9) ─────────────────────────────────────────────
        is LovelaceTileFeature.DateSet -> {
            if (domain != "date" && domain != "datetime") return false
            val isDatetime = domain == "datetime"
            val current = state.rawState
            val datePattern = Regex("\\d{4}-\\d{2}-\\d{2}")
            val datetimePattern = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")
            val valid = if (isDatetime) current?.matches(datetimePattern) == true
                        else current?.matches(datePattern) == true
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = if (isDatetime) "DATETIME" else "DATE", style = R1.labelMicro, color = R1.InkMuted)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = current ?: "-",
                        style = R1.numeralM,
                        color = accent,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                StepperButton(label = "-", accent = accent, enabled = valid) {
                    if (!valid || current == null) return@StepperButton
                    val serviceKey = if (isDatetime) "datetime" else "date"
                    val next = if (isDatetime) nudgeDateTimeByDays(current, -1) else nudgeDateByDays(current, -1)
                    onAction(
                        LovelaceAction.CallService(
                            service = "$domain.set_value",
                            entityId = entityId,
                            data = buildJsonObject { put(serviceKey, JsonPrimitive(next)) },
                        ),
                    )
                }
                Spacer(Modifier.width(10.dp))
                StepperButton(label = "+", accent = accent, enabled = valid) {
                    if (!valid || current == null) return@StepperButton
                    val serviceKey = if (isDatetime) "datetime" else "date"
                    val next = if (isDatetime) nudgeDateTimeByDays(current, +1) else nudgeDateByDays(current, +1)
                    onAction(
                        LovelaceAction.CallService(
                            service = "$domain.set_value",
                            entityId = entityId,
                            data = buildJsonObject { put(serviceKey, JsonPrimitive(next)) },
                        ),
                    )
                }
            }
        }
        is LovelaceTileFeature.Unsupported -> {
            // A feature type we don't model (including custom:* like
            // custom:service-call). Show a muted labeled row so the user
            // can see which configured feature was skipped rather than
            // having it vanish silently. Returns true so the caller
            // inserts the correct inter-feature spacing.
            val label = feature.type.removePrefix("custom:").ifBlank { feature.type }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "$label (unsupported feature)",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    return true
}

/** Nudge a "YYYY-MM-DD" string by [days], returning the new date string or the original on parse failure. */
private fun nudgeDateByDays(date: String, days: Int): String = runCatching {
    java.time.LocalDate.parse(date).plusDays(days.toLong()).toString()
}.getOrDefault(date)

/** Nudge the date part of a "YYYY-MM-DD HH:MM:SS" datetime string by [days]. */
private fun nudgeDateTimeByDays(dt: String, days: Int): String = runCatching {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    java.time.LocalDateTime.parse(dt, formatter).plusDays(days.toLong()).format(formatter)
}.getOrDefault(dt)

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

/**
 * open / stop / close, mirroring HA's cover-open-close feature: each button is
 * shown per the cover's raw `supported_features` bits and disabled at travel
 * limits (canOpen / canClose) or when unavailable. STOP shows only when the
 * cover advertises STOP and disables only when unavailable.
 */
@Composable
private fun CoverOpenCloseFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val gate = coverOpenCloseGate(state)
    if (!gate.showOpen && !gate.showClose && !gate.showStop) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (gate.showOpen) {
            FeatureButton(label = "OPEN", accent = accent, selected = false, enabled = gate.canOpen, modifier = Modifier.weight(1f)) {
                onAction(LovelaceAction.CallService("cover.open_cover", entityId, null))
            }
        }
        if (gate.showStop) {
            FeatureButton(label = "STOP", accent = accent, selected = false, enabled = gate.canStop, modifier = Modifier.weight(1f)) {
                onAction(LovelaceAction.CallService("cover.stop_cover", entityId, null))
            }
        }
        if (gate.showClose) {
            FeatureButton(label = "CLOSE", accent = accent, selected = false, enabled = gate.canClose, modifier = Modifier.weight(1f)) {
                onAction(LovelaceAction.CallService("cover.close_cover", entityId, null))
            }
        }
    }
}

/**
 * lock / unlock pair. Each button highlights the active state and disables per
 * HA's canLock / canUnlock (already in that state, in motion, or unavailable).
 * A code-protected lock (`code_format` set with no registry default code) opens
 * a code dialog and rides the entry on the service call's `code` field.
 */
@Composable
private fun LockCommandsFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val locked = state.rawState.equals("locked", ignoreCase = true)
    val repo = LocalHaRepository.current
    val regOptions = rememberEntityRegistryOptions(repo, entityId)
    // A code is needed only when the lock declares a code_format and the registry
    // carries no default_code (HA's callProtectedLockService gate).
    val needsCode = !state.lockCodeFormat.isNullOrBlank() && regOptions.defaultCode.isNullOrBlank()
    var pending by remember(entityId) { mutableStateOf<String?>(null) }

    val fire: (String) -> Unit = { service ->
        if (needsCode) pending = service else onAction(lockServiceAction(service, entityId, null))
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FeatureButton(label = "LOCK", accent = accent, selected = locked, enabled = lockCanLock(state), modifier = Modifier.weight(1f)) {
            fire("lock")
        }
        FeatureButton(label = "UNLOCK", accent = accent, selected = !locked, enabled = lockCanUnlock(state), modifier = Modifier.weight(1f)) {
            fire("unlock")
        }
    }
    val svc = pending
    if (svc != null) {
        LockCodeDialog(
            title = svc.uppercase(),
            accent = accent,
            onDismiss = { pending = null },
            onConfirm = { code ->
                pending = null
                onAction(lockServiceAction(svc, entityId, code))
            },
        )
    }
}

/** Build a lock service call (`lock.<service>`), riding any code on the data body. */
private fun lockServiceAction(service: String, entityId: String, code: String?): LovelaceAction.CallService {
    val data = code?.takeUnless { it.isBlank() }?.let {
        buildJsonObject { put("code", JsonPrimitive(it)) }
    }
    return LovelaceAction.CallService("lock.$service", entityId, data)
}

/**
 * Single OPEN-door button mirroring HA's two-tap confirm: the first tap arms a
 * warning-coloured CONFIRM state (auto-resetting after 5 s); the second tap, made
 * before the timeout, fires lock.open (prompting for a code first when the lock is
 * code-protected with no registry default). Disabled per canOpen / unavailable.
 */
@Composable
private fun LockOpenDoorFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val repo = LocalHaRepository.current
    val regOptions = rememberEntityRegistryOptions(repo, entityId)
    val needsCode = !state.lockCodeFormat.isNullOrBlank() && regOptions.defaultCode.isNullOrBlank()
    var confirming by remember(entityId) { mutableStateOf(false) }
    var pending by remember(entityId) { mutableStateOf(false) }
    // Auto-reset the confirm state after HA's 5 s window.
    LaunchedEffect(confirming) {
        if (confirming) {
            kotlinx.coroutines.delay(5_000L)
            confirming = false
        }
    }
    val open: () -> Unit = {
        if (needsCode) pending = true else onAction(lockServiceAction("open", entityId, null))
    }
    FeatureButton(
        label = if (confirming) "CONFIRM OPEN" else "OPEN",
        accent = if (confirming) R1.AccentWarm else accent,
        selected = confirming,
        enabled = lockCanOpen(state),
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (!confirming) {
            confirming = true
        } else {
            confirming = false
            open()
        }
    }
    if (pending) {
        LockCodeDialog(
            title = "OPEN",
            accent = accent,
            onDismiss = { pending = false },
            onConfirm = { code ->
                pending = false
                onAction(lockServiceAction("open", entityId, code))
            },
        )
    }
}

/**
 * Arm-mode chip row. A triggered / arming / pending alarm gets a single DISARM
 * button (HA's behaviour); otherwise a chip per supported arm mode. When the
 * panel demands a code (arming with code_arm_required, or any disarm, with a
 * code_format and no registry default_code), the tap opens the alarm keypad /
 * text dialog and the entered code rides on the service call's `code` field
 * (HA's setProtectedAlarmControlPanelMode).
 */
@Composable
private fun AlarmModeChipRow(
    entityId: String,
    state: EntityState,
    modes: List<String>,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val repo = LocalHaRepository.current
    val regOptions = rememberEntityRegistryOptions(repo, entityId)
    val codeFormat = state.alarmCodeFormat
    val codeArmRequired = state.alarmCodeArmRequired
    val hasDefaultCode = !regOptions.defaultCode.isNullOrBlank()
    // The pending mode token a code dialog is collecting a code for, if any.
    var pendingMode by remember(entityId) { mutableStateOf<String?>(null) }

    val fire: (String) -> Unit = { mode ->
        val arming = mode != "disarmed"
        val needsCode = !hasDefaultCode &&
            alarmCodeMode(codeFormat, codeArmRequired, arming) != AlarmCodeMode.NONE
        if (needsCode) pendingMode = mode else onAction(alarmModeAction(mode, entityId, null))
    }

    val raw = state.rawState.orEmpty().lowercase()
    if (raw == "triggered" || raw == "arming" || raw == "pending") {
        FeatureButton(label = "DISARM", accent = accent, selected = false, modifier = Modifier.fillMaxWidth()) {
            fire("disarmed")
        }
    } else {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            modes.forEach { mode ->
                val selected = state.rawState.equals(armStateFor(mode), ignoreCase = true)
                ModeChip(label = mode.replace('_', ' '), accent = accent, selected = selected) {
                    fire(mode)
                }
            }
        }
    }

    val mode = pendingMode
    if (mode != null) {
        val arming = mode != "disarmed"
        AlarmCodeDialog(
            title = if (arming) "ARM" else "DISARM",
            mode = alarmCodeMode(codeFormat, codeArmRequired, arming),
            accent = accent,
            onDismiss = { pendingMode = null },
            onConfirm = { code ->
                pendingMode = null
                onAction(alarmModeAction(mode, entityId, code))
            },
        )
    }
}

/** Build the alarm_control_panel service call for a mode token, riding any code. */
private fun alarmModeAction(mode: String, entityId: String, code: String?): LovelaceAction.CallService {
    val data = code?.takeUnless { it.isBlank() }?.let {
        buildJsonObject { put("code", JsonPrimitive(it)) }
    }
    return LovelaceAction.CallService(alarmServiceFor(mode), entityId, data)
}

/**
 * Setpoint stepper for climate / water_heater, nudging by the entity's step.
 * When the entity reports both target_temp_low and target_temp_high (heat_cool
 * mode) two independent low/high steppers are shown side by side, mirroring the
 * thermostat card's dual-setpoint layout. The two bounds are clamped against
 * each other (low <= high) using [nudgeDualSetpoint].
 */
@Composable
private fun TargetTemperatureFeature(
    entityId: String,
    domain: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val step = state.climateTempStep?.takeIf { it > 0 } ?: 0.5
    val unit = state.temperatureUnit?.takeUnless { it.isBlank() } ?: state.unit?.takeUnless { it.isBlank() } ?: "°"
    val min = state.climateMinTemp
    val max = state.climateMaxTemp
    val targetLow = state.climateTargetTempLow
    val targetHigh = state.climateTargetTempHigh
    if (targetLow != null && targetHigh != null) {
        // Dual setpoint (heat_cool): two steppers for low / high bounds.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "COOL", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.height(2.dp))
                Text(text = "${fmtTemp(targetHigh)}$unit", style = R1.numeralM, color = accent)
            }
            StepperButton(label = "−", accent = accent, enabled = true) {
                val next = nudgeDualSetpoint(targetLow, targetHigh, editingLow = false, direction = -1, step = step, min = min, max = max)
                onAction(setTempRangeFeatureAction(domain, entityId, low = targetLow, high = next))
            }
            Spacer(Modifier.width(4.dp))
            StepperButton(label = "+", accent = accent, enabled = true) {
                val next = nudgeDualSetpoint(targetLow, targetHigh, editingLow = false, direction = +1, step = step, min = min, max = max)
                onAction(setTempRangeFeatureAction(domain, entityId, low = targetLow, high = next))
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "HEAT", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.height(2.dp))
                Text(text = "${fmtTemp(targetLow)}$unit", style = R1.numeralM, color = accent)
            }
            StepperButton(label = "−", accent = accent, enabled = true) {
                val next = nudgeDualSetpoint(targetLow, targetHigh, editingLow = true, direction = -1, step = step, min = min, max = max)
                onAction(setTempRangeFeatureAction(domain, entityId, low = next, high = targetHigh))
            }
            Spacer(Modifier.width(4.dp))
            StepperButton(label = "+", accent = accent, enabled = true) {
                val next = nudgeDualSetpoint(targetLow, targetHigh, editingLow = true, direction = +1, step = step, min = min, max = max)
                onAction(setTempRangeFeatureAction(domain, entityId, low = next, high = targetHigh))
            }
        }
        return
    }
    val target = state.climateTargetTemperature ?: return
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

/** Build the set_temperature call for dual-setpoint heat_cool mode. */
private fun setTempRangeFeatureAction(domain: String, entityId: String, low: Double, high: Double): LovelaceAction.CallService {
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

/** A labelled +/- stepper that nudges a 0..100 scalar by 10 % per tap and fires
 *  [onSet] with the clamped target. Used for brightness / position / fan speed.
 *  [enabled] is false for an unavailable entity (HA disables the slider). */
@Composable
private fun ScalarStepperFeature(
    label: String,
    percent: Int,
    accent: Color,
    min: Int = 0,
    step: Int = 10,
    enabled: Boolean = true,
    onSet: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(2.dp))
            Text(text = "$percent%", style = R1.numeralM, color = if (enabled) accent else R1.InkMuted)
        }
        StepperButton(label = "−", accent = accent, enabled = enabled) {
            onSet((percent - step).coerceIn(min, 100))
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = enabled) {
            onSet((percent + step).coerceIn(min, 100))
        }
    }
}

/**
 * Favorite-position chips for cover / valve (position or tilt). Reads the entity
 * registry's favorite positions (falling back to HA's [DEFAULT_FAVORITE_POSITIONS]
 * when none are configured) and fires the position service with the chosen value.
 */
@Composable
private fun FavoritePositionChipsFeature(
    entityId: String,
    useTilt: Boolean,
    service: String,
    dataKey: String,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val repo = LocalHaRepository.current
    val regOptions = rememberEntityRegistryOptions(repo, entityId)
    val positions = if (useTilt) {
        resolveFavoritePositions(regOptions.favoriteTiltPositions, regOptions.hasFavoriteTiltPositions)
    } else {
        resolveFavoritePositions(regOptions.favoritePositions, regOptions.hasFavoritePositions)
    }
    if (positions.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        positions.forEach { pos ->
            ModeChip(label = "$pos%", accent = accent, selected = false) {
                onAction(
                    LovelaceAction.CallService(
                        service = service,
                        entityId = entityId,
                        data = buildJsonObject { put(dataKey, JsonPrimitive(pos)) },
                    ),
                )
            }
        }
    }
}

/**
 * Favorite-colour swatches for a light. Reads the entity registry's
 * favorite_colors (falling back to HA's computed default swatches) and fires
 * light.turn_on with the stored colour payload, rendering each as a tinted pill.
 */
@Composable
private fun LightColorFavoritesFeature(
    entityId: String,
    state: EntityState,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val repo = LocalHaRepository.current
    val regOptions = rememberEntityRegistryOptions(repo, entityId)
    val colors = resolveFavoriteColors(state, regOptions.favoriteColors, regOptions.hasFavoriteColors)
    if (colors.isEmpty()) return
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        colors.forEach { color ->
            val swatch = favoriteColorSwatchArgb(color)?.let { Color(it) } ?: accent
            Box(
                modifier = Modifier
                    .width(34.dp)
                    .height(34.dp)
                    .clip(R1.ShapeM)
                    .background(swatch)
                    .border(1.dp, R1.Hairline, R1.ShapeM)
                    .r1Pressable(onClick = {
                        onAction(
                            LovelaceAction.CallService(
                                service = "light.turn_on",
                                entityId = entityId,
                                data = color,
                            ),
                        )
                    }),
            )
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
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeM)
            .background(if (selected && enabled) accent.copy(alpha = 0.2f) else R1.SurfaceMuted)
            .border(1.dp, if (selected && enabled) accent else R1.Hairline, R1.ShapeM)
            .let { if (enabled) it.r1Pressable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 9.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = when {
                !enabled -> R1.InkMuted
                selected -> accent
                else -> R1.Ink
            },
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

/**
 * Media-player playback control row. Renders one button per requested control,
 * each gated on the player's advertised supported_features so a button HA would
 * reject is omitted. An empty controls list falls back to HA's default trio.
 */
@Composable
private fun MediaPlaybackFeature(
    entityId: String,
    state: EntityState,
    controls: List<String>,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val wanted = controls.ifEmpty { listOf("media_previous_track", "media_play_pause", "media_next_track") }
    val applicable = wanted.filter { mediaControlSupported(it, state) }
    if (applicable.isEmpty()) return
    val playing = state.rawState.equals("playing", ignoreCase = true)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        applicable.forEach { control ->
            val label = mediaControlLabel(control, playing, state)
            val selected = when (control) {
                "shuffle" -> state.mediaShuffle
                "repeat" -> (state.mediaRepeat ?: "off") != "off"
                "volume_mute" -> state.isVolumeMuted
                else -> false
            }
            FeatureButton(label = label, accent = accent, selected = selected, modifier = Modifier.weight(1f)) {
                onAction(mediaControlAction(entityId, control, state))
            }
        }
    }
}

/** Volume row: a percent readout plus +/- buttons and an optional mute toggle. */
@Composable
private fun MediaVolumeFeature(
    entityId: String,
    state: EntityState,
    step: Int,
    showMute: Boolean,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val pct = state.percent ?: 0
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "VOLUME", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(2.dp))
            Text(text = "$pct%", style = R1.numeralM, color = accent)
        }
        if (showMute && state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_MUTE)) {
            FeatureButton(label = if (state.isVolumeMuted) "UNMUTE" else "MUTE", accent = accent, selected = state.isVolumeMuted) {
                onAction(
                    LovelaceAction.CallService(
                        service = "media_player.volume_mute",
                        entityId = entityId,
                        data = buildJsonObject { put("is_volume_muted", JsonPrimitive(!state.isVolumeMuted)) },
                    ),
                )
            }
            Spacer(Modifier.width(10.dp))
        }
        StepperButton(label = "−", accent = accent, enabled = true) {
            onAction(volumeSetAction(entityId, (pct - step).coerceIn(0, 100)))
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = true) {
            onAction(volumeSetAction(entityId, (pct + step).coerceIn(0, 100)))
        }
    }
}

private fun volumeSetAction(entityId: String, pct: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "media_player.volume_set",
        entityId = entityId,
        data = buildJsonObject { put("volume_level", JsonPrimitive(EntityState.mediaVolumeFromPct(pct))) },
    )

/** Whether the player advertises support for a given playback control. */
private fun mediaControlSupported(control: String, state: EntityState): Boolean = when (control) {
    "turn_on" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.TURN_ON)
    "turn_off" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.TURN_OFF)
    "media_play", "media_play_pause" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.PLAY) ||
        state.hasMediaFeature(EntityState.MediaPlayerFeature.PAUSE)
    "media_pause" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.PAUSE)
    "media_stop" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.STOP)
    "media_previous_track" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.PREVIOUS_TRACK)
    "media_next_track" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.NEXT_TRACK)
    "volume_up", "volume_down" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_STEP) ||
        state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET)
    "volume_mute" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_MUTE)
    "shuffle" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.SHUFFLE_SET)
    "repeat" -> state.hasMediaFeature(EntityState.MediaPlayerFeature.REPEAT_SET)
    else -> false
}

/** Short button label for a playback control. */
private fun mediaControlLabel(control: String, playing: Boolean, state: EntityState): String = when (control) {
    "turn_on" -> "ON"
    "turn_off" -> "OFF"
    "media_play" -> "▶"
    "media_pause" -> "⏸"
    "media_play_pause" -> if (playing) "⏸" else "▶"
    "media_stop" -> "⏹"
    "media_previous_track" -> "⏮"
    "media_next_track" -> "⏭"
    "volume_up" -> "VOL +"
    "volume_down" -> "VOL −"
    "volume_mute" -> if (state.isVolumeMuted) "UNMUTE" else "MUTE"
    "shuffle" -> "SHUFFLE"
    "repeat" -> "REPEAT"
    else -> control.uppercase()
}

/** Build the service call for a playback control. */
private fun mediaControlAction(entityId: String, control: String, state: EntityState): LovelaceAction.CallService = when (control) {
    "shuffle" -> LovelaceAction.CallService(
        "media_player.shuffle_set", entityId,
        buildJsonObject { put("shuffle", JsonPrimitive(!state.mediaShuffle)) },
    )
    "repeat" -> LovelaceAction.CallService(
        "media_player.repeat_set", entityId,
        buildJsonObject { put("repeat", JsonPrimitive(nextRepeat(state.mediaRepeat))) },
    )
    "volume_mute" -> LovelaceAction.CallService(
        "media_player.volume_mute", entityId,
        buildJsonObject { put("is_volume_muted", JsonPrimitive(!state.isVolumeMuted)) },
    )
    else -> LovelaceAction.CallService("media_player.$control", entityId, null)
}

/** Cycle repeat off -> one -> all -> off, matching HA's repeat_set cycle. */
private fun nextRepeat(current: String?): String = when (current) {
    "off", null -> "one"
    "one" -> "all"
    else -> "off"
}

/**
 * Vacuum command row. Honours HA's `commands:` config keys
 * (start_pause / stop / clean_spot / locate / return_home), rendering one button
 * per supported requested command. start_pause is one context-sensitive button
 * (PAUSE while cleaning, else START), each button disabled per its state gate.
 * An empty `commands:` shows the first three supported keys (HA's editor stub).
 */
@Composable
private fun VacuumCommandsFeature(
    entityId: String,
    state: EntityState,
    commands: List<String>,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val keys = vacuumVisibleCommands(state, commands)
    if (keys.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        keys.forEach { key ->
            val button = vacuumButtonFor(state, key)
            FeatureButton(
                label = button.label,
                accent = accent,
                selected = false,
                enabled = button.enabled,
                modifier = Modifier.weight(1f),
            ) {
                onAction(LovelaceAction.CallService("vacuum.${button.service}", entityId, null))
            }
        }
    }
}

/**
 * Generic numeric stepper with an arbitrary value, step, and optional unit.
 * Mirrors [TargetTemperatureFeature] for non-temperature domains (number,
 * input_number, light colour temperature).
 */
@Composable
private fun NumericStepperFeature(
    label: String,
    value: Double,
    step: Double,
    accent: Color,
    unit: String = "",
    onSet: (Double) -> Unit,
) {
    val display = if (value == kotlin.math.floor(value)) value.toLong().toString() else
        (kotlin.math.round(value * 100.0) / 100.0).toString()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.height(2.dp))
            Text(text = "$display$unit", style = R1.numeralM, color = accent)
        }
        StepperButton(label = "−", accent = accent, enabled = true) {
            onSet(kotlin.math.round((value - step) * 1000.0) / 1000.0)
        }
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = true) {
            onSet(kotlin.math.round((value + step) * 1000.0) / 1000.0)
        }
    }
}

/**
 * `supported_features` bitmask read straight from the entity's raw attributes.
 * EntityState.supportedFeatures is only populated by the repository for a handful
 * of domains; cover is not among them, so the cover-tilt branch reads the bit
 * directly. Returns 0 when the attribute is absent.
 */
private fun EntityState.coverRawSupportedFeatures(): Int = attrInt("supported_features") ?: 0

/**
 * True when [bit] is set in the raw `supported_features`, or when the integration
 * didn't advertise a bitmask at all (== 0). Mirrors the forgive-an-omission rule
 * from CoverPanel for individual button gates (the section gate uses the stricter
 * sf != 0 check to avoid rendering a tilt row on a plain blind).
 */
private fun EntityState.coverRawHasFeature(bit: Int): Boolean {
    val sf = coverRawSupportedFeatures()
    return sf == 0 || (sf and bit) != 0
}

/**
 * Strict raw-bitmask test (no forgive-on-zero): true only when [bit] is explicitly
 * set. Used by the cover position / tilt-position / favorite features, which HA
 * gates on a concrete SET_POSITION / SET_TILT_POSITION bit rather than rendering
 * for every cover the way the open/close button gate forgives an omitted mask.
 */
private fun EntityState.coverRawSupportsBit(bit: Int): Boolean {
    val sf = coverRawSupportedFeatures()
    return sf != 0 && (sf and bit) != 0
}

