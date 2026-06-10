package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class HistoryStatBackfillTest {

    private val windowStart = Instant.parse("2026-06-01T00:00:00Z")

    private fun hp(at: String, value: Double) =
        HistoryPoint(timestamp = Instant.parse(at), state = value.toString(), numeric = value)

    private fun bucket(at: String, mean: Double?) = StatisticsBucket(
        start = Instant.parse(at),
        end = Instant.parse(at).plusSeconds(3600),
        mean = mean,
        min = null, max = null, sum = null, state = mean, change = null,
    )

    @Test fun `no buckets returns history unchanged`() {
        val history = listOf(hp("2026-06-05T00:00:00Z", 1.0))
        assertThat(mergeHistoryWithStatistics(history, emptyList(), windowStart)).isEqualTo(history)
    }

    @Test fun `buckets before the first recorder point backfill the gap`() {
        val history = listOf(hp("2026-06-05T00:00:00Z", 10.0))
        val stats = listOf(
            bucket("2026-06-01T00:00:00Z", 2.0),
            bucket("2026-06-02T00:00:00Z", 3.0),
            // This bucket overlaps the recorder window and is dropped.
            bucket("2026-06-06T00:00:00Z", 99.0),
        )
        val merged = mergeHistoryWithStatistics(history, stats, windowStart)
        assertThat(merged.map { it.numeric }).containsExactly(2.0, 3.0, 10.0).inOrder()
    }

    @Test fun `buckets before the window start are excluded`() {
        val history = listOf(hp("2026-06-05T00:00:00Z", 10.0))
        val stats = listOf(
            bucket("2026-05-30T00:00:00Z", 1.0), // before window
            bucket("2026-06-02T00:00:00Z", 3.0),
        )
        val merged = mergeHistoryWithStatistics(history, stats, windowStart)
        assertThat(merged.map { it.numeric }).containsExactly(3.0, 10.0).inOrder()
    }

    @Test fun `empty recorder history uses every in-window bucket`() {
        val stats = listOf(bucket("2026-06-02T00:00:00Z", 5.0), bucket("2026-06-03T00:00:00Z", 6.0))
        val merged = mergeHistoryWithStatistics(emptyList(), stats, windowStart)
        assertThat(merged.map { it.numeric }).containsExactly(5.0, 6.0).inOrder()
    }

    @Test fun `needsBackfill true when the early window has no recorder data`() {
        // First numeric point is 4 days after window start: recorder purged early window.
        val history = listOf(hp("2026-06-05T00:00:00Z", 1.0))
        assertThat(needsStatisticsBackfill(history, windowStart)).isTrue()
    }

    @Test fun `needsBackfill false when recorder covers the window start`() {
        val history = listOf(hp("2026-06-01T00:30:00Z", 1.0))
        assertThat(needsStatisticsBackfill(history, windowStart)).isFalse()
    }

    @Test fun `needsBackfill true for an empty numeric series`() {
        assertThat(needsStatisticsBackfill(emptyList(), windowStart)).isTrue()
    }
}
