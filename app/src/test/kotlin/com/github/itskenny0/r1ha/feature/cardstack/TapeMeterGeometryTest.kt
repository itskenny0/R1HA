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

    // ── verticalMeterHeightPx: the wrap-mode "span the body, floor at the band" rule ──

    @Test fun `tall body wins so the meter spans the full card`() {
        // A light with brightness + scene/effect rows measures ~tall; the meter takes the
        // body height so the slider reaches the bottom of the card.
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = 900, floorPx = 480))
            .isEqualTo(900)
    }

    @Test fun `short body floors at the band so the seekbar stays usable`() {
        // A bare meter card measures short; the meter keeps the floor band rather than
        // shrinking to an unusable nub.
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = 300, floorPx = 480))
            .isEqualTo(480)
    }

    @Test fun `equal body and floor resolve to that height`() {
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = 480, floorPx = 480))
            .isEqualTo(480)
    }

    @Test fun `zero or negative measurement never collapses the meter to zero`() {
        // The whole point of the floor: a degenerate measurement must not yield a
        // zero-height (invisible) seekbar. Floored at floorPx, and at 1 even if the floor
        // were somehow zero.
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = 0, floorPx = 480))
            .isEqualTo(480)
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = -10, floorPx = 0))
            .isEqualTo(1)
        assertThat(TapeMeterGeometry.verticalMeterHeightPx(bodyHeightPx = 0, floorPx = 0))
            .isEqualTo(1)
    }
}
