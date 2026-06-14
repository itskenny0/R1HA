package com.github.itskenny0.r1ha.ui.components

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.theme.LocalCardFillSlot
import com.github.itskenny0.r1ha.core.theme.LocalCardInk
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.pulseRing
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.coroutines.launch

/**
 * Card variant for stateless action entities — scenes, scripts, buttons, helper
 * buttons. No on/off state, no scalar to drive; the whole card is "fire the
 * trigger". The header mirrors the other entity cards (DOMAIN · TRIGGER · AREA,
 * friendly name) so the deck reads cohesively, but the body is a tactile FIRE
 * ACTUATOR instead of a switch track or value bar.
 *
 * THE FIRED CONFIRMATION. HA's state_changed for these entities is just a
 * last-triggered timestamp bump and doesn't reliably echo back through the
 * reconciliation path, so the satisfying "it fired" feedback is synthesised
 * locally, the same tactile treatment the Lovelace
 * [com.github.itskenny0.r1ha.feature.dashboards.cards.ButtonCard] uses so the two
 * read as one design language. On the ACTUAL dispatch (a tap), all keyed off a
 * per-fire trigger counter so repeated taps each get a fresh burst:
 *  1. a RADIATING SIGNAL PULSE: concentric accent rings fling outward from the
 *     actuator and dissipate (Canvas, expanding radius + decaying alpha; geometry
 *     SHARED with the Lovelace card via [pulseRing] so the launch animation is
 *     identical on both faces). The unmistakable "it fired" cue.
 *  2. an accent FLARE: the actuator disc washes solid accent, the glyph flips
 *     dark, the frame warms, then springs back.
 *  3. a verb crossfade: the affordance verb ([actionTapHint], "TAP TO RUN")
 *     crossfades out and the past-tense [actionSentLabel] ("RAN") crossfades in
 *     over the same slot for the window, then back.
 *  4. a crisp HAPTIC tick (the device buzzes), a real physical "sent".
 *
 * The actual service-call still goes through the same haRepository path as
 * everything else and keeps the failure-toast / rollback treatment.
 */
@Composable
fun ActionCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Ink rides in from the theme via the EntityCard wrapper (white over a
    // gradient backdrop, the classic R1 greys everywhere else). The backdrop
    // itself is painted by the wrapper, so the card stays theme-agnostic.
    val ink = LocalCardInk.current
    // Wrap mode (the DYNAMIC deck, LocalCardFillSlot = false): the card sizes to
    // its content so every control stays visible; fill mode keeps the historical
    // full-slot layout. This card has no value bar — it is header + actuator +
    // footer — so wrap mode collapses to that natural height (a compact tile)
    // while fill mode floats the actuator to the slot centre.
    val fillSlot = LocalCardFillSlot.current

    // ── The FIRED confirmation engine ───────────────────────────────────────
    // A per-fire trigger counter bumped on each tap so repeated taps each
    // relaunch a fresh burst. The pulse clock runs the radiating rings; the
    // flare drives the disc wash + verb crossfade; both re-run from the trigger.
    val domain = state.id.domain
    val tapHint = actionTapHint(domain)
    val sentLabel = actionSentLabel(domain)
    var fireTrigger by remember { mutableIntStateOf(0) }
    // 0 at rest, snaps to 1 on a fire then springs back — drives the flare wash
    // and the verb -> SENT crossfade.
    val flare = remember { Animatable(0f) }
    // 0..1 pulse clock per fire, re-run from 0 each trigger. Linear so the ring
    // geometry in the shared pulseRing owns the easing.
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
        launch {
            pulse.snapTo(0f)
            pulse.animateTo(1f, animationSpec = tween(durationMillis = 520))
            pulse.snapTo(0f)
        }
        flare.snapTo(1f)
        flare.animateTo(
            0f,
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioLowBouncy,
                stiffness = Spring.StiffnessMedium,
            ),
        )
    }

    // Share the press stream so the resting press dip lights the actuator in
    // lockstep with the tap.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressLift by animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "action-card-press-lift",
    )
    // Combined "face is hot" amount: max of the held-press lift and the fire
    // flare, so a held press and a fresh fire both light the actuator.
    val hot = maxOf(pressLift, flare.value)

    Column(
        modifier = modifier
            .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            // Soft radial inner glow: brightest at the actuator, fading out. Gives
            // the near-black face depth (lit from within) and intensifies with the
            // fire flare so the whole plate washes warm on a fire.
            .drawBehind {
                val glow = 0.04f + 0.16f * hot
                if (glow > 0.001f) {
                    drawRect(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accent.copy(alpha = glow),
                                accent.copy(alpha = 0f),
                            ),
                            center = Offset(size.width / 2f, size.height * 0.62f),
                            radius = size.maxDimension * 0.7f,
                        ),
                    )
                }
            }
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        // ── Header ───────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(domainLabel, style = R1.labelMicro, color = ink.ink)
            Spacer(Modifier.width(8.dp))
            Text("· TRIGGER", style = R1.labelMicro, color = ink.muted)
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = ink.muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = areaLabel(state.area),
                    style = R1.labelMicro,
                    color = ink.soft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = ink.ink,
            maxLines = 2,
        )
        // When the script last ran ("ran 2h ago"). last_triggered, not
        // last_changed, so it reflects the actual fire rather than a config
        // reload. Hidden until it has fired.
        val ran = rememberRelativeTime(state.lastTriggered)
        if (ran.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text = "ran $ran", style = R1.labelMicro, color = ink.muted, maxLines = 1)
        }

        // Fill mode floats the actuator to the slot centre; wrap mode keeps a
        // fixed gap so the card stays content-sized.
        if (fillSlot) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(16.dp))

        // ── The FIRE ACTUATOR ──────────────────────────────────────────────
        // The hero: a domain glyph held in a layered accent disc with the
        // radiating signal pulse drawn behind it. The whole row is the tap
        // target; r1Pressable would add its own scale dip, but we drive the
        // press feedback through the shared interaction source so the actuator
        // and the disc wash animate together.
        val glyph = R1Icons.forMdi(domain.prefix) ?: R1Icons.forMdi("button")
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center,
        ) {
            FireActuator(
                glyph = glyph,
                accent = accent,
                hot = hot,
                pulse = pulse.value,
                running = state.isOn,
                interaction = interaction,
                onFire = {
                    fireTrigger++
                    onFire()
                },
            )
        }

        if (fillSlot) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(16.dp))

        // ── Affordance footer: hairline rules flanking the verb ────────────
        // While a fire is hot the affordance verb ("TAP TO RUN") crossfades out
        // and the past-tense confirmation ("RAN") crossfades in over the same
        // slot, then back. A running script shows RUNNING… instead so the user
        // knows a long-running action is still in flight.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(R1.Hairline),
            )
            Box(
                modifier = Modifier.padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isOn) {
                    // Long-running (a script still executing): a steady status,
                    // not the one-shot crossfade.
                    Text(
                        text = "RUNNING…",
                        style = R1.labelMicro,
                        color = accent,
                        maxLines = 1,
                    )
                } else {
                    // Two texts stacked and crossfaded by `hot` via per-text layer
                    // alpha so the footer width stays stable and the rules don't
                    // twitch as the words swap.
                    Text(
                        text = tapHint,
                        style = R1.labelMicro,
                        color = ink.muted,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { this.alpha = 1f - hot },
                    )
                    Text(
                        text = sentLabel,
                        style = R1.labelMicro,
                        color = accent,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer { this.alpha = hot },
                    )
                }
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(R1.Hairline),
            )
        }
    }
}

