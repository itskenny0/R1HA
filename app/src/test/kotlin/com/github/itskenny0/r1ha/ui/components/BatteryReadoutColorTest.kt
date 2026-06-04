package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.theme.R1
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [batteryReadoutColor]: a battery sensor's readout tints red at/below 10%,
 * amber at/below 20%, and stays default (null) otherwise; non-battery and non-numeric
 * sensors never tint.
 */
class BatteryReadoutColorTest {
    @Test fun `critical battery is red`() {
        assertThat(batteryReadoutColor("battery", "10")).isEqualTo(R1.StatusRed)
        assertThat(batteryReadoutColor("battery", "3")).isEqualTo(R1.StatusRed)
        assertThat(batteryReadoutColor("battery", "0")).isEqualTo(R1.StatusRed)
    }

    @Test fun `low battery is amber`() {
        assertThat(batteryReadoutColor("battery", "20")).isEqualTo(R1.StatusAmber)
        assertThat(batteryReadoutColor("battery", "15")).isEqualTo(R1.StatusAmber)
        assertThat(batteryReadoutColor("battery", "11")).isEqualTo(R1.StatusAmber)
    }

    @Test fun `healthy battery keeps the default tint`() {
        assertThat(batteryReadoutColor("battery", "21")).isNull()
        assertThat(batteryReadoutColor("battery", "85")).isNull()
        assertThat(batteryReadoutColor("battery", "100")).isNull()
    }

    @Test fun `device-class match is case-insensitive and whitespace-tolerant`() {
        assertThat(batteryReadoutColor("Battery", " 8 ")).isEqualTo(R1.StatusRed)
    }

    @Test fun `non-battery and non-numeric never tint`() {
        assertThat(batteryReadoutColor("temperature", "5")).isNull()
        assertThat(batteryReadoutColor(null, "5")).isNull()
        assertThat(batteryReadoutColor("battery", null)).isNull()
        assertThat(batteryReadoutColor("battery", "unknown")).isNull()
    }

    @Test fun `level-based helper applies the same thresholds for any battery source`() {
        assertThat(batteryLevelColor(10.0)).isEqualTo(R1.StatusRed)
        assertThat(batteryLevelColor(20.0)).isEqualTo(R1.StatusAmber)
        assertThat(batteryLevelColor(21.0)).isNull()
        assertThat(batteryLevelColor(100.0)).isNull()
        assertThat(batteryLevelColor(null)).isNull()
    }
}
