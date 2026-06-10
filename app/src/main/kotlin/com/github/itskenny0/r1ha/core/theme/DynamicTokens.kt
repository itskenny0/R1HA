package com.github.itskenny0.r1ha.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.prefs.FontFace

/**
 * The text roles the type ramp distinguishes when a [FontFace] swaps families.
 * Coarser than the ramp's eleven styles on purpose: a face decision is about
 * *kinds* of text (readouts vs chrome vs prose), not individual styles.
 */
enum class FontRole {
    /** Monospace readouts: [R1TypeRamp.numeralXl] / [R1TypeRamp.numeralM] /
     *  [R1TypeRamp.numeralS] and the inline [R1TypeRamp.monoSpan]. */
    NUMERAL,

    /** Uppercase chrome: [R1TypeRamp.sectionHeader] / [R1TypeRamp.label] /
     *  [R1TypeRamp.labelMicro]. */
    LABEL,

    /** Titles: [R1TypeRamp.titleCard] / [R1TypeRamp.screenTitle]. */
    TITLE,

    /** Reading text: [R1TypeRamp.body] / [R1TypeRamp.bodyEmph]. */
    BODY,
}

/**
 * Which *named system family* a role resolves to. Kept as an enum (rather than
 * resolving straight to [FontFamily]) so the face → role → family decision is a
 * pure, JVM-testable mapping; turning a name into an actual [FontFamily] is the
 * thin platform layer in [systemFontFamily].
 */
enum class RampFamily { SANS, SANS_CONDENSED, SANS_LIGHT, SERIF, MONO, CASUAL, CURSIVE }

/**
 * Pure face → role → family decision. The whole font-face feature reduces to
 * this table:
 *
 *  - [FontFace.DEFAULT]   keeps today's mix — monospace numerals, sans
 *                         labels / titles / body.
 *  - [FontFace.CONDENSED] swaps labels / titles / body to the system
 *                         "sans-serif-condensed"; numerals stay monospace.
 *  - [FontFace.SERIF]     swaps labels / titles / body to the system serif;
 *                         numerals stay monospace.
 *  - [FontFace.MONO]      goes monospace everywhere.
 *
 * Numerals never leave monospace except on MONO (where they already are):
 * tabular digits are what keeps readouts steady while values tick.
 */
fun rampFamilyFor(face: FontFace, role: FontRole): RampFamily = when (role) {
    // The mixed faces keep tabular monospace digits (readouts are the app's
    // identity); the full-replacement faces swap numerals too, because 'give
    // me a normal font' means everything, not everything-except-the-numbers.
    FontRole.NUMERAL -> when (face) {
        FontFace.DEFAULT, FontFace.CONDENSED, FontFace.SERIF, FontFace.MONO -> RampFamily.MONO
        FontFace.SANS -> RampFamily.SANS
        FontFace.LIGHT -> RampFamily.SANS_LIGHT
        FontFace.CASUAL -> RampFamily.CASUAL
        FontFace.CURSIVE -> RampFamily.CURSIVE
    }
    FontRole.LABEL, FontRole.TITLE, FontRole.BODY -> when (face) {
        FontFace.DEFAULT, FontFace.SANS -> RampFamily.SANS
        FontFace.CONDENSED -> RampFamily.SANS_CONDENSED
        FontFace.LIGHT -> RampFamily.SANS_LIGHT
        FontFace.SERIF -> RampFamily.SERIF
        FontFace.CASUAL -> RampFamily.CASUAL
        FontFace.CURSIVE -> RampFamily.CURSIVE
        FontFace.MONO -> RampFamily.MONO
    }
}

/**
 * The full set of swappable text styles the [R1] object delegates to. One
 * immutable value per font face; sizes, weights, spacing, and line heights are
 * identical across faces — only the families move (see [rampFamilyFor]).
 */
data class R1TypeRamp(
    val numeralXl: TextStyle,
    val numeralM: TextStyle,
    val numeralS: TextStyle,
    val sectionHeader: TextStyle,
    val labelMicro: TextStyle,
    val label: TextStyle,
    val titleCard: TextStyle,
    val body: TextStyle,
    val bodyEmph: TextStyle,
    val screenTitle: TextStyle,
    val monoSpan: SpanStyle,
)

/**
 * Build the type ramp for [face]. Pure given [resolveFamily]; the default
 * resolver maps the generic families to Compose's pure [FontFamily] constants
 * and only touches the Android framework (lazily) for the condensed face, so
 * JVM unit tests can call this for every face by injecting a fake resolver —
 * and for the non-condensed faces even with the default one.
 *
 * Every numeric value here is the hand-tuned Mission Control ramp; a face
 * swap must never nudge a size, weight, or letter-spacing.
 */
