package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.rememberR1Haptic
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.launch

/**
 * Renderer for HA's `button` card: a tactile FIRE-CONTROL face built in R1HA's
 * industrial-kiosk idiom (near-black surface, a single warm accent, all-caps
 * micro labels, hairline rules). It reads as a physical actuator you arm and
 * launch, not a passive readout.
 *
 * Anatomy, top to bottom:
 *  - the ACTUATOR: a glyph held in a layered accent ring (the hero). At rest the
 *    ring is a hairline; pressed and freshly-fired it lights solid and the glyph
 *    flips dark, like a key pressing home. A soft radial inner glow sits behind
 *    the whole face so the actuator looks lit from within rather than stamped on.
 *  - the NAME: small and quiet. The deck slot already paints a `deckCardHeader
 *    Title` identity line above every face, so duplicating it big would read as
 *    three copies of the same word; the face name is a de-emphasised echo (or an
 *    inline `[IR]`-style badge), the same small-label / big-face split entity
 *    cards use. Honours `show_name`.
 *  - the VERB FOOTER: hairline rules flanking the affordance verb ("TAP TO SEND"
 *    for an IR blast, "TAP TO TOGGLE" for a light; see [buttonTapHint]).
 *
 * THE FIRED CONFIRMATION (the point of the card). These are one-shot launches
 * (`remote.send_command` / `automation.trigger` / scene / script / `*.press`),
 * so firing should FEEL like launching a signal. On the ACTUAL action dispatch
 * (not press-down) the card plays, all keyed off a per-fire trigger counter so
 * repeated taps each get a fresh burst:
 *  1. a RADIATING SIGNAL PULSE: concentric rings fling outward from the actuator
 *     and dissipate (Canvas, accent, expanding radius + decaying alpha, ~520ms;
 *     geometry in [pulseRing]). IR-evocative and the unmistakable "it fired" cue.
 *  2. an accent FLARE: the actuator disc washes solid accent, the glyph flips
 *     dark, then springs back; the verb footer crossfades the affordance verb
 *     out and the past-tense [buttonSentLabel] ("SENT" / "FIRED") in for the
 *     window, then back.
 *  3. a crisp HAPTIC confirm tick (the device buzzes), a real physical "sent".
 * A toggle-type action ([buttonFiresSignal] = false) skips the radiating pulse
 * (its on/off state the disc already shows is the feedback) but keeps the flare,
 * label crossfade and haptic so every tap still confirms.
 *
 * SHARED renderer: the dashboards grid renders this too, so a plain entity-bound
 * button (light / script / scene) must still look on-brand; it does, taking the
 * state accent and the gentle (toggle) or full (run) confirmation as its action
 * dictates. Hold / double-tap stay wired through [r1CardActions].
 */
