package com.github.itskenny0.r1ha.feature.moreinfo

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.MediaTransport
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides
import com.github.itskenny0.r1ha.core.theme.LocalOnEntityCall
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AlarmPanel
import com.github.itskenny0.r1ha.ui.components.ClimatePanel
import com.github.itskenny0.r1ha.ui.components.CoverPanel
import com.github.itskenny0.r1ha.ui.components.CustomActionsPanel
import com.github.itskenny0.r1ha.ui.components.FanPanel
import com.github.itskenny0.r1ha.ui.components.HumidifierPanel
import com.github.itskenny0.r1ha.ui.components.LawnMowerPanel
import com.github.itskenny0.r1ha.ui.components.LockPanel
import com.github.itskenny0.r1ha.ui.components.MediaExtrasPanel
import com.github.itskenny0.r1ha.ui.components.RemotePanel
import com.github.itskenny0.r1ha.ui.components.SensorHistoryChart
import com.github.itskenny0.r1ha.ui.components.rememberRelativeTime
import com.github.itskenny0.r1ha.ui.components.VacuumPanel
import com.github.itskenny0.r1ha.ui.components.ValvePanel
import com.github.itskenny0.r1ha.ui.components.WaterHeaterPanel
import com.github.itskenny0.r1ha.ui.components.formatSensorValue
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.launch

/**
 * Ultra-detail "more info" bottom sheet for a single HA entity. A dim-scrim overlay
 * sized for the R1's ~240-320 dp screen (and larger tiers), scrollable, holding:
 *
 *  - a header (icon + friendly name + big state value),
 *  - the primary per-domain control (reusing the card-stack's [EntityPanels] plus a
 *    handful of inline controls for the high-value domains),
 *  - a full attribute dump (humanised keys, single-line ellipsised values),
 *  - a compact history sparkline for numeric / sensor entities.
 *
 * The sheet provides [LocalOnEntityCall] + [LocalEntityOverrides] so the reused panels
 * dispatch straight through to [HaRepository.call] without any screen-level wiring.
 *
 * Robustness: never crashes on a missing attribute or an unsupported domain. A domain
 * with no control simply renders no control section; a non-numeric entity renders no
 * chart.
 */
@Composable
fun MoreInfoSheet(
    haRepository: HaRepository,
    settings: SettingsRepository,
    entityId: String,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)

    val stateHolder by rememberMoreInfoState(haRepository, entityId)
    val entity = stateHolder.entity

    val scope = rememberCoroutineScope()
    // Single dispatch lambda shared by every reused panel + inline control. Fire-and-
    // forget: the repository debounces + surfaces its own failure toast, so the sheet
    // doesn't need a result channel here.
    val dispatch: (ServiceCall) -> Unit = remember(haRepository) {
        { call -> scope.launch { haRepository.call(call) } }
    }
    val overrides = LocalEntityOverrides.current

    // Width cap for the panel itself. On mini / compact (maxContentWidth == Unspecified) the
    // sheet fills the panel edge-to-edge as a true bottom sheet. On medium+ tiers it stays a
    // centred, width-capped card rather than stretching wall-wide: clamp the tier's content
    // cap into a sheet-sensible 560..640 dp band so a 13" panel reads a focused dialog, not
    // one giant line.
    val dimens = rememberResponsiveDimens()
    val panelMaxWidth: Dp = if (dimens.capsContentWidth) {
        dimens.maxContentWidth.coerceIn(560.dp, 640.dp)
    } else {
        520.dp
    }

    // Dim scrim. Tapping outside the panel dismisses, matching the platform bottom-sheet
    // idiom. The panel itself swallows taps so a stray tap on a control's gutter doesn't
    // close the sheet.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false, contentDescription = "Close details"),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = panelMaxWidth)
                .heightIn(max = 9_000.dp)
                .clip(R1.ShapeM)
                .background(R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                // Swallow taps so they don't bubble to the scrim's dismiss handler.
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(R1.space.l),
        ) {
            when {
                entity != null -> CompositionLocalProvider(
                    LocalOnEntityCall provides dispatch,
                    LocalEntityOverrides provides overrides,
                ) {
                    MoreInfoContent(
                        haRepository = haRepository,
                        entity = entity,
                        dispatch = dispatch,
                        onDismiss = onDismiss,
                    )
                }

                stateHolder.error != null -> ErrorBody(stateHolder.error!!, onDismiss)
                else -> LoadingBody(onDismiss)
            }
        }
    }
}

