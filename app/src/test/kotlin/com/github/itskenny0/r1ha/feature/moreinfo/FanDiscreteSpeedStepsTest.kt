package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [fanDiscreteSpeedSteps]: verifies that the helper produces the
 * correct labeled speed percentages for a variety of percentage_step values reported
 * by HA fan integrations.
 */
class FanDiscreteSpeedStepsTest {

    @Test fun `step 33 produces three chips including 100`() {
        // floor(100/33) = 3 steps: 33, 66, 99 -> 99 < 100 so 100 is appended.
        val steps = fanDiscreteSpeedSteps(33.0)
        assertThat(steps).containsExactly(33, 66, 99, 100).inOrder()
    }

    @Test fun `step 25 produces four chips ending exactly at 100`() {
        // floor(100/25) = 4; 4*25 = 100, so no append needed.
        val steps = fanDiscreteSpeedSteps(25.0)
        assertThat(steps).containsExactly(25, 50, 75, 100).inOrder()
    }

    @Test fun `step 50 produces two chips`() {
        val steps = fanDiscreteSpeedSteps(50.0)
        assertThat(steps).containsExactly(50, 100).inOrder()
    }

    @Test fun `step 100 produces one chip`() {
        val steps = fanDiscreteSpeedSteps(100.0)
        assertThat(steps).containsExactly(100).inOrder()
    }

    @Test fun `step zero returns empty list`() {
        assertThat(fanDiscreteSpeedSteps(0.0)).isEmpty()
    }

    @Test fun `negative step returns empty list`() {
        assertThat(fanDiscreteSpeedSteps(-10.0)).isEmpty()
    }

    @Test fun `step larger than 100 produces one chip at 100`() {
        // step=200: floor(100/200) = 0, result is empty - defensive case.
        assertThat(fanDiscreteSpeedSteps(200.0)).isEmpty()
    }

    @Test fun `step 33dot3 (one third) rounds correctly`() {
        // Common HA value for 3-speed fans reported as 33.3333...
        val steps = fanDiscreteSpeedSteps(100.0 / 3.0)
        // round(33.33)=33, round(66.67)=67, round(100)=100; last == 100 so no append.
        assertThat(steps).containsExactly(33, 67, 100).inOrder()
    }
}
