package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import com.github.itskenny0.r1ha.ui.components.r1Pressable

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
    // Bind the card's entity to the resolved action so a config `tap_action:
    // toggle` (which parses without an entity) still has a target to act on.
    val resolvedAction = (card.tapAction ?: card.entityId?.let { defaultTapAction(it) })
        ?.boundTo(card.entityId)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.6f), R1.ShapeM)
            .r1Pressable(onClick = { resolvedAction?.let(onAction) })
            .padding(horizontal = 14.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
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
            )
        }
        Spacer(Modifier.height(6.dp))
        // Always show a small action chip so a button-card with hidden
        // name + state still reads as tappable. Mirrors HA's visual: even
        // a bare button surface has a hint that something will happen.
        StateChip(text = "tap", accent = accent)
    }
}
