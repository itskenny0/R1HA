package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.github.itskenny0.r1ha.core.theme.LocalR1Theme
import com.github.itskenny0.r1ha.core.theme.R1

@Composable
fun EntityCard(
    state: EntityState,
    onTapToggle: () -> Unit,
    modifier: Modifier = Modifier.fillMaxSize(),
    onSetOn: ((Boolean) -> Unit)? = null,
    /**
     * When true the entire card surface is tappable; tapping calls [onTapToggle]. When
     * false the card is inert (the wheel and the explicit ON/OFF labels on switch cards
     * still work). Mirrors the "Tap to toggle" setting in Settings, which used to be
     * silently dead-code because the three theme implementations of `theme.Card` never
     * wired their `onTapToggle` parameter to a `Modifier.clickable` — fixed here once for
     * all themes by wrapping the theme card in our own pressable Box.
     */
    tapToToggleEnabled: Boolean = true,
    /**
     * Optional long-press handler — fires when the user holds the card. Used by the card-
     * stack screen to dispatch the [EntityOverride.longPressTarget] action; null on
     * surfaces (like the picker preview) where long-press is meaningless.
     */
    onLongPress: (() -> Unit)? = null,
    /**
     * For light cards: current wheel mode (BRIGHTNESS / COLOR_TEMP / HUE). Null means
     * the parent doesn't surface a wheel mode — falls back to BRIGHTNESS for display.
     */
    lightWheelMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode? = null,
    /** Tap-to-cycle handler. Null disables the cycle gesture (used by previews). */
    onCycleLightMode: (() -> Unit)? = null,
    /**
     * When true (every pre-existing caller, including the FULLSCREEN deck) the card
     * claims the full height of its slot, the historical full-screen-control-surface
     * layout. False is the DYNAMIC deck's content-height path: the internal
     * fillMaxSize chain relaxes to fillMaxWidth and the value is forwarded through
     * [com.github.itskenny0.r1ha.core.theme.LocalCardFillSlot] so the theme card
     * bodies, the value-bar scaffold and the aux variants switch their fill-height
     * elements to natural heights, with the tape meter taking the concrete
     * [com.github.itskenny0.r1ha.feature.cardstack.DYNAMIC_VALUE_BAR_HEIGHT_DP] band.
     * (True intrinsic wrapping is not possible here: the value-bar tape meters are
     * SubcomposeLayout-backed, which throws on intrinsic measurement; concrete
     * heights sidestep the query entirely, so the card wraps to the sum of its
     * children and every control stays visible.)
     */
    fillSlot: Boolean = true,
) {
    val theme = LocalR1Theme.current
    val glyph = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.Glyph.LIGHT
        Domain.FAN -> CardRenderModel.Glyph.FAN
        Domain.COVER -> CardRenderModel.Glyph.COVER
        Domain.MEDIA_PLAYER -> CardRenderModel.Glyph.MEDIA_PLAYER
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> CardRenderModel.Glyph.SWITCH
        Domain.LOCK -> CardRenderModel.Glyph.LOCK
        Domain.HUMIDIFIER -> CardRenderModel.Glyph.HUMIDIFIER
        Domain.CLIMATE -> CardRenderModel.Glyph.CLIMATE
        Domain.WATER_HEATER -> CardRenderModel.Glyph.WATER_HEATER
        Domain.NUMBER, Domain.INPUT_NUMBER -> CardRenderModel.Glyph.NUMBER
        Domain.VALVE -> CardRenderModel.Glyph.VALVE
        Domain.VACUUM -> CardRenderModel.Glyph.VACUUM
        Domain.LAWN_MOWER -> CardRenderModel.Glyph.LAWN_MOWER
        // Action entities don't reach the theme card path — handled below — so the glyph
        // mapping never lands on theme.Card. Routed to ActionCard which has its own label
        // ("SCENE"/"SCRIPT"/"BUTTON") via domainLabel above. The Glyph value is unused but
        // has to be exhaustive for the when to compile.
        Domain.SCENE, Domain.SCRIPT, Domain.BUTTON, Domain.INPUT_BUTTON,
        Domain.SENSOR, Domain.BINARY_SENSOR -> CardRenderModel.Glyph.SWITCH
        // Select / input_select route to SelectCard before reaching the glyph map.
        // Glyph itself isn't used there but the when has to be exhaustive.
        Domain.SELECT, Domain.INPUT_SELECT -> CardRenderModel.Glyph.SWITCH
        // Helper-only domains — never reach the card stack via the
        // normal favourites flow (kind-filtered ★ on Helpers excludes
        // them); the glyph value is only used when the when is
        // exhaustive. Pick something sensible-but-irrelevant.
        Domain.COUNTER, Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> CardRenderModel.Glyph.NUMBER
        Domain.TIMER -> CardRenderModel.Glyph.SWITCH
        // New text / date / datetime / time / image / event: isSensor = true so these
        // route to SensorCard; glyph is unused in practice but must be exhaustive.
        Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
        Domain.IMAGE, Domain.EVENT -> CardRenderModel.Glyph.SWITCH
        // Siren: on/off; SWITCH glyph (never pinned via normal flows but exhaustive).
        Domain.SIREN -> CardRenderModel.Glyph.SWITCH
        // Update entities are managed from the dedicated Updates screen and
        // shouldn't end up on the card stack via normal flows. Defensive
        // glyph keeps the when exhaustive without crashing if a user pins
        // one manually via raw favourites JSON.
        Domain.UPDATE -> CardRenderModel.Glyph.SWITCH
        // Remote (IR / RF blasters, activity hubs) — same SWITCH glyph as the
        // generic on/off domains; the RemotePanel below the card carries the
        // activity-chip / custom-button content.
        Domain.REMOTE -> CardRenderModel.Glyph.SWITCH
        // Alarm control panel — reuses the LOCK glyph so the security-affordance
        // visual reads the same family as smart locks. The AlarmPanel below
        // surfaces the per-mode chips that distinguish an alarm from a lock.
        Domain.ALARM_CONTROL_PANEL -> CardRenderModel.Glyph.LOCK
        // Person / weather are read-only and routed to SensorCard before this glyph is
        // ever drawn, but the themed/glyph path now has first-class members so the
        // fallback (and any future themed rendering) carries the right domain identity.
        Domain.PERSON -> CardRenderModel.Glyph.PERSON
        Domain.WEATHER -> CardRenderModel.Glyph.WEATHER
        // Catch-all domains have no card archetype and aren't pinnable, so this glyph is
        // never actually drawn for them; a generic switch glyph keeps the when total.
        Domain.OTHER -> CardRenderModel.Glyph.SWITCH
    }
    val accentRole = when (state.id.domain) {
        Domain.LIGHT -> CardRenderModel.AccentRole.WARM
        Domain.FAN -> CardRenderModel.AccentRole.GREEN
        Domain.COVER -> CardRenderModel.AccentRole.NEUTRAL
        Domain.MEDIA_PLAYER -> CardRenderModel.AccentRole.COOL
        // Smart switches/plugs/automations get the warm accent — visually anchors the
        // largest new domain group to the same colour the user already associates with
        // "primary control".
        Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION -> CardRenderModel.AccentRole.WARM
        Domain.LOCK -> CardRenderModel.AccentRole.NEUTRAL
        Domain.HUMIDIFIER -> CardRenderModel.AccentRole.COOL
        // Thermostat accent follows the HVAC mode so the colour answers "is it heating or
        // cooling?" at a glance: heat reads warm, cool reads cool, off reads neutral.
        Domain.CLIMATE -> climateAccentRole(state.climateHvacMode)
        // Action entities — scenes get green (one-shot "go" energy), scripts cool, buttons
        // warm. Picked to keep the deck visually varied so the action tiles don't all look
        // identical when the user has a mix.
        Domain.SCENE -> CardRenderModel.AccentRole.GREEN
        Domain.SCRIPT -> CardRenderModel.AccentRole.COOL
        Domain.BUTTON, Domain.INPUT_BUTTON -> CardRenderModel.AccentRole.WARM
        Domain.NUMBER, Domain.INPUT_NUMBER -> CardRenderModel.AccentRole.WARM
        Domain.VALVE -> CardRenderModel.AccentRole.COOL
        Domain.VACUUM -> CardRenderModel.AccentRole.GREEN
        Domain.LAWN_MOWER -> CardRenderModel.AccentRole.GREEN
        Domain.WATER_HEATER -> CardRenderModel.AccentRole.WARM
        // Sensors — colour by the most common device_class so the deck doesn't read as a
        // wall of orange. Temperature/humidity reads cool, motion/door reads green ("safe
        // / unobtrusive"), everything else falls back to neutral.
        Domain.SENSOR -> sensorAccent(state.deviceClass)
        Domain.BINARY_SENSOR -> binarySensorAccent(state.deviceClass)
        // Select entities get a cool accent — keeps them visually distinct from the
        // warm-orange action / control crowd in the deck while still reading as
        // interactive (vs. neutral which conveys read-only).
        Domain.SELECT, Domain.INPUT_SELECT -> CardRenderModel.AccentRole.COOL
        // Helper-only domains — defensive accent. Helpers screen is the
        // canonical surface; if a user ever forces one onto the card
        // stack (e.g. via raw favourites JSON), neutral is least
        // confusing.
        Domain.COUNTER, Domain.TIMER,
        Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> CardRenderModel.AccentRole.NEUTRAL
        // text / date / datetime / time / image: read-only sensor-like, neutral accent.
        Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
        Domain.IMAGE -> CardRenderModel.AccentRole.NEUTRAL
        // event: read-only fire-and-forget, neutral accent.
        Domain.EVENT -> CardRenderModel.AccentRole.NEUTRAL
        // Siren: on/off; warm accent (high-attention safety device).
        Domain.SIREN -> CardRenderModel.AccentRole.WARM
        // Update entity defensive accent — see the glyph branch for context.
        Domain.UPDATE -> CardRenderModel.AccentRole.COOL
        // Remote — cool accent. IR/RF is a "send" affordance like media transport,
        // and the colour cue mirrors media_player so the deck reads consistently.
        Domain.REMOTE -> CardRenderModel.AccentRole.COOL
        // Alarm — warm to read as a high-attention security affordance, matching
        // the "this is important, don't tap it accidentally" framing of the
        // disarmed → armed transitions.
        Domain.ALARM_CONTROL_PANEL -> CardRenderModel.AccentRole.WARM
        // Person — accent reflects presence: green when home ("present"), neutral when
        // away, so the colour answers "are they home?" before the user even reads the
        // word. Weather — accent follows the condition (sunny warm, wet cool, storm
        // warm, windy green). Both render via SensorCard, which reads the same helpers.
        Domain.PERSON -> com.github.itskenny0.r1ha.ui.components.PersonWeatherCardModel
            .personAccent(state.rawState)
        Domain.WEATHER -> com.github.itskenny0.r1ha.ui.components.PersonWeatherCardModel
            .weatherAccent(state.rawState)
        // Catch-all domains have no card archetype and aren't pinnable; neutral keeps the
        // when total for the defensive / future-rendering path.
        Domain.OTHER -> CardRenderModel.AccentRole.NEUTRAL
    }
    // When the entity is unavailable, dim the whole card and overlay a "UNAVAILABLE" label so
    // the user doesn't think the card is just at 0%. The themes themselves don't honour
    // isAvailable, so this is enforced uniformly at the wrapper level. The tap-to-toggle
    // gesture is also wired here (rather than inside each theme) so all three themes get it
    // for free; r1Pressable's haptic is disabled because the existing percent-change effect
    // in CardStackScreen already fires CLOCK_TICK when the state actually flips — double-
    // haptic on a single tap reads as a stutter rather than a click. Sensors are skipped
    // because they're read-only — a press-state dip on a card that can't actually do
    // anything is just misleading.
    // If the parent supplied a long-press handler, use r1RowPressable so both tap and
    // long-press are detected. Otherwise stay on the cheaper r1Pressable which only
    // wires tap. Either way: sensors and unavailable entities don't get a gesture
    // surface at all — pressing them shouldn't even dip the card visually because
    // nothing will happen.
    // Card-level tap-to-toggle is only applied to variants WITHOUT an explicit
    // activation button on the card body itself. ActionCard has a big ACTIVATE button,
    // SwitchCard has clickable ON / OFF labels, SelectCard has a CHOOSE button — any
    // of those already cover the "fire this entity" intent with an intentional tap on
    // a labelled target, so the card-level wrapper would be redundant at best and
    // destructive at worst (a tap meant to scroll past an unrelated UI element would
    // accidentally relock a door or run a scene). Scalar cards have no dedicated
    // on/off button — the wheel sets brightness and tap-to-toggle is the obvious way
    // to flip the bulb on / off — so they keep the gesture.
    //
    // Per-card override (perCardOverride.tapToToggle) can force the gesture on or off
    // independent of the global setting, so a single problematic card can be tamed
    // without flipping behaviour for the whole deck.
    val hasExplicitActivationButton = state.id.domain.isAction ||
        state.id.domain.isSelect ||
        !state.supportsScalar
    val perCardOverridePulledEarly = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides
        .current[state.id.value]
    val effectiveTapToToggle = perCardOverridePulledEarly?.tapToToggle ?: tapToToggleEnabled
    // Per-card actionOnTap override. NOOP explicitly disarms the card-level
    // tap surface (overrides everything else, including tapToToggle=true).
    // Any other explicit value falls through to the existing onTapToggle
    // dispatch — that callback already covers TOGGLE for booleans / FIRE
    // for actions; richer routing (e.g. NAVIGATE_HISTORY opening the
    // sensor history overlay from any domain) will plug into the same
    // hook from the screen layer as the routing surface evolves.
    val tapActionOverride = perCardOverridePulledEarly?.actionOnTap
    val tapModifier = when {
        tapActionOverride == com.github.itskenny0.r1ha.core.prefs.TapAction.NOOP -> Modifier
        !effectiveTapToToggle || !state.isAvailable -> Modifier
        state.id.domain.isSensor -> Modifier
        hasExplicitActivationButton -> Modifier
        onLongPress != null -> Modifier.r1RowPressable(onTap = onTapToggle, onLongPress = onLongPress)
        else -> Modifier.r1Pressable(onClick = onTapToggle, hapticOnClick = false)
    }
    // Pull the per-card override out of the CompositionLocal that the screen layer
    // (CardStackScreen / FavoritesPickerScreen) provides from settings.entityOverrides.
    // Apply the visibility fields by merging into a per-card LocalUiOptions so themes /
    // SwitchCard / ActionCard / SensorCard each see the right pill/area visibility
    // without having to know that overrides exist.
    val perCardOverride = com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides.current[state.id.value]
        ?: com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE
    val baseUi = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current
    // Memoise the merged UiOptions so every card recomposition doesn't allocate a fresh
    // copy + push it through CompositionLocalProvider (which re-invalidates every
    // descendant reader). Only re-allocate when either side actually changes.
    val mergedUi = androidx.compose.runtime.remember(baseUi, perCardOverride) {
        baseUi.copy(
            showOnOffPill = perCardOverride.showOnOffPill ?: baseUi.showOnOffPill,
            showAreaLabel = perCardOverride.showAreaLabel ?: baseUi.showAreaLabel,
            maxDecimalPlaces = perCardOverride.maxDecimalPlaces ?: baseUi.maxDecimalPlaces,
        )
    }
    // Aux-card styling — the sensor / select / action / switch variants draw their own
    // full-screen layouts instead of going through theme.Card, which used to leave them
    // on the plain near-black background under the Colourful Cards theme while every
    // theme.Card entity got its gradient sky. The theme's auxCardStyle hook closes that
    // gap: backdrop + scrim paint under the card and the ink palette rides in on
    // LocalCardInk so the variants' grey inks turn white over the gradients. Null (the
    // default for the other themes) keeps the classic rendering byte-identical — the
    // R1.Bg backdrop below matches what each card used to paint itself, and
    // DefaultCardInk == the R1 ink tokens the cards used to read directly. Remembered
    // because the style is a pure function of theme + entity id; rebuilding the gradient
    // Brush on every recomposition would be a per-detent allocation for nothing.
    // The per-card accent override participates in the aux backdrop: under
    // Colourful Cards a chosen colour recolours the gradient itself, so the
    // override must reach the hook (and key the remember) for sensor / action /
    // select / switch cards too.
    val auxOverrideAccent = perCardOverridePulledEarly?.accentColor
        ?.let { androidx.compose.ui.graphics.Color(it) }
    val auxStyle = androidx.compose.runtime.remember(theme, state.id.value, auxOverrideAccent) {
        theme.auxCardStyle(state.id.value, auxOverrideAccent)
    }
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalUiOptions provides mergedUi,
        com.github.itskenny0.r1ha.core.theme.LocalCardInk provides
            (auxStyle?.ink ?: com.github.itskenny0.r1ha.core.theme.DefaultCardInk),
        // Wrap-vs-fill mode for everything below (theme card bodies, the
        // value-bar scaffold, the aux variants); see the fillSlot KDoc.
        com.github.itskenny0.r1ha.core.theme.LocalCardFillSlot provides fillSlot,
    ) {
    Box(modifier = modifier.then(tapModifier)) {
        // Dim slightly when unavailable, but keep the friendly name legible — the previous
        // 0.35 alpha made labels almost unreadable, which mattered when the user was
        // trying to identify *which* entity had gone offline. 0.55 still reads as "this
        // is broken" without burying the text completely.
        val themeAlpha = if (state.isAvailable) 1f else 0.55f
        // Per-card accent override resolves once here so every card variant gets the
        // same colour. Null = fall back to the domain-derived role colour.
        val overrideAccent = perCardOverride.accentColor?.let { androidx.compose.ui.graphics.Color(it) }
        val resolvedAccent = overrideAccent ?: resolveAccentColor(accentRole)
        // Shared root modifier for the aux variants. The backdrop is painted here (after
        // the alpha so an unavailable entity dims gradient and content together) rather
        // than inside each card, so the cards stay theme-agnostic: plain R1.Bg when the
        // theme has no aux style — exactly what the cards used to paint themselves —
        // or the theme's backdrop + scrim when it does.
        val auxCardModifier = Modifier
            .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .alpha(themeAlpha)
            .then(
                if (auxStyle == null) {
                    Modifier.background(R1.Bg)
                } else {
                    val sky = Modifier.background(auxStyle.backdrop)
                    auxStyle.scrim?.let { sky.background(it) } ?: sky
                },
            )
        if (state.id.domain.isSensor) {
            SensorCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = sensorDomainLabel(state.id.domain),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                textSizeSp = perCardOverride.textSizeSp,
                modifier = auxCardModifier,
            )
        } else if (state.id.domain.isSelect) {
            // Settable-enum entities (select / input_select). Wheel cycles through
            // the options; tap opens a full-screen picker overlay similar to the
            // light-effect picker. Lifted to its own card variant rather than
            // bolted onto the percent / switch layouts because the value semantics
            // are fundamentally different (discrete labels, not on/off or 0..100).
            SelectCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = if (state.id.domain == Domain.INPUT_SELECT) "SELECT" else "SELECT",
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                textSizeSp = perCardOverride.textSizeSp,
                // Hint copy below the CHOOSE button only makes sense if the
                // wheel is actually wired to this card. Resolves the
                // three-state override to a concrete boolean so the picker is
                // the only affordance visible when the user has explicitly
                // turned wheel-cycling off (or the per-domain default does).
                wheelEnabled = perCardOverride.resolvedWheelEnabled(state.id.domain.prefix),
                modifier = auxCardModifier,
            )
        } else if (state.id.domain.isAction) {
            ActionCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = actionDomainLabel(state.id.domain),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                onFire = onTapToggle,
                modifier = auxCardModifier,
            )
        } else if (!state.supportsScalar) {
            SwitchCard(
                state = state,
                accent = resolvedAccent,
                // REMOTE shares the SWITCH glyph but should read as "REMOTE"
                // on the card chip — otherwise an IR blaster reads as a
                // generic switch and the user can't tell at a glance what
                // the card represents. Same idea for ALARM_CONTROL_PANEL,
                // which shares the LOCK glyph but represents a different
                // family of device. Domain-specific overrides here, all
                // other glyphs fall through to the glyph-derived label.
                domainLabel = when (state.id.domain) {
                    Domain.REMOTE -> "REMOTE"
                    Domain.ALARM_CONTROL_PANEL -> "ALARM"
                    else -> domainLabel(glyph)
                },
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                onTapToggle = onTapToggle,
                onSetOn = onSetOn ?: { _ -> onTapToggle() },
                modifier = auxCardModifier,
            )
        } else {
            // Domain-native display value — for climate / number entities the percent
            // abstraction is hidden ("21.5 °C" not "60 %", "42 W" not "60 %"). The trick
            // is that `state.percent` carries the OPTIMISTIC wheel input, so converting
            // percent → range-position gives a value that tracks the wheel live rather
            // than waiting for HA's echo. Falls back to state.raw (HA's confirmed value)
            // only when no scalar range is available.
            val isTempDomain = state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER
            val (displayValue, displayUnit) = when {
                isTempDomain &&
                    state.minRaw != null && state.maxRaw != null && state.percent != null -> {
                    val tempNative = state.minRaw + (state.percent / 100.0) * (state.maxRaw - state.minRaw)
                    // Snap to 0.5° (in native unit) so the display matches the service call.
                    val snappedNative = Math.round(tempNative * 2.0) / 2.0
                    val (converted, suffix) = convertTemperature(snappedNative, state.unit, mergedUi.tempUnit)
                    formatSensorValue(converted.toString(), maxDecimals = mergedUi.maxDecimalPlaces) to suffix
                }
                isTempDomain && state.raw != null -> {
                    val (converted, suffix) = convertTemperature(state.raw.toDouble(), state.unit, mergedUi.tempUnit)
                    formatSensorValue(converted.toString(), maxDecimals = mergedUi.maxDecimalPlaces) to suffix
                }
                (state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.NUMBER ||
                    state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.INPUT_NUMBER) &&
                    state.minRaw != null && state.maxRaw != null && state.percent != null -> {
                    val value = state.minRaw + (state.percent / 100.0) * (state.maxRaw - state.minRaw)
                    formatSensorValue(value.toString(), maxDecimals = mergedUi.maxDecimalPlaces) to state.unit
                }
                // Covers / valves read CLOSED / OPEN / OPENING / CLOSING instead of a bare
                // "0%" / "100%"; partial positions still fall through to the percent readout.
                state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.COVER ||
                    state.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.VALVE ->
                    coverStateLabel(state.rawState, state.percent) to null
                else -> null to null
            }
            // For light cards we also re-compute display from the wheel mode (overrides
            // the climate/number branches above which produce null displayValue for
            // light entities anyway). When in CT mode, the readout becomes "3500" + "K";
            // in HUE mode it becomes "240" + "°". BRIGHTNESS keeps the percent.
            val (lightDisplay, lightDisplayUnit) = computeLightDisplay(state, lightWheelMode, state.percent ?: 0, mergedUi)
            // Tape-meter tick labels — for climate / water_heater convert the native
            // min..max into the user's display unit so the bar's range matches the
            // big readout. Number / input_number pass through their native range. Null
            // → the meter falls back to its default 0..100 labels.
            val meterLabels = computeMeterLabels(state, mergedUi)
            theme.Card(
                model = CardRenderModel(
                    entityIdText = state.id.value,
                    friendlyName = state.friendlyName,
                    area = state.area,
                    // When showZeroPercentWhenOff is on, clamp the displayed percent to 0
                    // for any entity that is currently off, regardless of what HA reported.
                    // Useful for Zigbee / Z-Wave bulbs that preserve their pre-off brightness
                    // in HA's state: without this the arc shows e.g. "75 %" for a dark bulb.
                    percent = if (mergedUi.showZeroPercentWhenOff && !state.isOn) 0
                              else state.percent ?: 0,
                    isOn = state.isOn,
                    domainGlyph = glyph,
                    accent = accentRole,
                    isAvailable = state.isAvailable,
                    accentOverride = overrideAccent,
                    liveLightColor = if (state.id.domain == Domain.LIGHT) {
                        lightAccentArgb(
                            isOn = state.isOn,
                            hueDeg = state.hue,
                            colorTempK = state.colorTempK,
                            minColorTempK = state.minColorTempK,
                            maxColorTempK = state.maxColorTempK,
                        )?.let { androidx.compose.ui.graphics.Color(it) }
                    } else null,
                    displayValue = lightDisplay ?: displayValue,
                    displayUnit = lightDisplayUnit ?: displayUnit,
                    textSizeSp = perCardOverride.textSizeSp,
                    lightWheelMode = lightWheelMode,
                    lightEffect = state.effect,
                    lightEffectListSize = state.effectList.size,
                    lightEffectList = state.effectList,
                    lightAvailableModes = if (state.id.domain == Domain.LIGHT) {
                        com.github.itskenny0.r1ha.core.ha.LightWheelMode.availableFor(state.supportedColorModes)
                    } else emptyList(),
                    lightButtonsHidden = perCardOverride.lightButtonsHidden,
                    meterLabels = meterLabels,
                    mediaTitle = state.mediaTitle,
                    mediaArtist = state.mediaArtist,
                    mediaAlbumName = state.mediaAlbumName,
                    mediaDurationSec = state.mediaDuration,
                    mediaPositionSec = state.mediaPosition,
                    mediaPositionUpdatedAt = state.mediaPositionUpdatedAt,
                    mediaPicture = state.mediaPicture,
                    mediaSource = if (state.id.domain == Domain.MEDIA_PLAYER) state.mediaSource else null,
                    mediaIsPlaying = state.id.domain == Domain.MEDIA_PLAYER &&
                        state.rawState.equals("playing", ignoreCase = true),
                    mediaIsMuted = state.id.domain == Domain.MEDIA_PLAYER && state.isVolumeMuted,
                    mediaSupportedFeatures = state.mediaSupportedFeatures,
                    lastChangedAt = state.lastChanged,
                    entityState = state,
                    // Effective value-bar slot: per-card override wins over
                    // the global setting, falling back to RIGHT (the
                    // historical layout) when neither is set.
                    valueBarLocation = perCardOverride.valueBarLocation
                        ?: baseUi.valueBarLocation,
                    showIcon = mergedUi.cardStackIcons,
                ),
                modifier = Modifier
                    .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
                    .alpha(themeAlpha),
                onTapToggle = onTapToggle,
            )
        }
        if (!state.isAvailable) {
            Box(
                // matchParentSize (not fillMaxSize) on the content-height path so the
                // overlay covers the card without forcing the wrapper to full height.
                modifier = if (fillSlot) Modifier.fillMaxSize() else Modifier.matchParentSize(),
                contentAlignment = Alignment.Center,
            ) {
                // R1.sectionHeader + StatusRed reads consistent with the rest of the chrome
                // instead of Material's red — the previous `colorScheme.error` was close to
                // StatusRed but not identical, which broke the palette discipline.
                Text(
                    text = "UNAVAILABLE",
                    style = R1.sectionHeader,
                    color = R1.StatusRed,
                )
            }
        }
        // Long-press indicator — a tiny '⋯' glyph in the bottom-right corner
        // when the card has a long-press target configured. Discoverability
        // for the per-card long-press action (e.g. long-press the kitchen
        // light to trigger scene.dinner) — without this affordance the
        // feature is invisible until the user accidentally happens upon it.
        // Restored in r1ha-20260514-17xx after the PagerState stale-closure
        // fix made the scroll-up crash go away.
        if (onLongPress != null && state.isAvailable) {
            Text(
                text = "⋯",
                style = R1.labelMicro,
                // Card ink rather than the raw token so the dots stay visible on a
                // themed gradient backdrop; identical to R1.InkMuted everywhere else.
                color = com.github.itskenny0.r1ha.core.theme.LocalCardInk.current.muted,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 8.dp, bottom = 6.dp),
            )
        }
    }
    }
}

