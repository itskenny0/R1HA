package com.github.itskenny0.r1ha.feature.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
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
            // Distinct from "empty integration": surface the actual
            // error so the user knows it's a transport problem (auth,
            // DNS, server down) rather than a config gap.
            ui.error != null && ui.weathers.isEmpty() -> R1ErrorState(
                title = "COULDN'T LOAD WEATHER",
                message = ui.error,
                onRetry = { vm.refresh() },
            )
            ui.weathers.isEmpty() -> R1EmptyState(
                title = "NO WEATHER ENTITIES",
                body = "Add a weather integration in HA to see them here.",
            )
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                // Only the explicit refresh spinner here; the populated list
                // stays put during auto-refresh ticks (initialLoading drives
                // the full-screen spinner branch above instead).
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                if (ui.weathers.size == 1) {
                    // The common install has exactly one weather entity; a
                    // single compact list row left most of a phone screen
                    // empty. Promote it to a hero layout that uses the page.
                    val heroScroll = rememberScrollState()
                    com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
                        wheelInput = wheelInput,
                        scrollState = heroScroll,
                        settings = settings,
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(heroScroll)
                            .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
                    ) {
                        WeatherHero(ui.weathers[0])
                    }
                } else {
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
        }
        } // R1CenteredContent
    }
}

/**
 * Label → value pairs for the secondary readings, in a stable order. Shared
 * by the compact row (dot-joined line) and the single-entity hero (stat
 * grid) so the two presentations can never drift. Only reported readings
 * appear; integrations that omit an attribute produce no blank cell.
 */
internal fun weatherStatPairs(w: WeatherViewModel.Weather): List<Pair<String, String>> = buildList {
    if (w.humidity != null) add("HUMIDITY" to "${w.humidity}%")
    if (w.windSpeed != null) {
        val bearing = w.windBearingText ?: w.windBearingDeg?.let { degreesToCompass(it) }
        val windStr = "${formatNumber(w.windSpeed)} ${w.windUnit ?: ""}".trim()
        val gust = w.windGust?.let { " G${formatNumber(it)}" } ?: ""
        add("WIND" to (if (bearing != null) "$windStr $bearing" else windStr) + gust)
    }
    if (w.pressure != null) add("PRESSURE" to "${formatNumber(w.pressure)} ${w.pressureUnit ?: ""}".trim())
    if (w.visibility != null) add("VISIBILITY" to "${formatNumber(w.visibility)} ${w.visibilityUnit ?: ""}".trim())
    if (w.uvIndex != null) add("UV INDEX" to formatNumber(w.uvIndex))
    if (w.dewPoint != null) add("DEW POINT" to formatTemp(w.dewPoint, w.temperatureUnit))
    if (w.cloudCoverage != null) add("CLOUD" to "${w.cloudCoverage}%")
}

