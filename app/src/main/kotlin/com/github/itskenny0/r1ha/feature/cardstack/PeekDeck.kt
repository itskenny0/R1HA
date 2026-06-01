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
 *  - [CardPeekMode.AUTO]   — peek only on a phone-width tier in portrait whose panel is
 *    also physically large enough: at least [PEEK_MIN_SHORTEST_SIDE_PX] raw pixels on its
 *    shortest side. The phone tiers are [WindowTier.COMPACT] and [WindowTier.MEDIUM]; the
 *    R1 / sub-compact tier ([WindowTier.R1]) and the tablet / desktop tiers
 *    ([WindowTier.EXPANDED], [WindowTier.EXTRA_LARGE]) stay full-viewport, as does any
 *    landscape orientation. The raw-pixel floor is what actually keeps the R1 out: its
 *    240 px panel can report a COMPACT-range width in dp on some ROMs (its density is low
 *    and ROM-dependent), but its pixel count is fixed and far below any real phone's.
 *  - [CardPeekMode.ALWAYS] — peek on every device and orientation (the opt-in path
 *    for R1 / small-phone users); ignores the raw-pixel floor.
 *  - [CardPeekMode.NEVER]  — full-viewport everywhere.
 *
 * Kept pure (no Compose, no Android) so it can be unit-tested directly and so the caller
 * reads it once per composition from the resolved tier + orientation + window pixel size.
 */
fun effectivePeek(
    mode: CardPeekMode,
    tier: WindowTier,
    isPortrait: Boolean,
    shortestSidePx: Int,
): Boolean = when (mode) {
    CardPeekMode.NEVER -> false
    CardPeekMode.ALWAYS -> true
    CardPeekMode.AUTO ->
        isPortrait && isPhonePeekTier(tier) && shortestSidePx >= PEEK_MIN_SHORTEST_SIDE_PX
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
 * The minimum shortest-side size, in RAW pixels, an AUTO deck needs before it peeks.
 *
 * The Rabbit R1's 240 px panel can report a portrait width of 360 dp or more (its density is
 * low and ROM-dependent), which lands it in the COMPACT tier and would otherwise enable peek
 * under AUTO despite there being no room for it. Raw pixels are density- and ROM-independent:
 * no ordinary phone has a shortest side below ~720 px and the R1's is 240 px, so a 600 px
 * floor cleanly excludes the R1 (and any genuinely sub-compact panel) while admitting every
 * real phone. ALWAYS bypasses this for users who explicitly want peek on a small panel.
 */
const val PEEK_MIN_SHORTEST_SIDE_PX = 600

/**
 * Whether the peek layout should actually render for a deck of [cardCount] cards once the
 * tier/orientation decision [peekEnabled] (from [effectivePeek]) has been made.
 *
 * A single-card deck has no previous/next card to peek, so peeking it would only strand the
 * lone card flush against the top of the deck (the first card snaps to the top) with empty
 * space below. Such a deck renders full-bleed instead, exactly like the historical
 * single-card view. Two or more cards is the point at which a peeking neighbour exists.
 */
fun peekActiveForDeck(peekEnabled: Boolean, cardCount: Int): Boolean =
    peekEnabled && cardCount > 1

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