private fun resolveAccentColor(role: CardRenderModel.AccentRole) = when (role) {
    CardRenderModel.AccentRole.WARM -> com.github.itskenny0.r1ha.core.theme.R1.AccentWarm
    CardRenderModel.AccentRole.COOL -> com.github.itskenny0.r1ha.core.theme.R1.AccentCool
    CardRenderModel.AccentRole.GREEN -> com.github.itskenny0.r1ha.core.theme.R1.AccentGreen
    CardRenderModel.AccentRole.NEUTRAL -> com.github.itskenny0.r1ha.core.theme.R1.AccentNeutral
}

// Anchor whites for the colour-temperature ramp, as 0xRRGGBB (no alpha).
private const val WARM_WHITE_RGB = 0xFFB46A // ~2000 K incandescent amber
private const val COOL_WHITE_RGB = 0xCFE0FF // ~6500 K daylight blue-white

/**
 * The card accent for a colour-capable light, derived from the bulb's CURRENT reported
 * colour so the card visually echoes the light (HA's own UI does this). Returns a packed
 * 0xAARRGGBB int, or null when there's no live colour to show (bulb off, or a plain
 * on/off / brightness-only bulb) so the caller falls back to the domain role colour.
 *
 * HS colour mode wins and renders the hue at full saturation: saturation isn't plumbed
 * through, and a vivid chrome accent reads better on the card than a washed-out one.
 * Colour-temp mode maps the kelvin value across a warm-amber..cool-white ramp using the
 * bulb's own min/max kelvin (or sane 2000..6500 defaults). Pure + unit-tested.
 */
