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
 * Renderer for HA's `home-summary` card. Each card shows one of the seven
 * summary categories (light, climate, security, media_players, maintenance,
 * energy, persons) with its icon and label, tapping navigates to the nearest
 * native R1HA screen.
 *
 * The per-card state slice is empty (the card has no entity refs, so
 * collectEntityIds yields nothing). Status counts requiring a domain-wide
 * entity scan are therefore not computed here; the card acts as a tappable
 * navigation tile, consistent with HA's own use-case (quick jump to a
 * domain surface).
 */
@Composable
fun HomeSummaryCard(
    card: LovelaceCard.HomeSummary,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = HomeSummaryCardLogic.labelFor(card.summary)
    val iconId = HomeSummaryCardLogic.iconFor(card.summary)
    val icon = R1Icons.forMdi(iconId)
    val accent = R1.AccentWarm

    // Explicit tap_action wins; otherwise navigate to the nearest native screen.
    val defaultTap = LovelaceAction.Navigate(HomeSummaryCardLogic.navRouteFor(card.summary))
    val actions = CardActions(
        tap = card.tapAction ?: defaultTap,
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )

    val outerMod = modifier
        .fillMaxWidth()
        .clip(R1.ShapeM)
        .background(R1.Surface)
        .border(1.dp, R1.Hairline, R1.ShapeM)
        .r1CardActions(actions = actions, onAction = onAction, contentDescription = label)

    if (card.vertical) {
        Column(
            modifier = outerMod.padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(32.dp))
                Spacer(Modifier.height(8.dp))
            }
            Text(text = label, style = R1.titleCard, color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    } else {
        Row(
            modifier = outerMod.padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(imageVector = icon, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
                Spacer(Modifier.width(12.dp))
            }
            Text(
                text = label,
                style = R1.titleCard,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}
