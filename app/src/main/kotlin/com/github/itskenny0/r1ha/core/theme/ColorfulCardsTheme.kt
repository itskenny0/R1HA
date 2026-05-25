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
import androidx.compose.foundation.shape.RoundedCornerShape
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ThemeId

/**
 * "Colourful Cards" — a per-entity gradient sky behind the same Mission-Control
 * layout. Each card gets a stable palette hashed from its entity_id so two lights
 * named "kitchen" and "lounge" always read distinctly. Reuses the shared
 * [PragmaticHybridTheme] building blocks ([BigReadout], [LightControlsRow],
 * [MediaControlsRow], [VerticalTapeMeter]) so the theme is fully featured rather than
 * just a pretty wrapper; the distinct identity here is the gradient backdrop and the
 * always-white ink/accent.
 */
object ColorfulCardsTheme : R1Theme {
    override val id = ThemeId.COLORFUL_CARDS
    override val displayName = "Colourful Cards"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    override val baseline = sharedDarkBaseline

    private val palette = listOf(
        listOf(Color(0xFFFFB347), Color(0xFFFF6B1A), Color(0xFFC7338A)), // warm
        listOf(Color(0xFF41BDF5), Color(0xFF1B7BB8), Color(0xFF0D3B66)), // cool
        listOf(Color(0xFF52C77F), Color(0xFF2C8B5A), Color(0xFF154A35)), // green
        listOf(Color(0xFF9B6BD8), Color(0xFF5B3B9E), Color(0xFF2E2057)), // violet
    )
    private fun paletteFor(id: String): List<Color> =
        palette[(id.hashCode().rem(palette.size) + palette.size) % palette.size]

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
        val pal = paletteFor(model.entityIdText)
        val ui = LocalUiOptions.current
        // Accent is white for body text + slider, with a per-card override (from
        // EntityOverride.accentColor) winning when set. The gradient backdrop already
        // carries the colour identity so we don't need a coloured accent on top.
        // Per-card override wins, then the global accent picker, then white
        // (the readable default on every gradient backdrop).
        val accent = model.accentOverride
            ?: LocalThemeAccentOverride.current
            ?: Color.White

        Row(
            modifier = modifier
                .fillMaxSize()
                .background(Brush.linearGradient(pal))
                .padding(start = 22.dp, top = 18.dp, bottom = 18.dp, end = 18.dp),
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(width = 14.dp, height = 4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.9f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domainLabel(model.domainGlyph),
                        style = R1.labelMicro,
                        color = Color.White,
                    )
                    if (ui.showAreaLabel && !model.area.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = R1.labelMicro, color = Color.White.copy(alpha = 0.7f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = model.area.replace('_', ' ').uppercase(),
                            style = R1.labelMicro,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    style = R1.titleCard,
                    color = Color.White,
                    maxLines = 2,
                )
                // 'Last changed' relative-time label — parity with
                // PragmaticHybridTheme. Localised composable so the ticker
                // doesn't recompose the whole card on every interval.
                if (model.lastChangedAt != null) {
                    Spacer(Modifier.height(2.dp))
                    com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                        at = model.lastChangedAt,
                        color = Color.White.copy(alpha = 0.65f),
                        style = R1.labelMicro,
                    )
                }
                Spacer(Modifier.height(20.dp))
                // Hide the giant percent readout on media_player cards with
                // active now-playing — same parity rule as PragmaticHybridTheme
                // and MinimalDarkTheme. The cover + title strip already
                // dominate the card; '100 %' on top of them just compressed
                // the now-playing into a sliver.
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
                    // Always render the now-playing block — the previous
                    // title-or-picture conditional left the block missing
                    // for the same entity on bigger screens where it
                    // rendered on R1. MediaNowPlayingCompact's internal
                    // null/blank skip keeps the empty-state clean.
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
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeRound)
                            .background(Color.Black.copy(alpha = 0.22f))
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = if (model.isOn) "● ON" else "○ OFF",
                            style = R1.labelMicro,
                            color = Color.White,
                        )
                    }
                }
            }
            Spacer(Modifier.width(20.dp))
            // Shared meter — touch-drag + clickable ticks. The accent passed here is
            // white, which reads cleanly against the gradient backdrop. Climate /
            // number cards still show their domain-native range via meterLabels; light
            // HUE mode still renders the rainbow track.
            VerticalTapeMeter(
                entityId = com.github.itskenny0.r1ha.core.ha.EntityId(model.entityIdText),
                percent = model.percent,
                accent = accent,
                tickLabels = model.meterLabels,
                rainbow = model.lightWheelMode == com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE,
                // Default tick colour (R1.InkMuted) is invisible against the
                // colourful gradient; force a soft-white that reads on every
                // palette in the theme.
                tickLabelColor = Color.White.copy(alpha = 0.78f),
            )
        }
    }
}
