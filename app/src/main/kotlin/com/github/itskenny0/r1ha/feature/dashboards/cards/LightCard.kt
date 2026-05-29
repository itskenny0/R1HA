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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Light
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `light` card. Distinct visual treatment from the
 * generic tile: an oversized brightness orb with the percent reading
 * sliced through it (numeralXl), the friendly name underneath, and a
 * tap-anywhere toggle. No brightness scrub here (a full slider belongs
 * on the dedicated card-stack screen); tap is the dashboard interaction.
 */
@Composable
fun LightCard(
    card: Light,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(card.entityId)
    val isOn = state?.isOn == true
    val pct = state?.percent ?: 0
    val name = resolveName(card.name, state, card.entityId)
    val accent = if (isOn) R1.AccentWarm else R1.InkSoft

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .r1Pressable(onClick = { onAction(LovelaceAction.Builtin("toggle", card.entityId)) })
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        BrightnessOrb(percent = if (isOn) pct else 0, accent = accent)
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
            text = if (isOn) "$pct% on" else "off",
            style = R1.labelMicro,
            color = accent,
        )
    }
}

/**
 * A 96dp orb whose interior alpha matches the brightness percent. Off
 * is a hollow ring; full is a fully-filled accent disc. The radial
 * gradient gives the orb a "bulb glow" feel without resorting to a
 * texture.
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
