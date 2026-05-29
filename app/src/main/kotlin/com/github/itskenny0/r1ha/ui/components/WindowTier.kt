package com.github.itskenny0.r1ha.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration

/**
 * The responsive foundation: a five-step width tier the whole app reasons about, derived
 * from the window's width in dp. Aligned to Material 3's WindowSizeClass breakpoints but
 * extended at BOTH ends so the project's two extremes get first-class layouts:
 *
 *  - the Rabbit R1's ~240-340 dp portrait panel (the original wheel-driven home), and
 *  - genuine large tablets / desktop-class windows where a phone layout stretched edge
 *    to edge would look broken.
 *
 * Thresholds (lower bound inclusive, finalised here as the single source of truth):
 *
 * | Tier        | Width range        | Intent                                              |
 * |-------------|--------------------|-----------------------------------------------------|
 * | [R1]        | `< 360.dp`         | Rabbit R1 + tiny phones. The EXISTING card-stack /  |
 * |             |                    | wheel experience renders bit-for-bit unchanged.     |
 * | [COMPACT]   | `360 .. 599.dp`    | Ordinary phones in portrait. Single pane, bottom /  |
 * |             |                    | in-place navigation, gentle breathing room.         |
 * | [MEDIUM]    | `600 .. 839.dp`    | Small tablets, foldables, large landscape phones.   |
 * |             |                    | NavigationRail; two-pane where it fits.             |
 * | [EXPANDED]  | `840 .. 1199.dp`   | Big tablets in landscape. Permanent drawer +        |
 * |             |                    | two-pane list/detail; multi-column dashboard.       |
 * | [EXTRA_LARGE]| `>= 1200.dp`      | Desktop-class windows / 12"+ tablets. Same as       |
 * |             |                    | EXPANDED but content is centred and line-length     |
 * |             |                    | capped instead of stretching full-bleed.            |
 *
 * The R1 ceiling is intentionally pinned at 360 dp (rather than Material's 600) so the
 * device sits squarely in the smallest bucket regardless of a LineageOS / GSI `wm density`
 * tweak that nudges its reported width up or down a few dp.
 *
 * Tiers are ordinal-ordered smallest → largest, so `tier >= WindowTier.MEDIUM` reads
 * naturally; the [isAtLeast] helper and the cached booleans on [WindowTierInfo] exist so
 * call sites never have to remember ordinal arithmetic.
 *
 * Adoption pattern: read [LocalWindowTier] (or call [rememberWindowTier]) once near the top
 * of a screen, branch on `info.tier` for structural decisions (one pane vs two, rail vs
 * drawer), and pull spacing / column counts from the tier-aware helpers in
 * `core/theme/ResponsiveTokens` rather than hardcoding per-screen dp.
 */
enum class WindowTier {
    /** `< 360.dp` — Rabbit R1 native portrait + tiny phones. Layout is rendered exactly as
     *  authored; no max-width clamp, no extra gutter, wheel-first navigation preserved. */
    R1,

    /** `360 .. 599.dp` — mainstream phones in portrait. Single pane. */
    COMPACT,

    /** `600 .. 839.dp` — small tablets, unfolded foldables, landscape phones. */
    MEDIUM,

    /** `840 .. 1199.dp` — large tablets in landscape; room for a permanent two-pane. */
    EXPANDED,

    /** `>= 1200.dp` — desktop-class / 12"+ tablet windows; centre + cap line length. */
    EXTRA_LARGE,
    ;

    /** True when this tier is at least [other] in the smallest → largest ordering. */
    fun isAtLeast(other: WindowTier): Boolean = ordinal >= other.ordinal
}

/**
 * Width thresholds (in dp) finalised for the project, exposed so tests and any non-Compose
 * caller can resolve a tier without a composition. Each constant is the INCLUSIVE lower
 * bound of the tier it names; [WindowTier.R1] has no lower bound.
 */
object WindowTierBreakpoints {
    const val COMPACT_MIN_DP = 360
    const val MEDIUM_MIN_DP = 600
    const val EXPANDED_MIN_DP = 840
    const val EXTRA_LARGE_MIN_DP = 1200

