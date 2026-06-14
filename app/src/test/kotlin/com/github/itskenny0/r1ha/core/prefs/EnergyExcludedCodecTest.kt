package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for the Energy-view excluded-sensor set codec. The set is
 * persisted as a newline-separated list of URL-encoded entity ids; these pin the
 * empty-set / blank-line handling and the dedup so a corrupt prefs line can't
 * smuggle a blank id into the exclusion set.
 */
class EnergyExcludedCodecTest {

    @Test fun `empty set round-trips to empty string`() {
        val encoded = encodeEnergyExcluded(emptySet())
        assertThat(encoded).isEmpty()
        assertThat(decodeEnergyExcluded(encoded)).isEmpty()
    }

    @Test fun `null or blank raw decodes to empty set`() {
        assertThat(decodeEnergyExcluded(null)).isEmpty()
        assertThat(decodeEnergyExcluded("")).isEmpty()
        assertThat(decodeEnergyExcluded("   ")).isEmpty()
    }

    @Test fun `entity ids round-trip through encode and decode`() {
        val ids = setOf("sensor.fridge_power", "sensor.tv_power")
        val decoded = decodeEnergyExcluded(encodeEnergyExcluded(ids))
        assertThat(decoded).isEqualTo(ids)
    }

    @Test fun `blank lines are dropped on decode`() {
        // A trailing or stray empty line must not surface as a blank "" id.
        assertThat(decodeEnergyExcluded("sensor.a_power\n\nsensor.b_power\n"))
            .isEqualTo(setOf("sensor.a_power", "sensor.b_power"))
    }

    @Test fun `blank ids are dropped on encode`() {
        assertThat(encodeEnergyExcluded(setOf("", "  ", "sensor.x_power")))
            .isEqualTo("sensor.x_power")
    }
}