/**
 * The hero ACTUATOR: a domain glyph held in a layered accent disc, with the
 * radiating signal pulse drawn behind it. [hot] (0..1) is the combined
 * press/flare heat: the inner disc fills from a faint wash to solid accent and
 * the glyph flips from accent to dark as it climbs, so a held press or a fresh
 * fire lights the key. [pulse] (0..1) is the per-fire clock that flings the
 * concentric rings outward (geometry SHARED with the Lovelace button card via
 * [pulseRing]). [running] outlines the disc with a steady accent halo so a
 * still-executing script reads as armed even at rest.
 *
 * The disc is the tap target (its own [MutableInteractionSource] shared with the
 * caller drives the press dip), large enough to be an easy thumb hit on the R1.
 */
@Composable
private fun FireActuator(
    glyph: androidx.compose.ui.graphics.vector.ImageVector?,
    accent: Color,
    hot: Float,
    pulse: Float,
    running: Boolean,
    interaction: MutableInteractionSource,
    onFire: () -> Unit,
) {
    val discSize = 88.dp
    val ringCount = 3
    // The pulse needs room to expand past the disc; the field box is sized larger
    // than the disc and the rings draw out to its bounds.
    val fieldSize = discSize * 1.7f
    Box(
        modifier = Modifier
            .size(fieldSize)
            .drawBehind {
                if (pulse > 0f && pulse < 1f) {
                    val maxR = size.minDimension / 2f
                    // Rings start at the disc rim, not the centre, so they read as
                    // leaving the actuator face.
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
        // A running script keeps a brighter halo so it reads as armed; otherwise
        // the halo brightens with the press/fire heat.
        val haloAlpha = maxOf(if (running) 0.7f else 0.4f, 0.4f + 0.6f * hot)
        Box(
            modifier = Modifier
                .size(discSize)
                .clip(CircleShape)
                // Inner disc fills from an 18% wash to solid as the face heats.
                .background(accent.copy(alpha = 0.18f + 0.82f * hot))
                .border(1.dp, accent.copy(alpha = haloAlpha), CircleShape)
                .r1Pressable(
                    onClick = onFire,
                    contentDescription = "ACTIVATE",
                    interactionSource = interaction,
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (glyph != null) {
                Icon(
                    imageVector = glyph,
                    contentDescription = null,
                    // Glyph flips accent -> dark as the disc fills, so it stays
                    // legible against the solid fill at the peak of a fire.
                    tint = lerpActionColor(accent, R1.Bg, hot),
                    modifier = Modifier.size(discSize * 0.42f),
                )
            }
        }
    }
}

/**
 * Cheap manual colour lerp for the glyph flip (it reads off the shared `hot`
 * clock already, so an animateColorAsState node would be wasted). [t] clamped
 * 0..1.
 */
private fun lerpActionColor(from: Color, to: Color, t: Float): Color {
    val c = t.coerceIn(0f, 1f)
    return Color(
        red = from.red + (to.red - from.red) * c,
        green = from.green + (to.green - from.green) * c,
        blue = from.blue + (to.blue - from.blue) * c,
        alpha = from.alpha + (to.alpha - from.alpha) * c,
    )
}
