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

    // ── RGBW / RGBWW white channel + color brightness ────────────────────────

    @Test fun `rgbw and rgbww modes are detected`() {
        assertThat(MoreInfoControls.lightSupportsRgbw(listOf("rgbw"))).isTrue()
        assertThat(MoreInfoControls.lightSupportsRgbww(listOf("rgbww"))).isTrue()
        assertThat(MoreInfoControls.lightSupportsRgbw(listOf("rgbww"))).isFalse()
        assertThat(MoreInfoControls.lightSupportsWhiteChannel(listOf("rgb"))).isFalse()
    }

    @Test fun `white channel percent maps to a 0-255 byte`() {
        assertThat(MoreInfoControls.whiteChannelByte(0)).isEqualTo(0)
        assertThat(MoreInfoControls.whiteChannelByte(100)).isEqualTo(255)
        // HA: round(50 * 255 / 100) = 128.
        assertThat(MoreInfoControls.whiteChannelByte(50)).isEqualTo(128)
        assertThat(MoreInfoControls.whiteChannelByte(150)).isEqualTo(255)
    }

    @Test fun `rgbw white preserves the rgb part`() {
        val out = MoreInfoControls.rgbwColorForWhite(currentRgb = listOf(10, 20, 30), whitePercent = 100)
        assertThat(out).containsExactly(10, 20, 30, 255).inOrder()
    }

    @Test fun `rgbw white defaults rgb to zero when none`() {
        val out = MoreInfoControls.rgbwColorForWhite(currentRgb = null, whitePercent = 0)
        assertThat(out).containsExactly(0, 0, 0, 0).inOrder()
    }

    @Test fun `rgbww cold white preserves rgb and warm white`() {
        val out = MoreInfoControls.rgbwwColorForWhite(
            currentRgbww = listOf(1, 2, 3, 40, 50),
            channel = MoreInfoControls.RgbwwChannel.COLD,
            whitePercent = 100,
        )
        // r,g,b kept, cold-white (index 3) overwritten, warm-white (index 4) kept.
        assertThat(out).containsExactly(1, 2, 3, 255, 50).inOrder()
    }

    @Test fun `rgbww warm white overwrites only index four`() {
        val out = MoreInfoControls.rgbwwColorForWhite(
            currentRgbww = listOf(1, 2, 3, 40, 50),
            channel = MoreInfoControls.RgbwwChannel.WARM,
            whitePercent = 0,
        )
        assertThat(out).containsExactly(1, 2, 3, 40, 0).inOrder()
    }

    @Test fun `rgbww pads a short or null source to five entries`() {
        val out = MoreInfoControls.rgbwwColorForWhite(
            currentRgbww = null,
            channel = MoreInfoControls.RgbwwChannel.COLD,
            whitePercent = 100,
        )
        assertThat(out).containsExactly(0, 0, 0, 255, 0).inOrder()
    }

    @Test fun `color brightness scales the rgb channels`() {
        // value/255 = 128/255 ~ 0.502; 255 * ratio ~ 128.
        val out = MoreInfoControls.adjustColorBrightness(listOf(255, 255, 255), brightnessPercent = 50)
        assertThat(out).containsExactly(128, 128, 128).inOrder()
    }

    @Test fun `color brightness normalises black to white`() {
        val out = MoreInfoControls.adjustColorBrightness(listOf(0, 0, 0), brightnessPercent = 100)
        assertThat(out).containsExactly(255, 255, 255).inOrder()
    }
}
