package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [formatDurationReadout]: seconds-based duration sensors condense to a compact
 * d/h/m/s readout; sub-minute values, non-second units, and non-duration sensors fall
 * through to the default formatter (null).
 */
class FormatDurationReadoutTest {
    @Test fun `seconds condense to the two most significant units`() {
        assertThat(formatDurationReadout("duration", "3661", "s")).isEqualTo("1h 1m")
        assertThat(formatDurationReadout("duration", "3600", "s")).isEqualTo("1h 0m")
        assertThat(formatDurationReadout("duration", "90", "s")).isEqualTo("1m 30s")
        assertThat(formatDurationReadout("duration", "90000", "s")).isEqualTo("1d 1h")
        assertThat(formatDurationReadout("duration", "120", "seconds")).isEqualTo("2m 0s")
    }

    @Test fun `sub-minute and non-second units are left to the default formatter`() {
        assertThat(formatDurationReadout("duration", "45", "s")).isNull()
        assertThat(formatDurationReadout("duration", "5", "min")).isNull()
        assertThat(formatDurationReadout("duration", "2", "h")).isNull()
        assertThat(formatDurationReadout("duration", "300", null)).isNull()
    }

    @Test fun `non-duration and non-numeric never apply`() {
        assertThat(formatDurationReadout("temperature", "3661", "s")).isNull()
        assertThat(formatDurationReadout("duration", null, "s")).isNull()
        assertThat(formatDurationReadout("duration", "unknown", "s")).isNull()
    }
}
