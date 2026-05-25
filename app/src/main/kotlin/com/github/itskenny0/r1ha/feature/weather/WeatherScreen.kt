package com.github.itskenny0.r1ha.feature.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor

/**
 * Weather surface — lists every `weather.*` entity HA reports with
 * its current condition glyph + temperature + secondary readings
 * (humidity, wind, pressure). Read-only display; no controls.
 *
 * Glyphs are drawn from the HA standard condition vocabulary
 * (clear-night, cloudy, fog, hail, lightning, partlycloudy, pouring,
 * rainy, snowy, snowy-rainy, sunny, windy, exceptional). The map
 * coerces anything unknown to a neutral '·' so a future HA condition
 * never breaks the layout.
 */
@Composable
fun WeatherScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: WeatherViewModel = viewModel(factory = WeatherViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.weatherRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "WEATHER", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.weathers.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "empty integration" — surface the actual
                // error so the user knows it's a transport problem (auth,
                // DNS, server down) rather than a config gap.
                Text(
                    text = "Weather load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.weathers.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No weather entities in HA. Add a weather integration to see them here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = ui.weathers, key = { it.entityId }) { w ->
                        WeatherRow(w)
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun WeatherRow(w: WeatherViewModel.Weather) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = conditionGlyph(w.condition),
                style = R1.body,
                color = conditionAccent(w.condition),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = w.name,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (w.temperature != null) {
                Text(
                    text = formatTemp(w.temperature, w.temperatureUnit),
                    style = R1.body,
                    color = R1.Ink,
                )
            }
        }
        Spacer(Modifier.size(4.dp))
        Text(
            text = w.condition.replace('-', ' ').uppercase(),
            style = R1.labelMicro,
            color = conditionAccent(w.condition),
        )
        // Secondary readings — only render when present. Avoids blank
        // columns when HA's integration omits an attribute (e.g. some
        // sensors only report temperature + condition).
        val parts = buildList {
            if (w.humidity != null) add("${w.humidity}% RH")
            if (w.windSpeed != null) {
                val bearing = w.windBearingText
                    ?: w.windBearingDeg?.let { degreesToCompass(it) }
                val windStr = "${formatNumber(w.windSpeed)} ${w.windUnit ?: ""}".trim()
                add(if (bearing != null) "$windStr $bearing" else windStr)
            }
            if (w.pressure != null) {
                add("${formatNumber(w.pressure)} ${w.pressureUnit ?: ""}".trim())
            }
        }
        if (parts.isNotEmpty()) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = parts.joinToString(" · "),
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
        // Daily forecast strip — only when HA's integration still exposes
        // the legacy `forecast` attribute. 2024+ HA installs need
        // weather.get_forecasts (service-with-response) which we don't
        // dispatch yet; that's a follow-up.
        if (w.forecast.isNotEmpty()) {
            Spacer(Modifier.size(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (day in w.forecast) {
                    ForecastTile(day, w.temperatureUnit)
                }
            }
        }
    }
}

@Composable
private fun ForecastTile(day: WeatherViewModel.ForecastDay, tempUnit: String?) {
    Column(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatForecastDate(day.whenIso),
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Text(
            text = conditionGlyph(day.condition),
            style = R1.body,
            color = conditionAccent(day.condition),
        )
        val tempLine = buildString {
            if (day.tempHigh != null) append("${"%.0f".format(day.tempHigh)}${tempUnit ?: "°"}")
            if (day.tempLow != null) {
                if (isNotEmpty()) append(" / ")
                append("${"%.0f".format(day.tempLow)}${tempUnit ?: "°"}")
            }
        }
        if (tempLine.isNotBlank()) {
            Text(text = tempLine, style = R1.labelMicro, color = R1.Ink)
        }
        if (day.precipitation != null && day.precipitation > 0.0) {
            Text(
                text = "${"%.1f".format(day.precipitation)}mm",
                style = R1.labelMicro,
                color = R1.AccentCool,
            )
        }
    }
}

/** Render an ISO instant as a short day-of-week + day-of-month label.
 *  Falls back to the raw substring if parsing fails. */
private fun formatForecastDate(iso: String): String {
    return runCatching {
        val instant = java.time.Instant.parse(iso)
        val zdt = instant.atZone(java.time.ZoneId.systemDefault())
        val day = zdt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
        "${day} ${zdt.dayOfMonth}"
    }.getOrElse { iso.take(5) }
}

private fun formatTemp(t: Double, unit: String?): String =
    "${formatNumber(t)}${unit ?: "°"}"

private fun formatNumber(d: Double): String =
    // One decimal for sub-100 values, integer for larger (pressure is usually 4 digits)
    if (kotlin.math.abs(d) < 100) "%.1f".format(d) else "%.0f".format(d)

/** Map HA standard weather conditions to a single-glyph preview.
 *  Falls back to '·' for unknown / future conditions. */
private fun conditionGlyph(condition: String): String = when (condition.lowercase()) {
    "sunny", "clear" -> "☀"
    "clear-night" -> "☾"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁"
    "rainy" -> "☂"
    "pouring" -> "☔"
    "snowy", "snowy-rainy" -> "❄"
    "fog" -> "≋"
    "lightning", "lightning-rainy" -> "⚡"
    "windy", "windy-variant" -> "🌬"
    "hail" -> "•"
    else -> "·"
}

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color =
    when (condition.lowercase()) {
        "sunny", "clear" -> R1.AccentWarm
        "rainy", "pouring", "snowy", "snowy-rainy", "fog" -> R1.AccentCool
        "lightning", "lightning-rainy" -> R1.StatusAmber
        "windy", "windy-variant" -> R1.AccentNeutral
        "unavailable", "unknown" -> R1.InkMuted
        else -> R1.InkSoft
    }

/**
 * Convert HA's `wind_bearing` (degrees, 0 = north) into the 8-point compass
 * label most weather UIs use ("N", "NE", "E", …). Doubles-out to 16-point
 * granularity would be marginally more precise but reads as cluttered.
 */
private fun degreesToCompass(deg: Double): String {
    val normalised = ((deg % 360) + 360) % 360
    val sector = ((normalised + 22.5) / 45.0).toInt() % 8
    return arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[sector]
}
