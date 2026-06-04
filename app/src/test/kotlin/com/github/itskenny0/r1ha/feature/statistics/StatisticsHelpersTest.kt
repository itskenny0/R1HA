package com.github.itskenny0.r1ha.feature.statistics

import com.github.itskenny0.r1ha.core.ha.StatisticId
import com.github.itskenny0.r1ha.core.ha.StatisticsBucket
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Pure helpers backing the Statistics surface: classification of a
 * statistic by its recorder columns, series/band projection, the
 * type-aware window summary, and number formatting. No coroutines or
 * repository here, just deterministic maths.
 */
class StatisticsHelpersTest {

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    private fun id(
        hasMean: Boolean,
        hasSum: Boolean,
        unit: String? = null,
    ) = StatisticId(
        statisticId = "sensor.example",
        name = "Example",
        source = "recorder",
        unitOfMeasurement = unit,
        hasMean = hasMean,
        hasSum = hasSum,
    )

    private fun bucket(
        index: Int,
        mean: Double? = null,
        min: Double? = null,
        max: Double? = null,
        sum: Double? = null,
        state: Double? = null,
        change: Double? = null,
    ) = StatisticsBucket(
        start = t0.plusSeconds(index * 3600L),
        end = t0.plusSeconds((index + 1) * 3600L),
        mean = mean,
        min = min,
        max = max,
        sum = sum,
        state = state,
        change = change,
    )

    // --- classification -----------------------------------------------------

    @Test
    fun `mean column classifies as measurement`() {
        assertThat(classify(id(hasMean = true, hasSum = false)))
            .isEqualTo(StatisticsViewModel.StatKind.MEASUREMENT)
    }

    @Test
    fun `sum only column classifies as metered`() {
        assertThat(classify(id(hasMean = false, hasSum = true)))
            .isEqualTo(StatisticsViewModel.StatKind.METERED)
    }

    @Test
    fun `both columns prefer measurement`() {
        assertThat(classify(id(hasMean = true, hasSum = true)))
            .isEqualTo(StatisticsViewModel.StatKind.MEASUREMENT)
    }

    @Test
    fun `neither column nor null is not plottable`() {
        assertThat(classify(id(hasMean = false, hasSum = false)))
            .isEqualTo(StatisticsViewModel.StatKind.NONE)
        assertThat(classify(null)).isEqualTo(StatisticsViewModel.StatKind.NONE)
    }

    // --- supported aggregations / defaults ----------------------------------

    @Test
    fun `measurement supports mean min max only`() {
        assertThat(supportedAggregations(id(hasMean = true, hasSum = false)))
            .containsExactly(
                StatisticsViewModel.Aggregation.MEAN,
                StatisticsViewModel.Aggregation.MIN,
                StatisticsViewModel.Aggregation.MAX,
            )
    }

    @Test
    fun `metered supports sum and change only`() {
        assertThat(supportedAggregations(id(hasMean = false, hasSum = true)))
            .containsExactly(
                StatisticsViewModel.Aggregation.SUM,
                StatisticsViewModel.Aggregation.CHANGE,
            )
    }

    @Test
    fun `default aggregation is mean for measurement, change for metered, null for none`() {
        assertThat(defaultAggregation(id(hasMean = true, hasSum = false)))
            .isEqualTo(StatisticsViewModel.Aggregation.MEAN)
        assertThat(defaultAggregation(id(hasMean = false, hasSum = true)))
            .isEqualTo(StatisticsViewModel.Aggregation.CHANGE)
        assertThat(defaultAggregation(id(hasMean = false, hasSum = false))).isNull()
    }

    // --- series projection --------------------------------------------------

    @Test
    fun `series points pick the selected column and drop empty or non-finite`() {
        val buckets = listOf(
            bucket(0, mean = 10.0),
            bucket(1, mean = null),
            bucket(2, mean = Double.NaN),
            bucket(3, mean = 12.5),
        )
        val pts = seriesPoints(buckets, StatisticsViewModel.Aggregation.MEAN)
        assertThat(pts.map { it.value }).containsExactly(10.0, 12.5).inOrder()
        assertThat(pts.first().timestamp).isEqualTo(t0)
    }

    @Test
    fun `change column drives metered series`() {
        val buckets = listOf(
            bucket(0, sum = 100.0, change = 2.0),
            bucket(1, sum = 105.0, change = 5.0),
        )
        val pts = seriesPoints(buckets, StatisticsViewModel.Aggregation.CHANGE)
        assertThat(pts.map { it.value }).containsExactly(2.0, 5.0).inOrder()
    }

