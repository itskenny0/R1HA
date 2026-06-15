package com.github.itskenny0.r1ha.core.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.ColorfulBackgroundDesign
import com.github.itskenny0.r1ha.core.prefs.ColorfulPaletteSet
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.ValueBarLocation
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.prefs.ThemeId
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Colourful Cards" — a per-entity gradient sky behind the same Mission-Control
 * layout. Each card gets a stable palette hashed from its entity_id so two lights
 * named "kitchen" and "lounge" always read distinctly. Reuses the shared
 * [PragmaticHybridTheme] building blocks ([BigReadout], [LightControlsRow],
 * [MediaControlsRow], [VerticalTapeMeter]) so the theme is fully featured rather than
 * just a pretty wrapper; the distinct identity here is the gradient backdrop and the
 * always-white ink/accent.
 *
 * Legibility on the gradients comes from quiet layers rather than dimming the palettes
 * themselves:
 *  - a top scrim whose head alpha is tuned PER PALETTE ([topScrimAlpha]) — the header,
 *    name, last-changed line, and big readout all live in the top band, which is exactly
 *    where the TL→BR gradient is lightest, so an amber card (near-white bright stop) earns
 *    a firmer scrim while a navy card (already dark) is barely touched;
 *  - a shallow bottom anchor so the brightness chips, the on/off pill, and any per-domain
 *    panel beneath them sit on a seated base instead of floating on a vivid mid-stop;
 *  - a narrow edge scrim under a vertical value bar so the white fill, thumb, and tick
 *    numerals read on a rail rather than on the gradient's brightest run;
 *  - a soft 1px ink shadow on the name and header glyphs so the white type keeps an edge
 *    even where a bright stop bleeds through the scrim.
 *
 * The bottom anchor and edge scrims are constant; the top scrim is built once per palette
 * (cheap — a vertical gradient of two stops) and reused across the card's recompositions.
 */
object ColorfulCardsTheme : R1Theme {
    override val id = ThemeId.COLORFUL_CARDS
    override val displayName = "Colourful Cards"
    override val systemBars = SystemBarColors(status = Color.Black, nav = Color.Black)
    // Getter so the Material primary follows the live accent token (see sharedDarkBaseline).
    override val baseline get() = sharedDarkBaseline

    // Inner panels (sensor chart box, the action/IR fire disc) read this instead of a
    // hardcoded near-black fill. A TRANSLUCENT dark tint so the panel dims the gradient
    // behind it just enough for legibility while the sky still shows through — it sits IN
    // the gradient rather than punching a black hole. The dark themes keep the opaque
    // R1.Surface default; only this theme's card identity is the backdrop.
    override val cardPanelColor = Color.Black.copy(alpha = 0.34f)

    // The six palettes are now data in [ColorfulPalettes] (one set per [ColorfulPaletteSet])
    // so the per-entity mapping unit-tests on a plain JVM. Resolve the chosen set's gradient
    // for an entity here, converting the packed ARGB stops to Compose Colours. NOTE: every
    // set has six SLOTS in the same hue order, so the stable hash keeps an entity on its slot
    // when the user switches sets — only the hue family of that slot changes.
    private fun paletteFor(id: String, set: ColorfulPaletteSet): List<Color> =
        ColorfulPalettes.paletteArgbFor(id, set).map { Color(it) }

