package com.github.itskenny0.r1ha.core.ha

import java.time.Instant

/**
 * Catalogue entry returned by HA's `recorder/list_statistic_ids` WS command.
 * Identifies a single time-series HA's recorder is collecting long-term
 * statistics for: usually a sensor, but also energy meters, integration-
 * provided cost trackers, etc.
 *
 * [hasMean] / [hasSum] hint which aggregation types are meaningful: a
 * temperature sensor records hourly means; a kWh meter records cumulative
 * sums (and the recorder derives [change] from successive sums).
 */
data class StatisticId(
    /** Stable id, usually shaped like an entity_id (`sensor.kitchen_temp`)
     *  but can also be `<integration>:<key>` for non-entity statistics. */
    val statisticId: String,
    /** Friendly label; HA falls back to the statistic_id when no name is
     *  configured. */
    val name: String?,
    /** Provider source; typically `recorder` for entity-backed statistics,
     *  or the integration domain for synthetic ones (`energy`, `tibber`). */
    val source: String?,
    /** Unit string the recorder is storing values in, e.g. "°C", "kWh". */
    val unitOfMeasurement: String?,
    /** True when HA records the per-bucket mean/min/max triple. Mostly
     *  numeric sensors with the `measurement` state_class. */
    val hasMean: Boolean,
    /** True when HA records the cumulative sum. Energy meters, gas, water. */
    val hasSum: Boolean,
)

/**
 * One bucket of long-term statistics returned by
 * `recorder/statistics_during_period`. The shape mirrors HA's wire format
 * verbatim: each bucket spans [start]..[end] and carries whichever
 * aggregates the recorder collected for that period.
 *
 * Fields are all nullable because the recorder only fills in the
 * aggregates the source statistic supports (mean/min/max for measurements,
 * sum/state/change for totals).
 */
data class StatisticsBucket(
    val start: Instant,
    val end: Instant,
    /** Arithmetic mean of all samples that fell in this bucket. */
    val mean: Double?,
    val min: Double?,
    val max: Double?,
    /** Cumulative recorder sum at [end]. Monotonic for total statistics. */
    val sum: Double?,
    /** Raw state at [end]. For totals this equals the meter reading. */
    val state: Double?,
    /** Difference between this bucket's sum and the previous bucket's sum;
     *  i.e. consumption during the bucket. HA computes this server-side. */
    val change: Double?,
)
