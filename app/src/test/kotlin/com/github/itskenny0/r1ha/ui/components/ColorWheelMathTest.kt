package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the colour-wheel / CT-slider math in ColorWheelMath.kt: the touch
 * position <-> (hue, saturation) mapping, the kelvin <-> slider-fraction mapping, the
 * black-body colour approximation, and the hs_color attribute parser. These pin down
 * the gesture contract (centre = white, rim = saturated, red at 3 o'clock, clockwise)
 * that can't be verified by eye in code review.
 */
class ColorWheelMathTest {

    private val cx = 100f
    private val cy = 100f
    private val radius = 50f

    // ── wheelHsAt ───────────────────────────────────────────────────────────────

    @Test fun `centre of the wheel is saturation zero`() {
        val (_, sat) = wheelHsAt(cx, cy, cx, cy, radius)!!
        assertThat(sat).isEqualTo(0f)
    }

    @Test fun `rim of the wheel is saturation one`() {
        val (_, sat) = wheelHsAt(cx + radius, cy, cx, cy, radius)!!
        assertThat(sat).isEqualTo(1f)
    }

    @Test fun `3 o'clock is hue zero (red)`() {
        val (hue, _) = wheelHsAt(cx + radius, cy, cx, cy, radius)!!
        assertThat(hue).isWithin(0.001f).of(0f)
    }

    @Test fun `6 o'clock is hue 90 (clockwise in screen space)`() {
        val (hue, _) = wheelHsAt(cx, cy + radius, cx, cy, radius)!!
        assertThat(hue).isWithin(0.001f).of(90f)
    }

    @Test fun `9 o'clock is hue 180 and 12 o'clock is hue 270`() {
        assertThat(wheelHsAt(cx - radius, cy, cx, cy, radius)!!.first).isWithin(0.001f).of(180f)
        assertThat(wheelHsAt(cx, cy - radius, cx, cy, radius)!!.first).isWithin(0.001f).of(270f)
    }

    @Test fun `position outside the disc clamps saturation to the rim but keeps the angle`() {
        val (hue, sat) = wheelHsAt(cx + radius * 3, cy, cx, cy, radius)!!
        assertThat(sat).isEqualTo(1f)
        assertThat(hue).isWithin(0.001f).of(0f)
    }

    @Test fun `degenerate radius returns null`() {
        assertThat(wheelHsAt(cx, cy, cx, cy, 0f)).isNull()
        assertThat(wheelHsAt(cx, cy, cx, cy, -5f)).isNull()
    }

    @Test fun `interior position reports proportional saturation`() {
        val (_, sat) = wheelHsAt(cx + radius / 2f, cy, cx, cy, radius)!!
        assertThat(sat).isWithin(0.001f).of(0.5f)
    }

    // ── wheelOffsetFor + round trip ─────────────────────────────────────────────

    @Test fun `offset for hue 0 sat 1 is the 3 o'clock rim point`() {
        val (x, y) = wheelOffsetFor(0f, 1f, cx, cy, radius)
        assertThat(x).isWithin(0.001f).of(cx + radius)
        assertThat(y).isWithin(0.001f).of(cy)
    }

    @Test fun `offset clamps out-of-range saturation onto the disc`() {
        val (x, y) = wheelOffsetFor(0f, 2.5f, cx, cy, radius)
        assertThat(x).isWithin(0.001f).of(cx + radius)
        assertThat(y).isWithin(0.001f).of(cy)
    }

    @Test fun `hue-sat to offset and back round-trips within tolerance`() {
        for (hue in listOf(0f, 37f, 90f, 180f, 211f, 270f, 359f)) {
            for (sat in listOf(0.1f, 0.5f, 1f)) {
                val (x, y) = wheelOffsetFor(hue, sat, cx, cy, radius)
                val (h2, s2) = wheelHsAt(x, y, cx, cy, radius)!!
                assertThat(h2).isWithin(0.01f).of(hue)
                assertThat(s2).isWithin(0.001f).of(sat)
            }
        }
    }

    // ── kelvin <-> fraction ─────────────────────────────────────────────────────

    @Test fun `fraction 0 is the warm end and fraction 1 the cool end`() {
        assertThat(kelvinFromFraction(0f, 2000, 6500)).isEqualTo(2000)
        assertThat(kelvinFromFraction(1f, 2000, 6500)).isEqualTo(6500)
    }

    @Test fun `fraction is clamped into the bar`() {
        assertThat(kelvinFromFraction(-0.4f, 2000, 6500)).isEqualTo(2000)
        assertThat(kelvinFromFraction(1.7f, 2000, 6500)).isEqualTo(6500)
    }

    @Test fun `kelvin round-trips through fraction`() {
        for (k in listOf(2000, 2700, 4000, 5000, 6500)) {
            val f = fractionFromKelvin(k, 2000, 6500)
            assertThat(kelvinFromFraction(f, 2000, 6500)).isEqualTo(k)
        }
    }

    @Test fun `out-of-range kelvin clamps onto the bar`() {
        assertThat(fractionFromKelvin(1000, 2000, 6500)).isEqualTo(0f)
        assertThat(fractionFromKelvin(9000, 2000, 6500)).isEqualTo(1f)
    }

    @Test fun `degenerate kelvin range maps to fraction zero`() {
        assertThat(fractionFromKelvin(3000, 3000, 3000)).isEqualTo(0f)
    }

    // ── kelvinToArgb ────────────────────────────────────────────────────────────

    @Test fun `2000K reads amber - full red, mid green, low blue`() {
        val argb = kelvinToArgb(2000)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        assertThat(r).isEqualTo(255)
        assertThat(g).isLessThan(r)
        assertThat(b).isLessThan(g)
    }

    @Test fun `6500K reads near-white - all channels high and close`() {
        val argb = kelvinToArgb(6500)
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        assertThat(r).isAtLeast(240)
        assertThat(g).isAtLeast(240)
        assertThat(b).isAtLeast(240)
    }

    @Test fun `alpha channel is always opaque`() {
        for (k in listOf(1500, 2700, 6500, 10_000)) {
            assertThat((kelvinToArgb(k) ushr 24)).isEqualTo(0xFF)
        }
    }

    @Test fun `blue channel rises monotonically from warm to cool`() {
        val warm = kelvinToArgb(2000) and 0xFF
        val mid = kelvinToArgb(4000) and 0xFF
        val cool = kelvinToArgb(6500) and 0xFF
        assertThat(mid).isGreaterThan(warm)
        assertThat(cool).isGreaterThan(mid)
    }

    // ── hsFromAttributes ────────────────────────────────────────────────────────

    @Test fun `parses HA hs_color into hue degrees and 0-1 saturation`() {
        val attrs = kotlinx.serialization.json.buildJsonObject {
            put(
                "hs_color",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(210.5))
                    add(kotlinx.serialization.json.JsonPrimitive(40))
                },
            )
        }
        val (hue, sat) = hsFromAttributes(attrs)!!
        assertThat(hue).isWithin(0.001f).of(210.5f)
        assertThat(sat).isWithin(0.001f).of(0.4f)
    }

    @Test fun `missing or malformed hs_color returns null`() {
        assertThat(hsFromAttributes(null)).isNull()
        assertThat(hsFromAttributes(kotlinx.serialization.json.buildJsonObject {})).isNull()
        assertThat(
            hsFromAttributes(
                kotlinx.serialization.json.buildJsonObject {
                    put("hs_color", kotlinx.serialization.json.JsonPrimitive("oops"))
                },
            ),
        ).isNull()
        assertThat(
            hsFromAttributes(
                kotlinx.serialization.json.buildJsonObject {
                    put(
                        "hs_color",
                        kotlinx.serialization.json.buildJsonArray {
                            add(kotlinx.serialization.json.JsonPrimitive(120))
                        },
                    )
                },
            ),
        ).isNull()
    }

    @Test fun `out-of-range hs_color values are normalised`() {
        val attrs = kotlinx.serialization.json.buildJsonObject {
            put(
                "hs_color",
                kotlinx.serialization.json.buildJsonArray {
                    add(kotlinx.serialization.json.JsonPrimitive(-30))
                    add(kotlinx.serialization.json.JsonPrimitive(150))
                },
            )
        }
        val (hue, sat) = hsFromAttributes(attrs)!!
        assertThat(hue).isWithin(0.001f).of(330f)
        assertThat(sat).isEqualTo(1f)
    }
}