    /**
     * Build the backdrop Brush for a card from its three palette stops and the chosen
     * [ColorfulBackgroundDesign]. The palette (hue identity) is the same across designs;
     * only the brush geometry changes:
     *  - GRADIENT: the original TL→BR linear sweep through all three stops.
     *  - MESH: a radial bloom — the bright stop pooled in the upper-left corner, easing
     *    through the base to the deep anchor at the far edge, layered so it reads as a sky
     *    lit from a corner rather than a straight diagonal band.
     *  - DUOTONE: a near-hard vertical split — the bright stop band up top (where the header
     *    lives), a short blend through the base, then the deep anchor filling the lower half.
     */
    private fun backdropBrush(stops: List<Color>, design: ColorfulBackgroundDesign): Brush =
        when (design) {
            ColorfulBackgroundDesign.GRADIENT -> Brush.linearGradient(stops)
            ColorfulBackgroundDesign.MESH -> Brush.radialGradient(
                colors = listOf(stops[0], stops[1], stops[2]),
                // Pool the bright stop in the upper-left; a generous radius so the deep
                // anchor only reaches the far (bottom-right) corner where the value bar's
                // lower ticks live, matching the GRADIENT design's anchor placement.
                center = Offset(0f, 0f),
                radius = Float.POSITIVE_INFINITY,
            )
            ColorfulBackgroundDesign.DUOTONE -> Brush.verticalGradient(
                0.00f to stops[0],
                0.40f to stops[0],
                0.58f to stops[1],
                0.74f to stops[2],
                1.00f to stops[2],
            )
        }

    // Top scrim — black easing out by ~62% height. The head alpha is data, not a
    // constant: [topScrimAlpha] reads the palette's brightest stop so a near-white amber
    // head gets a firm seat while navy is barely shaded. The intermediate stop is half
    // the head so the fade never bands; below it the gradient stays fully vivid (the
    // bottom anchor and the chips' own backings carry the lower band). Built once per
    // palette and remembered by the caller, so this allocation never lands in the
    // per-detent recomposition path.
    private fun topScrimFor(palette: List<Color>): Brush {
        val head = topScrimAlpha(palette.first().toArgb())
        return Brush.verticalGradient(
            0.00f to Color.Black.copy(alpha = head),
            0.32f to Color.Black.copy(alpha = head * 0.5f),
            0.62f to Color.Transparent,
            1.00f to Color.Transparent,
        )
    }

    // Aux-card scrim — a single fixed head for the synthetic preview / aux surfaces that
    // don't carry a live percent. Mirrors the mid-band of [topScrimFor] so a sensor tile
    // next to a light card reads as the same theme without per-aux palette plumbing.
    private val auxTopScrim = Brush.verticalGradient(
        0.00f to Color.Black.copy(alpha = 0.44f),
        0.32f to Color.Black.copy(alpha = 0.22f),
        0.62f to Color.Transparent,
        1.00f to Color.Transparent,
    )

    // Bottom anchor — a shallow black foot rising ~24% up from the card's base. The
    // brightness chips, the on/off pill, and any per-domain panel render in this band;
    // without it they floated on whatever vivid mid-stop the gradient happened to be at,
    // and the translucent-black chip backings alone weren't enough to seat white labels.
    private val bottomAnchor = Brush.verticalGradient(
        0.00f to Color.Transparent,
        0.76f to Color.Transparent,
        1.00f to Color.Black.copy(alpha = 0.28f),
    )

    // Soft ink shadow for the white headline + header glyphs. One short, low-blur drop in
    // near-black gives the type a defined edge where a bright stop bleeds through the
    // scrim, without the muddy halo a wide blur would smear across the gradient.
    private val inkShadow = Shadow(
        color = Color.Black.copy(alpha = 0.55f),
        offset = Offset(0f, 1f),
        blurRadius = 3f,
    )

    // Edge scrims for the value bar. Only the vertical LEFT/RIGHT placements get one
    // (the horizontal TOP placement already sits inside the top scrim, and BOTTOM
    // lands on the gradient's darkest run). Narrow and gentle — a seat, not a frame.
    private val rightEdgeScrim = Brush.horizontalGradient(
        0.00f to Color.Transparent,
        0.72f to Color.Transparent,
        1.00f to Color.Black.copy(alpha = 0.30f),
    )
    private val leftEdgeScrim = Brush.horizontalGradient(
        0.00f to Color.Black.copy(alpha = 0.30f),
        0.28f to Color.Transparent,
        1.00f to Color.Transparent,
    )

