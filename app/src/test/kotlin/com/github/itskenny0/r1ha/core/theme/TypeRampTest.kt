package com.github.itskenny0.r1ha.core.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.FontFace
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the font-face feature's two pure layers:
 *
 *  1. [rampFamilyFor] — the face → role → named-family table. Numerals never
 *     leave monospace except on MONO (where they already are); labels / titles
 *     / body move together.
 *  2. [buildTypeRamp] — family substitution only. Sizes, weights, spacings,
 *     and line heights are the hand-tuned Mission Control values on every
 *     face; the DEFAULT ramp must reproduce the historical [R1] styles
 *     byte-for-byte.
 *
 * The condensed face resolves through an injected fake family because the real
 * resolver's "sans-serif-condensed" lookup needs the Android framework — the
 * mapping is what matters here, not the platform Typeface.
 */
class TypeRampTest {

    // ── rampFamilyFor: the pure decision table ──────────────────────────────

    @Test fun `numerals stay monospace on every face`() {
        FontFace.entries.forEach { face ->
            assertThat(rampFamilyFor(face, FontRole.NUMERAL)).isEqualTo(RampFamily.MONO)
        }
    }

    @Test fun `default face maps chrome and prose to sans`() {
        listOf(FontRole.LABEL, FontRole.TITLE, FontRole.BODY).forEach { role ->
            assertThat(rampFamilyFor(FontFace.DEFAULT, role)).isEqualTo(RampFamily.SANS)
        }
    }

    @Test fun `condensed face maps chrome and prose to the condensed family`() {
        listOf(FontRole.LABEL, FontRole.TITLE, FontRole.BODY).forEach { role ->
            assertThat(rampFamilyFor(FontFace.CONDENSED, role))
                .isEqualTo(RampFamily.SANS_CONDENSED)
        }
    }

    @Test fun `serif face maps chrome and prose to serif`() {
        listOf(FontRole.LABEL, FontRole.TITLE, FontRole.BODY).forEach { role ->
            assertThat(rampFamilyFor(FontFace.SERIF, role)).isEqualTo(RampFamily.SERIF)
        }
    }

    @Test fun `mono face maps every role to monospace`() {
        FontRole.entries.forEach { role ->
            assertThat(rampFamilyFor(FontFace.MONO, role)).isEqualTo(RampFamily.MONO)
        }
    }

    // ── buildTypeRamp: DEFAULT reproduces the historical ramp exactly ───────

    @Test fun `default ramp matches the historical hand-tuned values`() {
        val ramp = buildTypeRamp(FontFace.DEFAULT)

        assertStyle(ramp.numeralXl, FontFamily.Monospace, FontWeight.Medium, 72f)
        assertThat(ramp.numeralXl.letterSpacing).isEqualTo((-2).sp)
        assertThat(ramp.numeralXl.lineHeight).isEqualTo(72.sp)

        assertStyle(ramp.numeralM, FontFamily.Monospace, FontWeight.Medium, 20f)
        assertStyle(ramp.numeralS, FontFamily.Monospace, FontWeight.Normal, 11f)
        assertThat(ramp.numeralS.letterSpacing).isEqualTo(0.5.sp)

        assertStyle(ramp.sectionHeader, FontFamily.SansSerif, FontWeight.SemiBold, 10f)
        assertThat(ramp.sectionHeader.letterSpacing).isEqualTo(2.5.sp)
        assertStyle(ramp.labelMicro, FontFamily.SansSerif, FontWeight.Bold, 9f)
        assertThat(ramp.labelMicro.letterSpacing).isEqualTo(2.sp)
        assertStyle(ramp.label, FontFamily.SansSerif, FontWeight.SemiBold, 11f)
        assertThat(ramp.label.letterSpacing).isEqualTo(1.5.sp)

        assertStyle(ramp.titleCard, FontFamily.SansSerif, FontWeight.Medium, 18f)
        assertStyle(ramp.screenTitle, FontFamily.SansSerif, FontWeight.SemiBold, 20f)
        assertStyle(ramp.body, FontFamily.SansSerif, FontWeight.Normal, 14f)
        assertStyle(ramp.bodyEmph, FontFamily.SansSerif, FontWeight.Medium, 14f)

        assertThat(ramp.monoSpan.fontFamily).isEqualTo(FontFamily.Monospace)
        assertThat(ramp.monoSpan.fontWeight).isEqualTo(FontWeight.Medium)
    }

