package com.github.itskenny0.r1ha.feature.favoritespicker

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Gating for the picker's ADD ALL chip ([shouldOfferBulkAdd]). The chip is a
 * one-tap bulk mutation, so the tests pin down the cases where it must NOT
 * appear (unscoped ALL view, FAVS, nothing meaningful to add) as carefully as
 * the ones where it should.
 */
class BulkAddGateTest {

    @Test
    fun `offered on a domain chip with several addable rows`() {
        assertThat(shouldOfferBulkAdd(PickerFilter.LIGHTS, query = "", addableCount = 15)).isTrue()
    }

    @Test
    fun `offered on ALL only when a search scopes the view`() {
        assertThat(shouldOfferBulkAdd(PickerFilter.ALL, query = "kitchen", addableCount = 5)).isTrue()
        // Unscoped ALL: one reflexive tap must not dump the whole install.
        assertThat(shouldOfferBulkAdd(PickerFilter.ALL, query = "", addableCount = 5)).isFalse()
        // Whitespace is not a scope.
        assertThat(shouldOfferBulkAdd(PickerFilter.ALL, query = "   ", addableCount = 5)).isFalse()
    }

    @Test
    fun `never offered on the FAVS tab`() {
        assertThat(shouldOfferBulkAdd(PickerFilter.FAVS, query = "", addableCount = 5)).isFalse()
        // Even with a search active: FAVS rows are already favourites; a non-zero
        // addableCount there would be a row-building bug, not a bulk-add case.
        assertThat(shouldOfferBulkAdd(PickerFilter.FAVS, query = "lamp", addableCount = 5)).isFalse()
    }

    @Test
    fun `not offered for zero or one addable row`() {
        assertThat(shouldOfferBulkAdd(PickerFilter.LIGHTS, query = "", addableCount = 0)).isFalse()
        // A single row's own checkbox is the right affordance; "ADD ALL · 1" is noise.
        assertThat(shouldOfferBulkAdd(PickerFilter.LIGHTS, query = "", addableCount = 1)).isFalse()
        assertThat(shouldOfferBulkAdd(PickerFilter.LIGHTS, query = "", addableCount = 2)).isTrue()
    }

    @Test
    fun `search scopes any non-FAVS chip`() {
        assertThat(shouldOfferBulkAdd(PickerFilter.SENSORS, query = "door", addableCount = 3)).isTrue()
        assertThat(shouldOfferBulkAdd(PickerFilter.SCENES, query = "night", addableCount = 2)).isTrue()
    }
}
