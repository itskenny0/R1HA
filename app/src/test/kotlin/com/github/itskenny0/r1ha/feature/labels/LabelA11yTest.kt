package com.github.itskenny0.r1ha.feature.labels

import com.google.common.truth.Truth.assertThat
import java.util.Locale
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LabelA11yTest {

    @BeforeEach
    fun setLocale() {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `label row label reads count in words and collapsed state`() {
        assertThat(LabelLogic.labelRowLabel("Batteries", 3, expanded = false))
            .isEqualTo("Label Batteries. 3 tagged items. Tap to expand.")
    }

    @Test
    fun `label row label singular`() {
        assertThat(LabelLogic.labelRowLabel("Routine", 1, expanded = false))
            .isEqualTo("Label Routine. 1 tagged item. Tap to expand.")
    }

    @Test
    fun `label row label empty count`() {
        assertThat(LabelLogic.labelRowLabel("Unused", 0, expanded = false))
            .isEqualTo("Label Unused. nothing tagged. Tap to expand.")
    }

    @Test
    fun `label row label expanded announces collapse`() {
        assertThat(LabelLogic.labelRowLabel("Batteries", 2, expanded = true))
            .isEqualTo("Label Batteries. 2 tagged items. Expanded. Tap to collapse.")
    }

    @Test
    fun `blank label name falls back`() {
        assertThat(LabelLogic.labelRowLabel("   ", 1, expanded = false))
            .isEqualTo("Label Unnamed label. 1 tagged item. Tap to expand.")
    }

    @Test
    fun `tappable entity member announces open action`() {
        assertThat(
            LabelLogic.memberRowLabel("Kitchen Light", LabelLogic.MemberKind.ENTITY, tappable = true),
        ).isEqualTo("Entity Kitchen Light. Tap to open history in Home Assistant.")
    }

    @Test
    fun `non-tappable device member omits action`() {
        assertThat(
            LabelLogic.memberRowLabel("Hub", LabelLogic.MemberKind.DEVICE, tappable = false),
        ).isEqualTo("Device Hub")
    }

    @Test
    fun `area member kind word`() {
        assertThat(
            LabelLogic.memberRowLabel("Garage", LabelLogic.MemberKind.AREA, tappable = false),
        ).isEqualTo("Area Garage")
    }

    @Test
    fun `blank member name falls back`() {
        assertThat(
            LabelLogic.memberRowLabel("  ", LabelLogic.MemberKind.ENTITY, tappable = false),
        ).isEqualTo("Entity Unnamed")
    }
}
