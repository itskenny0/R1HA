package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [chartYFraction]: the sensor-history chart's value -> vertical-position mapping,
 * including the flat-line centring and out-of-band clamping.
 */
class ChartYFractionTest {
    @Test fun `maps value linearly within the band`() {
        assertThat(chartYFraction(0.0, 0.0, 10.0)).isWithin(1e-6f).of(0f)
        assertThat(chartYFraction(5.0, 0.0, 10.0)).isWithin(1e-6f).of(0.5f)
        assertThat(chartYFraction(10.0, 0.0, 10.0)).isWithin(1e-6f).of(1f)
    }

    @Test fun `flat band centres the line at 0point5 instead of pinning to the bottom`() {
        // min == max (a constant reading) used to give 0 -> bottom edge; it must centre.
        assertThat(chartYFraction(21.0, 21.0, 21.0)).isEqualTo(0.5f)
        // Sub-epsilon band counts as flat too.
        assertThat(chartYFraction(21.0, 21.0, 21.0 + 1e-12)).isEqualTo(0.5f)
    }

    @Test fun `out-of-band values clamp to the plot`() {
        assertThat(chartYFraction(-5.0, 0.0, 10.0)).isEqualTo(0f)
        assertThat(chartYFraction(15.0, 0.0, 10.0)).isEqualTo(1f)
    }
}