internal fun lightAccentArgb(
    isOn: Boolean,
    hueDeg: Double?,
    colorTempK: Int?,
    minColorTempK: Int?,
    maxColorTempK: Int?,
): Int? {
    if (!isOn) return null
    if (hueDeg != null) return hsvFullToArgb(hueDeg)
    if (colorTempK != null) {
        val minK = minColorTempK ?: 2000
        val maxK = maxColorTempK ?: 6500
        val span = (maxK - minK).coerceAtLeast(1)
        val t = ((colorTempK - minK).toDouble() / span).coerceIn(0.0, 1.0)
        return lerpRgbToArgb(WARM_WHITE_RGB, COOL_WHITE_RGB, t)
    }
    return null
}

/** HSV→ARGB at full saturation and value; [hueDeg] in degrees, wrapped into 0..360. */
private fun hsvFullToArgb(hueDeg: Double): Int {
    val h = ((hueDeg % 360.0) + 360.0) % 360.0
    val x = 1.0 - kotlin.math.abs((h / 60.0) % 2.0 - 1.0)
    val (r, g, b) = when {
        h < 60.0 -> Triple(1.0, x, 0.0)
        h < 120.0 -> Triple(x, 1.0, 0.0)
        h < 180.0 -> Triple(0.0, 1.0, x)
        h < 240.0 -> Triple(0.0, x, 1.0)
        h < 300.0 -> Triple(x, 0.0, 1.0)
        else -> Triple(1.0, 0.0, x)
    }
    return packArgb(
        kotlin.math.round(r * 255).toInt(),
        kotlin.math.round(g * 255).toInt(),
        kotlin.math.round(b * 255).toInt(),
    )
}