    /** Pure width → tier mapping. The single place thresholds live; everything else
     *  (the composable reader, the tests) routes through here. */
    fun tierForWidthDp(widthDp: Int): WindowTier = when {
        widthDp < COMPACT_MIN_DP -> WindowTier.R1
        widthDp < MEDIUM_MIN_DP -> WindowTier.COMPACT
        widthDp < EXPANDED_MIN_DP -> WindowTier.MEDIUM
        widthDp < EXTRA_LARGE_MIN_DP -> WindowTier.EXPANDED
        else -> WindowTier.EXTRA_LARGE
    }
}

/**
 * Immutable snapshot of the current window's responsive context: the resolved [tier], the
 * raw width / height in dp, and pre-computed `isAtLeast*` booleans so common branches read
 * as plain English at the call site (`if (window.isAtLeastMedium) …`).
 *
 * Height is tracked too: a few surfaces (the card stack's vertical pager, a tall dashboard)
 * care whether they have vertical room, not just horizontal. [isLandscape] and
 * [isTallEnoughForTwoRows] surface that without each screen re-deriving it.
 */
@Immutable
data class WindowTierInfo(
    val tier: WindowTier,
    val widthDp: Int,
    val heightDp: Int,
) {
    val isR1: Boolean get() = tier == WindowTier.R1
    val isAtLeastCompact: Boolean get() = tier.isAtLeast(WindowTier.COMPACT)
    val isAtLeastMedium: Boolean get() = tier.isAtLeast(WindowTier.MEDIUM)
    val isAtLeastExpanded: Boolean get() = tier.isAtLeast(WindowTier.EXPANDED)
    val isExtraLarge: Boolean get() = tier == WindowTier.EXTRA_LARGE

    /** Wider than it is tall. Drives "can I afford a side rail / two panes?" alongside tier. */
    val isLandscape: Boolean get() = widthDp >= heightDp

    /** Enough vertical room to stack two card rows without crowding — guards the dashboard's
     *  taller multi-row arrangements from triggering on a short landscape phone. */
    val isTallEnoughForTwoRows: Boolean get() = heightDp >= 560
}

/**
 * The responsive context for the current composition. Provided once high in the tree (see
 * [ProvideWindowTier] / MainActivity) so any descendant can read it cheaply without
 * re-measuring. Defaults to an [WindowTier.R1] snapshot so a composable rendered outside the
 * provider (a preview, an early-boot surface) behaves like the smallest, safest tier rather
 * than crashing or assuming a large window.
 */
val LocalWindowTier = staticCompositionLocalOf {
    WindowTierInfo(tier = WindowTier.R1, widthDp = 0, heightDp = 0)
}

/**
 * Reads [LocalConfiguration] and resolves the current [WindowTierInfo]. Cheap: configuration
 * is already part of every composition, and the result is a value-equal [Immutable] so it
 * only re-provides / recomposes consumers when the tier or dimensions actually change.
 *
 * Prefer reading [LocalWindowTier] in leaf composables; call this only where you provide the
 * local (the shell) or in a screen that isn't yet under the provider.
 */
@Composable
@ReadOnlyComposable
fun rememberWindowTier(): WindowTierInfo {
    val config = LocalConfiguration.current
    val w = config.screenWidthDp
    val h = config.screenHeightDp
    return WindowTierInfo(
        tier = WindowTierBreakpoints.tierForWidthDp(w),
        widthDp = w,
        heightDp = h,
    )
}

/**
 * Provides [LocalWindowTier] for [content], computing the snapshot from the live
 * configuration. Place this once near the root (MainActivity wraps the nav graph in it) so
 * the whole app shares one resolved tier. Idempotent to nest — an inner provider simply
 * recomputes the same value from the same configuration.
 */
@Composable
fun ProvideWindowTier(content: @Composable () -> Unit) {
    val info = rememberWindowTier()
    androidx.compose.runtime.CompositionLocalProvider(LocalWindowTier provides info) {
        content()
    }
}
