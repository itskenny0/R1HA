package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's modern `tile` card. Compact horizontal layout: a
 * round accent-coloured icon disc on the left, friendly name + state
 * on the right. Mirrors HA's tile-card chrome: state-coloured background
 * for the icon disc, soft surface for the body, no border emphasis.
 *
 * Vertical mode flips the layout to icon-on-top so a wider screen can
 * pack tiles two-up in a column without label truncation. We honour
 * `vertical: true` from the card config when set.
 */
@Composable
fun TileCard(
    card: LovelaceCard.Tile,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(card.entityId)
    val accent = stateAccentFor(card.entityId, state)
    val name = resolveName(card.name, state, card.entityId)
    // Bind the card's entity to a config tap_action that omits one (toggle /
    // more-info / target-less call-service) so the dispatcher always has a target.
    val action = (card.tapAction ?: defaultTapAction(card.entityId)).boundTo(card.entityId)
    val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() }

    val tileSurface = Modifier
        .fillMaxWidth()
        .clip(R1.ShapeM)
        .background(R1.Surface)
        .border(1.dp, accent.copy(alpha = 0.25f), R1.ShapeM)
        .r1Pressable(onClick = { onAction(action) })
        .padding(horizontal = 14.dp, vertical = 12.dp)

    if (card.vertical) {
        Column(
            modifier = modifier.then(tileSurface),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            IconDisc(accent = accent, size = 48.dp)
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!card.hideState && stateText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stateText,
                    style = R1.labelMicro,
                    color = accent,
                )
            }
        }
    } else {
        Row(
            modifier = modifier.then(tileSurface),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconDisc(accent = accent, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (!card.hideState && stateText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stateText,
                        style = R1.body,
                        color = R1.InkSoft,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(8.dp))
            StateChip(text = if (state?.isOn == true) "on" else "off", accent = accent)
        }
    }
}

@Composable
private fun IconDisc(accent: androidx.compose.ui.graphics.Color, size: androidx.compose.ui.unit.Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.18f))
            .border(1.dp, accent.copy(alpha = 0.4f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "·", style = R1.numeralM, color = accent)
    }
}