@Composable
private fun MoreInfoContent(
    haRepository: HaRepository,
    entity: EntityState,
    dispatch: (ServiceCall) -> Unit,
    onDismiss: () -> Unit,
) {
    val domain = entity.id.domain
    val accent = accentForDomain(domain, entity.deviceClass)
    val scroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(R1.space.m),
    ) {
        Header(entity = entity, accent = accent, onDismiss = onDismiss)

        // ── Primary control ───────────────────────────────────────────────
        val control: @Composable () -> Unit = { PrimaryControl(entity, accent, dispatch) }
        SectionWrap(control)

        // ── Attributes ────────────────────────────────────────────────────
        AttributesSection(entity)

        // ── History ───────────────────────────────────────────────────────
        HistorySection(haRepository = haRepository, entity = entity, accent = accent)

        Spacer(Modifier.height(R1.space.l))
    }
}

@Composable
private fun Header(entity: EntityState, accent: Color, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(
            imageVector = R1Icons.forEntity(
                entityId = entity.id.value,
                deviceClass = entity.deviceClass,
                state = entity.rawState,
            ),
            contentDescription = null,
            tint = accent,
            modifier = Modifier
                .size(28.dp)
                .padding(top = 2.dp),
        )
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${domainLabel(entity.id.domain)}${entity.area?.takeIf { it.isNotBlank() }?.let { " · ${it.replace('_', ' ').uppercase()}" } ?: ""}",
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(R1.space.xxs))
            Text(
                text = entity.friendlyName.ifBlank { entity.id.value },
                style = responsiveType(R1.titleCard),
                color = R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(R1.space.xs))
            val (value, unit) = headerValueAndUnit(entity)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = value,
                    style = responsiveType(R1.numeralM).copy(fontWeight = FontWeight.SemiBold),
                    color = if (entity.isAvailable) accent else R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!unit.isNullOrBlank()) {
                    Spacer(Modifier.width(R1.space.xs))
                    Text(
                        text = unit,
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                        modifier = Modifier.padding(bottom = 3.dp),
                    )
                }
            }
            if (!entity.isAvailable) {
                Spacer(Modifier.height(R1.space.xxs))
                Text(text = "UNAVAILABLE", style = R1.labelMicro, color = R1.StatusAmber)
            }
            // Last-changed line — HA's more-info state-header shows a relative
            // "changed N ago" beneath the state. Mirrors that with the app's own
            // live-ticking relative-time formatter.
            val changedAgo = rememberRelativeTime(entity.lastChanged)
            if (changedAgo.isNotEmpty()) {
                Spacer(Modifier.height(R1.space.xxs))
                Text(
                    text = "CHANGED ${changedAgo.uppercase()}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(R1.space.s))
        CloseButton(onDismiss)
    }
}

@Composable
private fun CloseButton(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .size(R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onDismiss, contentDescription = "Close details"),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "✕", style = R1.numeralM, color = R1.InkSoft)
    }
}

/**
 * The high-value primary control per domain. Where an existing [EntityPanels] panel
 * already covers the discrete chips, we reuse it; the inline controls below add the
 * continuous / transport affordances those panels deliberately leave to the wheel on
 * the card stack (brightness, volume, temperature steppers, media transport).
 */
