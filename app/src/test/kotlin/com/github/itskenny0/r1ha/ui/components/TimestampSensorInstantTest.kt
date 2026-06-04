package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [timestampSensorInstant]: only a `device_class: timestamp` sensor with a
 * parseable HA timestamp yields an instant (so the card renders a relative readout);
 * everything else is null and falls back to the raw value.
 */
class TimestampSensorInstantTest {
    @Test fun `parses a timestamp sensor's HA instant`() {
        assertThat(timestampSensorInstant("timestamp", "2026-06-04T08:31:45+00:00")).isNotNull()
        assertThat(timestampSensorInstant("timestamp", "2026-06-04T08:31:45Z")).isNotNull()
        // Case-insensitive device_class.
        assertThat(timestampSensorInstant("Timestamp", "2026-06-04T08:31:45Z")).isNotNull()
    }

    @Test fun `non-timestamp or unparseable yields null`() {
        assertThat(timestampSensorInstant("temperature", "2026-06-04T08:31:45Z")).isNull()
        assertThat(timestampSensorInstant("timestamp", "not a date")).isNull()
        assertThat(timestampSensorInstant("timestamp", null)).isNull()
        assertThat(timestampSensorInstant(null, "2026-06-04T08:31:45Z")).isNull()
    }
}
