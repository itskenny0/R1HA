package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Renderer for HA's `clock` card. A self-contained local-time readout that
 * ticks once a second (when seconds are shown) or once a minute otherwise.
 * Carries no entities, so it never subscribes to HA state. The analog face
 * HA offers is rendered as the same digital readout here (the R1's small
 * canvas is better served by big legible digits than a tiny analog dial),
 * with the configured title preserved.
 *
 * clock_size (HA 2025.4): small / medium / large. Mapped to R1's text scales.
 * time_format (HA 2025.4): "12" -> h:mm a (12h AM/PM), else HH:mm (24h).
 */
@Composable
fun ClockCard(
    card: LovelaceCard.Clock,
    modifier: Modifier = Modifier,
) {
    val is12h = card.timeFormat == "12"
    val pattern = when {
        is12h && card.showSeconds -> "h:mm:ss a"
        is12h -> "h:mm a"
        card.showSeconds -> "HH:mm:ss"
        else -> "HH:mm"
    }
    val formatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern, Locale.US) }
    val tickMs = if (card.showSeconds) 1_000L else 15_000L
    val now by produceState(initialValue = LocalTime.now(), tickMs) {
        while (true) {
            value = LocalTime.now()
            delay(tickMs)
        }
    }
    val textStyle = when (card.clockSize?.lowercase()) {
        "small" -> R1.bodyEmph
        "large" -> R1.numeralXl
        else -> R1.numeralXl  // medium = default
    }

    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = now.format(formatter),
                style = textStyle,
                color = R1.Ink,
            )
        }
    }
}