@Composable
private fun PrimaryControl(
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    when (entity.id.domain) {
        Domain.LIGHT -> LightControl(entity, accent, dispatch)
        Domain.CLIMATE -> {
            ClimateStepper(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            ClimatePanel(state = entity, accent = accent)
        }
        Domain.WATER_HEATER -> {
            ClimateStepper(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            WaterHeaterPanel(state = entity, accent = accent)
        }
        Domain.MEDIA_PLAYER -> {
            MediaControl(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            MediaExtrasPanel(state = entity, accent = accent)
        }
        Domain.COVER -> {
            CoverControl(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            CoverPanel(state = entity, accent = accent)
        }
        Domain.VALVE -> {
            CoverControl(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            ValvePanel(state = entity, accent = accent)
        }
        Domain.LOCK -> {
            // LockPanel renders the keypad path for code locks; for plain locks it
            // early-returns, so always pair it with the explicit toggle below.
            ToggleRow(
                entity = entity,
                accent = accent,
                onLabel = "UNLOCK",
                offLabel = "LOCK",
                onOn = { dispatch(ServiceCall.lockSet(entity.id, lock = false)) },
                onOff = { dispatch(ServiceCall.lockSet(entity.id, lock = true)) },
                isOn = entity.isOn,
            )
            LockPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
        }
        Domain.FAN -> {
            PercentControl(
                label = "SPEED",
                pct = entity.percent ?: if (entity.isOn) 100 else 0,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
            Spacer(Modifier.height(R1.space.s))
            FanPanel(state = entity, accent = accent)
        }
        Domain.HUMIDIFIER -> {
            PercentControl(
                label = "TARGET",
                pct = entity.percent ?: 0,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
            Spacer(Modifier.height(R1.space.s))
            HumidifierPanel(state = entity, accent = accent)
        }
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> ToggleRow(
            entity = entity,
            accent = accent,
            onLabel = "ON",
            offLabel = "OFF",
            onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
            onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
            isOn = entity.isOn,
        )
        Domain.NUMBER, Domain.INPUT_NUMBER -> NumberStepper(entity, accent, dispatch)
        Domain.SELECT, Domain.INPUT_SELECT -> SelectControl(entity, accent, dispatch)
        Domain.VACUUM -> {
            VacuumButtons(entity, accent, dispatch)
            VacuumPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
        }
        Domain.LAWN_MOWER -> LawnMowerPanel(state = entity, accent = accent)
        Domain.REMOTE -> RemotePanel(state = entity, accent = accent)
        // Alarm panels previously fell through to the no-control branch. The
        // AlarmPanel renders the disarm + arm-mode keypad section (PIN-gated when
        // the integration sets code_format), bringing parity with HA's
        // more-info-alarm_control_panel.
        Domain.ALARM_CONTROL_PANEL -> AlarmPanel(state = entity, accent = accent)
        Domain.SCENE, Domain.SCRIPT -> ActionButton("ACTIVATE", accent) {
            dispatch(ServiceCall(entity.id, "turn_on", kotlinx.serialization.json.JsonObject(emptyMap())))
        }
        Domain.BUTTON, Domain.INPUT_BUTTON -> ActionButton("PRESS", accent) {
            dispatch(ServiceCall(entity.id, "press", kotlinx.serialization.json.JsonObject(emptyMap())))
        }
        // No primary control for read-only / unmodelled domains — the section
        // simply renders nothing (the host SectionWrap collapses it).
        else -> Unit
    }
    // User-defined custom actions are valid on any domain; render them last.
    CustomActionsPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
}

// ── Inline controls ───────────────────────────────────────────────────────────────────

@Composable
private fun LightControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        PercentControl(
            label = "BRIGHTNESS",
            pct = entity.percent ?: if (entity.isOn) 100 else 0,
            accent = accent,
            onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
        )
        // Colour temperature — only when the bulb advertises color_temp and reports a
        // range to scale against.
        val supportsCt = entity.supportedColorModes.any { it.equals("color_temp", ignoreCase = true) }
        val minK = entity.minColorTempK
        val maxK = entity.maxColorTempK
        if (supportsCt && minK != null && maxK != null && maxK > minK) {
            val current = entity.colorTempK?.coerceIn(minK, maxK) ?: ((minK + maxK) / 2)
            Text(text = "WHITE TEMP", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            var pos by remember(current) { mutableFloatStateOf(current.toFloat()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = pos,
                    onValueChange = { pos = it },
                    onValueChangeFinished = {
                        dispatch(ServiceCall.setLightColorTemp(entity.id, pos.toInt()))
                    },
                    valueRange = minK.toFloat()..maxK.toFloat(),
                    colors = sliderColors(accent),
                    modifier = Modifier
                        .weight(1f)
                        .semantics { contentDescription = "Colour temperature" },
                )
                Spacer(Modifier.width(R1.space.s))
                Text(text = "${pos.toInt()}K", style = responsiveType(R1.labelMicro), color = accent)
            }
        }
        // Colour swatches — HA's more-info exposes an RGB/HS picker plus favorite
        // colours. The favourite list lives in the entity-registry options (not the
        // state attributes the sheet can see), so we surface a fixed palette of useful
        // hues plus a small white-reset, dispatching via hs_color. Shown only when the
        // bulb advertises a colour mode beyond on/off + colour-temp.
        val colorModes = entity.supportedColorModes.map { it.lowercase() }
        val supportsColor = colorModes.any { it in COLOR_CAPABLE_MODES }
        if (supportsColor) {
            Text(text = "COLOR", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            val currentRgb = entity.attrIntList("rgb_color")
            ChipStrip {
                COLOR_SWATCHES.forEach { (label, hue) ->
                    val sel = currentRgb != null && hueMatches(currentRgb, hue)
                    ColorSwatch(
                        color = hueToColor(hue),
                        selected = sel,
                        description = label,
                        onClick = {
                            dispatch(
                                ServiceCall.setLightHue(entity.id, hue.toDouble()),
                            )
                        },
                    )
                }
                // White reset — drives the bulb back to a neutral white via hs_color
                // saturation 0 (rendered as a separate swatch so it reads distinct).
                ColorSwatch(
                    color = Color.White,
                    selected = false,
                    description = "White",
                    onClick = {
                        dispatch(
                            ServiceCall(
                                entity.id,
                                "turn_on",
                                kotlinx.serialization.json.buildJsonObject {
                                    put(
                                        "hs_color",
                                        kotlinx.serialization.json.buildJsonArray {
                                            add(kotlinx.serialization.json.JsonPrimitive(0))
                                            add(kotlinx.serialization.json.JsonPrimitive(0))
                                        },
                                    )
                                },
                            ),
                        )
                    },
                )
            }
        }
        // Effects — reuse the wire-level setter; chips so the user can pick by name.
        if (entity.effectList.isNotEmpty()) {
            Text(text = "EFFECT", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            ChipStrip {
                entity.effectList.forEach { fx ->
                    DetailChip(
                        label = optionLabel(fx),
                        accent = accent,
                        selected = entity.effect.equals(fx, ignoreCase = true),
                        onClick = { dispatch(ServiceCall.setLightEffect(entity.id, fx)) },
                    )
                }
            }
        }
    }
}

@Composable
private fun MediaControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        if (!entity.mediaTitle.isNullOrBlank() || !entity.mediaArtist.isNullOrBlank()) {
            Column {
                entity.mediaTitle?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = responsiveType(R1.bodyEmph), color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                entity.mediaArtist?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = responsiveType(R1.body), color = R1.InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        // Transport row — prev / play-pause / next, gated on supported_features.
        Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
            if (entity.hasMediaFeature(EntityState.MediaPlayerFeature.PREVIOUS_TRACK)) {
                TransportButton("‹‹", "Previous track", accent) {
                    dispatch(ServiceCall.mediaTransport(entity.id, MediaTransport.PREVIOUS))
                }
            }
            TransportButton(if (entity.isOn) "❚❚" else "▶", "Play or pause", accent, weighted = true) {
                dispatch(ServiceCall.mediaTransport(entity.id, MediaTransport.PLAY_PAUSE))
            }
            if (entity.hasMediaFeature(EntityState.MediaPlayerFeature.NEXT_TRACK)) {
                TransportButton("››", "Next track", accent) {
                    dispatch(ServiceCall.mediaTransport(entity.id, MediaTransport.NEXT))
                }
            }
        }
        // Volume + mute.
        val volPct = entity.percent
        if (volPct != null && entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET)) {
            PercentControl(
                label = "VOLUME",
                pct = volPct,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
        }
        if (entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_MUTE)) {
            ChipStrip {
                DetailChip(
                    label = if (entity.isVolumeMuted) "UNMUTE" else "MUTE",
                    accent = accent,
                    selected = entity.isVolumeMuted,
                    onClick = {
                        dispatch(
                            ServiceCall.mediaTransport(
                                entity.id,
                                MediaTransport.MUTE_TOGGLE,
                                currentlyMuted = entity.isVolumeMuted,
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun CoverControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val open = if (entity.id.domain == Domain.VALVE) "open_valve" else "open_cover"
    val stop = if (entity.id.domain == Domain.VALVE) "stop_valve" else "stop_cover"
    val close = if (entity.id.domain == Domain.VALVE) "close_valve" else "close_cover"
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
            TransportButton("▲", "Open", accent, weighted = true) {
                dispatch(ServiceCall(entity.id, open, kotlinx.serialization.json.JsonObject(emptyMap())))
            }
            TransportButton("■", "Stop", accent, weighted = true) {
                dispatch(ServiceCall(entity.id, stop, kotlinx.serialization.json.JsonObject(emptyMap())))
            }
            TransportButton("▼", "Close", accent, weighted = true) {
                dispatch(ServiceCall(entity.id, close, kotlinx.serialization.json.JsonObject(emptyMap())))
            }
        }
        // Position slider when the entity exposes one.
        val pos = entity.percent
        if (pos != null && entity.supportsScalar) {
            PercentControl(
                label = "POSITION",
                pct = pos,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
        }
    }
}

@Composable
private fun ClimateStepper(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val step = entity.climateTempStep ?: 0.5
    val minT = entity.climateMinTemp
    val maxT = entity.climateMaxTemp
    val unit = entity.temperatureUnit ?: entity.unit ?: ""
    // heat_cool / auto thermostats report a low+high band instead of a single
    // setpoint (TARGET_TEMPERATURE_RANGE). When both bounds are present we render a
    // pair of steppers and fire climate.set_temperature with target_temp_low/high —
    // matching HA's more-info-climate range control.
    val low = entity.climateTargetTempLow
    val high = entity.climateTargetTempHigh
    val isRange = low != null && high != null
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        if (isRange) {
            TempStepperRow(
                label = "LOW",
                value = low,
                unit = unit,
                accent = accent,
                onLower = {
                    val next = (low!! - step).let { v -> minT?.let { maxOf(v, it) } ?: v }
                    dispatch(ServiceCall.setTemperatureRange(entity.id, next, high!!))
                },
                onRaise = {
                    val next = (low!! + step).coerceAtMost(high!!)
                    dispatch(ServiceCall.setTemperatureRange(entity.id, next, high!!))
                },
            )
            TempStepperRow(
                label = "HIGH",
                value = high,
                unit = unit,
                accent = accent,
                onLower = {
                    val next = (high!! - step).coerceAtLeast(low!!)
                    dispatch(ServiceCall.setTemperatureRange(entity.id, low!!, next))
                },
                onRaise = {
                    val next = (high!! + step).let { v -> maxT?.let { minOf(v, it) } ?: v }
                    dispatch(ServiceCall.setTemperatureRange(entity.id, low!!, next))
                },
            )
        } else {
            val target = entity.climateTargetTemperature
            TempStepperRow(
                label = "TARGET",
                value = target,
                unit = unit,
                accent = accent,
                onLower = {
                    if (target != null) {
                        val next = (target - step).let { v -> minT?.let { maxOf(v, it) } ?: v }
                        dispatch(ServiceCall.setTemperature(entity.id, next))
                    }
                },
                onRaise = {
                    if (target != null) {
                        val next = (target + step).let { v -> maxT?.let { minOf(v, it) } ?: v }
                        dispatch(ServiceCall.setTemperature(entity.id, next))
                    }
                },
            )
        }
        // hvac_action ("heating" / "cooling" / "idle" / ...) is the live operating
        // status HA shows beside the mode; current temp + humidity round out the
        // "current" block from more-info-climate.
        val action = entity.attrStr("hvac_action")
        val nowTemp = entity.climateCurrentTemperature
        val nowHum = entity.attrDouble("current_humidity")
        val parts = buildList {
            if (action != null) add(action.replace('_', ' ').uppercase())
            if (nowTemp != null) add("NOW ${formatNumber(nowTemp)}$unit")
            if (nowHum != null) add("RH ${formatNumber(nowHum)}%")
        }
        if (parts.isNotEmpty()) {
            Text(text = parts.joinToString("  ·  "), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        }
    }
}

@Composable
private fun TempStepperRow(
    label: String,
    value: Double?,
    unit: String,
    accent: Color,
    onLower: () -> Unit,
    onRaise: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = label, style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", "Lower $label", accent, enabled = value != null, onClick = onLower)
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = value?.let { formatNumber(it) + unit } ?: "—",
                style = responsiveType(R1.numeralM),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.m))
            StepperButton("+", "Raise $label", accent, enabled = value != null, onClick = onRaise)
        }
    }
}

@Composable
private fun NumberStepper(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val value = entity.raw?.toDouble()
    val step = entity.step ?: 1.0
    val minV = entity.minRaw
    val maxV = entity.maxRaw
    val unit = entity.unit ?: ""
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "VALUE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", "Decrease value", accent, enabled = value != null) {
                if (value != null) {
                    val next = (value - step).let { v -> minV?.let { maxOf(v, it) } ?: v }
                    dispatch(ServiceCall.setNumberValue(entity.id, next))
                }
            }
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = value?.let { formatNumber(it) + unit } ?: "—",
                style = responsiveType(R1.numeralM),
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.m))
            StepperButton("+", "Increase value", accent, enabled = value != null) {
                if (value != null) {
                    val next = (value + step).let { v -> maxV?.let { minOf(v, it) } ?: v }
                    dispatch(ServiceCall.setNumberValue(entity.id, next))
                }
            }
        }
    }
}

@Composable
private fun SelectControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    if (entity.selectOptions.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "OPTIONS", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        ChipStrip(wrap = true) {
            entity.selectOptions.forEach { opt ->
                DetailChip(
                    label = optionLabel(opt),
                    accent = accent,
                    selected = entity.currentOption.equals(opt, ignoreCase = true),
                    onClick = { dispatch(ServiceCall.setSelectOption(entity.id, opt)) },
                )
            }
        }
    }
}

@Composable
private fun VacuumButtons(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    ChipStrip(wrap = true) {
        DetailChip("START", accent) {
            dispatch(ServiceCall.vacuumCommand(entity.id, com.github.itskenny0.r1ha.core.ha.VacuumAction.START))
        }
        DetailChip("PAUSE", accent) {
            dispatch(ServiceCall.vacuumCommand(entity.id, com.github.itskenny0.r1ha.core.ha.VacuumAction.PAUSE))
        }
        DetailChip("DOCK", accent) {
            dispatch(ServiceCall.vacuumCommand(entity.id, com.github.itskenny0.r1ha.core.ha.VacuumAction.RETURN_TO_BASE))
        }
    }
}

// ── Attributes + history ──────────────────────────────────────────────────────────────

@Composable
private fun AttributesSection(entity: EntityState) {
    val attrs = entity.attributesJson
    if (attrs.isNullOrEmptyJson()) return
    val rows = attrs!!.entries
        .filter { it.key !in SUPPRESSED_ATTRIBUTE_KEYS }
        .sortedBy { it.key }
    if (rows.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "ATTRIBUTES", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .padding(R1.space.m),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
            rows.forEach { (key, value) ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(
                        text = humanizeKey(key),
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(0.45f)
                            .padding(end = R1.space.s),
                    )
                    Text(
                        text = formatAttributeValue(value),
                        style = responsiveType(R1.body),
                        color = R1.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(0.55f),
                    )
                }
            }
        }
    }
}

