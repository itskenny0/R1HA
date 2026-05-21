package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

/**
 * "Minimal Dark" — pure black, single orange accent, no ornamental colour. Reuses the
 * full feature surface from [PragmaticHybridTheme] (light controls, media controls,
 * touch-drag slider with clickable tick labels, effect / select pickers via the
 * screen-level overlay) so the theme is fully featured rather than a visually-pretty
 * dead-end. The distinct identity here is the black background, the monochrome accent
 * (no per-domain tinting), and the inline-text on/off pill — everything else is the
 * same shared building blocks as the default theme.
 */
object MinimalDarkTheme : R1Theme {
    override val id = ThemeId.MINIMAL_DARK
    override val displayName = "Minimal Dark"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline.copy(background = Color.Black, surface = Color.Black)

    private val themeAccent = R1.AccentWarm

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
        val ui = LocalUiOptions.current
        // Per-card accent override (from EntityOverride.accentColor) takes precedence
        // over MinimalDark's single warm accent. Lets the user tint a card without
        // switching themes.
        // Per-card override > global theme-accent override > theme native accent.
        val accent = model.accentOverride
            ?: LocalThemeAccentOverride.current
            ?: themeAccent
        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // Header — monochrome tag instead of the accent dash.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 2.dp)
                            .background(R1.InkSoft),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domainLabel(model.domainGlyph),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    if (ui.showAreaLabel && !model.area.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = R1.labelMicro, color = R1.InkMuted)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.area.replace('_', ' ').uppercase(),
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 2,
                )
                // 'Last changed' relative-time label — parity with
                // PragmaticHybridTheme. Localised into its own composable so
                // the 5 s ticker doesn't recompose the whole card.
                if (model.lastChangedAt != null) {
                    Spacer(Modifier.height(2.dp))
                    com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                        at = model.lastChangedAt,
                        color = R1.InkMuted,
                        style = R1.labelMicro,
                    )
                }
                Spacer(Modifier.height(20.dp))
                // Hide the giant percent readout on media_player cards that are
                // currently playing — same logic as PragmaticHybridTheme. The
                // now-playing block + the right-side meter already convey
                // volume, so a 72 sp '100 %' on top of them squeezed the
                // now-playing block into a thumbnail-sized strip.
                val hideBigReadoutForMedia = model.domainGlyph ==
                    CardRenderModel.Glyph.MEDIA_PLAYER &&
                    (!model.mediaTitle.isNullOrBlank() || !model.mediaPicture.isNullOrBlank())
                if (!hideBigReadoutForMedia) {
                    BigReadout(
                        percent = model.percent,
                        showPercentSuffix = ui.displayMode == DisplayMode.PERCENT,
                        accent = accent,
                        overrideText = model.displayValue,
                        overrideUnit = model.displayUnit,
                        textSizeSp = model.textSizeSp,
                        lightEntityId = if (model.lightWheelMode != null) com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText) else null,
                        lightWheelMode = model.lightWheelMode,
                    )
                }
                // Light controls (BRIGHT / WHITE / HUE / FX) and Media controls — same
                // shared building blocks as PragmaticHybridTheme. Surface only when
                // the entity supports them; otherwise hidden.
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
                if (model.domainGlyph == CardRenderModel.Glyph.MEDIA_PLAYER) {
                    // Now-playing block always renders for media_player —
                    // earlier this gated on title-or-picture being
                    // non-null, but the user reported the block missing
                    // on bigger screens for the same entity that
                    // showed it on R1. MediaNowPlayingCompact already
                    // hides its own empty rows, so the unconditional
                    // call is safe even when no media is loaded yet.
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
                if (ui.showOnOffPill) {
                    // Minimal pill — text-only, no background fill. Reads like a status
                    // line rather than a control.
                    Text(
                        text = if (model.isOn) "● ON" else "○ OFF",
                        style = R1.labelMicro,
                        color = if (model.isOn) accent else R1.InkMuted,
                    )
                }
            }
            Spacer(Modifier.width(20.dp))
            // Shared meter — gives this theme the same touch-drag + clickable-tick
            // affordances as the default. Tick labels stay readable on black via R1.
            // InkMuted; rainbow mode renders correctly for light HUE.
            VerticalTapeMeter(
                entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                percent = model.percent,
                accent = accent,
                tickLabels = model.meterLabels,
                rainbow = model.lightWheelMode == com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE,
            )
        }
    }
}
