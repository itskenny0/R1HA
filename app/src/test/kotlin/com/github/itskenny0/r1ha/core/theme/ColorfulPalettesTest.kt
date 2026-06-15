package com.github.itskenny0.r1ha.core.theme

import com.github.itskenny0.r1ha.core.prefs.ColorfulBackgroundDesign
import com.github.itskenny0.r1ha.core.prefs.ColorfulPaletteSet
import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure tests for the Colourful Cards palette selection + the palette-set / background-design
 * codecs. No Compose, no Android — the selection math and the `fromStored` decoders are plain
 * functions, so they run on a bare JVM.
 */
class ColorfulPalettesTest {

    // ── Per-entity slot mapping ──────────────────────────────────────────────

    @Test fun `slot index is stable for a given entity`() {
        val id = "light.kitchen"
        val first = ColorfulPalettes.paletteIndexFor(id, 6)
        repeat(5) { assertThat(ColorfulPalettes.paletteIndexFor(id, 6)).isEqualTo(first) }
    }

    @Test fun `slot index is always in range and never negative`() {
        // A negative hashCode must not produce a negative (or out-of-range) slot — the
        // positive-remainder fix is the whole point of the helper.
        val ids = listOf("", "a", "light.living_room", "sensor.x", "switch.porch_light_outside_back")
        for (id in ids) {
            val idx = ColorfulPalettes.paletteIndexFor(id, 6)
            assertThat(idx).isAtLeast(0)
            assertThat(idx).isLessThan(6)
        }
    }

    @Test fun `slot index matches the legacy hashCode mapping`() {
        // The pre-refactor theme used `(hashCode % size + size) % size`. Lock that in so an
        // existing install keeps the exact palette it had under VIVID after the refactor.
        val id = "light.living_room"
        val expected = (id.hashCode().rem(6) + 6) % 6
        assertThat(ColorfulPalettes.paletteIndexFor(id, 6)).isEqualTo(expected)
    }

    @Test fun `the same entity keeps its slot across every set`() {
        // Every set has six slots in the same hue order, so switching set must not reshuffle
        // which slot an entity lands on (only the hue family of that slot changes).
        val id = "fan.bedroom"
        val vividIdx = ColorfulPalettes.VIVID.indexOfFirst {
            it.contentEquals(ColorfulPalettes.paletteArgbFor(id, ColorfulPaletteSet.VIVID))
        }
        val pastelIdx = ColorfulPalettes.PASTEL.indexOfFirst {
            it.contentEquals(ColorfulPalettes.paletteArgbFor(id, ColorfulPaletteSet.PASTEL))
        }
        val neonIdx = ColorfulPalettes.NEON.indexOfFirst {
            it.contentEquals(ColorfulPalettes.paletteArgbFor(id, ColorfulPaletteSet.NEON))
        }
        assertThat(pastelIdx).isEqualTo(vividIdx)
        assertThat(neonIdx).isEqualTo(vividIdx)
    }

    // ── Palette set shape + legibility ───────────────────────────────────────

    @Test fun `every set has six 3-stop gradients`() {
        for (set in ColorfulPaletteSet.entries) {
            val palettes = ColorfulPalettes.setFor(set)
            assertThat(palettes).hasSize(6)
            palettes.forEach { assertThat(it).hasLength(3) }
        }
    }

    @Test fun `every stop is fully opaque`() {
        for (set in ColorfulPaletteSet.entries) {
            ColorfulPalettes.setFor(set).forEach { stops ->
                stops.forEach { argb -> assertThat((argb ushr 24) and 0xFF).isEqualTo(0xFF) }
            }
        }
    }

    @Test fun `each gradient runs bright to deep`() {
        // The bright stop must out-lumine the deep anchor in every palette of every set, so the
        // top scrim seats white text where the header lives and the bottom-right stays dark.
        for (set in ColorfulPaletteSet.entries) {
            ColorfulPalettes.setFor(set).forEach { (bright, _, anchor) ->
                assertThat(relativeLuminance(bright)).isGreaterThan(relativeLuminance(anchor))
            }
        }
    }

    @Test fun `deep anchors stay dark enough for white text`() {
        // The deepest stop of every palette (where the value bar's lower ticks + more-info dots
        // live) must read white text comfortably — same ceiling the derived-override path uses.
        for (set in ColorfulPaletteSet.entries) {
            ColorfulPalettes.setFor(set).forEach { stops ->
                assertThat(relativeLuminance(stops[2])).isAtMost(ANCHOR_LUMINANCE_CEILING)
            }
        }
    }

    // ── Codec round-trips ────────────────────────────────────────────────────

    @Test fun `palette set codec round-trips every value`() {
        for (set in ColorfulPaletteSet.entries) {
            assertThat(ColorfulPaletteSet.fromStored(set.name)).isEqualTo(set)
        }
    }

    @Test fun `background design codec round-trips every value`() {
        for (design in ColorfulBackgroundDesign.entries) {
            assertThat(ColorfulBackgroundDesign.fromStored(design.name)).isEqualTo(design)
        }
    }

    @Test fun `unknown or absent stored values fall back to the shipped look`() {
        // A downgrade from a future build, or a hand-edited backup, must not crash — it lands on
        // VIVID / GRADIENT (the original Colourful Cards) so the user sees a sane sky.
        assertThat(ColorfulPaletteSet.fromStored(null)).isEqualTo(ColorfulPaletteSet.VIVID)
        assertThat(ColorfulPaletteSet.fromStored("FUTURE_SET")).isEqualTo(ColorfulPaletteSet.VIVID)
        assertThat(ColorfulBackgroundDesign.fromStored(null)).isEqualTo(ColorfulBackgroundDesign.GRADIENT)
        assertThat(ColorfulBackgroundDesign.fromStored("WILD")).isEqualTo(ColorfulBackgroundDesign.GRADIENT)
    }
}
