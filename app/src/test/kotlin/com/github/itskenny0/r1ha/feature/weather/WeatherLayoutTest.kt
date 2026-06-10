package com.github.itskenny0.r1ha.feature.weather

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WeatherLayoutTest {

    @Test fun `daily forecast is the default whenever present`() {
        assertThat(defaultForecastKind(hasHourly = true, hasDaily = true))
            .isEqualTo(ForecastKind.Daily)
        assertThat(defaultForecastKind(hasHourly = false, hasDaily = true))
            .isEqualTo(ForecastKind.Daily)
    }

    @Test fun `hourly only falls back to hourly`() {
        assertThat(defaultForecastKind(hasHourly = true, hasDaily = false))
            .isEqualTo(ForecastKind.Hourly)
    }

    @Test fun `stat pairs include only reported readings with stable labels`() {
        val w = WeatherViewModel.Weather(
            entityId = "weather.home",
            name = "Home",
            condition = "rainy",
            temperature = 14.8,
            temperatureUnit = "°C",
            apparentTemperature = null,
            humidity = 90,
            windSpeed = 6.8,
            windUnit = "km/h",
            windBearingDeg = 0.0,
            windBearingText = null,
            windGust = null,
            pressure = 1019.0,
            pressureUnit = "hPa",
            visibility = null,
            visibilityUnit = null,
            uvIndex = 4.0,
            dewPoint = 13.4,
            cloudCoverage = 100,
            hourly = emptyList(),
            daily = emptyList(),
        )
        val pairs = weatherStatPairs(w)
        assertThat(pairs.map { it.first })
            .containsExactly("HUMIDITY", "WIND", "PRESSURE", "UV INDEX", "DEW POINT", "CLOUD")
            .inOrder()
        assertThat(pairs.first { it.first == "WIND" }.second).isEqualTo("6.8 km/h N")
        assertThat(pairs.first { it.first == "HUMIDITY" }.second).isEqualTo("90%")
        // Visibility was null: no blank cell.
        assertThat(pairs.map { it.first }).doesNotContain("VISIBILITY")
    }
}
