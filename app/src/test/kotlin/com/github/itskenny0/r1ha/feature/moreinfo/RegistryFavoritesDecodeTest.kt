package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/** Unit tests for [decodeRegistryFavorites]: the per-domain favourites codec that
 *  reads `options[<domain>]` out of an entity registry options blob. */
class RegistryFavoritesDecodeTest {

    private fun opts(json: String): JsonObject = Json.parseToJsonElement(json) as JsonObject

    @Test fun `null options yields empty favorites`() {
        val fav = decodeRegistryFavorites(null, "cover")
        assertThat(fav.positions).isEmpty()
        assertThat(fav.tiltPositions).isEmpty()
        assertThat(fav.colors).isEmpty()
    }

    @Test fun `cover positions and tilt positions decode`() {
        val fav = decodeRegistryFavorites(
            opts("""{"cover":{"favorite_positions":[10,90],"favorite_tilt_positions":[0,100]}}"""),
            "cover",
        )
        assertThat(fav.positions).containsExactly(10, 90).inOrder()
        assertThat(fav.tiltPositions).containsExactly(0, 100).inOrder()
    }

    @Test fun `wrong domain key yields empty`() {
        val fav = decodeRegistryFavorites(
            opts("""{"valve":{"favorite_positions":[5]}}"""),
            "cover",
        )
        assertThat(fav.positions).isEmpty()
    }

    @Test fun `light favorite colors decode color-temp and rgb`() {
        val fav = decodeRegistryFavorites(
            opts(
                """{"light":{"favorite_colors":[
                    {"color_temp_kelvin":3000},
                    {"rgb_color":[255,0,0]},
                    {"rgbw_color":[0,255,0,128]}
                ]}}""",
            ),
            "light",
        )
        assertThat(fav.colors).hasSize(3)
        assertThat(fav.colors[0]).isEqualTo(FavoriteColor.ColorTemp(3000))
        assertThat(fav.colors[1]).isEqualTo(FavoriteColor.Rgb((0xFF shl 24) or (255 shl 16)))
        // rgbw flattens to its RGB triple (white channel dropped from the swatch).
        assertThat(fav.colors[2]).isEqualTo(FavoriteColor.Rgb((0xFF shl 24) or (255 shl 8)))
    }

    @Test fun `malformed color entries are skipped`() {
        val fav = decodeRegistryFavorites(
            opts("""{"light":{"favorite_colors":[{},{"rgb_color":[1,2]},{"color_temp_kelvin":4000}]}}"""),
            "light",
        )
        // Empty map and the short rgb pair are skipped; only the kelvin survives.
        assertThat(fav.colors).containsExactly(FavoriteColor.ColorTemp(4000))
    }

    @Test fun `position encoder round-trips through the decoder`() {
        val encoded = JsonObject(mapOf("cover" to encodeFavoritePositions(listOf(0, 25, 100))))
        val fav = decodeRegistryFavorites(encoded, "cover")
        assertThat(fav.positions).containsExactly(0, 25, 100).inOrder()
    }

    @Test fun `colour encoder round-trips through the decoder`() {
        val colors = listOf(
            FavoriteColor.ColorTemp(3000),
            FavoriteColor.Rgb((0xFF shl 24) or (0x11 shl 16) or (0x22 shl 8) or 0x33),
        )
        val encoded = JsonObject(mapOf("light" to encodeFavoriteColors(colors)))
        val fav = decodeRegistryFavorites(encoded, "light")
        assertThat(fav.colors).isEqualTo(colors)
    }
}
