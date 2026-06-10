package com.github.itskenny0.r1ha.feature.areas

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class AreasA11yTest {

    @Test fun `summary reads as one sentence`() {
        assertThat(areasSummaryDescription(areas = 12, entities = 240))
            .isEqualTo("Areas. 12 areas, 240 entities. Open an area to control its entities.")
    }

    @Test fun `summary pluralises singular counts`() {
        assertThat(areasSummaryDescription(areas = 1, entities = 1))
            .isEqualTo("Areas. 1 area, 1 entity. Open an area to control its entities.")
    }

    @Test fun `summary spells out zero counts`() {
        assertThat(areasSummaryDescription(areas = 0, entities = 0))
            .isEqualTo("Areas. no areas, no entities. Open an area to control its entities.")
    }
}
