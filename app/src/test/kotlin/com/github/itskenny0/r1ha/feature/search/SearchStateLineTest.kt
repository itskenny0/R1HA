package com.github.itskenny0.r1ha.feature.search

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [searchStateLine]: the unit is appended to a numeric state, non-numeric states
 * pass through, and missing parts (state, area, unit) are omitted cleanly.
 */
class SearchStateLineTest {
    @Test fun `numeric state gets its unit`() {
        assertThat(searchStateLine("sensor.temp", "21.5", "°C", "Kitchen"))
            .isEqualTo("sensor.temp  ·  21.5 °C  ·  Kitchen")
    }

    @Test fun `non-numeric state is left as-is`() {
        assertThat(searchStateLine("light.kitchen", "on", null, "Kitchen"))
            .isEqualTo("light.kitchen  ·  on  ·  Kitchen")
        // A unit on a non-numeric state is not appended.
        assertThat(searchStateLine("sensor.mode", "auto", "°C", null))
            .isEqualTo("sensor.mode  ·  auto")
    }

    @Test fun `missing parts are omitted`() {
        assertThat(searchStateLine("sensor.x", null, "°C", null)).isEqualTo("sensor.x")
        assertThat(searchStateLine("sensor.x", "5", null, null)).isEqualTo("sensor.x  ·  5")
        assertThat(searchStateLine("sensor.x", "5", "W", null)).isEqualTo("sensor.x  ·  5 W")
    }
}
