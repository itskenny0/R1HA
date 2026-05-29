package com.github.itskenny0.r1ha.feature.dashboards.cards

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

/**
 * Pins gauge min / max / value label formatting. The old renderer mashed min
 * and max into a single label (e.g. "3010 0" for a 30..100 range); these tests
 * lock each value to a separate, [Locale.US] formatted string.
 */
class GaugeLabelFormatTest {

    @Test fun `whole numbers render without decimals`() {
        assertEquals("30", formatGaugeNumber(30.0))
        assertEquals("100", formatGaugeNumber(100.0))
        assertEquals("0", formatGaugeNumber(0.0))
    }

    @Test fun `fractional numbers render with one decimal`() {
        assertEquals("21.5", formatGaugeNumber(21.5))
        assertEquals("99.9", formatGaugeNumber(99.94))
    }

    @Test fun `min and max stay separate labels`() {
        // Regression: a 30..100 range reads as "30" and "100", never "30100".
        assertEquals("30", formatGaugeNumber(30.0))
        assertEquals("100", formatGaugeNumber(100.0))
    }

    @Test fun `uses US decimal separator regardless of default locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY) // comma decimal separator
            assertEquals("21.5", formatGaugeNumber(21.5))
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test fun `humanizeCardType produces a tasteful title-case label`() {
        assertEquals("Mushroom Light Card", humanizeCardType("custom:mushroom-light-card"))
        assertEquals("Mushroom Light Card", humanizeCardType("mushroom-light-card"))
        assertEquals("My Slider Button", humanizeCardType("my_slider-button"))
    }
}
