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
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.WindowTier

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
    // Drop children whose visibility conditions fail BEFORE laying out, so a
    // hidden conditional consumes no inter-row gap (a zero-height child would
    // still pick up the spacedBy spacing on both sides).
    val visible = card.cards.filter { cardWillRender(it, stateMap.sliceFor(it)) }
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
        visible.forEach { child ->
            // Hand each child only its own entity slice so a state change on
            // one row doesn't recompose its siblings.
            LovelaceCardRenderer(child, stateMap.sliceFor(child), onAction)
        }
    }
}

/** Renderer for `horizontal-stack`. Children get equal weight so a
 *  stack of three buttons fills the available width evenly. On narrow
 *  windows (R1 / compact phones) a stack of more than [maxPerRow] children
 *  would squish each to illegibility, so we wrap into multiple rows. */
@Composable
fun HorizontalStackCard(
    card: LovelaceCard.HorizontalStack,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tier = LocalWindowTier.current.tier
    // Hide cards whose conditions fail so they don't claim an equal-weight slot
    // that would shrink the surviving siblings (HA removes the card entirely).
    val visible = card.cards.filter { cardWillRender(it, stateMap.sliceFor(it)) }
    val maxPerRow = horizontalStackMaxPerRow(visible.size, tier)
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
        visible.chunked(maxPerRow).forEach { rowCards ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowCards.forEach { child ->
                    androidx.compose.foundation.layout.Box(modifier = Modifier.weight(1f)) {
                        LovelaceCardRenderer(
                            card = child,
                            stateMap = stateMap.sliceFor(child),
                            onAction = onAction,
                        )
                    }
                }
                // Pad the trailing slots so a short final row keeps the same
                // child width as the full rows above it.
                repeat(maxPerRow - rowCards.size) { Spacer(Modifier.weight(1f)) }
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
    val tier = LocalWindowTier.current.tier
    val cols = responsiveColumnCount(card.columns, tier)
    // Hidden conditionals are removed before chunking so the grid doesn't leave
    // an empty weighted cell where a failed-condition card would have sat.
    val visible = card.cards.filter { cardWillRender(it, stateMap.sliceFor(it)) }
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!card.title.isNullOrBlank()) {
            Text(text = card.title, style = R1.sectionHeader, color = R1.InkSoft)
        }
        visible.chunked(cols).forEach { rowCards ->
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

/**
 * Effective column count for a `grid` card given the author's requested
 * [requested] columns and the current window [tier]. We honour the request on
 * roomy windows but cap it on narrow ones so cards stay legible: the R1 panel
 * never shows more than 2 columns and compact phones cap at 3, regardless of a
 * config that asks for 6. The author's value is always respected as a ceiling
 * (a 1-column grid stays 1-column even on a big tablet).
 */
internal fun responsiveColumnCount(requested: Int, tier: WindowTier): Int {
    val asked = requested.coerceIn(1, 6)
    val cap = when (tier) {
        WindowTier.R1 -> 2
        WindowTier.COMPACT -> 3
        WindowTier.MEDIUM -> 4
        WindowTier.EXPANDED -> 6
        WindowTier.EXTRA_LARGE -> 6
    }
    return asked.coerceAtMost(cap)
}

/**
 * How many `horizontal-stack` children to place per row before wrapping. A
 * horizontal stack is authored as a single row; on a narrow window that row
 * squishes every child, so we wrap. Returns the full child [count] on wider
 * tiers (no wrap), and a small per-row cap on the narrowest tiers. Always at
 * least 1 to avoid an empty chunk.
 */
internal fun horizontalStackMaxPerRow(count: Int, tier: WindowTier): Int {
    if (count <= 1) return 1
    val cap = when (tier) {
        WindowTier.R1 -> 2
        WindowTier.COMPACT -> 3
        WindowTier.MEDIUM -> 4
        WindowTier.EXPANDED -> 6
        WindowTier.EXTRA_LARGE -> Int.MAX_VALUE
    }
    return count.coerceAtMost(cap).coerceAtLeast(1)
}
