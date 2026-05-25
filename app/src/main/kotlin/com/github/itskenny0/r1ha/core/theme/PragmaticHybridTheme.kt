package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Mission Control" — the default theme. Heavy orange on near-black, monospace numerals,
 * uppercase letter-spaced labels, and a horizontal tape-meter slider directly under the
 * percent readout (rather than running the full screen height on the right edge). The whole
 * card reads like an instrument panel: SOURCE on top, value in the centre, status pill below.
 */
object PragmaticHybridTheme : R1Theme {
    override val id = ThemeId.PRAGMATIC_HYBRID
    override val displayName = "Pragmatic Hybrid"
    override val systemBars = SystemBarColors(status = R1.Bg, nav = R1.Bg)
    override val baseline = sharedDarkBaseline

    private fun accentColor(role: CardRenderModel.AccentRole) = when (role) {
        CardRenderModel.AccentRole.WARM -> R1.AccentWarm
        CardRenderModel.AccentRole.COOL -> R1.AccentCool
        CardRenderModel.AccentRole.GREEN -> R1.AccentGreen
        CardRenderModel.AccentRole.NEUTRAL -> R1.AccentNeutral
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
        CardRenderModel.Glyph.WATER_HEATER -> "WATER HEATER"
        CardRenderModel.Glyph.LAWN_MOWER -> "MOWER"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        // Per-card accent override (from EntityOverride.accentColor) takes precedence
        // over the domain-derived role colour. Lets users tint individual cards without
        // touching their HA setup.
        // Resolution order for the card's accent colour:
        //   1. Per-card override (EntityOverride.accentColor) — most specific.
        //   2. Global theme-accent override (Settings → Theme → accent picker).
        //   3. Domain-derived role colour for this theme.
        val accent = model.accentOverride
            ?: LocalThemeAccentOverride.current
            ?: accentColor(model.accent)
        val ui = LocalUiOptions.current

        Row(
            modifier = modifier
                .fillMaxSize()
                .background(R1.Bg)
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            // ── Main content column ─────────────────────────────────────────────────
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                DomainHeader(
                    domainLabel = domainLabel(model.domainGlyph),
                    area = model.area,
                    accent = accent,
                    showArea = ui.showAreaLabel,
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 2,
                )
                // Auto-ticking 'last changed' label, isolated into its own
                // composable (RelativeTimeLabel) so the 5-second tick
                // recomposes ONLY the label — not the surrounding card body
                // with its BigReadout, light controls, tape meter, and
                // brightness chips. Spacer-then-label so the spacer also
                // disappears when the label renders empty (no lastChangedAt).
                if (model.lastChangedAt != null) {
                    Spacer(Modifier.height(2.dp))
                    com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                        at = model.lastChangedAt,
                        color = R1.InkMuted,
                        style = R1.labelMicro,
                    )
                }
                Spacer(Modifier.height(20.dp))
                // Hide the giant percent readout on every media_player card —
                // the right-side volume meter already conveys the volume %
                // and the now-playing block (always rendered for media,
                // below) carries the useful info. Earlier this also gated
                // on title-or-picture being non-null, but the user reported
                // the now-playing block disappearing on bigger screens
                // when state momentarily passed through a 'no title yet'
                // window — the conditional then re-enabled BigReadout for
                // 72 sp of vertical space, squeezing the now-playing
                // strip back in only after the next state-changed event.
                // Removing the conditional keeps the layout stable
                // regardless of title-load timing.
                val hideBigReadoutForMedia = model.domainGlyph ==
                    CardRenderModel.Glyph.MEDIA_PLAYER
                if (!hideBigReadoutForMedia) {
                    BigReadout(
                        percent = model.percent,
                        showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                        accent = accent,
                        // For entities that surface a domain-native value (climate "21 °C"),
                        // displayValue/displayUnit replace the percent number + "%" suffix.
                        overrideText = model.displayValue,
                        overrideUnit = model.displayUnit,
                        textSizeSp = model.textSizeSp,
                        lightEntityId = if (model.lightWheelMode != null) com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText) else null,
                        lightWheelMode = model.lightWheelMode,
                    )
                }
                // Light controls — segmented mode buttons (BRIGHT / WHITE / COLOUR) +
                // an EFFECTS button. The mode buttons surface only the modes the bulb
                // actually supports; the EFFECTS button is hidden when effect_list is
                // empty. Tapping a mode button switches the wheel target immediately;
                // tapping EFFECTS opens the effect picker overlay (rendered at screen
                // scope from CardStackScreen — see LightControlsRow's implementation).
                if (model.lightAvailableModes.size > 1 || model.lightEffectListSize > 0) {
                    Spacer(Modifier.height(8.dp))
                    LightControlsRow(
                        entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                        currentMode = model.lightWheelMode
                            ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS,
                        availableModes = model.lightAvailableModes,
                        currentEffect = model.lightEffect,
                        effectList = model.lightEffectList,
                        accent = accent,
                        hidden = model.lightButtonsHidden,
                    )
                }
                // Brightness preset chips on light cards. Three tap targets for
                // the most common brightness values — saves the user from
                // wheel-tuning to round numbers. Shown only when the entity is
                // a Light AND its scalar is wheel-controlled (i.e., BRIGHTNESS
                // mode). The setter is the same path the wheel uses
                // (LocalOnSetEntityPercent), so the optimistic override +
                // service-call debouncer kick in just like a wheel adjustment.
                // Restored after the PagerState stale-closure fix.
                if (model.domainGlyph == CardRenderModel.Glyph.LIGHT &&
                    (model.lightWheelMode == com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS ||
                        model.lightWheelMode == null)
                ) {
                    Spacer(Modifier.height(8.dp))
                    val onSetPercent = com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent.current
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(25, 50, 100).forEach { pct ->
                            val isCurrent = model.percent == pct
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(R1.ShapeS)
                                    .background(if (isCurrent) accent else R1.SurfaceMuted)
                                    .r1Pressable(onClick = {
                                        onSetPercent?.invoke(
                                            com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                                            pct,
                                        )
                                    })
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = "$pct%",
                                    style = R1.labelMicro,
                                    color = if (isCurrent) R1.Bg else accent,
                                )
                            }
                        }
                    }
                }
                // Media-player extras: now-playing block (album art +
                // title/artist + live progress) above the transport row.
                // Both render unconditionally for media_player cards —
                // the title-or-picture conditional was reported missing
                // on bigger screens (user observed the same entity
                // showing the full block on R1 but only the transport
                // row on a phone). Even when title + picture are both
                // null/blank MediaNowPlayingCompact gracefully renders
                // its empty state (the AsyncBitmap shows a ♪
                // placeholder, the text rows hide their own empty
                // strings), which is preferable to a missing block
                // mid-state-transition.
                if (model.domainGlyph == CardRenderModel.Glyph.MEDIA_PLAYER) {
                    Spacer(Modifier.height(10.dp))
                    com.github.itskenny0.r1ha.ui.components.MediaNowPlayingCompact(
                        title = model.mediaTitle,
                        artist = model.mediaArtist,
                        album = model.mediaAlbumName,
                        picture = model.mediaPicture,
                        durationSec = model.mediaDurationSec,
                        positionSec = model.mediaPositionSec,
                        positionUpdatedAt = model.mediaPositionUpdatedAt,
                        isPlaying = model.mediaIsPlaying,
                        accent = accent,
                    )
                    Spacer(Modifier.height(8.dp))
                    MediaControlsRow(
                        entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                        isPlaying = model.isOn,
                        accent = accent,
                        isMuted = model.mediaIsMuted,
                        supportedFeatures = model.mediaSupportedFeatures,
                    )
                    if (model.entityState != null) {
                        Spacer(Modifier.height(8.dp))
                        com.github.itskenny0.r1ha.ui.components.MediaExtrasPanel(
                            state = model.entityState,
                            accent = accent,
                        )
                    }
                }
                // Dedicated per-domain panels for scalar entities. Climate /
                // water_heater / valve land here when their setpoint range is
                // present (see DefaultHaRepository.supportsScalar); the panels
                // surface the discrete mode/command chips that the wheel + meter
                // can't represent.
                if (model.entityState != null) {
                    when (model.domainGlyph) {
                        CardRenderModel.Glyph.CLIMATE -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.ClimatePanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        CardRenderModel.Glyph.WATER_HEATER -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.WaterHeaterPanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        CardRenderModel.Glyph.VALVE -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.ValvePanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        else -> Unit
                    }
                }
                Spacer(Modifier.weight(1f))
                if (ui.showOnOffPill) OnOffPill(isOn = model.isOn, accent = accent)
            }

            // ── Vertical tape meter — inset from the right edge, ~200 dp tall ───────
            Spacer(Modifier.width(20.dp))
            VerticalTapeMeter(
                entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                percent = model.percent,
                accent = accent,
                tickLabels = model.meterLabels,
                // Rainbow fill when the wheel is in HUE mode — the bar then doubles as
                // a colour reference so the user can see what the wheel is selecting
                // (top: red, scrolling through to violet/red again at the bottom).
                rainbow = model.lightWheelMode == com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE,
            )
        }
    }
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

