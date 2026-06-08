package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.Instant

class DistributionWeightsTest {

    private fun stateWithRawState(rawState: String?) = EntityState(
        id = EntityId("sensor.test"),
        friendlyName = "Test",
        area = null,
        isOn = true,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
        rawState = rawState,
    )

    @Test fun `distributionValueOf null state returns null`() {
        assertThat(distributionValueOf(null)).isNull()
    }

    @Test fun `distributionValueOf numeric rawState parses to double`() {
        assertThat(distributionValueOf(stateWithRawState("42.5"))).isEqualTo(42.5)
    }

    @Test fun `distributionValueOf non-numeric rawState returns null`() {
        assertThat(distributionValueOf(stateWithRawState("unavailable"))).isNull()
        assertThat(distributionValueOf(stateWithRawState("on"))).isNull()
        assertThat(distributionValueOf(stateWithRawState(null))).isNull()
    }



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
