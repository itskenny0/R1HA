package com.github.itskenny0.r1ha.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [brightness255ToPct]: nearest-percent rounding (so 254/255 is 100%, not the 99%
 * truncation produced) and input coercion for out-of-range manufacturer values.
 */
class BrightnessTest {
    @Test fun `rounds to the nearest percent`() {
        assertThat(brightness255ToPct(255)).isEqualTo(100)
        assertThat(brightness255ToPct(254)).isEqualTo(100)
        assertThat(brightness255ToPct(128)).isEqualTo(50)
        assertThat(brightness255ToPct(0)).isEqualTo(0)
        assertThat(brightness255ToPct(1)).isEqualTo(0)
    }

    @Test fun `coerces out-of-range input into 0 to 100`() {
        assertThat(brightness255ToPct(300)).isEqualTo(100)
        assertThat(brightness255ToPct(-5)).isEqualTo(0)
    }
}
