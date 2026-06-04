package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [hvacActionLabel]: the common HA hvac_action values map to clean chip labels,
 * a blank/absent action yields null (no chip), and an unknown value degrades gracefully.
 */
class HvacActionLabelTest {
    @Test fun `known actions map to clean labels`() {
        assertThat(hvacActionLabel("heating")).isEqualTo("HEATING")
        assertThat(hvacActionLabel("cooling")).isEqualTo("COOLING")
        assertThat(hvacActionLabel("idle")).isEqualTo("IDLE")
        assertThat(hvacActionLabel("drying")).isEqualTo("DRYING")
        assertThat(hvacActionLabel("fan")).isEqualTo("FAN")
        assertThat(hvacActionLabel("off")).isEqualTo("OFF")
    }

    @Test fun `case and whitespace are normalised`() {
        assertThat(hvacActionLabel("Heating")).isEqualTo("HEATING")
        assertThat(hvacActionLabel("  idle ")).isEqualTo("IDLE")
    }

    @Test fun `absent or blank action yields null`() {
        assertThat(hvacActionLabel(null)).isNull()
        assertThat(hvacActionLabel("")).isNull()
        assertThat(hvacActionLabel("   ")).isNull()
    }

    @Test fun `unknown action degrades to underscore-stripped uppercase`() {
        assertThat(hvacActionLabel("preheating")).isEqualTo("PREHEATING")
        assertThat(hvacActionLabel("defrosting")).isEqualTo("DEFROSTING")
        assertThat(hvacActionLabel("some_new_action")).isEqualTo("SOME NEW ACTION")
    }
}
