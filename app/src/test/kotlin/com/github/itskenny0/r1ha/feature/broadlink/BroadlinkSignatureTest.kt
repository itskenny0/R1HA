package com.github.itskenny0.r1ha.feature.broadlink

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class BroadlinkSignatureTest {

    @Test fun `trace is deterministic per command identity`() {
        val a = BroadlinkSignature.traceFor("tv", "power", "ir")
        val b = BroadlinkSignature.traceFor("tv", "power", "ir")
        assertThat(a).isEqualTo(b)
    }

    @Test fun `different commands render different traces`() {
        val a = BroadlinkSignature.traceFor("tv", "power", "ir")
        val b = BroadlinkSignature.traceFor("tv", "vol_up", "ir")
        assertThat(a.pulses).isNotEqualTo(b.pulses)
        assertThat(a.hexWords).isNotEqualTo(b.hexWords)
    }

    @Test fun `pulses stay inside the renderable band`() {
        val t = BroadlinkSignature.traceFor("amp", "mute", "rf")
        assertThat(t.pulses).hasSize(BroadlinkSignature.PULSE_COUNT)
        t.pulses.forEach {
            assertThat(it).isAtLeast(0.25f)
            assertThat(it).isAtMost(1.0f)
        }
    }

    @Test fun `hex words are four groups of four uppercase hex chars`() {
        val t = BroadlinkSignature.traceFor("tv", "power", "ir")
        assertThat(t.hexWords).hasSize(4)
        t.hexWords.forEach { word ->
            assertThat(word).matches("[0-9A-F]{4}")
        }
    }

    @Test fun `carrier label tracks the command type`() {
        assertThat(BroadlinkSignature.traceFor("tv", "power", "ir").carrierLabel)
            .isEqualTo("38.0 kHz")
        assertThat(BroadlinkSignature.traceFor("fan", "speed", "rf").carrierLabel)
            .isEqualTo("433.92 MHz")
    }
}