private fun lerpRgbToArgb(from: Int, to: Int, t: Double): Int {
    fun lerp(a: Int, b: Int) = kotlin.math.round(a + (b - a) * t).toInt()
    val r = lerp((from shr 16) and 0xFF, (to shr 16) and 0xFF)
    val g = lerp((from shr 8) and 0xFF, (to shr 8) and 0xFF)
    val b = lerp(from and 0xFF, to and 0xFF)
    return packArgb(r, g, b)
}

private fun packArgb(r: Int, g: Int, b: Int): Int =
    (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

private fun domainLabel(glyph: CardRenderModel.Glyph): String = when (glyph) {
    CardRenderModel.Glyph.LIGHT -> "LIGHT"
    CardRenderModel.Glyph.FAN -> "FAN"
    CardRenderModel.Glyph.COVER -> "COVER"
    CardRenderModel.Glyph.MEDIA_PLAYER -> "MEDIA"
    CardRenderModel.Glyph.SWITCH -> "SWITCH"
    CardRenderModel.Glyph.LOCK -> "LOCK"
    CardRenderModel.Glyph.HUMIDIFIER -> "HUMIDIFIER"
    CardRenderModel.Glyph.CLIMATE -> "CLIMATE"
    CardRenderModel.Glyph.NUMBER -> "NUMBER"
    CardRenderModel.Glyph.VALVE -> "VALVE"
    CardRenderModel.Glyph.VACUUM -> "VACUUM"
    CardRenderModel.Glyph.LAWN_MOWER -> "MOWER"
    CardRenderModel.Glyph.WATER_HEATER -> "WATER HEATER"
    CardRenderModel.Glyph.PERSON -> "PRESENCE"
    CardRenderModel.Glyph.WEATHER -> "WEATHER"
}

/** Action-card label — bypasses the Glyph-based mapping above because action entities
 *  never go through the theme.Card path. */
private fun actionDomainLabel(domain: Domain): String = when (domain) {
    Domain.SCENE -> "SCENE"
    Domain.SCRIPT -> "SCRIPT"
    Domain.BUTTON -> "BUTTON"
    Domain.INPUT_BUTTON -> "BUTTON"
    // Defensive: action-only path should only ever see action domains.
    else -> domain.prefix.uppercase()
}

/**
 * For light cards: compute the body readout + unit suffix from the current wheel mode.
 * BRIGHTNESS returns (null, null) so the caller falls through to the standard percent
 * display. CT returns the kelvin value the wheel currently maps to; HUE returns the
 * hue degrees. Range comes from the entity's min/max colour temp (CT) or a fixed
 * 0..360 (HUE).
 */
private fun computeLightDisplay(
    state: com.github.itskenny0.r1ha.core.ha.EntityState,
    mode: com.github.itskenny0.r1ha.core.ha.LightWheelMode?,
    pct: Int,
    ui: com.github.itskenny0.r1ha.core.prefs.UiOptions,
): Pair<String?, String?> {
    if (state.id.domain != Domain.LIGHT || mode == null) return null to null
    return when (mode) {
        com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> null to null
        com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
            val minK = state.minColorTempK ?: 2000
            val maxK = state.maxColorTempK ?: 6500
            val k = (minK + (pct / 100.0) * (maxK - minK)).toInt().coerceIn(minK, maxK)
            k.toString() to "K"
        }
        com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
            val hue = pct * 3.6  // 0..360
            // Locale-pinned so the hue readout keeps ASCII digits everywhere.
            "%.0f".format(java.util.Locale.US, hue) to "°"
        }
    }
}

