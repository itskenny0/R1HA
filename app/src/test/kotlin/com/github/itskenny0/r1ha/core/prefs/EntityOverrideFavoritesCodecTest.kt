package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EntityOverrideFavoritesCodecTest {

    @Test fun `favorites survive an encode-decode round trip`() {
        val map = mapOf(
            "light.kitchen" to EntityOverride(
                favoriteColors = listOf(0xFFFF8800.toInt(), 0xFF0044FF.toInt()),
            ),
            "cover.garage" to EntityOverride(
                favoritePositions = listOf(0, 50, 100),
            ),
        )
        val decoded = decodeEntityOverrides_visibleForTesting(encodeEntityOverrides_visibleForTesting(map))
        assertThat(decoded["light.kitchen"]!!.favoriteColors)
            .containsExactly(0xFFFF8800.toInt(), 0xFF0044FF.toInt()).inOrder()
        assertThat(decoded["cover.garage"]!!.favoritePositions)
            .containsExactly(0, 50, 100).inOrder()
    }

    @Test fun `an older save without the favorite slots decodes to empty favorites`() {
        // 20-slot legacy row (slots 0..19), no trailing favorite slots.
        val legacy = "light.kitchen=?|?|?||?|?|?||?|?|?||?||?||?|?|?|?"
        val decoded = decodeEntityOverrides_visibleForTesting(legacy)
        val o = decoded["light.kitchen"]!!
        assertThat(o.favoriteColors).isEmpty()
        assertThat(o.favoritePositions).isEmpty()
    }
}
