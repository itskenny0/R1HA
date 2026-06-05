package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [binaryWord]'s device-class wording: the curated short labels for HA's
 * binary_sensor classes and the ON/OFF fallback for unknown or absent classes.
 */
class BinaryWordTest {
    @Test fun `moving reports MOVING and STOPPED`() {
        assertThat(binaryWord("moving", on = true)).isEqualTo("MOVING")
        assertThat(binaryWord("moving", on = false)).isEqualTo("STOPPED")
    }

    @Test fun `representative classes keep their curated words`() {
        assertThat(binaryWord("door", on = true)).isEqualTo("OPEN")
        assertThat(binaryWord("door", on = false)).isEqualTo("CLOSED")
        assertThat(binaryWord("motion", on = true)).isEqualTo("MOTION")
        assertThat(binaryWord("lock", on = false)).isEqualTo("LOCKED")
        assertThat(binaryWord("connectivity", on = false)).isEqualTo("OFFLINE")
    }

    @Test fun `unknown and null classes fall back to ON and OFF`() {
        assertThat(binaryWord("not_a_real_class", on = true)).isEqualTo("ON")
        assertThat(binaryWord(null, on = false)).isEqualTo("OFF")
    }
}
