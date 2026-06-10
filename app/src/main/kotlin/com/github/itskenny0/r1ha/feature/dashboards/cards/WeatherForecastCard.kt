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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `weather-forecast` card. Compact: current
 * condition + temperature at the top, optional N-slot forecast strip
 * underneath. Forecast data comes from the entity's `forecast` attribute
 * (legacy shape) since R1HA doesn't currently subscribe to HA's newer
 * `weather/subscribe_forecast` WS API for dashboard rendering. the
 * dedicated Weather screen does, but a Lovelace card would need
 * extra plumbing the dashboards layer doesn't have today.
 */
@Composable
fun WeatherForecastCard(
    card: LovelaceCard.WeatherForecast,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val eid = safeEntityId(card.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(card.name, state, card.entityId)
    val condition = state?.rawState?.takeUnless { it.isBlank() } ?: "-"
    val tempC = state?.attributesJson?.get("temperature").asNumber()
    val unit = state?.attributesJson?.get("temperature_unit").asText() ?: "°"
    val accent = conditionAccent(condition)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.4f), R1.ShapeM)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(4.dp))
        if (card.showCurrent) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    // Locale-pinned: temperature readouts keep ASCII digits on
                    // non-Latin-digit device locales.
                    text = tempC?.let { "%.0f".format(java.util.Locale.US, it) } ?: "-",
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
        }
        if (card.showForecast) {
            val forecast = (state?.attributesJson?.get("forecast") as? JsonArray) ?: JsonArray(emptyList())
            if (forecast.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                ForecastStrip(forecast, slots = card.forecastSlots ?: 5)
            }
        }
    }
}

@Composable
private fun ForecastStrip(forecast: JsonArray, slots: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        forecast.take(slots.coerceIn(1, 8)).forEach { entry ->
            val obj = entry as? JsonObject ?: return@forEach
            val temp = obj["temperature"].asNumber()
            val low = obj["templow"].asNumber()
            val condition = obj["condition"].asText() ?: ""
            val day = obj["datetime"].asText()?.let { iso ->
                runCatching { java.time.OffsetDateTime.parse(iso) }.getOrNull()
                    ?.dayOfWeek?.name?.take(3)?.uppercase()
            } ?: "·"
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(day, style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = condition.take(8).replace('_', ' '),
                    style = R1.labelMicro,
                    color = conditionAccent(condition),
                    maxLines = 1,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = temp?.let { "%.0f°".format(java.util.Locale.US, it) } ?: "-",
                    style = R1.bodyEmph,
                    color = R1.Ink,
                )
                if (low != null) {
                    Text(text = "%.0f°".format(java.util.Locale.US, low), style = androidx.compose.ui.text.TextStyle.Default, color = R1.InkMuted)
                }
            }
        }
    }
}

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
    (this as? JsonPrimitive)?.content
