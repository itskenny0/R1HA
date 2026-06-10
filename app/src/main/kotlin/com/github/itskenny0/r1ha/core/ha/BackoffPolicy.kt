package com.github.itskenny0.r1ha.core.ha

import kotlin.math.min
import kotlin.random.Random

/** Exponential-backoff schedule with ±jitter. Pure function for easy testing. */
data class BackoffPolicy(
    val baseMillis: Long = 1_000,
    val capMillis: Long = 30_000,
    val jitter: Double = 0.25,
    val rng: Random = Random.Default,
) {
    fun delayForAttempt(attempt: Int): Long {
        // shl on Long wraps silently once the top set bit shifts past position 62, so an
        // unbounded attempt counter (a multi-day outage keeps incrementing it) would turn
        // the delay negative or tiny. The largest safe shift keeps the top bit at 62:
        // that's leading-zero-count(baseMillis) - 1. Past the clamp the raw value simply
        // stops doubling and min(raw, capMillis) below still applies the configured
        // ceiling. coerceAtLeast(0) covers the baseMillis = 0 degenerate case (nlz = 64).
        val safeShift = attempt.coerceIn(
            0,
            (java.lang.Long.numberOfLeadingZeros(baseMillis.coerceAtLeast(1L)) - 1).coerceAtLeast(0),
        )
        val raw = baseMillis shl safeShift
        val capped = min(raw, capMillis)
        if (jitter == 0.0) return capped
        val window = (capped * jitter).toLong()
        val delta = if (window == 0L) 0L else rng.nextLong(-window, window + 1)
        // capped + delta can itself exceed Long.MAX_VALUE when the capped delay sits near
        // the top of the range and the jitter draw is positive; a silent wrap would coerce
        // to 0 and turn the outage into a hot reconnect loop, so saturate instead.
        val jittered = capped + delta
        return if (delta > 0 && jittered < 0) Long.MAX_VALUE else jittered.coerceAtLeast(0)
    }
}