/**
 * Vertical tape-meter tick labels (top→bottom) for the right-side bar on a value card.
 * Returns null when the default `100/75/50/25/0` percent labels are fine — that's lights,
 * fans, covers, media, humidifiers (all 0..100 scalars) — and a five-string list when the
 * card surfaces a domain-native range:
 *  • CLIMATE / WATER_HEATER → min..max converted to the user's tempUnit (e.g. `30°/24°/19°/14°/9°`)
 *  • NUMBER / INPUT_NUMBER → min..max in the entity's native unit
 * Skipped for non-scalar entities (no meter shown there) and for lights in CT/HUE mode
 * (the meter's `fraction` is still 0..1 and the readout already shows the converted
 * value — labelling the meter with kelvin/hue would clash with the brightness mode).
 */
private fun computeMeterLabels(
    state: com.github.itskenny0.r1ha.core.ha.EntityState,
    ui: com.github.itskenny0.r1ha.core.prefs.UiOptions,
): List<String>? {
    val isTempDomain = state.id.domain == Domain.CLIMATE || state.id.domain == Domain.WATER_HEATER
    val isNumberDomain = state.id.domain == Domain.NUMBER || state.id.domain == Domain.INPUT_NUMBER
    val min = state.minRaw ?: return null
    val max = state.maxRaw ?: return null
    if (!isTempDomain && !isNumberDomain) return null
    if (max <= min) return null
    // Five evenly-spaced points top→bottom: max, 75%, 50%, 25%, min.
    val ticks = listOf(1.0, 0.75, 0.5, 0.25, 0.0)
    return ticks.map { frac ->
        val nativeValue = min + frac * (max - min)
        if (isTempDomain) {
            val (converted, _) = convertTemperature(nativeValue, state.unit, ui.tempUnit)
            // Round to whole degrees on the meter — labels are small and the precise
            // decimal lives in the big readout. Drop the trailing zero so "21" reads
            // better than "21.0".
            val rounded = kotlin.math.round(converted).toInt()
            "$rounded°"
        } else {
            // Numbers: integer if min/max are integer-shaped, one decimal otherwise.
            // Avoids "0.0" / "100.0" on power switches while keeping precision when
            // the entity's range is e.g. 0..1.5.
            val integer = min == kotlin.math.floor(min) && max == kotlin.math.floor(max)
            if (integer) nativeValue.toInt().toString() else formatFixed(nativeValue, 1)
        }
    }
}

