package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import java.time.Duration
import kotlin.math.floor

/**
 * Pure, unit-testable decisions for the weather temperature / precipitation
 * forecast tile features. Mirrors HA's `card-features/common/forecast.ts`,
 * `hui-precipitation-forecast-card-feature.ts`, and
 * `card-features/common/temperature-palette.ts`. Kept free of Android / Compose
 * so the "what to draw" maths can be pinned with JVM tests; the Compose layer
 * (WeatherForecastFeature) stays a thin renderer over these results.
 */
object WeatherForecastFeatureLogic {

    /** HA `WeatherEntityFeature`. */
    const val FORECAST_DAILY = 1
    const val FORECAST_HOURLY = 2
    const val FORECAST_TWICE_DAILY = 4

    const val DEFAULT_DAYS_TO_SHOW = 7
    const val DEFAULT_HOURS_TO_SHOW = 24

    /**
     * HA `getDefaultForecastType`: daily > twice_daily > hourly by supported
     * bits. Null when the entity advertises no forecast feature.
     */
    fun defaultForecastType(supportedFeatures: Int): String? = when {
        supportedFeatures and FORECAST_DAILY != 0 -> "daily"
        supportedFeatures and FORECAST_TWICE_DAILY != 0 -> "twice_daily"
        supportedFeatures and FORECAST_HOURLY != 0 -> "hourly"
        else -> null
    }

    /**
     * HA `resolveForecastResolution`: honour the configured type when the entity
     * supports it, otherwise fall back to [defaultForecastType]. A supported-bits
     * value of 0 (entity not yet loaded / no features reported) trusts the
     * configured type, or daily, so the feature still renders.
     */
    fun resolveForecastType(configured: String?, supportedFeatures: Int): String? {
        if (supportedFeatures == 0) return configured ?: "daily"
        val supported = buildList {
            if (supportedFeatures and FORECAST_DAILY != 0) add("daily")
            if (supportedFeatures and FORECAST_TWICE_DAILY != 0) add("twice_daily")
            if (supportedFeatures and FORECAST_HOURLY != 0) add("hourly")
        }
        if (configured != null && configured in supported) return configured
        return defaultForecastType(supportedFeatures)
    }

    /**
     * Window the forecast entries to the configured days_to_show / hours_to_show.
     * Hourly entries are filtered to [now, now + hours) and limited to that
     * count; daily / twice_daily entries take the first N (x2 for twice_daily).
     * [nowEpochSec] lets tests pin the window; production passes the wall clock.
     */
    fun windowEntries(
        entries: List<ForecastEntry>,
        forecastType: String?,
        daysToShow: Int?,
        hoursToShow: Int?,
        nowEpochSec: Long,
    ): List<ForecastEntry> {
        if (entries.isEmpty()) return entries
        return when (forecastType) {
            "hourly" -> {
                val hours = (hoursToShow ?: DEFAULT_HOURS_TO_SHOW).coerceAtLeast(1)
                val endSec = nowEpochSec + hours.toLong() * 3600L
                entries.filter { e ->
                    val sec = parseHaInstant(e.whenIso)?.epochSecond
                    sec != null && sec >= nowEpochSec - 3600L && sec < endSec
                }.take(hours)
            }
            "twice_daily" -> {
                val days = (daysToShow ?: DEFAULT_DAYS_TO_SHOW).coerceAtLeast(1)
                entries.take(days * 2)
            }
            else -> {
                val days = (daysToShow ?: DEFAULT_DAYS_TO_SHOW).coerceAtLeast(1)
                entries.take(days)
            }
        }
    }

    /**
     * HA `_referenceMaxAmount`: the floor the amount bar scale never drops below,
     * so a week of light drizzle doesn't fill the strip. Observed maxima above
     * the floor still drive the scale. Imperial uses the inch floors.
     */
    fun referenceMaxAmount(forecastType: String?, imperial: Boolean): Double = when (forecastType) {
        "hourly" -> if (imperial) 0.1 else 2.5
        "twice_daily" -> if (imperial) 0.25 else 6.0
        else -> if (imperial) 0.4 else 10.0
    }

