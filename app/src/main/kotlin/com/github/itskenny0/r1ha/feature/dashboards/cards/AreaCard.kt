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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `area` card. The full card resolves an area's member
 * entities from HA's area registry, which R1HA doesn't mirror locally, so
 * this renderer shows the area heading (name + optional background image)
 * plus any entities the config explicitly attached. An area with no listed
 * entities still reads as a labelled tile a user can recognise; tapping it
 * fires the configured navigation, if any.
 */
@Composable
fun AreaCard(
    card: LovelaceCard.Area,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = card.name?.takeUnless { it.isBlank() }
        ?: card.area.replace('_', ' ').replaceFirstChar { it.uppercase() }
    val tap: (() -> Unit)? = card.navigationPath?.let { p ->
        { onAction(LovelaceAction.Navigate(p)) }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .let { if (tap != null) it.r1Pressable(onClick = tap) else it },
    ) {
        if (!card.image.isNullOrBlank()) {
            AsyncBitmap(
                url = card.image,
                serverUrl = LocalHaServerUrl.current,
                bearerToken = LocalHaBearerToken.current,
                modifier = Modifier.fillMaxWidth().height(96.dp),
                contentDescription = null,
            )
        }
        Text(
            text = title,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
        )
        if (card.entities.isNotEmpty()) {
            card.entities.forEach { row ->
                val eid = safeEntityId(row.entityId)
                val state = eid?.let { stateMap[it] }
                val accent = stateAccentFor(row.entityId, state)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = resolveName(row.name, state, row.entityId),
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() }
                    if (stateText != null) {
                        Spacer(Modifier.width(10.dp))
                        StateChip(text = stateText, accent = accent)
                    }
                }
            }
        } else {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                Text(
                    text = "Area members resolve in Lovelace",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}