fun buildTypeRamp(
    face: FontFace,
    resolveFamily: (RampFamily) -> FontFamily = ::systemFontFamily,
): R1TypeRamp {
    val numerals = resolveFamily(rampFamilyFor(face, FontRole.NUMERAL))
    val labels = resolveFamily(rampFamilyFor(face, FontRole.LABEL))
    val titles = resolveFamily(rampFamilyFor(face, FontRole.TITLE))
    val body = resolveFamily(rampFamilyFor(face, FontRole.BODY))
    return R1TypeRamp(
        numeralXl = TextStyle(
            fontFamily = numerals,
            fontWeight = FontWeight.Medium,
            fontSize = 72.sp,
            letterSpacing = (-2).sp,
            lineHeight = 72.sp,
        ),
        numeralM = TextStyle(
            fontFamily = numerals,
            fontWeight = FontWeight.Medium,
            fontSize = 20.sp,
        ),
        numeralS = TextStyle(
            fontFamily = numerals,
            fontWeight = FontWeight.Normal,
            fontSize = 11.sp,
            letterSpacing = 0.5.sp,
        ),
        sectionHeader = TextStyle(
            fontFamily = labels,
            fontWeight = FontWeight.SemiBold,
            fontSize = 10.sp,
            letterSpacing = 2.5.sp,
        ),
        labelMicro = TextStyle(
            fontFamily = labels,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            letterSpacing = 2.sp,
        ),
        label = TextStyle(
            fontFamily = labels,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.5.sp,
        ),
        titleCard = TextStyle(
            fontFamily = titles,
            fontWeight = FontWeight.Medium,
            fontSize = 18.sp,
            letterSpacing = 0.sp,
        ),
        body = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Normal,
            fontSize = 14.sp,
        ),
        bodyEmph = TextStyle(
            fontFamily = body,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
        ),
        screenTitle = TextStyle(
            fontFamily = titles,
            fontWeight = FontWeight.SemiBold,
            fontSize = 20.sp,
            letterSpacing = 0.sp,
        ),
        monoSpan = SpanStyle(
            fontFamily = numerals,
            fontWeight = FontWeight.Medium,
            textDecoration = TextDecoration.None,
        ),
    )
}

/**
 * System "sans-serif-condensed", constructed lazily because [android.graphics.Typeface]
 * is framework-only (a JVM unit test that never asks for the condensed family
 * must not trip over it). System typefaces only — the app deliberately bundles
 * no font assets.
 */
private val condensedFontFamily: FontFamily by lazy { namedFontFamily("sans-serif-condensed") }
private val lightFontFamily: FontFamily by lazy { namedFontFamily("sans-serif-light") }

/** Android's bundled handwritten face (Coming Soon on AOSP): the closest the
 *  system ships to the comic genre without bundling a font asset. */
private val casualFontFamily: FontFamily by lazy { namedFontFamily("casual") }
private val cursiveFontFamily: FontFamily by lazy { namedFontFamily("cursive") }

private fun namedFontFamily(name: String): FontFamily =
    FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL))

/**
 * Thin platform layer: name → [FontFamily]. The generic three are Compose's
 * pure constants; the condensed family is the one framework lookup.
 */
fun systemFontFamily(family: RampFamily): FontFamily = when (family) {
    RampFamily.SANS -> FontFamily.SansSerif
    RampFamily.SANS_CONDENSED -> condensedFontFamily
    RampFamily.SANS_LIGHT -> lightFontFamily
    RampFamily.SERIF -> FontFamily.Serif
    RampFamily.MONO -> FontFamily.Monospace
    RampFamily.CASUAL -> casualFontFamily
    RampFamily.CURSIVE -> cursiveFontFamily
}

/**
 * Process-wide swappable design tokens. [R1.AccentWarm] and the [R1] type-ramp
 * vals delegate here, so the user's accent override and font face reach every
 * call site that reads the static-looking tokens — chips, buttons, spinners,
 * top bars — without threading a CompositionLocal through hundreds of sites.
 *
 * Written from MainActivity *during composition* (an idempotent write, before
 * the `key(accent, face)` subtree composes) and re-asserted in a SideEffect;
 * the `key` wrapper rebuilds the tree on change so remembered captures of the
 * old values are discarded. Defaults reproduce today's rendering exactly.
 */
object R1Dynamic {
    /** The stock R1 orange — the accent when no override is set. */
    val DEFAULT_ACCENT = Color(0xFFF36F21)

    @Volatile
    var accent: Color = Color(0xFFF36F21)

    @Volatile
    var ramp: R1TypeRamp = buildTypeRamp(FontFace.DEFAULT)

    @Volatile
    private var face: FontFace = FontFace.DEFAULT

    /**
     * Idempotent setter used by the activity wiring: null [accentArgb] means
     * "stock orange". The ramp is only rebuilt when the face actually changes,
     * so calling this once per composition is free.
     */
    fun apply(accentArgb: Int?, fontFace: FontFace) {
        accent = if (accentArgb != null) Color(accentArgb) else DEFAULT_ACCENT
        if (fontFace != face) {
            ramp = buildTypeRamp(fontFace)
            face = fontFace
        }
    }
}
