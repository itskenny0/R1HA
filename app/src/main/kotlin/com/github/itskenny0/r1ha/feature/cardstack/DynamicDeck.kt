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
 * End content padding (px) that lets a FIRST or LAST dynamic-deck card reach
 * the CENTER snap line of the viewport band.
 *
 * The deck centre-snaps (the focused card sits in the middle of the band, the
 * neighbour above and below peeking, matching the fullscreen peek deck). A
 * card whose centre is to align with the band centre needs (band - itemHeight)
 * / 2 of free space on its outer side: the top card needs that much top
 * padding so it can scroll down into the centre, the bottom card needs the
 * same as bottom padding so it can scroll up into the centre. Without it the
 * list clamps the first card flush to the band top and the last card flush to
 * the band bottom, so neither end card could ever be centred (nor become the
 * focused wheel target).
 *
 * Half of the old start-snap tail: a centred last card balances its
 * (band - height)/2 bottom pad against the previous card peeking above, so it
 * reads as a centred card rather than the near-full-viewport void the
 * top-snap (band - height) pad used to leave.
 *
 *  - [endItemHeightPx] null = the end card has not been measured yet (never
 *    composed, or the deck's head/tail just changed). Zero padding: it appears
 *    as the user approaches that end, never speculatively.
 *  - An end card at least as tall as the band needs no help (returns 0): a
 *    band-filling card is already its own centre.
 *
 * The caller must drop its cached measurement whenever the relevant end item
 * changes identity; carrying a height measured for a DIFFERENT card was how a
 * hidden/removed conditional card left a stale blank gap.
 */
fun dynamicCenterPaddingPx(bandHeightPx: Int, endItemHeightPx: Int?): Int {
    if (endItemHeightPx == null) return 0
    return ((bandHeightPx - endItemHeightPx) / 2).coerceIn(0, bandHeightPx.coerceAtLeast(0))
}

/**
 * One visible item of the dynamic deck list, as (index, main-axis start offset
 * in px relative to the band top, item height in px). A thin pure projection of
 * LazyListLayoutInfo.visibleItemsInfo so the focused-index math is testable
 * without a composition. The height is carried so the focused item can be
 * picked by its CENTRE, matching the centre-snap presentation.
 */
data class DynamicVisibleItem(val index: Int, val offsetPx: Int, val sizePx: Int)

/**
 * Which item of the dynamic deck is the FOCUSED one: the item whose CENTRE sits
 * nearest the band centre ([bandHeightPx] / 2). The deck centre-snaps, so after
 * a snap settles this is exactly the snapped (centred) item; mid-list it is the
 * dominant visible card. Ties (two card centres equidistant from the band
 * centre) break toward the EARLIER index so focus never jumps ahead of the
 * snap. Empty list (deck cleared mid-frame) falls back to 0, matching the
 * pager's realIndexOf guard.
 */
fun dynamicFocusedIndex(visible: List<DynamicVisibleItem>, bandHeightPx: Int): Int {
    val bandCenter = bandHeightPx / 2
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    for (item in visible) {
        val itemCenter = item.offsetPx + item.sizePx / 2
        val distance = kotlin.math.abs(itemCenter - bandCenter)
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
