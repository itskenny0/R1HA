package com.github.itskenny0.r1ha.feature.weather

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.jupiter.api.Test
import java.time.ZoneId

/**
 * Unit tests for the pure forecast parsing + formatting helpers in
 * WeatherForecast.kt. No Android / Compose dependencies are touched, so
 * these run on the plain JVM test source set.
 */
class WeatherForecastTest {

    private val utc = ZoneId.of("UTC")

    private fun arr(json: String): JsonArray =
        Json.parseToJsonElement(json) as JsonArray

    @Test
    fun `parses full daily entry`() {
        val entries = parseForecastEntries(
            arr(
                """
                [{
                  "datetime": "2026-05-29T00:00:00+00:00",
                  "condition": "partlycloudy",
                  "temperature": 21.5,
                  "templow": 11.0,
                  "precipitation": 1.2,
                  "precipitation_probability": 40,
                  "wind_speed": 14.0,
                  "wind_bearing": 225
                }]
                """.trimIndent(),
            ),
        )
        assertThat(entries).hasSize(1)
        val e = entries.single()
        assertThat(e.condition).isEqualTo("partlycloudy")
        assertThat(e.temperature).isEqualTo(21.5)
        assertThat(e.tempLow).isEqualTo(11.0)
        assertThat(e.precipitation).isEqualTo(1.2)
        assertThat(e.precipitationProbability).isEqualTo(40)
        assertThat(e.windSpeed).isEqualTo(14.0)
        assertThat(e.windBearingDeg).isEqualTo(225.0)
        assertThat(e.windBearingText).isNull()
    }

    @Test
    fun `entries without datetime are dropped`() {
        val entries = parseForecastEntries(
            arr("""[{"condition":"sunny"},{"datetime":"2026-05-29T00:00:00+00:00"}]"""),
        )
        assertThat(entries).hasSize(1)
    }

    @Test
    fun `null array yields empty list`() {
        assertThat(parseForecastEntries(null)).isEmpty()
    }

    @Test
    fun `numeric fields tolerate string encoding and missing keys`() {
        val entries = parseForecastEntries(
            arr("""[{"datetime":"2026-05-29T00:00:00+00:00","temperature":"18"}]"""),
        )
        val e = entries.single()
        assertThat(e.temperature).isEqualTo(18.0)
        assertThat(e.tempLow).isNull()
        assertThat(e.precipitationProbability).isNull()
        assertThat(e.windSpeed).isNull()
    }

    @Test
    fun `textual wind bearing is preserved as text not degrees`() {
        val entries = parseForecastEntries(
            arr("""[{"datetime":"2026-05-29T00:00:00+00:00","wind_bearing":"NE"}]"""),
        )
        val e = entries.single()
        assertThat(e.windBearingText).isEqualTo("NE")
        assertThat(e.windBearingDeg).isNull()
    }

    @Test
    fun `precipitation probability rounds to nearest int`() {
        val entries = parseForecastEntries(
            arr("""[{"datetime":"2026-05-29T00:00:00+00:00","precipitation_probability":66.7}]"""),
        )
        assertThat(entries.single().precipitationProbability).isEqualTo(67)
    }

    @Test
    fun `hourly cadence is classified as hourly`() {
        val entries = parseForecastEntries(
            arr(
                """
                [
                  {"datetime":"2026-05-29T00:00:00+00:00"},
                  {"datetime":"2026-05-29T01:00:00+00:00"},
                  {"datetime":"2026-05-29T02:00:00+00:00"}
                ]
                """.trimIndent(),
            ),
        )
        assertThat(classifyForecastKind(entries)).isEqualTo(ForecastKind.Hourly)
    }

    @Test
    fun `daily cadence is classified as daily`() {
        val entries = parseForecastEntries(
            arr(
                """
                [
                  {"datetime":"2026-05-29T00:00:00+00:00"},
                  {"datetime":"2026-05-30T00:00:00+00:00"},
                  {"datetime":"2026-05-31T00:00:00+00:00"}
                ]
                """.trimIndent(),
            ),
        )
        assertThat(classifyForecastKind(entries)).isEqualTo(ForecastKind.Daily)
    }

    @Test
    fun `single entry defaults to daily`() {
        val entries = parseForecastEntries(
            arr("""[{"datetime":"2026-05-29T00:00:00+00:00"}]"""),
        )
        assertThat(classifyForecastKind(entries)).isEqualTo(ForecastKind.Daily)
    }

    @Test
    fun `empty list defaults to daily`() {
        assertThat(classifyForecastKind(emptyList())).isEqualTo(ForecastKind.Daily)
    }

    @Test
    fun `hour label formats in given zone`() {
        assertThat(formatHourLabel("2026-05-29T14:30:00+00:00", utc)).isEqualTo("14:30")
    }

    @Test
    fun `day label uses US weekday abbreviation`() {
        // 2026-05-29 is a Friday.
        assertThat(formatDayLabel("2026-05-29T00:00:00+00:00", utc)).isEqualTo("Fri 29")
    }

    @Test
    fun `labels fall back to raw prefix when unparseable`() {
        assertThat(formatHourLabel("not-a-date", utc)).isEqualTo("not-a")
        assertThat(formatDayLabel("not-a-date", utc)).isEqualTo("not-a")
    }

    @Test
    fun `formatForecastLabel dispatches on kind`() {
        val iso = "2026-05-29T14:00:00+00:00"
        assertThat(formatForecastLabel(iso, ForecastKind.Hourly, utc)).isEqualTo("14:00")
        assertThat(formatForecastLabel(iso, ForecastKind.Daily, utc)).isEqualTo("Fri 29")
    }

    @Test
    fun `degrees map to eight-point compass`() {
        assertThat(degreesToCompass(0.0)).isEqualTo("N")
        assertThat(degreesToCompass(45.0)).isEqualTo("NE")
        assertThat(degreesToCompass(90.0)).isEqualTo("E")
        assertThat(degreesToCompass(180.0)).isEqualTo("S")
        assertThat(degreesToCompass(270.0)).isEqualTo("W")
        // Wraps and rounds: 359 -> N, 359 sits within N's +/-22.5 sector.
        assertThat(degreesToCompass(359.0)).isEqualTo("N")
        assertThat(degreesToCompass(-90.0)).isEqualTo("W")
    }
}
