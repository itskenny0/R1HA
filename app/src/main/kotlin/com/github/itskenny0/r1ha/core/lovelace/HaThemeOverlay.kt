package com.github.itskenny0.r1ha.core.lovelace

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * A subset of HA CSS theme variables mapped to native tokens for use within
 * dashboard card rendering. This is an ADAPTATION, not a CSS engine: we only
 * extract the small set of variables that affect legibility on the R1 panel.
 *
 * Variables sourced (matching apply_themes_on_element.ts resolution order):
 *  - `primary-color`       -> [primary]   (accent / interactive highlight)
 *  - `accent-color`        -> [accent]    (secondary accent)
 *  - `card-background-color` -> [cardBg]  (card surface fill)
 *  - `primary-text-color`  -> [textPrimary] (headline text)
 *  - `secondary-text-color` -> [textSecondary]
 *  - `state-icon-active-color` -> [iconActive]
 *  - `lovelace-background` -> [viewBackground] (view-level background hint,
 *    used when the view has no explicit `background:` image/string)
 *
 * Null values mean "not set by the theme; use the R1 design system default".
 * The overlay must NEVER replace every token — app chrome (top bar, nav) always
 * stays on the R1 design system; only card surfaces consult this.
 *
 * Contrast clamping rule: when [cardBg] is resolved and its luminance is above
 * [LIGHT_SURFACE_LUMINANCE_THRESHOLD] (i.e. the theme wants a light card surface),
 * the background is clamped to [R1.Surface] (0x141414) and text colours are reset
 * to null (so R1 defaults apply). This prevents illegible light-on-dark rendering
 * on the R1's dark-biased display. The clamping is applied by [withContrastGuard];
 * the raw extracted values are preserved in [HaThemeOverlay] itself so callers
 * can inspect them without re-parsing.
 */
data class HaThemeOverlay(
    val primary: Color? = null,
    val accent: Color? = null,
    val cardBg: Color? = null,
    val textPrimary: Color? = null,
    val textSecondary: Color? = null,
    val iconActive: Color? = null,
    val viewBackground: String? = null,
) {
    companion object {
        /** No override: every token falls back to the R1 design system. */
        val NONE = HaThemeOverlay()
    }
}

/**
 * Luminance threshold above which a card background is considered "light" and
 * therefore illegible on the R1's dark panel. WCAG relative luminance of
 * 0x414141 (the darkest grey that still reads as "grey, not black") is ~0.05;
 * anything above 0.12 is visibly lighter than R1.Surface and risks washing out
 * the white text. Using 0.12 as the threshold catches most "bright white" HA
 * themes while leaving dark theme customisations untouched.
 */
internal const val LIGHT_SURFACE_LUMINANCE_THRESHOLD = 0.12f

/**
 * WCAG relative luminance for a packed ARGB int (gamma-linearised). Reuses the
 * same algorithm as PaletteDerive.relativeLuminance but is kept here to avoid
 * a cross-module dependency (core/lovelace does not depend on core/theme).
 */
