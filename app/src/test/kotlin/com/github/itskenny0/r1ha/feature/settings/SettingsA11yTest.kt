package com.github.itskenny0.r1ha.feature.settings

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SettingsA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun switchRow_statesValueInWords() {
        assertThat(
            SettingsA11y.switchRowDescription("Haptic feedback", "Vibrate on tap", checked = true),
        ).isEqualTo("Haptic feedback, Vibrate on tap, on")
        assertThat(
            SettingsA11y.switchRowDescription("Keep screen on", subtitle = null, checked = false),
        ).isEqualTo("Keep screen on, off")
    }

    @Test
    fun switchRow_blankSubtitleOmitted() {
        assertThat(
            SettingsA11y.switchRowDescription("Guest mode", "   ", checked = true),
        ).isEqualTo("Guest mode, on")
    }

    @Test
    fun switchToggleHint_reflectsState() {
        assertThat(SettingsA11y.switchToggleHint(checked = true)).isEqualTo("Double tap to turn off")
        assertThat(SettingsA11y.switchToggleHint(checked = false)).isEqualTo("Double tap to turn on")
    }

    @Test
    fun infoRow_joinsLabelAndValue() {
        assertThat(SettingsA11y.infoRowDescription("HA version", "2025.5.1"))
            .isEqualTo("HA version, 2025.5.1")
        assertThat(SettingsA11y.infoRowDescription("Status", "  "))
            .isEqualTo("Status")
    }

    @Test
    fun categoryRow_spellsOutBadge() {
        assertThat(SettingsA11y.categoryRowDescription("Appearance", badge = 0))
            .isEqualTo("Open Appearance")
        assertThat(SettingsA11y.categoryRowDescription("Appearance", badge = 1))
            .isEqualTo("Open Appearance, 1 changed setting")
        assertThat(SettingsA11y.categoryRowDescription("Behaviour", badge = 3))
            .isEqualTo("Open Behaviour, 3 changed settings")
    }

    @Test
    fun modifiedBadge_pluralizes() {
        assertThat(SettingsA11y.modifiedBadgeDescription(1)).isEqualTo("1 changed setting")
        assertThat(SettingsA11y.modifiedBadgeDescription(4)).isEqualTo("4 changed settings")
    }
}
