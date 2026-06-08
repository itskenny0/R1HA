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
import com.github.itskenny0.r1ha.ui.components.attrInt
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.attrStringList
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
            if (domain != "climate") return false
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
            val showStart = state.hasFeature(EntityState.LawnMowerFeature.START_MOWING)
            val showPause = state.hasFeature(EntityState.LawnMowerFeature.PAUSE)
            val showDock = state.hasFeature(EntityState.LawnMowerFeature.DOCK)
            if (!showStart && !showPause && !showDock) return false
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (showStart) FeatureButton(label = "START", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("lawn_mower.start_mowing", entityId, null))
                }
                if (showPause) FeatureButton(label = "PAUSE", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("lawn_mower.pause", entityId, null))
                }
                if (showDock) FeatureButton(label = "DOCK", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("lawn_mower.dock", entityId, null))
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
            // HA's lock domain doesn't expose a typed supported_features bitmask
            // for the OPEN action in EntityState, so we show the button unconditionally
            // and let HA reject the call if the integration doesn't support it.
            FeatureButton(label = "OPEN", accent = accent, selected = false, modifier = Modifier.fillMaxWidth()) {
                onAction(LovelaceAction.CallService("lock.open", entityId, null))
            }
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FeatureButton(label = "INSTALL", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(
                        LovelaceAction.CallService(
                            service = "update.install",
                            entityId = entityId,
                            data = if (feature.backup) buildJsonObject { put("backup", JsonPrimitive(true)) } else null,
                        ),
                    )
                }
                FeatureButton(label = "SKIP", accent = accent, selected = false, modifier = Modifier.weight(1f)) {
                    onAction(LovelaceAction.CallService("update.skip", entityId, null))
                }
            }
        }
        // ── Cover tilt-position scalar ────────────────────────────────────────
        is LovelaceTileFeature.CoverTiltPosition -> {
            if (domain != "cover") return false
            // EntityState.supportedFeatures is not populated for cover; gate on the
            // presence of the actual tilt-position attribute instead (mirrors CoverPanel).
            // A cover that never reports current_tilt_position has no tilt support.
            val tiltPos = state.attrInt("current_tilt_position") ?: return false
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
            val currentK = state.colorTempK ?: return false
            // 2000..6500 K are HA's default mired-equivalent bounds when the
            // light doesn't advertise its own; ~20 nudges span the whole range.
            val minK = state.minColorTempK ?: 2000
            val maxK = state.maxColorTempK ?: 6500
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

/** Cycle repeat off -> all -> one -> off, matching HA's button. */
private fun nextRepeat(current: String?): String = when (current) {
    "off", null -> "all"
    "all" -> "one"
    else -> "off"
}

/**
 * Vacuum command row. Renders one button per requested command, each gated on
 * the vacuum's advertised supported_features. An empty commands list shows all
 * applicable buttons. Mirrors the VacuumPanel idiom.
 */
@Composable
private fun VacuumCommandsFeature(
    entityId: String,
    state: EntityState,
    commands: List<String>,
    accent: Color,
    onAction: (LovelaceAction) -> Unit,
) {
    val all = listOf("start", "pause", "stop", "return_to_base", "clean_spot", "locate")
    val wanted = if (commands.isEmpty()) all else commands.filter { it in all }
    val applicable = wanted.filter { vacuumCommandSupported(it, state) }
    if (applicable.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        applicable.forEach { cmd ->
            FeatureButton(
                label = vacuumCommandLabel(cmd),
                accent = accent,
                selected = false,
                modifier = Modifier.weight(1f),
            ) {
                onAction(LovelaceAction.CallService("vacuum.$cmd", entityId, null))
            }
        }
    }
}

private fun vacuumCommandSupported(cmd: String, state: EntityState): Boolean = when (cmd) {
    "start" -> state.hasVacuumFeature(EntityState.VacuumFeature.START)
    "pause" -> state.hasVacuumFeature(EntityState.VacuumFeature.PAUSE)
    "stop" -> state.hasVacuumFeature(EntityState.VacuumFeature.STOP)
    "return_to_base" -> state.hasVacuumFeature(EntityState.VacuumFeature.RETURN_HOME)
    "clean_spot" -> state.hasVacuumFeature(EntityState.VacuumFeature.CLEAN_SPOT)
    "locate" -> state.hasVacuumFeature(EntityState.VacuumFeature.LOCATE)
    else -> false
}

private fun vacuumCommandLabel(cmd: String): String = when (cmd) {
    "start" -> "START"
    "pause" -> "PAUSE"
    "stop" -> "STOP"
    "return_to_base" -> "DOCK"
    "clean_spot" -> "SPOT"
    "locate" -> "LOCATE"
    else -> cmd.uppercase()
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

