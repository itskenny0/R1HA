package com.github.itskenny0.r1ha.feature.ambient

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ambient.AmbientLogic
import com.github.itskenny0.r1ha.core.ambient.AmbientSummary
import com.github.itskenny0.r1ha.core.prefs.AmbientSettings
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.clockPattern
import com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The dimmed glance panel shown while the device is idle: clock, date, weather,
 * a compact stats row (lights / people / power), and an alert line that only
 * appears when something needs attention. Full-bleed and themed (so at night it
 * picks up the night theme). The first touch wakes via [onWake]; it is consumed
 * (so it does not actuate a control underneath) only when
 * [AmbientSettings.consumeWakeEvent] is on.
 */
@Composable
fun IdleFace(
    summary: AmbientSummary,
    ambient: AmbientSettings,
    powerAmberW: Int,
    powerRedW: Int,
    onWake: () -> Unit,
) {
    // Minute tick for the clock / date. Recomputes at the top of each minute.
    val now by produceState(initialValue = LocalDateTime.now()) {
        while (true) {
            value = LocalDateTime.now()
            val ms = 60_000L - (System.currentTimeMillis() % 60_000L)
            kotlinx.coroutines.delay(ms.coerceAtLeast(1_000L))
        }
    }

    // Optional slow burn-in-insurance drift (a few dp, ~2 min loop). Cheap and
    // gated; the R1 panel is LCD so this is belt-and-braces.
    val driftX by produceState(initialValue = 0, key1 = ambient.pixelDriftEnabled) {
        if (!ambient.pixelDriftEnabled) return@produceState
        val steps = listOf(0, 2, 4, 2, 0, -2, -4, -2)
        var i = 0
        while (true) {
            value = steps[i % steps.size]
            i++
            kotlinx.coroutines.delay(15_000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    // Leaving the DOWN unconsumed lets the control underneath
                    // receive the same press, which is what "wake tap also
                    // acts" promises when the consume toggle is off.
                    if (ambient.consumeWakeEvent) down.consume()
                    onWake()
                }
            }
            .padding(16.dp)
            .offset(x = driftX.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (ambient.showClock) {
            val use24h = rememberUse24HourClock()
            Text(
                text = now.format(DateTimeFormatter.ofPattern(clockPattern(use24h), Locale.getDefault())),
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        if (ambient.showDate) {
            Text(
                text = now.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.8f),
            )
        }
        if (ambient.showWeather && summary.temperature != null) {
            Spacer(Modifier.height(12.dp))
            val unit = summary.temperatureUnit ?: ""
            val feels = if (ambient.showFeelsLike && summary.apparentTemperature != null) {
                "  feels ${summary.apparentTemperature.toInt()}$unit"
            } else {
                ""
            }
            Text(
                text = "${summary.condition ?: ""}  ${summary.temperature.toInt()}$unit$feels".trim(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }

        // Stats row.
        val stats = buildList {
            if (ambient.showLights) summary.lightsOn?.let { add("$it lights") }
            if (ambient.showPersons) summary.personsHome?.let { add("$it home") }
            if (ambient.showPower) summary.powerWatts?.let { add("${it.toInt()} W") }
        }
        if (stats.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            val powerColor = when (AmbientLogic.powerSeverity(summary.powerWatts, powerAmberW, powerRedW)) {
                AmbientLogic.PowerSeverity.RED -> R1.StatusRed
                AmbientLogic.PowerSeverity.AMBER -> R1.StatusAmber
                AmbientLogic.PowerSeverity.NORMAL -> MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                stats.forEach { chip ->
                    val isPower = chip.endsWith(" W")
                    Text(
                        text = chip,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        color = if (isPower) powerColor else MaterialTheme.colorScheme.onBackground.copy(alpha = 0.85f),
                    )
                }
            }
        }

        // Alert line: only when something needs attention.
        if (ambient.showAlerts) {
            val alert = when {
                summary.alertCount > 0 ->
                    "${summary.alertCount} notification" + if (summary.alertCount > 1) "s" else ""
                summary.activeTimerLabel != null -> "Timer: ${summary.activeTimerLabel}"
                else -> null
            }
            if (alert != null) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = alert,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.9f),
                )
            }
        }
    }
}
