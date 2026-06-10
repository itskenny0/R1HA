package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Locks in the statistic-card period resolution: calendar (day/week/month/year
 * with offsets), fixed_period, rolling_window, the bucket-size choice, and the
 * stat-type column selection. All anchored on a fixed UTC instant so the
 * boundary arithmetic is deterministic.
 */
class StatisticPeriodEngineTest {

    private val utc = ZoneOffset.UTC
    // 2026-06-10T12:00:00Z (a Wednesday).
    private val now = Instant.parse("2026-06-10T12:00:00Z")

    @Test fun `calendar day offset 0 spans today midnight to midnight`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("day", 0), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-06-11T00:00:00Z"))
        assertThat(w.bucket).isEqualTo("hour")
    }

    @Test fun `calendar day offset 1 spans yesterday`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("day", 1), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-06-09T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-06-10T00:00:00Z"))
    }

    @Test fun `calendar week starts Monday`() {
        // 2026-06-10 is a Wednesday; ISO week start is Monday 2026-06-08.
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("week", 0), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-06-08T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-06-15T00:00:00Z"))
        assertThat(w.bucket).isEqualTo("day")
    }

    @Test fun `calendar week offset 1 is the previous week`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("week", 1), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-06-08T00:00:00Z"))
    }

    @Test fun `calendar month spans the whole month`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("month", 0), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-07-01T00:00:00Z"))
        assertThat(w.bucket).isEqualTo("day")
    }

    @Test fun `calendar month offset 1 is the previous month`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("month", 1), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-05-01T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2026-06-01T00:00:00Z"))
    }

    @Test fun `calendar year spans the whole year with month buckets`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.Calendar("year", 0), now, utc)
        assertThat(w.start).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"))
        assertThat(w.end).isEqualTo(Instant.parse("2027-01-01T00:00:00Z"))
        assertThat(w.bucket).isEqualTo("month")
    }

    @Test fun `fixed period uses explicit bounds`() {
        val start = Instant.parse("2026-01-01T00:00:00Z")
        val end = Instant.parse("2026-01-08T00:00:00Z")
        val w = resolveStatisticWindow(
            StatisticPeriodSpec.Fixed(start.toEpochMilli(), end.toEpochMilli()),
            now,
            utc,
        )
        assertThat(w.start).isEqualTo(start)
        assertThat(w.end).isEqualTo(end)
        assertThat(w.bucket).isEqualTo("day") // 7-day span
    }

    @Test fun `rolling window ends now and spans the duration`() {
        val w = resolveStatisticWindow(
            StatisticPeriodSpec.Rolling(Duration.ofDays(7).toMillis()),
            now,
            utc,
        )
        assertThat(w.end).isEqualTo(now)
        assertThat(w.start).isEqualTo(now.minus(Duration.ofDays(7)))
    }

    @Test fun `rolling window honours the offset shift`() {
        val w = resolveStatisticWindow(
            StatisticPeriodSpec.Rolling(
                durationMillis = Duration.ofDays(1).toMillis(),
                offsetMillis = Duration.ofDays(1).toMillis(),
            ),
            now,
            utc,
        )
        // Yesterday's 24h window: ends now-1d, starts now-2d.
        assertThat(w.end).isEqualTo(now.minus(Duration.ofDays(1)))
        assertThat(w.start).isEqualTo(now.minus(Duration.ofDays(2)))
    }

    @Test fun `bucket choice scales with span`() {
        assertThat(bucketForSpan(Duration.ofHours(12))).isEqualTo("hour")
        assertThat(bucketForSpan(Duration.ofDays(7))).isEqualTo("day")
        assertThat(bucketForSpan(Duration.ofDays(60))).isEqualTo("week")
        assertThat(bucketForSpan(Duration.ofDays(400))).isEqualTo("month")
    }

    @Test fun `stat column selection maps each stat type to its column`() {
        assertThat(selectStatColumn("mean", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(1.0)
        assertThat(selectStatColumn("min", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(2.0)
        assertThat(selectStatColumn("max", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(3.0)
        assertThat(selectStatColumn("sum", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(4.0)
        assertThat(selectStatColumn("state", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(5.0)
        assertThat(selectStatColumn("change", 1.0, 2.0, 3.0, 4.0, 5.0, 6.0)).isEqualTo(6.0)
    }

    @Test fun `stat column unknown falls back to mean then state`() {
        assertThat(selectStatColumn("bogus", null, null, null, null, 9.0, null)).isEqualTo(9.0)
        assertThat(selectStatColumn("bogus", 7.0, null, null, null, 9.0, null)).isEqualTo(7.0)
    }

    @Test fun `default spec is a seven day rolling window`() {
        val w = resolveStatisticWindow(StatisticPeriodSpec.DEFAULT, now, utc)
        assertThat(Duration.between(w.start, w.end)).isEqualTo(Duration.ofDays(7))
    }
}
