package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.lovelace.TimestampFormat
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

// Locale-pinned formatters matching the repo's TimeFormat.kt convention.
// Month names in patterns need Locale.US to stay stable across device locales.
private val FMT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
private val FMT_TIME_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private val FMT_TIME_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val FMT_DATETIME_24H: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.US)
private val FMT_DATETIME_12H: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM h:mm a", Locale.US)

/**
 * Device classes that HA automatically renders as timestamps. Mirrors
 * `SENSOR_TIMESTAMP_DEVICE_CLASSES` from `src/data/sensor.ts`.
 */
private val TIMESTAMP_DEVICE_CLASSES = setOf("timestamp", "uptime")

/**
 * Whether this entity's device_class triggers automatic timestamp rendering.
 * Pure: no I/O, injectable [deviceClass] for easy unit testing.
 */
fun isTimestampDeviceClass(deviceClass: String?): Boolean =
    deviceClass?.lowercase()?.let { it in TIMESTAMP_DEVICE_CLASSES } == true

/**
 * Resolve the effective [TimestampFormat] for a row, applying HA's defaults:
 *  - explicit [rowFormat] wins when set
 *  - `uptime` device_class defaults to TOTAL (elapsed duration)
 *  - `timestamp` device_class defaults to RELATIVE
 *  - null when the entity is not a timestamp class at all (the caller skips
 *    timestamp rendering and falls through to raw-state text)
 */
fun resolveTimestampFormat(rowFormat: TimestampFormat?, deviceClass: String?): TimestampFormat? {
    if (rowFormat != null) return rowFormat
    return when (deviceClass?.lowercase()) {
        "uptime" -> TimestampFormat.TOTAL
        "timestamp" -> TimestampFormat.RELATIVE
        else -> null
    }
}

/**
 * Core timestamp formatting engine. Pure: takes an [Instant], a [TimestampFormat],
 * the current time [now], the local [zone], and the user's 12/24-hour choice. Returns
 * the formatted string. Never throws; callers pass validated [Instant] values.
 *
 * [TimestampFormat.RELATIVE]: "just now", "5m ago", "in 2h" — delegates to
 * [formatRelativeTime] so the wording stays consistent with existing cards.
 *
 * [TimestampFormat.TOTAL]: elapsed/remaining HH:MM:SS (unsigned; the sign is
 * supplied by the caller's framing). Uses the HA convention: total seconds
 * formatted as H:MM:SS for durations >= 1 h, or M:SS otherwise. Mirrors
 * `relativeTime(now, locale, ts, false)` in HA's hub-timestamp-display for
 * the `total` format: it is an unsigned elapsed duration, not a delta phrase.
 *
 * [TimestampFormat.DATE]: locale-formatted date only.
 * [TimestampFormat.TIME]: locale-formatted time-of-day only (respects [use24h]).
 * [TimestampFormat.DATETIME]: locale-formatted date + time (respects [use24h]).
 */
fun formatTimestamp(
    at: Instant,
    format: TimestampFormat,
    now: Instant,
    zone: ZoneId,
    use24h: Boolean,
): String {
    return when (format) {
        TimestampFormat.RELATIVE -> formatRelativeTime(at, now)
        TimestampFormat.TOTAL -> formatTotalDuration(at, now)
        TimestampFormat.DATE -> at.atZone(zone).format(FMT_DATE)
        TimestampFormat.TIME -> at.atZone(zone).format(if (use24h) FMT_TIME_24H else FMT_TIME_12H)
        TimestampFormat.DATETIME -> at.atZone(zone).format(if (use24h) FMT_DATETIME_24H else FMT_DATETIME_12H)
    }
}

/**
 * Format the absolute difference between [at] and [now] as an unsigned H:MM:SS
 * (or M:SS for durations under an hour). This is the TOTAL format from HA's
 * `hui-timestamp-display`: an elapsed/remaining duration counter without "ago" /
 * "in" framing, matching HA's `relativeTime(now, locale, ts, false)` path.
 *
 * Pure + injectable [now] for unit testing.
 */
fun formatTotalDuration(at: Instant, now: Instant): String {
    val totalSec = abs(now.toEpochMilli() - at.toEpochMilli()) / 1000L
    val h = totalSec / 3600L
    val m = (totalSec % 3600L) / 60L
    val s = totalSec % 60L
    return if (h > 0) {
        // H:MM:SS — same shape HA uses for multi-hour durations.
        "%d:%02d:%02d".format(Locale.US, h, m, s)
    } else {
        // M:SS — drop the hours component when < 1 h so the label stays compact.
        "%d:%02d".format(Locale.US, m, s)
    }
}

/**
 * Attempt to derive an [Instant] from a raw entity state string that may be an
 * HA timestamp. Returns null when [rawState] is blank, unparseable, or when the
 * entity's device_class is not a timestamp class. The null return means the
 * caller should fall back to raw-state rendering rather than ticking.
 *
 * Uses [com.github.itskenny0.r1ha.core.ha.parseHaInstant] for desugaring-safe
 * parsing (never plain Instant.parse; see HaTime.kt for the +00:00 issue).
 */
fun timestampInstantOrNull(deviceClass: String?, rawState: String?): Instant? {
    if (!isTimestampDeviceClass(deviceClass)) return null
    return com.github.itskenny0.r1ha.core.ha.parseHaInstant(rawState)
}
