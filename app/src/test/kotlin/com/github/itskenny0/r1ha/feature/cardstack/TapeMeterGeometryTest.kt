package com.github.itskenny0.r1ha.feature.cardstack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in the tick-label → percent mapping the tape meters use. The central worry was
 * that tapping a non-0..100 domain tick (e.g. climate "30°") would mis-jump because the
 * label index isn't a percent. It isn't a mis-jump: the meter labels are laid out at
 * fractions 1.0 … 0.0 of min..max and the setter maps percent linearly back onto the same
 * min..max, so the index→percent conversion below is the exact inverse of the label layout.
 */
class TapeMeterGeometryTest {
    @Test fun `vertical top tick is 100 and bottom is 0`() {
        // Five labels top→bottom: 30°,25°,20°,15°,9° for a 9..30 climate range.
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 0, count = 5)).isEqualTo(100)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 4, count = 5)).isEqualTo(0)
    }

    @Test fun `vertical middle ticks are evenly spaced`() {
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 1, count = 5)).isEqualTo(75)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 2, count = 5)).isEqualTo(50)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 3, count = 5)).isEqualTo(25)
    }

    @Test fun `horizontal mirrors vertical low-to-high left-to-right`() {
        // Left tick = lowest value = 0 percent; right tick = highest = 100 percent.
        assertThat(TapeMeterGeometry.horizontalTickPercent(idx = 0, count = 5)).isEqualTo(0)
        assertThat(TapeMeterGeometry.horizontalTickPercent(idx = 2, count = 5)).isEqualTo(50)
        assertThat(TapeMeterGeometry.horizontalTickPercent(idx = 4, count = 5)).isEqualTo(100)
    }

    @Test fun `climate jump lands on the tapped native value`() {
        // Round trip for a 9..30 climate range: the percent a tick maps to, fed through
        // the same linear percent→native conversion the VM uses, must reproduce the
        // native value that tick's label was generated from (frac of min..max).
        val min = 9.0
        val max = 30.0
        val fracs = listOf(1.0, 0.75, 0.5, 0.25, 0.0) // top→bottom label fractions
        fracs.forEachIndexed { idx, frac ->
            val pct = TapeMeterGeometry.verticalTickPercent(idx, fracs.size)
            val nativeFromPct = min + (pct / 100.0) * (max - min)
            val nativeFromLabel = min + frac * (max - min)
            assertThat(nativeFromPct).isWithin(1e-6).of(nativeFromLabel)
        }
    }

    @Test fun `fractional-spacing ticks round to nearest percent, not truncate`() {
        // count = 4 spaces ticks at 100, 66.67, 33.33, 0. The middle ticks must round
        // (67 / 33), not truncate (66 / 33): truncation biased the 66.67% tick low.
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 0, count = 4)).isEqualTo(100)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 1, count = 4)).isEqualTo(67)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 2, count = 4)).isEqualTo(33)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 3, count = 4)).isEqualTo(0)
        // count = 8 spaces ticks at multiples of 100/7 ≈ 14.29. Left→right rounded.
        assertThat((0 until 8).map { TapeMeterGeometry.horizontalTickPercent(it, 8) })
            .containsExactly(0, 14, 29, 43, 57, 71, 86, 100)
            .inOrder()
    }

    @Test fun `every tick is the closest whole percent to its native fraction`() {
        // Property: for any tick count, the returned percent is within half a percent of
        // the tick's true fraction-of-range, for both orientations. This is what makes a
        // tap land on (or nearest to) the label's own value.
        for (count in 2..12) {
            for (idx in 0 until count) {
                val trueVPct = 100.0 * (count - 1 - idx) / (count - 1)
                assertThat(TapeMeterGeometry.verticalTickPercent(idx, count).toDouble())
                    .isWithin(0.5).of(trueVPct)
                val trueHPct = 100.0 * idx / (count - 1)
                assertThat(TapeMeterGeometry.horizontalTickPercent(idx, count).toDouble())
                    .isWithin(0.5).of(trueHPct)
            }
        }
    }

    @Test fun `degenerate single tick collapses to 100`() {
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 0, count = 1)).isEqualTo(100)
        assertThat(TapeMeterGeometry.horizontalTickPercent(idx = 0, count = 1)).isEqualTo(100)
        assertThat(TapeMeterGeometry.verticalTickPercent(idx = 0, count = 0)).isEqualTo(100)
    }
}
