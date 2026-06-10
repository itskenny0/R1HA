package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import java.time.Instant

/**
 * Pure merge logic for the history-graph card's long-term statistics backfill.
 *
 * HA fetches hourly statistics (mean / state) and merges them with recorder
 * history so a window longer than the recorder's purge horizon still draws. The
 * recorder keeps only the recent window (default ~10 days); for the earlier part
 * of a long `hours_to_show` range, `/api/history` returns nothing while the
 * statistics table still has hourly buckets.
 *
 * [mergeHistoryWithStatistics] fills the gap before the first recorder point with
 * statistics buckets (one synthesised [HistoryPoint] per bucket, using the
 * bucket's mean, or its state when no mean), then appends the recorder history
 * verbatim. Buckets at or after the first recorder point are dropped so the
 * finer-grained recorder data wins in the overlap. When there is no recorder
 * history at all, every in-window bucket is used.
 */
fun mergeHistoryWithStatistics(
    history: List<HistoryPoint>,
    buckets: List<StatisticsBucket>,
    windowStart: Instant,
): List<HistoryPoint> {
    if (buckets.isEmpty()) return history
    // The earliest recorder point bounds where statistics stop contributing.
    val firstRecorded = history.minByOrNull { it.timestamp }?.timestamp
    val backfill = buckets
        .asSequence()
        .filter { !it.start.isBefore(windowStart) }
        .filter { firstRecorded == null || it.start.isBefore(firstRecorded) }
        .mapNotNull { bucket ->
            val value = bucket.mean ?: bucket.state ?: return@mapNotNull null
            HistoryPoint(timestamp = bucket.start, state = value.toString(), numeric = value)
        }
        .toList()
    if (backfill.isEmpty()) return history
    return (backfill + history).sortedBy { it.timestamp }
}

/**
 * Whether a series needs a statistics backfill: it is numeric (has at least one
 * numeric point or is empty) and its earliest point starts meaningfully after the
 * requested [windowStart], implying the recorder purged the early window. A
 * [gapMillis] grace (default 1 h) avoids fetching statistics for a series that
 * simply has no early activity.
 */
fun needsStatisticsBackfill(
    history: List<HistoryPoint>,
    windowStart: Instant,
    gapMillis: Long = 3_600_000L,
): Boolean {
    val firstNumeric = history.firstOrNull { it.numeric != null }?.timestamp
        ?: return history.isEmpty() // empty numeric series: try the backfill
    return firstNumeric.toEpochMilli() - windowStart.toEpochMilli() > gapMillis
}
