package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Instant

/**
 * Renderer for the `energy-date-selection` card: the compact period host that
 * drives every energy card bound to the same collection key. Lays out on the
 * 640px screen as a single row: a back chevron, the preset dropdown / period
 * title in the middle, a forward chevron, and a compare toggle below.
 *
 * Tapping the title opens the preset menu; the chevrons shift the window by its
 * own span. All of it mutates the shared [EnergyCollectionState], so the change
 * fans out to the bound cards.
 */
@Composable
fun EnergyDateSelectionCard(
    card: LovelaceCard.EnergyDateSelection,
    modifier: Modifier = Modifier,
) {
    val collection = rememberEnergyCollection(card.collectionKey)
    val data by collection.data.collectAsStateWithLifecycle()
    val period = data.period
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            ChevronButton(ChevronDirection.Left) {
                collection.setPeriod(EnergyPeriodEngine.shift(period, forward = false))
            }
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.Center) {
                Text(
                    text = EnergyPeriodEngine.title(period),
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .r1Pressable(onClick = { menuOpen = true })
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    EnergyDateRange.entries.forEach { range ->
                        DropdownMenuItem(
                            text = { Text(range.label, style = R1.labelMicro, color = R1.Ink) },
                            onClick = {
                                menuOpen = false
                                collection.setPeriod(
                                    EnergyPeriodEngine.resolve(range, Instant.now(), compare = period.compare),
                                )
                            },
                        )
                    }
                }
            }
            ChevronButton(ChevronDirection.Right) {
                collection.setPeriod(EnergyPeriodEngine.shift(period, forward = true))
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier
                .clip(R1.ShapeRound)
                .r1Pressable(onClick = { collection.setCompare(!period.compare) })
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(R1.ShapeRound)
                    .background(if (period.compare) R1.AccentCool else R1.SurfaceMuted),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = "Compare to previous",
                style = R1.labelMicro,
                color = if (period.compare) R1.Ink else R1.InkMuted,
            )
        }
    }
}

@Composable
private fun ChevronButton(direction: ChevronDirection, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(32.dp)
            .clip(R1.ShapeRound)
            .r1Pressable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(direction = direction, size = 14.dp, tint = R1.InkSoft)
    }
}
