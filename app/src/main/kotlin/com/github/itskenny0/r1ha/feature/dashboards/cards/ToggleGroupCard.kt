package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `toggle-group` card (hui-toggle-group-card.ts): a labelled
 * row of segmented on/off toggles, one per configured entity. Each segment
 * shows the entity's name + a glyph that reflects its live state and toggles
 * that entity on tap. An entity with no live state still renders (greyed) so
 * the group layout is stable.
 *
 * R1HA's typed model has no dedicated toggle-group variant, so this reads its
 * entity set from the [LovelaceCard.Unsupported.entityRefs] the parser captures
 * off the card's `entities` array (those entities are subscribed, so the
 * segments are live).
 */
@Composable
fun ToggleGroupCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = card.raw["name"]?.let { (it as? JsonPrimitive)?.content }
        ?: card.raw["title"]?.let { (it as? JsonPrimitive)?.content }
    CardSurface(modifier = modifier, title = title?.takeUnless { it.isBlank() }) {
        if (card.entityRefs.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            // Lay the toggles out two-per-row so a group of more than a couple
            // of entities doesn't overflow the narrow R1 screen.
            card.entityRefs.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { ref ->
                        ToggleSegment(
                            ref = ref,
                            stateMap = stateMap,
                            onAction = onAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ToggleSegment(
    ref: String,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(ref)
    val on = state?.isOn == true
    val name = resolveName(null, state, ref)
    val accent = stateAccentFor(ref, state)
    Row(
        modifier = modifier
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeM)
            .background(if (on) accent.copy(alpha = 0.16f) else R1.SurfaceMuted)
            .border(1.dp, if (on) accent.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = { onAction(LovelaceAction.Builtin("toggle", ref)) })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = domainGlyph(ref, state), style = R1.numeralS, color = accent)
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = R1.labelMicro,
            color = if (on) accent else R1.InkSoft,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
