package com.github.itskenny0.r1ha.core.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AuthThrottleTest {
    /** Mutable fake clock. */
    private class Clock(var now: Long = 0L) { fun millis() = now }

    private fun newThrottle(c: Clock) = AuthThrottle(
        windowMillis = 60_000L,
        failureThreshold = 4,
        baseBackoffMillis = 30_000L,
        maxBackoffMillis = 900_000L,
        clock = c::millis,
    )

    @Test fun closed_by_default_does_not_short_circuit() {
        val t = newThrottle(Clock())
        assertFalse(t.shouldShortCircuit())
    }

    @Test fun opens_after_threshold_failures_in_window() {
        val c = Clock(); val t = newThrottle(c)
        repeat(3) { t.recordAuthFailure() }
        assertFalse(t.shouldShortCircuit()) // 3 < 4
        t.recordAuthFailure()               // 4th -> open
        assertTrue(t.shouldShortCircuit())
    }

    @Test fun failures_outside_window_do_not_count() {
        val c = Clock(); val t = newThrottle(c)
        repeat(3) { t.recordAuthFailure() }
        c.now = 61_000L                     // first 3 now expired
        t.recordAuthFailure()               // only 1 in window
        assertFalse(t.shouldShortCircuit())
    }

    @Test fun success_resets_to_closed() {
        val c = Clock(); val t = newThrottle(c)
        repeat(4) { t.recordAuthFailure() }
        assertTrue(t.shouldShortCircuit())
        t.recordSuccess()
        assertFalse(t.shouldShortCircuit())
    }

    @Test fun half_open_admits_one_probe_then_short_circuits_again() {
        val c = Clock(); val t = newThrottle(c)
        repeat(4) { t.recordAuthFailure() } // open, backoff 30s
        c.now = 30_000L                     // backoff elapsed -> half-open
        assertFalse(t.shouldShortCircuit()) // first call: probe admitted
        assertTrue(t.shouldShortCircuit())  // concurrent call while probe in flight
    }

    @Test fun half_open_failure_reopens_with_longer_backoff() {
        val c = Clock(); val t = newThrottle(c)
        repeat(4) { t.recordAuthFailure() } // open @0, backoff 30s -> openUntil 30s
        c.now = 30_000L
        assertFalse(t.shouldShortCircuit()) // probe admitted (half-open)
        t.recordAuthFailure()               // probe failed -> reopen, backoff 60s
        c.now = 30_000L + 59_000L
        assertTrue(t.shouldShortCircuit())  // still open (60s not elapsed)
        c.now = 30_000L + 60_000L
        assertFalse(t.shouldShortCircuit()) // half-open again
    }

    @Test fun half_open_success_closes() {
        val c = Clock(); val t = newThrottle(c)
        repeat(4) { t.recordAuthFailure() }
        c.now = 30_000L
        assertFalse(t.shouldShortCircuit()) // probe admitted
        t.recordSuccess()
        c.now = 30_001L
        assertFalse(t.shouldShortCircuit()) // closed; normal traffic
    }

    @Test fun backoff_caps() {
        val c = Clock(); val t = newThrottle(c)
        // Drive many reopen cycles; backoff must never exceed maxBackoffMillis.
        repeat(4) { t.recordAuthFailure() }
        var open = 0L
        repeat(10) {
            open += 900_000L
            c.now = open
            t.shouldShortCircuit() // -> half-open
            t.recordAuthFailure()  // reopen
        }
        // After cap, an elapsed 900s must reach half-open.
        c.now = open + 900_000L
        assertFalse(t.shouldShortCircuit())
    }

    @Test fun reset_clears_everything() {
        val c = Clock(); val t = newThrottle(c)
        repeat(4) { t.recordAuthFailure() }
        assertTrue(t.shouldShortCircuit())
        t.reset()
        assertFalse(t.shouldShortCircuit())
    }

    @Test fun applyConfig_lowers_threshold_so_breaker_trips_sooner() {
        val c = Clock(); val t = newThrottle(c) // threshold starts at 4
        t.applyConfig(failureThreshold = 1, baseBackoffMillis = 30_000L)
        t.recordAuthFailure() // now a single failure is enough
        assertTrue(t.shouldShortCircuit())
    }

    @Test fun applyConfig_changes_backoff_length() {
        val c = Clock(); val t = newThrottle(c)
        t.applyConfig(failureThreshold = 1, baseBackoffMillis = 120_000L)
        t.recordAuthFailure()            // open @0, backoff 120s
        c.now = 119_000L
        assertTrue(t.shouldShortCircuit())  // still open
        c.now = 120_000L
        assertFalse(t.shouldShortCircuit()) // half-open once the new, longer backoff elapses
    }

    @Test fun applyConfig_clamps_stray_zero_to_safe_floor() {
        val c = Clock(); val t = newThrottle(c)
        t.applyConfig(failureThreshold = 0, baseBackoffMillis = 0L)
        // threshold floored to 1, so one failure trips; backoff floored to >=1s (not 0 = disabled).
        t.recordAuthFailure()
        assertTrue(t.shouldShortCircuit())
    }
}
