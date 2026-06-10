package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

/**
 * Pure period-selection model shared by every energy card on a dashboard, the
 * R1 analogue of HA's `EnergyCollection` (src/data/energy.ts). A collection is
 * identified by a [collectionKey]: cards carrying the same key share one
 * [EnergyPeriod], so moving the date-range selector reflows every chart bound
 * to it. The default key is "energy_date_selection" (HA's
 * `energy_date_selection: true` without an explicit `collection_key`).
 *
 * The window is a half-open `[start, end)` instant range plus the preset that
 * produced it (so the chevrons know how to shift and the title knows how to
 * render). Compare-to-previous is a separate flag; when on, the previous-span
 * window is derived by [EnergyPeriod.compareWindow].
 *
 * All arithmetic is offline-testable: nothing here touches HA or Compose.
 */

/** The date-range presets the selector offers, mirroring HA's `DateRange`
 *  (src/common/datetime/calc_date_range.ts). Each resolves to a concrete
 *  `[start, end)` window anchored on "now" in the device zone. */
enum class EnergyDateRange(val label: String) {
    TODAY("Today"),
    YESTERDAY("Yesterday"),
    THIS_WEEK("This week"),
    THIS_MONTH("This month"),
    THIS_QUARTER("This quarter"),
    THIS_YEAR("This year"),
    LAST_7_DAYS("Last 7 days"),
    LAST_30_DAYS("Last 30 days"),
    LAST_365_DAYS("Last year"),
}

/** A resolved, shared energy period. */
data class EnergyPeriod(
    val start: Instant,
    /** Exclusive end of the window. */
    val end: Instant,
    /** The preset that produced the window, or null after a chevron shift moved
     *  it off a named preset (then it is a free-floating span of the same length). */
    val preset: EnergyDateRange?,
    val compare: Boolean = false,
) {
    /** Span length of the current window. Chevrons shift by exactly this. */
    fun span(): Duration = Duration.between(start, end)
}

object EnergyPeriodEngine {

    /** The default collection key HA uses for `energy_date_selection: true`
     *  without an explicit `collection_key`. */
    const val DEFAULT_COLLECTION_KEY = "energy_date_selection"

    /**
     * Resolve a [range] preset into a concrete period anchored on [now] in
     * [zone]. Calendar presets snap to local midnight boundaries; the rolling
     * "last N days" presets end at the start of today (HA's `now-Nd` ends at
     * `subDays(today, 0)` which is the start of the current day) and span N
     * whole days back.
     *
     * HA's collection has a special case: at hour 0 the default "today" preset
     * falls back to "yesterday" because no data exists yet for today. That
     * fallback is applied by [defaultPeriod], not here, so an explicit "today"
     * request is honoured verbatim.
     */
    fun resolve(
        range: EnergyDateRange,
        now: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
        compare: Boolean = false,
    ): EnergyPeriod {
        val today = now.atZone(zone).toLocalDate()
        val (startDate, endDateExclusive) = when (range) {
            EnergyDateRange.TODAY -> today to today.plusDays(1)
            EnergyDateRange.YESTERDAY -> today.minusDays(1) to today
            EnergyDateRange.THIS_WEEK -> {
                val monday = today.with(DayOfWeek.MONDAY)
                monday to monday.plusWeeks(1)
            }
            EnergyDateRange.THIS_MONTH -> {
                val first = today.withDayOfMonth(1)
                first to first.plusMonths(1)
            }
            EnergyDateRange.THIS_QUARTER -> {
                val firstMonth = ((today.monthValue - 1) / 3) * 3 + 1
                val first = LocalDate.of(today.year, firstMonth, 1)
                first to first.plusMonths(3)
            }
            EnergyDateRange.THIS_YEAR -> {
                val first = today.withDayOfYear(1)
                first to first.plusYears(1)
            }
            EnergyDateRange.LAST_7_DAYS -> today.minusDays(7) to today
            EnergyDateRange.LAST_30_DAYS -> today.minusDays(30) to today
            EnergyDateRange.LAST_365_DAYS -> today.minusDays(365) to today
        }
        return EnergyPeriod(
            start = startDate.atStartOfDay(zone).toInstant(),
            end = endDateExclusive.atStartOfDay(zone).toInstant(),
            preset = range,
            compare = compare,
        )
    }

    /**
     * The period a freshly-loaded collection opens on, mirroring HA's
     * `getEnergyDataCollection` default: "today", but at hour 0 (before any of
     * today's hourly statistics exist) fall back to "yesterday".
     */
    fun defaultPeriod(now: Instant, zone: ZoneId = ZoneId.systemDefault()): EnergyPeriod {
        val hour = now.atZone(zone).hour
        val range = if (hour == 0) EnergyDateRange.YESTERDAY else EnergyDateRange.TODAY
        return resolve(range, now, zone)
    }

