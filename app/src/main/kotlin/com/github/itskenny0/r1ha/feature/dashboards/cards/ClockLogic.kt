package com.github.itskenny0.r1ha.feature.dashboards.cards

import java.time.ZoneId

/**
 * Pure decision logic for the clock card: the time-format resolution, the zone
 * resolution, and the analog hand geometry. Split out so the maths is
 * unit-tested without a Canvas or Compose harness.
 */

/**
 * Resolve whether the clock should render in 24-hour form.
 *
 * Mirrors HA's clock `time_format`: "24" forces 24-hour, "12" forces AM/PM, and
 * "auto" (or an absent value) follows the device's [systemIs24h] setting.
 */
fun clockUses24h(timeFormat: String?, systemIs24h: Boolean): Boolean =
    when (timeFormat?.trim()?.lowercase()) {
        "24" -> true
        "12" -> false
        // "auto" and anything else (including null) defer to the device.
        else -> systemIs24h
    }

/**
 * Resolve the zone the clock displays. A configured IANA id is used when valid;
 * an unknown / malformed id falls back to [fallback] (the device's local zone)
 * rather than throwing.
 */
fun clockZone(timeZone: String?, fallback: ZoneId): ZoneId {
    if (timeZone.isNullOrBlank()) return fallback
    return runCatching { ZoneId.of(timeZone.trim()) }.getOrDefault(fallback)
}

/**
 * Clock-hand angles in degrees clockwise from 12 o'clock (straight up), for an
 * analog face. The hour hand sweeps smoothly with the minutes (so 1:30 sits
 * halfway between 1 and 2), the minute hand sweeps with the seconds, and the
 * second hand ticks per second. Hours are taken modulo 12.
 */
data class ClockHands(val hourDeg: Float, val minuteDeg: Float, val secondDeg: Float)

fun clockHands(hour: Int, minute: Int, second: Int): ClockHands {
    val h = ((hour % 12) + 12) % 12
    val m = ((minute % 60) + 60) % 60
    val s = ((second % 60) + 60) % 60
    val secondDeg = s * 6f
    val minuteDeg = m * 6f + s * 0.1f
    val hourDeg = h * 30f + m * 0.5f + s * (0.5f / 60f)
    return ClockHands(hourDeg = hourDeg, minuteDeg = minuteDeg, secondDeg = secondDeg)
}