@Composable
private fun HistorySection(haRepository: HaRepository, entity: EntityState, accent: Color) {
    // Numeric history only makes sense for entities whose state is a reading. Probe by
    // whether the current state parses as a number; sensors/number/climate all qualify.
    val numericNow = entity.rawState?.toDoubleOrNull() != null || entity.raw != null
    val sensorLike = entity.id.domain == Domain.SENSOR ||
        entity.id.domain == Domain.NUMBER ||
        entity.id.domain == Domain.INPUT_NUMBER ||
        entity.id.domain == Domain.CLIMATE ||
        entity.id.domain == Domain.WATER_HEATER ||
        entity.id.domain == Domain.HUMIDIFIER
    val enabled = numericNow && sensorLike
    val history by rememberHistory(haRepository, entity.id.value, enabled = enabled)
    if (!enabled) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "HISTORY", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
        when (val pts = history) {
            null -> Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                    .clip(R1.ShapeS)
                    .background(R1.Surface),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "LOADING HISTORY", style = R1.labelMicro, color = R1.InkMuted)
            }

            else -> SensorHistoryChart(points = pts, accent = accent, unit = entity.unit)
        }
    }
}

// ── Shared primitives ─────────────────────────────────────────────────────────────────

@Composable
private fun PercentControl(label: String, pct: Int, accent: Color, onChange: (Int) -> Unit) {
    var pos by remember(pct) { mutableFloatStateOf(pct.toFloat()) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(text = "${pos.toInt()}%", style = responsiveType(R1.labelMicro), color = accent)
        }
        Slider(
            value = pos,
            onValueChange = { pos = it },
            onValueChangeFinished = { onChange(pos.toInt()) },
            valueRange = 0f..100f,
            colors = sliderColors(accent),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

@Composable
private fun ToggleRow(
    entity: EntityState,
    accent: Color,
    onLabel: String,
    offLabel: String,
    isOn: Boolean,
    onOn: () -> Unit,
    onOff: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
        DetailChip(label = onLabel, accent = accent, selected = isOn, onClick = onOn, modifier = Modifier.weight(1f))
        DetailChip(label = offLabel, accent = accent, selected = !isOn, onClick = onOff, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun ActionButton(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(accent)
            .r1Pressable(onClick = onClick, contentDescription = label),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = responsiveType(R1.label), color = R1.Bg, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TransportButton(
    glyph: String,
    description: String,
    accent: Color,
    weighted: Boolean = false,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .then(if (weighted) Modifier.fillMaxWidth() else Modifier.widthIn(min = R1.MinTarget))
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = description)
            .padding(horizontal = R1.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.numeralM, color = accent)
    }
}

@Composable
private fun StepperButton(glyph: String, description: String, accent: Color, enabled: Boolean, onClick: () -> Unit) {
    val base = Modifier
        .size(R1.MinTarget)
        .clip(R1.ShapeS)
        .background(if (enabled) R1.SurfaceMuted else R1.Surface)
        .border(1.dp, if (enabled) accent.copy(alpha = 0.4f) else R1.Hairline, R1.ShapeS)
    Box(
        modifier = if (enabled) base.r1Pressable(onClick = onClick, contentDescription = description) else base,
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.numeralM, color = if (enabled) accent else R1.InkMuted)
    }
}

@Composable
private fun ChipStrip(wrap: Boolean = false, content: @Composable () -> Unit) {
    if (wrap) {
        androidx.compose.foundation.layout.FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            verticalArrangement = Arrangement.spacedBy(R1.space.s),
        ) { content() }
    } else {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) { content() }
    }
}

