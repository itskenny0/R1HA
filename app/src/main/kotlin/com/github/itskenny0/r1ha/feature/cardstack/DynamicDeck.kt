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
 * Per-item height policy in the DYNAMIC layout: whether this deck item's snap
 * block fills the deck viewport or hugs its content.
 *
 * Entity cards fill: their interior (value bar, percent readout, wheel
 * affordances, peek chrome) is designed as a full-screen control surface and
 * the wheel drives the FOCUSED card, so an entity card must still snap in
 * full-screen exactly as it does in the FULLSCREEN layout. Lovelace cards hug
 * their content: that is the whole point of the dynamic layout (a one-line
 * toggle stops marooning itself in a viewport of black). Tall Lovelace content
 * still caps at the viewport and scrolls internally (see [DeckCardSurface]).
 */
fun deckItemFillsViewport(item: DeckItem): Boolean = item is DeckItem.Entity

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
