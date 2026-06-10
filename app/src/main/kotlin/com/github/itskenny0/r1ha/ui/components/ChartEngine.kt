package com.github.itskenny0.r1ha.ui.components

import java.time.Instant
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Pure decision layer for the dashboards graph engine: downsampling, Y
 * autoscale, fixed-window anchoring, and timeline band segmentation. All
 * functions here are deterministic and Compose-free so they can be locked in
 * with plain JUnit tests; the Canvas drawing in [Sparkline] / the timeline
 * composables stays thin and reads its geometry from these results.
 *
 * The numeric parity targets are HA's `src/panels/lovelace/common/graph/`
 * (coordinates.ts) and `src/components/chart/down-sample.ts`. The bucketing,
 * margin, and origin rules below mirror those verbatim so a series renders the
 * same shape it would in the HA frontend.
 */

/** One time-stamped numeric sample feeding the engine. */
data class ChartSample(val tMillis: Long, val value: Double)

/**
 * Inclusive numeric bounds the renderer maps onto the plot's vertical extent,
 * plus the zero-origin position used to anchor area fills and bar baselines.
 *
 * [yAxisOrigin] is expressed as a fraction of the plot HEIGHT measured from the
 * TOP (HA's convention: 0 = top edge, 1 = bottom edge), so a renderer computes
 * the baseline pixel as `origin * height`. For an all-positive or all-negative
 * series this sits at the bottom/top edge; for a mixed series it falls where
 * the real zero line is.
 */
data class ChartScale(
    val minY: Double,
    val maxY: Double,
    val yAxisOriginFraction: Float,
)

/**
 * The default target sample count HA uses before pixel width is known.
 *
 * HA: `Math.max(10, detail > 1 ? Math.max(width/5, hours) : hours)`. We can't
 * read the live pixel width from a pure function, so the caller passes the
 * laid-out width when it has one (the Canvas does); when it doesn't, the
 * width-independent `max(10, hours)` form is the right default and matches the
 * `detail: 1` branch exactly.
 */
fun maxDetailsFor(hoursToShow: Double, detail: Int, widthPx: Float? = null): Int {
    val hours = max(1.0, hoursToShow)
    val base = if (detail > 1 && widthPx != null) {
        max((widthPx / 5f).toDouble(), hours)
    } else {
        hours
    }
    return max(10.0, base).toInt()
}

/**
 * Downsample [data] to at most [maxDetails] frames, mirroring HA's
 * `downSampleLineData`.
 *
 * [useMean] true (HA's `detail: 1`, the default) emits one mean point per
 * frame. [useMean] false (`detail: 2`) preserves each frame's min AND max in
 * chronological order, so spikes survive the reduction. Series already shorter
 * than [maxDetails] pass through untouched.
 *
 * [minX]/[maxX] pin the frame grid to a fixed window when supplied (so live
 * pushes don't re-bucket the whole series differently each tick); otherwise the
 * data's own span is used.
 */
fun downSampleLineData(
    data: List<ChartSample>,
    maxDetails: Int,
    minX: Long? = null,
    maxX: Long? = null,
    useMean: Boolean = true,
): List<ChartSample> {
    if (data.isEmpty()) return emptyList()
    if (data.size <= maxDetails) return data
    val mn = minX ?: data.first().tMillis
    val mx = maxX ?: data.last().tMillis
    // ceil((max-min)/floor(maxDetails)); guard a zero span so step stays >= 1.
    val span = (mx - mn).coerceAtLeast(1L)
    val step = ceil(span.toDouble() / floor(maxDetails.toDouble())).toLong().coerceAtLeast(1L)

    // LinkedHashMap keeps frames in first-seen (chronological) order so the
    // emitted point order tracks time without a separate sort.
    val frames = LinkedHashMap<Long, MutableList<ChartSample>>()
    for (p in data) {
        val frameIndex = floor((p.tMillis - mn).toDouble() / step).toLong()
        frames.getOrPut(frameIndex) { ArrayList() }.add(p)
    }

    val result = ArrayList<ChartSample>(frames.size * if (useMean) 1 else 2)
    if (useMean) {
        for ((_, framePoints) in frames) {
            val meanY = framePoints.sumOf { it.value } / framePoints.size
            val meanX = framePoints.sumOf { it.tMillis } / framePoints.size
            result.add(ChartSample(meanX, meanY))
        }
    } else {
        for ((_, framePoints) in frames) {
            var minPoint = framePoints.first()
            var maxPoint = framePoints.first()
            for (p in framePoints) {
                if (p.value < minPoint.value) minPoint = p
                if (p.value > maxPoint.value) maxPoint = p
            }
            // Preserve sample order: emit whichever of min/max came first first.
            if (minPoint.tMillis > maxPoint.tMillis) result.add(maxPoint)
            result.add(minPoint)
            if (minPoint.tMillis < maxPoint.tMillis) result.add(maxPoint)
        }
    }
    return result
}

