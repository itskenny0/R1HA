package com.github.itskenny0.r1ha.wear.theme

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Typography

// ── Palette ─────────────────────────────────────────────────────────────────
// A dark-first palette tuned for AMOLED watch screens. Keep black (#000) as
// the true background to maximise battery life on Galaxy Watch OLED displays.

private val WearPrimary        = Color(0xFFFF6600)  // HA orange
private val WearPrimaryVariant = Color(0xFFCC5200)
private val WearSecondary      = Color(0xFF03DAC6)
private val WearBackground     = Color(0xFF000000)
private val WearSurface        = Color(0xFF1A1A1A)
private val WearError          = Color(0xFFCF6679)
private val WearOnPrimary      = Color(0xFFFFFFFF)
private val WearOnSecondary    = Color(0xFF000000)
private val WearOnBackground   = Color(0xFFEEEEEE)
private val WearOnSurface      = Color(0xFFCCCCCC)
private val WearOnError        = Color(0xFF000000)

private val WearColorScheme = Colors(
    primary          = WearPrimary,
    primaryVariant   = WearPrimaryVariant,
    secondary        = WearSecondary,
    secondaryVariant = WearPrimaryVariant,
    background       = WearBackground,
    surface          = WearSurface,
    error            = WearError,
    onPrimary        = WearOnPrimary,
    onSecondary      = WearOnSecondary,
    onBackground     = WearOnBackground,
    onSurface        = WearOnSurface,
    onError          = WearOnError,
)

// Convenience colour shortcuts accessible outside the theme composition.
object WearColors {
    val Bg          get() = WearBackground
    val Surface     get() = WearSurface
    val OnBg        get() = WearOnBackground
    val OnSurface   get() = WearOnSurface
    val Primary     get() = WearPrimary
    val Error       get() = WearError
}

// ── Theme host ───────────────────────────────────────────────────────────────

/**
 * Root Wear Material theme for the HA Watch app.
 *
 * Wraps content in [androidx.wear.compose.material.MaterialTheme] so all
 * Wear Compose components (Chip, Button, ScalingLazyColumn, etc.) inherit
 * the HA colour palette and dark background automatically.
 *
 * Uses the phone app's `R1Theme` indirectly: the shared core code doesn't
 * depend on the theme, so there's nothing to migrate — the wear-specific
 * theme is self-contained.
 */
@Composable
fun WearTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = WearColorScheme,
        typography = Typography(),
        content = content,
    )
}
