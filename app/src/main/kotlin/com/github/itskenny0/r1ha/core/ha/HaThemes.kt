package com.github.itskenny0.r1ha.core.ha

/**
 * A single HA theme as returned by `frontend/get_themes`. The [vars] map holds
 * the base CSS variable overrides; [darkVars] and [lightVars] are the optional
 * per-mode overlays declared under `modes.dark` and `modes.light` in the HA
 * theme YAML. Null modes mean the theme has no mode-specific variants.
 *
 * R1HA always applies dark-mode resolution (the panel is dark): when [darkVars]
 * is non-null it is merged on top of [vars] before variable extraction, matching
 * apply_themes_on_element.ts's `{ ...baseThemeRules, ...modes.dark }` merge.
 */
data class HaThemeEntry(
    val vars: Map<String, String>,
    val darkVars: Map<String, String>?,
    val lightVars: Map<String, String>?,
)

/**
 * The full theme catalogue from `frontend/get_themes`, cached per session.
 *
 *  - [themes]: map of theme name to [HaThemeEntry]. The name `"default"` is
 *    HA's built-in theme and is typically not present here (HA returns it only
 *    when the user has customised it); callers treat an absent "default" as
 *    "no override" and use R1 design system colours.
 *  - [defaultTheme]: the name currently selected as the default HA theme
 *    (`default_theme` field). May be `"default"` (no override) or a custom name.
 *  - [defaultDarkTheme]: the name selected for dark mode specifically
 *    (`default_dark_theme` field). Null when the user hasn't configured a dark-
 *    specific theme. R1HA prefers [defaultDarkTheme] when present because the
 *    panel is always dark.
 */
data class HaThemeCatalogue(
    val themes: Map<String, HaThemeEntry>,
    val defaultTheme: String,
    val defaultDarkTheme: String?,
) {
    companion object {
        val EMPTY = HaThemeCatalogue(
            themes = emptyMap(),
            defaultTheme = "default",
            defaultDarkTheme = null,
        )
    }

    /**
     * Resolve the effective default theme name for R1HA. Prefers the dark
     * variant; falls back to [defaultTheme]. Returns null when both resolve to
     * `"default"` (meaning HA's built-in, no named theme override).
     */
    fun effectiveDefaultName(): String? {
        val name = defaultDarkTheme?.takeUnless { it == "default" || it.isBlank() }
            ?: defaultTheme.takeUnless { it == "default" || it.isBlank() }
        return name
    }

    /**
     * Resolve the dark-mode–merged variable map for a theme by [name]. When the
     * theme has `modes.dark`, those variables are merged on top of the base vars
     * (matching HA's apply_themes_on_element merge order). Returns null when
     * [name] is not in the catalogue or is blank / "default".
     */
    fun resolvedVarsFor(name: String?): Map<String, String>? {
        if (name.isNullOrBlank() || name == "default") return null
        val entry = themes[name] ?: return null
        return if (entry.darkVars != null) {
            entry.vars + entry.darkVars
        } else {
            entry.vars
        }
    }
}