/**
 * Compute the Y scale for [values], mirroring coordinates.ts.
 *
 * Rules, in order:
 *  - 10% top and bottom margins around the data range; a flat series uses
 *    `min * 0.1` as the synthetic range so a constant line still gets breathing
 *    room rather than collapsing to a zero-height band.
 *  - All-negative series clamp the top to 0 and put the origin at the top edge.
 *  - Mixed-sign series place the origin where the real zero falls.
 *  - All-positive series clamp the bottom to 0 (so bars/fills grow from zero).
 *
 * [limitMin]/[limitMax] override the computed bound when set (HA's `limits:` /
 * `min_y_axis` / `max_y_axis`), applied BEFORE the margin/clamp pass exactly as
 * HA seeds minY/maxY from the limits.
 */
fun computeScale(
    values: List<Double>,
    limitMin: Double? = null,
    limitMax: Double? = null,
): ChartScale {
    if (values.isEmpty()) {
        return ChartScale(limitMin ?: 0.0, limitMax ?: 1.0, 1f)
    }
    var minY = limitMin ?: values.first()
    var maxY = limitMax ?: values.first()
    for (v in values) {
        if (v < minY) minY = v else if (v > maxY) maxY = v
    }
    val rangeY = (maxY - minY).let { if (it == 0.0) minY * 0.1 else it }
    maxY += rangeY * 0.1
    minY -= rangeY * 0.1
    // A user-pinned bound wins over the auto margin.
    if (limitMax != null) maxY = limitMax
    if (limitMin != null) minY = limitMin

    var originFromTop: Float
    when {
        maxY < 0 -> {
            // All values negative: zero sits at (or above) the top edge.
            maxY = min(0.0, maxY)
            originFromTop = 0f
        }
        minY < 0 -> {
            // Mixed sign: origin where real zero falls, measured from the top.
            val denom = (maxY - minY).let { if (it == 0.0) 1.0 else it }
            originFromTop = (maxY / denom).toFloat()
        }
        else -> {
            // All positive: floor at zero, origin at the bottom edge.
            minY = max(0.0, minY)
            originFromTop = 1f
        }
    }
    return ChartScale(minY, maxY, originFromTop.coerceIn(0f, 1f))
}

/**
 * Vertical plot fraction (0 = bottom edge, 1 = top edge) for [value] within a
 * [scale]. A degenerate band centres at 0.5 so a constant reading reads as
 * "steady, mid-chart" rather than pinned to the floor. Clamped so a stray
 * out-of-band sample can't draw outside the plot.
 */
fun scaleYFraction(value: Double, scale: ChartScale): Float {
    val range = scale.maxY - scale.minY
    if (range <= 1e-9) return 0.5f
    return ((value - scale.minY) / range).toFloat().coerceIn(0f, 1f)
}

/**
 * Horizontal plot fraction (0 = left/window-start, 1 = right/window-end) for a
 * timestamp anchored to a fixed [windowStartMillis]..[windowEndMillis] span.
 *
 * Fixed-window anchoring (HA's minX = now - hours, maxX = now): a sample's X is
 * its position in the WINDOW, not in the data's own span, so a series that
 * doesn't reach back the full window starts partway across rather than being
 * stretched edge to edge. The caller extends the last sample to the right edge
 * separately (see [extendsToRightEdge]).
 */
fun xFraction(tMillis: Long, windowStartMillis: Long, windowEndMillis: Long): Float {
    val span = (windowEndMillis - windowStartMillis).coerceAtLeast(1L)
    return ((tMillis - windowStartMillis).toDouble() / span).toFloat().coerceIn(0f, 1f)
}

/**
 * HA draws a final flat segment from the last real sample to the right edge so
 * the line reads as "this value, held until now". Always true here; exposed as
 * a named seam so a renderer reads intent rather than a bare boolean.
 */
const val extendsToRightEdge: Boolean = true

/**
 * Redraw cadence for a live sliding window, in milliseconds. HA ticks every
 * minute for short windows and hourly once the window exceeds a day, so a
 * week-long graph doesn't repaint 60 times an hour for sub-pixel motion.
 */
fun redrawIntervalMillis(hoursToShow: Double): Long =
    if (hoursToShow > 24.0) 3_600_000L else 60_000L

/**
 * Drop samples older than the window's left edge, preserving the single
 * newest sample that predates the edge as the "boundary state".
 *
 * Purging naively would leave the chart blank-on-the-left until a fresh sample
 * lands inside the window; HA keeps the last pre-window sample so the line has a
 * defined value at the left edge (the entity's state when the window opened).
 * Input is assumed chronological; output preserves that order.
 */
