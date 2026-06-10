package com.github.itskenny0.r1ha.ui.components

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields

/**
 * Pure period-resolution layer for the statistic / statistics-graph cards.
 *
 * HA's `period:` accepts three shapes the cards resolve into a concrete
 * [start, end) instant window plus a recorder bucket size:
 *   - calendar: {period: day|week|month|year, offset: N}  (offset counts
 *     whole calendar periods back from now; 0 = the current period).
 *   - fixed_period: {start, end}  (explicit ISO instants).
 *   - rolling_window: {duration: {...}, offset: {...}}  (a sliding span ending
 *     now, optionally shifted by an offset duration).
 * Plus the legacy bare-string forms ("day" / "week" / ...).
 *
 * Resolution is split out here so the calendar boundary arithmetic and the
 * bucket-size choice can be locked in with plain JUnit tests; the cards just
 * call [resolveStatisticWindow] and feed the result to the repository.
 */

/** Parsed `period:` config. Defaults to a 7-day rolling window (HA's effective
 *  default when nothing is configured). */
sealed interface StatisticPeriodSpec {
    /** Legacy / calendar bare label: day / week / month / year. */
    data class Calendar(val period: String, val offset: Int = 0) : StatisticPeriodSpec

    /** Explicit [start, end) window. Either bound may be null (open-ended). */
    data class Fixed(val startMillis: Long?, val endMillis: Long?) : StatisticPeriodSpec

    /** Sliding [duration] window ending now, shifted back by [offset]. */
    data class Rolling(
        val durationMillis: Long,
        val offsetMillis: Long = 0L,
    ) : StatisticPeriodSpec

    companion object {
        val DEFAULT: StatisticPeriodSpec = Rolling(Duration.ofDays(7).toMillis())
    }
}

/** Concrete window + recorder bucket the card requests. */
data class StatisticWindow(
    val start: Instant,
    val end: Instant,
    /** Recorder bucket size: 5minute / hour / day / week / month. */
    val bucket: String,
)

/**
 * Resolve a [spec] into a concrete [StatisticWindow] anchored on [now] in
 * [zone] (the device zone by default). The bucket size is chosen so a window
 * yields a readable number of buckets: short windows get fine buckets, long
 * windows coarse ones, matching HA's defaults.
 */
fun resolveStatisticWindow(
    spec: StatisticPeriodSpec,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
): StatisticWindow = when (spec) {
    is StatisticPeriodSpec.Fixed -> {
        val end = spec.endMillis?.let(Instant::ofEpochMilli) ?: now
        val start = spec.startMillis?.let(Instant::ofEpochMilli)
            ?: end.minus(Duration.ofDays(7))
        StatisticWindow(start, end, bucketForSpan(Duration.between(start, end)))
    }
    is StatisticPeriodSpec.Rolling -> {
        val end = now.minusMillis(spec.offsetMillis)
        val start = end.minusMillis(spec.durationMillis)
        StatisticWindow(start, end, bucketForSpan(Duration.ofMillis(spec.durationMillis)))
    }
    is StatisticPeriodSpec.Calendar -> resolveCalendar(spec, now, zone)
}

private fun resolveCalendar(
    spec: StatisticPeriodSpec.Calendar,
    now: Instant,
    zone: ZoneId,
): StatisticWindow {
    val today = now.atZone(zone)
    val (start, end, bucket) = when (spec.period.lowercase()) {
        "day" -> {
            val base = today.toLocalDate().minusDays(spec.offset.toLong())
            Triple(
                base.atStartOfDay(zone).toInstant(),
                base.plusDays(1).atStartOfDay(zone).toInstant(),
                "hour",
            )
        }
        "week" -> {
            // ISO week starts Monday.
            val monday = today.toLocalDate()
                .with(java.time.DayOfWeek.MONDAY)
                .minusWeeks(spec.offset.toLong())
            Triple(
                monday.atStartOfDay(zone).toInstant(),
                monday.plusWeeks(1).atStartOfDay(zone).toInstant(),
                "day",
            )
        }
        "month" -> {
            val first = today.toLocalDate().withDayOfMonth(1).minusMonths(spec.offset.toLong())
            Triple(
                first.atStartOfDay(zone).toInstant(),
                first.plusMonths(1).atStartOfDay(zone).toInstant(),
                "day",
            )
        }
        "year" -> {
            val first = today.toLocalDate().withDayOfYear(1).minusYears(spec.offset.toLong())
            Triple(
                first.atStartOfDay(zone).toInstant(),
                first.plusYears(1).atStartOfDay(zone).toInstant(),
                "month",
            )
        }
        else -> {
            val base = today.toLocalDate().minusDays(spec.offset.toLong())
            Triple(
                base.atStartOfDay(zone).toInstant(),
                base.plusDays(1).atStartOfDay(zone).toInstant(),
                "hour",
            )
        }
    }
    return StatisticWindow(start, end, bucket)
}

/** Choose a recorder bucket so a [span] resolves into a legible bucket count. */
fun bucketForSpan(span: Duration): String {
    val hours = span.toHours()
    return when {
        hours <= 26 -> "hour"
        hours <= 24 * 35 -> "day"
        hours <= 24 * 200 -> "week"
        else -> "month"
    }
}

/**
 * Select one numeric value for a requested stat type from a bucket's columns.
 * Returns null when the bucket doesn't carry the requested column, so the
 * caller can drop the point. Pure mirror of HA's stat-type column mapping.
 *
 * The columns map keys are the canonical HA stat-type names: mean / min / max /
 * sum / state / change. "value" / unknown falls back to mean then state.
 */
fun selectStatColumn(
    statType: String,
    mean: Double?,
    min: Double?,
    max: Double?,
    sum: Double?,
    state: Double?,
    change: Double?,
): Double? = when (statType.lowercase()) {
    "mean" -> mean
    "min" -> min
    "max" -> max
    "sum" -> sum
    "state" -> state
    "change" -> change
    else -> mean ?: state
}

/**
 * Number of distinct ISO calendar weeks a span touches, used by tests that
 * exercise the week-boundary arithmetic. Kept tiny and pure.
 */
internal fun isoWeekOf(instant: Instant, zone: ZoneId): Int =
    instant.atZone(zone).get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)

/** Whole calendar days a [Duration] spans (floored). */
internal fun wholeDays(span: Duration): Long = span.toDays()

/** Whole hours between two instants (floored). */
internal fun wholeHoursBetween(a: Instant, b: Instant): Long =
    ChronoUnit.HOURS.between(a, b)