@Composable
private fun DetailChip(
    label: String,
    accent: Color,
    selected: Boolean = false,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val fill = if (selected) accent else R1.SurfaceMuted
    val textColor = if (selected) R1.Bg else accent
    val border = if (selected) null else accent.copy(alpha = 0.4f)
    val base = modifier
        .heightIn(min = R1.MinTarget)
        .clip(R1.ShapeS)
        .background(fill)
    val bordered = if (border != null) base.border(1.dp, border, R1.ShapeS) else base
    Box(
        modifier = bordered
            .r1Pressable(onClick = onClick, contentDescription = label)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = responsiveType(R1.labelMicro), color = textColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun ColorSwatch(
    color: Color,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(R1.MinTarget)
            .clip(R1.ShapeS)
            .background(color)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) R1.Ink else R1.Hairline,
                shape = R1.ShapeS,
            )
            .r1Pressable(onClick = onClick, contentDescription = description),
    )
}

/** Wrap that drops a section when its content renders nothing measurable. Compose can't
 *  cheaply detect an empty subtree, so we just render the content directly — the
 *  individual controls already early-return for unsupported domains. */
@Composable
private fun SectionWrap(content: @Composable () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) { content() }
}

@Composable
private fun LoadingBody(onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "LOADING", style = responsiveType(R1.sectionHeader), color = R1.InkSoft, modifier = Modifier.weight(1f))
        CloseButton(onDismiss)
    }
    Spacer(Modifier.height(R1.space.l))
    Text(text = "Fetching live state…", style = responsiveType(R1.body), color = R1.InkMuted)
    Spacer(Modifier.height(R1.space.l))
}

