package com.github.itskenny0.r1ha.feature.statistics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StatisticsPeriodTest {
    @Test fun `year period maps to the HA wire string`() {
        val year = StatisticsViewModel.Period.YEAR
        assertThat(year.wire).isEqualTo("year")
        assertThat(year.label).isEqualTo("YEAR")
    }
}
