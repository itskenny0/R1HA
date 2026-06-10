package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.feature.weather.ForecastEntry
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure slot-grouping + labelling + rounding logic for the weather-forecast card.
 * Kept free of Compose / IO so the forecast-type resolution, the per-slot label
 * (hourly time, daily weekday, twice_daily day/night with a day-grouping header),
 * and the temperature rounding can be unit-tested directly. Mirrors
 * hui-weather-forecast-card.ts: it derives the same labels HA renders for each
 * forecast cadence.
 */
object WeatherForecastLogic {

    /** The three modern forecast cadences HA's `weather/get_forecasts` accepts. */
    enum class ForecastType(val wire: String) {
        DAILY("daily"),
        HOURLY("hourly"),
        TWICE_DAILY("twice_daily"),
    }

    /** Weather entity supported_features bitmask values (HA WeatherEntityFeature). */
    const val FEATURE_FORECAST_DAILY = 1
    const val FEATURE_FORECAST_HOURLY = 2
    const val FEATURE_FORECAST_TWICE_DAILY = 4

    /**
     * Resolve the forecast type to request. An explicit config [configType]
     * (`daily` / `hourly` / `twice_daily`) wins when the entity supports it;
     * otherwise we fall back to HA's default-by-supported-feature order
     * (daily > twice_daily > hourly), matching getDefaultForecastType.
     * [supportedFeatures] is the entity's `supported_features` attribute; a null
     * (legacy server that doesn't report it) defaults to daily.
     */
    fun resolveForecastType(configType: String?, supportedFeatures: Int?): ForecastType {
        val requested = when (configType?.lowercase()) {
            "hourly" -> ForecastType.HOURLY
            "twice_daily" -> ForecastType.TWICE_DAILY
            "daily" -> ForecastType.DAILY
            else -> null
        }
        val features = supportedFeatures ?: return requested ?: ForecastType.DAILY
        fun supports(type: ForecastType) = when (type) {
            ForecastType.DAILY -> features and FEATURE_FORECAST_DAILY != 0
            ForecastType.HOURLY -> features and FEATURE_FORECAST_HOURLY != 0
            ForecastType.TWICE_DAILY -> features and FEATURE_FORECAST_TWICE_DAILY != 0
        }
        if (requested != null && supports(requested)) return requested
        return when {
            supports(ForecastType.DAILY) -> ForecastType.DAILY
            supports(ForecastType.TWICE_DAILY) -> ForecastType.TWICE_DAILY
            supports(ForecastType.HOURLY) -> ForecastType.HOURLY
            else -> requested ?: ForecastType.DAILY
        }
    }

    /**
     * The per-slot primary label. Hourly entries show the time of day, daily show
     * the weekday + day-of-month, twice_daily show "Day" / "Night" from the
     * entry's `is_daytime` flag. All locale-pinned to [Locale.US] so weekday /
     * AM-PM tokens stay stable across device locales.
     */
    fun slotLabel(
        entry: ForecastEntry,
        type: ForecastType,
        zone: ZoneId = ZoneId.systemDefault(),
        use24h: Boolean = true,
    ): String = when (type) {
        ForecastType.TWICE_DAILY -> if (entry.isDaytime == false) "Night" else "Day"
        ForecastType.HOURLY -> formatTime(entry.whenIso, zone, use24h)
        ForecastType.DAILY -> formatDay(entry.whenIso, zone)
    }

    /**
     * The day-grouping header shown above a twice_daily slot: the weekday label,
     * emitted only on the first slot of each calendar day so the day/night pairs
     * sit under one heading. Returns null when [entry] is not the first of its day
     * (so no header is drawn). [previous] is the entry immediately before in the
     * list (null for the first slot).
     */
    fun dayGroupHeader(
        entry: ForecastEntry,
        previous: ForecastEntry?,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val day = localDate(entry.whenIso, zone) ?: return null
        val prevDay = previous?.let { localDate(it.whenIso, zone) }
        return if (day != prevDay) formatDay(entry.whenIso, zone) else null
    }

    /**
     * Format a temperature for display. [round] = whole degrees (round half up),
     * otherwise one fractional digit. Locale-pinned so decimal separators and
     * digits are ASCII. Null in -> null out so the caller can omit the slot.
     */
    fun formatTemperature(value: Double?, round: Boolean): String? {
        if (value == null) return null
        return if (round) {
            "${Math.round(value)}"
        } else {
            String.format(Locale.US, "%.1f", value)
        }
    }

    /** The default secondary line (extrema) text HA shows under the current
     *  condition when no `secondary_info_attribute` is configured: the day's
     *  high / low for the first forecast slot. Null when neither is present. */
    fun defaultSecondary(first: ForecastEntry?, round: Boolean): String? {
        if (first == null) return null
        val hi = formatTemperature(first.temperature, round)
        val lo = formatTemperature(first.tempLow, round)
        return when {
            hi != null && lo != null -> "$hi° / $lo°"
            hi != null -> "$hi°"
            lo != null -> "$lo°"
            else -> null
        }
    }

    private fun localDate(iso: String, zone: ZoneId) =
        runCatching { (parseHaInstant(iso) ?: error("bad")).atZone(zone).toLocalDate() }.getOrNull()

    private fun formatTime(iso: String, zone: ZoneId, use24h: Boolean): String =
        runCatching {
            val zdt = (parseHaInstant(iso) ?: error("bad")).atZone(zone)
            (if (use24h) HOUR_24 else HOUR_12).format(zdt)
        }.getOrElse { iso.take(5) }

    private fun formatDay(iso: String, zone: ZoneId): String =
        runCatching {
            val zdt = (parseHaInstant(iso) ?: error("bad")).atZone(zone)
            DAY.format(zdt)
        }.getOrElse { iso.take(5) }

    private val HOUR_24 = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val HOUR_12 = DateTimeFormatter.ofPattern("h a", Locale.US)
    private val DAY = DateTimeFormatter.ofPattern("EEE d", Locale.US)
}
