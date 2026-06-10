package com.github.itskenny0.r1ha.feature.dashboards.cards.energy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * Locks in the shared energy-period model: preset resolution to concrete
 * `[start, end)` windows, the hour-0 default fallback, chevron shifting by the
 * window's own span (calendar-aware for whole months/days), the compare window,
 * and period-title rendering. Anchored on a fixed UTC instant so the boundary
 * arithmetic is deterministic.
 */
class EnergyPeriodTest {

    private val utc = ZoneOffset.UTC
    // Wed 2024-06-12 14:30 UTC.
    private val now = Instant.parse("2024-06-12T14:30:00Z")

    @Test fun `today snaps to local midnight boundaries`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-12T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-06-13T00:00:00Z"))
        assertThat(p.preset).isEqualTo(EnergyDateRange.TODAY)
        assertThat(p.span()).isEqualTo(Duration.ofDays(1))
    }

    @Test fun `yesterday is the prior calendar day`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.YESTERDAY, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-11T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-06-12T00:00:00Z"))
    }

    @Test fun `this week starts monday`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_WEEK, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-10T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-06-17T00:00:00Z"))
    }

    @Test fun `this month spans full calendar month`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_MONTH, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-01T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-07-01T00:00:00Z"))
    }

    @Test fun `this quarter is q2`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_QUARTER, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-04-01T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-07-01T00:00:00Z"))
    }

    @Test fun `this year spans full year`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_YEAR, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-01-01T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2025-01-01T00:00:00Z"))
    }

    @Test fun `last 7 days ends at start of today`() {
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.LAST_7_DAYS, now, utc)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-05T00:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-06-12T00:00:00Z"))
        assertThat(p.span()).isEqualTo(Duration.ofDays(7))
    }

    @Test fun `default period is today during day`() {
        assertThat(EnergyPeriodEngine.defaultPeriod(now, utc).preset).isEqualTo(EnergyDateRange.TODAY)
    }

    @Test fun `default period falls back to yesterday at hour zero`() {
        val midnightish = Instant.parse("2024-06-12T00:15:00Z")
        assertThat(EnergyPeriodEngine.defaultPeriod(midnightish, utc).preset)
            .isEqualTo(EnergyDateRange.YESTERDAY)
    }

    @Test fun `shift back on today gives yesterday window and clears preset`() {
        val today = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, utc)
        val prev = EnergyPeriodEngine.shift(today, forward = false, zone = utc)
        assertThat(prev.start).isEqualTo(Instant.parse("2024-06-11T00:00:00Z"))
        assertThat(prev.end).isEqualTo(Instant.parse("2024-06-12T00:00:00Z"))
        assertThat(prev.preset).isNull()
    }

    @Test fun `shift forward then back is identity for day`() {
        val today = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, utc)
        val round = EnergyPeriodEngine.shift(
            EnergyPeriodEngine.shift(today, forward = true, zone = utc),
            forward = false, zone = utc,
        )
        assertThat(round.start).isEqualTo(today.start)
        assertThat(round.end).isEqualTo(today.end)
    }

    @Test fun `shift back on month steps to prior calendar month`() {
        val june = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_MONTH, now, utc)
        val may = EnergyPeriodEngine.shift(june, forward = false, zone = utc)
        assertThat(may.start).isEqualTo(Instant.parse("2024-05-01T00:00:00Z"))
        assertThat(may.end).isEqualTo(Instant.parse("2024-06-01T00:00:00Z"))
    }

    @Test fun `shift forward on month steps to next calendar month`() {
        val june = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_MONTH, now, utc)
        val july = EnergyPeriodEngine.shift(june, forward = true, zone = utc)
        assertThat(july.start).isEqualTo(Instant.parse("2024-07-01T00:00:00Z"))
        assertThat(july.end).isEqualTo(Instant.parse("2024-08-01T00:00:00Z"))
    }

    @Test fun `shift week steps by seven days`() {
        val week = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_WEEK, now, utc)
        val prevWeek = EnergyPeriodEngine.shift(week, forward = false, zone = utc)
        assertThat(prevWeek.start).isEqualTo(Instant.parse("2024-06-03T00:00:00Z"))
        assertThat(prevWeek.end).isEqualTo(Instant.parse("2024-06-10T00:00:00Z"))
    }

    @Test fun `compare window is prior span with compare flag`() {
        val today = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, utc)
        val cmp = EnergyPeriodEngine.compareWindow(today, utc)
        assertThat(cmp.start).isEqualTo(Instant.parse("2024-06-11T00:00:00Z"))
        assertThat(cmp.end).isEqualTo(Instant.parse("2024-06-12T00:00:00Z"))
        assertThat(cmp.compare).isTrue()
    }

    @Test fun `compare window for month is prior calendar month`() {
        val june = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_MONTH, now, utc)
        val cmp = EnergyPeriodEngine.compareWindow(june, utc)
        assertThat(cmp.start).isEqualTo(Instant.parse("2024-05-01T00:00:00Z"))
        assertThat(cmp.end).isEqualTo(Instant.parse("2024-06-01T00:00:00Z"))
    }

    @Test fun `title single day`() {
        val today = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, utc)
        assertThat(EnergyPeriodEngine.title(today, utc, Locale.US)).isEqualTo("Jun 12, 2024")
    }

    @Test fun `title full month`() {
        val june = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_MONTH, now, utc)
        assertThat(EnergyPeriodEngine.title(june, utc, Locale.US)).isEqualTo("June 2024")
    }

    @Test fun `title full year`() {
        val year = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_YEAR, now, utc)
        assertThat(EnergyPeriodEngine.title(year, utc, Locale.US)).isEqualTo("2024")
    }

    @Test fun `title week`() {
        val week = EnergyPeriodEngine.resolve(EnergyDateRange.THIS_WEEK, now, utc)
        assertThat(EnergyPeriodEngine.title(week, utc, Locale.US)).isEqualTo("Week 24, 2024")
    }

    @Test fun `title arbitrary range`() {
        val last7 = EnergyPeriodEngine.resolve(EnergyDateRange.LAST_7_DAYS, now, utc)
        assertThat(EnergyPeriodEngine.title(last7, utc, Locale.US))
            .isEqualTo("Jun 5, 2024 - Jun 11, 2024")
    }

    @Test fun `default collection key matches HA`() {
        assertThat(EnergyPeriodEngine.DEFAULT_COLLECTION_KEY).isEqualTo("energy_date_selection")
    }

    @Test fun `zone offset shifts day boundary`() {
        val plus2 = ZoneId.of("+02:00")
        val p = EnergyPeriodEngine.resolve(EnergyDateRange.TODAY, now, plus2)
        assertThat(p.start).isEqualTo(Instant.parse("2024-06-11T22:00:00Z"))
        assertThat(p.end).isEqualTo(Instant.parse("2024-06-12T22:00:00Z"))
    }
}