@Composable
private fun ErrorBody(message: String, onDismiss: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "UNAVAILABLE", style = responsiveType(R1.sectionHeader), color = R1.StatusAmber, modifier = Modifier.weight(1f))
        CloseButton(onDismiss)
    }
    Spacer(Modifier.height(R1.space.l))
    Text(text = message, style = responsiveType(R1.body), color = R1.InkSoft)
    Spacer(Modifier.height(R1.space.l))
}

@Composable
private fun sliderColors(accent: Color) = SliderDefaults.colors(
    thumbColor = accent,
    activeTrackColor = accent,
    inactiveTrackColor = R1.Hairline,
)

// ── Local helpers ─────────────────────────────────────────────────────────────────────

private fun domainLabel(domain: Domain): String =
    domain.prefix.ifBlank { "ENTITY" }.replace('_', ' ').uppercase()

/**
 * The big header readout + unit. Sensor-family entities show their reading; on/off
 * domains show their friendly state word; scalar domains fall back to the percent.
 */
private fun headerValueAndUnit(entity: EntityState): Pair<String, String?> {
    val raw = entity.rawState
    return when (entity.id.domain) {
        Domain.SENSOR, Domain.NUMBER, Domain.INPUT_NUMBER ->
            formatSensorValue(raw) to entity.unit
        Domain.CLIMATE, Domain.WATER_HEATER -> {
            val t = entity.climateTargetTemperature
            if (t != null) formatNumber(t) to (entity.temperatureUnit ?: entity.unit)
            else (raw?.replace('_', ' ')?.uppercase() ?: "—") to null
        }
        Domain.SELECT, Domain.INPUT_SELECT ->
            (entity.currentOption ?: raw ?: "—") to null
        Domain.MEDIA_PLAYER -> (raw?.uppercase() ?: "—") to null
        else -> {
            // On/off + everything else: show the raw state word; if it's a bare on/off
            // collapse to ON/OFF, otherwise show HA's word verbatim (OPEN, LOCKED, ...).
            val word = raw?.replace('_', ' ')?.uppercase()
                ?: if (entity.isOn) "ON" else "OFF"
            word to null
        }
    }
}

