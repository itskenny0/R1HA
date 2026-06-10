package com.github.itskenny0.r1ha.core.ha

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class ExtEntityRegistryOptionsTest {

    private fun payload(raw: String): JsonObject = Json.parseToJsonElement(raw) as JsonObject

    @Test fun `parses cover favorite positions and tilt positions`() {
        val o = ExtEntityRegistryOptions.fromPayload(
            "cover",
            payload(
                """
                {"options":{"cover":{"favorite_positions":[0,50,100],"favorite_tilt_positions":[25,75]}}}
                """.trimIndent(),
            ),
        )
        assertThat(o.favoritePositions).containsExactly(0, 50, 100).inOrder()
        assertThat(o.hasFavoritePositions).isTrue()
        assertThat(o.favoriteTiltPositions).containsExactly(25, 75).inOrder()
        assertThat(o.hasFavoriteTiltPositions).isTrue()
    }

    @Test fun `parses lock default code`() {
        val o = ExtEntityRegistryOptions.fromPayload(
            "lock",
            payload("""{"options":{"lock":{"default_code":"1234"}}}"""),
        )
        assertThat(o.defaultCode).isEqualTo("1234")
    }

    @Test fun `parses light favorite colours as raw payloads`() {
        val o = ExtEntityRegistryOptions.fromPayload(
            "light",
            payload("""{"options":{"light":{"favorite_colors":[{"rgb_color":[255,0,0]},{"color_temp_kelvin":4000}]}}}"""),
        )
        assertThat(o.favoriteColors).hasSize(2)
        assertThat(o.hasFavoriteColors).isTrue()
        assertThat(o.favoriteColors[0].containsKey("rgb_color")).isTrue()
    }

    @Test fun `missing options degrade to empty without flagging presence`() {
        val o = ExtEntityRegistryOptions.fromPayload("cover", payload("""{"options":null}"""))
        assertThat(o.favoritePositions).isEmpty()
        assertThat(o.hasFavoritePositions).isFalse()
        assertThat(o).isEqualTo(ExtEntityRegistryOptions.EMPTY)
    }

    @Test fun `wrong-domain options are ignored`() {
        val o = ExtEntityRegistryOptions.fromPayload(
            "valve",
            payload("""{"options":{"cover":{"favorite_positions":[10]}}}"""),
        )
        assertThat(o).isEqualTo(ExtEntityRegistryOptions.EMPTY)
    }
}
