package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.ui.i18n.Text
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
        // Update entities are managed from the dedicated Updates screen and
        // shouldn't end up on the card stack via normal flows. Defensive
        // glyph keeps the when exhaustive without crashing if a user pins
        // one manually via raw favourites JSON.
        Domain.UPDATE -> CardRenderModel.Glyph.SWITCH
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
        // Thermostats run hot most of the time on a Rabbit — warm reads right for "this
        // controls temperature". Cooler accents can come back if/when a heat/cool sub-mode
        // colour pass lands alongside scalar target-temperature support.
        Domain.CLIMATE -> CardRenderModel.AccentRole.WARM
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
        // Update entity defensive accent — see the glyph branch for context.
        Domain.UPDATE -> CardRenderModel.AccentRole.COOL
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
    val tapModifier = when {
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
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalUiOptions provides mergedUi,
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
        if (state.id.domain.isSensor) {
            SensorCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = sensorDomainLabel(state.id.domain),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                textSizeSp = perCardOverride.textSizeSp,
                modifier = Modifier.fillMaxSize().alpha(themeAlpha),
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
                modifier = Modifier.fillMaxSize().alpha(themeAlpha),
            )
        } else if (state.id.domain.isAction) {
            ActionCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = actionDomainLabel(state.id.domain),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                onFire = onTapToggle,
                modifier = Modifier.fillMaxSize().alpha(themeAlpha),
            )
        } else if (!state.supportsScalar) {
            SwitchCard(
                state = state,
                accent = resolvedAccent,
                domainLabel = domainLabel(glyph),
                showArea = com.github.itskenny0.r1ha.core.theme.LocalUiOptions.current.showAreaLabel,
                onTapToggle = onTapToggle,
                onSetOn = onSetOn ?: { _ -> onTapToggle() },
                modifier = Modifier.fillMaxSize().alpha(themeAlpha),
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
                    mediaIsPlaying = state.id.domain == Domain.MEDIA_PLAYER &&
                        state.rawState.equals("playing", ignoreCase = true),
                    mediaIsMuted = state.id.domain == Domain.MEDIA_PLAYER && state.isVolumeMuted,
                    mediaSupportedFeatures = state.mediaSupportedFeatures,
                    lastChangedAt = state.lastChanged,
                    entityState = state,
                ),
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(themeAlpha),
                onTapToggle = onTapToggle,
            )
        }
        if (!state.isAvailable) {
            Box(
                modifier = Modifier.fillMaxSize(),
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
                color = R1.InkMuted,
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
            "%.0f".format(hue) to "°"
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
            if (integer) nativeValue.toInt().toString() else "%.1f".format(nativeValue)
        }
    }
}

/** Sensor-card label — sensor and binary_sensor get distinct labels so the user can tell
 *  a numeric reading apart from a boolean trigger at a glance. */
private fun sensorDomainLabel(domain: Domain): String = when (domain) {
    Domain.SENSOR -> "SENSOR"
    Domain.BINARY_SENSOR -> "DETECTOR"
    else -> domain.prefix.uppercase()
}

/** Map a plain sensor's device_class to an accent colour. Read on the picker UI's
 *  domainAccentFor too so the picker chip and the card agree. */
private fun sensorAccent(deviceClass: String?): CardRenderModel.AccentRole = when (deviceClass) {
    // Cool — physical environment readouts.
    "temperature", "humidity", "pressure", "atmospheric_pressure", "water" -> CardRenderModel.AccentRole.COOL
    // Warm — energy/power consumption.
    "power", "energy", "current", "voltage", "gas", "frequency" -> CardRenderModel.AccentRole.WARM
    // Green — outdoor/illuminance-ish.
    "illuminance", "wind_speed", "speed", "battery" -> CardRenderModel.AccentRole.GREEN
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
