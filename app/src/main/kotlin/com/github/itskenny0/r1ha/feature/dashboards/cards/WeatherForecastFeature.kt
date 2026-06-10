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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import com.github.itskenny0.r1ha.feature.weather.formatDayLabel
import com.github.itskenny0.r1ha.feature.weather.formatHourLabel
import com.github.itskenny0.r1ha.feature.weather.parseForecastResponse

/** Which series a forecast bar strip draws. */
internal enum class ForecastSeries { TEMPERATURE, PRECIP_AMOUNT, PRECIP_PROBABILITY }

/** Loading / data / error+empty state for the forecast fetch. */
private sealed interface ForecastFetch {
    data object Loading : ForecastFetch
    data class Loaded(val entries: List<ForecastEntry>) : ForecastFetch
    data object Error : ForecastFetch
}

/**
 * A compact forecast bar strip for the weather temperature/precipitation tile
 * features. Resolves the forecast type from the entity's supported
 * WeatherEntityFeature bits (daily > twice_daily > hourly) with the configured
 * type honoured when supported, fetches via the get_forecasts path, windows the
 * entries to days_to_show / hours_to_show, then draws:
 *  - temperature daily: templow..temperature range bars in the HA temperature
 *    palette gradient (entries lacking templow are dropped),
 *  - temperature hourly: a line anchored at the current temperature,
 *  - precipitation: amount bars scaled against a reference floor (so light
 *    drizzle stays small) or probability against a fixed 0..100, zero as a dot.
 * Shows a slim loading / error / no-forecast placeholder rather than nothing.
 *
 * The decisions (type resolution, windowing, scaling, palette) live in
 * [WeatherForecastFeatureLogic]; this composable is a thin renderer over them.
 */
@Composable
internal fun WeatherForecastFeature(
    entityId: String,
    configuredForecastType: String?,
    supportedFeatures: Int,
    series: ForecastSeries,
    accent: Color,
    showLabels: Boolean,
    daysToShow: Int?,
    hoursToShow: Int?,
    currentTemperature: Double?,
    precipitationImperial: Boolean,
) {
    val repo = LocalHaRepository.current
    val forecastType = WeatherForecastFeatureLogic.resolveForecastType(configuredForecastType, supportedFeatures)
    var fetch by remember(entityId, forecastType, repo) { mutableStateOf<ForecastFetch>(ForecastFetch.Loading) }
    LaunchedEffect(entityId, forecastType, repo) {
        if (repo == null || forecastType == null) {
            fetch = ForecastFetch.Error
            return@LaunchedEffect
        }
        fetch = repo.getWeatherForecasts(entityId, forecastType).fold(
            onSuccess = { ForecastFetch.Loaded(parseForecastResponse(it)) },
            onFailure = { ForecastFetch.Error },
        )
    }

    when (val f = fetch) {
        is ForecastFetch.Loading -> ForecastPlaceholder("...")
        is ForecastFetch.Error -> ForecastPlaceholder("No forecast")
        is ForecastFetch.Loaded -> {
            val windowed = WeatherForecastFeatureLogic.windowEntries(
                entries = f.entries,
                forecastType = forecastType,
                daysToShow = daysToShow,
                hoursToShow = hoursToShow,
                nowEpochSec = System.currentTimeMillis() / 1000L,
            )
            if (windowed.isEmpty()) {
                ForecastPlaceholder("No forecast")
            } else {
                ForecastStrip(
                    entries = windowed,
                    forecastType = forecastType,
                    series = series,
                    accent = accent,
                    showLabels = showLabels,
                    currentTemperature = currentTemperature,
                    precipitationImperial = precipitationImperial,
                )
            }
        }
    }
}

/** A one-line muted placeholder occupying roughly the strip's height. */
@Composable
private fun ForecastPlaceholder(text: String) {
    Text(text = text, style = R1.labelMicro, color = R1.InkMuted, maxLines = 1)
}

