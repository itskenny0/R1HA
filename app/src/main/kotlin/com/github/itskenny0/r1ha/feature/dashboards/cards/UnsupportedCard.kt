package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LOVELACE_EDIT_JSON
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Fallback for card types R1HA doesn't natively render. Surfaces the
 * card's type string + an expandable "raw JSON" body so a power user
 * can see why a card isn't rendering and (if useful) re-author it as
 * a supported type.
 *
 * Explicitly visual rather than silent. a hidden card would be very
 * confusing, especially for users importing complex HA configs where
 * silent omissions look like a renderer bug.
 */
@Composable
fun UnsupportedCard(card: LovelaceCard.Unsupported, modifier: Modifier = Modifier) {
    var expanded by remember { mutableStateOf(false) }
    val prettyJson = remember(card.raw) {
        runCatching { LOVELACE_EDIT_JSON.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), card.raw) }
            .getOrElse { card.raw.toString() }
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.StatusAmber.copy(alpha = 0.6f), R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row {
            Text(
                text = "UNSUPPORTED CARD",
                style = R1.sectionHeader,
                color = R1.StatusAmber,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "HIDE" else "SHOW JSON",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.r1Pressable(onClick = { expanded = !expanded }),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "type: ${card.type}",
            style = R1.body,
            color = R1.Ink,
        )
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = prettyJson,
                    style = androidx.compose.ui.text.TextStyle(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
                    ),
                    color = R1.InkSoft,
                )
            }
        }
    }
}