@Composable
fun ButtonCard(
    card: LovelaceCard.Button,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = card.entityId?.let { stateMap.byRaw(it) }
    // HA renders a hui-warning when the button is bound to an entity the backend
    // doesn't serve. A bare action button (no entity) is fine and skips this.
    if (card.entityId != null && state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }
    // HA precedence: an explicit `color` wins; else `state_color` gates the
    // state-derived tint; else neutral. R1HA divergence: a bare action button
    // is always warm-accented (see buttonAccent), never disabled-grey.
    val accent = buttonAccent(card.color, card.stateColor, card.entityId, state)
    val rawLabel = card.name?.takeUnless { it.isBlank() }
        ?: card.entityId?.let { resolveStructuredName(null, card.nameItems, null, state, it) }
        ?: "Action"
    // De-emphasise a leading bracket tag ("[IR] Living Room") into an inline
    // badge + a clean label. Safe on odd names (returns null badge + the
    // original string), so we never crash or drop text.
    val (nameBadge, faceLabel) = buttonNameBadge(rawLabel)
    // HA's `icon_height` sizes the glyph; default one notch above the tile
    // discs (56 vs 48) because the disc is this card's hero, not a row marker.
    val discSize = iconHeightDp(card.iconHeight)?.dp ?: 56.dp
    // Resolve tap (with HA's domain-default fallback) plus hold / double-tap,
    // all bound to the card entity, in one shot via the shared action layer.
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )
    // Share the press stream with r1CardActions so the resting press dip and the
    // face chrome animate in lockstep with the modifier's own scale/alpha dip.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // ── The FIRED confirmation engine ───────────────────────────────────────
    // A per-fire trigger counter bumped on each ACTUAL dispatch (not press-down)
    // so repeated taps each relaunch a fresh burst. The signal pulse (radiating
    // rings) only runs for fire-and-forget actions; the flare + label crossfade
    // + haptic run for every actionable tap.
    val firesSignal = buttonFiresSignal(actions.tap)
    val sentLabel = buttonSentLabel(actions.tap)
    var fireTrigger by remember { mutableIntStateOf(0) }
    // 0 at rest, snaps to 1 on a fire then springs back — drives the flare wash
    // and the verb->SENT crossfade. Separate from the pulse clock so the flare
    // can settle on its own spring while the rings ease out linearly.
    val flare = remember { Animatable(0f) }
    // 0..1 pulse clock per fire, re-run from 0 each trigger. Linear so the ring
    // geometry in pulseRing owns the easing.
    val pulse = remember { Animatable(0f) }
    val view = LocalView.current
    val haptic = rememberR1Haptic()

    LaunchedEffect(fireTrigger) {
        if (fireTrigger == 0) return@LaunchedEffect
        // Crisp physical confirm the instant the command leaves.
        haptic.tick(view)
        // Run the radiating pulse and the flare concurrently: the pulse tween
        // expands the rings while the flare snaps the disc solid and springs it
        // home, so the disc punches and the signal flies out together.
        if (firesSignal) {
            launch {
                pulse.snapTo(0f)
                pulse.animateTo(1f, animationSpec = tween(durationMillis = 520))
                pulse.snapTo(0f)
            }
        }
        // Flare: snap up (disc fills solid, glyph flips dark) then spring back
        // with a touch of bounce so the key reads as released, not just faded.
        flare.snapTo(1f)
        flare.animateTo(
            0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    // Wrap the dispatch so EVERY fired action bumps the trigger. We bump for any
    // resolved tap (the user did tap something); hold / double-tap also count as
    // a fire so the face confirms whatever gesture dispatched.
    val firingOnAction: (LovelaceAction) -> Unit = remember(onAction) {
        { action ->
            fireTrigger++
            onAction(action)
        }
    }

    // Resting frame is a hairline that warms a touch while pressed; the heavy
    // full-accent box is gone. The flare briefly brightens it to confirm a fire.
    val pressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    val pressLift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = pressSpring,
        label = "button-card-press-lift",
    )
    // Combined "face is hot" amount: max of the held-press lift and the fire
    // flare, so a held press and a fresh fire both light the face.
    val hot = maxOf(pressLift, flare.value)
    // Frame stays a hairline at rest and warms toward accent as the face heats.
    // Cheap manual lerp keeps it out of animateColorAsState (one less node).
    val frameColor = lerpColor(R1.Hairline, accent, 0.15f + 0.85f * hot)

    // The disc's resting fill comes from the theme so it doesn't punch a black hole in a
    // backdrop-identity theme (Colourful Cards' gradient); near-black on the dark themes.
    val panelColor = com.github.itskenny0.r1ha.core.theme.LocalCardPanelColor.current
    // When the deck slot already painted a colourful backdrop (Colourful Cards),
    // the face goes TRANSPARENT so the per-card gradient + scrim show through and
    // the IR / button card reads as the same colourful tile as the entity action
    // cards. On the dark themes (default false) it keeps its near-black plate.
    val faceColor = if (com.github.itskenny0.r1ha.core.theme.LocalCardBackdropPainted.current) {
        androidx.compose.ui.graphics.Color.Transparent
    } else {
        R1.Surface
    }
    // Compact layout: the actuator sits BESIDE the name + footer instead of stacked above
    // them, roughly halving the card height — the win the pinned IR command wall wanted.
    val iconSlug = buttonIconSlug(actions.tap, card.icon)
    val icon = when {
        !card.showIcon -> null
        card.entityId != null -> cardEntityIcon(card.entityId, state, iconSlug)
        else -> R1Icons.forMdi(iconSlug)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(faceColor)
            // Soft radial inner glow anchored on the actuator (now left-of-centre), fading
            // out across the plate; intensifies with the fire flare so the whole plate
            // washes warm on a send, the same lit-from-within depth as before.
            .drawBehind {
                val glow = 0.05f + 0.16f * hot
                if (glow > 0.001f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = glow),
                                accent.copy(alpha = 0f),
                            ),
                            center = Offset(size.width * 0.22f, size.height / 2f),
                            radius = size.maxDimension * 0.62f,
                        ),
                    )
                }
            }
            .border(1.dp, frameColor, R1.ShapeM)
            .r1CardActions(
                actions = actions,
                onAction = firingOnAction,
                contentDescription = faceLabel,
                interactionSource = interaction,
            )
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // We honour `show_icon` and draw a glyph when we can derive one. The slug is
        // buttonIconSlug-overridden so a `remote.send_command` button always shows the
        // remote glyph even if its stored `icon:` is a stale cog (the user's pre-existing
        // pinned IR cards), while every other button keeps its configured icon.
        if (icon != null) {
            FireActuator(
                icon = icon,
                accent = accent,
                discSize = discSize,
                hot = hot,
                pulse = if (firesSignal) pulse.value else 0f,
                panelColor = panelColor,
            )
            Spacer(Modifier.size(R1.space.m))
        }
        Column(modifier = Modifier.weight(1f)) {
            if (card.showName) {
                // Small, quiet name (the deck header is the identity line). If the original
                // name carried an "[IR]"-style tag we render it as an inline badge ahead of
                // the cleaned label. Left-aligned now that it sits beside the disc.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (nameBadge != null) {
                        Text(
                            text = nameBadge,
                            style = R1.labelMicro,
                            color = accent,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(accent.copy(alpha = 0.14f))
                                .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
                        )
                        Spacer(Modifier.size(R1.space.s))
                    }
                    Text(
                        text = faceLabel,
                        style = R1.bodyEmph,
                        color = R1.Ink,
                        textAlign = TextAlign.Start,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (card.showState && state != null) {
                Spacer(Modifier.height(R1.space.xxs))
                Text(
                    text = compactStateText(state),
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Affordance footer: the verb, then a single trailing hairline rule. While a
            // fire is hot the affordance verb crossfades out and the past-tense confirmation
            // ("SENT" / "FIRED") crossfades in over the same slot, then back.
            val hint = buttonTapHint(actions.tap)
            if (hint != null) {
                Spacer(Modifier.height(R1.space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Two texts stacked in a Box, crossfaded by `hot` via per-text layer
                    // alpha: the affordance verb fades out as the confirmation fades in over
                    // the same slot. Box (not a swap) keeps the slot width stable so the rule
                    // doesn't twitch as the words swap.
                    Box(contentAlignment = Alignment.CenterStart) {
                        Text(
                            text = hint,
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                            maxLines = 1,
                            modifier = Modifier.graphicsLayer { this.alpha = 1f - hot },
                        )
                        if (sentLabel != null) {
                            Text(
                                text = sentLabel,
                                style = R1.labelMicro,
                                color = accent,
                                maxLines = 1,
                                modifier = Modifier.graphicsLayer { this.alpha = hot },
                            )
                        }
                    }
                    Spacer(Modifier.width(R1.space.s))
                    Box(
                        Modifier
                            .weight(1f)
                            .height(1.dp)
                            .background(R1.Hairline),
                    )
                }
            }
        }
    }
}

/**
 * The hero ACTUATOR: the glyph held in a layered accent ring, with the radiating
 * signal pulse drawn behind it. [hot] (0..1) is the combined press/flare heat:
 * the inner disc fills from a faint wash to solid accent and the glyph flips from
 * accent to dark as it climbs, so a held press or a fresh fire lights the key.
 * [pulse] (0..1) is the per-fire clock that flings the concentric rings outward
 * (geometry in [pulseRing]); 0 for toggle actions, which skip the launch.
 *
 * Drawn rather than reusing [CardIconDisc] because the actuator carries the pulse
 * canvas and a two-layer ring (an outer hairline halo + the inner solid disc)
 * the row discs don't need.
 */
@Composable
private fun FireActuator(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    discSize: androidx.compose.ui.unit.Dp,
    hot: Float,
    pulse: Float,
    /** Resting disc fill from the active theme's [com.github.itskenny0.r1ha.core.theme
     *  .LocalCardPanelColor]; near-black on the dark themes, a translucent tint on
     *  Colourful Cards so the disc reads as a well in the gradient, not a black hole. */
    panelColor: androidx.compose.ui.graphics.Color,
) {
    val ringCount = 3
    // The pulse needs room to expand past the disc; the Box is sized larger than the disc
    // and the rings draw out to its bounds. Kept compact (1.55x) for the inline layout.
    val fieldSize = discSize * 1.55f
    Box(
        modifier = Modifier
            .size(fieldSize)
            .drawBehind {
                if (pulse > 0f && pulse < 1f) {
                    val maxR = size.minDimension / 2f
                    // Rings start at the disc rim, not the centre, so they read as leaving
                    // the actuator face.
                    val minR = (discSize.toPx() / 2f).coerceAtMost(maxR)
                    val center = Offset(size.width / 2f, size.height / 2f)
                    for (i in 0 until ringCount) {
                        val ring = pulseRing(pulse, i, ringCount)
                        if (ring.alpha <= 0f) continue
                        val r = minR + (maxR - minR) * ring.radiusFraction
                        drawCircle(
                            color = accent.copy(alpha = ring.alpha * 0.75f),
                            radius = r,
                            center = center,
                            style = Stroke(width = 2f),
                        )
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // Resting fill is the theme panel tint; crossfade to solid accent as the face heats.
        val discFill = lerpColor(panelColor, accent, (0.18f + 0.82f * hot).coerceIn(0f, 1f))
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(CircleShape)
                .background(discFill)
                // Outer hairline halo ring (always present, brightens with heat).
                .border(1.dp, accent.copy(alpha = 0.4f + 0.6f * hot), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                // Glyph flips accent -> dark as the disc fills, so it stays legible against
                // the solid fill at the peak of a fire.
                tint = lerpColor(accent, R1.Bg, hot),
                modifier = Modifier.size(discSize * 0.5f),
            )
        }
    }
}

/**
 * Cheap manual colour lerp for the few face transitions that don't warrant an
 * `animateColorAsState` node (the frame tint and the glyph flip read off the
 * shared `hot`/`pulse` clocks already). [t] is clamped 0..1.
 */
private fun lerpColor(
    from: androidx.compose.ui.graphics.Color,
    to: androidx.compose.ui.graphics.Color,
    t: Float,
): androidx.compose.ui.graphics.Color {
    val c = t.coerceIn(0f, 1f)
    return androidx.compose.ui.graphics.Color(
        red = from.red + (to.red - from.red) * c,
        green = from.green + (to.green - from.green) * c,
        blue = from.blue + (to.blue - from.blue) * c,
        alpha = from.alpha + (to.alpha - from.alpha) * c,
    )
}
