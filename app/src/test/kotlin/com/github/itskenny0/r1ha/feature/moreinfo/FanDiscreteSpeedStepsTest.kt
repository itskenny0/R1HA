package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for [fanDiscreteSpeedSteps]: verifies that the helper produces evenly-spaced
 * speed percentages always ending at exactly 100 for a variety of percentage_step values
 * reported by HA fan integrations.
 */
class FanDiscreteSpeedStepsTest {

    @Test fun `step 33 produces three evenly-spaced chips ending at 100`() {
        val steps = fanDiscreteSpeedSteps(33.0)
        assertThat(steps).containsExactly(33, 67, 100).inOrder()
    }

    @Test fun `step 33dot33 (one third) produces three evenly-spaced chips ending at 100`() {
        val steps = fanDiscreteSpeedSteps(100.0 / 3.0)
        assertThat(steps).containsExactly(33, 67, 100).inOrder()
    }

    @Test fun `step 25 produces four chips ending exactly at 100`() {
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
        // round(100/200) = 1 step; coerced to 1; round(1 * 100 / 1) = 100.
        assertThat(fanDiscreteSpeedSteps(200.0)).containsExactly(100).inOrder()
    }
}
