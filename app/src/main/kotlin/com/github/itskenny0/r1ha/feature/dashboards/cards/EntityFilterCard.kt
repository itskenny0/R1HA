package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `entity-filter` card. Shows only the configured
 * entities whose live state currently matches one of the card's
 * `state_filter` values; the survivors render as a compact entities-style
 * list. With no filter every entity is shown (HA's behaviour). When
 * nothing passes and `show_empty` is false the card collapses to nothing,
 * mirroring HA; otherwise a small placeholder keeps the card visible so
 * the layout doesn't jump.
 */
@Composable
fun EntityFilterCard(
    card: LovelaceCard.EntityFilter,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val visible = remember(card.entities, card.stateFilter, stateMap) {
        filterEntityRows(card.entities, card.stateFilter) { raw ->
            safeEntityId(raw)?.let { stateMap[it]?.rawState }
        }
    }
    if (visible.isEmpty() && !card.showEmpty) return
    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        if (visible.isEmpty()) {
            EmptyRow(text = "Nothing matches the filter")
            return@CardSurface
        }
        visible.forEachIndexed { idx, row ->
            if (idx > 0) FilterDivider()
            FilterRow(row = row, stateMap = stateMap, onAction = onAction)
        }
    }
}

@Composable
private fun FilterRow(
    row: EntityRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val eid = safeEntityId(row.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(row.name, state, row.entityId)
    val stateText = state?.let { compactStateText(it) } ?: ". "
    val accent = stateAccentFor(row.entityId, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        StateChip(text = stateText, accent = accent)
    }
}

@Composable
private fun FilterDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}

/**
 * Pure filter: keep the rows whose current state (resolved via
 * [stateOf]) matches one of [stateFilter], case-insensitively. An empty
 * [stateFilter] keeps every row (HA semantics). A row with no resolvable
 * state is dropped when a filter is active (it can't satisfy the filter)
 * and kept when there's no filter. Stateless and side-effect-free so it
 * can be unit-tested without Compose.
 */
internal fun filterEntityRows(
    rows: List<EntityRow>,
    stateFilter: List<String>,
    stateOf: (String) -> String?,
): List<EntityRow> {
    if (stateFilter.isEmpty()) return rows
    return rows.filter { row ->
        val s = stateOf(row.entityId) ?: return@filter false
        stateFilter.any { it.equals(s, ignoreCase = true) }
    }
}
