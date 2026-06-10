package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in the pure graph-engine decisions: downsample bucketing (mean and
 * min/max frames), maxDetails sizing, Y autoscale rules (margins, zero-clamp,
 * negative origin, flat fallback), fixed-window X anchoring, live-window purge,
 * timeline band segmentation, and per-unit chart grouping. Compose-free.
 */
class ChartEngineTest {

    private fun s(t: Long, v: Double) = ChartSample(t, v)

    // --- maxDetails -------------------------------------------------------

    @Test fun `maxDetails floors at 10`() {
        assertThat(maxDetailsFor(hoursToShow = 4.0, detail = 1)).isEqualTo(10)
    }

    @Test fun `maxDetails detail 1 tracks hours`() {
        assertThat(maxDetailsFor(hoursToShow = 24.0, detail = 1)).isEqualTo(24)
    }

    @Test fun `maxDetails detail 2 uses width over 5 when wider than hours`() {
        // width/5 = 100 beats hours = 24.
        assertThat(maxDetailsFor(hoursToShow = 24.0, detail = 2, widthPx = 500f)).isEqualTo(100)
    }

    // --- downsample -------------------------------------------------------

    @Test fun `downsample passes through when already under the cap`() {
        val data = listOf(s(0, 1.0), s(1, 2.0), s(2, 3.0))
        assertThat(downSampleLineData(data, maxDetails = 10)).isEqualTo(data)
    }

    @Test fun `downsample mean collapses each frame to its average`() {
        // 10 points over [0,9], maxDetails 2 -> step = ceil(9/2) = 5.
        // frame0 = t0..4 (vals 0..4 -> mean 2), frame1 = t5..9 (vals 5..9 -> mean 7).
        val data = (0..9).map { s(it.toLong(), it.toDouble()) }
        val out = downSampleLineData(data, maxDetails = 2, useMean = true)
        assertThat(out).hasSize(2)
        assertThat(out[0].value).isWithin(1e-9).of(2.0)
        assertThat(out[1].value).isWithin(1e-9).of(7.0)
    }

    @Test fun `downsample min-max keeps both extremes per frame in time order`() {
        // A spike up then down: span 3, maxDetails 1 -> step = ceil(3/1) = 3, so
        // t0..2 fall in frame0 and t3 in frame1. frame0 has min=1 (t2) and
        // max=50 (t1); max precedes min in time so the emitted order is max,min.
        val data = listOf(s(0, 5.0), s(1, 50.0), s(2, 1.0), s(3, 6.0))
        val out = downSampleLineData(data, maxDetails = 1, useMean = false)
        assertThat(out.map { it.value }).containsExactly(50.0, 1.0, 6.0).inOrder()
    }

    // --- autoscale --------------------------------------------------------

    @Test fun `autoscale all-positive keeps margin above zero and floors only negatives`() {
        val scale = computeScale(listOf(10.0, 20.0, 30.0))
        // 10 - 10% of range (20) = 8; positive so max(0,8) leaves it at 8.
        assertThat(scale.minY).isWithin(1e-9).of(8.0)
        // max gets a 10% top margin of the range (20): 30 + 2 = 32.
        assertThat(scale.maxY).isWithin(1e-9).of(32.0)
        assertThat(scale.yAxisOriginFraction).isEqualTo(1f) // origin at bottom
    }

    @Test fun `autoscale lower margin dipping below zero takes the negative-origin branch`() {
        // Mirrors HA: the zero-floor clamp only runs when the post-margin minY
        // is still non-negative. A small-valued series whose lower margin dips
        // below zero is treated as "some values negative" and gets an interior
        // origin instead, so the floor is NOT clamped back to zero.
        val scale = computeScale(listOf(1.0, 2.0, 30.0))
        // 1 - 10% of 29 = -1.9 stays; origin = max/(max-min).
        assertThat(scale.minY).isWithin(1e-9).of(-1.9)
        assertThat(scale.yAxisOriginFraction).isGreaterThan(0f)
        assertThat(scale.yAxisOriginFraction).isLessThan(1f)
    }

    @Test fun `autoscale all-negative caps the top at zero and origin at top`() {
        val scale = computeScale(listOf(-30.0, -20.0, -10.0))
        assertThat(scale.maxY).isAtMost(0.0)
        assertThat(scale.yAxisOriginFraction).isEqualTo(0f)
    }

    @Test fun `autoscale mixed sign places origin where zero falls`() {
        val scale = computeScale(listOf(-10.0, 10.0))
        // range 20, margins +-2 -> min -12, max 12; origin = max/(max-min) = 12/24 = 0.5.
        assertThat(scale.yAxisOriginFraction).isWithin(1e-6f).of(0.5f)
    }

    @Test fun `autoscale flat series uses a synthetic range so it isn't zero-height`() {
        val scale = computeScale(listOf(50.0, 50.0, 50.0))
        // rangeY = 50 * 0.1 = 5; margins +-0.5; all positive so floor clamps.
        assertThat(scale.maxY).isGreaterThan(50.0)
        assertThat(scale.minY).isAtLeast(0.0)
    }

