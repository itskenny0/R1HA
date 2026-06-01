package com.github.itskenny0.r1ha.core.ha

import com.github.itskenny0.r1ha.core.prefs.ConnectionSettings
import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTuningTest {

    @Test fun non_strict_protects_without_slowing_anything() {
        // Defaults: strict off. The always-on dials are tightened (one at a time, trip on the
        // first failure) but the freshness-cost dials stay at their snappy non-strict defaults
        // REGARDLESS of the stored strict-only values, so a normal user pays no slowdown.
        val t = ConnectionTuning.from(ConnectionSettings())
        assertEquals(1, t.maxConcurrentRequests)
        assertEquals(1, t.breakerFailureThreshold)
        assertEquals(ConnectionTuning.DEFAULT_COOLDOWN_MILLIS, t.breakerCooldownMillis)
        assertEquals(ConnectionTuning.DEFAULT_MAX_AUTH_RETRIES, t.maxAuthRetries)
        assertEquals(0L, t.minCameraIntervalMillis)   // no camera floor
        assertEquals(1, t.backgroundRefreshMultiplier) // background unchanged
    }

    @Test fun strict_applies_the_slowdown_dials() {
        val t = ConnectionTuning.from(
            ConnectionSettings(
                strictMode = true,
                maxConcurrentRequests = 2,
                breakerFailureThreshold = 3,
                breakerCooldownSec = 90,
                maxAuthRetries = 1,
                minCameraRefreshSec = 30,
                backgroundRefreshMultiplier = 3,
            ),
        )
        assertEquals(2, t.maxConcurrentRequests)
        assertEquals(3, t.breakerFailureThreshold)
        assertEquals(90_000L, t.breakerCooldownMillis)
        assertEquals(1, t.maxAuthRetries)
        assertEquals(30_000L, t.minCameraIntervalMillis)
        assertEquals(3, t.backgroundRefreshMultiplier)
    }

    @Test fun values_are_clamped_to_safe_ranges() {
        val t = ConnectionTuning.from(
            ConnectionSettings(
                strictMode = true,
                maxConcurrentRequests = 99,
                breakerFailureThreshold = 0,
                breakerCooldownSec = 100_000,
                maxAuthRetries = 0,
                minCameraRefreshSec = 9_999,
                backgroundRefreshMultiplier = 50,
            ),
        )
        assertEquals(8, t.maxConcurrentRequests)
        assertEquals(1, t.breakerFailureThreshold)
        assertEquals(900_000L, t.breakerCooldownMillis)
        assertEquals(1, t.maxAuthRetries)
        assertEquals(120_000L, t.minCameraIntervalMillis)
        assertEquals(6, t.backgroundRefreshMultiplier)
    }

    @Test fun camera_floor_only_raises_never_lowers() {
        val strict = ConnectionTuning.from(ConnectionSettings(strictMode = true, minCameraRefreshSec = 20))
        // A camera already polling slower than the floor keeps its own (slower) cadence.
        assertEquals(45, strict.flooredCameraSeconds(45))
        // A camera polling faster than the floor is raised to it.
        assertEquals(20, strict.flooredCameraSeconds(4))
        // Non-strict: no floor, cadence untouched.
        val lax = ConnectionTuning.from(ConnectionSettings(minCameraRefreshSec = 20))
        assertEquals(4, lax.flooredCameraSeconds(4))
    }

    @Test fun background_scaling_multiplies() {
        val strict = ConnectionTuning.from(ConnectionSettings(strictMode = true, backgroundRefreshMultiplier = 3))
        assertEquals(90_000L, strict.scaleBackground(30_000L))
        val lax = ConnectionTuning.from(ConnectionSettings(backgroundRefreshMultiplier = 3))
        assertEquals(30_000L, lax.scaleBackground(30_000L))
    }
}
