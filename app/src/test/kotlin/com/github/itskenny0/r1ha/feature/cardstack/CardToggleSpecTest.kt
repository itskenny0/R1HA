package com.github.itskenny0.r1ha.feature.cardstack

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class CardToggleSpecTest {

    @Test
    fun buttonExposesNameIconStateColor() {
        val keys = cardTogglesFor("button").map { it.key }
        assertThat(keys).containsExactly("show_name", "show_icon", "show_state", "state_color").inOrder()
    }

    @Test
    fun glanceExposesItsToggles() {
        assertThat(cardTogglesFor("glance").map { it.key })
            .containsExactly("show_name", "show_icon", "show_state", "state_color").inOrder()
    }

    @Test
    fun tileHideStateUsesHideSense() {
        val t = cardTogglesFor("tile").first { it.key == "hide_state" }
        assertThat(t.sense).isEqualTo(ToggleSense.HIDE)
        assertThat(t.default).isFalse()
    }

    @Test
    fun typesWithoutTogglesReturnEmpty() {
        assertThat(cardTogglesFor("markdown")).isEmpty()
        assertThat(cardTogglesFor("calendar")).isEmpty()
        assertThat(cardTogglesFor("unknown-type")).isEmpty()
    }

    @Test
    fun hideSenseInvertsChipAndStorage() {
        // HIDE-sense: a config value of hide_state:false means the state IS shown.
        assertThat(toggleChipShown(raw = false, sense = ToggleSense.HIDE)).isTrue()
        assertThat(toggleChipShown(raw = true, sense = ToggleSense.HIDE)).isFalse()
        // Round-trips back to the stored key value.
        assertThat(toggleStoredValue(chipShown = true, sense = ToggleSense.HIDE)).isFalse()
        assertThat(toggleStoredValue(chipShown = false, sense = ToggleSense.HIDE)).isTrue()
        // SHOW-sense is identity.
        assertThat(toggleChipShown(raw = true, sense = ToggleSense.SHOW)).isTrue()
        assertThat(toggleStoredValue(chipShown = true, sense = ToggleSense.SHOW)).isTrue()
    }

    @Test
    fun triStateCycles() {
        assertThat(triStateNext(null)).isTrue()
        assertThat(triStateNext(true)).isFalse()
        assertThat(triStateNext(false)).isNull()
    }

    @Test
    fun entitiesRowExposesNativeSubToggles() {
        assertThat(rowTogglesFor("entities").map { it.key })
            .containsAtLeast("show_state", "state_color")
    }
}
