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
 * Forecast handling reads the legacy `forecast` attribute and splits it
 * into hourly + daily buckets (an integration usually exposes one or the
 * other; some expose both across a refresh). Parsing + classification +
 * label formatting live in WeatherForecast.kt as pure helpers so they're
 * unit tested directly. Newer HA installs that dropped the legacy
 * attribute in favour of the `weather.get_forecasts` service-with-
 * response need a repository method that forwards `?return_response`;
 * see the agent report.
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
        /** Hourly forecast entries parsed from the legacy `forecast`
         *  attribute when the integration reports it at hourly cadence. */
        val hourly: List<ForecastEntry>,
        /** Daily forecast entries parsed from the legacy `forecast`
         *  attribute when the integration reports it at daily cadence. */
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
                        val forecastArr = attrs["forecast"] as? kotlinx.serialization.json.JsonArray
                        val entries = parseForecastEntries(forecastArr)
                        // The legacy attribute carries a single cadence; bucket
                        // it so the UI can offer a toggle if a future refresh
                        // (or merged entity) ever surfaces both.
                        val hourly: List<ForecastEntry>
                        val daily: List<ForecastEntry>
                        when (classifyForecastKind(entries)) {
                            ForecastKind.Hourly -> {
                                hourly = entries.take(MAX_HOURLY)
                                daily = emptyList()
                            }
                            ForecastKind.Daily -> {
                                hourly = emptyList()
                                daily = entries.take(MAX_DAILY)
                            }
                        }
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

    companion object {
        /** Cap rendered rows so a long forecast doesn't blow out the strip. */
        private const val MAX_HOURLY = 24
        private const val MAX_DAILY = 7

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { WeatherViewModel(haRepository) }
        }
    }
}