fun purgeToWindow(data: List<ChartSample>, windowStartMillis: Long): List<ChartSample> {
    if (data.isEmpty()) return data
    val firstInsideIdx = data.indexOfFirst { it.tMillis >= windowStartMillis }
    if (firstInsideIdx <= 0) {
        // Everything is inside the window, or nothing is (keep the last known).
        return if (firstInsideIdx == 0) data else listOf(data.last())
    }
    // Keep the one boundary sample just before the edge, plus everything inside.
    return data.subList(firstInsideIdx - 1, data.size).toList()
}

// ---------------------------------------------------------------------------
// Timeline band segmentation (non-numeric entities: switches, binary_sensors).
// ---------------------------------------------------------------------------

/** One contiguous run of an unchanged categorical state on the timeline. */
data class TimelineBand(
    val state: String,
    val startMillis: Long,
    val endMillis: Long,
) {
    val durationMillis: Long get() = (endMillis - startMillis).coerceAtLeast(0L)
}

/**
 * Collapse a categorical history into contiguous coloured bands, mirroring HA's
 * timeline chart. Consecutive identical states merge into one band; each band
 * runs from its sample's timestamp to the next change (or [windowEndMillis] for
 * the final, still-current state). Samples before [windowStartMillis] seed the
 * left-edge band so the timeline is defined across the whole window.
 *
 * [history] is assumed chronological. Blank states are kept verbatim (HA shows
 * "unavailable"/"unknown" bands); the caller decides how to colour them.
 */
fun segmentTimeline(
    history: List<Pair<Long, String>>,
    windowStartMillis: Long,
    windowEndMillis: Long,
): List<TimelineBand> {
    if (history.isEmpty()) return emptyList()
    // Clip to the window: keep the last pre-window sample as the opening state.
    val clipped = clipCategoricalToWindow(history, windowStartMillis)
    if (clipped.isEmpty()) return emptyList()

    val bands = ArrayList<TimelineBand>()
    var runState = clipped.first().second
    var runStart = max(clipped.first().first, windowStartMillis)
    for (i in 1 until clipped.size) {
        val (t, s) = clipped[i]
        if (s != runState) {
            val end = max(t, runStart).coerceAtMost(windowEndMillis)
            if (end > runStart) bands.add(TimelineBand(runState, runStart, end))
            runState = s
            runStart = t.coerceIn(windowStartMillis, windowEndMillis)
        }
    }
    // Final run extends to the window end (state still current).
    if (windowEndMillis > runStart) {
        bands.add(TimelineBand(runState, runStart, windowEndMillis))
    }
    return bands
}

private fun clipCategoricalToWindow(
    history: List<Pair<Long, String>>,
    windowStartMillis: Long,
): List<Pair<Long, String>> {
    val firstInside = history.indexOfFirst { it.first >= windowStartMillis }
    return when {
        firstInside < 0 -> listOf(history.last()) // all before window: carry last state
        firstInside == 0 -> history
        else -> history.subList(firstInside - 1, history.size)
    }
}

/**
 * A numeric history is plottable as a line when at least two of its samples
 * parse as finite numbers. HA routes anything that fails this to the timeline
 * chart instead. Pure so the per-card split can be unit-tested.
 */
fun isNumericSeries(numericSampleCount: Int): Boolean = numericSampleCount >= 2

// ---------------------------------------------------------------------------
// Per-unit / per-device-class chart grouping (history-graph split).
// ---------------------------------------------------------------------------

/** Minimal grouping key for a numeric series in a split history graph. */
data class ChartGroupKey(val unit: String?, val deviceClass: String?)

/**
 * Group numeric series indices into separate charts, mirroring HA's
 * `split_device_classes`. With [split] off, every numeric series shares one
 * chart keyed on unit only (so a °C and a % series still get distinct axes but
 * sensors of the same unit overlay). With [split] on, series additionally split
 * by device_class so e.g. a "temperature" and an "apparent temperature" sensor
 * (both °C) land on separate charts.
 *
 * Returns an ordered list of (groupKey, member indices) preserving the input
 * order of first appearance, so the rendered chart order is stable. Indices
 * with no unit AND no device-class collapse into one trailing "unitless" group.
 */
fun groupSeriesForCharts(
    keys: List<ChartGroupKey>,
    split: Boolean,
): List<Pair<ChartGroupKey, List<Int>>> {
    val groups = LinkedHashMap<ChartGroupKey, MutableList<Int>>()
    keys.forEachIndexed { idx, k ->
        val groupKey = if (split) k else ChartGroupKey(k.unit, null)
        groups.getOrPut(groupKey) { ArrayList() }.add(idx)
    }
    return groups.entries.map { it.key to it.value.toList() }
}
