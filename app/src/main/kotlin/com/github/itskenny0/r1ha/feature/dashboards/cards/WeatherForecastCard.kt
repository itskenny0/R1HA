package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import com.github.itskenny0.r1ha.feature.weather.parseForecastEntries
import com.github.itskenny0.r1ha.feature.weather.parseForecastResponse
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `weather-forecast` card. Mirrors
 * hui-weather-forecast-card.ts: the current condition + temperature sit at the
 * top with an optional secondary line, and an N-slot forecast strip underneath.
 *
 * Forecast data is fetched from the modern `weather.get_forecasts` service for
 * the configured (or default-by-supported-feature) forecast type, the same path
 * the native Weather screen uses. On a server that only exposes the legacy
 * `forecast` state attribute, the card falls back to it so older installs still
 * render. Slot labels (hour / weekday / day-night) and temperature rounding come
 * from the pure [WeatherForecastLogic] so they are unit-tested.
 */
@Composable
fun WeatherForecastCard(
    card: LovelaceCard.WeatherForecast,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] } ?: stateMap.byRaw(card.entityId)
    val name = resolveName(card.name, state, card.entityId)
    val rawState = state?.rawState
    val unavailable = rawState == null || rawState == "unavailable" || rawState == "unknown"
    val condition = rawState?.takeUnless { it.isBlank() } ?: "-"
    val tempC = state?.attributesJson?.get("temperature").asNumber()
    val unit = state?.attributesJson?.get("temperature_unit").asText() ?: "°"
    val accent = conditionAccent(condition)

    val supportedFeatures = state?.attributesJson?.get("supported_features").asNumber()?.toInt()
    val forecastType = remember(card.forecastType, supportedFeatures) {
        WeatherForecastLogic.resolveForecastType(card.forecastType, supportedFeatures)
    }

    // Fetch forecasts from the modern service; fall back to the legacy attribute.
    val repo = LocalHaRepository.current
    var entries by remember(card.entityId, forecastType, repo) { mutableStateOf<List<ForecastEntry>>(emptyList()) }
    LaunchedEffect(card.entityId, forecastType, repo, card.showForecast) {
        if (!card.showForecast) return@LaunchedEffect
        val modern = repo?.getWeatherForecasts(card.entityId, forecastType.wire)
            ?.map { parseForecastResponse(it) }
            ?.getOrNull()
            ?.takeIf { it.isNotEmpty() }
        entries = modern ?: legacyForecast(state?.attributesJson?.get("forecast") as? JsonArray)
    }

    val actions = CardActions(
        tap = card.tapAction ?: LovelaceAction.Builtin("more-info", card.entityId),
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (unavailable) {
            Spacer(Modifier.height(4.dp))
            Text(text = "Unavailable", style = R1.labelMicro, color = R1.InkMuted)
            return@Column
        }
        Spacer(Modifier.height(4.dp))
        if (card.showCurrent) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = WeatherForecastLogic.formatTemperature(tempC, round = true) ?: "-",
                    style = R1.numeralXl.copy(fontSize = androidx.compose.ui.unit.TextUnit.Unspecified),
                    color = R1.Ink,
                )
                Spacer(Modifier.width(2.dp))
                Text(unit, style = R1.numeralM, color = R1.InkSoft)
                Spacer(Modifier.weight(1f))
                Text(
                    text = condition.replace('_', ' ').replaceFirstChar { it.uppercase() },
                    style = R1.labelMicro,
                    color = accent,
                )
            }
            SecondaryLine(card, state?.attributesJson, entries.firstOrNull())
        }
        if (card.showForecast && entries.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            ForecastStrip(
                entries = entries,
                type = forecastType,
                slots = card.forecastSlots ?: 5,
                round = card.roundTemperature,
            )
        }
    }
}

/** The secondary info line under the current temperature: a configured
 *  `secondary_info_attribute` value, or HA's default extrema line. */
@Composable
private fun SecondaryLine(
    card: LovelaceCard.WeatherForecast,
    attrs: kotlinx.serialization.json.JsonObject?,
    first: ForecastEntry?,
) {
    val text = card.secondaryInfoAttribute?.let { attr ->
        val value = attrs?.get(attr).asText()
        val unitAttr = attrs?.get("${attr}_unit").asText()
        value?.let { if (unitAttr != null) "$it $unitAttr" else it }
    } ?: WeatherForecastLogic.defaultSecondary(first, card.roundTemperature)
    if (text != null) {
        Spacer(Modifier.height(2.dp))
        Text(text = text, style = R1.labelMicro, color = R1.InkSoft)
    }
}

@Composable
private fun ForecastStrip(
    entries: List<ForecastEntry>,
    type: WeatherForecastLogic.ForecastType,
    slots: Int,
    round: Boolean,
) {
    val shown = entries.take(slots.coerceIn(1, 8))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        shown.forEachIndexed { i, entry ->
            val prev = if (i > 0) shown[i - 1] else null
            val dayHeader = if (type == WeatherForecastLogic.ForecastType.TWICE_DAILY) {
                WeatherForecastLogic.dayGroupHeader(entry, prev)
            } else {
                null
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // Day-group header slot: only the first half of each day prints a
                // weekday, so day/night pairs sit under one heading.
                if (type == WeatherForecastLogic.ForecastType.TWICE_DAILY) {
                    Text(
                        text = dayHeader ?: " ",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                    )
                }
                Text(
                    text = WeatherForecastLogic.slotLabel(entry, type),
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = entry.condition.take(8).replace('_', ' '),
                    style = R1.labelMicro,
                    color = conditionAccent(entry.condition),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = WeatherForecastLogic.formatTemperature(entry.temperature, round)?.let { "$it°" } ?: "-",
                    style = R1.bodyEmph,
                    color = R1.Ink,
                )
                WeatherForecastLogic.formatTemperature(entry.tempLow, round)?.let { low ->
                    Text(text = "$low°", style = R1.labelMicro, color = R1.InkMuted)
                }
            }
        }
    }
}

/** Parse the legacy `forecast` state attribute into entries (old servers). */
private fun legacyForecast(arr: JsonArray?): List<ForecastEntry> = parseForecastEntries(arr)

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color = when (condition.lowercase()) {
    "sunny", "clear-night" -> R1.AccentWarm
    "rainy", "pouring", "hail", "snowy", "snowy-rainy" -> R1.AccentCool
    "cloudy", "partlycloudy", "fog" -> R1.InkSoft
    "lightning", "lightning-rainy" -> R1.StatusAmber
    "windy", "windy-variant" -> R1.AccentGreen
    else -> R1.InkSoft
}

private fun JsonElement?.asNumber(): Double? =
    (this as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun JsonElement?.asText(): String? =
    (this as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
