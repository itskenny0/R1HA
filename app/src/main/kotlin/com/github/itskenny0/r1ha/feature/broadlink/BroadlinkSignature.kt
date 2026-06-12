package com.github.itskenny0.r1ha.feature.broadlink

/**
 * Deterministic "signal signature" for a learned command: a synthetic
 * pulse train + pseudo-hex word group rendered as a logic-analyzer trace
 * on the capture-success screen and in the command detail sheet.
 *
 * HONESTY CONSTRAINT: HA never returns the captured code bytes (they go
 * straight into server-side .storage), so a real waveform is impossible
 * client-side. This trace is derived from the command's identity hash:
 * stable per command, visually distinct between commands, and labelled
 * in the UI as a local rendering rather than the raw code.
 */
object BroadlinkSignature {

    /**
     * [pulses] alternates mark/space widths (even index = mark), each in
     * 0.25..1.0 so no segment collapses to invisibility. [hexWords] is a
     * four-group pseudo signature line. [carrierLabel] is the nominal
     * carrier for the command type: consumer IR remotes modulate at
     * ~38 kHz and Broadlink RF capture targets the 433.92 MHz ISM band.
     */
    data class Trace(
        val pulses: List<Float>,
        val hexWords: List<String>,
        val carrierLabel: String,
    )

    const val PULSE_COUNT = 48

    fun traceFor(deviceName: String, commandName: String, type: String): Trace {
        var state = seed(deviceName, commandName, type)
        val pulses = ArrayList<Float>(PULSE_COUNT)
        repeat(PULSE_COUNT) {
            state = next(state)
            // Map the top bits onto 0.25..1.0; IR pulse trains read as
            // short-short-long runs, so quantize to four widths for the
            // stepped protocol-analyzer look rather than smooth noise.
            val q = ((state ushr 48) and 0x3).toInt()
            pulses.add(0.25f + q * 0.25f)
        }
        val words = ArrayList<String>(4)
        repeat(4) {
            state = next(state)
            words.add(String.format(java.util.Locale.US, "%04X", (state ushr 40) and 0xFFFF))
        }
        return Trace(
            pulses = pulses,
            hexWords = words,
            carrierLabel = if (type == "rf") "433.92 MHz" else "38.0 kHz",
        )
    }

    private fun seed(deviceName: String, commandName: String, type: String): Long {
        // FNV-1a over the identity triple; cheap, stable across runs and
        // devices (unlike String.hashCode contractually, which is in fact
        // stable, but FNV keeps the mixing explicit and 64-bit).
        var h = -0x340d631b7bdddcdbL
        for (ch in "$deviceName/$commandName#$type") {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return if (h == 0L) 0x9E3779B97F4A7C15UL.toLong() else h
    }

    /** xorshift64* step. */
    private fun next(x: Long): Long {
        var v = x
        v = v xor (v shl 13)
        v = v xor (v ushr 7)
        v = v xor (v shl 17)
        return v * 0x2545F4914F6CDD1DL
    }
}
