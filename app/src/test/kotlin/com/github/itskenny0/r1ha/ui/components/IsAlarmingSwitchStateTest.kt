package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [isAlarmingSwitchState]: a jammed lock, an errored vacuum/mower and a triggered
 * alarm read as alarming (red); ordinary on/off states do not.
 */
class IsAlarmingSwitchStateTest {
    @Test fun `jammed error and triggered are alarming`() {
        assertThat(isAlarmingSwitchState("jammed")).isTrue()
        assertThat(isAlarmingSwitchState("error")).isTrue()
        assertThat(isAlarmingSwitchState("triggered")).isTrue()
        assertThat(isAlarmingSwitchState("JAMMED")).isTrue()
        assertThat(isAlarmingSwitchState(" error ")).isTrue()
    }

    @Test fun `ordinary states are not alarming`() {
        assertThat(isAlarmingSwitchState("on")).isFalse()
        assertThat(isAlarmingSwitchState("off")).isFalse()
        assertThat(isAlarmingSwitchState("locked")).isFalse()
        assertThat(isAlarmingSwitchState("cleaning")).isFalse()
        assertThat(isAlarmingSwitchState(null)).isFalse()
    }
}
