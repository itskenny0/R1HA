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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.github.itskenny0.r1ha.feature.moreinfo.rememberEntityHistory
import com.github.itskenny0.r1ha.feature.moreinfo.rememberWeatherForecasts
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import com.github.itskenny0.r1ha.feature.weather.ForecastKind
import com.github.itskenny0.r1ha.feature.weather.classifyForecastKind
import com.github.itskenny0.r1ha.feature.weather.formatForecastLabel
import com.github.itskenny0.r1ha.ui.components.AlarmPanel
import com.github.itskenny0.r1ha.ui.components.ClimatePanel
import com.github.itskenny0.r1ha.ui.components.CoverPanel
import com.github.itskenny0.r1ha.ui.components.CustomActionsPanel
import com.github.itskenny0.r1ha.ui.components.FanPanel
import com.github.itskenny0.r1ha.ui.components.FavoriteColorChips
import com.github.itskenny0.r1ha.ui.components.favoriteColorAction
import com.github.itskenny0.r1ha.ui.components.HumidifierPanel
import com.github.itskenny0.r1ha.ui.components.LawnMowerPanel
import com.github.itskenny0.r1ha.ui.components.LockPanel
import com.github.itskenny0.r1ha.ui.components.MediaExtrasPanel
import com.github.itskenny0.r1ha.ui.components.RemotePanel
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.SensorHistoryChart
import com.github.itskenny0.r1ha.ui.components.SkeletonBlock
import com.github.itskenny0.r1ha.ui.components.formatAbsoluteTimestamp
import com.github.itskenny0.r1ha.ui.components.rememberRelativeTime
import com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock
import com.github.itskenny0.r1ha.ui.components.VacuumPanel
import com.github.itskenny0.r1ha.ui.components.ValvePanel
import com.github.itskenny0.r1ha.ui.components.WaterHeaterPanel
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.util.optionLabel
import com.github.itskenny0.r1ha.ui.components.formatSensorValue
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/**
 * Ultra-detail "more info" bottom sheet for a single HA entity. A dim-scrim overlay
 * sized for the R1's ~240-320 dp screen (and larger tiers), scrollable, holding:
 *
 *  - a header (icon + friendly name + big state value),
 *  - the primary per-domain control (reusing the card-stack's [EntityPanels] plus a
 *    handful of inline controls for the high-value domains),
 *  - a compact history sparkline for numeric / sensor entities (forecast strip for
 *    weather, logbook-style recent activity for stateful on/off domains),
 *  - a full attribute dump (humanised keys, single-line ellipsised values), last.
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
    /** "Show more" hook — when set, the history embed offers a SHOW MORE chip that
     *  closes the sheet and opens the native History screen for the entity. Null
     *  (the default) hides the chip, so callers without a nav host stay valid. */
    onOpenHistory: ((entityId: String) -> Unit)? = null,
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
                        onOpenHistory = onOpenHistory,
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
    onOpenHistory: ((entityId: String) -> Unit)?,
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
        // Cross-cutting gate (gap 11): every primary control disables when the
        // entity is unavailable. HA renders the controls greyed (not hidden), so
        // the affordance still shows; we dim the whole control block and swallow
        // taps so a service can't be fired against a dead entity.
        val controlsEnabled = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
            .controlEnabled(entity.rawState, entity.isAvailable)
        val control: @Composable () -> Unit = { PrimaryControl(haRepository, entity, accent, dispatch) }
        if (controlsEnabled) {
            SectionWrap(control)
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // Swallow taps so disabled controls can't dispatch. hapticOnClick
                    // off so the dead block doesn't buzz under a stray tap.
                    .r1Pressable(onClick = {}, hapticOnClick = false)
                    .alpha(0.4f),
            ) {
                SectionWrap(control)
            }
        }

        // ── Weather forecast strip ─────────────────────────────────────────
        WeatherForecastSection(haRepository = haRepository, entity = entity, accent = accent)

        // ── History ───────────────────────────────────────────────────────
        HistorySection(
            haRepository = haRepository,
            entity = entity,
            accent = accent,
            onOpenHistory = onOpenHistory?.let { open -> { open(entity.id.value); onDismiss() } },
        )

        // ── Non-numeric recent activity ────────────────────────────────────
        RecentActivitySection(haRepository = haRepository, entity = entity)

        // ── Logbook (24h, collapsible) ─────────────────────────────────────
        LogbookSection(haRepository = haRepository, entity = entity)

        // ── Details (state block + YAML) ───────────────────────────────────
        DetailsSection(entity = entity)

        // ── Attributes ────────────────────────────────────────────────────
        // Last on purpose: the raw attribute dump is reference material, while
        // forecast/history/activity answer the questions a long-press usually
        // asks. Burying a weather entity's forecast below twenty attribute rows
        // forced a scroll past noise to reach the payoff; HA's own more-info
        // orders the same way (graph first, attributes at the bottom).
        AttributesSection(entity)

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
                text = "${domainLabel(entity.id.domain)}${entity.area?.takeIf { it.isNotBlank() }?.let { " · ${areaLabel(it)}" } ?: ""}",
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
            // live-ticking relative-time formatter. Tapping toggles to the
            // absolute local date-time so the user can see the exact timestamp.
            val changedAgo = rememberRelativeTime(entity.lastChanged)
            if (changedAgo.isNotEmpty()) {
                var showAbsolute by remember { mutableStateOf(false) }
                // Absolute rendering goes through the shared clock pipeline
                // (formatAbsoluteTimestamp + rememberUse24HourClock) so it
                // honours Settings, Appearance, Clock format like every other
                // readout, instead of a hard-coded 24-hour pattern. Not
                // remembered on purpose: the line already recomposes on every
                // relative-time tick, and "now" should move with it.
                val absoluteText = formatAbsoluteTimestamp(
                    at = entity.lastChanged,
                    now = Instant.now(),
                    zone = ZoneId.systemDefault(),
                    use24h = rememberUse24HourClock(),
                )
                Spacer(Modifier.height(R1.space.xxs))
                Text(
                    text = "CHANGED ${(if (showAbsolute) absoluteText else changedAgo).uppercase()}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.r1Pressable(
                        onClick = { showAbsolute = !showAbsolute },
                        hapticOnClick = false,
                        contentDescription = "Toggle timestamp format",
                    ),
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
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    when (entity.id.domain) {
        Domain.LIGHT -> LightControl(haRepository, entity, accent, dispatch)
        Domain.CLIMATE -> {
            ClimateStepper(entity, accent, dispatch)
            ClimateHumidityControl(entity, accent, dispatch)
            Spacer(Modifier.height(R1.space.s))
            ClimatePanel(state = entity, accent = accent)
        }
        Domain.WATER_HEATER -> {
            ClimateStepper(entity, accent, dispatch)
            WaterHeaterAwayToggle(entity, accent, dispatch)
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
            RegistryFavoritePositions(haRepository, entity, accent, dispatch, domain = "cover")
            Spacer(Modifier.height(R1.space.s))
            CoverPanel(state = entity, accent = accent)
        }
        Domain.VALVE -> {
            CoverControl(entity, accent, dispatch)
            RegistryFavoritePositions(haRepository, entity, accent, dispatch, domain = "valve")
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
            LockOpenControl(entity, accent, dispatch)
            LockPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
        }
        Domain.FAN -> {
            val supportsSetSpeed = entity.hasFanFeature(EntityState.FanFeature.SET_SPEED)
            val toggleOnly = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls.fanIsToggleOnly(
                supportsSetSpeed = supportsSetSpeed,
                hasPresetModes = entity.fanPresetModes.isNotEmpty(),
            )
            if (toggleOnly) {
                // No speed and no presets — the power toggle is the only control.
                ToggleRow(
                    entity = entity,
                    accent = accent,
                    onLabel = "ON",
                    offLabel = "OFF",
                    onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
                    onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
                    isOn = entity.isOn,
                )
            } else {
                val step = entity.fanPercentageStep
                if (step != null && step >= 25.0) {
                    // Discrete speed steps: compute the labeled percentages from step size.
                    FanDiscreteSpeedControl(entity = entity, accent = accent, dispatch = dispatch)
                } else {
                    PercentControl(
                        label = "SPEED",
                        pct = entity.percent ?: if (entity.isOn) 100 else 0,
                        accent = accent,
                        onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
                    )
                }
                // Power button beside the speed control — HA's more-info-fan always
                // shows on/off next to the speed dial so the fan can be stopped
                // without dragging speed to 0.
                Spacer(Modifier.height(R1.space.xs))
                ToggleRow(
                    entity = entity,
                    accent = accent,
                    onLabel = "ON",
                    offLabel = "OFF",
                    onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
                    onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
                    isOn = entity.isOn,
                )
            }
            Spacer(Modifier.height(R1.space.s))
            FanPanel(state = entity, accent = accent)
        }
        Domain.HUMIDIFIER -> {
            // Explicit on/off — HA's more-info-humidifier shows a dedicated power
            // toggle distinct from the target-humidity slider.
            ToggleRow(
                entity = entity,
                accent = accent,
                onLabel = "ON",
                offLabel = "OFF",
                onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
                onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
                isOn = entity.isOn,
            )
            Spacer(Modifier.height(R1.space.xs))
            PercentControl(
                label = "TARGET",
                pct = entity.percent ?: 0,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
            // action attribute (humidifying / drying / idle) — the live operating status.
            com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
                .humidifierActionLabel(entity.attrStr("action"))?.let { action ->
                    Spacer(Modifier.height(R1.space.xxs))
                    Text(text = action, style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                }
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
        // New domains.
        Domain.TEXT -> TextSetControl(entity, accent)
        Domain.DATE -> DateSetControl(entity, accent, dispatch)
        Domain.DATETIME -> DateTimeSetControl(entity, accent, dispatch)
        Domain.TIME -> TimeSetControl(entity, accent, dispatch)
        Domain.SIREN -> SirenControl(entity, accent, dispatch)
        Domain.IMAGE -> ImageControl(entity)
        // event: read-only; no primary control (attributes section surfaces event_type).
        Domain.EVENT -> Unit
        // weather: read-only; the forecast strip renders in its own section below attributes.
        Domain.WEATHER -> Unit
        // No primary control for read-only / unmodelled domains — the section
        // simply renders nothing (the host SectionWrap collapses it).
        else -> Unit
    }
    // User-defined custom actions are valid on any domain; render them last.
    CustomActionsPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
}

// ── Inline controls ───────────────────────────────────────────────────────────────────

@Composable
private fun LightControl(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        PercentControl(
            label = "BRIGHTNESS",
            pct = entity.percent ?: if (entity.isOn) 100 else 0,
            accent = accent,
            onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
        )
        val colorModes = entity.supportedColorModes.map { it.lowercase() }
        val supportsColor = colorModes.any { it in COLOR_CAPABLE_MODES }
        val supportsCt = colorModes.any { it == "color_temp" }
        // HA-style continuous colour controls: a circular HS wheel for colour-capable
        // bulbs and a black-body gradient slider for CCT bulbs. Both update LIVE
        // while the finger moves; per-move positions are funnelled through a
        // DebouncedCaller with the same 60 ms trailing / 150 ms force-fire windows
        // as the card stack's wheel debouncer, so HA sees ~6-8 service calls/sec
        // mid-drag and the trailing edge guarantees the exact release value.
        if (supportsColor || supportsCt) {
            val scope = rememberCoroutineScope()
            val throttled = remember(entity.id) {
                com.github.itskenny0.r1ha.core.ha.DebouncedCaller<String, ServiceCall>(
                    scope = scope,
                    debounceMillis = 60L,
                    maxIntervalMillis = 150L,
                ) { _, call -> dispatch(call) }
            }
            // Carry current brightness so tinting doesn't dim the bulb; omitted when
            // the bulb is off so the call can't accidentally flip it (same contract
            // as the card stack's CT/HUE dispatch).
            val carryBright = entity.percent?.takeIf { it > 0 }
            // Both axes supported: a small COLOR / TEMP toggle picks which control is
            // visible, defaulting to whichever matches the bulb's current color_mode
            // so opening the sheet shows the control that's actually driving the bulb.
            var showColorTab by remember(entity.id) {
                mutableStateOf(
                    when {
                        !supportsColor -> false
                        !supportsCt -> true
                        else -> !entity.attrStr("color_mode").equals("color_temp", ignoreCase = true)
                    },
                )
            }
            if (supportsColor && supportsCt) {
                ChipStrip {
                    DetailChip(label = "COLOR", accent = accent, selected = showColorTab) { showColorTab = true }
                    DetailChip(label = "TEMP", accent = accent, selected = !showColorTab) { showColorTab = false }
                }
            }
            if (showColorTab && supportsColor) {
                val hs = com.github.itskenny0.r1ha.ui.components.hsFromAttributes(entity.attributesJson)
                val onHs: (Float, Float) -> Unit = { h, s ->
                    scope.launch {
                        throttled.submit(
                            "hs",
                            ServiceCall.setLightHs(
                                entity.id,
                                h.toDouble(),
                                (s * 100f).toDouble(),
                                brightnessPct = carryBright,
                            ),
                        )
                    }
                }
                // Diameter capped at 260 dp so the wheel doesn't swallow the whole
                // sheet on larger tiers and the R1 keeps room to scroll past it.
                androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                    val wheelSize = if (maxWidth < 260.dp) maxWidth else 260.dp
                    com.github.itskenny0.r1ha.ui.components.ColorWheel(
                        // Saturation 0 (centred / white) when the bulb isn't currently
                        // reporting a colour; the wheel reconciles to the entity's
                        // echoed hs_color whenever a finger isn't down.
                        hue = hs?.first ?: entity.hue?.toFloat() ?: 0f,
                        saturation = hs?.second ?: 0f,
                        onHsChange = onHs,
                        onHsChangeFinished = onHs,
                        modifier = Modifier
                            .size(wheelSize)
                            .align(Alignment.Center),
                    )
                }
            }
            if (!showColorTab && supportsCt) {
                // Range falls back to HA's conventional 2000-6500 K when the
                // integration omits the min/max attributes.
                val minK = entity.minColorTempK ?: 2000
                val maxK = (entity.maxColorTempK ?: 6500).let { if (it > minK) it else minK + 1 }
                val current = entity.colorTempK?.coerceIn(minK, maxK) ?: ((minK + maxK) / 2)
                // Live readout beside the slider; resets to the echoed value whenever
                // HA confirms (same remember(current) idiom as PercentControl).
                var shownK by remember(current) { mutableStateOf(current) }
                val onKelvin: (Int) -> Unit = { k ->
                    shownK = k
                    scope.launch {
                        throttled.submit(
                            "ct",
                            ServiceCall.setLightColorTemp(entity.id, k, brightnessPct = carryBright),
                        )
                    }
                }
                Text(text = "WHITE TEMP", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    com.github.itskenny0.r1ha.ui.components.ColorTempSlider(
                        kelvin = current,
                        minKelvin = minK,
                        maxKelvin = maxK,
                        onKelvinChange = onKelvin,
                        onKelvinChangeFinished = onKelvin,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(R1.space.s))
                    Text(text = "${shownK}K", style = responsiveType(R1.labelMicro), color = accent)
                }
            }
        }
        // Colour swatches — HA's more-info exposes an RGB/HS picker plus favorite
        // colours. The favourite list lives in the entity-registry options (not the
        // state attributes the sheet can see), so we surface a fixed palette of useful
        // hues plus a small white-reset, dispatching via hs_color. Shown only when the
        // bulb advertises a colour mode beyond on/off + colour-temp.
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
        // White mode — HA's more-info-light offers a dedicated WHITE button for
        // bulbs that advertise LightColorMode.WHITE; it drives the white channel
        // (not a colour) at the bulb's current brightness.
        if (com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls.lightSupportsWhite(colorModes)) {
            val whiteLevel = EntityState.lightRawFromPct(entity.percent ?: 100)
            ChipStrip {
                DetailChip(
                    label = "WHITE",
                    accent = accent,
                    selected = entity.attrStr("color_mode").equals("white", ignoreCase = true),
                    onClick = {
                        dispatch(
                            ServiceCall(
                                entity.id,
                                "turn_on",
                                kotlinx.serialization.json.buildJsonObject {
                                    put("white", kotlinx.serialization.json.JsonPrimitive(whiteLevel))
                                },
                            ),
                        )
                    },
                )
            }
        }
        // Favourite colours — user-curated per-entity swatches from EntityOverride.favoriteColors.
        // Each swatch fires light.turn_on with rgb_color. Renders nothing when none configured.
        FavoriteColorChips(entity) { argb -> dispatch(favoriteColorAction(entity, argb)) }
        // Registry-backed favourite colours — HA's more-info reads the user's
        // stored favourites from the entity registry and falls back to a computed
        // default palette (per-temperature swatches + fixed colours) from the
        // bulb's capabilities. We surface those here.
        RegistryFavoriteColors(haRepository, entity, accent, dispatch)
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

/**
 * Registry-backed favourite colours for a light: reads the user's stored
 * favourites from the entity registry options, falling back to HA's computed
 * default palette ([MoreInfoFavorites.computeDefaultFavoriteColors]) when none are
 * stored. Each swatch fires the matching `light.turn_on` (rgb_color or
 * color_temp_kelvin). Renders nothing until the registry fetch resolves and only
 * when the bulb supports colour or colour-temperature at all.
 */
@Composable
private fun RegistryFavoriteColors(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    val colorModes = entity.supportedColorModes.map { it.lowercase() }
    val supportsColor = colorModes.any { it in COLOR_CAPABLE_MODES }
    val supportsCt = colorModes.any { it == "color_temp" }
    if (!supportsColor && !supportsCt) return
    val favorites by rememberRegistryFavorites(
        haRepository = haRepository,
        entityId = entity.id.value,
        domain = "light",
        enabled = true,
    )
    val stored = favorites ?: return
    val swatches = if (stored.colors.isNotEmpty()) {
        stored.colors
    } else {
        com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.computeDefaultFavoriteColors(
            supportsColorTemp = supportsCt,
            supportsColor = supportsColor,
            minColorTempK = entity.minColorTempK,
            maxColorTempK = entity.maxColorTempK,
        )
    }
    if (swatches.isEmpty()) return
    Text(text = "FAVOURITES", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
    ChipStrip {
        swatches.forEach { fav ->
            when (fav) {
                is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.Rgb -> ColorSwatch(
                    color = Color(fav.argb),
                    selected = false,
                    description = "Favourite colour",
                    onClick = { dispatch(favoriteColorAction(entity, fav.argb)) },
                )
                is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.ColorTemp -> ColorSwatch(
                    color = Color(com.github.itskenny0.r1ha.ui.components.kelvinToArgb(fav.kelvin)),
                    selected = entity.colorTempK == fav.kelvin,
                    description = "${fav.kelvin} K",
                    onClick = {
                        dispatch(
                            ServiceCall.setLightColorTemp(
                                entity.id,
                                fav.kelvin,
                                brightnessPct = entity.percent?.takeIf { it > 0 },
                            ),
                        )
                    },
                )
            }
        }
    }
}

@Composable
private fun MediaControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        // Album art — only when entity_picture is present. Mirrors the card-stack
        // MediaNowPlaying artwork slot; uses the same server URL + bearer token
        // locals so relative HA paths resolve correctly.
        if (!entity.mediaPicture.isNullOrBlank()) {
            AsyncBitmap(
                url = entity.mediaPicture,
                serverUrl = LocalHaServerUrl.current,
                bearerToken = LocalHaBearerToken.current,
                modifier = Modifier
                    .size(112.dp)
                    .clip(R1.ShapeS),
                contentDescription = "Album art",
            )
        }
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

/**
 * Registry-backed favourite position chips for a cover / valve. Reads the stored
 * favourites from the entity registry options, falling back to HA's
 * [MoreInfoFavorites.DEFAULT_POSITIONS] ([0, 25, 75, 100]) when none are stored.
 * Each chip fires `set_cover_position` / `set_valve_position`. Renders nothing
 * until the registry fetch resolves.
 */
@Composable
private fun RegistryFavoritePositions(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
    domain: String,
) {
    val favorites by rememberRegistryFavorites(
        haRepository = haRepository,
        entityId = entity.id.value,
        domain = domain,
        enabled = true,
    )
    val stored = favorites ?: return
    // Position support: cover/valve both gate the feature on a settable position.
    val supportsPosition = entity.supportsScalar || entity.percent != null
    val positions = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.resolvePositions(
        stored = stored.positions,
        supportsPosition = supportsPosition,
    )
    if (positions.isEmpty()) return
    Spacer(Modifier.height(R1.space.xs))
    Text(text = "FAVOURITES", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
    ChipStrip {
        positions.forEach { pos ->
            val service = if (domain == "valve") "set_valve_position" else "set_cover_position"
            DetailChip(
                label = "$pos%",
                accent = accent,
                selected = entity.percent == pos,
                onClick = {
                    dispatch(
                        ServiceCall(
                            entity.id,
                            service,
                            kotlinx.serialization.json.buildJsonObject {
                                put("position", kotlinx.serialization.json.JsonPrimitive(pos))
                            },
                        ),
                    )
                },
            )
        }
    }
    // Tilt favourites (cover only) when the registry carries them.
    if (domain == "cover" && stored.tiltPositions.isNotEmpty()) {
        Spacer(Modifier.height(R1.space.xs))
        Text(text = "TILT FAVOURITES", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        ChipStrip {
            stored.tiltPositions.forEach { tilt ->
                DetailChip(
                    label = "$tilt%",
                    accent = accent,
                    selected = entity.attrDouble("current_tilt_position")?.toInt() == tilt,
                    onClick = { dispatch(ServiceCall.coverSetTiltPosition(entity.id, tilt)) },
                )
            }
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
            if (action != null) add(optionLabel(action))
            if (nowTemp != null) add("NOW ${formatNumber(nowTemp)}$unit")
            if (nowHum != null) add("RH ${formatNumber(nowHum)}%")
        }
        if (parts.isNotEmpty()) {
            Text(text = parts.joinToString("  ·  "), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        }
    }
}

/**
 * Climate target-humidity slider — shown only when the thermostat advertises the
 * TARGET_HUMIDITY feature. Fires `climate.set_humidity`. Mirrors HA's
 * more-info-climate humidity control sitting beside the temperature control.
 */
@Composable
private fun ClimateHumidityControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    if (!com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
            .climateSupportsTargetHumidity(entity.supportedFeatures)
    ) {
        return
    }
    val target = entity.attrDouble("humidity")?.toInt() ?: 50
    Spacer(Modifier.height(R1.space.xs))
    PercentControl(
        label = "TARGET HUMIDITY",
        pct = target.coerceIn(0, 100),
        accent = accent,
        onChange = { pct ->
            dispatch(
                ServiceCall(
                    entity.id,
                    "set_humidity",
                    kotlinx.serialization.json.buildJsonObject {
                        put("humidity", kotlinx.serialization.json.JsonPrimitive(pct))
                    },
                ),
            )
        },
    )
}

/**
 * Water-heater away-mode toggle — shown when the heater advertises the AWAY_MODE
 * feature. Fires `water_heater.set_away_mode`. Mirrors HA's more-info-water_heater
 * away-mode switch.
 */
@Composable
private fun WaterHeaterAwayToggle(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    if (!com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
            .waterHeaterSupportsAway(entity.supportedFeatures)
    ) {
        return
    }
    val away = entity.attrStr("away_mode").equals("on", ignoreCase = true)
    Spacer(Modifier.height(R1.space.xs))
    Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
        DetailChip(
            label = if (away) "AWAY ON" else "AWAY OFF",
            accent = accent,
            selected = away,
            onClick = {
                dispatch(
                    ServiceCall(
                        entity.id,
                        "set_away_mode",
                        kotlinx.serialization.json.buildJsonObject {
                            put("away_mode", kotlinx.serialization.json.JsonPrimitive(!away))
                        },
                    ),
                )
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Lock open-door button with a confirm step — shown only when the lock advertises
 * LockEntityFeature.OPEN (unlatch). Unlatching a door is higher-risk than a plain
 * unlock, so the first tap arms a "CONFIRM OPEN" state that the second tap fires.
 * Fires `lock.open` with the `default_code` registry option when present (skips a
 * keypad we don't surface here).
 */
@Composable
private fun LockOpenControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    if (!com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
            .lockSupportsOpen(entity.supportedFeatures)
    ) {
        return
    }
    var armed by remember(entity.id) { mutableStateOf(false) }
    Spacer(Modifier.height(R1.space.xs))
    DetailChip(
        label = if (armed) "CONFIRM OPEN" else "OPEN DOOR",
        accent = accent,
        selected = armed,
        onClick = {
            if (!armed) {
                armed = true
            } else {
                armed = false
                val defaultCode = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
                    .lockDefaultCode(entity.attrStr("default_code"))
                dispatch(
                    ServiceCall(
                        entity.id,
                        "open",
                        if (defaultCode != null) {
                            kotlinx.serialization.json.buildJsonObject {
                                put("code", kotlinx.serialization.json.JsonPrimitive(defaultCode))
                            }
                        } else {
                            kotlinx.serialization.json.JsonObject(emptyMap())
                        },
                    ),
                )
            }
        },
        modifier = Modifier.fillMaxWidth(),
    )
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
                text = value?.let { formatNumber(it) + unit } ?: "-",
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
                text = value?.let { formatNumber(it) + unit } ?: "-",
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
private fun HistorySection(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    onOpenHistory: (() -> Unit)? = null,
) {
    // Numeric history only makes sense for entities whose state is a reading. Probe by
    // whether the current state parses as a number; sensors/number/climate all qualify.
    val numericNow = entity.rawState?.toDoubleOrNull() != null || entity.raw != null
    val sensorLike = entity.id.domain == Domain.SENSOR ||
        entity.id.domain == Domain.NUMBER ||
        entity.id.domain == Domain.INPUT_NUMBER ||
        entity.id.domain == Domain.CLIMATE ||
        entity.id.domain == Domain.WATER_HEATER ||
        entity.id.domain == Domain.HUMIDIFIER
    // HA routes sensors that carry a long-term `state_class` (measurement / total /
    // total_increasing) to the statistics-backed aggregate chart; everything else
    // numeric uses the raw line chart.
    val hasStatistics = entity.attrStr("state_class") != null
    val mode = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.chooseHistoryMode(
        numericNow = numericNow && sensorLike,
        hasStatistics = hasStatistics,
        supportsTimeline = false,
    )
    if (mode == com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.HistoryMode.NONE) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "HISTORY",
                style = responsiveType(R1.sectionHeader),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            if (onOpenHistory != null) {
                DetailChip(label = "SHOW MORE", accent = accent, onClick = onOpenHistory)
            }
        }
        when (mode) {
            com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.HistoryMode.STATISTICS ->
                StatisticsChart(haRepository = haRepository, entity = entity, accent = accent)
            else -> {
                val history by rememberHistory(haRepository, entity.id.value, enabled = true)
                when (val pts = history) {
                    null -> SectionLoadingPlaceholder("Loading history")
                    else -> SensorHistoryChart(points = pts, accent = accent, unit = entity.unit)
                }
            }
        }
    }
}

/**
 * Statistics-backed aggregate chart for sensors with a `state_class`. Draws the
 * mean line plus a min/max band via the shared [com.github.itskenny0.r1ha.ui.components.Sparkline].
 * Live-updates while the sheet is open (a per-minute tick re-fetches); a fetch
 * failure surfaces a quiet error instead of an empty box. Falls back to the raw
 * line chart when the recorder returns no buckets (sensor too new to have any).
 */
@Composable
private fun StatisticsChart(haRepository: HaRepository, entity: EntityState, accent: Color) {
    // Live tick: re-fetch every minute while the sheet stays open so an open
    // more-info reflects new recorder buckets (matches HA's subscribed history).
    var tick by remember(entity.id) { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(entity.id) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            tick++
        }
    }
    val stats by rememberStatistics(
        haRepository = haRepository,
        entityId = entity.id.value,
        enabled = true,
        refreshKey = tick,
    )
    when (val s = stats) {
        null -> SectionLoadingPlaceholder("Loading statistics")
        else -> when {
            s.error ->
                com.github.itskenny0.r1ha.ui.components.SparklinePlaceholder(
                    errorText = "Statistics unavailable",
                )
            s.buckets.isEmpty() -> {
                // Recorder has no buckets yet — fall back to the raw line so a new
                // sensor still shows its short live history.
                val history by rememberHistory(haRepository, entity.id.value, enabled = true)
                when (val pts = history) {
                    null -> SectionLoadingPlaceholder("Loading history")
                    else -> SensorHistoryChart(points = pts, accent = accent, unit = entity.unit)
                }
            }
            else -> {
                val series = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.statisticsSeries(s.buckets)
                val lines = buildList {
                    if (series.min.isNotEmpty()) {
                        add(
                            com.github.itskenny0.r1ha.ui.components.SparklineSeries(
                                samples = series.min,
                                color = accent.copy(alpha = 0.35f),
                                fill = false,
                            ),
                        )
                    }
                    if (series.max.isNotEmpty()) {
                        add(
                            com.github.itskenny0.r1ha.ui.components.SparklineSeries(
                                samples = series.max,
                                color = accent.copy(alpha = 0.35f),
                                fill = false,
                            ),
                        )
                    }
                    if (series.mean.isNotEmpty()) {
                        add(
                            com.github.itskenny0.r1ha.ui.components.SparklineSeries(
                                samples = series.mean,
                                color = accent,
                                fill = true,
                            ),
                        )
                    }
                }
                if (lines.isEmpty()) {
                    com.github.itskenny0.r1ha.ui.components.SparklinePlaceholder(
                        errorText = "No statistics",
                    )
                } else {
                    com.github.itskenny0.r1ha.ui.components.Sparkline(series = lines)
                }
                val unit = entity.unit
                if (!unit.isNullOrBlank()) {
                    Text(text = "MEAN · MIN/MAX · $unit", style = R1.labelMicro, color = R1.InkMuted)
                }
            }
        }
    }
}

/**
 * Forecast strip for `weather.*` entities. Shows a HOURLY / DAILY toggle when both
 * cadences are available, then a horizontally-scrollable row of forecast slots
 * (time label, condition slug, high temp, low temp, precip probability). Renders
 * nothing until [rememberWeatherForecasts] resolves; shows a loading placeholder in
 * the interim. Falls back to whichever cadence is available when only one exists.
 *
 * Uses [HaRepository.getWeatherForecasts] (the modern service path) with a legacy
 * `forecast` attribute fallback, mirroring WeatherViewModel.loadForecasts exactly.
 */
@Composable
private fun WeatherForecastSection(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
) {
    if (entity.id.domain != Domain.WEATHER) return
    val forecasts by rememberWeatherForecasts(
        haRepository = haRepository,
        entityId = entity.id.value,
        entityAttrs = entity.attributesJson,
        enabled = true,
    )
    // Null = still loading; non-null with both lists empty = no data (render nothing).
    val f = forecasts ?: run {
        // Loading state: show the section header + placeholder while fetching.
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
            Text(text = "FORECAST", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
            SectionLoadingPlaceholder("Loading forecast")
        }
        return
    }
    // Resolved but both cadences empty — no data at all; render nothing.
    if (f.hourly.isEmpty() && f.daily.isEmpty()) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "FORECAST", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
        val hasBoth = f.hourly.isNotEmpty() && f.daily.isNotEmpty()
        var showHourly by remember(f) { mutableStateOf(f.hourly.isNotEmpty()) }
        val activeEntries = if (showHourly) f.hourly else f.daily
        val kind = if (showHourly) ForecastKind.Hourly else ForecastKind.Daily
        if (hasBoth) {
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                DetailChip(
                    label = "HOURLY",
                    accent = accent,
                    selected = showHourly,
                    onClick = { showHourly = true },
                )
                DetailChip(
                    label = "DAILY",
                    accent = accent,
                    selected = !showHourly,
                    onClick = { showHourly = false },
                )
            }
        }
        val unit = entity.attrStr("temperature_unit") ?: ""
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            activeEntries.forEach { entry ->
                ForecastSlot(entry = entry, kind = kind, unit = unit, accent = accent)
            }
        }
    }
}

@Composable
private fun ForecastSlot(
    entry: ForecastEntry,
    kind: ForecastKind,
    unit: String,
    accent: Color,
) {
    val label = formatForecastLabel(
        entry.whenIso,
        kind,
        use24h = rememberUse24HourClock(),
    )
    Column(
        modifier = Modifier
            .widthIn(min = 48.dp)
            .clip(R1.ShapeS)
            .background(R1.Surface)
            .padding(horizontal = R1.space.s, vertical = R1.space.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        Text(text = label, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
        if (entry.condition.isNotBlank()) {
            Text(
                text = conditionGlyph(entry.condition),
                style = R1.numeralM,
                color = accent,
                maxLines = 1,
            )
        }
        val temp = entry.temperature
        if (temp != null) {
            Text(
                text = "${Math.round(temp)}$unit",
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
            )
        }
        val low = entry.tempLow
        if (low != null) {
            Text(
                text = "${Math.round(low)}$unit",
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
        val prob = entry.precipitationProbability
        if (prob != null && prob > 0) {
            Text(
                text = "$prob%",
                style = R1.labelMicro,
                color = R1.AccentCool,
                maxLines = 1,
            )
        }
    }
}

/** Map HA condition slug to a compact Unicode glyph. Unknown slugs get a neutral dot. */
private fun conditionGlyph(condition: String): String = when (condition.lowercase()) {
    "clear-night" -> "★"
    "cloudy" -> "●"
    "exceptional" -> "!"
    "fog" -> "~"
    "hail" -> "*"
    "lightning" -> "↯"
    "lightning-rainy" -> "↯"
    "partlycloudy" -> "◑"
    "pouring" -> "▼"
    "rainy" -> "·"
    "snowy" -> "❄"
    "snowy-rainy" -> "❄"
    "sunny" -> "○"
    "windy" -> ">"
    "windy-variant" -> ">>"
    else -> "·"
}

/**
 * Discrete speed chip row for fans with <= 4 speed steps (percentage_step >= 25).
 * Each chip fires `fan.set_percentage` with the computed step value. The current
 * percentage is highlighted as the selected chip. Chip layout mirrors SelectControl.
 */
@Composable
private fun FanDiscreteSpeedControl(
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    val step = entity.fanPercentageStep ?: return
    val steps = fanDiscreteSpeedSteps(step)
    if (steps.isEmpty()) return
    val currentPct = entity.percent ?: (if (entity.isOn) steps.last() else 0)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "SPEED", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        ChipStrip(wrap = true) {
            steps.forEach { pct ->
                DetailChip(
                    label = "$pct%",
                    accent = accent,
                    selected = entity.isOn && currentPct == pct,
                    onClick = { dispatch(ServiceCall.setPercent(entity.id, pct)) },
                )
            }
        }
    }
}

/**
 * Recent activity list for non-numeric entities. Shows the last ~10 state changes
 * fetched via [HaRepository.fetchHistory] in reverse-chronological order. Renders
 * only when the entity is non-numeric and belongs to a domain suited for a logbook
 * view (switch, binary_sensor, light, lock, cover, etc.). Numeric and sensor-like
 * entities are handled by [HistorySection]'s sparkline instead.
 */
@Composable
private fun RecentActivitySection(
    haRepository: HaRepository,
    entity: EntityState,
) {
    val numericNow = entity.rawState?.toDoubleOrNull() != null || entity.raw != null
    val sensorLike = entity.id.domain == Domain.SENSOR ||
        entity.id.domain == Domain.NUMBER ||
        entity.id.domain == Domain.INPUT_NUMBER ||
        entity.id.domain == Domain.CLIMATE ||
        entity.id.domain == Domain.WATER_HEATER ||
        entity.id.domain == Domain.HUMIDIFIER
    // Only show for non-numeric, non-sensor-like, supported domains that have meaningful
    // state transitions. Weather gets the forecast strip instead; read-only or action
    // domains have no state transitions worth showing.
    val showable = !numericNow && !sensorLike && entity.id.domain !in NON_LOGBOOK_DOMAINS
    val history by rememberEntityHistory(
        haRepository = haRepository,
        entityId = entity.id.value,
        enabled = showable,
        hours = 24,
    )
    if (!showable) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "RECENT ACTIVITY", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
        when (val pts = history) {
            null -> SectionLoadingPlaceholder("Loading activity")
            else -> {
                val rows = pts
                    .filter { it.state != "unknown" && it.state != "unavailable" }
                    .take(10)
                if (rows.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.Surface)
                            .padding(R1.space.m),
                    ) {
                        Text(text = "No activity in the last 24 h", style = responsiveType(R1.body), color = R1.InkMuted)
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.Surface)
                            .padding(R1.space.m),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        rows.forEach { pt ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
                            ) {
                                Text(
                                    text = optionLabel(pt.state),
                                    style = responsiveType(R1.bodyEmph),
                                    color = R1.Ink,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                val timeAgo = rememberRelativeTime(pt.timestamp)
                                Text(
                                    text = timeAgo.uppercase(),
                                    style = R1.labelMicro,
                                    color = R1.InkMuted,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Collapsible 24h logbook embed for non-continuous entities, mirroring HA's
 * `ha-more-info-logbook.ts`. Shown for the same domains as [RecentActivitySection]
 * (binary state transitions worth a log), collapsed by default so it never pushes
 * the controls off-screen; expanding fetches the per-entity logbook. The header
 * row is the expand toggle.
 *
 * Distinct from RecentActivitySection: that derives a state list from /api/history;
 * this is HA's logbook proper (carries automation triggers, context attribution,
 * and human-readable messages), fetched server-side filtered to the entity.
 */
@Composable
private fun LogbookSection(haRepository: HaRepository, entity: EntityState) {
    val numericNow = entity.rawState?.toDoubleOrNull() != null || entity.raw != null
    val sensorLike = entity.id.domain == Domain.SENSOR ||
        entity.id.domain == Domain.NUMBER ||
        entity.id.domain == Domain.INPUT_NUMBER ||
        entity.id.domain == Domain.CLIMATE ||
        entity.id.domain == Domain.WATER_HEATER ||
        entity.id.domain == Domain.HUMIDIFIER
    val showable = !numericNow && !sensorLike && entity.id.domain !in NON_LOGBOOK_DOMAINS
    if (!showable) return
    var expanded by remember(entity.id) { mutableStateOf(false) }
    val logbook by rememberEntityLogbook(
        haRepository = haRepository,
        entityId = entity.id.value,
        enabled = expanded,
    )
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .r1Pressable(
                    onClick = { expanded = !expanded },
                    hapticOnClick = false,
                    contentDescription = if (expanded) "Collapse logbook" else "Expand logbook",
                ),
        ) {
            Text(
                text = "LOGBOOK",
                style = responsiveType(R1.sectionHeader),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(text = if (expanded) "−" else "+", style = R1.numeralM, color = R1.InkSoft)
        }
        if (expanded) {
            when (val rows = logbook) {
                null -> SectionLoadingPlaceholder("Loading logbook")
                else -> {
                    val visible = rows.take(12)
                    if (visible.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.Surface)
                                .padding(R1.space.m),
                        ) {
                            Text(
                                text = "No logbook entries in the last 24 h",
                                style = responsiveType(R1.body),
                                color = R1.InkMuted,
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.Surface)
                                .padding(R1.space.m),
                            verticalArrangement = Arrangement.spacedBy(R1.space.s),
                        ) {
                            visible.forEach { row -> LogbookRow(row) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogbookRow(row: com.github.itskenny0.r1ha.core.ha.LogbookEntry) {
    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.message.ifBlank { "changed" },
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val by = row.contextName?.takeIf { it.isNotBlank() }
            if (by != null) {
                Text(
                    text = "via ${by}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val timeAgo = rememberRelativeTime(row.timestamp)
        Text(
            text = timeAgo.uppercase(),
            style = R1.labelMicro,
            color = R1.InkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Details pane (HA `ha-more-info-details.ts`): a state block (translated / raw
 * state, last-changed, last-updated) plus a read-only YAML-ish dump of state +
 * attributes. Collapsed by default behind a toggle so it stays the reference
 * material it is, never crowding the controls. The YAML is rendered by the pure
 * [com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.renderStateYaml].
 */
@Composable
private fun DetailsSection(entity: EntityState) {
    var expanded by remember(entity.id) { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .r1Pressable(
                    onClick = { expanded = !expanded },
                    hapticOnClick = false,
                    contentDescription = if (expanded) "Collapse details" else "Expand details",
                ),
        ) {
            Text(
                text = "DETAILS",
                style = responsiveType(R1.sectionHeader),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(text = if (expanded) "−" else "+", style = R1.numeralM, color = R1.InkSoft)
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(R1.Surface)
                    .padding(R1.space.m),
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                DetailRow("State", entity.rawState?.let { optionLabel(it) } ?: "-")
                entity.rawState?.let { DetailRow("Raw state", it) }
                // last_updated isn't parsed into EntityState; last_changed is the
                // timestamp HA's state-header surfaces, so the details block mirrors it.
                val changed = rememberRelativeTime(entity.lastChanged)
                if (changed.isNotEmpty()) DetailRow("Last changed", changed)
            }
            // Read-only YAML view of state + attributes.
            val yaml = remember(entity.rawState, entity.attributesJson) {
                com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoEmbeds.renderStateYaml(
                    rawState = entity.rawState,
                    attributes = entity.attributesJson,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .padding(R1.space.m),
            ) {
                Text(
                    text = yaml,
                    style = responsiveType(R1.body),
                    color = R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = responsiveType(R1.body),
            color = R1.InkMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(0.4f)
                .padding(end = R1.space.s),
        )
        Text(
            text = value,
            style = responsiveType(R1.body),
            color = R1.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(0.6f),
        )
    }
}

// Domains where state transitions are not meaningful to show in a logbook-style list.
// Weather gets the forecast strip; event/scene/script/button are action-only;
// image is a binary read-only URL; datetime/date/time are setpoints not events.
private val NON_LOGBOOK_DOMAINS = setOf(
    Domain.WEATHER,
    Domain.EVENT,
    Domain.SCENE,
    Domain.SCRIPT,
    Domain.BUTTON,
    Domain.INPUT_BUTTON,
    Domain.IMAGE,
    Domain.DATE,
    Domain.DATETIME,
    Domain.TIME,
    Domain.TEXT,
)

// ── Shared primitives ─────────────────────────────────────────────────────────────────

/**
 * Pulsing skeleton placeholder for the lazily-fetched sections (history, forecast,
 * recent activity). Same 40dp footprint the loaded content's surface starts at, so
 * the section doesn't jump when data lands; the pulse reads as "loading" without a
 * per-section spinner (matches the sprint-wide SkeletonList/SkeletonBlock idiom).
 * [description] keeps the placeholder announced once to TalkBack instead of as a
 * mute grey rectangle.
 */
@Composable
private fun SectionLoadingPlaceholder(description: String) {
    SkeletonBlock(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .semantics { contentDescription = description },
    )
}

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

/**
 * Compute the discrete speed percentages for a fan whose `percentage_step` is
 * [step]. Produces evenly-spaced steps that always end at exactly 100 with no
 * near-duplicates. The count is derived by rounding 100/step and clamping to
 * 1..8; each step value is round(i * 100 / count) so the last entry is always
 * exactly 100.
 *
 * Examples:
 *   step=33.0  -> [33, 67, 100]
 *   step=33.33 -> [33, 67, 100]
 *   step=25.0  -> [25, 50, 75, 100]
 *   step=50.0  -> [50, 100]
 *   step=100.0 -> [100]
 *
 * Returns an empty list for a zero or negative [step] (defensive against
 * malformed HA payloads).
 */
internal fun fanDiscreteSpeedSteps(percentageStep: Double): List<Int> {
    if (percentageStep <= 0.0) return emptyList()
    val count = Math.round(100.0 / percentageStep).toInt().coerceIn(1, 8)
    return (1..count).map { Math.round(it * 100.0 / count).toInt() }
}

private fun domainLabel(domain: Domain): String =
    optionLabel(domain.prefix.ifBlank { "ENTITY" })

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
            else (raw?.let { optionLabel(it) } ?: "-") to null
        }
        Domain.SELECT, Domain.INPUT_SELECT ->
            (entity.currentOption ?: raw ?: "-") to null
        Domain.MEDIA_PLAYER -> (raw?.uppercase() ?: "-") to null
        else -> {
            // On/off + everything else: show the raw state word; if it's a bare on/off
            // collapse to ON/OFF, otherwise show HA's word verbatim (OPEN, LOCKED, ...).
            val word = raw?.let { optionLabel(it) }
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

// ── New-domain inline controls ────────────────────────────────────────────────────────

/**
 * `text.*` more-info editor — mirrors input_text. Shows a text field pre-filled with
 * the current state; fires `text.set_value` on confirm. Pattern / min / max / mode
 * attributes are read from attributesJson when present so the editor is consistent with
 * HA's own validation (mode="password" is displayed as-is since the R1 has no keyboard
 * anyway; the value field still fills correctly). A missing state falls back to blank.
 */
@Composable
private fun TextSetControl(entity: EntityState, accent: Color) {
    val current = entity.rawState?.takeIf { it != "unknown" && it != "unavailable" } ?: ""
    val maxLen = entity.attrDouble("max")?.toInt() ?: 255
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "VALUE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        // Read-only display: the R1 exposes no soft keyboard, so a text.* value is
        // shown but not edited here; scripts/automations that set it reflect live
        // in the state row above.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeS)
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = current.ifBlank { "-" },
                style = responsiveType(R1.body),
                color = if (current.isBlank()) R1.InkMuted else R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Hint line: show min/max/pattern when present so the user knows constraints.
        val min = entity.attrDouble("min")?.toInt()
        val pattern = entity.attrStr("pattern")
        val hints = buildList {
            if (min != null && min > 0) add("min $min")
            if (maxLen < 255) add("max $maxLen")
            if (pattern != null) add("pattern: $pattern")
        }
        if (hints.isNotEmpty()) {
            Text(
                text = hints.joinToString("  ·  "),
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
    }
}

/**
 * `date.*` more-info — mirrors the date portion of input_datetime. Displays the current
 * date string (YYYY-MM-DD) and chips for +1 / -1 day, firing `date.set_value`.
 * Full date-picker UI would need a calendar widget beyond what's available here; the
 * stepper approach matches HA's input_datetime more-info concept of nudge controls.
 */
@Composable
private fun DateSetControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val current = entity.rawState?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "DATE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", "Previous day", accent, enabled = current != null) {
                if (current != null) {
                    val next = nudgeDate(current, -1)
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("date", kotlinx.serialization.json.JsonPrimitive(next))
                    }))
                }
            }
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = current ?: "-",
                style = responsiveType(R1.numeralM),
                color = accent,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.m))
            StepperButton("+", "Next day", accent, enabled = current != null) {
                if (current != null) {
                    val next = nudgeDate(current, +1)
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("date", kotlinx.serialization.json.JsonPrimitive(next))
                    }))
                }
            }
        }
    }
}

/**
 * `datetime.*` more-info — displays date + time and offers +/- one-minute and one-hour
 * steppers. Fires `datetime.set_value {datetime: "YYYY-MM-DD HH:MM:SS"}`.
 */
@Composable
private fun DateTimeSetControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    // HA datetime state string: "YYYY-MM-DD HH:MM:SS"
    val current = entity.rawState?.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}")) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "DATETIME", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        Text(
            text = current ?: "-",
            style = responsiveType(R1.numeralM),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChipStrip(wrap = false) {
            TransportButton("−1h", "One hour earlier", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("datetime", kotlinx.serialization.json.JsonPrimitive(nudgeDateTime(current, -3600)))
                    }))
                }
            }
            TransportButton("−1m", "One minute earlier", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("datetime", kotlinx.serialization.json.JsonPrimitive(nudgeDateTime(current, -60)))
                    }))
                }
            }
            TransportButton("+1m", "One minute later", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("datetime", kotlinx.serialization.json.JsonPrimitive(nudgeDateTime(current, +60)))
                    }))
                }
            }
            TransportButton("+1h", "One hour later", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("datetime", kotlinx.serialization.json.JsonPrimitive(nudgeDateTime(current, +3600)))
                    }))
                }
            }
        }
    }
}

