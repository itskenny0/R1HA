package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `vertical-stack` card. Wraps its children in a
 * Column with a small gap between rows. No surface of its own. the
 * stack is purely a layout primitive (HA's `vertical-stack-in-card`
 * for the boxed variant is a separate type we don't model yet).
 */
@Composable
fun VerticalStackCard(
    card: LovelaceCard.VerticalStack,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (!card.title.isNullOrBlank()) {
            Text(
                text = card.title,
                style = R1.sectionHeader,
                color = R1.InkSoft,
            )
        }
        card.cards.forEach { child ->
            // Hand each child only its own entity slice so a state change on
            // one row doesn't recompose its siblings.
            LovelaceCardRenderer(child, stateMap.sliceFor(child), onAction)
        }
    }
}

/** Renderer for `horizontal-stack`. Children get equal weight so a
 *  stack of three buttons fills the available width evenly. */
@Composable
fun HorizontalStackCard(
    card: LovelaceCard.HorizontalStack,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        if (!card.title.isNullOrBlank()) {
            Text(
                text = card.title,
                style = R1.sectionHeader,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(4.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            card.cards.forEach { child ->
                LovelaceCardRenderer(
                    card = child,
                    stateMap = stateMap.sliceFor(child),
                    onAction = onAction,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Renderer for `grid`. `columns` defaults to 3; we honour it but cap
 * at the surface width. a 6-column grid on a 600 dp phone tablet would
 * shrink each child to invisibility. Chunked into rows; the final row
 * pads with weighted spacers so children don't grow asymmetrically.
 */
@Composable
fun GridCard(
    card: LovelaceCard.Grid,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cols = card.columns.coerceIn(1, 6)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!card.title.isNullOrBlank()) {
            Text(text = card.title, style = R1.sectionHeader, color = R1.InkSoft)
        }
        card.cards.chunked(cols).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowCards.forEach { child ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        LovelaceCardRenderer(card = child, stateMap = stateMap.sliceFor(child), onAction = onAction)
                    }
                }
                // Pad the trailing slots so the last row doesn't stretch
                // single-child gridwise.
                repeat(cols - rowCards.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