    @Test fun `R1 object delegates to the default ramp out of the box`() {
        // The static-looking R1 tokens are getters over R1Dynamic; with nothing
        // applied they must hand back the DEFAULT ramp and the stock orange.
        assertThat(R1.numeralXl).isEqualTo(buildTypeRamp(FontFace.DEFAULT).numeralXl)
        assertThat(R1.body).isEqualTo(buildTypeRamp(FontFace.DEFAULT).body)
        assertThat(R1.monoSpan).isEqualTo(buildTypeRamp(FontFace.DEFAULT).monoSpan)
        assertThat(R1.AccentWarm).isEqualTo(R1Dynamic.DEFAULT_ACCENT)
    }

    // ── buildTypeRamp: family substitution per face ──────────────────────────

    @Test fun `condensed ramp swaps chrome and prose but keeps numerals monospace`() {
        // Cursive stands in for the framework-resolved condensed family.
        val fakeCondensed = FontFamily.Cursive
        val ramp = buildTypeRamp(FontFace.CONDENSED) { family ->
            if (family == RampFamily.SANS_CONDENSED) fakeCondensed else systemFontFamily(family)
        }

        listOf(ramp.sectionHeader, ramp.labelMicro, ramp.label, ramp.titleCard,
            ramp.screenTitle, ramp.body, ramp.bodyEmph).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(fakeCondensed)
        }
        listOf(ramp.numeralXl, ramp.numeralM, ramp.numeralS).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(FontFamily.Monospace)
        }
        assertThat(ramp.monoSpan.fontFamily).isEqualTo(FontFamily.Monospace)
    }

    @Test fun `serif ramp swaps chrome and prose but keeps numerals monospace`() {
        val ramp = buildTypeRamp(FontFace.SERIF)

        listOf(ramp.sectionHeader, ramp.labelMicro, ramp.label, ramp.titleCard,
            ramp.screenTitle, ramp.body, ramp.bodyEmph).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(FontFamily.Serif)
        }
        listOf(ramp.numeralXl, ramp.numeralM, ramp.numeralS).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(FontFamily.Monospace)
        }
    }

    @Test fun `mono ramp is monospace everywhere`() {
        val ramp = buildTypeRamp(FontFace.MONO)

        listOf(ramp.numeralXl, ramp.numeralM, ramp.numeralS, ramp.sectionHeader,
            ramp.labelMicro, ramp.label, ramp.titleCard, ramp.screenTitle,
            ramp.body, ramp.bodyEmph).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(FontFamily.Monospace)
        }
        assertThat(ramp.monoSpan.fontFamily).isEqualTo(FontFamily.Monospace)
    }

    @Test fun `face swaps never nudge a size weight or spacing`() {
        val reference = buildTypeRamp(FontFace.DEFAULT)
        val faces = listOf(
            buildTypeRamp(FontFace.SERIF),
            buildTypeRamp(FontFace.MONO),
            buildTypeRamp(FontFace.CONDENSED) { FontFamily.Cursive },
        )

        faces.forEach { ramp ->
            listOf(
                ramp.numeralXl to reference.numeralXl,
                ramp.numeralM to reference.numeralM,
                ramp.numeralS to reference.numeralS,
                ramp.sectionHeader to reference.sectionHeader,
                ramp.labelMicro to reference.labelMicro,
                ramp.label to reference.label,
                ramp.titleCard to reference.titleCard,
                ramp.screenTitle to reference.screenTitle,
                ramp.body to reference.body,
                ramp.bodyEmph to reference.bodyEmph,
            ).forEach { (actual, expected) ->
                assertThat(actual.fontSize).isEqualTo(expected.fontSize)
                assertThat(actual.fontWeight).isEqualTo(expected.fontWeight)
                assertThat(actual.letterSpacing).isEqualTo(expected.letterSpacing)
                assertThat(actual.lineHeight).isEqualTo(expected.lineHeight)
            }
        }
    }

    private fun assertStyle(
        style: TextStyle,
        family: FontFamily,
        weight: FontWeight,
        sizeSp: Float,
    ) {
        assertThat(style.fontFamily).isEqualTo(family)
        assertThat(style.fontWeight).isEqualTo(weight)
        assertThat(style.fontSize).isEqualTo(sizeSp.sp)
    }
}