/**
 * `time.*` more-info — displays current time-of-day and offers +/- one-minute and
 * one-hour steppers. Fires `time.set_value {time: "HH:MM:SS"}`.
 */
@Composable
private fun TimeSetControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val current = entity.rawState?.takeIf { it.matches(Regex("\\d{2}:\\d{2}:\\d{2}")) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "TIME", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        Text(
            text = current ?: "-",
            style = responsiveType(R1.numeralM),
            color = accent,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        ChipStrip(wrap = false) {
            TransportButton("−1h", "One hour earlier", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("time", kotlinx.serialization.json.JsonPrimitive(nudgeTime(current, -3600)))
                    }))
                }
            }
            TransportButton("−1m", "One minute earlier", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("time", kotlinx.serialization.json.JsonPrimitive(nudgeTime(current, -60)))
                    }))
                }
            }
            TransportButton("+1m", "One minute later", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("time", kotlinx.serialization.json.JsonPrimitive(nudgeTime(current, +60)))
                    }))
                }
            }
            TransportButton("+1h", "One hour later", accent) {
                if (current != null) {
                    dispatch(ServiceCall(entity.id, "set_value", kotlinx.serialization.json.buildJsonObject {
                        put("time", kotlinx.serialization.json.JsonPrimitive(nudgeTime(current, +3600)))
                    }))
                }
            }
        }
    }
}

