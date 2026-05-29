package com.github.itskenny0.r1ha.feature.history

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HistoryContentDescriptionTest {

    private fun projection(
        displayName: String,
        colorIndex: Int,
        unit: String?,
        samples: List<Double>,
        yMin: Double = samples.min(),
        yMax: Double = samples.max(),
    ): SeriesProjection {
        val xs = FloatArray(samples.size) { i ->
            if (samples.size <= 1) 0f else i.toFloat() / (samples.size - 1)
        }
        return SeriesProjection(
            colorIndex = colorIndex,
            displayName = displayName,
            unit = unit,
            xsNorm = xs,
            ysNorm = FloatArray(samples.size),
            yMin = yMin,
            yMax = yMax,
            samples = samples.mapIndexed { i, v -> java.time.Instant.ofEpochMilli(i.toLong()) to v },
        )
    }

    private fun multi(vararg series: SeriesProjection) = MultiProjection(
        series = series.toList(),
        tStart = java.time.Instant.EPOCH,
        tEnd = java.time.Instant.ofEpochMilli(1000),
        tSpan = 1000L,
    )

    // --- windowAccessibleLabel ---------------------------------------------

    @Test
    fun windowLabel_expandsHoursAndDays() {
        assertEquals("1 hour", windowAccessibleLabel(HistoryViewModel.Window.H1))
        assertEquals("6 hours", windowAccessibleLabel(HistoryViewModel.Window.H6))
        assertEquals("1 day", windowAccessibleLabel(HistoryViewModel.Window.H24))
        assertEquals("7 days", windowAccessibleLabel(HistoryViewModel.Window.D7))
    }

    // --- seriesColorName ----------------------------------------------------

    @Test
    fun colorName_namesKnownSlotsAndFallsBack() {
        assertEquals("orange", seriesColorName(0))
        assertEquals("grey", seriesColorName(4))
        assertEquals("series 6", seriesColorName(5))
    }

    // --- legendRowContentDescription ---------------------------------------

    @Test
    fun legendRow_includesNameColorAndRange() {
        val label = legendRowContentDescription(
            name = "Living Room",
            colorIndex = 1,
            min = 18.0,
            max = 22.5,
            unit = "C",
        )
        assertTrue(label.contains("Living Room"))
        assertTrue(label.contains("blue line"))
        assertTrue(label.contains("range 18 to 22.50 C"))
    }

    @Test
    fun legendRow_omitsRangeWhenNoMinMax() {
        val label = legendRowContentDescription(
            name = "Door",
            colorIndex = 0,
            min = null,
            max = null,
            unit = null,
        )
        assertEquals("Door, orange line", label)
    }

    // --- buildHistoryChartContentDescription -------------------------------

    @Test
    fun chartDescription_noDataWhenNullOrEmpty() {
        assertEquals(
            "Line chart with no numeric history to display.",
            buildHistoryChartContentDescription(null, null),
        )
        assertEquals(
            "Line chart with no numeric history to display.",
            buildHistoryChartContentDescription(multi(), null),
        )
    }

    @Test
    fun chartDescription_summarizesSingleSeriesWithTrend() {
        val desc = buildHistoryChartContentDescription(
            multi(projection("Temp", 0, "C", listOf(10.0, 15.0, 20.0))),
            null,
        )
        assertTrue(desc.startsWith("Line chart, 1 series."))
        assertTrue(desc.contains("Temp, orange:"))
        assertTrue(desc.contains("minimum 10 C"))
        assertTrue(desc.contains("maximum 20 C"))
        assertTrue(desc.contains("average 15 C"))
        assertTrue(desc.contains("rising"))
        assertFalse(desc.contains("Selected point"))
    }

    @Test
    fun chartDescription_countsMultipleSeriesAndDetectsFalling() {
        val desc = buildHistoryChartContentDescription(
            multi(
                projection("A", 0, "C", listOf(5.0, 4.0, 3.0)),
                projection("B", 1, "%", listOf(40.0, 60.0)),
            ),
            null,
        )
        assertTrue(desc.startsWith("Line chart, 2 series."))
        assertTrue(desc.contains("A, orange:"))
        assertTrue(desc.contains("falling"))
        assertTrue(desc.contains("B, blue:"))
    }

    @Test
    fun chartDescription_appendsScrubReadout() {
        val desc = buildHistoryChartContentDescription(
            multi(
                projection("A", 0, "C", listOf(10.0, 20.0, 30.0)),
                projection("B", 1, "%", listOf(1.0, 2.0, 3.0)),
            ),
            scrubFrac = 1f,
        )
        assertTrue(desc.contains("Selected point:"))
        assertTrue(desc.contains("A 30 C"))
        assertTrue(desc.contains("B 3 %"))
    }
}