@Composable
private fun ForecastStrip(
    entries: List<ForecastEntry>,
    forecastType: String?,
    series: ForecastSeries,
    accent: Color,
    showLabels: Boolean,
    currentTemperature: Double?,
    precipitationImperial: Boolean,
) {
    val hourly = WeatherForecastFeatureLogic.isHourly(forecastType)
    Column(modifier = Modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val n = entries.size
            if (n == 0) return@Canvas
            val slot = size.width / n
            val barW = slot * 0.6f
            when (series) {
                ForecastSeries.TEMPERATURE ->
                    if (hourly) {
                        drawTemperatureLine(entries, currentTemperature, accent, slot)
                    } else {
                        drawTemperatureRangeBars(entries, slot, barW, accent)
                    }
                ForecastSeries.PRECIP_AMOUNT, ForecastSeries.PRECIP_PROBABILITY -> {
                    val isProb = series == ForecastSeries.PRECIP_PROBABILITY
                    val observedMax = entries.mapNotNull {
                        if (isProb) it.precipitationProbability?.toDouble() else it.precipitation
                    }.maxOrNull() ?: 0.0
                    entries.forEachIndexed { i, e ->
                        val value = if (isProb) e.precipitationProbability?.toDouble() else e.precipitation
                        val frac = WeatherForecastFeatureLogic.precipBarFraction(
                            value = value,
                            isProbability = isProb,
                            forecastType = forecastType,
                            imperial = precipitationImperial,
                            observedMaxAmount = observedMax,
                        )
                        val left = i * slot + (slot - barW) / 2f
                        if (frac <= 0f) {
                            // Zero / dry: a small dot at the baseline, HA-style.
                            val r = (barW * 0.18f).coerceAtMost(3.dp.toPx())
                            drawCircle(
                                color = accent,
                                radius = r,
                                center = Offset(left + barW / 2f, size.height - r),
                            )
                        } else {
                            val barH = size.height * frac
                            drawRect(
                                color = accent,
                                topLeft = Offset(left, size.height - barH),
                                size = Size(barW, barH),
                            )
                        }
                    }
                }
            }
        }
        if (showLabels) {
            Spacer(Modifier.height(2.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                entries.forEach { e ->
                    Text(
                        text = if (hourly) formatHourLabel(e.whenIso) else formatDayLabel(e.whenIso),
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

/** Daily templow..temperature range bars in the temperature palette. Entries
 *  with no templow are skipped (their slot stays blank), matching HA. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTemperatureRangeBars(
    entries: List<ForecastEntry>,
    slot: Float,
    barW: Float,
    fallback: Color,
) {
    val highs = entries.mapNotNull { it.temperature }
    val lows = entries.mapNotNull { it.tempLow }
    val seriesMin = (lows + highs).minOrNull() ?: return
    val seriesMax = (lows + highs).maxOrNull() ?: return
    entries.forEachIndexed { i, e ->
        val high = e.temperature ?: return@forEachIndexed
        val low = e.tempLow ?: return@forEachIndexed
        val (lf, hf) = WeatherForecastFeatureLogic.rangeBarFractions(low, high, seriesMin, seriesMax)
        val yHigh = size.height * (1f - hf)
        val yLow = size.height * (1f - lf)
        val left = i * slot + (slot - barW) / 2f
        val mid = (low + high) / 2.0
        val color = Color(WeatherForecastFeatureLogic.temperaturePaletteArgb(mid))
        drawRect(
            color = if (color.alpha > 0f) color else fallback,
            topLeft = Offset(left, yHigh),
            size = Size(barW, (yLow - yHigh).coerceAtLeast(1.5.dp.toPx())),
        )
    }
}

/** Hourly temperature as a line anchored at the current temperature. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTemperatureLine(
    entries: List<ForecastEntry>,
    currentTemperature: Double?,
    accent: Color,
    slot: Float,
) {
    val temps = buildList {
        currentTemperature?.let { add(it) }
        entries.forEach { it.temperature?.let { t -> add(t) } }
    }
    if (temps.size < 2) return
    val tMin = temps.min()
    val tMax = temps.max()
    val span = (tMax - tMin).takeIf { it > 0.0 } ?: 1.0
    fun y(v: Double) = (size.height * (1f - ((v - tMin) / span).toFloat())).coerceIn(0f, size.height)
    val path = Path()
    var x = 0f
    var first = true
    if (currentTemperature != null) {
        path.moveTo(0f, y(currentTemperature))
        first = false
        x = slot
    }
    entries.forEach { e ->
        val t = e.temperature ?: return@forEach
        val px = x + slot / 2f
        if (first) {
            path.moveTo(px, y(t)); first = false
        } else {
            path.lineTo(px, y(t))
        }
        x += slot
    }
    drawPath(path = path, color = accent, style = Stroke(width = 1.5.dp.toPx()))
}