/**
 * `siren.*` more-info control. Always renders ON/OFF toggle. When the entity
 * advertises `available_tones`, renders a scrollable chip row — each chip fires
 * `siren.turn_on { tone: <name> }`. When `is_volume_controllable` and
 * `volume_level` are present, renders a 0..100% volume slider that fires
 * `siren.turn_on { volume_level: <0..1> }`.
 */
@Composable
private fun SirenControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        ToggleRow(
            entity = entity,
            accent = accent,
            onLabel = "ON",
            offLabel = "OFF",
            isOn = entity.isOn,
            onOn = { dispatch(ServiceCall(entity.id, "turn_on", kotlinx.serialization.json.JsonObject(emptyMap()))) },
            onOff = { dispatch(ServiceCall(entity.id, "turn_off", kotlinx.serialization.json.JsonObject(emptyMap()))) },
        )
        if (entity.sirenAvailableTones.isNotEmpty()) {
            Text(text = "TONE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
            ChipStrip(wrap = true) {
                entity.sirenAvailableTones.forEach { tone ->
                    DetailChip(
                        label = com.github.itskenny0.r1ha.core.util.optionLabel(tone),
                        accent = accent,
                        selected = false,
                        onClick = {
                            dispatch(ServiceCall(
                                entity.id,
                                "turn_on",
                                kotlinx.serialization.json.buildJsonObject {
                                    put("tone", kotlinx.serialization.json.JsonPrimitive(tone))
                                },
                            ))
                        },
                    )
                }
            }
        }
        val volLevel = entity.sirenVolumeLevel
        if (volLevel != null) {
            val volPct = (volLevel.coerceIn(0.0, 1.0) * 100.0).toInt()
            PercentControl(
                label = "VOLUME",
                pct = volPct,
                accent = accent,
                onChange = { pct ->
                    dispatch(ServiceCall(
                        entity.id,
                        "turn_on",
                        kotlinx.serialization.json.buildJsonObject {
                            put("volume_level", kotlinx.serialization.json.JsonPrimitive(pct / 100.0))
                        },
                    ))
                },
            )
        }
    }
}

