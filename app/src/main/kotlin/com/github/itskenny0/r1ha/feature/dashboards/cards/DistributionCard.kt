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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
    // Entities the user has toggled off via the legend; their segment is dropped
    // from the bar (and the legend item dims) until tapped again. Keyed by the
    // card identity so re-rendering the same card preserves the hidden set.
    var hidden by remember(card) { mutableStateOf(emptySet<String>()) }
    // SI-normalise each entry's value by its unit prefix (kW -> W, mA -> A, ...)
    // so a mixed-prefix set weights by true magnitude rather than raw number.
    val values = card.entries.map { e ->
        val state = stateMap.byRaw(e.entityId)
        val raw = distributionValueOf(state)
        if (e.entityId in hidden) null else normalizeBySiPrefix(raw, state?.unit)
    }
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
        // Legend. Tapping a legend item toggles its segment's visibility (HA's
        // _toggleEntity); a long-press equivalent isn't available on the R1, so
        // more-info is reachable from the segment itself in the bar above.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            card.entries.forEachIndexed { i, entry ->
                val state = stateMap.byRaw(entry.entityId)
                val label = resolveName(entry.name, state, entry.entityId)
                val valueText = state?.let { compactStateText(it) } ?: "-"
                val isHidden = entry.entityId in hidden
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .alpha(if (isHidden) 0.4f else 1f)
                        .r1Pressable(
                            onClick = {
                                hidden = if (isHidden) hidden - entry.entityId else hidden + entry.entityId
                            },
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
 * Normalise [value] by a leading SI prefix on [unit] (T/G/M/k/m/µ), a port of
 * HA's `normalizeValueBySIPrefix`. Only fires when the unit is longer than one
 * char and starts with a recognised prefix, so a bare "m" (metres) or "A"
 * (amps) is left alone. Lets the distribution bar weight a kW entry against a
 * W entry by true magnitude. Null value passes through.
 */
internal fun normalizeBySiPrefix(value: Double?, unit: String?): Double? {
    if (value == null) return null
    if (unit == null || unit.length <= 1) return value
    val factor = when (unit[0]) {
        'T' -> 1e12
        'G' -> 1e9
        'M' -> 1e6
        'k' -> 1e3
        'm' -> 1e-3
        'µ', 'μ' -> 1e-6
        else -> return value
    }
    return value * factor
}

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
