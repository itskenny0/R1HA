package com.github.itskenny0.r1ha.nav

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Coverage for the favourites-picker open policy. */
class FavoritesPickerNavTest {

    // A push timestamp old enough that the debounce never trips.
    private val noRecentOpen = 0L
    private val now = 10_000L

    @Test fun `null route is allowed`() {
        assertThat(shouldOpenFavoritesPicker(null, noRecentOpen, now)).isTrue()
    }

    @Test fun `plain deck route is allowed`() {
        assertThat(shouldOpenFavoritesPicker(Routes.CARD_STACK, noRecentOpen, now)).isTrue()
    }

    @Test fun `focus deck route is allowed`() {
        assertThat(shouldOpenFavoritesPicker(Routes.CARD_STACK_FOCUS, noRecentOpen, now)).isTrue()
    }

    @Test fun `arbitrary other route is allowed because the button only exists on the deck`() {
        assertThat(shouldOpenFavoritesPicker(Routes.SETTINGS, noRecentOpen, now)).isTrue()
        assertThat(shouldOpenFavoritesPicker("some/restored/route", noRecentOpen, now)).isTrue()
    }

    @Test fun `already on the picker is blocked`() {
        assertThat(shouldOpenFavoritesPicker(Routes.FAVORITES_PICKER, noRecentOpen, now)).isFalse()
    }

    @Test fun `second push inside the debounce window is blocked`() {
        val lastOpen = now - (FAVORITES_PICKER_DEBOUNCE_MILLIS - 1)
        assertThat(shouldOpenFavoritesPicker(Routes.CARD_STACK, lastOpen, now)).isFalse()
    }

    @Test fun `push after the debounce window is allowed`() {
        val lastOpen = now - FAVORITES_PICKER_DEBOUNCE_MILLIS
        assertThat(shouldOpenFavoritesPicker(Routes.CARD_STACK, lastOpen, now)).isTrue()
    }
}
