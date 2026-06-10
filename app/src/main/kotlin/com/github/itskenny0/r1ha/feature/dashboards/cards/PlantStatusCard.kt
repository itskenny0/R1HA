package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `plant-status` card (hui-plant-status-card.ts). Surfaces the
 * plant entity's moisture / temperature / brightness / conductivity / battery
 * readouts in a column of icon-led chips, flagging any readout the plant reports
 * as a problem (its `problem` attribute) in the error colour. Tapping a readout
 * opens more-info for that readout's backing sensor; tapping the title opens the
 * plant entity itself.
 *
 * R1HA has no typed plant-status model, so the entity id is read from the
 * [LovelaceCard.Unsupported.entityRefs] the parser scraped off the card's
 * `entity` key (a plant config always carries one).
 */
@Composable
fun PlantStatusCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entityId = card.entityRefs.firstOrNull()
    if (entityId == null) {
        UnsupportedCard(card.copy(entityRefs = emptyList()), stateMap, onAction, modifier)
        return
    }
    val state = stateMap.byRaw(entityId)
    if (state == null) {
        EntityNotFoundCard(entityId, modifier)
        return
    }
    val name = (card.raw["name"] as? JsonPrimitive)?.content ?: resolveName(null, state, entityId)
    val readouts = plantReadouts(state)
    val hasProblem = plantHasProblem(state)
    val accent = if (hasProblem) R1.StatusRed else R1.AccentGreen

    CardSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .r1Pressable(onClick = { onAction(defaultTapAction(entityId)) })
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CardIconDisc(
                icon = cardEntityIcon(entityId, state, (card.raw["icon"] as? JsonPrimitive)?.content ?: "mdi:flower"),
                accent = accent,
                discSize = 32.dp,
                iconSize = 20.dp,
                showBorder = false,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = name, style = R1.bodyEmph, color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Plant state ("ok" / "problem") read as the status line.
                Text(
                    text = if (hasProblem) "PROBLEM" else "OK",
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                )
            }
        }
        if (readouts.isEmpty()) {
            EmptyRow(text = "No plant readouts")
        } else {
            Spacer(Modifier.height(4.dp))
            readouts.forEach { readout ->
                PlantReadoutRow(readout = readout, onAction = onAction)
            }
        }
    }
}

@Composable
private fun PlantReadoutRow(readout: PlantReadout, onAction: (LovelaceAction) -> Unit) {
    val accent = if (readout.isProblem) R1.StatusRed else R1.InkSoft
    val pressable = readout.backingEntity?.let { backing ->
        Modifier.r1Pressable(onClick = { onAction(defaultTapAction(backing)) }, contentDescription = readout.attribute)
    } ?: Modifier
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(pressable)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIconDisc(
            icon = cardEntityIcon(readout.backingEntity ?: "sensor.${readout.attribute}", state = null, configIcon = plantAttributeIcon(readout.attribute)),
            accent = accent,
            discSize = 24.dp,
            iconSize = 14.dp,
            showBorder = false,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = readout.attribute.replaceFirstChar { it.uppercase() },
            style = R1.body,
            color = if (readout.isProblem) R1.StatusRed else R1.Ink,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = readout.unit?.let { "${readout.value} $it" } ?: readout.value,
            style = R1.bodyEmph,
            color = accent,
            maxLines = 1,
        )
    }
}

/** The MDI slug HA uses per plant readout. */
private fun plantAttributeIcon(attribute: String): String = when (attribute) {
    "moisture" -> "mdi:water-percent"
    "temperature" -> "mdi:thermometer"
    "brightness" -> "mdi:white-balance-sunny"
    "conductivity" -> "mdi:sprout"
    "battery" -> "mdi:battery"
    else -> "mdi:gauge"
}
