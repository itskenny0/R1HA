package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeatherForecastFeatureLogicTest {

    private fun entry(iso: String, temp: Double? = null, low: Double? = null, precip: Double? = null, prob: Int? = null) =
        ForecastEntry(
            whenIso = iso,
            condition = "",
            temperature = temp,
            tempLow = low,
            precipitation = precip,
            precipitationProbability = prob,
            windSpeed = null,
            windBearingDeg = null,
            windBearingText = null,
        )

    // ── forecast type resolution ────────────────────────────────────────────

    @Test fun `default type prefers daily over twice_daily over hourly`() {
        assertEquals("daily", WeatherForecastFeatureLogic.defaultForecastType(1 or 2 or 4))
        assertEquals("twice_daily", WeatherForecastFeatureLogic.defaultForecastType(2 or 4))
        assertEquals("hourly", WeatherForecastFeatureLogic.defaultForecastType(2))
        assertNull(WeatherForecastFeatureLogic.defaultForecastType(0))
    }

    @Test fun `configured type honoured when supported`() {
        // entity supports daily + hourly; hourly is configured and supported.
        assertEquals("hourly", WeatherForecastFeatureLogic.resolveForecastType("hourly", 1 or 2))
    }

    @Test fun `configured type falls back when unsupported`() {
        // entity supports only hourly; daily configured -> fall back to hourly.
        assertEquals("hourly", WeatherForecastFeatureLogic.resolveForecastType("daily", 2))
    }

    @Test fun `zero supported features trusts configured type or daily`() {
        assertEquals("hourly", WeatherForecastFeatureLogic.resolveForecastType("hourly", 0))
        assertEquals("daily", WeatherForecastFeatureLogic.resolveForecastType(null, 0))
    }

    // ── windowing ───────────────────────────────────────────────────────────

    @Test fun `daily window takes days_to_show entries`() {
        val entries = (0 until 10).map { entry("2026-01-0${it % 9 + 1}T00:00:00+00:00", temp = it.toDouble()) }
        val out = WeatherForecastFeatureLogic.windowEntries(entries, "daily", daysToShow = 3, hoursToShow = null, nowEpochSec = 0L)
        assertEquals(3, out.size)
    }

    @Test fun `twice_daily window takes 2x days`() {
        val entries = (0 until 20).map { entry("2026-01-01T00:00:00+00:00") }
        val out = WeatherForecastFeatureLogic.windowEntries(entries, "twice_daily", daysToShow = 2, hoursToShow = null, nowEpochSec = 0L)
        assertEquals(4, out.size)
    }

    @Test fun `hourly window filters to the next hours_to_show window`() {
        // now = 2026-01-01T00:00:00Z (epoch 1767225600); entries at +0,+1,...+5h.
        val now = 1767225600L
        val entries = (0 until 6).map { h ->
            val sec = now + h * 3600L
            val iso = java.time.Instant.ofEpochSecond(sec).toString()
            entry(iso, temp = h.toDouble())
        }
        val out = WeatherForecastFeatureLogic.windowEntries(entries, "hourly", daysToShow = null, hoursToShow = 3, nowEpochSec = now)
        // 3-hour window keeps the first 3 (0,+1,+2h).
        assertEquals(3, out.size)
    }

    // ── precipitation scaling ───────────────────────────────────────────────

    @Test fun `reference floor keeps light drizzle small`() {
        // daily floor 10mm; a 1mm drizzle -> 0.1 fraction even though it is the max.
        val frac = WeatherForecastFeatureLogic.precipBarFraction(
            value = 1.0, isProbability = false, forecastType = "daily", imperial = false, observedMaxAmount = 1.0,
        )
        assertEquals(0.1f, frac, 0.001f)
    }

    @Test fun `observed max above the floor drives the scale`() {
        // a 20mm storm exceeds the 10mm floor; 20mm -> full, 10mm -> half.
        assertEquals(1.0f, WeatherForecastFeatureLogic.precipBarFraction(20.0, false, "daily", false, 20.0), 0.001f)
        assertEquals(0.5f, WeatherForecastFeatureLogic.precipBarFraction(10.0, false, "daily", false, 20.0), 0.001f)
    }

    @Test fun `probability is fixed 0 to 100`() {
        assertEquals(0.5f, WeatherForecastFeatureLogic.precipBarFraction(50.0, true, "daily", false, 80.0), 0.001f)
        assertEquals(1.0f, WeatherForecastFeatureLogic.precipBarFraction(100.0, true, "daily", false, 80.0), 0.001f)
    }

    @Test fun `zero and null render as zero fraction (dot)`() {
        assertEquals(0f, WeatherForecastFeatureLogic.precipBarFraction(0.0, false, "daily", false, 10.0), 0f)
        assertEquals(0f, WeatherForecastFeatureLogic.precipBarFraction(null, false, "daily", false, 10.0), 0f)
    }

    @Test fun `hourly floor is smaller than daily`() {
        assertEquals(2.5, WeatherForecastFeatureLogic.referenceMaxAmount("hourly", false), 0.0)
        assertEquals(10.0, WeatherForecastFeatureLogic.referenceMaxAmount("daily", false), 0.0)
        assertEquals(0.1, WeatherForecastFeatureLogic.referenceMaxAmount("hourly", true), 0.0)
    }

    // ── temperature palette ─────────────────────────────────────────────────

    @Test fun `temperature palette clamps below min and above max`() {
        // -6C is the first stop (#249df2), 42C the last (#c82c1d). Out-of-range clamps.
        val cold = WeatherForecastFeatureLogic.temperaturePaletteArgb(-50.0)
        val hot = WeatherForecastFeatureLogic.temperaturePaletteArgb(99.0)
        assertEquals(0xFF249df2.toInt(), cold)
        assertEquals(0xFFc82c1d.toInt(), hot)
    }

    @Test fun `temperature palette is opaque`() {
        val argb = WeatherForecastFeatureLogic.temperaturePaletteArgb(15.0)
        assertEquals(0xFF, (argb ushr 24) and 0xFF)
    }

    @Test fun `range bar fractions span the series`() {
        val (lo, hi) = WeatherForecastFeatureLogic.rangeBarFractions(low = 5.0, high = 15.0, seriesMin = 0.0, seriesMax = 20.0)
        assertEquals(0.25f, lo, 0.001f)
        assertEquals(0.75f, hi, 0.001f)
    }

    @Test fun `isHourly only for hourly`() {
        assertTrue(WeatherForecastFeatureLogic.isHourly("hourly"))
        assertTrue(!WeatherForecastFeatureLogic.isHourly("daily"))
        assertTrue(!WeatherForecastFeatureLogic.isHourly(null))
    }
}
