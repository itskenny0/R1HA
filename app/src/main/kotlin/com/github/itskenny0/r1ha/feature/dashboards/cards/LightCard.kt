package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Light
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `light` card. An oversized brightness orb with the percent
 * reading sliced through it, the friendly name underneath, and a brightness
 * stepper following the app's value-bar idiom (HA renders a continuous circular
 * slider; the R1 equivalent is a prominent − / + brightness control).
 *
 * Parity with hui-light-card.ts:
 *  - the whole card tap defaults to `toggle` and hold to `more-info` (HA's
 *    getStubConfig), resolved through [resolveCardActions]; an explicit
 *    tap/hold/double-tap config overrides.
 *  - a dedicated more-info affordance (the top-right info dot) always opens the
 *    detail sheet, matching how the thermostat/humidifier cards expose it.
 *  - the brightness control is gated on the light advertising a brightness-
 *    capable colour mode (see [lightSupportsBrightness]); an on/off-only bulb
 *    shows just the toggle. Nudges call `light.turn_on` with `brightness_pct`.
 *  - the icon/orb takes the bulb's live `rgb_color` when on (see [lightIconTint]).
 *  - unavailable / unknown lights render greyed with no actionable control; a
 *    missing entity shows the standard not-found card.
 */
@Composable
fun LightCard(
    card: Light,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] } ?: stateMap.byRaw(card.entityId)
    if (state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }

    val unavailable = lightIsUnavailable(state)
    val isOn = state.isOn && !unavailable
    val pct = state.percent ?: 0
    val name = resolveName(card.name, state, card.entityId)
    val tint = if (unavailable) R1.InkMuted else lightIconTint(state, R1.AccentWarm, R1.InkSoft)
    val supportsBrightness = !unavailable && lightSupportsBrightness(state)

    // HA's defaults: tap = toggle, hold = more-info. resolveCardActions applies
    // them when the config leaves a slot null, and binds the entity id on.
    val actions: CardActions = resolveCardActions(
        tapAction = card.tapAction ?: LovelaceAction.Builtin("toggle", card.entityId),
        holdAction = card.holdAction ?: LovelaceAction.Builtin("more-info", card.entityId),
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, tint.copy(alpha = 0.4f), R1.ShapeM)
            .then(
                if (unavailable) Modifier
                else Modifier.r1CardActions(actions, onAction, contentDescription = name),
            )
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // More-info affordance: a small info dot, top-right, always opening the
        // detail sheet regardless of the card-level tap action.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            MoreInfoDot(
                accent = tint,
                onClick = { onAction(LovelaceAction.Builtin("more-info", card.entityId)) },
            )
        }
        BrightnessOrb(percent = if (isOn) pct else 0, accent = tint)
        Spacer(Modifier.height(10.dp))
        Text(
            text = name,
            style = R1.titleCard,
            color = R1.Ink,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                unavailable -> state.rawState?.takeUnless { it.isBlank() } ?: "unavailable"
                isOn -> "$pct% on"
                else -> "off"
            },
            style = R1.labelMicro,
            color = tint,
        )
        if (supportsBrightness) {
            Spacer(Modifier.height(12.dp))
            BrightnessStepper(
                current = if (isOn) pct else 0,
                accent = tint,
                onSet = { next -> onAction(setBrightnessAction(card.entityId, next)) },
            )
        }
    }
}

/**
 * The − / value / + brightness control. Each nudge sends `light.turn_on` with
 * `brightness_pct` (which also turns the light on if it was off), the R1
 * value-bar idiom mapped onto the small screen.
 */
@Composable
private fun BrightnessStepper(current: Int, accent: Color, onSet: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepperButton(label = "−", accent = accent, enabled = true) {
            onSet(nextBrightnessPct(current, up = false))
        }
        Spacer(Modifier.width(10.dp))
        Text(text = "$current%", style = R1.numeralM, color = accent)
        Spacer(Modifier.width(10.dp))
        StepperButton(label = "+", accent = accent, enabled = true) {
            onSet(nextBrightnessPct(current, up = true))
        }
    }
}

/** Small circular more-info affordance, mirroring the detail-sheet entry the
 *  thermostat/humidifier cards expose. Shared with the humidifier card. */
@Composable
internal fun MoreInfoDot(accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .border(1.dp, accent.copy(alpha = 0.6f), CircleShape)
            .r1Pressable(onClick = onClick)
            .semantics { contentDescription = "More information" },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "i", style = R1.labelMicro, color = accent)
    }
}

/**
 * A 96dp orb whose interior alpha matches the brightness percent. Off is a
 * hollow ring; full is a fully-filled accent disc. The radial gradient gives the
 * orb a "bulb glow" feel without resorting to a texture.
 */
@Composable
private fun BrightnessOrb(percent: Int, accent: Color) {
    val pct = percent.coerceIn(0, 100)
    val alpha = (pct / 100f) * 0.85f + if (pct > 0) 0.15f else 0f
    Box(
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0f to accent.copy(alpha = alpha),
                        0.65f to accent.copy(alpha = alpha * 0.55f),
                        1f to accent.copy(alpha = 0.0f),
                    ),
                    center = Offset.Unspecified,
                ),
            )
            .border(1.dp, accent.copy(alpha = if (pct == 0) 0.5f else 0f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (pct == 0) "·" else "$pct",
            style = R1.numeralM,
            color = if (pct == 0) accent else R1.Bg,
        )
    }
}

private fun setBrightnessAction(entityId: String, pct: Int): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "light.turn_on",
        entityId = entityId,
        data = buildJsonObject { put("brightness_pct", JsonPrimitive(pct.coerceIn(1, 100))) },
    )