    /**
     * Bar height fraction (0..1) for a precipitation [value]. Amount bars scale
     * against max(referenceFloor, observedMax); probability against a fixed
     * 0..100. A zero / null value yields 0 so the renderer can draw a dot
     * instead of a stub bar.
     */
    fun precipBarFraction(
        value: Double?,
        isProbability: Boolean,
        forecastType: String?,
        imperial: Boolean,
        observedMaxAmount: Double,
    ): Float {
        val v = value ?: return 0f
        if (v <= 0.0) return 0f
        val scale = if (isProbability) {
            100.0
        } else {
            maxOf(referenceMaxAmount(forecastType, imperial), observedMaxAmount)
        }
        if (scale <= 0.0) return 0f
        return (v / scale).toFloat().coerceIn(0f, 1f)
    }

    /**
     * HA temperature palette (-6..42 C), linearly interpolated. Returns an opaque
     * ARGB int. Values outside the range clamp to the endpoints.
     */
    fun temperaturePaletteArgb(tempC: Double): Int {
        val clamped = tempC.coerceIn(PALETTE_MIN.toDouble(), PALETTE_MAX.toDouble())
        val idx = clamped - PALETTE_MIN
        val i0 = floor(idx).toInt().coerceIn(0, PALETTE_RGB.size - 1)
        val i1 = (i0 + 1).coerceAtMost(PALETTE_RGB.size - 1)
        val frac = (idx - i0)
        val a = PALETTE_RGB[i0]
        val b = PALETTE_RGB[i1]
        val r = (a[0] + (b[0] - a[0]) * frac).toInt().coerceIn(0, 255)
        val g = (a[1] + (b[1] - a[1]) * frac).toInt().coerceIn(0, 255)
        val bl = (a[2] + (b[2] - a[2]) * frac).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    /**
     * A daily temperature range bar's [low, high] vertical extent as fractions of
     * the strip height, where 0 is the strip's series minimum and 1 its maximum.
     * Entries with no templow are excluded upstream; this assumes both ends set.
     */
    fun rangeBarFractions(low: Double, high: Double, seriesMin: Double, seriesMax: Double): Pair<Float, Float> {
        val span = (seriesMax - seriesMin).takeIf { it > 0.0 } ?: 1.0
        val lf = ((low - seriesMin) / span).toFloat().coerceIn(0f, 1f)
        val hf = ((high - seriesMin) / span).toFloat().coerceIn(0f, 1f)
        return lf to hf
    }

    private const val PALETTE_MIN = -6
    private val PALETTE_HEX = listOf(
        "#249df2", "#239dec", "#239fec", "#23a3eb", "#23a6eb", "#23a9e9", "#22abe6", "#22aee4",
        "#22b1e0", "#21b1dd", "#23b6da", "#28b8d6", "#2abdd3", "#2fbfcf", "#34c4cc", "#36c6c9",
        "#43c9c0", "#59c9b3", "#6fc9a3", "#82c992", "#98c985", "#a8c977", "#b9c762", "#c7c74d",
        "#d1be31", "#dbb921", "#e6ba22", "#ecc123", "#ecba23", "#eeb424", "#ecaa23", "#eca023",
        "#ec9723", "#ec8f23", "#ec8523", "#ec7c23", "#ee7f24", "#e67122", "#df6321", "#df5b21",
        "#dd5421", "#db4c21", "#db4121", "#db3721", "#d62f20", "#cc301f", "#c22a1d", "#ca2d1e",
        "#c82c1d",
    )
    private val PALETTE_MAX = PALETTE_MIN + PALETTE_HEX.size - 1
    private val PALETTE_RGB: List<IntArray> = PALETTE_HEX.map { hex ->
        val v = hex.removePrefix("#").toLong(16).toInt()
        intArrayOf((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    /** Whether the given hourly window contains a "now" anchor worth drawing the
     *  temperature line from (used by the hourly temperature feature). */
    fun isHourly(forecastType: String?): Boolean = forecastType == "hourly"
}
