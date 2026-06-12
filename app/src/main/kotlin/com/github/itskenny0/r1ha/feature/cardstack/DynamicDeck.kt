package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.ui.components.WindowTier

/**
 * The two concrete deck layouts a page can render. [DeckLayoutMode] is the USER
 * setting (which includes AUTO); this is what AUTO resolves into, so the render
 * branch in CardStackScreen never has to reason about AUTO itself.
 */
enum class DeckLayout { FULLSCREEN, DYNAMIC }

/**
 * Pure decision for which deck layout the card stack renders.
 *
 *  - [DeckLayoutMode.FULLSCREEN] / [DeckLayoutMode.DYNAMIC] force that layout
 *    regardless of screen size.
 *  - [DeckLayoutMode.AUTO] resolves by width tier: the small tiers
 *    ([WindowTier.R1], [WindowTier.COMPACT]) keep the historical full-viewport
 *    pager (a content-sized block on a 640x480 panel is too small to read or
 *    hit), while [WindowTier.MEDIUM] and up get the content-height DYNAMIC
 *    list, where several blocks fit comfortably.
 *
 * When the resolved layout is DYNAMIC it supersedes the peek deck (the
 * half-height pager presentation): the dynamic list already shows neighbouring
 * cards, so layering peek on top would be redundant. The caller only consults
 * [effectivePeek] on the FULLSCREEN branch.
 *
 * Kept pure (no Compose, no Android) so it can be unit-tested directly,
 * mirroring [effectivePeek].
 */
fun effectiveDeckLayout(mode: DeckLayoutMode, tier: WindowTier): DeckLayout = when (mode) {
    DeckLayoutMode.FULLSCREEN -> DeckLayout.FULLSCREEN
    DeckLayoutMode.DYNAMIC -> DeckLayout.DYNAMIC
    DeckLayoutMode.AUTO ->
        if (tier.isAtLeast(WindowTier.MEDIUM)) DeckLayout.DYNAMIC else DeckLayout.FULLSCREEN
}

/**
 * Height of an ENTITY deck item in the DYNAMIC layout, in px.
 *
 * Entity cards no longer fill the deck viewport here (user-confirmed: the
 * whole stack should FLOW, several cards visible at once); only the FULLSCREEN
 * layout keeps the full-viewport wheel surface. True wrap-content is not an
 * option for these cards: every variant's interior is a weight-based
 * full-slot layout and the value-bar tape meters are SubcomposeLayout-backed
 * (BoxWithConstraints), which throws on intrinsic measurement, so "natural
 * content height" must be expressed as a compact design height instead. The
 * compact band ([preferredHeightPx], ~[DYNAMIC_ENTITY_CARD_HEIGHT_DP] dp at
 * the call site) is the same order of size the half-height peek deck proved
 * usable for the full control surface (value bar, glyph, readout all keep
 * working; they are built to fill whatever slot they get). Capped at the deck
 * viewport ([bandHeightPx]) so a short window never produces an item taller
 * than the band; with the cap the item can never overflow, so entity items
 * need no internal scroll (deliberate: an extra vertical scrollable per card
 * face would re-create the cross-axis gesture capture fixed in
 * [DeckCardSurface]).
 */
fun dynamicEntityItemHeightPx(bandHeightPx: Int, preferredHeightPx: Int): Int =
    preferredHeightPx.coerceAtMost(bandHeightPx).coerceAtLeast(0)

/** Compact entity-card height for the DYNAMIC deck, see [dynamicEntityItemHeightPx]. */
const val DYNAMIC_ENTITY_CARD_HEIGHT_DP = 220

/**
 * Bottom content padding (px) that lets the LAST dynamic-deck card reach the
 * start-snap line at the viewport top.
 *
 * With start snapping the list stops scrolling when its content end meets the
 * viewport bottom, so a last card shorter than the band could never put its
 * start on the snap line and could never become the focused (wheel) target.
 * Padding the end by exactly (band - last item height) raises the max scroll
 * by exactly the missing amount: enough to snap the last card, never more, so
 * no dead space beyond "last card at the line".
 *
 *  - [lastItemHeightPx] null = the last card has not been measured yet (it has
 *    never been composed, or the deck's tail just changed). Zero padding: the
 *    padding appears as the user approaches the end, never speculatively.
 *  - A last card at least as tall as the band needs no help (returns 0).
 *
 * The caller must drop its cached measurement whenever the deck's last item
 * changes identity; carrying a height measured for a DIFFERENT card was how
 * a hidden/removed conditional card left a stale over-sized blank tail.
 */
fun dynamicEndReachPaddingPx(bandHeightPx: Int, lastItemHeightPx: Int?): Int {
    if (lastItemHeightPx == null) return 0
    return (bandHeightPx - lastItemHeightPx).coerceIn(0, bandHeightPx.coerceAtLeast(0))
}

/**
 * One visible item of the dynamic deck list, as (index, main-axis offset in px
 * relative to the snap line at the viewport top). A thin pure projection of
 * LazyListLayoutInfo.visibleItemsInfo so the focused-index math is testable
 * without a composition.
 */
data class DynamicVisibleItem(val index: Int, val offsetPx: Int)

/**
 * Which item of the dynamic deck is the FOCUSED one: the item whose start sits
 * nearest the snap line (offset 0 at the viewport top). After a snap settles
 * this is exactly the snapped item; mid-list it is the dominant visible card.
 * Ties (a card's end and the next card's start equidistant around the line)
 * break toward the EARLIER index so focus never jumps ahead of the snap.
 * Empty list (deck cleared mid-frame) falls back to 0, matching the pager's
 * realIndexOf guard.
 */
fun dynamicFocusedIndex(visible: List<DynamicVisibleItem>): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    for (item in visible) {
        val distance = kotlin.math.abs(item.offsetPx)
        // Strict less-than: on a tie the earlier item (lists are in index
        // order) keeps the focus.
        if (distance < bestDistance) {
            bestDistance = distance
            bestIndex = item.index
        }
    }
    return bestIndex
}

/**
 * Snap-target index for a programmatic move over the dynamic deck: a pip jump
 * straight to [targetIndex], or a hardware-key step of [delta] cards from
 * [currentIndex] (pass `targetIndex = currentIndex + delta`). Clamped to the
 * finite deck: the dynamic layout never wraps. Infinite scroll stays a
 * FULLSCREEN-only feature because a LazyColumn of measured-on-demand,
 * per-item-height blocks has no honest analogue of the pager's fixed-size
 * 200k-virtual-page trick; faking it with huge item counts would force
 * speculative measurement of unknown heights. Empty deck pins to 0.
 */
fun dynamicSnapTarget(targetIndex: Int, itemCount: Int): Int =
    targetIndex.coerceIn(0, (itemCount - 1).coerceAtLeast(0))
