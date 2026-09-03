package com.github.itskenny0.r1ha.core.ambient

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WakeKeySwallowTest {

    @Test fun `passes keys through when not idle`() {
        val s = WakeKeySwallow()
        assertThat(s.shouldSwallow(keyCode = 4, isDown = true, swallowNow = false)).isFalse()
        assertThat(s.shouldSwallow(keyCode = 4, isDown = false, swallowNow = false)).isFalse()
    }

    @Test fun `swallows the waking DOWN and its UP even after the idle flag cleared`() {
        val s = WakeKeySwallow()
        assertThat(s.shouldSwallow(keyCode = 4, isDown = true, swallowNow = true)).isTrue()
        // Idle flag already cleared by the time the finger lifts.
        assertThat(s.shouldSwallow(keyCode = 4, isDown = false, swallowNow = false)).isTrue()
        // The next press of the same key is a normal press.
        assertThat(s.shouldSwallow(keyCode = 4, isDown = true, swallowNow = false)).isFalse()
        assertThat(s.shouldSwallow(keyCode = 4, isDown = false, swallowNow = false)).isFalse()
    }

    @Test fun `an UP swallowed while still idle clears the latch`() {
        val s = WakeKeySwallow()
        assertThat(s.shouldSwallow(keyCode = 24, isDown = true, swallowNow = true)).isTrue()
        assertThat(s.shouldSwallow(keyCode = 24, isDown = false, swallowNow = true)).isTrue()
        assertThat(s.shouldSwallow(keyCode = 24, isDown = false, swallowNow = false)).isFalse()
    }

    @Test fun `a different key's UP is not swallowed`() {
        val s = WakeKeySwallow()
        s.shouldSwallow(keyCode = 4, isDown = true, swallowNow = true)
        assertThat(s.shouldSwallow(keyCode = 25, isDown = false, swallowNow = false)).isFalse()
    }
}
