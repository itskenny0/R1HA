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
 * Concrete height, in dp, of a card's VERTICAL value-bar tape meter when the
 * card wraps to its content height (the DYNAMIC layout's entity items).
 *
 * Entity cards no longer fill the deck viewport here (user-confirmed: the
 * whole stack should FLOW, several cards visible at once); only the FULLSCREEN
 * layout keeps the full-viewport wheel surface. A previous revision expressed
 * "content height" as a flat 220 dp item cap, which clipped every rich
 * variant: climate cards lost their HVAC mode buttons, lights their scene and
 * effect rows. The card must instead wrap to the real sum of its controls,
 * and the only blocker to that is the meter: querying intrinsics is
 * impossible (the tape meters are BoxWithConstraints/SubcomposeLayout-backed,
 * which throws on intrinsic measurement), so rather than ASK the layout for a
 * natural height, the wrap-mode path gives every fill-height element a
 * concrete one. This constant is the meter's: tall enough that its five tick
 * labels (e.g. a climate scale's 35/27/20/12/4) keep comfortable tap spacing
 * and a touch-drag spans usable resolution, short enough that a meter-only
 * card stays a compact block in the flowing deck. Everything else in the card
 * body (header, readout, button rows, panels) already has a natural height,
 * so the card's total height is the plain sum of its children, capped at the
 * deck viewport with internal scroll past it (the [DeckCardSurface] contract:
 * the scroll only grabs gestures when content genuinely overflows).
 */
const val DYNAMIC_VALUE_BAR_HEIGHT_DP = 180

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
