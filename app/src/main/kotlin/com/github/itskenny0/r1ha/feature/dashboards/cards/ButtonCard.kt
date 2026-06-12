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
    // HA renders a hui-warning when the button is bound to an entity the backend
    // doesn't serve. A bare action button (no entity) is fine and skips this.
    if (card.entityId != null && state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }
    // HA precedence: an explicit `color` wins; else `state_color` gates the
    // state-derived tint; else the button stays neutral.
    val accent = buttonAccent(card.color, card.stateColor, card.entityId, state)
    val label = card.name?.takeUnless { it.isBlank() }
        ?: card.entityId?.let { resolveStructuredName(null, card.nameItems, null, state, it) }
        ?: "Action"
    val discSize = iconHeightDp(card.iconHeight)?.dp ?: 48.dp
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
        // We honour `show_icon` and show a glyph when we can derive one: from
        // the bound entity (with the config icon as override), or for a bare
        // action button (no entity, e.g. a pinned IR-command button) from the
        // config `icon:` alone. Only an unresolvable / absent icon skips the
        // disc, rather than drawing a meaningless placeholder.
        val icon = when {
            !card.showIcon -> null
            card.entityId != null -> cardEntityIcon(card.entityId, state, card.icon)
            else -> com.github.itskenny0.r1ha.ui.icons.R1Icons.forMdi(card.icon)
        }
        if (icon != null) {
            // HA's `icon_height` sizes the glyph; the disc and inner glyph scale
            // together so a taller icon reads proportionally on the small screen.
            CardIconDisc(icon = icon, accent = accent, discSize = discSize, iconSize = discSize * 0.5f)
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
