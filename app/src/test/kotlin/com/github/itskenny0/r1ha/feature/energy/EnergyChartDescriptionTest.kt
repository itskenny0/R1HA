package com.github.itskenny0.r1ha.feature.energy

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Covers the pure text-alternative the EnergyScreen attaches to its otherwise
 * invisible consumption-chart Canvas so TalkBack announces the same headline
 * figures a sighted user reads off the bars. No Compose here, just the string.
 */
class EnergyChartDescriptionTest {

    @Test fun `summarises bar count total and peak with adaptive kWh precision`() {
        // Sub-10 kWh values keep two decimals to match formatKwh.
        assertThat(energyChartDescription(barCount = 24, totalKwh = 1.5, peakKwh = 0.4))
            .isEqualTo("Consumption chart, 24 bars, total 1.50 kWh, peak 0.40 kWh")
    }

    @Test fun `large totals drop to one decimal`() {
        assertThat(energyChartDescription(barCount = 30, totalKwh = 123.4, peakKwh = 12.0))
            .isEqualTo("Consumption chart, 30 bars, total 123.4 kWh, peak 12.0 kWh")
    }

    @Test fun `zero bars and zero figures still read cleanly`() {
        assertThat(energyChartDescription(barCount = 0, totalKwh = 0.0, peakKwh = 0.0))
            .isEqualTo("Consumption chart, 0 bars, total 0.00 kWh, peak 0.00 kWh")
    }
}
