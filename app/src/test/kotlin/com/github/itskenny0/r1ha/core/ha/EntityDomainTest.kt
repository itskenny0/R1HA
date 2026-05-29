package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class EntityDomainTest {
    @Test fun `fromPrefix maps known prefixes`() {
        assertThat(Domain.fromPrefix("light")).isEqualTo(Domain.LIGHT)
        assertThat(Domain.fromPrefix("fan")).isEqualTo(Domain.FAN)
        assertThat(Domain.fromPrefix("cover")).isEqualTo(Domain.COVER)
        assertThat(Domain.fromPrefix("media_player")).isEqualTo(Domain.MEDIA_PLAYER)
        assertThat(Domain.fromPrefix("switch")).isEqualTo(Domain.SWITCH)
        assertThat(Domain.fromPrefix("input_boolean")).isEqualTo(Domain.INPUT_BOOLEAN)
        assertThat(Domain.fromPrefix("automation")).isEqualTo(Domain.AUTOMATION)
        assertThat(Domain.fromPrefix("lock")).isEqualTo(Domain.LOCK)
        assertThat(Domain.fromPrefix("humidifier")).isEqualTo(Domain.HUMIDIFIER)
        assertThat(Domain.fromPrefix("sensor")).isEqualTo(Domain.SENSOR)
        assertThat(Domain.fromPrefix("binary_sensor")).isEqualTo(Domain.BINARY_SENSOR)
        assertThat(Domain.fromPrefix("alarm_control_panel")).isEqualTo(Domain.ALARM_CONTROL_PANEL)
    }

    @Test fun `fromPrefix rejects unknown prefix`() {
        // Domains the app deliberately doesn't surface yet — device_tracker / weather /
        // sun / etc. are read-only state surfaces without a clean R1 affordance.
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("device_tracker") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("weather") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("sun") }
        assertThrows<IllegalArgumentException> { Domain.fromPrefix("") }
    }
}