    @Test fun `autoscale honours pinned limits`() {
        val scale = computeScale(listOf(10.0, 20.0), limitMin = 0.0, limitMax = 100.0)
        assertThat(scale.minY).isEqualTo(0.0)
        assertThat(scale.maxY).isEqualTo(100.0)
    }

    // --- X anchoring ------------------------------------------------------

    @Test fun `xFraction anchors to the fixed window not the data span`() {
        // Window [0,100]; a sample at 25 sits a quarter across regardless of
        // where the data actually starts.
        assertThat(xFraction(25, 0, 100)).isWithin(1e-6f).of(0.25f)
        assertThat(xFraction(0, 0, 100)).isEqualTo(0f)
        assertThat(xFraction(100, 0, 100)).isEqualTo(1f)
    }

    @Test fun `xFraction clamps out-of-window samples`() {
        assertThat(xFraction(-50, 0, 100)).isEqualTo(0f)
        assertThat(xFraction(150, 0, 100)).isEqualTo(1f)
    }

    @Test fun `scaleYFraction flat band centres at half`() {
        val flat = ChartScale(5.0, 5.0, 1f)
        assertThat(scaleYFraction(5.0, flat)).isEqualTo(0.5f)
    }

    // --- live window purge ------------------------------------------------

    @Test fun `purge keeps the boundary sample just before the window edge`() {
        val data = listOf(s(0, 1.0), s(10, 2.0), s(20, 3.0), s(30, 4.0))
        val out = purgeToWindow(data, windowStartMillis = 15)
        // First inside is t20; boundary sample t10 is preserved as the left edge.
        assertThat(out.map { it.tMillis }).containsExactly(10L, 20L, 30L).inOrder()
    }

    @Test fun `purge with everything inside the window keeps all`() {
        val data = listOf(s(20, 1.0), s(30, 2.0))
        assertThat(purgeToWindow(data, windowStartMillis = 10)).isEqualTo(data)
    }

    @Test fun `purge with everything before the window keeps only the last known`() {
        val data = listOf(s(0, 1.0), s(5, 2.0))
        val out = purgeToWindow(data, windowStartMillis = 100)
        assertThat(out.map { it.tMillis }).containsExactly(5L)
    }

    @Test fun `redraw cadence is minute for short windows, hour for long`() {
        assertThat(redrawIntervalMillis(6.0)).isEqualTo(60_000L)
        assertThat(redrawIntervalMillis(48.0)).isEqualTo(3_600_000L)
    }

    // --- timeline band segmentation --------------------------------------

    @Test fun `timeline merges consecutive identical states into one band`() {
        val hist = listOf(0L to "on", 10L to "on", 20L to "off", 30L to "off")
        val bands = segmentTimeline(hist, windowStartMillis = 0, windowEndMillis = 40)
        assertThat(bands.map { it.state }).containsExactly("on", "off").inOrder()
        assertThat(bands[0].startMillis).isEqualTo(0L)
        assertThat(bands[0].endMillis).isEqualTo(20L)
        assertThat(bands[1].endMillis).isEqualTo(40L) // final state extends to window end
    }

    @Test fun `timeline carries the pre-window state to the left edge`() {
        val hist = listOf(0L to "on", 50L to "off")
        val bands = segmentTimeline(hist, windowStartMillis = 30, windowEndMillis = 100)
        // "on" band opens at the window start (30), not at 0.
        assertThat(bands.first().state).isEqualTo("on")
        assertThat(bands.first().startMillis).isEqualTo(30L)
        assertThat(bands.last().state).isEqualTo("off")
    }

    @Test fun `timeline of empty history is empty`() {
        assertThat(segmentTimeline(emptyList(), 0, 100)).isEmpty()
    }

    // --- chart grouping ---------------------------------------------------

    @Test fun `grouping without split keys on unit only`() {
        val keys = listOf(
            ChartGroupKey("°C", "temperature"),
            ChartGroupKey("°C", "apparent_temperature"),
            ChartGroupKey("%", "humidity"),
        )
        val groups = groupSeriesForCharts(keys, split = false)
        assertThat(groups).hasSize(2) // °C group + % group; device-class ignored
        assertThat(groups[0].second).containsExactly(0, 1).inOrder()
        assertThat(groups[1].second).containsExactly(2)
    }

    @Test fun `grouping with split separates by device class too`() {
        val keys = listOf(
            ChartGroupKey("°C", "temperature"),
            ChartGroupKey("°C", "apparent_temperature"),
        )
        val groups = groupSeriesForCharts(keys, split = true)
        assertThat(groups).hasSize(2) // same unit, different device_class -> two charts
    }

    @Test fun `isNumericSeries needs at least two numeric samples`() {
        assertThat(isNumericSeries(1)).isFalse()
        assertThat(isNumericSeries(2)).isTrue()
    }
}
