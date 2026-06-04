package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [lightAccentArgb]: an off / colourless bulb yields null (role colour wins),
 * an HS bulb maps its hue to a vivid ARGB, and a colour-temp bulb ramps warm..cool.
 */
class LightAccentArgbTest {
    @Test fun `off bulb has no live colour`() {
        assertThat(lightAccentArgb(isOn = false, hueDeg = 200.0, colorTempK = 4000, null, null))
            .isNull()
    }

    @Test fun `plain on-off bulb with no colour data is null`() {
        assertThat(lightAccentArgb(isOn = true, hueDeg = null, colorTempK = null, null, null))
            .isNull()
    }

    @Test fun `hue maps to the primary at full saturation`() {
        // 0xAARRGGBB, full alpha, full-saturation primaries.
        assertThat(lightAccentArgb(true, 0.0, null, null, null)).isEqualTo(0xFFFF0000.toInt())
        assertThat(lightAccentArgb(true, 120.0, null, null, null)).isEqualTo(0xFF00FF00.toInt())
        assertThat(lightAccentArgb(true, 240.0, null, null, null)).isEqualTo(0xFF0000FF.toInt())
    }

    @Test fun `hue wraps past 360`() {
        assertThat(lightAccentArgb(true, 360.0, null, null, null))
            .isEqualTo(lightAccentArgb(true, 0.0, null, null, null))
    }

    @Test fun `hue takes priority over colour temp`() {
        // A bulb reporting both reads as its hue, not its kelvin.
        assertThat(lightAccentArgb(true, 0.0, 6500, null, null)).isEqualTo(0xFFFF0000.toInt())
    }

    @Test fun `colour temp ramps from warm to cool across the bulb range`() {
        // At the min the accent is the warm anchor; at the max it's the cool anchor.
        assertThat(lightAccentArgb(true, null, 2000, 2000, 6500)).isEqualTo(0xFFFFB46A.toInt())
        assertThat(lightAccentArgb(true, null, 6500, 2000, 6500)).isEqualTo(0xFFCFE0FF.toInt())
        // Mid-range lands strictly between the two anchors on every channel.
        val mid = lightAccentArgb(true, null, 4250, 2000, 6500)!!
        val r = (mid shr 16) and 0xFF
        assertThat(r).isLessThan(0xFF)
        assertThat(r).isGreaterThan(0xCF)
    }

    @Test fun `colour temp uses sane defaults when the bulb omits its range`() {
        // Defaults are 2000..6500, so 2000 K still reads as the warm anchor.
        assertThat(lightAccentArgb(true, null, 2000, null, null)).isEqualTo(0xFFFFB46A.toInt())
    }
}
