package com.github.itskenny0.r1ha.core.theme

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks the pure layers of the system-font discovery pipeline: fonts.xml
 * parsing, display-name prettifying, prominence ordering, and the typeface
 * dedupe. The snippet below mirrors the real AOSP fonts.xml shape: named
 * families, weightless rename aliases, weight-bearing variant aliases, and
 * unnamed locale-fallback families.
 */
class SystemFontCatalogTest {

    private val fontsXml = """
        <?xml version="1.0" encoding="utf-8"?>
        <familyset version="22">
            <family name="sans-serif">
                <font weight="100" style="normal">Roboto-Thin.ttf</font>
                <font weight="400" style="normal">Roboto-Regular.ttf</font>
                <font weight="700" style="normal">Roboto-Bold.ttf</font>
            </family>
            <!-- Note that aliases must come after the fonts they reference. -->
            <alias name="sans-serif-thin" to="sans-serif" weight="100" />
            <alias name="sans-serif-light" to="sans-serif" weight="300" />
            <alias name="sans-serif-medium" to="sans-serif" weight="500" />
            <alias name="sans-serif-black" to="sans-serif" weight="900" />
            <alias name="arial" to="sans-serif" />
            <alias name="helvetica" to="sans-serif" />
            <family name="sans-serif-condensed">
                <font weight="400" style="normal">RobotoCondensed-Regular.ttf</font>
            </family>
            <alias name="sans-serif-condensed-light" to="sans-serif-condensed" weight="300" />
            <family name="serif">
                <font weight="400" style="normal">NotoSerif-Regular.ttf</font>
            </family>
            <alias name="times" to="serif" />
            <family name="monospace">
                <font weight="400" style="normal">DroidSansMono.ttf</font>
            </family>
            <family name="serif-monospace">
                <font weight="400" style="normal">CutiveMono.ttf</font>
            </family>
            <family name="casual">
                <font weight="400" style="normal">ComingSoon.ttf</font>
            </family>
            <family name="cursive">
                <font weight="400" style="normal">DancingScript-Regular.ttf</font>
            </family>
            <family name="sans-serif-smallcaps">
                <font weight="400" style="normal">CarroisGothicSC-Regular.ttf</font>
            </family>
            <family name="vendor-grotesk">
                <font weight="400" style="normal">VendorGrotesk-Regular.ttf</font>
            </family>
            <family lang="und-Arab" variant="elegant">
                <font weight="400" style="normal">NotoNaskhArabic-Regular.ttf</font>
            </family>
            <family lang="ja">
                <font weight="400" style="normal">NotoSansCJK-Regular.ttc</font>
            </family>
        </familyset>
    """.trimIndent()

    // ── parseFontFamilyNames ─────────────────────────────────────────────────

    @Test fun `parses named families including vendor additions`() {
        val names = parseFontFamilyNames(fontsXml)

        assertThat(names).containsAtLeast(
            "sans-serif", "sans-serif-condensed", "serif", "monospace",
            "serif-monospace", "casual", "cursive", "sans-serif-smallcaps",
            "vendor-grotesk",
        )
    }

    @Test fun `parses weight-bearing aliases but not pure renames`() {
        val names = parseFontFamilyNames(fontsXml)

        // The weight variants are how modern AOSP ships light / medium /
        // black; they must surface even though they're aliases.
        assertThat(names).containsAtLeast(
            "sans-serif-thin", "sans-serif-light", "sans-serif-medium",
            "sans-serif-black", "sans-serif-condensed-light",
        )
        // Pure renames resolve to a typeface already in the list; parsing
        // skips them outright so dedupe has less framework work to do.
        assertThat(names).containsNoneOf("arial", "helvetica", "times")
    }

    @Test fun `skips unnamed locale-fallback families`() {
        // The lang= fallback chains carry no name attribute and aren't
        // selectable; they must not leak placeholder entries into the list.
        val names = parseFontFamilyNames(fontsXml)

        names.forEach { assertThat(it).isNotEmpty() }
        assertThat(names).hasSize(names.distinct().size)
        assertThat(names).hasSize(14)
    }

    @Test fun `empty or garbage input parses to an empty list`() {
        assertThat(parseFontFamilyNames("")).isEmpty()
        assertThat(parseFontFamilyNames("not xml at all")).isEmpty()
    }

    // ── prettyFontFamilyName ─────────────────────────────────────────────────

    @Test fun `prettifies family slugs`() {
        assertThat(prettyFontFamilyName("sans-serif-condensed")).isEqualTo("Sans Serif Condensed")
        assertThat(prettyFontFamilyName("sans-serif")).isEqualTo("Sans Serif")
        assertThat(prettyFontFamilyName("casual")).isEqualTo("Casual")
        assertThat(prettyFontFamilyName("serif-monospace")).isEqualTo("Serif Monospace")
        assertThat(prettyFontFamilyName("source-sans-pro")).isEqualTo("Source Sans Pro")
    }

    // ── orderFontFamilies ────────────────────────────────────────────────────

    @Test fun `curated prominence order comes first then the rest alphabetically`() {
        val ordered = orderFontFamilies(
            listOf(
                "zz-vendor", "cursive", "monospace", "aa-vendor",
                "sans-serif", "serif", "sans-serif-smallcaps",
            ),
        )

        assertThat(ordered).containsExactly(
            // Curated order, only the present ones:
            "sans-serif", "serif", "monospace", "cursive",
            // Then alphabetical:
            "aa-vendor", "sans-serif-smallcaps", "zz-vendor",
        ).inOrder()
    }

    // ── dedupeFontFamilies ───────────────────────────────────────────────────

    @Test fun `drops families that resolve to an already-seen typeface`() {
        // Fake resolver: strings stand in for Typeface; equal string = the
        // framework fell back to the same face.
        val resolved = mapOf(
            "sans-serif" to "roboto-400",
            "arial" to "roboto-400",            // rename: same face
            "sans-serif-light" to "roboto-300", // weight variant: distinct
            "serif" to "noto-serif",
            "made-up-family" to "roboto-400",   // unavailable: falls back
        )

        val kept = dedupeFontFamilies(
            listOf("sans-serif", "arial", "sans-serif-light", "serif", "made-up-family"),
        ) { resolved[it] }

        assertThat(kept).containsExactly("sans-serif", "sans-serif-light", "serif").inOrder()
    }

    @Test fun `drops families the resolver cannot resolve at all`() {
        val kept = dedupeFontFamilies(listOf("good", "broken")) { name ->
            name.takeIf { it == "good" }
        }

        assertThat(kept).containsExactly("good")
    }

    @Test fun `first name in list order wins its duplicates`() {
        // Order matters: dedupe runs after the prominence sort so the curated
        // name is the representative its aliases collapse into.
        val kept = dedupeFontFamilies(listOf("sans-serif", "vendor-alias")) { "same-face" }

        assertThat(kept).containsExactly("sans-serif")
    }
}