    // Aux-card ink — the same always-white treatment the main cards earned in the
    // contrast pass: full white headlines, 0.85 for secondary text, 0.72 for muted
    // callouts. The plain grey R1 inks (InkSoft 0xA8, InkMuted 0x6E) sink into the
    // vivid mid-stops; translucent white reads on every palette.
    private val auxInk = CardInkPalette(
        ink = Color.White,
        soft = Color.White.copy(alpha = 0.85f),
        muted = Color.White.copy(alpha = 0.72f),
    )

    /**
     * Same per-entity gradient sky + top scrim as [Card], so a sensor / select / action /
     * switch card sitting next to a light card reads as the same theme rather than a
     * left-over black tile. Builds the Brush per call (cheap — three colour stops); the
     * EntityCard wrapper remembers the returned style per entity so this never runs in
     * the per-detent recomposition path.
     */
    override fun auxCardStyle(
        entityIdText: String,
        accentOverride: Color?,
        config: ColorfulCardsConfig,
    ): AuxCardStyle {
        // A per-card colour override recolours the sky from the chosen base; otherwise the
        // entity-id hash picks a palette from the user's chosen SET. Either way the backdrop
        // is painted in the user's chosen DESIGN so aux cards match the main cards' look.
        val stops = accentOverride?.let { overridePalette(it) }
            ?: paletteFor(entityIdText, config.paletteSet)
        return AuxCardStyle(
            backdrop = backdropBrush(stops, config.backgroundDesign),
            scrim = auxTopScrim,
            ink = auxInk,
        )
    }

