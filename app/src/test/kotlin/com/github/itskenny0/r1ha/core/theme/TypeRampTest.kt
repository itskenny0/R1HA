package com.github.itskenny0.r1ha.core.theme

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks [buildTypeRamp]'s two branches:
 *
 *  1. "" (the stock mix) must reproduce the historical [R1] styles
 *     byte-for-byte: monospace numerals, sans chrome and prose, the
 *     hand-tuned Mission Control sizes / weights / spacings.
 *  2. A named family replaces EVERY role, numerals and the inline mono span
 *     included, without nudging any numeric value.
 *
 * Named families resolve through an injected fake because the real resolver
 * needs the Android framework's Typeface; the substitution logic is what
 * matters here, not the platform lookup.
 */
class TypeRampTest {

    // ── "" reproduces the historical ramp exactly ───────────────────────────

    @Test fun `stock mix matches the historical hand-tuned values`() {
        val ramp = buildTypeRamp("")

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

    @Test fun `stock mix never calls the named-family resolver`() {
        // The "" branch must stay JVM-pure: the default resolver needs the
        // Android framework, so reaching it from the stock path would crash
        // any unit test (and waste a Typeface lookup in production).
        buildTypeRamp("") { error("resolver must not run for the stock mix") }
    }

    @Test fun `R1 object delegates to the stock ramp out of the box`() {
        // The static-looking R1 tokens are getters over R1Dynamic; with nothing
        // applied they must hand back the stock ramp and the stock orange.
        assertThat(R1.numeralXl).isEqualTo(buildTypeRamp("").numeralXl)
        assertThat(R1.body).isEqualTo(buildTypeRamp("").body)
        assertThat(R1.monoSpan).isEqualTo(buildTypeRamp("").monoSpan)
        assertThat(R1.AccentWarm).isEqualTo(R1Dynamic.DEFAULT_ACCENT)
    }

    // ── named family replaces every role ─────────────────────────────────────

    @Test fun `named family is applied to every role including numerals`() {
        val fake = FontFamily.Cursive
        val ramp = buildTypeRamp("casual") { name ->
            assertThat(name).isEqualTo("casual")
            fake
        }

        listOf(
            ramp.numeralXl, ramp.numeralM, ramp.numeralS, ramp.sectionHeader,
            ramp.labelMicro, ramp.label, ramp.titleCard, ramp.screenTitle,
            ramp.body, ramp.bodyEmph,
        ).forEach { style ->
            assertThat(style.fontFamily).isEqualTo(fake)
        }
        assertThat(ramp.monoSpan.fontFamily).isEqualTo(fake)
    }

    @Test fun `family swaps never nudge a size weight or spacing`() {
        val reference = buildTypeRamp("")
        val swapped = buildTypeRamp("serif") { FontFamily.Serif }

        listOf(
            swapped.numeralXl to reference.numeralXl,
            swapped.numeralM to reference.numeralM,
            swapped.numeralS to reference.numeralS,
            swapped.sectionHeader to reference.sectionHeader,
            swapped.labelMicro to reference.labelMicro,
            swapped.label to reference.label,
            swapped.titleCard to reference.titleCard,
            swapped.screenTitle to reference.screenTitle,
            swapped.body to reference.body,
            swapped.bodyEmph to reference.bodyEmph,
        ).forEach { (actual, expected) ->
            assertThat(actual.fontSize).isEqualTo(expected.fontSize)
            assertThat(actual.fontWeight).isEqualTo(expected.fontWeight)
            assertThat(actual.letterSpacing).isEqualTo(expected.letterSpacing)
            assertThat(actual.lineHeight).isEqualTo(expected.lineHeight)
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
