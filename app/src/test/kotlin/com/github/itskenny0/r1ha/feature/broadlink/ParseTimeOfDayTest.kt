package com.github.itskenny0.r1ha.feature.broadlink

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class ParseTimeOfDayTest {

    @Test fun `accepts HH MM and pads to canonical form`() {
        assertThat(parseTimeOfDay("07:30")).isEqualTo("07:30:00")
        assertThat(parseTimeOfDay("7:5")).isEqualTo("07:05:00")
        assertThat(parseTimeOfDay(" 23:59 ")).isEqualTo("23:59:00")
    }

    @Test fun `accepts explicit seconds`() {
        assertThat(parseTimeOfDay("07:30:15")).isEqualTo("07:30:15")
    }

    @Test fun `rejects out-of-range and malformed input`() {
        assertThat(parseTimeOfDay("24:00")).isNull()
        assertThat(parseTimeOfDay("12:60")).isNull()
        assertThat(parseTimeOfDay("12:00:60")).isNull()
        assertThat(parseTimeOfDay("noon")).isNull()
        assertThat(parseTimeOfDay("12")).isNull()
        assertThat(parseTimeOfDay("1:2:3:4")).isNull()
        assertThat(parseTimeOfDay("")).isNull()
    }
}