@Composable
private fun DomainHeader(
    domainLabel: String,
    area: String?,
    accent: Color,
    showArea: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Accent chip
        Box(
            modifier = Modifier
                .size(width = 14.dp, height = 4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(accent),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = domainLabel,
            style = R1.labelMicro,
            color = R1.Ink,
        )
        if (showArea && !area.isNullOrBlank()) {
            Spacer(Modifier.width(8.dp))
            Text(text = "·", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.width(8.dp))
            Text(
                text = area.replace('_', ' ').uppercase(),
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * The percent readout — single Text with the live value, plus a brief glitch treatment while
 * the wheel is actively churning. No per-digit AnimatedContent (the 180 ms slot-machine slide
 * stacked badly under fast wheel input, making the value visibly lag the wheel by hundreds of
 * ms). The glitch comes from (a) a small Y wobble on the whole readout, and (b) two ghost
 * copies in red and cyan offset left/right — both fade in only while changing.
 */
@Composable
internal fun BigReadout(
    percent: Int,
    showPercentSuffix: Boolean,
    accent: Color,
    overrideText: String? = null,
    overrideUnit: String? = null,
    /** Absolute readout size in sp; null = use theme default (R1.numeralXl, 72 sp). */
    textSizeSp: Int? = null,
    lightEntityId: com.github.itskenny0.r1ha.core.ha.EntityId? = null,
    lightWheelMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode? = null,
) {
    // Plain, snappy readout. Jitter and chromatic aberration were obscuring the live value
    // and chewing recomposition budget; the slider, the spring on the slider, and the
    // haptic on each detent already telegraph the wheel motion. We can layer subtler
    // effects back in once the core feel is solid.
    //
    // When overrideText is non-null (climate "21" with overrideUnit "°C") it replaces
    // both the percent number AND the percent suffix — domain-native readings are more
    // useful than a meaningless 60% on a thermostat.
    val bodyText = overrideText ?: percent.coerceIn(0, 100).toString()
    val suffixText = overrideUnit ?: if (showPercentSuffix) "%" else null
    // Apply the per-card absolute readout size. When textSizeSp is null, fall through to
    // the theme defaults (numeralXl 72 sp, numeralM 20 sp). When set, scale the suffix
    // (numeralM) proportionally so the unit doesn't dwarf the number — but floor it at
    // 8 sp so tiny readouts (6 sp for sensor headlines) don't make the suffix invisible.
    val defaultBodySp = EntityOverride.DEFAULT_TEXT_SIZE_SP
    val numeralStyle = if (textSizeSp != null) {
        R1.numeralXl.copy(
            fontSize = textSizeSp.sp,
            lineHeight = (textSizeSp * 1.05f).sp,
        )
    } else R1.numeralXl
    val suffixStyle = if (textSizeSp != null) {
        val proportionalSuffix = (textSizeSp.toFloat() / defaultBodySp.toFloat() *
            R1.numeralM.fontSize.value).coerceAtLeast(8f)
        R1.numeralM.copy(fontSize = proportionalSuffix.sp)
    } else R1.numeralM
    // Readout row only. The mode-cycle gesture moved off the readout (it was invisible
    // to users — see #117) and onto explicit segmented BRIGHT / WHITE / COLOUR buttons
    // rendered by [LightControlsRow] below the readout. lightEntityId / lightWheelMode
    // remain as params so themes still receive enough context for future visual hooks
    // (e.g. accent tint based on which mode is active).
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            text = bodyText,
            style = numeralStyle,
            color = R1.Ink,
        )
        if (suffixText != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = suffixText,
                style = suffixStyle,
                color = accent,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
}

/**
 * Vertical tape meter, inset from the right edge of the card. Track is a 2 dp hairline; fill
 * grows from the bottom and ends in a 4 dp accent-coloured thumb at the current value.
 * [rememberSliderFraction] gives the fill a snappy bouncy spring — each detent visibly
 * "jumps" past the target and settles, which reads as mechanical feedback per click.
 */
@Composable
internal fun VerticalTapeMeter(
    /** Entity this meter is for — used by the touch-drag and tick-tap callbacks so
     *  they target the right card even when (in theory) two meters are visible at
     *  once during a swipe. Themes/previews without a real entity can pass a synthetic
     *  EntityId; the setter callback gracefully ignores unknown ids. */
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    percent: Int,
    accent: Color,
    /** Top→bottom tick labels. Null = the default 0..100 percent labels. Climate /
     *  number cards pass their domain-native range so the meter reads in real units. */
    tickLabels: List<String>? = null,
    /** When true (HUE mode on a light card), render the FULL track as a rainbow
     *  gradient and use a white-bordered thumb at the current hue instead of an
     *  accent-coloured grow-from-bottom fill. The track then doubles as a colour
     *  reference for the wheel. */
    rainbow: Boolean = false,
    /** Override the tick-label colour. Defaults to [R1.InkMuted] which reads cleanly
     *  on the dark theme backgrounds; the Colourful Cards theme passes a near-white
     *  here because the gradient backdrop renders muted-grey labels invisible. */
    tickLabelColor: Color = R1.InkMuted,
) {
    val fraction = rememberSliderFraction(percent).coerceIn(0f, 1f)
    val labels = tickLabels ?: listOf("100", "75", "50", "25", "0")
    // Touch-drag + tick-tap setter — wired by the screen layer to VM.setEntityPercent.
    // When unavailable (previews) the meter still renders cleanly but the gestures are
    // no-ops; we skip wrapping the modifier so non-interactive consumers don't pick up
    // a useless pointerInput.
    val onSetPercent = com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent.current
    val interactive = onSetPercent != null
    // Tick row labels — at fixed Y positions, monospace tiny text on the inside edge.
    Row(
        modifier = Modifier.fillMaxHeight(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Tick labels — vertically distributed alongside the track. List is top→bottom.
        // Each label is its own clickable jump-to-value: the first label (e.g. "100")
        // sets the meter to 100%, the last ("0") to 0%, evenly spaced in between. With
        // five labels by convention the increments land on 25 / 50 / 75. Domain meters
        // (climate min..max) use the same mapping so the user can tap a temperature
        // string to jump straight to that setpoint.
        Column(
            modifier = Modifier.fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.End,
        ) {
            labels.forEachIndexed { idx, tick ->
                // Convert the label index into a target percent. Top label = 100, bottom
                // = 0; evenly spaced.
                val targetPct = if (labels.size <= 1) 100
                    else (100f * (labels.size - 1 - idx) / (labels.size - 1)).toInt()
                val labelMod = if (interactive) {
                    Modifier
                        .clip(R1.ShapeS)
                        .r1Pressable(onClick = { onSetPercent?.invoke(entityId, targetPct) })
                        // Generous padding both makes the tap target finger-friendly on
                        // the R1's narrow display and visually keeps the label in the
                        // same place — we trim the padding so the column's even
                        // distribution still feels honest.
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                } else Modifier
                Text(
                    text = tick,
                    style = R1.numeralS,
                    color = tickLabelColor,
                    modifier = labelMod,
                )
            }
        }
        Spacer(Modifier.width(6.dp))
        // Track + fill + thumb. Anchor to BottomCenter so the fill grows upward.
        // The track itself is touch-draggable: press at any Y to jump there, drag to
        // scrub. We compute fraction = 1 - (Y / trackHeight) inside detectDragGestures
        // because pointer Y is in pixels-from-the-top while the meter conceptually fills
        // from the bottom. The pointerInput modifier consumes the touch so the
        // surrounding card-level tap-to-toggle doesn't also fire when the user just
        // wanted to scrub. Skipped entirely when there's no setter (preview mode).
        val trackHeightPx = androidx.compose.runtime.remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
        val trackInteractionMod = if (interactive) {
            Modifier
                .onSizeChanged { trackHeightPx.floatValue = it.height.coerceAtLeast(1).toFloat() }
                .pointerInput(entityId) {
                    // Manual gesture loop — awaitEachGesture / awaitFirstDown live in
                    // androidx.compose.ui.input.pointer (the Compose-UI surface that's
                    // always on the classpath) so this path doesn't pull in any extra
                    // foundation-gesture symbols. Behaviour matches detectVerticalDrag-
                    // Gestures: initial touch is treated as a tap-to-set, subsequent
                    // movement events scrub continuously, and every change is consumed
                    // so the surrounding card-tap doesn't also fire.
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val initial = (1f - down.position.y / trackHeightPx.floatValue)
                            .coerceIn(0f, 1f)
                        onSetPercent?.invoke(entityId, (initial * 100f).toInt().coerceIn(0, 100))
                        down.consume()
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null || !change.pressed) break
                            if (change.position != change.previousPosition) {
                                val frac = (1f - change.position.y / trackHeightPx.floatValue)
                                    .coerceIn(0f, 1f)
                                onSetPercent?.invoke(entityId, (frac * 100f).toInt().coerceIn(0, 100))
                            }
                            change.consume()
                        }
                    }
                }
        } else Modifier
        // Outer Box stays wide for the touch-drag hit area (so fingers can scrub
        // without having to land on a 2 dp hairline) but the inner track / fill /
        // thumb all align to CenterEnd so the visible bar sits flush against the
        // right edge — the way it always was before touch scrubbing was added.
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(24.dp)
                .then(trackInteractionMod),
        ) {
            if (rainbow) {
                // Rainbow track — full-height vertical gradient covering the hue
                // wheel (0..360°). Top = red (0°), middle = green (~120°), bottom =
                // violet/red again (300°→360°). Slightly wider than the standard
                // 2 dp hairline so the colours are actually legible.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(6.dp)
                        .align(Alignment.CenterEnd)
                        .clip(RoundedCornerShape(3.dp))
                        .background(androidx.compose.ui.graphics.Brush.verticalGradient(rainbowStops())),
                )
            } else {
                // Hairline track.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .align(Alignment.CenterEnd)
                        .background(R1.SurfaceMuted),
                )
                // Fill — grows from the bottom up to `fraction` of available height.
                // Skipped in rainbow mode because there's no "level" being displayed;
                // the thumb position alone communicates the wheel value.
                Box(
                    modifier = Modifier
                        .fillMaxHeight(fraction)
                        .width(4.dp)
                        .align(Alignment.BottomEnd)
                        .clip(RoundedCornerShape(2.dp))
                        .background(accent),
                )
            }
            // Thumb — a 14 dp wide capsule sitting at the top of the fill, also
            // pinned to the right edge. Rainbow mode adds a 1 dp dark border so it
            // stays visible against any colour beneath.
            ThumbCapsule(
                fraction = fraction,
                accent = if (rainbow) R1.Ink else accent,
                borderInRainbow = rainbow,
            )
        }
    }
}

