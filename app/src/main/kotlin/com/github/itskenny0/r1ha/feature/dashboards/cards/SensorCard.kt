package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.SensorHistoryChart

/**
 * Renderer for HA's `sensor` card. A single numeric (or text) sensor's
 * name + current value; when the config sets `graph: line` the renderer
 * fetches the entity's recent history and overlays a compact line chart
 * (reusing [SensorHistoryChart], the same canvas the per-entity history
 * drill-in uses) so the dashboards renderer doesn't carry its own chart.
 *
 * History is fetched off [LocalHaRepository]; when that's unset (the
 * dashboards host didn't provide one) the card degrades gracefully to the
 * name + value readout with no graph.
 */
@Composable
fun SensorCard(
    card: LovelaceCard.Sensor,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val accent = stateAccentFor(card.entityId, state)
    val unit = card.unit ?: state?.unit
    val valueText = state?.let { s ->
        val raw = s.rawState.orEmpty()
        if (unit != null && raw.toDoubleOrNull() != null) "$raw $unit" else compactStateText(s)
    }?.takeUnless { it.isBlank() } ?: "-"

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = cardEntityIcon(card.entityId, state, card.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = valueText,
                style = R1.numeralM,
                color = accent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        if (card.graph && eid != null) {
            Spacer(Modifier.height(10.dp))
            val repo = LocalHaRepository.current
            var points by remember(card.entityId, card.hoursToShow) {
                mutableStateOf<List<HistoryPoint>>(emptyList())
            }
            if (repo != null) {
                LaunchedEffect(card.entityId, card.hoursToShow) {
                    repo.fetchHistory(eid, hours = card.hoursToShow)
                        .onSuccess { points = it }
                }
            }
            if (points.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "-", style = R1.labelMicro, color = R1.InkMuted)
                }
            } else {
                SensorHistoryChart(points = points, accent = accent, unit = unit)
            }
        }
    }
}