private fun formatNumber(v: Double): String {
    val rounded = Math.round(v * 10.0) / 10.0
    return if (rounded == rounded.toLong().toDouble()) rounded.toLong().toString() else rounded.toString()
}

private fun kotlinx.serialization.json.JsonObject?.isNullOrEmptyJson(): Boolean =
    this == null || this.isEmpty()

// Raw-attribute readers for fields the repository doesn't parse into typed EntityState
// columns (hvac_action, current_humidity, rgb_color, ...). attributesJson is the verbatim
// HA attributes object, so these mirror HA's own attribute names exactly.
private fun EntityState.attrStr(key: String): String? =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonPrimitive)
        ?.content?.takeIf { it.isNotBlank() && it != "null" }

private fun EntityState.attrDouble(key: String): Double? =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()

private fun EntityState.attrIntList(key: String): List<Int>? =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }

// Light colour-mode names that imply true colour control (as opposed to brightness-only
// or colour-temperature-only). Mirrors HA's ColorMode enum members that carry chromaticity.
private val COLOR_CAPABLE_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

// Fixed palette surfaced as colour swatches when favourite colours aren't reachable.
// Each entry is a label + a hue in degrees; saturation is pinned at 100% by setLightHue.
private val COLOR_SWATCHES = listOf(
    "Red" to 0,
    "Orange" to 30,
    "Yellow" to 60,
    "Green" to 120,
    "Cyan" to 180,
    "Blue" to 240,
    "Purple" to 280,
    "Pink" to 320,
)

