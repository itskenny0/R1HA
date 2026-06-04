package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.theme.CardRenderModel
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [climateAccentRole]: cool-family modes read cool, off reads neutral, and heat /
 * auto / an absent mode keep the warm default.
 */
class ClimateAccentRoleTest {
    @Test fun `cool family reads cool`() {
        assertThat(climateAccentRole("cool")).isEqualTo(CardRenderModel.AccentRole.COOL)
        assertThat(climateAccentRole("dry")).isEqualTo(CardRenderModel.AccentRole.COOL)
        assertThat(climateAccentRole("fan_only")).isEqualTo(CardRenderModel.AccentRole.COOL)
        // Case-insensitive.
        assertThat(climateAccentRole("COOL")).isEqualTo(CardRenderModel.AccentRole.COOL)
    }

    @Test fun `off reads neutral`() {
        assertThat(climateAccentRole("off")).isEqualTo(CardRenderModel.AccentRole.NEUTRAL)
    }

    @Test fun `heat auto and unknown keep warm`() {
        assertThat(climateAccentRole("heat")).isEqualTo(CardRenderModel.AccentRole.WARM)
        assertThat(climateAccentRole("heat_cool")).isEqualTo(CardRenderModel.AccentRole.WARM)
        assertThat(climateAccentRole("auto")).isEqualTo(CardRenderModel.AccentRole.WARM)
        assertThat(climateAccentRole(null)).isEqualTo(CardRenderModel.AccentRole.WARM)
    }
}
