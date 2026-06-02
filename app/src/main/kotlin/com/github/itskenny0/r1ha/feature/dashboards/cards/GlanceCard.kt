package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `glance` card. A compact tile grid where each tile
 * is icon-on-top, name-below, optional state line. Columns honour the
 * card config's `columns` field, falling back to a width-aware default
 * (3 on tablets, 2 on phones, 2 on R1. except that R1 doesn't surface
 * this feature at all per the breakpoint gate).
 */
@Composable
fun GlanceCard(
    card: LovelaceCard.Glance,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        if (card.entities.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        val widthDp = LocalConfiguration.current.screenWidthDp
        val cols = card.columns?.coerceIn(1, 6) ?: when {
            widthDp >= 720 -> 4
            widthDp >= 480 -> 3
            else -> 2
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            card.entities.chunked(cols).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { e ->
                        GlanceTile(
                            row = e,
                            stateMap = stateMap,
                            showName = card.showName,
                            showState = card.showState,
                            showIcon = card.showIcon,
                            onAction = onAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the last row so the trailing tile doesn't grow to fill.
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GlanceTile(
    row: EntityRow,
    stateMap: EntityStates,
    showName: Boolean,
    showState: Boolean,
    showIcon: Boolean,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolve by raw id, the domain-agnostic path used by EntitiesCard /
    // TileCard (no need to round-trip through a typed EntityId just to read a
    // state slice).
    val state = stateMap.byRaw(row.entityId)
    val name = resolveName(row.name, state, row.entityId)
    val accent = stateAccentFor(row.entityId, state)
    Column(
        modifier = modifier
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showIcon) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = 0.18f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "·", style = R1.numeralM, color = accent)
            }
            Spacer(Modifier.height(6.dp))
        }
        if (showName) {
            Text(
                text = name,
                style = R1.labelMicro,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (showState) {
            // A genuinely-absent state shows a single dash rather than a ". " stub
            // (which reads as a rendering glitch); a blank readout from
            // compactStateText collapses to the same dash.
            val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() } ?: "-"
            Spacer(Modifier.height(3.dp))
            Text(
                text = stateText,
                style = R1.labelMicro,
                color = accent,
                maxLines = 1,
            )
        }
    }
}
