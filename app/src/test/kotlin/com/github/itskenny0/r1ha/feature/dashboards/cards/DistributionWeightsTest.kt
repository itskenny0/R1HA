package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DistributionWeightsTest {

    @Test fun `weights are proportional to values`() {
        val w = distributionWeights(listOf(25.0, 75.0))
        assertThat(w).containsExactly(0.25f, 0.75f).inOrder()
    }

    @Test fun `non-numeric and negative values count as zero`() {
        val w = distributionWeights(listOf(50.0, null, -10.0, 50.0))
        assertThat(w).containsExactly(0.5f, 0f, 0f, 0.5f).inOrder()
    }

    @Test fun `all-zero or empty sum yields all-zero weights`() {
        assertThat(distributionWeights(listOf(0.0, 0.0))).containsExactly(0f, 0f).inOrder()
        assertThat(distributionWeights(emptyList())).isEmpty()
    }
}