    /**
     * Shift [period] forward or backward by one of its own spans, mirroring HA's
     * `shiftDateRange`. Whole-calendar-month and whole-calendar-day windows
     * shift by calendar months / days so a month view steps cleanly to the
     * previous month regardless of differing month lengths; arbitrary spans
     * shift by the raw duration.
     *
     * A shift clears [EnergyPeriod.preset] only when the result is no longer a
     * named preset; the calendar shifts keep the named-ness conceptually (the
     * title still renders the month/week), but since the anchor moves we drop
     * the preset and let the title fall back to the explicit date range.
     */
    fun shift(
        period: EnergyPeriod,
        forward: Boolean,
        zone: ZoneId = ZoneId.systemDefault(),
    ): EnergyPeriod {
        val dir = if (forward) 1L else -1L
        val startZdt = period.start.atZone(zone)
        val endZdt = period.end.atZone(zone)
        val startDate = startZdt.toLocalDate()
        val endDate = endZdt.toLocalDate()
        val isMidnightAligned = startZdt.toLocalTime().toNanoOfDay() == 0L &&
            endZdt.toLocalTime().toNanoOfDay() == 0L

        // Whole calendar months: start on the 1st, end on the 1st of a later
        // month. Step by that many whole months so 28/30/31-day months line up.
        if (isMidnightAligned &&
            startDate.dayOfMonth == 1 &&
            endDate.dayOfMonth == 1
        ) {
            val months = monthsBetween(startDate, endDate)
            if (months >= 1) {
                val newStart = startDate.plusMonths(months * dir)
                val newEnd = endDate.plusMonths(months * dir)
                return period.copy(
                    start = newStart.atStartOfDay(zone).toInstant(),
                    end = newEnd.atStartOfDay(zone).toInstant(),
                    preset = null,
                )
            }
        }

        // Whole calendar days: step by that many whole days.
        if (isMidnightAligned) {
            val days = java.time.temporal.ChronoUnit.DAYS.between(startDate, endDate)
            if (days >= 1) {
                val newStart = startDate.plusDays(days * dir)
                val newEnd = endDate.plusDays(days * dir)
                return period.copy(
                    start = newStart.atStartOfDay(zone).toInstant(),
                    end = newEnd.atStartOfDay(zone).toInstant(),
                    preset = null,
                )
            }
        }

        // Arbitrary span: shift by the raw duration.
        val span = period.span()
        val signed = if (forward) span else span.negated()
        return period.copy(
            start = period.start.plus(signed),
            end = period.end.plus(signed),
            preset = null,
        )
    }

    /**
     * The comparison window for compare-to-previous mode: the period immediately
     * preceding [period], same length. Calendar months/days step back by whole
     * calendar units (the same rule as [shift]) so "this month vs last month"
     * compares true calendar months.
     */
    fun compareWindow(period: EnergyPeriod, zone: ZoneId = ZoneId.systemDefault()): EnergyPeriod =
        shift(period.copy(preset = period.preset), forward = false, zone = zone)
            .copy(preset = null, compare = true)

    /**
     * Human title for the period, matching the granularity HA's selector shows:
     * a single day reads as the date; a calendar month/week/year reads as that
     * unit's name; anything else reads as a "start - end" range. Month and
     * weekday names honour [locale].
     */
    fun title(
        period: EnergyPeriod,
        zone: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val startZdt = period.start.atZone(zone)
        val endZdt = period.end.atZone(zone)
        val startDate = startZdt.toLocalDate()
        val endDateInclusive = endZdt.toLocalDate().minusDays(1)
        val midnightAligned = startZdt.toLocalTime().toNanoOfDay() == 0L &&
            endZdt.toLocalTime().toNanoOfDay() == 0L

        if (midnightAligned) {
            // Single calendar day.
            if (endDateInclusive == startDate) {
                return formatDate(startDate, locale)
            }
            // Whole calendar month.
            if (startDate.dayOfMonth == 1 &&
                endDateInclusive == startDate.plusMonths(1).minusDays(1)
            ) {
                val month = startDate.month.getDisplayName(TextStyle.FULL, locale)
                return "$month ${startDate.year}"
            }
            // Whole calendar year.
            if (startDate.dayOfYear == 1 &&
                endDateInclusive == startDate.plusYears(1).minusDays(1)
            ) {
                return startDate.year.toString()
            }
            // ISO week (Monday start, 7 days).
            if (startDate.dayOfWeek == DayOfWeek.MONDAY &&
                java.time.temporal.ChronoUnit.DAYS.between(startDate, endDateInclusive) == 6L
            ) {
                val week = startDate.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                return "Week $week, ${startDate.year}"
            }
        }
        return "${formatDate(startDate, locale)} - ${formatDate(endDateInclusive, locale)}"
    }

    private fun monthsBetween(start: LocalDate, endExclusive: LocalDate): Long =
        java.time.temporal.ChronoUnit.MONTHS.between(start.withDayOfMonth(1), endExclusive.withDayOfMonth(1))

    private fun formatDate(date: LocalDate, locale: Locale): String {
        val month = date.month.getDisplayName(TextStyle.SHORT, locale)
        return "$month ${date.dayOfMonth}, ${date.year}"
    }
}
