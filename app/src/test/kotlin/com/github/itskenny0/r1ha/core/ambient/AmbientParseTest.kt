package com.github.itskenny0.r1ha.core.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AmbientParseTest {

    @Test fun `firstInt parses a plain template count`() {
        assertThat(AmbientParse.firstInt("4")).isEqualTo(4)
        assertThat(AmbientParse.firstInt(" 12 ")).isEqualTo(12)
    }

    @Test fun `firstInt returns null for blank or non-numeric input`() {
        assertThat(AmbientParse.firstInt(null)).isNull()
        assertThat(AmbientParse.firstInt("")).isNull()
        assertThat(AmbientParse.firstInt("unknown")).isNull()
    }

    @Test fun `firstDouble tolerates integer and decimal watt strings`() {
        assertThat(AmbientParse.firstDouble("0")).isWithin(0.001).of(0.0)
        assertThat(AmbientParse.firstDouble("1234.5")).isWithin(0.001).of(1234.5)
        assertThat(AmbientParse.firstDouble("n/a")).isNull()
    }
}
