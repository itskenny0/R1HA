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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1CenteredContent
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor

/**
 * Weather surface: lists every `weather.*` entity HA reports with
 * its current condition icon + temperature + secondary readings
 * (humidity, wind, pressure). Read-only display; no controls.
 *
 * Condition icons are in-house line vectors resolved by
 * [R1Icons.conditionIcon] from the HA standard condition vocabulary
 * (clear-night, cloudy, fog, hail, lightning, partlycloudy, pouring,
 * rainy, snowy, snowy-rainy, sunny, windy, exceptional). Anything
 * unknown falls back to the neutral sun-behind-cloud glyph so a future
 * HA condition never breaks the layout.
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
        AutoRefresh(refreshSec * 1000L) { vm.refresh() }
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
        // Centre + width-cap the list on medium+ tiers (R1CenteredContent is a
        // fill-width passthrough on R1 / compact, a centred capped column above),
        // so the rows read as a single column on a wide panel instead of being
        // stretched edge to edge.
        val dimens = rememberResponsiveDimens()
        R1CenteredContent(modifier = Modifier.weight(1f)) {
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
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "empty integration": surface the actual
                // error so the user knows it's a transport problem (auth,
                // DNS, server down) rather than a config gap.
                Text(
                    text = "Weather load failed: ${ui.error}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.weathers.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No weather entities in HA. Add a weather integration to see them here.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                // Only the explicit refresh spinner here; the populated list
                // stays put during auto-refresh ticks (initialLoading drives
                // the full-screen spinner branch above instead).
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = dimens.screenGutter, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(dimens.sectionGap),
                ) {
                    items(items = ui.weathers, key = { it.entityId }) { w ->
                        WeatherRow(w)
                    }
                }
            }
        }
        } // R1CenteredContent
    }
}

@Composable
private fun WeatherRow(w: WeatherViewModel.Weather) {
    // Which forecast cadence the strip shows. Defaults to whichever the
    // entity reports; when both are present the toggle flips between them.
    var mode by androidx.compose.runtime.remember(w.entityId) {
        androidx.compose.runtime.mutableStateOf(
            if (w.hourly.isNotEmpty()) ForecastKind.Hourly else ForecastKind.Daily,
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // In-house line vector for the condition (replaces the unicode
            // glyph). Tinted by conditionAccent; the readable condition label
            // is exposed to a11y here since the row's other text doesn't repeat
            // it on this line.
            Icon(
                imageVector = R1Icons.conditionIcon(w.condition),
                contentDescription = conditionLabel(w.condition),
                tint = conditionAccent(w.condition),
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = w.name,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (w.temperature != null) {
                Text(
                    text = formatTemp(w.temperature, w.temperatureUnit),
                    style = responsiveType(R1.numeralM),
                    color = R1.Ink,
                )
            }
        }
        Spacer(Modifier.size(R1.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = conditionLabel(w.condition).uppercase(),
                style = responsiveType(R1.labelMicro),
                color = conditionAccent(w.condition),
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // "Feels like" sits next to the condition: it qualifies the
            // headline temperature, so it reads better here than buried in
            // the metrics line. Only shown when it differs from the actual.
            if (w.apparentTemperature != null && w.apparentTemperature != w.temperature) {
                Text(
                    text = "FEELS ${formatTemp(w.apparentTemperature, w.temperatureUnit)}",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    maxLines = 1,
                )
            }
        }
        // Secondary readings: only render when present. Avoids blank
        // columns when HA's integration omits an attribute (e.g. some
        // sensors only report temperature + condition).
        val parts = buildList {
            if (w.humidity != null) add("${w.humidity}% RH")
            if (w.windSpeed != null) {
                val bearing = w.windBearingText
                    ?: w.windBearingDeg?.let { degreesToCompass(it) }
                val windStr = "${formatNumber(w.windSpeed)} ${w.windUnit ?: ""}".trim()
                val gust = w.windGust?.let { " G${formatNumber(it)}" } ?: ""
                add((if (bearing != null) "$windStr $bearing" else windStr) + gust)
            }
            if (w.pressure != null) {
                add("${formatNumber(w.pressure)} ${w.pressureUnit ?: ""}".trim())
            }
            if (w.visibility != null) {
                add("VIS ${formatNumber(w.visibility)} ${w.visibilityUnit ?: ""}".trim())
            }
            if (w.uvIndex != null) add("UV ${formatNumber(w.uvIndex)}")
            if (w.dewPoint != null) {
                add("DEW ${formatTemp(w.dewPoint, w.temperatureUnit)}")
            }
            if (w.cloudCoverage != null) add("${w.cloudCoverage}% CLOUD")
        }
        if (parts.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = parts.joinToString(" · "),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Forecast strip. The VM fills hourly/daily from the modern
        // weather.get_forecasts service (HaRepository.getWeatherForecasts)
        // and falls back to the legacy `forecast` attribute, so this stays
        // populated on both old and new HA installs.
        val entries = if (mode == ForecastKind.Hourly) w.hourly else w.daily
        if (w.hourly.isNotEmpty() || w.daily.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.s))
            if (w.hasBothForecasts) {
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.xs)) {
                    R1Chip(
                        text = "HOURLY",
                        variant = R1ChipVariant.Filter,
                        selected = mode == ForecastKind.Hourly,
                        onClick = { mode = ForecastKind.Hourly },
                        contentDescription = "Show hourly forecast",
                    )
                    R1Chip(
                        text = "DAILY",
                        variant = R1ChipVariant.Filter,
                        selected = mode == ForecastKind.Daily,
                        onClick = { mode = ForecastKind.Daily },
                        contentDescription = "Show daily forecast",
                    )
                }
                Spacer(Modifier.size(R1.space.xs))
            }
            // Forecast strip always scrolls horizontally so it never clips on
            // the R1's narrow panel; on larger tiers the tiles + gaps breathe
            // (wider min-width, more padding, stepped-up type) instead of
            // staying R1-tight.
            val tier = com.github.itskenny0.r1ha.ui.components.LocalWindowTier.current.tier
            val stripGap = if (tier.isAtLeast(
                    com.github.itskenny0.r1ha.ui.components.WindowTier.MEDIUM,
                )
            ) R1.space.m else R1.space.s
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(stripGap),
            ) {
                for (entry in entries) {
                    ForecastTile(entry, mode, w.temperatureUnit, w.windUnit, tier)
                }
            }
        }
    }
}

@Composable
private fun ForecastTile(
    entry: ForecastEntry,
    kind: ForecastKind,
    tempUnit: String?,
    windUnit: String?,
    tier: com.github.itskenny0.r1ha.ui.components.WindowTier =
        com.github.itskenny0.r1ha.ui.components.WindowTier.R1,
) {
    // Breathe on bigger tiers: a wider floor + roomier inset keep each tile
    // legible across a large panel, while the R1 keeps its tight 56dp tiles
    // so the whole strip still fits the narrow panel's scroll.
    val isWide = tier.isAtLeast(com.github.itskenny0.r1ha.ui.components.WindowTier.MEDIUM)
    val tileMinWidth = if (isWide) 76.dp else 56.dp
    val tileInset = if (isWide) R1.space.m else R1.space.s
    Column(
        modifier = Modifier
            .widthIn(min = tileMinWidth)
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .padding(horizontal = tileInset, vertical = tileInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatForecastLabel(entry.whenIso, kind),
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            maxLines = 1,
        )
        Icon(
            imageVector = R1Icons.conditionIcon(entry.condition),
            contentDescription = conditionLabel(entry.condition),
            tint = conditionAccent(entry.condition),
            modifier = Modifier.size(20.dp),
        )
        val tempLine = buildString {
            if (entry.temperature != null) {
                append("${"%.0f".format(java.util.Locale.US, entry.temperature)}${tempUnit ?: "°"}")
            }
            if (entry.tempLow != null) {
                if (isNotEmpty()) append(" / ")
                append("${"%.0f".format(java.util.Locale.US, entry.tempLow)}${tempUnit ?: "°"}")
            }
        }
        if (tempLine.isNotBlank()) {
            Text(text = tempLine, style = responsiveType(R1.labelMicro), color = R1.Ink)
        }
        // Precipitation probability reads as the primary "will it rain"
        // signal; fall back to the amount when only that is reported.
        if (entry.precipitationProbability != null) {
            Text(
                text = "${entry.precipitationProbability}%",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentCool,
            )
        } else if (entry.precipitation != null && entry.precipitation > 0.0) {
            Text(
                text = "${"%.1f".format(java.util.Locale.US, entry.precipitation)}mm",
                style = responsiveType(R1.labelMicro),
                color = R1.AccentCool,
            )
        }
        if (entry.windSpeed != null) {
            val bearing = entry.windBearingText
                ?: entry.windBearingDeg?.let { degreesToCompass(it) }
            val windStr = "${formatNumber(entry.windSpeed)} ${windUnit ?: ""}".trim()
            Text(
                text = if (bearing != null) "$windStr $bearing" else windStr,
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
            )
        }
    }
}

private fun formatTemp(t: Double, unit: String?): String =
    "${formatNumber(t)}${unit ?: "°"}"

private fun formatNumber(d: Double): String =
    // One decimal for sub-100 values, integer for larger (pressure is usually 4 digits)
    if (kotlin.math.abs(d) < 100) "%.1f".format(java.util.Locale.US, d) else "%.0f".format(java.util.Locale.US, d)

/** Human-readable condition label. Empty / missing states read as a
 *  clear word rather than a blank or a bare slug. */
private fun conditionLabel(condition: String): String = when (condition.lowercase()) {
    "", "unknown" -> "unknown"
    "unavailable" -> "unavailable"
    else -> condition.replace('-', ' ')
}

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color =
    when (condition.lowercase()) {
        "sunny", "clear" -> R1.AccentWarm
        "rainy", "pouring", "snowy", "snowy-rainy", "fog" -> R1.AccentCool
        "lightning", "lightning-rainy" -> R1.StatusAmber
        "exceptional" -> R1.StatusRed
        "windy", "windy-variant" -> R1.AccentNeutral
        "unavailable", "unknown", "" -> R1.InkMuted
        else -> R1.InkSoft
    }
