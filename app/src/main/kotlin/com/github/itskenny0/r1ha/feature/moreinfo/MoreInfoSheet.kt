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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
import com.github.itskenny0.r1ha.core.ha.EntityId
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
    /** Media-browse hook — when set, a media_player's more-info offers a BROWSE
     *  MEDIA button that closes the sheet and opens the media-browse screen with
     *  this player preselected. Null hides the button. */
    onOpenMediaBrowse: ((entityId: String) -> Unit)? = null,
    /** "Show more" hook for the logbook embed — when set, the logbook section
     *  offers a SHOW MORE chip that closes the sheet and opens the native Logbook
     *  screen scoped to the entity. Null hides the chip. */
    onOpenLogbook: ((entityId: String) -> Unit)? = null,
) {
    // Internal back-stack: opening a group member's more-info pushes its id here so
    // the system Back pops to the parent instead of dismissing the whole sheet.
    // Mirrors HA's ha-more-info-dialog breadcrumb behaviour adapted to the sheet.
    val stack = remember(entityId) { androidx.compose.runtime.mutableStateListOf(entityId) }
    val currentEntityId = stack.last()
    BackHandler(onBack = { if (stack.size > 1) stack.removeAt(stack.size - 1) else onDismiss() })

    val stateHolder by rememberMoreInfoState(haRepository, currentEntityId)
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
                    // Group members push onto the stack; a no-op self-push is filtered
                    // so a member that's already showing doesn't loop.
                    LocalMoreInfoNavigate provides { id -> if (id != stack.last()) stack.add(id) },
                ) {
                    MoreInfoContent(
                        haRepository = haRepository,
                        entity = entity,
                        dispatch = dispatch,
                        onDismiss = { if (stack.size > 1) stack.removeAt(stack.size - 1) else onDismiss() },
                        onOpenHistory = onOpenHistory,
                        onOpenMediaBrowse = onOpenMediaBrowse,
                        onOpenLogbook = onOpenLogbook,
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
    onOpenMediaBrowse: ((entityId: String) -> Unit)? = null,
    onOpenLogbook: ((entityId: String) -> Unit)? = null,
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

        // ── Lifecycle alert ───────────────────────────────────────────────
        // HA shows a banner when an entity is unavailable / restored / in an
        // unknown state. The `restored` flag isn't surfaced into EntityState, so we
        // drive the banner off the raw state alone (unavailable / unknown).
        EntityAlertBanner(entity)

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

        // ── Browse media ───────────────────────────────────────────────────
        // HA's more-info-media_player offers a "Browse media" entry point for a
        // player advertising BROWSE_MEDIA. When a nav host is wired, surface a
        // button that closes the sheet and opens the media-browse screen with
        // this player preselected.
        if (onOpenMediaBrowse != null &&
            domain == Domain.MEDIA_PLAYER &&
            entity.hasMediaFeature(EntityState.MediaPlayerFeature.BROWSE_MEDIA)
        ) {
            ActionButton(label = "BROWSE MEDIA", accent = accent) {
                onOpenMediaBrowse(entity.id.value)
                onDismiss()
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
        LogbookSection(
            haRepository = haRepository,
            entity = entity,
            accent = accent,
            onOpenLogbook = onOpenLogbook?.let { open -> { open(entity.id.value); onDismiss() } },
        )

        // ── Related (same device + same area) ──────────────────────────────
        RelatedSection(haRepository = haRepository, entity = entity, accent = accent)

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
        Domain.SWITCH, Domain.INPUT_BOOLEAN -> ToggleRow(
            entity = entity,
            accent = accent,
            onLabel = "ON",
            offLabel = "OFF",
            onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
            onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
            isOn = entity.isOn,
        )
        Domain.AUTOMATION -> AutomationControl(entity, accent, dispatch)
        Domain.NUMBER, Domain.INPUT_NUMBER -> NumberStepper(entity, accent, dispatch)
        Domain.SELECT, Domain.INPUT_SELECT -> SelectControl(entity, accent, dispatch)
        Domain.VACUUM -> {
            VacuumStatusLine(entity)
            VacuumButtons(entity, accent, dispatch)
            VacuumPanel(state = entity, accent = accent, modifier = Modifier.padding(top = R1.space.s))
        }
        Domain.LAWN_MOWER -> {
            VacuumStatusLine(entity)
            LawnMowerPanel(state = entity, accent = accent)
        }
        Domain.REMOTE -> {
            RemoteActivityControl(entity, accent, dispatch)
            RemotePanel(state = entity, accent = accent)
        }
        // Alarm panels previously fell through to the no-control branch. The
        // AlarmPanel renders the disarm + arm-mode keypad section (PIN-gated when
        // the integration sets code_format), bringing parity with HA's
        // more-info-alarm_control_panel. The phase banner above adds the
        // arming/pending/triggered countdown HA shows during a transition.
        Domain.ALARM_CONTROL_PANEL -> {
            AlarmPhaseBanner(entity, accent)
            AlarmPanel(state = entity, accent = accent)
        }
        Domain.SCRIPT -> ScriptControl(entity, accent, dispatch)
        Domain.SCENE -> ActionButton("ACTIVATE", accent) {
            dispatch(ServiceCall(entity.id, "turn_on", kotlinx.serialization.json.JsonObject(emptyMap())))
        }
        Domain.COUNTER -> CounterControl(entity, accent, dispatch)
        Domain.TIMER -> TimerControl(entity, accent, dispatch)
        Domain.UPDATE -> UpdateControl(haRepository, entity, accent, dispatch)
        Domain.PERSON -> PersonLocationControl(entity, accent)
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
        // OTHER covers domains the app has no archetype for (camera, group, sun,
        // device_tracker, ...). Branch on the raw prefix so those still get the
        // high-value control HA's more-info shows.
        Domain.OTHER -> when (entity.id.value.substringBefore('.')) {
            "camera" -> CameraControl(entity, accent)
            "group" -> GroupControl(haRepository, entity, accent, dispatch)
            "sun" -> SunControl(entity)
            "device_tracker" -> PersonLocationControl(entity, accent)
            else -> UnmodelledToggle(entity, accent, dispatch)
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
        // RGBW / RGBWW white-channel + color-brightness sliders — HA's
        // light-color-rgb-picker shows a "color brightness" slider plus one (rgbw)
        // or two (rgbww cold/warm) white-channel sliders when the bulb advertises
        // those modes. Each call preserves the rest of the colour payload.
        if (com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls.lightSupportsWhiteChannel(colorModes)) {
            val controls = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls
            val currentRgb = entity.attrIntList("rgb_color")
            // Color brightness: scales the rgb part, preserving the white channels.
            val colorBrightPct = run {
                val maxRgb = currentRgb?.take(3)?.maxOrNull()
                controls.whiteChannelPercent(maxRgb) ?: 100
            }
            PercentControl(
                label = "COLOR BRIGHTNESS",
                pct = colorBrightPct.coerceIn(0, 100),
                accent = accent,
                onChange = { pct ->
                    val rgb = controls.adjustColorBrightness(currentRgb, pct)
                    dispatch(setRgbColorPreservingWhite(entity, controls, colorModes, rgb))
                },
            )
            if (controls.lightSupportsRgbww(colorModes)) {
                val rgbww = entity.attrIntList("rgbww_color")
                val cwPct = controls.whiteChannelPercent(rgbww?.getOrNull(3)) ?: 0
                val wwPct = controls.whiteChannelPercent(rgbww?.getOrNull(4)) ?: 0
                PercentControl(
                    label = "COLD WHITE",
                    pct = cwPct,
                    accent = accent,
                    onChange = { pct ->
                        dispatch(
                            rgbwwWhiteCall(
                                entity,
                                controls.rgbwwColorForWhite(rgbww, com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls.RgbwwChannel.COLD, pct),
                            ),
                        )
                    },
                )
                PercentControl(
                    label = "WARM WHITE",
                    pct = wwPct,
                    accent = accent,
                    onChange = { pct ->
                        dispatch(
                            rgbwwWhiteCall(
                                entity,
                                controls.rgbwwColorForWhite(rgbww, com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls.RgbwwChannel.WARM, pct),
                            ),
                        )
                    },
                )
            } else {
                val rgbw = entity.attrIntList("rgbw_color")
                val wPct = controls.whiteChannelPercent(rgbw?.getOrNull(3)) ?: 0
                PercentControl(
                    label = "WHITE",
                    pct = wPct,
                    accent = accent,
                    onChange = { pct ->
                        dispatch(
                            rgbwWhiteCall(
                                entity,
                                controls.rgbwColorForWhite(currentRgb, pct),
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

/** `light.turn_on { rgbw_color: [...] }`. */
private fun rgbwWhiteCall(entity: EntityState, rgbw: List<Int>): ServiceCall =
    ServiceCall(
        entity.id,
        "turn_on",
        kotlinx.serialization.json.buildJsonObject {
            put("rgbw_color", kotlinx.serialization.json.buildJsonArray {
                rgbw.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        },
    )

/** `light.turn_on { rgbww_color: [...] }`. */
private fun rgbwwWhiteCall(entity: EntityState, rgbww: List<Int>): ServiceCall =
    ServiceCall(
        entity.id,
        "turn_on",
        kotlinx.serialization.json.buildJsonObject {
            put("rgbww_color", kotlinx.serialization.json.buildJsonArray {
                rgbww.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        },
    )

/**
 * Color-brightness apply: scale the rgb part (already adjusted), then re-attach
 * the bulb's existing white channels so an rgbww/rgbw bulb keeps its whites.
 * Mirrors HA's `_setRgbWColor`: an rgbww bulb sends `rgbww_color = rgb + current
 * whites`, an rgbw bulb sends `rgbw_color = rgb + current white`, a plain rgb
 * bulb sends `rgb_color`.
 */
private fun setRgbColorPreservingWhite(
    entity: EntityState,
    controls: com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoControls,
    colorModes: List<String>,
    rgb: List<Int>,
): ServiceCall = when {
    controls.lightSupportsRgbww(colorModes) -> {
        val whites = entity.attrIntList("rgbww_color")
        rgbwwWhiteCall(entity, rgb.take(3) + listOf(whites?.getOrNull(3) ?: 0, whites?.getOrNull(4) ?: 0))
    }
    controls.lightSupportsRgbw(colorModes) -> {
        val white = entity.attrIntList("rgbw_color")?.getOrNull(3) ?: 0
        rgbwWhiteCall(entity, rgb.take(3) + white)
    }
    else -> ServiceCall(
        entity.id,
        "turn_on",
        kotlinx.serialization.json.buildJsonObject {
            put("rgb_color", kotlinx.serialization.json.buildJsonArray {
                rgb.take(3).forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
        },
    )
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
    val scope = rememberCoroutineScope()
    var editing by remember(entity.id) { mutableStateOf(false) }
    var confirmReset by remember(entity.id) { mutableStateOf(false) }
    var copyOpen by remember(entity.id) { mutableStateOf(false) }
    var report by remember(entity.id) { mutableStateOf<com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.CopyReport?>(null) }
    // Working copy while editing; seeded from the stored swatches (defaults are
    // read-only until the user edits, at which point they materialise as stored).
    var working by remember(entity.id, stored.colors) { mutableStateOf(swatches) }
    // Index being edited via the picker overlay; -1 = picking a NEW colour to
    // append; null = picker closed.
    var pickerIndex by remember(entity.id) { mutableStateOf<Int?>(null) }

    // Persist the working colours to the registry, invalidate the cache, refetch.
    val persist: (List<com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor>) -> Unit = { colors ->
        scope.launch {
            haRepository.updateEntityRegistryOptions(
                entity.id.value,
                "light",
                com.github.itskenny0.r1ha.feature.moreinfo.encodeFavoriteColors(colors),
            )
            com.github.itskenny0.r1ha.feature.dashboards.cards.EntityRegistryOptionsCache
                .invalidate(entity.id.value)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "FAVOURITES",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            modifier = Modifier.weight(1f),
        )
        DetailChip(label = if (editing) "DONE" else "EDIT", accent = accent, selected = editing) {
            editing = !editing
            if (editing) working = swatches
        }
    }
    // Removal while editing is the explicit x badge; tapping the swatch body
    // opens the picker to EDIT that favourite in place. Outside edit mode a tap
    // applies the colour to the light, as before.
    val removeAt: (Int) -> Unit = { index ->
        working = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites
            .removeColorAt(working, index)
        persist(working)
    }
    ChipStrip {
        (if (editing) working else swatches).forEachIndexed { index, fav ->
            when (fav) {
                is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.Rgb -> ColorSwatch(
                    color = Color(fav.argb),
                    selected = false,
                    description = if (editing) "Edit colour" else "Favourite colour",
                    onClick = {
                        if (editing) {
                            pickerIndex = index
                        } else {
                            dispatch(favoriteColorAction(entity, fav.argb))
                        }
                    },
                    onRemove = if (editing) ({ removeAt(index) }) else null,
                )
                is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.ColorTemp -> ColorSwatch(
                    color = Color(com.github.itskenny0.r1ha.ui.components.kelvinToArgb(fav.kelvin)),
                    selected = !editing && entity.colorTempK == fav.kelvin,
                    description = if (editing) "Edit ${fav.kelvin} K" else "${fav.kelvin} K",
                    onClick = {
                        if (editing) {
                            pickerIndex = index
                        } else {
                            dispatch(
                                ServiceCall.setLightColorTemp(
                                    entity.id,
                                    fav.kelvin,
                                    brightnessPct = entity.percent?.takeIf { it > 0 },
                                ),
                            )
                        }
                    },
                    onRemove = if (editing) ({ removeAt(index) }) else null,
                )
            }
        }
        if (editing && supportsColor) {
            AddSwatchChip { pickerIndex = -1 }
        }
    }
    if (editing) {
        ChipStrip {
            DetailChip(label = "RESET", accent = R1.StatusAmber) { confirmReset = true }
            DetailChip(label = "COPY TO", accent = accent) { copyOpen = true }
        }
    }
    // Picker overlay: kelvin favourites edit on the temperature slider (staying
    // kelvin entries); rgb favourites and new colours edit on the HS wheel.
    pickerIndex?.let { idx ->
        val editingFav = working.getOrNull(idx)
        val apply: (com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor) -> Unit = { picked ->
            working = if (idx >= 0) {
                com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites
                    .replaceColorAt(working, idx, picked)
            } else {
                com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites
                    .appendColor(working, picked)
            }
            persist(working)
            pickerIndex = null
        }
        if (editingFav is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.ColorTemp) {
            com.github.itskenny0.r1ha.ui.components.KelvinPickerOverlaySheet(
                title = entity.friendlyName.ifBlank { entity.id.value },
                initialKelvin = editingFav.kelvin,
                minKelvin = entity.minColorTempK ?: 2000,
                maxKelvin = entity.maxColorTempK ?: 6500,
                onConfirm = { k ->
                    apply(com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.ColorTemp(k))
                },
                onDismiss = { pickerIndex = null },
            )
        } else {
            val seed = when {
                editingFav is com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.Rgb ->
                    com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.argbToHs(editingFav.argb)
                else -> (entity.hue?.toFloat() ?: 30f) to 1f
            }
            com.github.itskenny0.r1ha.ui.components.ColorPickerOverlaySheet(
                title = entity.friendlyName.ifBlank { entity.id.value },
                initialHue = seed.first,
                initialSaturation = seed.second,
                onConfirm = { h, s ->
                    apply(
                        com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor.Rgb(
                            com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.hsToArgb(h, s),
                        ),
                    )
                },
                onDismiss = { pickerIndex = null },
            )
        }
    }
    // Reset-to-defaults confirm: clears the stored favourites so the computed
    // defaults take over again.
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset favourites?",
            message = "Restore the default favourite colours for ${entity.friendlyName.ifBlank { entity.id.value }}.",
            confirmLabel = "RESET",
            accent = R1.StatusAmber,
            onConfirm = {
                confirmReset = false
                editing = false
                // Persisting an empty list clears the stored option; the section
                // then falls back to the computed defaults.
                persist(emptyList())
            },
            onDismiss = { confirmReset = false },
        )
    }
    // Copy-to-compatible-entities: pick targets, then write the current swatches
    // to each and report partial failures.
    if (copyOpen) {
        CopyFavoritesDialog(
            haRepository = haRepository,
            sourceEntity = entity,
            colors = working,
            accent = accent,
            onDismiss = { copyOpen = false },
            onDone = { result ->
                copyOpen = false
                report = result
            },
        )
    }
    report?.let { r ->
        ConfirmDialog(
            title = if (r.allOk) "Copied" else "Copied with errors",
            message = if (r.allOk) {
                "Favourites copied to ${r.succeeded.size} ${if (r.succeeded.size == 1) "entity" else "entities"}."
            } else {
                "Copied to ${r.succeeded.size} of ${r.total}. Failed: ${r.failed.joinToString(", ")}."
            },
            confirmLabel = "OK",
            accent = accent,
            onConfirm = { report = null },
            onDismiss = { report = null },
        )
    }
}

/** A small confirm dialog reused by the favourites editor (reset + copy report). */
@Composable
private fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    accent: Color,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(R1.ShapeM)
                .background(R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(R1.space.l),
            verticalArrangement = Arrangement.spacedBy(R1.space.m),
        ) {
            Text(text = title, style = responsiveType(R1.bodyEmph), color = accent)
            Text(text = message, style = responsiveType(R1.body), color = R1.Ink)
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                DetailChip(label = "CANCEL", accent = R1.InkSoft) { onDismiss() }
                DetailChip(label = confirmLabel, accent = accent, selected = true) { onConfirm() }
            }
        }
    }
}

/**
 * Copy-favourites target picker: lists every same-domain entity that shares the
 * light colour capability, lets the user multi-select, writes the [colors] to each
 * selected entity's registry, and reports partial failures back to the caller.
 */
@Composable
private fun CopyFavoritesDialog(
    haRepository: HaRepository,
    sourceEntity: EntityState,
    colors: List<com.github.itskenny0.r1ha.feature.moreinfo.FavoriteColor>,
    accent: Color,
    onDismiss: () -> Unit,
    onDone: (com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.CopyReport) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val sourceDomain = sourceEntity.id.domain.prefix
    val candidates by androidx.compose.runtime.produceState<List<EntityState>>(emptyList(), sourceEntity.id) {
        value = haRepository.listAllEntities().getOrDefault(emptyList()).filter { cand ->
            cand.id.value != sourceEntity.id.value &&
                com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.canCopyTo(
                    sourceDomain = sourceDomain,
                    candidateDomain = cand.id.domain.prefix,
                    // Light copy needs a colour-capable target.
                    capabilityOk = cand.supportedColorModes.any {
                        it.lowercase() in COLOR_CAPABLE_MODES || it.lowercase() == "color_temp"
                    },
                )
        }
    }
    val selected = remember { androidx.compose.runtime.mutableStateMapOf<String, Boolean>() }
    var busy by remember { mutableStateOf(false) }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(R1.ShapeM)
                .background(R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(R1.space.l)
                .heightIn(max = 420.dp),
            verticalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            Text(text = "Copy favourites to", style = responsiveType(R1.bodyEmph), color = accent)
            if (candidates.isEmpty()) {
                Text(text = "No compatible entities.", style = responsiveType(R1.body), color = R1.InkMuted)
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    candidates.forEach { cand ->
                        val on = selected[cand.id.value] == true
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.Surface)
                                .r1Pressable(onClick = { selected[cand.id.value] = !on }, hapticOnClick = false)
                                .padding(horizontal = R1.space.m, vertical = R1.space.s),
                        ) {
                            Text(
                                text = cand.friendlyName.ifBlank { cand.id.value },
                                style = responsiveType(R1.body),
                                color = R1.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            Text(text = if (on) "✓" else "+", style = R1.numeralM, color = if (on) accent else R1.InkMuted)
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                DetailChip(label = "CANCEL", accent = R1.InkSoft) { onDismiss() }
                val targets = selected.filter { it.value }.keys.toList()
                DetailChip(
                    label = if (busy) "COPYING" else "COPY",
                    accent = accent,
                    selected = targets.isNotEmpty(),
                ) {
                    if (targets.isEmpty() || busy) return@DetailChip
                    busy = true
                    scope.launch {
                        val results = targets.map { id ->
                            val ok = haRepository.updateEntityRegistryOptions(
                                id,
                                "light",
                                com.github.itskenny0.r1ha.feature.moreinfo.encodeFavoriteColors(colors),
                            ).isSuccess
                            if (ok) {
                                com.github.itskenny0.r1ha.feature.dashboards.cards.EntityRegistryOptionsCache
                                    .invalidate(id)
                            }
                            id to ok
                        }
                        onDone(
                            com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites.summariseCopy(results),
                        )
                    }
                }
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
        // Position / seek — HA's more-info shows a draggable progress bar when the
        // player advertises SEEK and reports a duration. We interpolate the live
        // position off the last-reported anchor and fire media_seek on release.
        MediaSeekControl(entity, accent, dispatch)
        // Volume + mute.
        val volPct = entity.percent
        if (volPct != null && entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_SET)) {
            PercentControl(
                label = "VOLUME",
                pct = volPct,
                accent = accent,
                onChange = { dispatch(ServiceCall.setPercent(entity.id, it)) },
            )
        } else if (entity.hasMediaFeature(EntityState.MediaPlayerFeature.VOLUME_STEP)) {
            // Step-only players (no absolute set): up/down buttons, matching HA's
            // fallback when VOLUME_SET is absent but VOLUME_STEP is advertised.
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
                TransportButton("VOL −", "Volume down", accent, weighted = true) {
                    dispatch(ServiceCall.mediaTransport(entity.id, MediaTransport.VOLUME_DOWN))
                }
                TransportButton("VOL +", "Volume up", accent, weighted = true) {
                    dispatch(ServiceCall.mediaTransport(entity.id, MediaTransport.VOLUME_UP))
                }
            }
        }
        // Power — HA shows turn_on / turn_off when the player advertises them, so a
        // player that's off can be woken and a playing one fully powered down.
        val hasOn = entity.hasMediaFeature(EntityState.MediaPlayerFeature.TURN_ON)
        val hasOff = entity.hasMediaFeature(EntityState.MediaPlayerFeature.TURN_OFF)
        if (hasOn || hasOff) {
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
                if (hasOn) {
                    DetailChip(label = "TURN ON", accent = accent, selected = entity.isOn, modifier = Modifier.weight(1f)) {
                        dispatch(ServiceCall(entity.id, "turn_on", kotlinx.serialization.json.JsonObject(emptyMap())))
                    }
                }
                if (hasOff) {
                    DetailChip(label = "TURN OFF", accent = accent, selected = !entity.isOn, modifier = Modifier.weight(1f)) {
                        dispatch(ServiceCall(entity.id, "turn_off", kotlinx.serialization.json.JsonObject(emptyMap())))
                    }
                }
            }
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
    val scope = rememberCoroutineScope()
    var editing by remember(entity.id) { mutableStateOf(false) }
    var confirmReset by remember(entity.id) { mutableStateOf(false) }
    var working by remember(entity.id, stored.positions) { mutableStateOf(positions) }
    val persist: (List<Int>) -> Unit = { list ->
        scope.launch {
            haRepository.updateEntityRegistryOptions(
                entity.id.value,
                domain,
                com.github.itskenny0.r1ha.feature.moreinfo.encodeFavoritePositions(list),
            )
            com.github.itskenny0.r1ha.feature.dashboards.cards.EntityRegistryOptionsCache
                .invalidate(entity.id.value)
        }
    }
    Spacer(Modifier.height(R1.space.xs))
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "FAVOURITES",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            modifier = Modifier.weight(1f),
        )
        DetailChip(label = if (editing) "DONE" else "EDIT", accent = accent, selected = editing) {
            editing = !editing
            if (editing) working = positions
        }
    }
    ChipStrip {
        (if (editing) working else positions).forEach { pos ->
            val service = if (domain == "valve") "set_valve_position" else "set_cover_position"
            DetailChip(
                label = if (editing) "$pos% ✕" else "$pos%",
                accent = accent,
                selected = !editing && entity.percent == pos,
                onClick = {
                    if (editing) {
                        working = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites
                            .removePosition(working, pos)
                        persist(working)
                    } else {
                        dispatch(
                            ServiceCall(
                                entity.id,
                                service,
                                kotlinx.serialization.json.buildJsonObject {
                                    put("position", kotlinx.serialization.json.JsonPrimitive(pos))
                                },
                            ),
                        )
                    }
                },
            )
        }
    }
    if (editing) {
        ChipStrip {
            // Add the entity's CURRENT position as a new favourite.
            entity.percent?.let { cur ->
                DetailChip(label = "ADD $cur%", accent = accent) {
                    working = com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoFavorites
                        .addPosition(working, cur)
                    persist(working)
                }
            }
            DetailChip(label = "RESET", accent = R1.StatusAmber) { confirmReset = true }
        }
    }
    if (confirmReset) {
        ConfirmDialog(
            title = "Reset favourites?",
            message = "Restore the default favourite positions for ${entity.friendlyName.ifBlank { entity.id.value }}.",
            confirmLabel = "RESET",
            accent = R1.StatusAmber,
            onConfirm = {
                confirmReset = false
                editing = false
                persist(emptyList())
            },
            onDismiss = { confirmReset = false },
        )
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
private fun LogbookSection(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color = R1.AccentWarm,
    /** SHOW MORE hook: closes the sheet and opens the native Logbook scoped to
     *  this entity. Null hides the chip. */
    onOpenLogbook: (() -> Unit)? = null,
) {
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
                    // SHOW MORE: open the full Logbook scoped to this entity, the
                    // logbook analogue of the history embed's SHOW MORE.
                    if (onOpenLogbook != null) {
                        ChipStrip {
                            DetailChip(label = "SHOW MORE", accent = accent, onClick = onOpenLogbook)
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
    onRemove: (() -> Unit)? = null,
) {
    Box {
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
        // Explicit removal affordance while editing: a corner x badge, so the
        // swatch body stays the edit target and a tap can never delete by
        // accident. Slightly oversized hit area for the small badge.
        if (onRemove != null) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(18.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, androidx.compose.foundation.shape.CircleShape)
                    .r1Pressable(onClick = onRemove, contentDescription = "Remove favourite"),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "x", style = R1.labelMicro, color = R1.StatusRed)
            }
        }
    }
}

/** The + chip ending the favourites strip in edit mode: opens the colour picker
 *  to append a user-defined favourite. */
@Composable
private fun AddSwatchChip(onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = "Add favourite colour"),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "+", style = R1.numeralM, color = R1.InkSoft)
    }
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

private fun EntityState.attrStringList(key: String): List<String> =
    (attributesJson?.get(key) as? kotlinx.serialization.json.JsonArray)
        ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?: emptyList()

/**
 * Navigation seam for the more-info back-stack: opening a group member's own
 * more-info pushes it onto the sheet's internal stack so the system Back returns to
 * the parent. Null when the sheet has no stack wired (then member rows just toggle).
 */
internal val LocalMoreInfoNavigate = compositionLocalOf<((entityId: String) -> Unit)?> { null }

/**
 * Parse a script entity's `fields:` attribute into typed [MoreInfoDomainControls.ScriptField]s.
 * HA exposes each field as `{ name, description, required, selector: { <kind>: {...} } }`;
 * a `select` selector carries `options`. Unknown selectors fall back to a text input.
 */
private fun parseScriptFields(attrs: kotlinx.serialization.json.JsonObject?): List<MoreInfoDomainControls.ScriptField> {
    val fields = attrs?.get("fields") as? kotlinx.serialization.json.JsonObject ?: return emptyList()
    return fields.entries.mapNotNull { (key, spec) ->
        val obj = spec as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        fun str(k: String): String? =
            (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        val selector = obj["selector"] as? kotlinx.serialization.json.JsonObject
        val selectorKey = selector?.keys?.firstOrNull()
        val selectorBody = selectorKey?.let { selector[it] as? kotlinx.serialization.json.JsonObject }
        val options = (selectorBody?.get("options") as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { el ->
                when (el) {
                    is kotlinx.serialization.json.JsonPrimitive -> el.content
                    is kotlinx.serialization.json.JsonObject ->
                        (el["value"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                    else -> null
                }
            }
            ?: emptyList()
        val type = MoreInfoDomainControls.classifyScriptField(selectorKey, hasOptions = options.isNotEmpty())
        val required = (obj["required"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() == true
        MoreInfoDomainControls.ScriptField(
            key = key,
            name = str("name") ?: key,
            description = str("description"),
            type = type,
            required = required,
            options = options,
            defaultText = (obj["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
        )
    }
}

/** Collect entered script-field values into the `variables`-style service data for
 *  `script.turn_on`. Numbers are emitted as JSON numbers, booleans as booleans, the
 *  rest as strings. Fields with no entered value and no default are omitted. */
private fun buildScriptServiceData(
    fields: List<MoreInfoDomainControls.ScriptField>,
    values: Map<String, String>,
): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
    val vars = kotlinx.serialization.json.buildJsonObject {
        fields.forEach { field ->
            val raw = values[field.key] ?: field.defaultText ?: return@forEach
            when (field.type) {
                MoreInfoDomainControls.ScriptFieldType.NUMBER ->
                    raw.toDoubleOrNull()?.let { put(field.key, kotlinx.serialization.json.JsonPrimitive(it)) }
                MoreInfoDomainControls.ScriptFieldType.BOOLEAN ->
                    put(field.key, kotlinx.serialization.json.JsonPrimitive(raw.equals("true", ignoreCase = true)))
                else -> put(field.key, kotlinx.serialization.json.JsonPrimitive(raw))
            }
        }
    }
    if (vars.isNotEmpty()) put("variables", vars)
}

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

// ── Batch O2: media seek / camera / counter / timer / update / domain controls ─────────

private val O2 = MoreInfoDomainControls

/**
 * Draggable position / seek bar for a media player that advertises SEEK and reports
 * a duration. The live position is interpolated off the last-reported anchor
 * ([EntityState.mediaPosition] + [EntityState.mediaPositionUpdatedAt]) while playing,
 * so the bar tracks playback without a per-second WS push. Releasing the thumb fires
 * `media_player.media_seek {seek_position: <seconds>}`. Renders nothing when the
 * player can't seek or has no duration.
 */
@Composable
private fun MediaSeekControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val duration = entity.mediaDuration?.takeIf { it > 0 } ?: return
    if (!entity.hasMediaFeature(EntityState.MediaPlayerFeature.SEEK)) return
    // Interpolate: anchor position + wall-clock elapsed since the anchor while playing.
    val playing = entity.rawState.equals("playing", ignoreCase = true)
    val anchor = entity.mediaPosition ?: 0
    val anchorAt = entity.mediaPositionUpdatedAt
    // Per-second tick so the readout advances while playing.
    var tick by remember(entity.id) { mutableIntStateOf(0) }
    LaunchedEffect(entity.id, playing) {
        while (playing) {
            kotlinx.coroutines.delay(1_000L)
            tick++
        }
    }
    val livePos = remember(anchor, anchorAt, tick, playing, duration) {
        val elapsed = if (playing && anchorAt != null) {
            (java.time.Instant.now().epochSecond - anchorAt.epochSecond).coerceAtLeast(0L)
        } else {
            0L
        }
        (anchor + elapsed).coerceIn(0L, duration.toLong()).toInt()
    }
    // Local drag override: while the finger is down, show the dragged value.
    var dragging by remember(entity.id) { mutableStateOf(false) }
    var dragPos by remember(entity.id) { mutableFloatStateOf(livePos.toFloat()) }
    val shown = if (dragging) dragPos.toInt() else livePos
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "POSITION", style = responsiveType(R1.labelMicro), color = R1.InkMuted, modifier = Modifier.weight(1f))
            Text(
                text = "${O2.formatRemaining(shown.toLong())} / ${O2.formatRemaining(duration.toLong())}",
                style = responsiveType(R1.labelMicro),
                color = accent,
            )
        }
        Slider(
            value = shown.toFloat().coerceIn(0f, duration.toFloat()),
            onValueChange = { dragging = true; dragPos = it },
            onValueChangeFinished = {
                dragging = false
                dispatch(
                    ServiceCall(
                        entity.id,
                        "media_seek",
                        kotlinx.serialization.json.buildJsonObject {
                            put("seek_position", kotlinx.serialization.json.JsonPrimitive(dragPos.toInt()))
                        },
                    ),
                )
            },
            valueRange = 0f..duration.toFloat(),
            colors = sliderColors(accent),
            modifier = Modifier.semantics { contentDescription = "Seek position" },
        )
    }
}

/**
 * Live camera view for a `camera.*` more-info: a fast JPEG poll via [CameraSnapshot]
 * with a download button that saves the current frame to the device Downloads
 * collection (MediaStore, API 29+). The download button greys on older Android
 * where the no-permission MediaStore write is unavailable.
 */
@Composable
private fun CameraControl(entity: EntityState, accent: Color) {
    val serverUrl = LocalHaServerUrl.current.orEmpty()
    val bearerToken = LocalHaBearerToken.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(entity.id) { mutableStateOf<String?>(null) }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted),
        ) {
            com.github.itskenny0.r1ha.ui.components.CameraSnapshot(
                serverUrl = serverUrl,
                bearerToken = bearerToken,
                entityId = entity.id.value,
                // Snappier poll than the card grid: a more-info is a focused live look.
                intervalMillis = 1_500L,
                modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp),
            )
        }
        if (CameraSnapshotDownload.isSupported) {
            DetailChip(label = "SAVE SNAPSHOT", accent = accent, modifier = Modifier.fillMaxWidth()) {
                status = "Saving…"
                scope.launch {
                    val result = CameraSnapshotDownload.save(
                        context = context,
                        serverUrl = serverUrl,
                        bearerToken = bearerToken,
                        entityId = entity.id.value,
                    )
                    status = when (result) {
                        is CameraSnapshotDownload.DownloadResult.Saved -> "Saved to Downloads"
                        is CameraSnapshotDownload.DownloadResult.Failed -> result.reason
                        CameraSnapshotDownload.DownloadResult.Unsupported -> "Not supported on this device"
                    }
                }
            }
            status?.let { Text(text = it, style = responsiveType(R1.labelMicro), color = R1.InkSoft) }
        }
    }
}

/**
 * `counter.*` more-info: increment / decrement / reset, gated on the configured
 * minimum / maximum the way HA's more-info-counter is.
 */
@Composable
private fun CounterControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val value = entity.rawState?.toLongOrNull()
    val minimum = entity.attrDouble("minimum")?.toLong()
    val maximum = entity.attrDouble("maximum")?.toLong()
    val buttons = O2.counterButtons(value, minimum, maximum)
    fun fire(service: String) = dispatch(ServiceCall(entity.id, service, kotlinx.serialization.json.JsonObject(emptyMap())))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepperButton("−", "Decrement", accent, enabled = buttons.canDecrement) { fire("decrement") }
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = value?.toString() ?: (entity.rawState ?: "-"),
                style = responsiveType(R1.numeralM),
                color = accent,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(R1.space.m))
            StepperButton("+", "Increment", accent, enabled = buttons.canIncrement) { fire("increment") }
        }
        DetailChip(label = "RESET", accent = accent, modifier = Modifier.fillMaxWidth()) { fire("reset") }
    }
}

/**
 * `timer.*` more-info: start / pause / cancel / finish gated on state, plus a live
 * remaining countdown that ticks every second while the timer is active.
 */
@Composable
private fun TimerControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val state = entity.rawState
    val buttons = O2.timerButtons(state)
    val finishesAt = entity.attrStr("finishes_at")
        ?.let { com.github.itskenny0.r1ha.core.ha.parseHaInstant(it) }
        ?.epochSecond
    // Tick every second while active so the countdown advances.
    var tick by remember(entity.id, state) { mutableIntStateOf(0) }
    LaunchedEffect(entity.id, state) {
        while (state.equals("active", ignoreCase = true)) {
            kotlinx.coroutines.delay(1_000L)
            tick++
        }
    }
    val remaining = remember(state, tick, finishesAt) {
        O2.timerRemainingSeconds(
            state = state,
            nowEpochSeconds = java.time.Instant.now().epochSecond,
            finishesAtEpochSeconds = finishesAt,
            remaining = entity.attrStr("remaining"),
            duration = entity.attrStr("duration"),
        )
    }
    fun fire(service: String) = dispatch(ServiceCall(entity.id, service, kotlinx.serialization.json.JsonObject(emptyMap())))
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        if (remaining != null) {
            Text(
                text = O2.formatRemaining(remaining),
                style = responsiveType(R1.numeralM).copy(fontWeight = FontWeight.SemiBold),
                color = accent,
            )
        }
        ChipStrip(wrap = true) {
            if (buttons.showStart) DetailChip("START", accent) { fire("start") }
            if (buttons.showPause) DetailChip("PAUSE", accent) { fire("pause") }
            if (buttons.showCancel) DetailChip("CANCEL", accent) { fire("cancel") }
            if (buttons.showFinish) DetailChip("FINISH", accent) { fire("finish") }
        }
    }
}

/**
 * `update.*` more-info: installed / latest version readout, install + skip actions
 * gated on supported_features, an optional install-specific-version input, a backup
 * awareness note, and the full release notes fetched via the `update/release_notes`
 * WS command (falling back to the `release_summary` attribute) rendered through the
 * shared markdown view.
 */
@Composable
private fun UpdateControl(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    val inProgress = entity.attrStr("in_progress").let { it != null && it != "false" } ||
        entity.attrDouble("update_percentage") != null
    val controls = O2.updateControls(entity.rawState, entity.supportedFeatures, inProgress)
    val installed = entity.attrStr("installed_version")
    val latest = entity.attrStr("latest_version")
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Column(verticalArrangement = Arrangement.spacedBy(R1.space.xxs)) {
            if (installed != null) DetailRow("Installed", installed)
            if (latest != null) DetailRow("Latest", latest)
        }
        if (controls.inProgress) {
            val pct = entity.attrDouble("update_percentage")?.toInt()
            Text(
                text = if (pct != null) "Installing $pct%" else "Installing…",
                style = responsiveType(R1.labelMicro),
                color = accent,
            )
        }
        if (controls.canInstall || controls.canSkip) {
            ChipStrip(wrap = true) {
                if (controls.canInstall) {
                    DetailChip("INSTALL", accent) {
                        dispatch(ServiceCall(entity.id, "install", kotlinx.serialization.json.JsonObject(emptyMap())))
                    }
                }
                if (controls.canSkip) {
                    DetailChip("SKIP", accent) {
                        dispatch(ServiceCall(entity.id, "skip", kotlinx.serialization.json.JsonObject(emptyMap())))
                    }
                }
            }
        }
        // Specific-version install: the R1 has no keyboard, so we surface the
        // capability as a note rather than a free-text box (HA's input would need
        // typing). Documents the SPECIFIC_VERSION support without a dead control.
        if (controls.supportsSpecificVersion && latest != null) {
            Text(
                text = "Installs $latest. This update supports installing a specific version from the HA frontend.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
        if (controls.supportsBackup) {
            Text(
                text = "A backup is created before this update installs.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
        UpdateReleaseNotes(haRepository, entity, controls.supportsReleaseNotes)
    }
}

/**
 * Release notes for an update entity. Fetches the full markdown via the
 * `update/release_notes` WS command when the entity supports RELEASE_NOTES; falls
 * back to the `release_summary` attribute otherwise. Rendered through the shared
 * markdown view. Collapsed behind a toggle so it never crowds the install action.
 */
@Composable
private fun UpdateReleaseNotes(haRepository: HaRepository, entity: EntityState, supportsReleaseNotes: Boolean) {
    var expanded by remember(entity.id) { mutableStateOf(false) }
    val notes by androidx.compose.runtime.produceState<String?>(
        initialValue = null,
        entity.id,
        expanded,
        supportsReleaseNotes,
    ) {
        if (!expanded) {
            value = null
            return@produceState
        }
        value = if (supportsReleaseNotes) {
            haRepository.fetchUpdateReleaseNotes(entity.id.value).getOrNull()
                ?: entity.attrStr("release_summary")
        } else {
            entity.attrStr("release_summary")
        }
    }
    // No notes available at all and no WS support — nothing to expand.
    if (!supportsReleaseNotes && entity.attrStr("release_summary") == null) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .r1Pressable(
                    onClick = { expanded = !expanded },
                    hapticOnClick = false,
                    contentDescription = if (expanded) "Collapse release notes" else "Expand release notes",
                ),
        ) {
            Text(text = "RELEASE NOTES", style = responsiveType(R1.sectionHeader), color = R1.InkSoft, modifier = Modifier.weight(1f))
            Text(text = if (expanded) "−" else "+", style = R1.numeralM, color = R1.InkSoft)
        }
        if (expanded) {
            when (val md = notes) {
                null -> SectionLoadingPlaceholder("Loading release notes")
                else -> {
                    if (md.isBlank()) {
                        Text(text = "No release notes", style = responsiveType(R1.body), color = R1.InkMuted)
                    } else {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.Surface)
                                .padding(R1.space.m),
                        ) {
                            com.github.itskenny0.r1ha.ui.components.MarkdownView(
                                nodes = remember(md) { com.github.itskenny0.r1ha.ui.components.parseMarkdown(md) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * `automation.*` more-info: a 'Run actions' trigger (automation.trigger), an
 * enable/disable toggle (turn_on/turn_off), and a last-triggered readout.
 */
@Composable
private fun AutomationControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        ActionButton("RUN ACTIONS", accent) {
            dispatch(ServiceCall(entity.id, "trigger", kotlinx.serialization.json.JsonObject(emptyMap())))
        }
        ToggleRow(
            entity = entity,
            accent = accent,
            onLabel = "ENABLED",
            offLabel = "DISABLED",
            isOn = entity.isOn,
            onOn = { dispatch(ServiceCall.setSwitch(entity.id, true)) },
            onOff = { dispatch(ServiceCall.setSwitch(entity.id, false)) },
        )
        val last = entity.attrStr("last_triggered")?.let { com.github.itskenny0.r1ha.core.ha.parseHaInstant(it) }
        if (last != null) {
            val ago = rememberRelativeTime(last)
            if (ago.isNotEmpty()) {
                Text(text = "TRIGGERED ${ago.uppercase()}", style = R1.labelMicro, color = R1.InkMuted)
            }
        }
    }
}

/**
 * `script.*` more-info: a run-state line, a run button (or cancel while running),
 * and a typed fields form derived from the script's `fields:` attribute. Each field
 * renders as a text / number / boolean / select input; running the script collects
 * the entered values into the service data.
 */
@Composable
private fun ScriptControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val running = O2.scriptIsRunning(entity.rawState)
    val fields = remember(entity.attributesJson) { parseScriptFields(entity.attributesJson) }
    // Per-field entered values, keyed by field key.
    val values = remember(entity.id) { androidx.compose.runtime.mutableStateMapOf<String, String>() }
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.s)) {
        if (running) {
            Text(text = "RUNNING", style = responsiveType(R1.labelMicro), color = accent)
        }
        fields.forEach { field ->
            ScriptFieldInput(field = field, accent = accent, value = values[field.key], onChange = { values[field.key] = it })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s), modifier = Modifier.fillMaxWidth()) {
            DetailChip(label = "RUN", accent = accent, modifier = Modifier.weight(1f)) {
                val data = buildScriptServiceData(fields, values)
                dispatch(ServiceCall(entity.id, "turn_on", data))
            }
            if (running) {
                DetailChip(label = "CANCEL", accent = accent, modifier = Modifier.weight(1f)) {
                    dispatch(ServiceCall(entity.id, "turn_off", kotlinx.serialization.json.JsonObject(emptyMap())))
                }
            }
        }
    }
}

@Composable
private fun ScriptFieldInput(
    field: MoreInfoDomainControls.ScriptField,
    accent: Color,
    value: String?,
    onChange: (String) -> Unit,
) {
    val current = value ?: field.defaultText
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xxs)) {
        Text(
            text = field.name.ifBlank { field.key }.uppercase() + if (field.required) " *" else "",
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
        when (field.type) {
            MoreInfoDomainControls.ScriptFieldType.BOOLEAN -> {
                val on = current.equals("true", ignoreCase = true)
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    DetailChip("TRUE", accent, selected = on) { onChange("true") }
                    DetailChip("FALSE", accent, selected = current != null && !on) { onChange("false") }
                }
            }
            MoreInfoDomainControls.ScriptFieldType.SELECT -> {
                ChipStrip(wrap = true) {
                    field.options.forEach { opt ->
                        DetailChip(label = optionLabel(opt), accent = accent, selected = current == opt) { onChange(opt) }
                    }
                }
            }
            else -> {
                // text / number: the R1 has no soft keyboard in the sheet, so show the
                // default (or entered) value read-only with the description hint. The
                // value still flows into the run call.
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
                        text = current?.ifBlank { "-" } ?: "-",
                        style = responsiveType(R1.body),
                        color = if (current.isNullOrBlank()) R1.InkMuted else R1.Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        field.description?.takeIf { it.isNotBlank() }?.let {
            Text(text = it, style = responsiveType(R1.labelMicro), color = R1.InkMuted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * RELATED section — the R1 equivalent of HA's more-info "Related" tab. Lists the
 * entities sharing the focused entity's device (same-device) and its area
 * (same-area), each row tapping through to that entity's own more-info via the
 * back-stack push ([LocalMoreInfoNavigate]). Groups come from the area-registry
 * snapshot already cached for area cards; the section renders nothing while the
 * snapshot resolves or when nothing relates.
 */
@Composable
private fun RelatedSection(haRepository: HaRepository, entity: EntityState, accent: Color) {
    val push = LocalMoreInfoNavigate.current ?: return
    val entityId = entity.id.value
    val related by androidx.compose.runtime.produceState<MoreInfoRelated.Related?>(
        initialValue = null,
        entityId,
    ) {
        val snap = com.github.itskenny0.r1ha.feature.dashboards.cards.AreaRegistryCache
            .get(haRepository, System.currentTimeMillis())
        value = snap?.let {
            MoreInfoRelated.compute(entityId, it.entitiesByArea, it.deviceByEntity)
        } ?: MoreInfoRelated.Related(emptyList(), emptyList())
    }
    val groups = related ?: return
    if (groups.isEmpty) return
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        if (groups.sameDevice.isNotEmpty()) {
            RelatedGroup(
                title = "RELATED",
                memberIds = groups.sameDevice,
                haRepository = haRepository,
                accent = accent,
                onOpen = push,
            )
        }
        if (groups.sameArea.isNotEmpty()) {
            RelatedGroup(
                title = "SAME AREA",
                memberIds = groups.sameArea,
                haRepository = haRepository,
                accent = accent,
                onOpen = push,
            )
        }
    }
}

/**
 * One collapsible group of related entities. Collapsed by default: a long
 * same-area list would otherwise dominate the sheet's tail and push the
 * attributes out of reach on the 640px panel; the header carries the count so
 * a collapsed group still says how much it hides. Same +/- header idiom as
 * the logbook and attributes sections.
 */
@Composable
private fun RelatedGroup(
    title: String,
    memberIds: List<String>,
    haRepository: HaRepository,
    accent: Color,
    onOpen: (String) -> Unit,
) {
    var expanded by remember(title, memberIds.size) { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(
                onClick = { expanded = !expanded },
                hapticOnClick = false,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            ),
    ) {
        Text(
            text = "$title (${memberIds.size})",
            style = responsiveType(R1.sectionHeader),
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
        )
        Text(text = if (expanded) "−" else "+", style = R1.numeralM, color = R1.InkSoft)
    }
    if (expanded) {
        memberIds.forEach { memberId ->
            GroupMemberRow(
                haRepository = haRepository,
                memberId = memberId,
                accent = accent,
                onToggle = {},
                onOpen = { onOpen(memberId) },
                showToggle = false,
            )
        }
    }
}

/**
 * `group.*` more-info: a member entity list with a compact toggle for switchable
 * members (light / switch / fan / ...) and a tap-through to each member's own
 * more-info via the back-stack push. Non-toggleable members show their state with
 * a tap-through only.
 */
@Composable
private fun GroupControl(
    haRepository: HaRepository,
    entity: EntityState,
    accent: Color,
    dispatch: (ServiceCall) -> Unit,
) {
    val members = entity.attrStringList("entity_id")
    if (members.isEmpty()) return
    val push = LocalMoreInfoNavigate.current
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "MEMBERS", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        members.forEach { memberId ->
            GroupMemberRow(
                haRepository = haRepository,
                memberId = memberId,
                accent = accent,
                onToggle = { on -> dispatch(ServiceCall.setSwitch(EntityId(memberId), on)) },
                onOpen = push?.let { open -> { open(memberId) } },
            )
        }
    }
}

@Composable
private fun GroupMemberRow(
    haRepository: HaRepository,
    memberId: String,
    accent: Color,
    onToggle: (Boolean) -> Unit,
    onOpen: (() -> Unit)?,
    /** Show the inline ON/OFF toggle chip for switchable members. False (the
     *  related-section case) renders a tap-through row with no toggle. */
    showToggle: Boolean = true,
) {
    val member by haRepository.observeRaw(setOf(memberId))
        .collectAsState(initial = emptyMap())
    val state = member[memberId]
    val toggleable = showToggle && O2.memberIsToggleable(memberId)
    val isOn = state?.isOn == true
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.Surface)
            .then(if (onOpen != null) Modifier.r1Pressable(onClick = onOpen, hapticOnClick = false, contentDescription = "Open $memberId") else Modifier)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state?.friendlyName?.ifBlank { memberId } ?: memberId,
                style = responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val word = state?.rawState?.let { optionLabel(it) }
            if (word != null) {
                Text(text = word, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (toggleable && state != null) {
            DetailChip(label = if (isOn) "ON" else "OFF", accent = accent, selected = isOn) { onToggle(!isOn) }
        }
    }
}

/**
 * `sun.sun` more-info: next-rising / next-setting times (sooner event first) plus
 * elevation and azimuth, mirroring HA's more-info-sun read-only block.
 */
@Composable
private fun SunControl(entity: EntityState) {
    val rising = entity.attrStr("next_rising")?.let { com.github.itskenny0.r1ha.core.ha.parseHaInstant(it) }
    val setting = entity.attrStr("next_setting")?.let { com.github.itskenny0.r1ha.core.ha.parseHaInstant(it) }
    val order = O2.sunEventOrder(rising?.epochSecond, setting?.epochSecond)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        order.forEach { event ->
            val instant = if (event == MoreInfoDomainControls.SunEvent.RISING) rising else setting
            val label = if (event == MoreInfoDomainControls.SunEvent.RISING) "Rising" else "Setting"
            if (instant != null) {
                val ago = rememberRelativeTime(instant)
                DetailRow(label, ago.ifEmpty { "-" })
            }
        }
        entity.attrDouble("elevation")?.let { DetailRow("Elevation", "${formatNumber(it)}°") }
        entity.attrDouble("azimuth")?.let { DetailRow("Azimuth", "${formatNumber(it)}°") }
    }
}

/**
 * Location map for a `person.*` / `device_tracker.*` more-info. When the entity
 * reports latitude/longitude a compact single-marker canvas plots it; otherwise the
 * zone state word is shown. Mirrors HA's more-info-person map + zone state line.
 */
@Composable
private fun PersonLocationControl(entity: EntityState, accent: Color) {
    val lat = entity.attrDouble("latitude")
    val lon = entity.attrDouble("longitude")
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        // Zone state word (home / not_home / a named zone).
        entity.rawState?.takeIf { it.isNotBlank() }?.let {
            DetailRow("Zone", optionLabel(it))
        }
        if (lat != null && lon != null) {
            MiniLocationMap(lat = lat, lon = lon, accent = accent)
            Text(
                text = "%.4f, %.4f".format(java.util.Locale.US, lat, lon),
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

/** Compact single-marker map canvas. Self-contained projection (the MapCard's own
 *  canvas is private); centres the marker in a fixed viewport so a lone person reads
 *  as "here" without a bounding-box zoom. */
@Composable
private fun MiniLocationMap(lat: Double, lon: Double, accent: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted),
    ) {
        androidx.compose.foundation.Canvas(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            val w = size.width
            val h = size.height
            drawLine(R1.Hairline, androidx.compose.ui.geometry.Offset(w * 0.5f, 0f), androidx.compose.ui.geometry.Offset(w * 0.5f, h), strokeWidth = 1f)
            drawLine(R1.Hairline, androidx.compose.ui.geometry.Offset(0f, h * 0.5f), androidx.compose.ui.geometry.Offset(w, h * 0.5f), strokeWidth = 1f)
            val centre = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.5f)
            drawCircle(color = accent.copy(alpha = 0.24f), radius = 14f, center = centre)
            drawCircle(color = accent, radius = 5f, center = centre)
        }
    }
}

/** `remote.*` more-info: an activity chip row when the integration reports a
 *  current activity + an activity list. Each chip switches activity via
 *  remote.turn_on {activity: <name>}. Renders nothing when no activity list. */
@Composable
private fun RemoteActivityControl(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val activities = entity.attrStringList("activity_list")
    if (activities.isEmpty()) return
    val current = entity.attrStr("current_activity")
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(R1.space.xs)) {
        Text(text = "ACTIVITY", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
        ChipStrip(wrap = true) {
            activities.forEach { act ->
                DetailChip(
                    label = optionLabel(act),
                    accent = accent,
                    selected = current.equals(act, ignoreCase = true),
                    onClick = {
                        dispatch(
                            ServiceCall(
                                entity.id,
                                "turn_on",
                                kotlinx.serialization.json.buildJsonObject {
                                    put("activity", kotlinx.serialization.json.JsonPrimitive(act))
                                },
                            ),
                        )
                    },
                )
            }
        }
    }
    Spacer(Modifier.height(R1.space.s))
}

/** Live remaining-status line for vacuum / lawn_mower, surfacing the integration's
 *  richer `status` attribute (battery + status). Mirrors HA's more-info-vacuum
 *  state-override + battery row. */
@Composable
private fun VacuumStatusLine(entity: EntityState) {
    val battery = O2.vacuumBatteryPercent(entity.vacuumBatteryLevel, entity.attrDouble("battery_level"))
    val status = O2.vacuumStatusLabel(entity.attrStr("status") ?: entity.vacuumStatus, entity.rawState)
    val parts = buildList {
        if (status != null) add(status)
        if (battery != null) add("BATT $battery%")
    }
    if (parts.isEmpty()) return
    Text(
        text = parts.joinToString("  ·  "),
        style = responsiveType(R1.labelMicro),
        color = R1.InkSoft,
        modifier = Modifier.padding(bottom = R1.space.xs),
    )
}

/**
 * Arming / pending / triggered phase banner above the alarm keypad. Shows a
 * countdown when the integration reports the matching delay (arming_time /
 * delay_time); otherwise just the phase word. Renders nothing in the normal
 * armed / disarmed phase.
 */
@Composable
private fun AlarmPhaseBanner(entity: EntityState, accent: Color) {
    val phase = O2.alarmPhase(entity.rawState)
    if (phase == MoreInfoDomainControls.AlarmPhase.ACTIVE) return
    val total = O2.alarmPhaseTotalSeconds(
        phase = phase,
        armingTime = entity.attrDouble("arming_time")?.toLong(),
        delayTime = entity.attrDouble("delay_time")?.toLong(),
    )
    val label = when (phase) {
        MoreInfoDomainControls.AlarmPhase.ARMING -> "ARMING"
        MoreInfoDomainControls.AlarmPhase.PENDING -> "PENDING"
        MoreInfoDomainControls.AlarmPhase.TRIGGERED -> "TRIGGERED"
        MoreInfoDomainControls.AlarmPhase.ACTIVE -> ""
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(accent.copy(alpha = 0.15f))
            .border(1.dp, accent, R1.ShapeS)
            .padding(R1.space.m),
    ) {
        val text = if (total != null) "$label · ${O2.formatRemaining(total)}" else label
        Text(text = text, style = responsiveType(R1.label), color = accent)
    }
    Spacer(Modifier.height(R1.space.s))
}

/** Fallback power toggle for an unmodelled OTHER-domain entity that nonetheless
 *  reports an on/off state, mirroring HA routing an unknown toggleable domain to a
 *  plain toggle. Renders nothing for a non-on/off state. */
@Composable
private fun UnmodelledToggle(entity: EntityState, accent: Color, dispatch: (ServiceCall) -> Unit) {
    val raw = entity.rawState?.lowercase()
    if (raw != "on" && raw != "off") return
    ToggleRow(
        entity = entity,
        accent = accent,
        onLabel = "ON",
        offLabel = "OFF",
        isOn = entity.isOn,
        onOn = { dispatch(ServiceCall(entity.id, "turn_on", kotlinx.serialization.json.JsonObject(emptyMap()))) },
        onOff = { dispatch(ServiceCall(entity.id, "turn_off", kotlinx.serialization.json.JsonObject(emptyMap()))) },
    )
}

/** Top-of-sheet lifecycle banner for an unavailable / unknown / restored entity,
 *  mirroring HA's more-info alert. Renders nothing when the entity is live. */
@Composable
private fun EntityAlertBanner(entity: EntityState) {
    val alert = O2.entityAlert(entity.rawState, restored = false)
    if (alert == MoreInfoDomainControls.EntityAlert.NONE) return
    val (text, color) = when (alert) {
        MoreInfoDomainControls.EntityAlert.UNAVAILABLE ->
            "This entity is not currently available." to R1.StatusAmber
        MoreInfoDomainControls.EntityAlert.UNKNOWN ->
            "This entity's state is unknown." to R1.InkSoft
        MoreInfoDomainControls.EntityAlert.RESTORED ->
            "This entity was restored and may be stale." to R1.StatusAmber
        MoreInfoDomainControls.EntityAlert.NONE -> return
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(color.copy(alpha = 0.15f))
            .border(1.dp, color.copy(alpha = 0.5f), R1.ShapeS)
            .padding(R1.space.m),
    ) {
        Text(text = text, style = responsiveType(R1.body), color = color)
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
