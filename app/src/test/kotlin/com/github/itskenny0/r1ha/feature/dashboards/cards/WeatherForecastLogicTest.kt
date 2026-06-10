package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * Unit tests for [WeatherForecastLogic]: forecast-type resolution (config +
 * supported-feature fallback), per-slot labels (hourly time, daily weekday,
 * twice_daily day/night with day grouping), and temperature rounding.
 */
class WeatherForecastLogicTest {

    private val utc = ZoneId.of("UTC")

    private fun entry(
        iso: String,
        condition: String = "sunny",
        temp: Double? = 20.0,
        low: Double? = null,
        isDaytime: Boolean? = null,
    ) = ForecastEntry(
        whenIso = iso,
        condition = condition,
        temperature = temp,
        tempLow = low,
        precipitation = null,
        precipitationProbability = null,
        windSpeed = null,
        windBearingDeg = null,
        windBearingText = null,
        isDaytime = isDaytime,
    )

    @Test
    fun `config type honoured when supported`() {
        val type = WeatherForecastLogic.resolveForecastType(
            "hourly",
            WeatherForecastLogic.FEATURE_FORECAST_HOURLY or WeatherForecastLogic.FEATURE_FORECAST_DAILY,
        )
        assertThat(type).isEqualTo(WeatherForecastLogic.ForecastType.HOURLY)
    }

    @Test
    fun `unsupported config type falls back to daily`() {
        val type = WeatherForecastLogic.resolveForecastType(
            "hourly",
            WeatherForecastLogic.FEATURE_FORECAST_DAILY,
        )
        assertThat(type).isEqualTo(WeatherForecastLogic.ForecastType.DAILY)
    }

    @Test
    fun `default order prefers daily then twice_daily then hourly`() {
        assertThat(
            WeatherForecastLogic.resolveForecastType(null, WeatherForecastLogic.FEATURE_FORECAST_HOURLY),
        ).isEqualTo(WeatherForecastLogic.ForecastType.HOURLY)
        assertThat(
            WeatherForecastLogic.resolveForecastType(
                null,
                WeatherForecastLogic.FEATURE_FORECAST_TWICE_DAILY or WeatherForecastLogic.FEATURE_FORECAST_HOURLY,
            ),
        ).isEqualTo(WeatherForecastLogic.ForecastType.TWICE_DAILY)
    }

    @Test
    fun `null supported features defaults to daily`() {
        assertThat(WeatherForecastLogic.resolveForecastType(null, null))
            .isEqualTo(WeatherForecastLogic.ForecastType.DAILY)
    }

    @Test
    fun `null features honours config request`() {
        assertThat(WeatherForecastLogic.resolveForecastType("hourly", null))
            .isEqualTo(WeatherForecastLogic.ForecastType.HOURLY)
    }

    @Test
    fun `hourly slot label is the time`() {
        val label = WeatherForecastLogic.slotLabel(
            entry("2026-06-10T14:00:00+00:00"),
            WeatherForecastLogic.ForecastType.HOURLY,
            zone = utc,
            use24h = true,
        )
        assertThat(label).isEqualTo("14:00")
    }

    @Test
    fun `daily slot label is weekday plus day`() {
        val label = WeatherForecastLogic.slotLabel(
            entry("2026-06-10T00:00:00+00:00"),
            WeatherForecastLogic.ForecastType.DAILY,
            zone = utc,
        )
        // 2026-06-10 is a Wednesday.
        assertThat(label).isEqualTo("Wed 10")
    }

    @Test
    fun `twice_daily day and night labels`() {
        val day = WeatherForecastLogic.slotLabel(
            entry("2026-06-10T06:00:00+00:00", isDaytime = true),
            WeatherForecastLogic.ForecastType.TWICE_DAILY,
            zone = utc,
        )
        val night = WeatherForecastLogic.slotLabel(
            entry("2026-06-10T18:00:00+00:00", isDaytime = false),
            WeatherForecastLogic.ForecastType.TWICE_DAILY,
            zone = utc,
        )
        assertThat(day).isEqualTo("Day")
        assertThat(night).isEqualTo("Night")
    }

    @Test
    fun `day group header only on first slot of day`() {
        val first = entry("2026-06-10T06:00:00+00:00", isDaytime = true)
        val second = entry("2026-06-10T18:00:00+00:00", isDaytime = false)
        val nextDay = entry("2026-06-11T06:00:00+00:00", isDaytime = true)
        assertThat(WeatherForecastLogic.dayGroupHeader(first, null, utc)).isEqualTo("Wed 10")
        assertThat(WeatherForecastLogic.dayGroupHeader(second, first, utc)).isNull()
        assertThat(WeatherForecastLogic.dayGroupHeader(nextDay, second, utc)).isEqualTo("Thu 11")
    }

    @Test
    fun `round temperature drops fraction`() {
        assertThat(WeatherForecastLogic.formatTemperature(20.6, round = true)).isEqualTo("21")
        assertThat(WeatherForecastLogic.formatTemperature(20.4, round = true)).isEqualTo("20")
    }

    @Test
    fun `unrounded temperature keeps one digit`() {
        assertThat(WeatherForecastLogic.formatTemperature(20.6, round = false)).isEqualTo("20.6")
    }

    @Test
    fun `null temperature formats to null`() {
        assertThat(WeatherForecastLogic.formatTemperature(null, round = true)).isNull()
    }

    @Test
    fun `default secondary shows high and low`() {
        val first = entry("2026-06-10T00:00:00+00:00", temp = 24.0, low = 12.0)
        assertThat(WeatherForecastLogic.defaultSecondary(first, round = true)).isEqualTo("24° / 12°")
    }

    @Test
    fun `default secondary high only when no low`() {
        val first = entry("2026-06-10T00:00:00+00:00", temp = 24.0, low = null)
        assertThat(WeatherForecastLogic.defaultSecondary(first, round = true)).isEqualTo("24°")
    }

    @Test
    fun `default secondary null when no entry`() {
        assertThat(WeatherForecastLogic.defaultSecondary(null, round = true)).isNull()
    }
}
