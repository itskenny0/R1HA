package com.github.itskenny0.r1ha.feature.moreinfo

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class MoreInfoRelatedTest {

    private val byArea = mapOf(
        "living" to listOf("light.lamp", "light.ceiling", "media_player.tv", "switch.plug"),
        "kitchen" to listOf("light.kitchen", "sensor.fridge"),
    )
    // light.lamp and light.ceiling share device d1; media_player.tv is d2.
    private val byEntity = mapOf(
        "light.lamp" to "d1",
        "light.ceiling" to "d1",
        "media_player.tv" to "d2",
    )

    @Test fun `same device lists the other entities on the device`() {
        val r = MoreInfoRelated.compute("light.lamp", byArea, byEntity)
        assertThat(r.sameDevice).containsExactly("light.ceiling")
    }

    @Test fun `same area excludes self and same-device entities`() {
        val r = MoreInfoRelated.compute("light.lamp", byArea, byEntity)
        // living-room members minus self (light.lamp) and same-device (light.ceiling).
        assertThat(r.sameArea).containsExactly("media_player.tv", "switch.plug").inOrder()
    }

    @Test fun `entity with no device has no same-device group`() {
        val r = MoreInfoRelated.compute("switch.plug", byArea, byEntity)
        assertThat(r.sameDevice).isEmpty()
        // Its area is living; self excluded.
        assertThat(r.sameArea).containsExactly("light.lamp", "light.ceiling", "media_player.tv").inOrder()
    }

    @Test fun `entity in no area has empty same-area`() {
        val r = MoreInfoRelated.compute("light.orphan", byArea, byEntity)
        assertThat(r.sameArea).isEmpty()
        assertThat(r.isEmpty).isTrue()
    }

    @Test fun `isEmpty is false when any group has members`() {
        val r = MoreInfoRelated.compute("light.lamp", byArea, byEntity)
        assertThat(r.isEmpty).isFalse()
    }
}