/** Sensor-card label — sensor and binary_sensor get distinct labels so the user can tell
 *  a numeric reading apart from a boolean trigger at a glance. */
private fun sensorDomainLabel(domain: Domain): String = when (domain) {
    Domain.SENSOR -> "SENSOR"
    Domain.BINARY_SENSOR -> "DETECTOR"
    // Person presence and weather render via SensorCard too — give them their own
    // header labels so the card chip reads "PRESENCE" / "WEATHER" instead of a generic
    // "SENSOR", matching the distinct body treatments in SensorCard.
    Domain.PERSON -> "PRESENCE"
    Domain.WEATHER -> "WEATHER"
    else -> domain.prefix.uppercase()
}

/** A human state word for a cover / valve readout: CLOSED / OPEN at the extremes, and
 *  OPENING / CLOSING while in transit (from the raw state). A partial position returns
 *  null so the caller falls back to the "N%" percent readout. A positionless cover (no
 *  current_position, so percent is null) reads its plain open/closed raw state instead of
 *  collapsing to a misleading "0%" / CLOSED. Pure + unit-tested. */
internal fun coverStateLabel(rawState: String?, percent: Int?): String? = when {
    rawState.equals("opening", ignoreCase = true) -> "OPENING"
    rawState.equals("closing", ignoreCase = true) -> "CLOSING"
    percent == 0 -> "CLOSED"
    percent == 100 -> "OPEN"
    // Partial position (1..99): let the caller show the precise "N%" readout.
    percent != null -> null
    // Positionless cover/valve: fall back to the raw open/closed state word.
    rawState.equals("open", ignoreCase = true) -> "OPEN"
    rawState.equals("closed", ignoreCase = true) -> "CLOSED"
    else -> null
}