/**
 * `image.*` more-info — loads and displays the entity's image via AsyncBitmap.
 * Prefers `entity_picture` from attributes (the standard HA image path); falls
 * back to `/api/image_proxy/<entity_id>` which is the canonical image proxy
 * endpoint for image.* entities. No controls — image entities are read-only.
 */
@Composable
private fun ImageControl(entity: EntityState) {
    val serverUrl = LocalHaServerUrl.current
    val bearerToken = LocalHaBearerToken.current
    // entity_picture is the standard attr; image.* also supports /api/image_proxy/<id>
    val url = entity.attrStr("entity_picture")
        ?: "/api/image_proxy/${entity.id.value}"
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Text(text = "IMAGE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        AsyncBitmap(
            url = url,
            serverUrl = serverUrl,
            bearerToken = bearerToken,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 240.dp)
                .clip(R1.ShapeS),
            contentDescription = entity.friendlyName,
        )
    }
}

// ── Date/time nudge helpers (pure, no Compose) ────────────────────────────────────────

/** Nudge a "YYYY-MM-DD" string by [days] and return the new date string. Defensive:
 *  if the input isn't parseable the original string is returned unchanged. */
private fun nudgeDate(date: String, days: Int): String = runCatching {
    val ld = java.time.LocalDate.parse(date)
    ld.plusDays(days.toLong()).toString()
}.getOrDefault(date)

/** Nudge a "YYYY-MM-DD HH:MM:SS" datetime string by [seconds] and return the result. */
private fun nudgeDateTime(dt: String, seconds: Int): String = runCatching {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    val ldt = java.time.LocalDateTime.parse(dt, formatter)
    ldt.plusSeconds(seconds.toLong()).format(formatter)
}.getOrDefault(dt)

/** Nudge a "HH:MM:SS" time-of-day string by [seconds], wrapping at midnight. */
private fun nudgeTime(time: String, seconds: Int): String = runCatching {
    val formatter = java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")
    val lt = java.time.LocalTime.parse(time, formatter)
    lt.plusSeconds(seconds.toLong()).format(formatter)
}.getOrDefault(time)

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
