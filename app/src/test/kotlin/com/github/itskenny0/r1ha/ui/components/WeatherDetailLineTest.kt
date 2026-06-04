package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [weatherDetailLine]: humidity and wind each appear only when reported, wind
 * carries its unit when given, and an all-absent reading collapses to null so the card
 * omits the secondary line.
 */
class WeatherDetailLineTest {
    @Test fun `both present join with a middot`() {
        assertThat(weatherDetailLine(64.0, 12.0, "km/h"))
            .isEqualTo("Humidity 64%  ·  Wind 12 km/h")
    }

    @Test fun `values round to whole numbers`() {
        assertThat(weatherDetailLine(63.7, 11.4, "mph"))
            .isEqualTo("Humidity 64%  ·  Wind 11 mph")
    }

    @Test fun `humidity only`() {
        assertThat(weatherDetailLine(50.0, null, "km/h")).isEqualTo("Humidity 50%")
    }

    @Test fun `wind only, with and without a unit`() {
        assertThat(weatherDetailLine(null, 8.0, "m/s")).isEqualTo("Wind 8 m/s")
        assertThat(weatherDetailLine(null, 8.0, null)).isEqualTo("Wind 8")
        assertThat(weatherDetailLine(null, 8.0, "")).isEqualTo("Wind 8")
    }

    @Test fun `neither present yields null`() {
        assertThat(weatherDetailLine(null, null, "km/h")).isNull()
    }
}
