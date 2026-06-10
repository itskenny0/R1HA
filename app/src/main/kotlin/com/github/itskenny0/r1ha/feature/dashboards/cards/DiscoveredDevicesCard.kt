package com.github.itskenny0.r1ha.feature.dashboards.cards

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `discovered-devices` card (hui-discovered-devices-card.ts).
 *
 * HA's card subscribes to in-progress config flows from discovery sources and
 * counts discovered devices, with the default tap opening the add-integration
 * setup dialog. On the R1 (a read-only kiosk companion) that admin setup UX is
 * out of scope, and R1HA carries no config-flow-progress data layer, so this
 * renders the card read-only: a labelled informational tile that explains
 * discovered devices are set up from Home Assistant's own UI.
 *
 * `hide_empty` is honoured by always rendering (HA hides the card only when zero
 * devices are discovered; we have no live count, so the informational tile always
 * shows). Documented here as an R1-appropriate adaptation rather than a 1:1 port.
 */
@Composable
fun DiscoveredDevicesCard(
    card: LovelaceCard.Unsupported,
    modifier: Modifier = Modifier,
) {
    val title = (card.raw["title"] as? JsonPrimitive)?.content?.takeUnless { it.isBlank() }
        ?: "Discovered devices"
    CardSurface(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = R1Icons.forMdi("mdi:cellphone-link")
                    ?: cardEntityIcon("sensor.discovered", null, null),
                contentDescription = null,
                tint = R1.AccentCool,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, style = R1.bodyEmph, color = R1.Ink, maxLines = 1)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Set up discovered devices in Home Assistant",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 2,
                )
            }
        }
    }
}
