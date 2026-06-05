package com.github.itskenny0.r1ha.feature.floors

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-helper coverage for the Floors surface: the pluralised area / entity
 * tally shown on each floor row (and spoken by the accessibility label).
 */
class FloorsViewModelTest {
    @Test fun `floorTally pluralises single area and entity`() {
        assertThat(FloorsViewModel.floorTally(areaCount = 1, entityCount = 1, separator = " · "))
            .isEqualTo("1 area · 1 entity")
    }

    @Test fun `floorTally pluralises multiples`() {
        assertThat(FloorsViewModel.floorTally(areaCount = 3, entityCount = 12, separator = " · "))
            .isEqualTo("3 areas · 12 entities")
    }

    @Test fun `floorTally handles a mixed singular and plural`() {
        assertThat(FloorsViewModel.floorTally(areaCount = 1, entityCount = 0, separator = " · "))
            .isEqualTo("1 area · 0 entities")
    }

    @Test fun `floorTally honours the spoken-label separator`() {
        assertThat(FloorsViewModel.floorTally(areaCount = 2, entityCount = 1, separator = ", "))
            .isEqualTo("2 areas, 1 entity")
    }
}
