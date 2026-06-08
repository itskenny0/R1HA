package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import com.github.itskenny0.r1ha.feature.weather.parseForecastResponse

/** Which series a forecast bar strip draws. */
internal enum class ForecastSeries { TEMPERATURE, PRECIP_AMOUNT, PRECIP_PROBABILITY }

/**
 * A compact forecast bar strip for the weather temperature/precipitation tile
 * features. Fetches forecasts off [LocalHaRepository] (the same hook SensorCard
 * uses) via the existing get_forecasts path, then draws one bar per slot.
 * Renders nothing until data resolves, and nothing at all when the repository
 * is unset or returns no usable forecast.
 */
@Composable
internal fun WeatherForecastFeature(
    entityId: String,
    forecastType: String,
    series: ForecastSeries,
    accent: Color,
    showLabels: Boolean,
) {
    val repo = LocalHaRepository.current
    var entries by remember(entityId, forecastType, repo) { mutableStateOf<List<ForecastEntry>>(emptyList()) }
    LaunchedEffect(entityId, forecastType, repo) {
        if (repo == null) return@LaunchedEffect
        val result = repo.getWeatherForecasts(entityId, forecastType)
        entries = result.map { parseForecastResponse(it) }.getOrElse { emptyList() }.take(8)
    }
    if (entries.isEmpty()) return
    val values = entries.map { e ->
        when (series) {
            ForecastSeries.TEMPERATURE -> e.temperature
            ForecastSeries.PRECIP_AMOUNT -> e.precipitation
            ForecastSeries.PRECIP_PROBABILITY -> e.precipitationProbability?.toDouble()
        }
    }
    val maxV = values.filterNotNull().maxOrNull() ?: return
    val minV = values.filterNotNull().minOrNull() ?: 0.0
    val span = (maxV - minV).takeIf { it > 0.0 } ?: 1.0
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val n = values.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barW = slot * 0.6f
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val norm = ((v - minV) / span).toFloat().coerceIn(0.05f, 1f)
                val barH = size.height * norm
                val left = i * slot + (slot - barW) / 2f
                drawRect(
                    color = accent,
                    topLeft = Offset(left, size.height - barH),
                    size = androidx.compose.ui.geometry.Size(barW, barH),
                )
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                entries.forEach { e ->
                    Text(
                        text = forecastSlotLabel(e, series),
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** A short per-slot label: the value for the series. */
private fun forecastSlotLabel(e: ForecastEntry, series: ForecastSeries): String = when (series) {
    ForecastSeries.TEMPERATURE -> e.temperature?.let { "${Math.round(it)}" } ?: "-"
    ForecastSeries.PRECIP_AMOUNT -> e.precipitation?.let { String.format(java.util.Locale.US, "%.1f", it) } ?: "-"
    ForecastSeries.PRECIP_PROBABILITY -> e.precipitationProbability?.let { "$it%" } ?: "-"
}
