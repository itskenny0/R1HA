package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [hvacModeLabel]: the multi-word HA modes lose their underscores ("heat_cool"
 * reads HEAT/COOL, "fan_only" reads FAN ONLY) and unknown modes still degrade gracefully.
 */
class HvacModeLabelTest {
    @Test fun `single-word modes uppercase as-is`() {
        assertThat(hvacModeLabel("heat")).isEqualTo("HEAT")
        assertThat(hvacModeLabel("cool")).isEqualTo("COOL")
        assertThat(hvacModeLabel("auto")).isEqualTo("AUTO")
        assertThat(hvacModeLabel("dry")).isEqualTo("DRY")
        assertThat(hvacModeLabel("off")).isEqualTo("OFF")
    }

    @Test fun `multi-word modes read cleanly`() {
        assertThat(hvacModeLabel("heat_cool")).isEqualTo("HEAT/COOL")
        assertThat(hvacModeLabel("fan_only")).isEqualTo("FAN ONLY")
    }

    @Test fun `case and whitespace are normalised`() {
        assertThat(hvacModeLabel("Heat_Cool")).isEqualTo("HEAT/COOL")
        assertThat(hvacModeLabel("  fan_only  ")).isEqualTo("FAN ONLY")
    }

    @Test fun `unknown mode falls back to underscore-stripped uppercase`() {
        assertThat(hvacModeLabel("eco_boost")).isEqualTo("ECO BOOST")
        assertThat(hvacModeLabel(null)).isEqualTo("")
    }
}
