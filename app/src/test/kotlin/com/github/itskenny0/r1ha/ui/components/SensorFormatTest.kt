package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Locks in [formatSensorValue]'s numeric rendering: decimal rounding, trailing-zero
 * trimming, non-numeric passthrough, NaN/Infinity guarding, and the negative-zero
 * normalisation that keeps a sensor hovering just under zero from showing "-0".
 */
class SensorFormatTest {
    @Test fun `rounds and strips trailing zeros`() {
        assertThat(formatSensorValue("21.74321")).isEqualTo("21.74")
        assertThat(formatSensorValue("21.70")).isEqualTo("21.7")
        assertThat(formatSensorValue("21.00")).isEqualTo("21")
    }

    @Test fun `blank and null collapse to a dash`() {
        assertThat(formatSensorValue(null)).isEqualTo("—")
        assertThat(formatSensorValue("")).isEqualTo("—")
        assertThat(formatSensorValue("   ")).isEqualTo("—")
    }

    @Test fun `non-numeric states pass through unchanged`() {
        assertThat(formatSensorValue("Heating")).isEqualTo("Heating")
        assertThat(formatSensorValue("on")).isEqualTo("on")
    }

    @Test fun `unknown and unavailable sentinels render as a dash`() {
        assertThat(formatSensorValue("unknown")).isEqualTo("—")
        assertThat(formatSensorValue("unavailable")).isEqualTo("—")
        assertThat(formatSensorValue("Unknown")).isEqualTo("—")
        assertThat(formatSensorValue(" unavailable ")).isEqualTo("—")
    }

    @Test fun `NaN and Infinity render as a dash`() {
        assertThat(formatSensorValue("NaN")).isEqualTo("—")
        assertThat(formatSensorValue("Infinity")).isEqualTo("—")
        assertThat(formatSensorValue("-Infinity")).isEqualTo("—")
    }

    @Test fun `maxDecimals zero rounds to a whole number`() {
        assertThat(formatSensorValue("21.74", maxDecimals = 0)).isEqualTo("22")
        assertThat(formatSensorValue("21.4", maxDecimals = 0)).isEqualTo("21")
    }

    @Test fun `value rounding to zero from below is not shown as negative zero`() {
        // Two decimals: -0.002 rounds to -0.00 -> "0".
        assertThat(formatSensorValue("-0.002", maxDecimals = 2)).isEqualTo("0")
        // Zero decimals: -0.4 rounds to -0 -> "0".
        assertThat(formatSensorValue("-0.4", maxDecimals = 0)).isEqualTo("0")
        // Literal negative zero.
        assertThat(formatSensorValue("-0.0")).isEqualTo("0")
    }

    @Test fun `genuine negatives keep their sign`() {
        assertThat(formatSensorValue("-0.5", maxDecimals = 2)).isEqualTo("-0.5")
        assertThat(formatSensorValue("-1", maxDecimals = 0)).isEqualTo("-1")
        assertThat(formatSensorValue("-12.30")).isEqualTo("-12.3")
    }

    @Test fun `large values get thousands separators`() {
        assertThat(formatSensorValue("12345")).isEqualTo("12,345")
        assertThat(formatSensorValue("1234567")).isEqualTo("1,234,567")
        assertThat(formatSensorValue("1234567.5")).isEqualTo("1,234,567.5")
        assertThat(formatSensorValue("-123456")).isEqualTo("-123,456")
        assertThat(formatSensorValue("10000")).isEqualTo("10,000")
    }

    @Test fun `four-digit values stay ungrouped so years and short codes are intact`() {
        assertThat(formatSensorValue("2026")).isEqualTo("2026")
        assertThat(formatSensorValue("9999")).isEqualTo("9999")
        assertThat(formatSensorValue("1234.5")).isEqualTo("1234.5")
    }

    @Test fun `formatFixed rounds to the requested decimal places`() {
        assertThat(formatFixed(21.45, 1)).isEqualTo("21.5")
        assertThat(formatFixed(21.0, 1)).isEqualTo("21.0")
        assertThat(formatFixed(21.456, 2)).isEqualTo("21.46")
        assertThat(formatFixed(21.6, 0)).isEqualTo("22")
    }

    @Test fun `formatFixed uses a dot separator even under a comma-decimal locale`() {
        val previous = Locale.getDefault()
        try {
            // Germany formats decimals with a comma; the platform formatter would emit
            // "21,5" without the US pin, clashing with HA's dot-based numbers.
            Locale.setDefault(Locale.GERMANY)
            assertThat(formatFixed(21.5, 1)).isEqualTo("21.5")
            assertThat(formatSensorValue("21.5")).isEqualTo("21.5")
        } finally {
            Locale.setDefault(previous)
        }
    }
}
