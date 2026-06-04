package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

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
}
