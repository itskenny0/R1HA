package com.github.itskenny0.r1ha.wear.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.wear.theme.WearColors

/**
 * Entity chip shared between the Lovelace overview and the Favourites screen.
 * Shows domain emoji + friendly name on the left, state label on the right.
 */
@Composable
fun WearEntityChip(entity: EntityState, onTap: () -> Unit) {
    val stateLabel = when {
        !entity.isAvailable -> "unavailable"
        entity.unit != null -> "${entity.raw ?: "—"} ${entity.unit}"
        entity.isOn         -> "ON"
        else                -> "off"
    }
    val labelColor = if (entity.isOn) WearColors.Primary
    else MaterialTheme.colors.onBackground.copy(alpha = 0.55f)

    Chip(
        label = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = domainEmoji(entity.id.domain, entity.isOn),
                        fontSize = 14.sp,
                    )
                    Text(
                        text = entity.friendlyName,
                        style = MaterialTheme.typography.caption1,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = stateLabel,
                    style = MaterialTheme.typography.caption2,
                    color = labelColor,
                    maxLines = 1,
                )
            }
        },
        onClick = onTap,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

fun domainEmoji(domain: Domain, isOn: Boolean): String = when (domain) {
    Domain.LIGHT         -> if (isOn) "💡" else "🔦"
    Domain.SWITCH        -> if (isOn) "🔌" else "⭕"
    Domain.INPUT_BOOLEAN -> if (isOn) "✅" else "⬜"
    Domain.FAN           -> "🌀"
    Domain.COVER         -> if (isOn) "⬆" else "⬇"
    Domain.SCENE         -> "🎬"
    Domain.SCRIPT        -> "▶"
    Domain.AUTOMATION    -> "⚙"
    Domain.MEDIA_PLAYER  -> if (isOn) "🔊" else "🔇"
    Domain.CLIMATE       -> "🌡"
    Domain.LOCK          -> if (isOn) "🔓" else "🔒"
    Domain.SENSOR,
    Domain.BINARY_SENSOR -> "📊"
    Domain.CAMERA        -> "📷"
    Domain.WEATHER       -> "⛅"
    Domain.PERSON        -> "👤"
    else                 -> "●"
}
