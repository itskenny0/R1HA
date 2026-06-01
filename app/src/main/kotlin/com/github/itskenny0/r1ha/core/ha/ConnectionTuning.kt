package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.ConnectionSettings

/**
 * Effective, clamped runtime values derived from [ConnectionSettings]. Keeping the mapping in one
 * pure place means every consumer (the breaker, the OkHttp gate, the camera poller, the REST
 * heartbeat, the auth-recovery loop) agrees on what a given settings struct actually means, and
 * the gating rule "slowdown dials only bite in strict mode" lives in exactly one spot.
 *
 * The split: the two dials with negligible UX cost ([maxConcurrentRequests],
 * [breakerFailureThreshold]) apply on EVERY install, so a normal user is protected from the ban
 * path without slowing anything down. The four dials that trade freshness or recovery speed for
 * fewer requests ([breakerCooldownMillis], [maxAuthRetries], [minCameraIntervalMillis],
 * [backgroundRefreshMultiplier]) only deviate from their snappy defaults when strict mode is on.
 *
 * @property maxConcurrentRequests gate cap, 1..8. Always honoured.
 * @property breakerFailureThreshold 401s-in-window to trip, 1..10. Always honoured.
 * @property breakerCooldownMillis first breaker backoff. [DEFAULT_COOLDOWN_MILLIS] (snappy
 *           recovery) unless strict mode is on, otherwise the user value clamped to 5s..900s.
 * @property maxAuthRetries auth-recovery attempt cap. [DEFAULT_MAX_AUTH_RETRIES] unless strict
 *           mode is on, otherwise the user value clamped to 1..10.
 * @property minCameraIntervalMillis floor on every camera poll interval. 0 (no floor) unless
 *           strict mode is on.
 * @property backgroundRefreshMultiplier scales background polling cadences. 1 (unchanged) unless
 *           strict mode is on, otherwise 1..6.
 */
data class ConnectionTuning(
    val maxConcurrentRequests: Int,
    val breakerFailureThreshold: Int,
    val breakerCooldownMillis: Long,
    val maxAuthRetries: Int,
    val minCameraIntervalMillis: Long,
    val backgroundRefreshMultiplier: Int,
) {
    /** Scale a background poll interval (millis) by the strict-mode multiplier. */
    fun scaleBackground(baseMillis: Long): Long = baseMillis * backgroundRefreshMultiplier

    /** Apply the strict-mode floor (if any) to a camera poll interval given in seconds. */
    fun flooredCameraSeconds(configuredSeconds: Int): Int {
        if (minCameraIntervalMillis <= 0L) return configuredSeconds
        val floorSec = (minCameraIntervalMillis / 1_000L).toInt()
        return configuredSeconds.coerceAtLeast(floorSec)
    }

    companion object {
        /** Matches the repository's historical AuthLost retry cap. */
        const val DEFAULT_MAX_AUTH_RETRIES = 3

        /** Snappy non-strict breaker cooldown (the historical [AuthThrottle] base backoff), so a
         *  normal user recovers from a transient blip in seconds, not the strict-mode minute. */
        const val DEFAULT_COOLDOWN_MILLIS = 15_000L

        fun from(c: ConnectionSettings): ConnectionTuning = ConnectionTuning(
            // Negligible-cost dials: always honoured so every install is protected.
            maxConcurrentRequests = c.maxConcurrentRequests.coerceIn(1, 8),
            breakerFailureThreshold = c.breakerFailureThreshold.coerceIn(1, 10),
            // Freshness / recovery-cost dials: only deviate from the snappy defaults in strict mode.
            breakerCooldownMillis = if (c.strictMode) c.breakerCooldownSec.coerceIn(5, 900) * 1_000L else DEFAULT_COOLDOWN_MILLIS,
            maxAuthRetries = if (c.strictMode) c.maxAuthRetries.coerceIn(1, 10) else DEFAULT_MAX_AUTH_RETRIES,
            minCameraIntervalMillis = if (c.strictMode) c.minCameraRefreshSec.coerceIn(0, 120) * 1_000L else 0L,
            backgroundRefreshMultiplier = if (c.strictMode) c.backgroundRefreshMultiplier.coerceIn(1, 6) else 1,
        )
    }
}