@Composable
private fun ThumbCapsule(fraction: Float, accent: Color, borderInRainbow: Boolean = false) {
    // BoxWithConstraints lets us compute the absolute thumb Y from `fraction` cheaply —
    // recomposes only when `fraction` does (post-spring settle). The thumb is pinned to
    // the right edge (TopEnd alignment + the parent Box is right-aligned) so the visible
    // slider stays flush against the card's right edge even though the touch-pickup Box
    // is wider than the bar.
    androidx.compose.foundation.layout.BoxWithConstraints(
        modifier = Modifier.fillMaxHeight(),
        contentAlignment = Alignment.TopEnd,
    ) {
        val trackH = maxHeight
        val thumbH = 6.dp
        val travel = trackH - thumbH
        // fraction = 1.0 → thumb at the top; fraction = 0.0 → thumb at the bottom.
        // `offset` (not `padding`) because the slider's spring overshoots — a fraction of
        // 1.05 briefly produces a negative `offsetFromTop`, and `padding` crashes on
        // negative Dp. `offset` accepts any Dp, so a tiny visible overshoot is fine.
        val offsetFromTop = travel * (1f - fraction)
        Box(
            modifier = Modifier
                .offset(y = offsetFromTop)
                .width(14.dp)
                .height(thumbH + if (borderInRainbow) 2.dp else 0.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent)
                .let { m ->
                    if (borderInRainbow) m.border(1.dp, R1.Bg, RoundedCornerShape(3.dp)) else m
                },
        )
    }
}

