package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `button` card. Big tappable accent-bordered box;
 * fires the configured `tap_action` (or the entity-default action when
 * the config omits one). Optional secondary line shows the live state.
 */
@Composable
fun ButtonCard(
    card: LovelaceCard.Button,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = card.entityId?.let { stateMap.byRaw(it) }
    val accent = stateAccentFor(card.entityId.orEmpty(), state)
    val label = card.name ?: card.entityId?.let { resolveName(null, state, it) } ?: "Action"
    // Resolve tap (with HA's domain-default fallback) plus hold / double-tap,
    // all bound to the card entity, in one shot via the shared action layer.
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.6f), R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = label)
            .padding(horizontal = 14.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // HA's button card is icon-forward: a large glyph disc tops the surface.
        // We honour `show_icon` and only show a glyph when we can derive one
        // (an entity-bound button); a bare action button without an entity skips
        // the disc rather than drawing a meaningless placeholder.
        if (card.showIcon && card.entityId != null) {
            val icon = cardEntityIcon(card.entityId, state, card.icon)
            CardIconDisc(icon = icon, accent = accent, discSize = 48.dp, iconSize = 24.dp)
            Spacer(Modifier.height(10.dp))
        }
        if (card.showName) {
            Text(
                text = label,
                style = R1.titleCard,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (card.showState && state != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = compactStateText(state),
                style = R1.labelMicro,
                color = accent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
    }
}
