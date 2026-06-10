package com.github.itskenny0.r1ha.feature.weather

import androidx.compose.runtime.Stable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import java.util.Locale

/**
 * Pure forecast parsing + formatting helpers for the Weather surface.
 * Kept free of Android + Compose dependencies so they can be unit
 * tested directly: every function here takes plain inputs and returns
 * plain outputs.
 *
 * HA reports forecast entries either via the legacy `forecast`
 * attribute on the weather entity (a single JSON array, hourly OR daily
 * depending on how the integration is configured) or via the modern
 * `weather.get_forecasts` service-with-response. We parse the legacy
 * attribute here. When a forecast list is present we classify it as
 * hourly vs daily from the spacing between consecutive datetimes so the
 * UI can label it correctly and pick the right time format.
 */

/** Which cadence a forecast list represents. */
enum class ForecastKind { Hourly, Daily }

/**
 * Which cadence a forecast UI opens on. Daily wins whenever the entity
 * reports it: a day-level outlook is the planning view every weather app
 * leads with, and hourly is one tap away. Hourly is only the default when
 * it is all the integration provides.
 */
internal fun defaultForecastKind(hasHourly: Boolean, hasDaily: Boolean): ForecastKind =
    if (hasDaily) ForecastKind.Daily else ForecastKind.Hourly

/**
 * A single normalised forecast entry. All temperature / wind values
 * stay in HA's reported units; the UI appends the entity's unit string.
 * Nulls are preserved so the UI can omit absent fields rather than
 * rendering a placeholder zero.
 */
@Stable
data class ForecastEntry(
    /** Raw ISO-8601 instant string from HA (`datetime`). */
    val whenIso: String,
    /** HA standard condition slug (e.g. "partlycloudy"); "" when absent. */
    val condition: String,
    /** Daily high or, for hourly entries, the single temperature. */
    val temperature: Double?,
    /** Daily low (`templow`); null for hourly entries that omit it. */
    val tempLow: Double?,
    /** Precipitation amount in the entity's precip unit (mm/in). */
    val precipitation: Double?,
    /** Precipitation probability as a percentage 0..100; null when absent. */
    val precipitationProbability: Int?,
    /** Wind speed in the entity's wind unit; null when absent. */
    val windSpeed: Double?,
    /** Wind bearing in degrees (0 = N), or null when absent / textual. */
    val windBearingDeg: Double?,
    /** Textual wind bearing ("NE") when HA passes a compass string. */
    val windBearingText: String?,
)

/**
 * Parse HA's `forecast` JSON array into [ForecastEntry] rows. Entries
 * without a `datetime` are dropped (we key labels off the instant).
 * Robust to missing / null fields and to numbers arriving as strings.
 */
fun parseForecastEntries(arr: JsonArray?): List<ForecastEntry> {
    if (arr == null) return emptyList()
    return arr.mapNotNull { el ->
        val obj = el as? JsonObject ?: return@mapNotNull null
        val whenIso = obj.str("datetime") ?: return@mapNotNull null
        val bearingRaw = obj.str("wind_bearing")
        ForecastEntry(
            whenIso = whenIso,
            condition = obj.str("condition") ?: "",
            temperature = obj.dbl("temperature"),
            tempLow = obj.dbl("templow"),
            precipitation = obj.dbl("precipitation"),
            precipitationProbability = obj.dbl("precipitation_probability")
                ?.let { Math.round(it).toInt() },
            windSpeed = obj.dbl("wind_speed"),
            windBearingDeg = bearingRaw?.toDoubleOrNull(),
            windBearingText = bearingRaw?.takeIf { it.toDoubleOrNull() == null },
        )
    }
}

/**
 * Parse the per-entity object returned by the `weather.get_forecasts`
 * response-only service into [ForecastEntry] rows. HA shapes the
 * per-entity service response as `{ "forecast": [ ... ] }`, so we pull
 * the `forecast` array out and reuse [parseForecastEntries]. Anything
 * that isn't an object carrying a `forecast` JSON array (an empty
 * fallback object, a malformed payload) yields an empty list so the
 * caller can fall back to the legacy attribute.
 */
