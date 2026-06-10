package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Locks the eight-face → family-name migration:
 *
 *  1. [fontFaceToFamilyName] is the fixed table mapping each legacy face onto
 *     the Android named family it used for chrome and prose ("" for DEFAULT).
 *  2. [fontFaceFromFamilyName] is its best-effort inverse, used to materialise
 *     the legacy backup slot; vendor families collapse to DEFAULT.
 *  3. [resolveFontFamilyName] is the preferences read path: the new key wins
 *     whenever written (an explicit "" included), the legacy enum name maps
 *     through the table otherwise, and garbage / absence lands on "" so a
 *     fresh install renders byte-for-byte unchanged.
 */
class FontFamilyMigrationTest {

    @Test fun `every legacy face maps to its documented family name`() {
        assertThat(fontFaceToFamilyName(FontFace.DEFAULT)).isEmpty()
        assertThat(fontFaceToFamilyName(FontFace.SANS)).isEqualTo("sans-serif")
        assertThat(fontFaceToFamilyName(FontFace.CONDENSED)).isEqualTo("sans-serif-condensed")
        assertThat(fontFaceToFamilyName(FontFace.LIGHT)).isEqualTo("sans-serif-light")
        assertThat(fontFaceToFamilyName(FontFace.SERIF)).isEqualTo("serif")
        assertThat(fontFaceToFamilyName(FontFace.CASUAL)).isEqualTo("casual")
        assertThat(fontFaceToFamilyName(FontFace.CURSIVE)).isEqualTo("cursive")
        assertThat(fontFaceToFamilyName(FontFace.MONO)).isEqualTo("monospace")
    }

    @Test fun `reverse mapping inverts the table and collapses unknowns to DEFAULT`() {
        FontFace.entries.forEach { face ->
            assertThat(fontFaceFromFamilyName(fontFaceToFamilyName(face))).isEqualTo(face)
        }
        assertThat(fontFaceFromFamilyName("vendor-grotesk")).isEqualTo(FontFace.DEFAULT)
    }

    @Test fun `stored family name wins over the legacy face`() {
        assertThat(resolveFontFamilyName(stored = "casual", legacyFaceName = "SERIF"))
            .isEqualTo("casual")
        // An explicitly written "" means the user chose the stock mix AFTER
        // the rework; the lingering legacy key must not resurrect the old face.
        assertThat(resolveFontFamilyName(stored = "", legacyFaceName = "SERIF")).isEmpty()
    }

    @Test fun `absent new key falls back to mapping the legacy face`() {
        assertThat(resolveFontFamilyName(stored = null, legacyFaceName = "SERIF"))
            .isEqualTo("serif")
        assertThat(resolveFontFamilyName(stored = null, legacyFaceName = "CONDENSED"))
            .isEqualTo("sans-serif-condensed")
        assertThat(resolveFontFamilyName(stored = null, legacyFaceName = "DEFAULT")).isEmpty()
    }

    @Test fun `garbage or absence resolves to the stock mix`() {
        assertThat(resolveFontFamilyName(stored = null, legacyFaceName = "NOT_A_FACE")).isEmpty()
        assertThat(resolveFontFamilyName(stored = null, legacyFaceName = null)).isEmpty()
    }
}
