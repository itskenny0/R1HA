package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [dangerBinaryTriggered]: only a danger-class binary sensor that is currently
 * on counts as triggered (and so renders red); everything else is false.
 */
class DangerBinaryTriggeredTest {
    @Test fun `danger classes count when on`() {
        for (dc in listOf("smoke", "gas", "carbon_monoxide", "moisture", "safety", "problem", "tamper")) {
            assertThat(dangerBinaryTriggered(dc, isOn = true)).isTrue()
        }
        // Case-insensitive.
        assertThat(dangerBinaryTriggered("SMOKE", isOn = true)).isTrue()
    }

    @Test fun `off danger sensors are not triggered`() {
        assertThat(dangerBinaryTriggered("smoke", isOn = false)).isFalse()
        assertThat(dangerBinaryTriggered("gas", isOn = false)).isFalse()
    }

    @Test fun `non-danger classes never count`() {
        assertThat(dangerBinaryTriggered("motion", isOn = true)).isFalse()
        assertThat(dangerBinaryTriggered("door", isOn = true)).isFalse()
        assertThat(dangerBinaryTriggered("occupancy", isOn = true)).isFalse()
        assertThat(dangerBinaryTriggered(null, isOn = true)).isFalse()
    }
}