    // --- band ---------------------------------------------------------------

    @Test
    fun `band keeps buckets with both min and max and orders the pair`() {
        val buckets = listOf(
            bucket(0, mean = 5.0, min = 3.0, max = 7.0),
            bucket(1, mean = 6.0, min = null, max = 8.0),
            // recorder hiccup: min above max, helper should normalise
            bucket(2, mean = 6.0, min = 9.0, max = 4.0),
        )
        val band = bandPoints(buckets)
        assertThat(band).hasSize(2)
        assertThat(band[0].min).isEqualTo(3.0)
        assertThat(band[0].max).isEqualTo(7.0)
        assertThat(band[1].min).isEqualTo(4.0)
        assertThat(band[1].max).isEqualTo(9.0)
    }

    // --- window total -------------------------------------------------------

    @Test
    fun `window total sums finite change columns`() {
        val buckets = listOf(
            bucket(0, change = 1.5),
            bucket(1, change = null),
            bucket(2, change = Double.POSITIVE_INFINITY),
            bucket(3, change = 2.5),
        )
        assertThat(windowTotal(buckets)).isEqualTo(4.0)
    }

    @Test
    fun `window total is null when no bucket carries change`() {
        val buckets = listOf(bucket(0, mean = 10.0), bucket(1, mean = 11.0))
        assertThat(windowTotal(buckets)).isNull()
    }

    // --- window summary -----------------------------------------------------

    @Test
    fun `measurement summary reports current min max avg, no total`() {
        val buckets = listOf(
            bucket(0, mean = 10.0, min = 8.0, max = 12.0),
            bucket(1, mean = 20.0, min = 18.0, max = 22.0),
        )
        val pts = seriesPoints(buckets, StatisticsViewModel.Aggregation.MEAN)
        val s = windowSummary(StatisticsViewModel.StatKind.MEASUREMENT, buckets, pts)
        assertThat(s.current).isEqualTo(20.0)
        assertThat(s.min).isEqualTo(10.0)
        assertThat(s.max).isEqualTo(20.0)
        assertThat(s.avg).isEqualTo(15.0)
        assertThat(s.total).isNull()
        assertThat(s.count).isEqualTo(2)
    }

    @Test
    fun `metered summary reports window total from change`() {
        val buckets = listOf(
            bucket(0, sum = 100.0, change = 2.0),
            bucket(1, sum = 105.0, change = 5.0),
        )
        val pts = seriesPoints(buckets, StatisticsViewModel.Aggregation.CHANGE)
        val s = windowSummary(StatisticsViewModel.StatKind.METERED, buckets, pts)
        assertThat(s.total).isEqualTo(7.0)
        // avg here is the mean of the plotted change series
        assertThat(s.avg).isEqualTo(3.5)
        assertThat(s.max).isEqualTo(5.0)
        assertThat(s.count).isEqualTo(2)
    }

    @Test
    fun `empty window yields all-null summary with zero count`() {
        val s = windowSummary(StatisticsViewModel.StatKind.MEASUREMENT, emptyList(), emptyList())
        assertThat(s.current).isNull()
        assertThat(s.min).isNull()
        assertThat(s.max).isNull()
        assertThat(s.avg).isNull()
        assertThat(s.total).isNull()
        assertThat(s.count).isEqualTo(0)
    }

    // --- formatting ---------------------------------------------------------

    @Test
    fun `format drops trailing zeros and keeps two decimals otherwise`() {
        assertThat(formatStatNum(23.0)).isEqualTo("23")
        assertThat(formatStatNum(23.45)).isEqualTo("23.45")
        assertThat(formatStatNum(23.456)).isEqualTo("23.46")
        assertThat(formatStatNum(-5.0)).isEqualTo("-5")
        // Locale.US decimal point even where the platform default would differ.
        assertThat(formatStatNum(1234.5)).isEqualTo("1234.50")
    }

    @Test
    fun `format never shows a rounded-to-zero negative as minus zero`() {
        assertThat(formatStatNum(-0.002)).isEqualTo("0")
        assertThat(formatStatNum(-0.0)).isEqualTo("0")
        // Genuine negatives keep their sign and two decimals.
        assertThat(formatStatNum(-0.5)).isEqualTo("-0.50")
        assertThat(formatStatNum(-12.34)).isEqualTo("-12.34")
    }
}
