package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.pager.PageSize
import androidx.compose.ui.unit.Density
import com.github.itskenny0.r1ha.core.prefs.CardPeekMode
import com.github.itskenny0.r1ha.ui.components.WindowTier
import kotlin.math.roundToInt

/**
 * Pure decision for whether the card stack renders the half-height "peek deck"
 * (active card centred, previous and next cards peeking) instead of the historical
 * full-viewport single card.
 *
 * Semantics, by [mode]:
 *
 *  - [CardPeekMode.AUTO]   — peek only on a phone-width tier in portrait. The phone
 *    tiers are [WindowTier.COMPACT] and [WindowTier.MEDIUM]; the R1 / sub-compact
 *    tier ([WindowTier.R1]) and the tablet / desktop tiers ([WindowTier.EXPANDED],
 *    [WindowTier.EXTRA_LARGE]) stay full-viewport, as does any landscape orientation.
 *  - [CardPeekMode.ALWAYS] — peek on every device and orientation (the opt-in path
 *    for R1 / small-phone users).
 *  - [CardPeekMode.NEVER]  — full-viewport everywhere.
 *
 * Kept pure (no Compose, no Android) so it can be unit-tested directly and so the
 * caller reads it once per composition from the resolved tier + orientation.
 */
fun effectivePeek(mode: CardPeekMode, tier: WindowTier, isPortrait: Boolean): Boolean = when (mode) {
    CardPeekMode.NEVER -> false
    CardPeekMode.ALWAYS -> true
    CardPeekMode.AUTO -> isPortrait && isPhonePeekTier(tier)
}

/**
 * The width tiers AUTO treats as "phone": ordinary phones in portrait. The R1 /
 * sub-compact tier is deliberately excluded (its narrow panel has no room to peek a
 * useful slice of two neighbours), and the tablet / desktop tiers are excluded
 * because a half-height card centred in a tall window wastes the extra space the
 * full-screen single card already fills well.
 */
private fun isPhonePeekTier(tier: WindowTier): Boolean =
    tier == WindowTier.COMPACT || tier == WindowTier.MEDIUM

/**
 * A [PageSize] that measures each page as a [fraction] of the pager's available main-axis
 * space (the viewport minus the pager's content padding). Compose's bundled [PageSize]
 * only ships [PageSize.Fill] and [PageSize.Fixed]; the peek deck needs a fractional size so
 * the active card occupies just over half the viewport and the neighbours peek into the
 * remainder. The page spacing is left out of the fraction (the spacing sits in the gaps
 * between pages) so two visible peek slices plus the centred card share the leftover space
 * evenly. Value class semantics via a data class keep [equals] stable so the pager doesn't
 * re-measure when the same fraction recomposes.
 */
data class FractionPageSize(val fraction: Float) : PageSize {
    override fun Density.calculateMainAxisPageSize(availableSpace: Int, pageSpacing: Int): Int =
        (availableSpace * fraction).roundToInt()
}