    /** Gradient stops derived from a per-card colour override; see [overrideGradientArgb]. */
    private fun overridePalette(base: Color): List<Color> =
        overrideGradientArgb(base.toArgb()).map { Color(it) }

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
        CardRenderModel.Glyph.PERSON -> "PERSON"
        CardRenderModel.Glyph.WEATHER -> "WEATHER"
    }

    @Composable
    override fun Card(model: CardRenderModel, modifier: Modifier, onTapToggle: () -> Unit) {
        // A per-card colour override recolours the SKY in this theme: the gradient is
        // the card's colour identity, so picking a colour in the customize sheet and
        // only tinting the slider read as "the override does nothing". Without an
        // override the entity-id hash picks from the stock palettes as before.
        val config = LocalColorfulCardsConfig.current
        val overrideAccent = model.accentOverride
        val pal = overrideAccent?.let { ov ->
            androidx.compose.runtime.remember(ov) { overridePalette(ov) }
        } ?: androidx.compose.runtime.remember(model.entityIdText, config.paletteSet) {
            paletteFor(model.entityIdText, config.paletteSet)
        }
        // The gradient backdrop depends only on the (stable) palette for this entity and the
        // chosen background design, so build the Brush once and reuse it. The card recomposes
        // on every wheel detent (percent change); rebuilding the Brush each time was an
        // allocation in the per-detent rendering path for no benefit.
        val bgBrush = androidx.compose.runtime.remember(pal, config.backgroundDesign) {
            backdropBrush(pal, config.backgroundDesign)
        }
        // Top scrim is palette-dependent (its head alpha is read from the bright stop),
        // so remember it on the same key as the gradient — both are stable per entity and
        // must stay out of the per-detent recomposition path.
        val topScrim = androidx.compose.runtime.remember(pal) { topScrimFor(pal) }
        val ui = LocalUiOptions.current
        // Accent is white for body text + slider. When a per-card override exists it
        // now paints the backdrop, so the accent FALLS BACK to white: a fill the same
        // colour as the sky's mid-stop would vanish into it. Otherwise the global
        // accent picker, then the light's live colour, then white.
        val accent = if (overrideAccent != null) Color.White
        else LocalThemeAccentOverride.current
            ?: model.liveLightColor
            ?: Color.White
        // Short landscape viewport: trim vertical chrome so the card's bottom controls don't
        // clip. Portrait and the always-portrait R1 are byte-for-byte unchanged. Mirrors the
        // compact mode in PragmaticHybridTheme.
        val window = com.github.itskenny0.r1ha.ui.components.LocalWindowTier.current
        val compact = window.isLandscape && window.heightDp in 1..479

        // Seat the value bar's edge with a scrim matching where the user put it.
        // Vertical placements only — see the scrim declarations for rationale.
        val edgeScrim = when (model.valueBarLocation) {
            ValueBarLocation.RIGHT -> rightEdgeScrim
            ValueBarLocation.LEFT -> leftEdgeScrim
            else -> null
        }
        // Wrap mode (the DYNAMIC deck, LocalCardFillSlot = false): the card
        // sizes to its content, so every fill-height element switches to a
        // natural height. Fill mode is the unchanged historical layout.
        val fillSlot = LocalCardFillSlot.current
        CardValueBarScaffold(
            model = model,
            accent = accent,
            outer = modifier
                .then(if (fillSlot) Modifier.fillMaxSize() else Modifier)
                .background(bgBrush)
                .background(topScrim)
                .background(bottomAnchor)
                .let { m -> if (edgeScrim != null) m.background(edgeScrim) else m }
                .padding(
                    start = 22.dp,
                    top = if (compact) 8.dp else 18.dp,
                    bottom = if (compact) 8.dp else 18.dp,
                    end = 18.dp,
                ),
            // Default tick colour (R1.InkMuted) is invisible against the
            // colourful gradient; force near-white. 0.92 rather than full white
            // keeps the numerals a half-step behind the accent fill/thumb while
            // still reading over the vivid mid-stops (the edge scrim does the
            // rest of the work).
            tickLabelColor = Color.White.copy(alpha = 0.92f),
            // The default dark-grey hairline vanishes against the gradients'
            // deep anchors; a translucent white reads on every palette without
            // competing with the solid white fill.
            trackColor = Color.White.copy(alpha = 0.25f),
        ) {
            Column(
                modifier = if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (model.showIcon) {
                        // The gradient background needs a high-contrast tint, so
                        // use the per-card override accent when present and fall
                        // back to soft-white (matching the chip) otherwise.
                        androidx.compose.material3.Icon(
                            imageVector = com.github.itskenny0.r1ha.ui.icons.R1Icons.forEntity(
                                model.entityIdText,
                                deviceClass = model.entityState?.deviceClass,
                                state = model.entityState?.rawState,
                            ),
                            contentDescription = null,
                            tint = model.accentOverride ?: Color.White.copy(alpha = 0.9f),
                            modifier = Modifier.size(16.dp),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(width = 14.dp, height = 4.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Color.White.copy(alpha = 0.9f)),
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = domainLabel(model.domainGlyph),
                        style = R1.labelMicro.copy(shadow = inkShadow),
                        color = Color.White,
                    )
                    if (ui.showAreaLabel && !model.area.isNullOrBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text("·", style = R1.labelMicro, color = Color.White.copy(alpha = 0.75f))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = areaLabel(model.area),
                            style = R1.labelMicro,
                            color = Color.White.copy(alpha = 0.85f),
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = model.friendlyName,
                    // Soft drop so the name keeps a defined edge over a bright stop that
                    // bleeds through the scrim; the readout below leans on the top scrim's
                    // firmer head instead (it's a shared component, no per-theme shadow).
                    style = R1.titleCard.copy(shadow = inkShadow),
                    color = Color.White,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    // Tap the title to select this card in the dynamic deck (no-op
                    // elsewhere); full width so the whole title row is the target.
                    modifier = Modifier.fillMaxWidth().cardTitleTarget(),
                )
                // 'Last changed' relative-time label — parity with
                // PragmaticHybridTheme. Localised composable so the ticker
                // doesn't recompose the whole card on every interval. 0.75
                // alpha (not the InkMuted-equivalent 0.65) because even under
                // the top scrim this line sits on a colourful stop, not on
                // near-black.
                if (!compact && model.lastChangedAt != null) {
                    Spacer(Modifier.height(2.dp))
                    com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                        at = model.lastChangedAt,
                        color = Color.White.copy(alpha = 0.75f),
                        style = R1.labelMicro,
                    )
                }
                Spacer(Modifier.height(if (compact) 8.dp else 20.dp))
                // Hide the giant percent readout on every media_player card —
                // parity with PragmaticHybridTheme. The right-side meter already
                // conveys the volume % and the now-playing block below carries
                // the useful info. Unconditional (not gated on title/picture)
                // so the layout stays stable while a track's metadata is still
                // loading — see the matching comment in PragmaticHybridTheme.
                val hideBigReadoutForMedia = model.domainGlyph ==
                    CardRenderModel.Glyph.MEDIA_PLAYER
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
                // Brightness preset chips on light cards — parity with
                // PragmaticHybridTheme's 25/50/100 tap targets, restyled for the
                // gradient: inactive chips use the same translucent black backing
                // as the on/off pill (an opaque SurfaceMuted slab would punch a
                // grey hole in the sky), the current value fills with the accent.
                if (model.domainGlyph == CardRenderModel.Glyph.LIGHT &&
                    (model.lightWheelMode == com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS ||
                        model.lightWheelMode == null)
                ) {
                    Spacer(Modifier.height(8.dp))
                    val onSetPercent = LocalOnSetEntityPercent.current
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
                                    .background(
                                        if (isCurrent) accent
                                        else Color.Black.copy(alpha = 0.30f),
                                    )
                                    // Hairline so an inactive chip reads as a real control
                                    // on the gradient rather than a vague dark smudge; the
                                    // active accent chip needs no outline (it carries weight).
                                    .then(
                                        if (isCurrent) Modifier
                                        else Modifier.border(1.dp, Color.White.copy(alpha = 0.16f), R1.ShapeS),
                                    )
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
                                    color = if (isCurrent) R1.Bg else Color.White.copy(alpha = 0.92f),
                                )
                            }
                        }
                    }
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
                        source = model.mediaSource,
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
                        CardRenderModel.Glyph.FAN -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.FanPanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        CardRenderModel.Glyph.COVER -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.CoverPanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        CardRenderModel.Glyph.HUMIDIFIER -> {
                            Spacer(Modifier.height(10.dp))
                            com.github.itskenny0.r1ha.ui.components.HumidifierPanel(
                                state = model.entityState,
                                accent = accent,
                            )
                        }
                        else -> {
                            if (model.entityState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.REMOTE) {
                                Spacer(Modifier.height(10.dp))
                                com.github.itskenny0.r1ha.ui.components.RemotePanel(
                                    state = model.entityState,
                                    accent = accent,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    com.github.itskenny0.r1ha.ui.components.CustomActionsPanel(
                        state = model.entityState,
                        accent = accent,
                    )
                }
                // Fill mode floats the pill to the slot bottom; wrap mode uses
                // a fixed gap (a weighted spacer in a wrap-height column would
                // re-inflate the card to the cap).
                if (fillSlot) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(12.dp))
                if (ui.showOnOffPill) {
                    // Stateful pill — parity with PragmaticHybridTheme's OnOffPill,
                    // which fills with the accent when on. The previous version drew
                    // the same translucent pill for both states, so on/off wasn't
                    // glanceable on a wall of gradients. ON = solid accent (white by
                    // default) with dark text; OFF = the quiet scrim pill.
                    val (pillLabel, pillFg, pillBg) = if (model.isOn) {
                        Triple("● ON", R1.Bg, accent)
                    } else {
                        Triple("○ OFF", Color.White.copy(alpha = 0.82f), Color.Black.copy(alpha = 0.32f))
                    }
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeRound)
                            .background(pillBg)
                            // The OFF pill earns a hairline so it reads as a status capsule
                            // on the gradient; ON is solid accent and needs no outline.
                            .then(
                                if (model.isOn) Modifier
                                else Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), R1.ShapeRound),
                            )
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = pillLabel,
                            style = R1.labelMicro,
                            color = pillFg,
                        )
                    }
                }
            }
        }
    }
}
