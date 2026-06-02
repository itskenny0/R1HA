package com.github.itskenny0.r1ha.feature.weather

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the Weather surface. Pulls `weather.*` entities via
 * [HaRepository.listRawEntitiesByDomain] and decodes the attributes
 * HA's weather domain reports — condition (raw state, e.g.
 * "partlycloudy"), temperature, humidity, wind speed, pressure.
 *
 * Forecast handling prefers the modern `weather.get_forecasts`
 * response-only service (requested once for `hourly` and once for
 * `daily` per entity via [HaRepository.getWeatherForecasts]), which is
 * the only path newer HA integrations expose. When the service errors or
 * returns nothing (older integrations, providers that only do one
 * cadence) it falls back to the legacy `forecast` attribute and buckets
 * that by its detected cadence. Parsing + classification + label
 * formatting live in WeatherForecast.kt as pure helpers so they're unit
 * tested directly.
 */
class WeatherViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Weather(
        val entityId: String,
        val name: String,
        val condition: String,
        val temperature: Double?,
        val temperatureUnit: String?,
        val humidity: Int?,
        val windSpeed: Double?,
        val windUnit: String?,
        /** Wind bearing in degrees (0 = N, 90 = E, …). HA exposes this as
         *  `wind_bearing` on most integrations; some pass a string compass
         *  abbreviation directly (e.g. "NE"). Null when neither is set. */
        val windBearingDeg: Double?,
        val windBearingText: String?,
        val pressure: Double?,
        val pressureUnit: String?,
        /** "Feels like" temperature (`apparent_temperature`); null when the
         *  integration doesn't report it. Shares the temperature unit. */
        val apparentTemperature: Double?,
        /** Dew point (`dew_point`) in the temperature unit; null when absent. */
        val dewPoint: Double?,
        /** Horizontal visibility (`visibility`) in [visibilityUnit]; null absent. */
        val visibility: Double?,
        val visibilityUnit: String?,
        /** UV index (`uv_index`), a unitless 0..11+ scale; null when absent. */
        val uvIndex: Double?,
        /** Cloud coverage as a percentage 0..100 (`cloud_coverage`); null absent. */
        val cloudCoverage: Int?,
        /** Wind gust speed (`wind_gust_speed`) in [windUnit]; null when absent. */
        val windGust: Double?,
        /** Hourly forecast entries from `weather.get_forecasts` (modern HA),
         *  or the legacy `forecast` attribute when it reads as hourly. */
        val hourly: List<ForecastEntry>,
        /** Daily forecast entries from `weather.get_forecasts` (modern HA),
         *  or the legacy `forecast` attribute when it reads as daily. */
        val daily: List<ForecastEntry>,
    ) {
        /** True when both cadences are present, so the UI shows a toggle. */
        val hasBothForecasts: Boolean get() = hourly.isNotEmpty() && daily.isNotEmpty()
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val weathers: List<Weather> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("weather").fold(
                onSuccess = { rows ->
                    val list = rows.map { row ->
                        val attrs = row.attributes
                        val (hourly, daily) = loadForecasts(row.entityId, attrs)
                        Weather(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            condition = row.state,
                            temperature = (attrs["temperature"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            temperatureUnit = (attrs["temperature_unit"] as? JsonPrimitive)?.content,
                            humidity = (attrs["humidity"] as? JsonPrimitive)?.content
                                ?.toDoubleOrNull()?.toInt(),
                            windSpeed = (attrs["wind_speed"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            windUnit = (attrs["wind_speed_unit"] as? JsonPrimitive)?.content,
                            windBearingDeg = (attrs["wind_bearing"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            windBearingText = (attrs["wind_bearing"] as? JsonPrimitive)?.content
                                ?.takeIf { it.toDoubleOrNull() == null },
                            pressure = (attrs["pressure"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            pressureUnit = (attrs["pressure_unit"] as? JsonPrimitive)?.content,
                            apparentTemperature = (attrs["apparent_temperature"] as? JsonPrimitive)
                                ?.content?.toDoubleOrNull(),
                            dewPoint = (attrs["dew_point"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            visibility = (attrs["visibility"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            visibilityUnit = (attrs["visibility_unit"] as? JsonPrimitive)?.content,
                            uvIndex = (attrs["uv_index"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            cloudCoverage = (attrs["cloud_coverage"] as? JsonPrimitive)?.content
                                ?.toDoubleOrNull()?.let { Math.round(it).toInt() },
                            windGust = (attrs["wind_gust_speed"] as? JsonPrimitive)?.content?.toDoubleOrNull(),
                            hourly = hourly,
                            daily = daily,
                        )
                    }.sortedBy { it.name.lowercase() }
                    R1Log.i("Weather", "loaded ${list.size}")
                    _ui.value = _ui.value.copy(loading = false, weathers = list, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Weather", "list failed: ${t.message}")
                    Toaster.error("Weather load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Resolve a weather entity's hourly + daily forecast buckets. Tries the
     * modern `weather.get_forecasts` service first (one call per cadence),
     * then falls back to the legacy `forecast` attribute for any bucket the
     * service couldn't fill. The fallback classifies the single legacy list by
     * its detected cadence so it lands in the matching bucket.
     */
    private suspend fun loadForecasts(
        entityId: String,
        attrs: kotlinx.serialization.json.JsonObject,
    ): Pair<List<ForecastEntry>, List<ForecastEntry>> {
        val hourlyEntries = haRepository.getWeatherForecasts(entityId, "hourly")
            .map { parseForecastResponse(it) }
            .getOrDefault(emptyList())
        val dailyEntries = haRepository.getWeatherForecasts(entityId, "daily")
            .map { parseForecastResponse(it) }
            .getOrDefault(emptyList())

        var hourly = hourlyEntries.take(MAX_HOURLY)
        var daily = dailyEntries.take(MAX_DAILY)

        // Legacy fallback: when get_forecasts returned nothing for either
        // cadence, classify the single legacy attribute list and slot it into
        // the matching empty bucket.
        if (hourly.isEmpty() || daily.isEmpty()) {
            val legacyArr = attrs["forecast"] as? kotlinx.serialization.json.JsonArray
            val legacy = parseForecastEntries(legacyArr)
            if (legacy.isNotEmpty()) {
                when (classifyForecastKind(legacy)) {
                    ForecastKind.Hourly -> if (hourly.isEmpty()) hourly = legacy.take(MAX_HOURLY)
                    ForecastKind.Daily -> if (daily.isEmpty()) daily = legacy.take(MAX_DAILY)
                }
            }
        }
        return hourly to daily
    }

    companion object {
        /** Cap rendered rows so a long forecast doesn't blow out the strip. */
        private const val MAX_HOURLY = 24
        private const val MAX_DAILY = 7

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { WeatherViewModel(haRepository) }
        }
    }
}
