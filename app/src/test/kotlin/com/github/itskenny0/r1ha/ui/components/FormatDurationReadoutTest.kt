package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [formatDurationReadout]: a duration sensor in any recognised time unit condenses
 * to a compact d/h/m/s readout; sub-minute values, unrecognised / missing units, and
 * non-duration sensors fall through to the default formatter (null).
 */
class FormatDurationReadoutTest {
    @Test fun `seconds condense to the two most significant units`() {
        assertThat(formatDurationReadout("duration", "3661", "s")).isEqualTo("1h 1m")
        assertThat(formatDurationReadout("duration", "3600", "s")).isEqualTo("1h 0m")
        assertThat(formatDurationReadout("duration", "90", "s")).isEqualTo("1m 30s")
        assertThat(formatDurationReadout("duration", "90000", "s")).isEqualTo("1d 1h")
        assertThat(formatDurationReadout("duration", "120", "seconds")).isEqualTo("2m 0s")
    }

    @Test fun `minutes hours and days are normalised to seconds first`() {
        assertThat(formatDurationReadout("duration", "5", "min")).isEqualTo("5m 0s")
        assertThat(formatDurationReadout("duration", "125", "min")).isEqualTo("2h 5m")
        assertThat(formatDurationReadout("duration", "2", "h")).isEqualTo("2h 0m")
        assertThat(formatDurationReadout("duration", "2", "d")).isEqualTo("2d 0h")
    }

    @Test fun `fractional values in larger units work`() {
        assertThat(formatDurationReadout("duration", "1.5", "h")).isEqualTo("1h 30m")
        // Sub-minute even after conversion still defers to the default formatter.
        assertThat(formatDurationReadout("duration", "0.5", "min")).isNull()
    }

    @Test fun `sub-minute, unrecognised, and missing units are left to the default formatter`() {
        assertThat(formatDurationReadout("duration", "45", "s")).isNull()
        assertThat(formatDurationReadout("duration", "5", "weeks")).isNull()
        assertThat(formatDurationReadout("duration", "300", null)).isNull()
    }

    @Test fun `non-duration and non-numeric never apply`() {
        assertThat(formatDurationReadout("temperature", "3661", "s")).isNull()
        assertThat(formatDurationReadout("duration", null, "s")).isNull()
        assertThat(formatDurationReadout("duration", "unknown", "s")).isNull()
    }
}
