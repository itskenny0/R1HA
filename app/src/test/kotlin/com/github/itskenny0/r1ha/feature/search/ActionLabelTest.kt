package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.Domain
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Guards the tap-affordance label mapping extracted from SearchResultRow. The label
 * tells the user what tapping a result will do, so a regression here would mislead
 * (e.g. showing "ON" on a read-only sensor). Pure function, so it's cheap to pin every
 * branch.
 */
class ActionLabelTest {

    @Test
    fun `scenes and scripts fire`() {
        assertThat(actionLabelFor(Domain.SCENE, isOn = false)).isEqualTo("FIRE")
        assertThat(actionLabelFor(Domain.SCRIPT, isOn = true)).isEqualTo("FIRE")
    }

    @Test
    fun `buttons press`() {
        assertThat(actionLabelFor(Domain.BUTTON, isOn = false)).isEqualTo("PRESS")
        assertThat(actionLabelFor(Domain.INPUT_BUTTON, isOn = true)).isEqualTo("PRESS")
    }

    @Test
    fun `toggleable entities show the post-tap target state`() {
        // An entity currently ON offers to turn it OFF, and vice versa.
        assertThat(actionLabelFor(Domain.LIGHT, isOn = true)).isEqualTo("OFF")
        assertThat(actionLabelFor(Domain.LIGHT, isOn = false)).isEqualTo("ON")
        assertThat(actionLabelFor(Domain.SWITCH, isOn = true)).isEqualTo("OFF")
        assertThat(actionLabelFor(Domain.FAN, isOn = false)).isEqualTo("ON")
        assertThat(actionLabelFor(Domain.COVER, isOn = true)).isEqualTo("OFF")
        assertThat(actionLabelFor(Domain.LOCK, isOn = false)).isEqualTo("ON")
        assertThat(actionLabelFor(Domain.MEDIA_PLAYER, isOn = true)).isEqualTo("OFF")
        assertThat(actionLabelFor(Domain.INPUT_BOOLEAN, isOn = false)).isEqualTo("ON")
        assertThat(actionLabelFor(Domain.AUTOMATION, isOn = true)).isEqualTo("OFF")
        assertThat(actionLabelFor(Domain.CLIMATE, isOn = false)).isEqualTo("ON")
        assertThat(actionLabelFor(Domain.VALVE, isOn = true)).isEqualTo("OFF")
    }

    @Test
    fun `read-only entities surface info`() {
        assertThat(actionLabelFor(Domain.SENSOR, isOn = false)).isEqualTo("INFO")
        assertThat(actionLabelFor(Domain.BINARY_SENSOR, isOn = true)).isEqualTo("INFO")
    }
}