/** Map a thermostat's HVAC mode to an accent so the card colour answers "heating or
 *  cooling?" at a glance. Cool modes read cool, heat reads warm, off reads neutral; auto /
 *  heat_cool and an absent mode keep the warm default. Pure + unit-tested. */
internal fun climateAccentRole(hvacMode: String?): CardRenderModel.AccentRole =
    when (hvacMode?.trim()?.lowercase()) {
        "cool", "dry", "fan_only" -> CardRenderModel.AccentRole.COOL
        "off" -> CardRenderModel.AccentRole.NEUTRAL
        else -> CardRenderModel.AccentRole.WARM
    }

/** Map a plain sensor's device_class to an accent colour. Read on the picker UI's
 *  domainAccentFor too so the picker chip and the card agree. */
private fun sensorAccent(deviceClass: String?): CardRenderModel.AccentRole = when (deviceClass?.lowercase()) {
    // Cool — physical environment readouts.
    "temperature", "humidity", "pressure", "atmospheric_pressure", "water" -> CardRenderModel.AccentRole.COOL
    // Warm — energy/power consumption.
    "power", "energy", "current", "voltage", "gas", "frequency" -> CardRenderModel.AccentRole.WARM
    // Green — outdoor/illuminance-ish.
    "illuminance", "wind_speed", "speed", "battery" -> CardRenderModel.AccentRole.GREEN
    // Cool — data-throughput/size classes feel like tech/information (blue-ish family).
    "data_size", "data_rate" -> CardRenderModel.AccentRole.COOL
    // Warm — solar irradiance is radiated heat/light energy.
    "irradiance" -> CardRenderModel.AccentRole.WARM
    // Neutral — sound pressure has no obvious colour affinity.
    "sound_pressure" -> CardRenderModel.AccentRole.NEUTRAL
    // Cool — humidity relative; absolute humidity is similarly environment-moisture.
    "absolute_humidity" -> CardRenderModel.AccentRole.COOL
    else -> CardRenderModel.AccentRole.NEUTRAL
}

/** Same idea for binary sensors. Motion / door / leak each get a sensible accent. */
private fun binarySensorAccent(deviceClass: String?): CardRenderModel.AccentRole = when (deviceClass) {
    // Warm — high-attention triggers (motion, smoke, gas).
    "motion", "occupancy", "presence", "smoke", "gas", "carbon_monoxide" -> CardRenderModel.AccentRole.WARM
    // Cool — environmental contacts.
    "door", "garage_door", "window", "opening", "moisture" -> CardRenderModel.AccentRole.COOL
    // Green — informational.
    "connectivity", "running", "plug" -> CardRenderModel.AccentRole.GREEN
    else -> CardRenderModel.AccentRole.NEUTRAL
}
