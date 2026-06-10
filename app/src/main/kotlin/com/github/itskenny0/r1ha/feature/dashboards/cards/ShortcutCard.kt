package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Renderer for HA's `shortcut` card (2026.5): a tile-shaped one-tap launcher.
 * The whole card fires the configured action. Label/description/icon/colour come
 * from the card config; when neither `label` nor `name` is set we best-effort
 * a label from a navigate path's last segment, else a generic "Shortcut".
 *
 * Layout:
 *  - vertical=false (default): icon LEFT + label/description column RIGHT (tile row).
 *  - vertical=true: icon ABOVE + label/description BELOW (centred column).
 */
@Composable
fun ShortcutCard(
    card: LovelaceCard.Shortcut,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = haColorAccent(card.color) ?: R1.AccentWarm
    // `label` takes precedence over `name` (HA 2026.5); fall back to action-derived.
    val label = card.displayLabel ?: shortcutLabelFor(card.tapAction)
    val description = card.description?.takeUnless { it.isBlank() }
    val icon = R1Icons.forMdi(card.icon)
    val actions = CardActions(
        tap = card.tapAction,
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )
    val outerMod = modifier
        .fillMaxWidth()
        .clip(R1.ShapeM)
        .background(R1.Surface)
        .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
        .r1CardActions(actions = actions, onAction = onAction, contentDescription = label)

    if (card.vertical) {
        // Vertical layout: icon above, text centred below.
        Column(
            modifier = outerMod.padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text = label,
                style = R1.titleCard,
                color = R1.Ink,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (description != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = description,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    } else {
        // Horizontal layout: icon left, text column right.
        Row(
            modifier = outerMod.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(32.dp),
                )
                Spacer(Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (description != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = description,
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Best-effort label from an action when the card omits a name. */
internal fun shortcutLabelFor(action: LovelaceAction?): String = when (action) {
    is LovelaceAction.Navigate -> action.path.trimEnd('/').substringAfterLast('/')
        .replace('-', ' ').replace('_', ' ')
        .replaceFirstChar { it.uppercase() }
        .ifBlank { "Shortcut" }
    is LovelaceAction.Url -> "Open link"
    else -> "Shortcut"
}
