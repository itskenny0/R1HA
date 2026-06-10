package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/** Unit tests for [MoreInfoControls]: the cross-cutting unavailable gate plus the
 *  per-domain capability checks (light white, lock open, water-heater away,
 *  climate target-humidity, fan toggle-only). */
class MoreInfoControlsTest {

    @Test fun `control disabled when unavailable`() {
        assertThat(MoreInfoControls.controlEnabled("unavailable", isAvailable = false)).isFalse()
    }

    @Test fun `control enabled when available even if state unknown`() {
        assertThat(MoreInfoControls.controlEnabled("unknown", isAvailable = true)).isTrue()
    }

    @Test fun `control disabled when state is unavailable string despite available flag`() {
        // Defensive: a stale isAvailable=true with an "unavailable" raw state still
        // locks the control out.
        assertThat(MoreInfoControls.controlEnabled("unavailable", isAvailable = true)).isFalse()
    }

    @Test fun `humidifier action label humanises`() {
        // optionLabel uppercases and de-underscores, matching the sheet's label style.
        assertThat(MoreInfoControls.humidifierActionLabel("humidifying")).isEqualTo("HUMIDIFYING")
        assertThat(MoreInfoControls.humidifierActionLabel("idle")).isEqualTo("IDLE")
    }

    @Test fun `humidifier action label null when absent`() {
        assertThat(MoreInfoControls.humidifierActionLabel(null)).isNull()
        assertThat(MoreInfoControls.humidifierActionLabel("")).isNull()
    }

    @Test fun `light white-mode detection`() {
        assertThat(MoreInfoControls.lightSupportsWhite(listOf("hs", "white"))).isTrue()
        assertThat(MoreInfoControls.lightSupportsWhite(listOf("hs", "color_temp"))).isFalse()
    }

    @Test fun `light white-channel detection`() {
        assertThat(MoreInfoControls.lightSupportsWhiteChannel(listOf("rgbw"))).isTrue()
        assertThat(MoreInfoControls.lightSupportsWhiteChannel(listOf("rgbww"))).isTrue()
        assertThat(MoreInfoControls.lightSupportsWhiteChannel(listOf("rgb"))).isFalse()
    }

    @Test fun `lock open requires explicit feature bit`() {
        assertThat(MoreInfoControls.lockSupportsOpen(1)).isTrue()
        assertThat(MoreInfoControls.lockSupportsOpen(0)).isFalse()
        assertThat(MoreInfoControls.lockSupportsOpen(2)).isFalse()
    }

    @Test fun `lock default code skips keypad`() {
        assertThat(MoreInfoControls.lockDefaultCode("1234")).isEqualTo("1234")
        assertThat(MoreInfoControls.lockDefaultCode("")).isNull()
        assertThat(MoreInfoControls.lockDefaultCode(null)).isNull()
    }

    @Test fun `fan toggle-only when no speed and no presets`() {
        assertThat(MoreInfoControls.fanIsToggleOnly(supportsSetSpeed = false, hasPresetModes = false)).isTrue()
        assertThat(MoreInfoControls.fanIsToggleOnly(supportsSetSpeed = true, hasPresetModes = false)).isFalse()
        assertThat(MoreInfoControls.fanIsToggleOnly(supportsSetSpeed = false, hasPresetModes = true)).isFalse()
    }

    @Test fun `water heater away support forgives omitted bitmask`() {
        assertThat(MoreInfoControls.waterHeaterSupportsAway(0)).isTrue()
        assertThat(MoreInfoControls.waterHeaterSupportsAway(4)).isTrue()
        assertThat(MoreInfoControls.waterHeaterSupportsAway(2)).isFalse()
    }

    @Test fun `climate target humidity requires explicit bit`() {
        assertThat(MoreInfoControls.climateSupportsTargetHumidity(4)).isTrue()
        assertThat(MoreInfoControls.climateSupportsTargetHumidity(1)).isFalse()
        assertThat(MoreInfoControls.climateSupportsTargetHumidity(0)).isFalse()
    }
}
