package com.github.itskenny0.r1ha.feature.history

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Locks in the shared-axis multi-series projection used by the History overlay.
 * The core worries: (1) each series normalizes to its OWN min/max so differing
 * units overlay legibly, (2) the time axis is the union of all series so lines
 * align in time, (3) series with < 2 numeric points drop out without blocking
 * the rest, (4) nearest-sample scrubbing picks the right index.
 */
class MultiProjectionTest {

    private fun pt(epochSec: Long, value: Double?) = HistoryPoint(
        timestamp = Instant.ofEpochSecond(epochSec),
        state = value?.toString() ?: "unknown",
        numeric = value,
    )

    private fun series(id: String, color: Int, vararg pts: HistoryPoint) =
        HistoryViewModel.Series(
            entityId = EntityId(id),
            colorIndex = color,
            points = pts.toList(),
            displayName = id,
        )

    @Test fun `null when no series has two numeric points`() {
        val s = series("sensor.a", 0, pt(0, 1.0))
        assertThat(buildMultiProjection(listOf(s))).isNull()
    }

    @Test fun `single series normalizes y to its own min and max`() {
        val s = series("sensor.temp", 0, pt(0, 10.0), pt(100, 20.0), pt(200, 30.0))
        val multi = buildMultiProjection(listOf(s))!!
        val proj = multi.series.single()
        assertThat(proj.yMin).isEqualTo(10.0)
        assertThat(proj.yMax).isEqualTo(30.0)
        // y is inverted (1f = bottom / min, 0f = top / max).
        assertThat(proj.ysNorm.first()).isWithin(1e-4f).of(1f)
        assertThat(proj.ysNorm.last()).isWithin(1e-4f).of(0f)
        assertThat(proj.ysNorm[1]).isWithin(1e-4f).of(0.5f)
    }

    @Test fun `two series each keep their own vertical scale`() {
        // Temperature 10..30 and power 0..1000 — different magnitudes.
        val temp = series("sensor.temp", 0, pt(0, 10.0), pt(200, 30.0))
        val power = series("sensor.power", 1, pt(0, 0.0), pt(200, 1000.0))
        val multi = buildMultiProjection(listOf(temp, power))!!
        assertThat(multi.series).hasSize(2)
        val t = multi.series[0]
        val p = multi.series[1]
        // Both span the full normalized height despite different units.
        assertThat(t.ysNorm.first()).isWithin(1e-4f).of(1f)
        assertThat(t.ysNorm.last()).isWithin(1e-4f).of(0f)
        assertThat(p.ysNorm.first()).isWithin(1e-4f).of(1f)
        assertThat(p.ysNorm.last()).isWithin(1e-4f).of(0f)
        assertThat(p.yMax).isEqualTo(1000.0)
    }

    @Test fun `shared time axis spans the union of all series`() {
        val early = series("sensor.a", 0, pt(0, 1.0), pt(100, 2.0))
        val late = series("sensor.b", 1, pt(50, 5.0), pt(300, 6.0))
        val multi = buildMultiProjection(listOf(early, late))!!
        assertThat(multi.tStart).isEqualTo(Instant.ofEpochSecond(0))
        assertThat(multi.tEnd).isEqualTo(Instant.ofEpochSecond(300))
        // The early series ends at t=100 which is 1/3 along a 0..300 axis.
        val a = multi.series[0]
        assertThat(a.xsNorm.first()).isWithin(1e-4f).of(0f)
        assertThat(a.xsNorm.last()).isWithin(1e-4f).of(100f / 300f)
        // The late series starts at t=50 -> 50/300.
        val b = multi.series[1]
        assertThat(b.xsNorm.first()).isWithin(1e-4f).of(50f / 300f)
        assertThat(b.xsNorm.last()).isWithin(1e-4f).of(1f)
    }

    @Test fun `series with too few numeric points are dropped not fatal`() {
        val good = series("sensor.a", 0, pt(0, 1.0), pt(100, 2.0))
        val sparse = series("sensor.b", 1, pt(0, 5.0)) // only one numeric point
        val multi = buildMultiProjection(listOf(good, sparse))!!
        assertThat(multi.series).hasSize(1)
        assertThat(multi.series.single().colorIndex).isEqualTo(0)
    }

    @Test fun `nearestIndex picks the closest sample`() {
        val xs = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        assertThat(nearestIndex(xs, 0.0f)).isEqualTo(0)
        assertThat(nearestIndex(xs, 0.3f)).isEqualTo(1)
        assertThat(nearestIndex(xs, 0.6f)).isEqualTo(2)
        assertThat(nearestIndex(xs, 1.0f)).isEqualTo(4)
        assertThat(nearestIndex(FloatArray(0), 0.5f)).isEqualTo(-1)
    }
}
