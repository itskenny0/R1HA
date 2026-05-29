package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `heading` card. Section divider inside a view . 
 * no chrome of its own (no fill, no border), just a typographic
 * separator. The `heading_style` config picks the size; everything
 * else falls back to the title variant.
 */
@Composable
fun HeadingCard(card: LovelaceCard.Heading, modifier: Modifier = Modifier) {
    val style = when (card.headingStyle.lowercase()) {
        "subtitle" -> R1.sectionHeader
        else -> R1.screenTitle
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 8.dp),
    ) {
        Text(
            text = card.heading,
            style = style,
            color = R1.Ink,
        )
    }
}
