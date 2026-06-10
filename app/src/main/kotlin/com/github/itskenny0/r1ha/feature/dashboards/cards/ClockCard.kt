package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * Renderer for HA's `clock` card. A self-contained local-time readout that ticks
 * once a second (when seconds are shown or the analog second hand is moving) or
 * once a minute otherwise. Carries no entities, so it never subscribes to HA
 * state.
 *
 * clock_style (HA 2025.4): analog draws a Canvas dial; digital (default) shows
 * big legible digits.
 * clock_size (HA 2025.4): small / medium / large, mapped to R1's text scales.
 * time_format (HA 2025.4): "12" -> AM/PM, "24" -> 24h, "auto"/null -> device.
 * time_zone (HA 2025.4): an IANA zone id; an unknown id falls back to local.
 */
@Composable
fun ClockCard(
    card: LovelaceCard.Clock,
    modifier: Modifier = Modifier,
) {
    val systemIs24h = rememberUse24HourClock()
    val is24h = clockUses24h(card.timeFormat, systemIs24h)
    val zone = remember(card.timeZone) { clockZone(card.timeZone, ZoneId.systemDefault()) }
    // The analog face always ticks its second hand; the digital face only needs
    // a per-second tick when seconds are shown.
    val perSecond = card.showSeconds || card.analog
    val tickMs = if (perSecond) 1_000L else 15_000L
    val now by produceState(initialValue = ZonedDateTime.now(zone), tickMs, zone) {
        while (true) {
            value = ZonedDateTime.now(zone)
            delay(tickMs)
        }
    }

    CardSurface(
        modifier = modifier,
        title = card.title?.takeUnless { it.isBlank() },
        transparent = card.noBackground,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (card.analog) {
                AnalogClock(
                    hands = clockHands(now.hour, now.minute, now.second),
                    showSeconds = card.showSeconds,
                )
            } else {
                val pattern = when {
                    !is24h && card.showSeconds -> "h:mm:ss a"
                    !is24h -> "h:mm a"
                    card.showSeconds -> "HH:mm:ss"
                    else -> "HH:mm"
                }
                val formatter = remember(pattern) { DateTimeFormatter.ofPattern(pattern, Locale.US) }
                val textStyle = when (card.clockSize?.lowercase()) {
                    "small" -> R1.bodyEmph
                    else -> R1.numeralXl // medium / large = the big readout
                }
                Text(
                    text = now.format(formatter),
                    style = textStyle,
                    color = R1.Ink,
                )
            }
        }
    }
}

/** Analog dial drawn with Canvas: a bordered face, hour ticks, and the resolved
 *  hour / minute / (optional) second hands. */
@Composable
private fun AnalogClock(hands: ClockHands, showSeconds: Boolean) {
    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(120.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val radius = size.minDimension / 2f - 4.dp.toPx()
            val center = Offset(cx, cy)
            // Face border.
            drawCircle(
                color = R1.Hairline,
                radius = radius,
                center = center,
                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
            )
            // Hour ticks at each 30 degrees.
            for (i in 0 until 12) {
                val angle = Math.toRadians((i * 30f - 90f).toDouble())
                val outer = radius - 2.dp.toPx()
                val inner = radius - 8.dp.toPx()
                drawLine(
                    color = R1.InkMuted,
                    start = Offset(cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat()),
                    end = Offset(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat()),
                    strokeWidth = 1.5.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            fun hand(deg: Float, lengthFrac: Float, width: Float, color: androidx.compose.ui.graphics.Color) {
                // Degrees are measured clockwise from 12 (up); shift to canvas
                // angle space (0 = 3 o'clock, clockwise positive y-down).
                val a = Math.toRadians((deg - 90f).toDouble())
                val len = radius * lengthFrac
                drawLine(
                    color = color,
                    start = center,
                    end = Offset(cx + len * cos(a).toFloat(), cy + len * sin(a).toFloat()),
                    strokeWidth = width,
                    cap = StrokeCap.Round,
                )
            }
            hand(hands.hourDeg, 0.55f, 3.dp.toPx(), R1.Ink)
            hand(hands.minuteDeg, 0.8f, 2.dp.toPx(), R1.Ink)
            if (showSeconds) hand(hands.secondDeg, 0.85f, 1.dp.toPx(), R1.AccentWarm)
            drawCircle(color = R1.Ink, radius = 3.dp.toPx(), center = center)
        }
    }
}
