package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Renderer for HA's `shortcut` card (2026.5): a tile-shaped one-tap launcher.
 * The whole card fires the configured action. Label/icon/colour come from the
 * card config; when the name is omitted we best-effort a label from a navigate
 * path's last segment, else a generic "Shortcut".
 */
@Composable
fun ShortcutCard(
    card: LovelaceCard.Shortcut,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = haColorAccent(card.color) ?: R1.AccentWarm
    val label = card.name?.takeUnless { it.isBlank() } ?: shortcutLabelFor(card.tapAction)
    val icon = R1Icons.forMdi(card.icon)
    val action = card.tapAction
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .let { m -> if (action != null) m.r1Pressable(onClick = { onAction(action) }) else m }
            .padding(horizontal = 14.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (icon != null) {
            Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(10.dp))
        }
        Text(
            text = label,
            style = R1.titleCard,
            color = R1.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
