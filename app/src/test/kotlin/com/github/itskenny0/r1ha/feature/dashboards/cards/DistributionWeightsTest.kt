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

    @Test fun `SI prefix scales kilo and milli`() {
        assertThat(normalizeBySiPrefix(2.0, "kW")).isEqualTo(2000.0)
        assertThat(normalizeBySiPrefix(500.0, "mA")).isEqualTo(0.5)
        assertThat(normalizeBySiPrefix(1.0, "MWh")).isEqualTo(1_000_000.0)
    }

    @Test fun `SI prefix leaves bare and unprefixed units alone`() {
        // "m" (metres) and "A" (amps) are single-char; "Pa" starts with no prefix.
        assertThat(normalizeBySiPrefix(3.0, "m")).isEqualTo(3.0)
        assertThat(normalizeBySiPrefix(3.0, "A")).isEqualTo(3.0)
        assertThat(normalizeBySiPrefix(3.0, "Pa")).isEqualTo(3.0)
        assertThat(normalizeBySiPrefix(3.0, null)).isEqualTo(3.0)
        assertThat(normalizeBySiPrefix(null, "kW")).isNull()
    }

    @Test fun `mixed-prefix entries weight by true magnitude`() {
        // 2 kW vs 500 W should split 80 / 20, not be dominated by the raw 500.
        val values = listOf(normalizeBySiPrefix(2.0, "kW"), normalizeBySiPrefix(500.0, "W"))
        val w = distributionWeights(values)
        assertThat(w).containsExactly(0.8f, 0.2f).inOrder()
    }
}