@Composable
private fun WeatherRow(w: WeatherViewModel.Weather) {
    // Which forecast cadence the strip shows; daily-first (see defaultForecastKind).
    var mode by androidx.compose.runtime.remember(w.entityId) {
        androidx.compose.runtime.mutableStateOf(
            defaultForecastKind(hasHourly = w.hourly.isNotEmpty(), hasDaily = w.daily.isNotEmpty()),
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
                text = conditionDisplayLabel(w.condition),
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
        // Secondary readings: only render when present. Shares the pair
        // builder with the hero grid so the two presentations agree.
        val parts = weatherStatPairs(w).map { (label, value) ->
            when (label) {
                "HUMIDITY" -> "$value RH"
                "WIND" -> value
                "PRESSURE" -> value
                "VISIBILITY" -> "VIS $value"
                "UV INDEX" -> "UV $value"
                "DEW POINT" -> "DEW $value"
                "CLOUD" -> "$value CLOUD"
                else -> "$label $value"
            }
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

/**
 * Full-page treatment for the single-weather-entity install: big condition +
 * temperature up top, the secondary readings as a legible two-column stat
 * grid, and the forecast strip with roomier tiles. Multi-entity installs
 * keep the compact [WeatherRow] cards instead.
 */
@Composable
private fun WeatherHero(w: WeatherViewModel.Weather) {
    var mode by androidx.compose.runtime.remember(w.entityId) {
        androidx.compose.runtime.mutableStateOf(
            defaultForecastKind(hasHourly = w.hourly.isNotEmpty(), hasDaily = w.daily.isNotEmpty()),
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = w.name,
            style = responsiveType(R1.bodyEmph),
            color = R1.InkSoft,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.size(R1.space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = R1Icons.conditionIcon(w.condition),
                contentDescription = conditionLabel(w.condition),
                tint = conditionAccent(w.condition),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.width(R1.space.l))
            Column {
                if (w.temperature != null) {
                    Text(
                        text = formatTemp(w.temperature, w.temperatureUnit),
                        style = responsiveType(R1.numeralXl),
                        color = R1.Ink,
                        maxLines = 1,
                    )
                }
                Text(
                    text = conditionDisplayLabel(w.condition),
                    style = responsiveType(R1.label),
                    color = conditionAccent(w.condition),
                    maxLines = 1,
                )
                if (w.apparentTemperature != null && w.apparentTemperature != w.temperature) {
                    Text(
                        text = "FEELS ${formatTemp(w.apparentTemperature, w.temperatureUnit)}",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                    )
                }
            }
        }
        // Secondary readings as a two-column grid: each value gets a labelled
        // slot instead of competing inside one dot-joined micro line.
        val pairs = weatherStatPairs(w)
        if (pairs.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.l))
            for (rowPair in pairs.chunked(2)) {
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xs)) {
                    for (stat in rowPair) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stat.first,
                                style = responsiveType(R1.labelMicro),
                                color = R1.InkMuted,
                            )
                            Text(
                                text = stat.second,
                                style = responsiveType(R1.numeralM),
                                color = R1.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    if (rowPair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        val entries = if (mode == ForecastKind.Hourly) w.hourly else w.daily
        if (w.hourly.isNotEmpty() || w.daily.isNotEmpty()) {
            Spacer(Modifier.size(R1.space.l))
            if (w.hasBothForecasts) {
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.xs)) {
                    R1Chip(
                        text = "DAILY",
                        variant = R1ChipVariant.Filter,
                        selected = mode == ForecastKind.Daily,
                        onClick = { mode = ForecastKind.Daily },
                        contentDescription = "Show daily forecast",
                    )
                    R1Chip(
                        text = "HOURLY",
                        variant = R1ChipVariant.Filter,
                        selected = mode == ForecastKind.Hourly,
                        onClick = { mode = ForecastKind.Hourly },
                        contentDescription = "Show hourly forecast",
                    )
                }
                Spacer(Modifier.size(R1.space.s))
            }
            val tier = com.github.itskenny0.r1ha.ui.components.LocalWindowTier.current.tier
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(R1.space.m),
            ) {
                for (entry in entries) {
                    ForecastTile(entry, mode, w.temperatureUnit, w.windUnit, tier, large = true)
                }
            }
        }
        Spacer(Modifier.size(R1.space.xl))
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
    /** Hero-strip tiles get the roomy treatment regardless of tier. */
    large: Boolean = false,
) {
    // Breathe on bigger tiers: a wider floor + roomier inset keep each tile
    // legible across a large panel, while the R1 keeps its tight 56dp tiles
    // so the whole strip still fits the narrow panel's scroll.
    val isWide = large || tier.isAtLeast(com.github.itskenny0.r1ha.ui.components.WindowTier.MEDIUM)
    val tileMinWidth = if (isWide) 76.dp else 56.dp
    val tileInset = if (isWide) R1.space.m else R1.space.s
    Column(
        modifier = Modifier
            .widthIn(min = tileMinWidth)
            .clip(R1.ShapeS)
            // Inside a card the tiles cut darker wells into SurfaceMuted; on
            // the hero (which sits on Bg directly) they need the inverse.
            .background(if (large) R1.SurfaceMuted else R1.Bg)
            .padding(horizontal = tileInset, vertical = tileInset),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatForecastLabel(
                entry.whenIso,
                kind,
                use24h = com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock(),
            ),
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

/** Human-readable condition label (lower-case, used for the spoken accessibility
 *  description). Empty / missing states read as a clear word rather than a blank or a
 *  bare slug. */
private fun conditionLabel(condition: String): String = when (condition.lowercase(java.util.Locale.US)) {
    "", "unknown" -> "unknown"
    "unavailable" -> "unavailable"
    else -> condition.replace('-', ' ')
}

/** Upper-case display form of [conditionLabel], pinned to US so a condition with an
 *  'i' ("rainy", "lightning", "windy") doesn't render with a dotted "İ" on Turkish /
 *  Azeri locales. Matches the US pin already used for this screen's temperatures. */
internal fun conditionDisplayLabel(condition: String): String =
    conditionLabel(condition).uppercase(java.util.Locale.US)

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color =
    when (condition.lowercase(java.util.Locale.US)) {
        "sunny", "clear" -> R1.AccentWarm
        "rainy", "pouring", "snowy", "snowy-rainy", "fog" -> R1.AccentCool
        "lightning", "lightning-rainy" -> R1.StatusAmber
        "exceptional" -> R1.StatusRed
        "windy", "windy-variant" -> R1.AccentNeutral
        "unavailable", "unknown", "" -> R1.InkMuted
        else -> R1.InkSoft
    }
