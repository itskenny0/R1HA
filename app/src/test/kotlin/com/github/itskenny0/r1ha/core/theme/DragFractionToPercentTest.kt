package com.github.itskenny0.r1ha.core.theme

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [dragFractionToPercent]: the value-bar drag fraction → percent setpoint mapping.
 * The point of interest is that it rounds to the nearest percent (so the set value sits on
 * the finger) instead of truncating low, and clamps to 0..100 at the ends.
 */
class DragFractionToPercentTest {
    @Test fun `ends clamp to 0 and 100`() {
        assertThat(dragFractionToPercent(0f)).isEqualTo(0)
        assertThat(dragFractionToPercent(1f)).isEqualTo(100)
        // Out-of-range fractions (rounding error at the track extremes) still clamp.
        assertThat(dragFractionToPercent(-0.01f)).isEqualTo(0)
        assertThat(dragFractionToPercent(1.01f)).isEqualTo(100)
    }

    @Test fun `rounds to nearest percent rather than truncating`() {
        // Two-thirds drag sets 67, not the truncated 66.
        assertThat(dragFractionToPercent(0.666f)).isEqualTo(67)
        // Just under a whole percent rounds up to it.
        assertThat(dragFractionToPercent(0.495f)).isEqualTo(50)
        // Just over rounds down to it.
        assertThat(dragFractionToPercent(0.504f)).isEqualTo(50)
    }

    @Test fun `exact percents map exactly`() {
        assertThat(dragFractionToPercent(0.25f)).isEqualTo(25)
        assertThat(dragFractionToPercent(0.5f)).isEqualTo(50)
        assertThat(dragFractionToPercent(0.75f)).isEqualTo(75)
    }
}