/**
 * Light-card controls — segmented mode buttons (BRIGHT / WHITE / COLOUR) and an
 * EFFECTS button. Replaces the previous tap-to-cycle gestures, which were invisible to
 * users (they only manifested as a near-invisible "TAP READOUT TO CYCLE" hint below
 * the readout). The segmented buttons are unmistakable affordances; tapping any of
 * them sets the wheel mode directly via [LocalOnSetLightWheelMode]. The EFFECTS button
 * opens a full-screen picker (see [EffectPickerSheet]) so the user sees every
 * available effect at once rather than blindly cycling.
 *
 * Modes are filtered against [availableModes] so a tunable-white bulb doesn't surface
 * a COLOUR button it can't honour. The EFFECTS button is hidden entirely when the
 * bulb's effect_list is empty.
 */
@Composable
internal fun LightControlsRow(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    currentMode: com.github.itskenny0.r1ha.core.ha.LightWheelMode,
    availableModes: List<com.github.itskenny0.r1ha.core.ha.LightWheelMode>,
    currentEffect: String?,
    effectList: List<String>,
    accent: Color,
    /** Per-card hidden-button set from [EntityOverride.lightButtonsHidden]. Buttons in
     *  this set are filtered out before render — used to declutter cards the user
     *  rarely tweaks beyond brightness. Empty = show everything available. */
    hidden: Set<com.github.itskenny0.r1ha.core.prefs.LightCardButton> = emptySet(),
) {
    val onSetMode = com.github.itskenny0.r1ha.core.theme.LocalOnSetLightWheelMode.current
    val onOpenPicker = com.github.itskenny0.r1ha.core.theme.LocalOnOpenEffectPicker.current
    // Filter the available modes through the per-card hidden set. The mapping from
    // LightWheelMode to LightCardButton is one-to-one for BRIGHTNESS/COLOR_TEMP/HUE
    // (the three wheel modes); FX is handled separately below as a non-mode button.
    val visibleModes = availableModes.ifEmpty {
        listOf(com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS)
    }.filter { mode ->
        val asButton = when (mode) {
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> com.github.itskenny0.r1ha.core.prefs.LightCardButton.BRIGHTNESS
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> com.github.itskenny0.r1ha.core.prefs.LightCardButton.WHITE
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> com.github.itskenny0.r1ha.core.prefs.LightCardButton.HUE
        }
        asButton !in hidden
    }
    // Column rather than Row — the R1 is a portrait device with abundant vertical
    // space but only 240 px wide, so a horizontal row of three mode buttons plus an
    // FX button collided into the right-side meter. Stacking them gives each button
    // the full card width and stays comfortable even when the user picks long labels.
    Column(modifier = Modifier.fillMaxWidth()) {
        if (visibleModes.size > 1) {
            visibleModes.forEachIndexed { idx, mode ->
                val active = mode == currentMode
                val label = when (mode) {
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> "BRIGHT"
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> "WHITE"
                    com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> "HUE"
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(R1.ShapeS)
                        .background(if (active) accent else R1.SurfaceMuted)
                        .let { m ->
                            if (onSetMode != null) m.r1Pressable(onClick = { onSetMode(entityId, mode) }) else m
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    Text(
                        text = label,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                }
                if (idx < visibleModes.lastIndex) Spacer(Modifier.height(4.dp))
            }
        }
        // EFFECTS button — surfaces only on bulbs that expose effect_list. The label
        // is just "FX" with a small ● indicator on the right when an effect is active,
        // never the effect name itself (long Nanoleaf effect names like "Northern
        // Lights" would dominate the card; the active name belongs in the picker, not
        // on the dismissed button). Tap opens the screen-level picker via
        // [LocalOnOpenEffectPicker] — the actual sheet lives in CardStackScreen so it
        // can render full-screen above the card chrome.
        if (effectList.isNotEmpty() && onOpenPicker != null &&
            com.github.itskenny0.r1ha.core.prefs.LightCardButton.EFFECTS !in hidden
        ) {
            if (visibleModes.size > 1) Spacer(Modifier.height(4.dp))
            val active = !currentEffect.isNullOrBlank()
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onOpenPicker(entityId) })
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "FX",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    if (active) {
                        Spacer(Modifier.width(6.dp))
                        // Filled dot in accent reads as "an effect is on" without
                        // dominating the button. Open the picker to see / change which.
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(androidx.compose.foundation.shape.CircleShape)
                                .background(accent),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Media-player control row — discrete transport / volume buttons under the volume
 * readout. The user's HA dashboard typically has these as icon buttons on a media-
 * player card and this app's wheel-only volume control didn't surface them, so
 * pause/play/skip/back/vol± lived purely in the wheel (volume) or nowhere (the
 * transport actions). Stacked vertically to match the light-card pattern: the R1 is
 * 240 px wide and a single row of five glyph buttons feels cramped.
 *
 * Transport buttons live on the top row (◀ ⏯ ▶), volume on the bottom row (- +)
 * plus mute (∅). Three-button rows are still narrow enough at 240 px to read
 * cleanly while keeping the section short vertically — two rows fit under the
 * readout without crowding the deck chrome.
 */
@Composable
internal fun MediaControlsRow(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    isPlaying: Boolean,
    accent: Color,
    isMuted: Boolean = false,
    /** [com.github.itskenny0.r1ha.core.ha.EntityState.mediaSupportedFeatures]
     *  bitmask. Used for *dimming* unadvertised buttons rather than hiding them
     *  outright — a previous version hid buttons when their bit wasn't set, but
     *  some integrations don't advertise transport bits even though their
     *  services do work (and others rely on user-experimentation to discover
     *  what's wired up). Visually dimming gives the user a hint that the action
     *  *might* not be supported without removing the affordance entirely. The
     *  expandable toast still surfaces HA's validation error when the call is
     *  rejected so the user knows why nothing happened. */
    supportedFeatures: Int = 0,
) {
    val onTransport = com.github.itskenny0.r1ha.core.theme.LocalOnMediaTransport.current
    // 0 = 'no info' (integration didn't advertise) — treat every action as
    // potentially-supported. Non-zero bitmask gives us bit-precision: missing
    // bits dim the corresponding button but it stays tappable so the user can
    // try anyway (and see the expandable error if HA rejects).
    val noInfo = supportedFeatures == 0
    val advertisesPrev = noInfo ||
        (supportedFeatures and com.github.itskenny0.r1ha.core.ha.EntityState.MediaPlayerFeature.PREVIOUS_TRACK) != 0
    val advertisesNext = noInfo ||
        (supportedFeatures and com.github.itskenny0.r1ha.core.ha.EntityState.MediaPlayerFeature.NEXT_TRACK) != 0
    val advertisesPlayPause = noInfo ||
        (supportedFeatures and (
            com.github.itskenny0.r1ha.core.ha.EntityState.MediaPlayerFeature.PLAY or
                com.github.itskenny0.r1ha.core.ha.EntityState.MediaPlayerFeature.PAUSE
        )) != 0
    val advertisesMute = noInfo ||
        (supportedFeatures and com.github.itskenny0.r1ha.core.ha.EntityState.MediaPlayerFeature.VOLUME_MUTE) != 0

    // Glyph-only row — ⏮ / ⏯ / ⏭ / speaker. Previous version paired each glyph
    // with a 4-char text label, but on the R1's narrow card area each button's
    // slot is ~50 px wide and the labels wrapped mid-word (BAC/K, PAU/SE,
    // NEX/T, MUT/E). The music-control glyphs are universal and read better
    // without the text. Vol± buttons were dropped earlier since the slider
    // covers volume. Buttons whose features aren't advertised render with a
    // dim alpha so the user has a visual hint, but they still fire — HA's
    // expandable validation error tells them when something genuinely isn't
    // wired up.
    Row(modifier = Modifier.fillMaxWidth()) {
        MediaButton(
            onClick = { onTransport?.invoke(entityId, com.github.itskenny0.r1ha.core.ha.MediaTransport.PREVIOUS) },
            accent = accent,
            modifier = Modifier.weight(1f),
            dimmed = !advertisesPrev,
        ) {
            Text(text = "⏮", style = R1.numeralM, color = accent)
        }
        Spacer(Modifier.width(4.dp))
        MediaButton(
            onClick = { onTransport?.invoke(entityId, com.github.itskenny0.r1ha.core.ha.MediaTransport.PLAY_PAUSE) },
            accent = accent,
            emphasis = true,
            modifier = Modifier.weight(1f),
            dimmed = !advertisesPlayPause,
        ) {
            Text(text = if (isPlaying) "⏸" else "▶", style = R1.numeralM, color = R1.Bg)
        }
        Spacer(Modifier.width(4.dp))
        MediaButton(
            onClick = { onTransport?.invoke(entityId, com.github.itskenny0.r1ha.core.ha.MediaTransport.NEXT) },
            accent = accent,
            modifier = Modifier.weight(1f),
            dimmed = !advertisesNext,
        ) {
            Text(text = "⏭", style = R1.numeralM, color = accent)
        }
        Spacer(Modifier.width(4.dp))
        // Mute toggle. When currently muted, render with emphasis (accent fill +
        // slashed speaker glyph in Bg). When unmuted, surface-muted background
        // with the speaker emitting two waves in accent. Two independent cues
        // (background fill + glyph shape) so state is unambiguous.
        MediaButton(
            onClick = { onTransport?.invoke(entityId, com.github.itskenny0.r1ha.core.ha.MediaTransport.MUTE_TOGGLE) },
            accent = accent,
            emphasis = isMuted,
            modifier = Modifier.weight(1f),
            dimmed = !advertisesMute,
        ) {
            com.github.itskenny0.r1ha.ui.components.SpeakerGlyph(
                isMuted = isMuted,
                tint = if (isMuted) R1.Bg else accent,
                size = 22.dp,
            )
        }
    }
}


/**
 * Single media-control button — content slot in the middle. The emphasis flag
 * fills with accent (used on play/pause so it's the obvious primary action of
 * the row, and on the mute button when currently muted so the active state
 * stands out); the others are surface-muted. Tall enough (~36 dp) for a
 * comfortable tap target without crowding the rest of the card. The slot is a
 * composable so callers can pass either a Text (transport glyphs) or a custom
 * Canvas-drawn icon (the speaker glyph for mute).
 *
 * [dimmed] paints the whole tile at reduced alpha to hint that the action
 * isn't advertised as supported by the integration — but the tap still fires,
 * because some integrations under-report features yet handle the call fine,
 * and the user benefits from a try-and-see affordance over a hidden one. When
 * HA rejects, the expandable toast surfaces the validation error so the user
 * learns what happened.
 */
@Composable
private fun MediaButton(
    onClick: () -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    emphasis: Boolean = false,
    dimmed: Boolean = false,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .alpha(if (dimmed) 0.38f else 1f)
            .clip(R1.ShapeS)
            .background(if (emphasis) accent else R1.SurfaceMuted)
            .r1Pressable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        content()
    }
}

/**
 * Fullscreen overlay listing every effect from the bulb's `effect_list`, plus a NONE
 * entry at the top that clears the effect. The active effect is highlighted in accent.
 * Tapping any row applies it via [LocalOnSetLightEffect] and dismisses. A CLOSE row at
 * the bottom and a backdrop-tap also dismiss. Scrolls vertically so bulbs with long
 * effect lists (Nanoleaf can ship 30+) are usable on the R1's 320 px tall display.
 */
@Composable
internal fun EffectPickerSheet(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    current: String?,
    effects: List<String>,
    accent: Color,
    onPick: (String?) -> Unit,
    onDismiss: () -> Unit,
) {
    // R1 system back dismisses the picker without applying.
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // Backdrop — captures taps outside the list so the user can dismiss by tapping any
    // empty area. The list itself is full-bleed so there isn't actually much "outside",
    // but the CLOSE chip at the top right guarantees a discoverable dismiss path.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            // Header row — title + a CLOSE chip on the right.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "EFFECTS",
                    style = R1.sectionHeader,
                    color = R1.Ink,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Spacer(Modifier.height(10.dp))
            // Scrollable list of effects with NONE at the top.
            val scroll = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .androidxVerticalScroll(scroll),
            ) {
                EffectRow(label = "NONE", isActive = current.isNullOrBlank(), accent = accent) {
                    onPick(null)
                }
                effects.forEach { name ->
                    EffectRow(label = name, isActive = name == current, accent = accent) {
                        onPick(name)
                    }
                }
                // Bottom padding so the last row isn't flush with the screen edge,
                // making it harder to scroll past on touch.
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

/**
 * Fullscreen overlay listing every option from a `select.*` / `input_select.*`
 * entity's `options` attribute. The active option is highlighted in accent. Same
 * UX shape as [EffectPickerSheet] (tap row to apply, CLOSE chip / system back to
 * dismiss) so users only have to learn one picker convention.
 */
@Composable
internal fun SelectPickerSheet(
    entityId: com.github.itskenny0.r1ha.core.ha.EntityId,
    current: String?,
    options: List<String>,
    accent: Color,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "OPTIONS", style = R1.sectionHeader, color = R1.Ink)
                Spacer(Modifier.weight(1f))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Spacer(Modifier.height(10.dp))
            val scroll = androidx.compose.foundation.rememberScrollState()
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .androidxVerticalScroll(scroll),
            ) {
                options.forEach { name ->
                    EffectRow(label = name, isActive = name == current, accent = accent) {
                        onPick(name)
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun EffectRow(label: String, isActive: Boolean, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(R1.ShapeS)
            .background(if (isActive) accent else R1.SurfaceMuted)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Small radio-style indicator on the left so the active row is identifiable
            // even at a glance — accent fill on the chip helps but the bullet pins it.
            Text(
                text = if (isActive) "●" else "○",
                style = R1.labelMicro,
                color = if (isActive) R1.Bg else R1.InkSoft,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = label.uppercase(),
                style = R1.body,
                color = if (isActive) R1.Bg else R1.Ink,
            )
        }
    }
}

/**
 * Vertical-gradient colour stops for the HUE-mode rainbow track. Distinct points are
 * pinned at red / yellow / green / cyan / blue / magenta / red so the gradient walks
 * once around the colour wheel from top (0°) to bottom (360°). The wheel-in-hue-mode
 * percent maps 0..100 onto 0..360°, so the user can read off the rough colour by
 * looking where the thumb sits on the track.
 */
private fun rainbowStops(): List<Color> = listOf(
    Color(0xFFFF0000), // 0°   red
    Color(0xFFFFFF00), // 60°  yellow
    Color(0xFF00FF00), // 120° green
    Color(0xFF00FFFF), // 180° cyan
    Color(0xFF0000FF), // 240° blue
    Color(0xFFFF00FF), // 300° magenta
    Color(0xFFFF0000), // 360° red — closes the wheel
)

/** Alias for `androidx.compose.foundation.verticalScroll` so the picker call site reads
 *  cleanly. Keeps the import surface small at the top of the file. */
private fun Modifier.androidxVerticalScroll(
    state: androidx.compose.foundation.ScrollState,
): Modifier = this.then(verticalScroll(state))

@Composable
private fun OnOffPill(isOn: Boolean, accent: Color) {
    val (label, fg, bg) = if (isOn) {
        Triple("● ON", R1.Bg, accent)
    } else {
        Triple("○ OFF", R1.InkSoft, R1.SurfaceMuted)
    }
    Box(
        modifier = Modifier
            .clip(R1.ShapeM)
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = fg)
    }
}
