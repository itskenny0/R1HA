package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import kotlin.random.Random

class BackoffPolicyTest {
    @Test fun `doubles and caps at 30 seconds`() {
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(0)).isEqualTo(1_000)
        assertThat(p.delayForAttempt(1)).isEqualTo(2_000)
        assertThat(p.delayForAttempt(2)).isEqualTo(4_000)
        assertThat(p.delayForAttempt(3)).isEqualTo(8_000)
        assertThat(p.delayForAttempt(4)).isEqualTo(16_000)
        assertThat(p.delayForAttempt(5)).isEqualTo(30_000)   // 32k capped to 30k
        assertThat(p.delayForAttempt(20)).isEqualTo(30_000)
    }
    @Test fun `jitter widens the window deterministically with seeded rng`() {
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.25, rng = Random(42))
        val d = p.delayForAttempt(0)
        assertThat(d).isAtLeast(750)
        assertThat(d).isAtMost(1_250)
    }

    @Test fun `huge attempt counts never wrap and still hold the cap`() {
        // A multi-day outage keeps incrementing the attempt counter; the shift
        // clamp must keep the doubled value positive no matter how high it goes.
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(63)).isEqualTo(30_000)
        assertThat(p.delayForAttempt(1_000)).isEqualTo(30_000)
        assertThat(p.delayForAttempt(Int.MAX_VALUE)).isEqualTo(30_000)
    }

    @Test fun `a cap above 256 seconds is still reached with the default base`() {
        // Regression for the old shift clamp (62 - leadingZeros) which plateaued
        // 1 s doublings at 256 s, silently undershooting any larger cap.
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 600_000, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(10)).isEqualTo(600_000)
        assertThat(p.delayForAttempt(1_000)).isEqualTo(600_000)
    }

    @Test fun `near-max base never overflows to a negative or tiny delay`() {
        // Regression for the old shift clamp, which permitted a wrapping shift
        // for any base at or above 2^32 (62 - leadingZeros exceeds the true safe
        // shift there) and turned the delay into garbage.
        val base = Long.MAX_VALUE / 2
        val p = BackoffPolicy(baseMillis = base, capMillis = Long.MAX_VALUE, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(0)).isEqualTo(base)
        assertThat(p.delayForAttempt(50)).isAtLeast(base)
    }

    @Test fun `jitter on a near-max capped delay saturates instead of wrapping to zero`() {
        // capped + positive jitter can exceed Long.MAX_VALUE; a silent wrap would
        // coerce to 0 and turn an outage into a hot reconnect loop.
        val p = BackoffPolicy(
            baseMillis = Long.MAX_VALUE / 2,
            capMillis = Long.MAX_VALUE,
            jitter = 0.25,
            rng = Random(1),
        )
        repeat(50) {
            assertThat(p.delayForAttempt(10)).isGreaterThan(0L)
        }
    }

    @Test fun `negative attempts clamp to the base delay`() {
        val p = BackoffPolicy(baseMillis = 1_000, capMillis = 30_000, jitter = 0.0, rng = Random(0))
        assertThat(p.delayForAttempt(-5)).isEqualTo(1_000)
    }
}