/** Render a hue (degrees, full saturation + value) as an opaque Compose [Color] for the
 *  swatch fill. Simple HSV→RGB at S=V=1. */
private fun hueToColor(hue: Int): Color {
    val h = ((hue % 360) + 360) % 360 / 60.0
    val x = 1.0 - Math.abs(h % 2.0 - 1.0)
    val (r, g, b) = when (h.toInt()) {
        0 -> Triple(1.0, x, 0.0)
        1 -> Triple(x, 1.0, 0.0)
        2 -> Triple(0.0, 1.0, x)
        3 -> Triple(0.0, x, 1.0)
        4 -> Triple(x, 0.0, 1.0)
        else -> Triple(1.0, 0.0, x)
    }
    return Color(r.toFloat(), g.toFloat(), b.toFloat())
}

/** True when an `rgb_color` triple is close (within a coarse tolerance) to [hue]'s
 *  fully-saturated colour, so the matching swatch reads as selected. Converts the RGB to
 *  a hue and compares within ±18°, ignoring near-white / near-black readings where hue is
 *  meaningless. */
private fun hueMatches(rgb: List<Int>, hue: Int): Boolean {
    if (rgb.size < 3) return false
    val r = rgb[0] / 255.0
    val g = rgb[1] / 255.0
    val b = rgb[2] / 255.0
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val delta = max - min
    if (delta < 0.12) return false // grey / white — no meaningful hue
    val h = when (max) {
        r -> 60.0 * (((g - b) / delta) % 6.0)
        g -> 60.0 * (((b - r) / delta) + 2.0)
        else -> 60.0 * (((r - g) / delta) + 4.0)
    }.let { ((it % 360.0) + 360.0) % 360.0 }
    val diff = Math.abs(h - hue).let { minOf(it, 360.0 - it) }
    return diff <= 18.0
}
