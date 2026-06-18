package com.github.itskenny0.r1ha.core.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp

/**
 * The full set of swappable text styles the [R1] object delegates to. One
 * immutable value per font choice; sizes, weights, spacing, and line heights
 * are identical across choices; only the families move (see [buildTypeRamp]).
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
 * Build the type ramp for [familyName]. "" keeps the stock mix: monospace
 * numerals (tabular digits keep readouts steady while values tick) with sans
 * chrome and prose, today's hand-tuned rendering. Any other value is an
 * Android named font family ("serif", "casual", a vendor family from
 * [SystemFontCatalog]) applied to EVERY role, numerals included: picking
 * "serif" means everything, not everything-except-the-numbers.
 *
 * Pure given [resolveNamed]; the stock-mix path uses Compose's pure constants
 * and never calls it, so JVM unit tests cover both branches by injecting a
 * fake resolver (the default one needs the Android framework's Typeface).
 *
 * Every numeric value here is the hand-tuned Mission Control ramp; a family
 * swap must never nudge a size, weight, or letter-spacing.
 */
fun buildTypeRamp(
    familyName: String,
    resolveNamed: (String) -> FontFamily = ::namedFontFamily,
): R1TypeRamp {
    val replacement = familyName.takeIf { it.isNotEmpty() }?.let(resolveNamed)
    val numerals = replacement ?: FontFamily.Monospace
    val labels = replacement ?: FontFamily.SansSerif
    val titles = replacement ?: FontFamily.SansSerif
    val body = replacement ?: FontFamily.SansSerif
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
 * Thin platform layer: named system family → [FontFamily]. Framework-only
 * ([android.graphics.Typeface]), so it must never run on the JVM-pure paths;
 * [buildTypeRamp]'s stock-mix branch deliberately avoids it. System typefaces
 * only: the app deliberately bundles no font assets, so the public-domain
 * dedication stays clean.
 */
fun namedFontFamily(name: String): FontFamily =
    FontFamily(android.graphics.Typeface.create(name, android.graphics.Typeface.NORMAL))

/**
 * Process-wide swappable design tokens. [R1.AccentWarm] and the [R1] type-ramp
 * vals delegate here, so the user's accent override and font family reach every
 * call site that reads the static-looking tokens — chips, buttons, spinners,
 * top bars — without threading a CompositionLocal through hundreds of sites.
 *
 * Written from MainActivity *during composition* (an idempotent write, before
 * the `key(accent, family)` subtree composes) and re-asserted in a SideEffect;
 * the `key` wrapper rebuilds the tree on change so remembered captures of the
 * old values are discarded. Defaults reproduce today's rendering exactly.
 */
object R1Dynamic {
    /**
     * The accent when no override is set: stock R1 orange on the normal builds, and a
     * bright yellow on the slim legacy build (R1HAL) so the two installs are visually
     * distinct at a glance and match R1HAL's yellow launcher icon.
     */
    val DEFAULT_ACCENT =
        if (com.github.itskenny0.r1ha.BuildConfig.IS_LEGACY) Color(0xFFFFC400) else Color(0xFFF36F21)

    @Volatile
    var accent: Color = DEFAULT_ACCENT

    @Volatile
    var ramp: R1TypeRamp = buildTypeRamp("")

    @Volatile
    private var familyName: String = ""

    /**
     * Idempotent setter used by the activity wiring: null [accentArgb] means
     * "stock orange", "" [fontFamilyName] means the stock mix. The ramp is
     * only rebuilt when the family actually changes, so calling this once per
     * composition is free.
     */
    fun apply(accentArgb: Int?, fontFamilyName: String) {
        accent = if (accentArgb != null) Color(accentArgb) else DEFAULT_ACCENT
        if (fontFamilyName != familyName) {
            ramp = buildTypeRamp(fontFamilyName)
            familyName = fontFamilyName
        }
    }
}