fun parseForecastResponse(element: JsonElement?): List<ForecastEntry> {
    val obj = element as? JsonObject ?: return emptyList()
    val arr = obj["forecast"] as? JsonArray ?: return emptyList()
    return parseForecastEntries(arr)
}

/**
 * Classify a forecast list as [ForecastKind.Hourly] or
 * [ForecastKind.Daily] from the median gap between consecutive
 * datetimes. Gaps under 23h read as hourly; everything else (and the
 * degenerate 0/1-entry case) reads as daily, matching how HA's own
 * frontend buckets the legacy attribute.
 */
fun classifyForecastKind(entries: List<ForecastEntry>): ForecastKind {
    val instants = entries.mapNotNull { parseHaInstant(it.whenIso) }
    if (instants.size < 2) return ForecastKind.Daily
    val gaps = instants.zipWithNext { a, b -> kotlin.math.abs(b.epochSecond - a.epochSecond) }
        .filter { it > 0 }
        .sorted()
    if (gaps.isEmpty()) return ForecastKind.Daily
    val median = gaps[gaps.size / 2]
    val twentyThreeHours = 23L * 3600L
    return if (median < twentyThreeHours) ForecastKind.Hourly else ForecastKind.Daily
}

/**
 * Short hour label for an hourly entry, e.g. "14:00" (or "2 PM" when
 * [use24h] is false, honouring the Settings clock-format choice) in [zone].
 * Defaults to 24-hour so existing call sites and tests keep their shape.
 * Falls back to the first five chars of the raw ISO when unparseable.
 */
fun formatHourLabel(
    iso: String,
    zone: ZoneId = ZoneId.systemDefault(),
    use24h: Boolean = true,
): String =
    runCatching {
        val zdt = (parseHaInstant(iso) ?: error("unparseable timestamp")).atZone(zone)
        (if (use24h) HOUR_FORMAT else HOUR_FORMAT_12).format(zdt)
    }.getOrElse { iso.take(5) }

/**
 * Short day label for a daily entry, e.g. "Mon 15" in [zone].
 * Weekday name is always Locale.US so the abbreviation is stable across
 * device locales. Falls back to the first five chars when unparseable.
 */
fun formatDayLabel(iso: String, zone: ZoneId = ZoneId.systemDefault()): String =
    runCatching {
        val zdt = (parseHaInstant(iso) ?: error("unparseable timestamp")).atZone(zone)
        DAY_FORMAT.format(zdt)
    }.getOrElse { iso.take(5) }

/** Locale.US label appropriate to the forecast [kind]. [use24h] only
 *  affects hourly labels (daily labels carry no time of day). */
fun formatForecastLabel(
    iso: String,
    kind: ForecastKind,
    zone: ZoneId = ZoneId.systemDefault(),
    use24h: Boolean = true,
): String = when (kind) {
    ForecastKind.Hourly -> formatHourLabel(iso, zone, use24h)
    ForecastKind.Daily -> formatDayLabel(iso, zone)
}

/**
 * Convert HA's `wind_bearing` (degrees, 0 = north) into the 8-point
 * compass label most weather UIs use ("N", "NE", "E", …).
 */
fun degreesToCompass(deg: Double): String {
    val normalised = ((deg % 360) + 360) % 360
    val sector = ((normalised + 22.5) / 45.0).toInt() % 8
    return COMPASS[sector]
}

private val COMPASS = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

private val HOUR_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.US)

/** 12-hour variant. "h a" (not "h:mm a") because hourly forecast entries sit
 *  on whole hours and the AM/PM marker already costs the label width that
 *  ":00" would have used. */
private val HOUR_FORMAT_12: DateTimeFormatter =
    DateTimeFormatter.ofPattern("h a", Locale.US)

private val DAY_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("EEE d", Locale.US)

private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

private fun JsonObject.dbl(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.toDoubleOrNull()
