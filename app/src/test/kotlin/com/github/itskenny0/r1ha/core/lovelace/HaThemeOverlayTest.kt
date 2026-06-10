package com.github.itskenny0.r1ha.core.lovelace

import androidx.compose.ui.graphics.Color
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class HaThemeOverlayTest {

    // -----------------------------------------------------------------------
    // parseCssColor
    // -----------------------------------------------------------------------

    @Test fun `hex6 parses to opaque ARGB`() {
        val c = parseCssColor("#ff8800")
        assertThat(c).isNotNull()
        assertThat(c!!.red).isWithin(0.01f).of(1.0f)
        assertThat(c.green).isWithin(0.01f).of(0.533f)
        assertThat(c.blue).isWithin(0.01f).of(0.0f)
        assertThat(c.alpha).isWithin(0.01f).of(1.0f)
    }

    @Test fun `hex3 expands each nibble`() {
        val c = parseCssColor("#f80")
        assertThat(c).isNotNull()
        // #f80 -> #ff8800
        assertThat(c!!.red).isWithin(0.01f).of(1.0f)
        assertThat(c.green).isWithin(0.01f).of(0.533f)
        assertThat(c.blue).isWithin(0.01f).of(0.0f)
    }

    @Test fun `hex6 uppercase is accepted`() {
        val c = parseCssColor("#FF8800")
        assertThat(c).isNotNull()
    }

    @Test fun `rgb() with integer channels`() {
        val c = parseCssColor("rgb(255, 136, 0)")
        assertThat(c).isNotNull()
        assertThat(c!!.red).isWithin(0.01f).of(1.0f)
        assertThat(c.green).isWithin(0.01f).of(0.533f)
        assertThat(c.blue).isWithin(0.01f).of(0.0f)
        assertThat(c.alpha).isWithin(0.01f).of(1.0f)
    }

    @Test fun `rgba() with float alpha`() {
        val c = parseCssColor("rgba(255, 0, 0, 0.5)")
        assertThat(c).isNotNull()
        assertThat(c!!.red).isWithin(0.01f).of(1.0f)
        assertThat(c.alpha).isWithin(0.01f).of(0.5f)
    }

    @Test fun `named color white`() {
        val c = parseCssColor("white")
        assertThat(c).isEqualTo(Color(0xFFFFFFFF.toInt()))
    }

    @Test fun `named color black`() {
        val c = parseCssColor("black")
        assertThat(c).isEqualTo(Color(0xFF000000.toInt()))
    }

    @Test fun `named color orange`() {
        val c = parseCssColor("orange")
        assertThat(c).isNotNull()
    }

    @Test fun `CSS var reference returns null`() {
        assertThat(parseCssColor("var(--primary-color)")).isNull()
    }

    @Test fun `gradient string returns null`() {
        assertThat(parseCssColor("linear-gradient(to bottom, #000, #fff)")).isNull()
    }

    @Test fun `unknown named color returns null`() {
        assertThat(parseCssColor("cornflowerblue")).isNull()
    }

    @Test fun `empty string returns null`() {
        assertThat(parseCssColor("")).isNull()
    }

    @Test fun `malformed hex returns null`() {
        assertThat(parseCssColor("#gg0011")).isNull()
    }

    // -----------------------------------------------------------------------
    // haRelativeLuminance
    // -----------------------------------------------------------------------

    @Test fun `white has luminance 1`() {
        assertThat(haRelativeLuminance(0xFFFFFFFF.toInt())).isWithin(0.001f).of(1.0f)
    }

    @Test fun `black has luminance 0`() {
        assertThat(haRelativeLuminance(0xFF000000.toInt())).isWithin(0.001f).of(0.0f)
    }

    @Test fun `R1 surface 0x141414 is below threshold`() {
        // R1.Surface is 0xFF141414; luminance must be well below the clamping
        // threshold so that the R1 dark surface is never itself clamped.
        val lum = haRelativeLuminance(0xFF141414.toInt())
        assertThat(lum).isLessThan(LIGHT_SURFACE_LUMINANCE_THRESHOLD)
    }

    // -----------------------------------------------------------------------
    // withContrastGuard
    // -----------------------------------------------------------------------

    @Test fun `light card background is clamped to R1 surface`() {
        val overlay = HaThemeOverlay(
            cardBg = Color(0xFFFFFFFF.toInt()),  // pure white
            textPrimary = Color(0xFF000000.toInt()),  // black text
        )
        val guarded = overlay.withContrastGuard()
        // White is above the threshold; must be replaced
        assertThat(guarded.cardBg).isNotEqualTo(Color(0xFFFFFFFF.toInt()))
        // Text tokens reset on a light bg so we never get dark text on dark surface
        assertThat(guarded.textPrimary).isNull()
    }

    @Test fun `dark card background passes through unchanged`() {
        val darkBg = Color(0xFF1A1A2E.toInt())
        val overlay = HaThemeOverlay(cardBg = darkBg, textPrimary = Color(0xFFEDEDED.toInt()))
        val guarded = overlay.withContrastGuard()
        assertThat(guarded.cardBg).isEqualTo(darkBg)
        // Text not nulled when bg is dark
        assertThat(guarded.textPrimary).isNotNull()
    }

    @Test fun `null card bg passes through unchanged`() {
        val overlay = HaThemeOverlay(primary = Color(0xFFF36F21.toInt()))
        val guarded = overlay.withContrastGuard()
        assertThat(guarded).isEqualTo(overlay)
    }

    // -----------------------------------------------------------------------
    // mergedWith
    // -----------------------------------------------------------------------

    @Test fun `merge - other non-null tokens win`() {
        val red = Color(0xFFFF0000.toInt())
        val blue = Color(0xFF0000FF.toInt())
        val green = Color(0xFF00FF00.toInt())
        val base = HaThemeOverlay(primary = red, accent = blue)
        val over = HaThemeOverlay(primary = green)
        val merged = base.mergedWith(over)
        assertThat(merged.primary).isEqualTo(green)
        // base accent survives since over has no accent
        assertThat(merged.accent).isEqualTo(blue)
    }

    @Test fun `merge - null tokens fall through to base`() {
        val red = Color(0xFFFF0000.toInt())
        val base = HaThemeOverlay(cardBg = red)
        val over = HaThemeOverlay()
        val merged = base.mergedWith(over)
        assertThat(merged.cardBg).isEqualTo(red)
    }

    // -----------------------------------------------------------------------
    // haThemeVariablesToOverlay
    // -----------------------------------------------------------------------

    @Test fun `extract primary and accent from vars`() {
        val vars = mapOf(
            "primary-color" to "#ff8800",
            "accent-color" to "#41bdf5",
        )
        val overlay = haThemeVariablesToOverlay(vars)
        assertThat(overlay.primary).isNotNull()
        assertThat(overlay.accent).isNotNull()
    }

    @Test fun `unparseable var is null in overlay`() {
        val vars = mapOf(
            "primary-color" to "var(--something)",
            "card-background-color" to "#111111",
        )
        val overlay = haThemeVariablesToOverlay(vars)
        assertThat(overlay.primary).isNull()
        assertThat(overlay.cardBg).isNotNull()
    }

    @Test fun `light card-background-color triggers clamping`() {
        val vars = mapOf(
            "card-background-color" to "#ffffff",
            "primary-text-color" to "#000000",
        )
        val overlay = haThemeVariablesToOverlay(vars)
        // White bg clamped; text reset
        assertThat(overlay.cardBg).isNotEqualTo(Color(0xFFFFFFFF.toInt()))
        assertThat(overlay.textPrimary).isNull()
    }

    @Test fun `dark theme vars pass through without clamping`() {
        val vars = mapOf(
            "card-background-color" to "#1a1a2e",
            "primary-text-color" to "#ededed",
            "primary-color" to "#e94560",
        )
        val overlay = haThemeVariablesToOverlay(vars)
        assertThat(overlay.cardBg).isNotNull()
        // Luminance of 0x1a1a2e is low enough to pass the threshold
        assertThat(overlay.textPrimary).isNotNull()
    }

    @Test fun `lovelace-background var ref is stripped`() {
        val vars = mapOf(
            "lovelace-background" to "var(--primary-background-color)",
        )
        val overlay = haThemeVariablesToOverlay(vars)
        // var() references are not useful on a native renderer
        assertThat(overlay.viewBackground).isNull()
    }

    // -----------------------------------------------------------------------
    // HaThemeCatalogue
    // -----------------------------------------------------------------------

    @Test fun `effectiveDefaultName prefers dark variant`() {
        val cat = com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue(
            themes = mapOf("MyTheme" to com.github.itskenny0.r1ha.core.ha.HaThemeEntry(
                vars = emptyMap(), darkVars = null, lightVars = null,
            )),
            defaultTheme = "MyTheme",
            defaultDarkTheme = "MyDarkTheme",
        )
        assertThat(cat.effectiveDefaultName()).isEqualTo("MyDarkTheme")
    }

    @Test fun `effectiveDefaultName falls back to default theme`() {
        val cat = com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue(
            themes = mapOf("MyTheme" to com.github.itskenny0.r1ha.core.ha.HaThemeEntry(
                vars = emptyMap(), darkVars = null, lightVars = null,
            )),
            defaultTheme = "MyTheme",
            defaultDarkTheme = null,
        )
        assertThat(cat.effectiveDefaultName()).isEqualTo("MyTheme")
    }

    @Test fun `effectiveDefaultName returns null for built-in default`() {
        val cat = com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue.EMPTY
        assertThat(cat.effectiveDefaultName()).isNull()
    }

    @Test fun `resolvedVarsFor merges dark mode on top of base`() {
        val cat = com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue(
            themes = mapOf("T" to com.github.itskenny0.r1ha.core.ha.HaThemeEntry(
                vars = mapOf("primary-color" to "#ff0000", "accent-color" to "#00ff00"),
                darkVars = mapOf("primary-color" to "#0000ff"),
                lightVars = null,
            )),
            defaultTheme = "T",
            defaultDarkTheme = null,
        )
        val vars = cat.resolvedVarsFor("T")
        assertThat(vars).isNotNull()
        // dark mode overrides primary-color
        assertThat(vars!!["primary-color"]).isEqualTo("#0000ff")
        // base accent-color survives
        assertThat(vars["accent-color"]).isEqualTo("#00ff00")
    }

    @Test fun `resolvedVarsFor returns null for unknown name`() {
        assertThat(com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue.EMPTY.resolvedVarsFor("NeverHappens")).isNull()
    }

    @Test fun `resolvedVarsFor returns null for default`() {
        val cat = com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue(
            themes = mapOf("default" to com.github.itskenny0.r1ha.core.ha.HaThemeEntry(
                vars = mapOf("primary-color" to "#123456"), darkVars = null, lightVars = null,
            )),
            defaultTheme = "default",
            defaultDarkTheme = null,
        )
        assertThat(cat.resolvedVarsFor("default")).isNull()
    }
}
