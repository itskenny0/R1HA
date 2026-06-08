package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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

/**
 * Renderer for HA's `distribution` card (2026.2). One proportional segmented
 * bar across the configured entities, plus a legend. Each segment's width is
 * the entity's numeric state over the sum of all entities' states; non-numeric
 * or negative states count as zero. Tapping a segment or legend item opens
 * more-info for that entity.
 */
@Composable
fun DistributionCard(
    card: LovelaceCard.Distribution,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val values = card.entries.map { distributionValueOf(stateMap.byRaw(it.entityId)) }
    val weights = distributionWeights(values)
    val colors = card.entries.map { e ->
        haColorAccent(e.color) ?: stateAccentFor(e.entityId, stateMap.byRaw(e.entityId))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        if (!card.title.isNullOrBlank()) {
            Text(text = card.title, style = R1.sectionHeader, color = R1.InkSoft)
            Spacer(Modifier.height(10.dp))
        }
        if (weights.all { it <= 0f }) {
            Text(text = "No distribution data", style = R1.labelMicro, color = R1.InkMuted)
            return@Column
        }
        // Segmented bar. A zero-weight entry emits no segment.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(18.dp)
                .clip(R1.ShapeRound),
        ) {
            card.entries.forEachIndexed { i, entry ->
                val w = weights[i]
                if (w <= 0f) return@forEachIndexed
                Box(
                    modifier = Modifier
                        .weight(w)
                        .fillMaxWidth()
                        .height(18.dp)
                        .background(colors[i])
                        .r1Pressable(onClick = { onAction(LovelaceAction.Builtin("more-info", entry.entityId)) }),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        // Legend.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            card.entries.forEachIndexed { i, entry ->
                val state = stateMap.byRaw(entry.entityId)
                val label = resolveName(entry.name, state, entry.entityId)
                val valueText = state?.let { compactStateText(it) } ?: "-"
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.r1Pressable(
                        onClick = { onAction(LovelaceAction.Builtin("more-info", entry.entityId)) },
                    ),
                ) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(colors[i]))
                    Spacer(Modifier.width(6.dp))
                    Text(text = "$label $valueText", style = R1.labelMicro, color = R1.Ink)
                }
            }
        }
    }
}

/** Numeric value a distribution segment uses: the entity's state parsed as a
 *  number, or null when the entity is absent or its state is non-numeric. */
internal fun distributionValueOf(state: com.github.itskenny0.r1ha.core.ha.EntityState?): Double? =
    state?.rawState?.toDoubleOrNull()

/**
 * Pure helper: turn raw per-entity values into proportional weights summing to
 * 1 (or all-zero when nothing is positive). A null or negative value is zero.
 */
internal fun distributionWeights(values: List<Double?>): List<Float> {
    val clamped = values.map { (it ?: 0.0).coerceAtLeast(0.0) }
    val sum = clamped.sum()
    if (sum <= 0.0) return clamped.map { 0f }
    return clamped.map { (it / sum).toFloat() }
}