internal fun haRelativeLuminance(argb: Int): Float {
    fun lin(channel: Int): Float {
        val c = channel / 255f
        return if (c <= 0.04045f) c / 12.92f
        else Math.pow(((c + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    val r = lin((argb shr 16) and 0xFF)
    val g = lin((argb shr 8) and 0xFF)
    val b = lin(argb and 0xFF)
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/**
 * Apply contrast clamping to this overlay. If [cardBg] resolves to a surface
 * that is too light for the R1 panel, the card background is replaced with
 * [R1.Surface] and the text colours are cleared (so R1 ink tokens apply).
 * All other tokens are left as-is. Returns the same object unchanged when the
 * background is already dark enough or absent.
 */
fun HaThemeOverlay.withContrastGuard(): HaThemeOverlay {
    val bg = cardBg ?: return this
    val argb = (0xFF shl 24) or
        ((bg.red * 255).toInt() shl 16) or
        ((bg.green * 255).toInt() shl 8) or
        (bg.blue * 255).toInt()
    return if (haRelativeLuminance(argb) > LIGHT_SURFACE_LUMINANCE_THRESHOLD) {
        // Light surface: clamp to the R1 surface colour and reset text tokens
        // so we never end up with dark text on R1's dark surface.
        copy(cardBg = R1.Surface, textPrimary = null, textSecondary = null)
    } else {
        this
    }
}

/**
 * Merge two overlays: [other] wins over [this] for every non-null token.
 * Used to layer card-level on top of section-level on top of view-level.
 */
fun HaThemeOverlay.mergedWith(other: HaThemeOverlay): HaThemeOverlay = HaThemeOverlay(
    primary = other.primary ?: primary,
    accent = other.accent ?: accent,
    cardBg = other.cardBg ?: cardBg,
    textPrimary = other.textPrimary ?: textPrimary,
    textSecondary = other.textSecondary ?: textSecondary,
    iconActive = other.iconActive ?: iconActive,
    viewBackground = other.viewBackground ?: viewBackground,
)

// ---------------------------------------------------------------------------
// CSS colour parsing
// ---------------------------------------------------------------------------

/**
 * Parse a CSS color value from a HA theme variable to a Compose [Color], or
 * null when the value is not representable (CSS variable references, gradients,
 * keywords other than the small named subset below, unparseable tokens).
 *
 * Supported forms:
 *  - `#rrggbb` / `#rgb` / `#rrggbbaa`
 *  - `rgb(r, g, b)` / `rgba(r, g, b, a)` with integer 0-255 channels
 *  - Named subset: black, white, red, green, blue, orange, yellow, purple,
 *    gray / grey, transparent
 *
 * Everything else (CSS vars, hsl, oklch, named colours outside the subset,
 * bare integers, relative lengths) returns null and is silently ignored so
 * an unknown token never crashes or corrupts the overlay.
 */
fun parseCssColor(raw: String): Color? {
    val s = raw.trim().lowercase()
    if (s.isEmpty()) return null

    // CSS variable references — cannot evaluate without a DOM context
    if (s.startsWith("var(")) return null

    // Hex
    if (s.startsWith('#')) return parseHexColor(s)

    // rgb() / rgba()
    if (s.startsWith("rgb")) return parseRgbColor(s)

    // Named subset
    return when (s) {
        "black" -> Color(0xFF000000.toInt())
        "white" -> Color(0xFFFFFFFF.toInt())
        "red" -> Color(0xFFFF0000.toInt())
        "green" -> Color(0xFF008000.toInt())
        "blue" -> Color(0xFF0000FF.toInt())
        "orange" -> Color(0xFFFFA500.toInt())
        "yellow" -> Color(0xFFFFFF00.toInt())
        "purple" -> Color(0xFF800080.toInt())
        "gray", "grey" -> Color(0xFF808080.toInt())
        "transparent" -> Color(0x00000000)
        else -> null
    }
}

private fun parseHexColor(hex: String): Color? {
    val s = hex.removePrefix("#")
    return when (s.length) {
        3 -> {
            val r = s[0].digitToIntOrNull(16) ?: return null
            val g = s[1].digitToIntOrNull(16) ?: return null
            val b = s[2].digitToIntOrNull(16) ?: return null
            Color(((0xFF shl 24) or (r * 17 shl 16) or (g * 17 shl 8) or (b * 17)).toLong().toInt())
        }
        6 -> {
            val v = s.toLongOrNull(16) ?: return null
            Color((0xFF000000L or v).toInt())
        }
        8 -> {
            // rrggbbaa: convert to aarrggbb for Compose's ARGB
            val v = s.toLongOrNull(16) ?: return null
            val rr = (v shr 24) and 0xFF
            val gg = (v shr 16) and 0xFF
            val bb = (v shr 8) and 0xFF
            val aa = v and 0xFF
            Color(((aa shl 24) or (rr shl 16) or (gg shl 8) or bb).toInt())
        }
        else -> null
    }
}

private fun parseRgbColor(s: String): Color? {
    val inner = s
        .removePrefix("rgba(").removePrefix("rgb(")
        .removeSuffix(")")
        .trim()
    val parts = inner.split(',').map { it.trim() }
    if (parts.size < 3) return null
    val r = parts[0].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val g = parts[1].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val b = parts[2].toIntOrNull()?.coerceIn(0, 255) ?: return null
    val a = if (parts.size >= 4) {
        val af = parts[3].toFloatOrNull() ?: return null
        (af.coerceIn(0f, 1f) * 255).toInt()
    } else 255
    return Color((a shl 24) or (r shl 16) or (g shl 8) or b)
}

// ---------------------------------------------------------------------------
// Theme variable extraction
// ---------------------------------------------------------------------------

/**
 * Derive a [HaThemeOverlay] from a flat map of HA CSS variable names to their
 * values (as returned by `frontend/get_themes`, after dark-mode resolution).
 *
 * Only the variables in [OVERLAY_VARIABLE_KEYS] are read; all others are
 * silently ignored. Unparseable values (CSS var refs, gradients) become null
 * in the overlay (fall through to R1 defaults). The returned overlay has
 * contrast clamping applied via [withContrastGuard].
 */
fun haThemeVariablesToOverlay(vars: Map<String, String>): HaThemeOverlay {
    fun color(key: String): Color? = vars[key]?.let { parseCssColor(it) }
    val raw = HaThemeOverlay(
        primary = color("primary-color"),
        accent = color("accent-color"),
        cardBg = color("card-background-color"),
        textPrimary = color("primary-text-color"),
        textSecondary = color("secondary-text-color"),
        iconActive = color("state-icon-active-color"),
        viewBackground = vars["lovelace-background"]?.takeUnless {
            // Only pass through values that look like image URLs or gradients;
            // CSS var() references are useless on a native renderer.
            it.startsWith("var(") || it.isBlank()
        },
    )
    return raw.withContrastGuard()
}

/** The CSS variable names we extract from a HA theme. */
val OVERLAY_VARIABLE_KEYS: Set<String> = setOf(
    "primary-color",
    "accent-color",
    "card-background-color",
    "primary-text-color",
    "secondary-text-color",
    "state-icon-active-color",
    "lovelace-background",
)
