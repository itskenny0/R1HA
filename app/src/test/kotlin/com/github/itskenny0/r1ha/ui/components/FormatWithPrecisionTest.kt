package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Locks in [formatWithPrecision]'s contract:
 *   - numeric state with a precision rounds to that many decimal places,
 *   - non-numeric states pass through unchanged,
 *   - null precision delegates to [formatSensorValue]'s default rounding,
 *   - HA sentinels and blank inputs still render as a dash.
 */
class FormatWithPrecisionTest {

    @Test fun `null precision delegates to formatSensorValue`() {
        assertThat(formatWithPrecision("21.74321", null)).isEqualTo("21.74")
        assertThat(formatWithPrecision("21.00", null)).isEqualTo("21")
        assertThat(formatWithPrecision(null, null)).isEqualTo("—")
    }

    @Test fun `precision 0 rounds to whole number`() {
        assertThat(formatWithPrecision("21.7", 0)).isEqualTo("22")
        assertThat(formatWithPrecision("21.4", 0)).isEqualTo("21")
    }

    @Test fun `precision 1 rounds to one decimal place`() {
        assertThat(formatWithPrecision("21.74", 1)).isEqualTo("21.7")
        assertThat(formatWithPrecision("21.75", 1)).isEqualTo("21.8")
    }

    @Test fun `precision 2 rounds to two decimal places`() {
        assertThat(formatWithPrecision("21.745", 2)).isEqualTo("21.75")
        assertThat(formatWithPrecision("21.740", 2)).isEqualTo("21.74")
    }

    @Test fun `trailing zeros stripped after rounding`() {
        assertThat(formatWithPrecision("21.70", 2)).isEqualTo("21.7")
        assertThat(formatWithPrecision("21.00", 2)).isEqualTo("21")
    }

    @Test fun `non-numeric state passes through unchanged`() {
        assertThat(formatWithPrecision("Heating", 1)).isEqualTo("Heating")
        assertThat(formatWithPrecision("on", 0)).isEqualTo("on")
    }

    @Test fun `unknown and unavailable sentinels render as dash`() {
        assertThat(formatWithPrecision("unknown", 1)).isEqualTo("—")
        assertThat(formatWithPrecision("unavailable", 2)).isEqualTo("—")
        assertThat(formatWithPrecision("Unknown", 0)).isEqualTo("—")
    }

    @Test fun `blank and null inputs render as dash`() {
        assertThat(formatWithPrecision(null, 1)).isEqualTo("—")
        assertThat(formatWithPrecision("", 1)).isEqualTo("—")
        assertThat(formatWithPrecision("  ", 1)).isEqualTo("—")
    }

    @Test fun `negative zero suppressed`() {
        assertThat(formatWithPrecision("-0.002", 2)).isEqualTo("0")
        assertThat(formatWithPrecision("-0.4", 0)).isEqualTo("0")
    }

    @Test fun `genuine negatives keep sign`() {
        assertThat(formatWithPrecision("-1.5", 1)).isEqualTo("-1.5")
        assertThat(formatWithPrecision("-12.3", 0)).isEqualTo("-12")
    }

    @Test fun `large values get thousands separators`() {
        assertThat(formatWithPrecision("12345.0", 0)).isEqualTo("12,345")
        assertThat(formatWithPrecision("1234567.89", 1)).isEqualTo("1,234,567.9")
    }

    @Test fun `dot separator used even under comma-decimal locale`() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertThat(formatWithPrecision("21.5", 1)).isEqualTo("21.5")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
