package com.github.itskenny0.r1ha.feature.dashboards.cards

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
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.LovelaceBadgeRow
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Renderer for HA's `heading` card. A typographic section separator, now with
 * an optional leading icon, a trailing row of action badges (2026.2), and a
 * whole-heading tap action (2024.10).
 */
@Composable
fun HeadingCard(
    card: LovelaceCard.Heading,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val style = when (card.headingStyle.lowercase()) {
        "subtitle" -> R1.sectionHeader
        else -> R1.screenTitle
    }
    val hasTap = card.tapAction != null
    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp)) {
        val rowMod = if (hasTap) {
            Modifier.fillMaxWidth().r1Pressable(onClick = { onAction(card.tapAction!!) })
        } else {
            Modifier.fillMaxWidth()
        }
        Row(modifier = rowMod, verticalAlignment = Alignment.CenterVertically) {
            R1Icons.forMdi(card.icon)?.let { icon ->
                Icon(imageVector = icon, contentDescription = null, tint = R1.InkSoft, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text = card.heading, style = style, color = R1.Ink, modifier = Modifier.weight(1f))
            if (hasTap) {
                Spacer(Modifier.width(8.dp))
                Text(text = "›", style = R1.numeralM, color = R1.InkSoft)
            }
        }
        if (card.badges.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            LovelaceBadgeRow(badges = card.badges, states = stateMap, onAction = onAction)
        }
    }
}
