package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.ui.components.WindowTier

/**
 * The two concrete deck layouts a page can render. [DeckLayoutMode] is the USER
 * setting (which includes AUTO); this is what AUTO resolves into, so the render
 * branch in CardStackScreen never has to reason about AUTO itself.
 */
enum class DeckLayout { FULLSCREEN, HALF_HEIGHT, DYNAMIC }

/**
 * Pure decision for which deck layout the card stack renders.
 *
 *  - [DeckLayoutMode.FULLSCREEN] / [DeckLayoutMode.HALF_HEIGHT] /
 *    [DeckLayoutMode.DYNAMIC] force that layout regardless of screen size.
 *    HALF_HEIGHT is the explicit half-height peek presentation (focused card
 *    centred, neighbours peeking); the caller forces peek on for it.
 *  - [DeckLayoutMode.AUTO] keeps PHYSICALLY SMALL screens on the historical
 *    full-viewport pager, where a content-sized block is too small to read or
 *    hit, and gives every roomier screen the (now mature) content-height DYNAMIC
 *    list. "Small" is decided by RAW pixels, not the dp width tier: the R1's
 *    sub-compact panel ([WindowTier.R1]) and any other tiny device whose
 *    shortest side is below [PEEK_MIN_SHORTEST_SIDE_PX] (e.g. a 480 px minimalist
 *    "dumbphone" that still reports a COMPACT dp width) stay full-viewport, while
 *    an ordinary phone (shortest side ~720 px+) gets DYNAMIC. The same raw-pixel
 *    floor the peek deck uses, for the same reason: the dp tier alone can't tell
 *    a real phone from a low-density toy panel. The half-height peek that AUTO
 *    used to produce on phones is the explicit [DeckLayoutMode.HALF_HEIGHT] mode.
 *
 * When the resolved layout is DYNAMIC it supersedes the peek deck: the dynamic
 * list already shows neighbouring cards, so layering peek on top would be
 * redundant. The caller resolves the peek flag from this layout (forced on for
 * HALF_HEIGHT, [effectivePeek] for FULLSCREEN, off for DYNAMIC).
 *
 * Kept pure (no Compose, no Android) so it can be unit-tested directly,
 * mirroring [effectivePeek].
 */
fun effectiveDeckLayout(
    mode: DeckLayoutMode,
    tier: WindowTier,
    shortestSidePx: Int,
): DeckLayout = when (mode) {
    DeckLayoutMode.FULLSCREEN -> DeckLayout.FULLSCREEN
    DeckLayoutMode.HALF_HEIGHT -> DeckLayout.HALF_HEIGHT
    DeckLayoutMode.DYNAMIC -> DeckLayout.DYNAMIC
    DeckLayoutMode.AUTO ->
        if (tier == WindowTier.R1 || shortestSidePx < PEEK_MIN_SHORTEST_SIDE_PX) {
            DeckLayout.FULLSCREEN
        } else {
            DeckLayout.DYNAMIC
        }
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
 * One visible item of the dynamic deck list, as (index, main-axis start offset
 * in px relative to the band top, item height in px). A thin pure projection of
 * LazyListLayoutInfo.visibleItemsInfo so the focused-index math is testable
 * without a composition. The height is carried so the focused item can be
 * picked by the distance from its own SNAP LINE, matching the per-item snap.
 *
 * The list's LAST index is also needed (the last card bottom-aligns rather than
 * top-aligns, see [dynamicSnapStartPx]); the focus math takes it as a separate
 * argument rather than carrying a redundant per-item flag.
 */
data class DynamicVisibleItem(val index: Int, val offsetPx: Int, val sizePx: Int)

/**
 * Where item [itemIndex] of the dynamic deck WANTS its start to sit, measured
 * from the band top, given the band span ([bandHeightPx]), the item's measured
 * height ([itemSizePx]) and the deck's [itemCount] (so the last card can be
 * told apart). This is the per-item TOP-ALIGN snap rule, the single source of
 * truth shared by the custom snap provider (which feeds it through a
 * [androidx.compose.foundation.gestures.snapping.SnapPosition]) and the
 * focused-index math, so the two never disagree about a card's rest line.
 *
 *  - Items 0..n-2 anchor FLUSH at the band TOP (offset 0). Every non-last card
 *    reaches the top by scrolling up (earlier cards roll off the top, later
 *    cards drop below the fold), so the top line is always achievable with NO
 *    top content padding: the first card is flush under the chrome at the raw
 *    top clamp from the very first frame, the rest rise to the top as the user
 *    scrolls down. Natural order falls out of this: scroll up = first card at
 *    the top, scroll down = the next card rises to the top.
 *  - The LAST card (index n-1) BOTTOM-aligns (when [lastCardBottomAligns], the
 *    default): start at `band - itemHeight`, so its bottom edge sits flush with
 *    the band bottom. It rests against the bottom, where the bottom scroll clamp
 *    already puts it, and is the focused card there. A last card at least as tall
 *    as the band bottom-aligns at <= 0, which clamps to 0 (it simply top-aligns;
 *    never snap below the top).
 *  - HYBRID: when the last card and the second-to-last FIT in the band together,
 *    bottom-aligning the last card makes the second-to-last unreachable by scroll
 *    (the bottom clamp is hit before its top can climb to the chrome). In that
 *    case the caller passes [lastCardBottomAligns] = false and the last card
 *    TOP-aligns like the others (with a bottom spacer the caller adds so it can
 *    reach the top), so the whole fitting tail is scroll-steppable; the cost is a
 *    spacer below the last card instead of a flush rest. The flush bottom is kept
 *    only when the last card is tall / alone (the tail does not fit together),
 *    where there is no such conflict. [dynamicLastCardBottomAligns] is the shared
 *    decision; the caller passes its result here and to the focus math so the two
 *    never disagree about the last card's rest line.
 *  - A single-card deck: that card is both first and last; the `itemIndex <= 0`
 *    branch wins, so it top-aligns flush at 0 regardless of the flag.
 *
 * Pure (no Compose) so the snap/focus frame agreement is unit-tested directly.
 * Item indices below 0 are treated as item 0 (defensive).
 */
fun dynamicSnapStartPx(
    itemIndex: Int,
    itemSizePx: Int,
    bandHeightPx: Int,
    itemCount: Int,
    lastCardBottomAligns: Boolean = true,
): Int =
    when {
        // Single-card deck: first and last at once; the top-align branch wins so
        // a lone card sits flush under the chrome rather than bottom-aligning.
        itemIndex <= 0 -> 0
        // Last card bottom-aligns: its bottom edge flush with the band bottom.
        // Clamp so a band-taller card never snaps above the top. When the tail
        // fits together the caller flips the flag off and the last card top-aligns
        // (0) so the fitting tail stays scroll-steppable.
        itemIndex >= itemCount - 1 ->
            if (lastCardBottomAligns) (bandHeightPx - itemSizePx).coerceAtLeast(0) else 0
        // Every other card top-aligns flush under the chrome.
        else -> 0
    }

/**
 * Whether the deck's LAST card should BOTTOM-align (rest flush at the band
 * bottom) rather than top-align like the others, given the live-measured last and
 * second-to-last card heights, the band span and the inter-card gap.
 *
 * The single source of truth for the hybrid rule (see [dynamicSnapStartPx]). The
 * last card stays flush ONLY when the tail does NOT fit together: when
 * `lastHeight + gap + secondToLastHeight >= band`, the second-to-last card's top
 * reaches the chrome at a scroll position BEFORE the last-flush bottom clamp, so
 * both ends are scroll-focusable and the last card can rest flush with no cost.
 * When the tail DOES fit together (deficit > 0) bottom-aligning the last card
 * would strand the second-to-last (the bottom clamp blocks its rise to the
 * chrome), so the last card top-aligns instead and the caller pads the list so it
 * can reach the top, making the whole tail scroll-steppable.
 *
 *  - A 0/1-card deck: the lone card top-aligns via [dynamicSnapStartPx]'s
 *    `itemIndex <= 0` branch, so the flag is moot; return true (flush) as the
 *    inert default.
 *  - Heights not yet measured (null): keep the flush look (true) until the first
 *    layout reports them, then the fitting tail flips to top-align.
 *
 * Pure (no Compose) so the decision is unit-tested directly.
 */
fun dynamicLastCardBottomAligns(
    bandHeightPx: Int,
    lastCardHeightPx: Int?,
    secondToLastCardHeightPx: Int?,
    gapPx: Int,
    itemCount: Int,
): Boolean {
    if (itemCount <= 1) return true
    if (lastCardHeightPx == null || secondToLastCardHeightPx == null) return true
    val deficit = bandHeightPx - lastCardHeightPx - gapPx - secondToLastCardHeightPx
    return deficit <= 0
}

/**
 * The bottom content padding (px) the deck needs WHEN the last card top-aligns
 * (the fitting-tail branch of the hybrid, [dynamicLastCardBottomAligns] = false):
 * exactly `band - lastHeight`, the slack that lets the last card's top climb to
 * the chrome (below it is only this spacer, since nothing follows the last card).
 * That much padding is precisely enough for the last card to reach the top no
 * matter how many cards precede it: the last card's natural offset is
 * `totalContent - lastHeight`, and the max scroll with this pad is
 * `totalContent + pad - band = totalContent - lastHeight`, which meets it.
 *
 * 0 when the last card bottom-aligns (flush; it reaches its rest at the plain
 * bottom clamp, no pad needed) or before the last height is measured. Clamped
 * non-negative.
 *
 * Pure (no Compose) so it is unit-tested directly.
 */
fun dynamicLastCardTopPaddingPx(
    bandHeightPx: Int,
    lastCardHeightPx: Int?,
    lastCardBottomAligns: Boolean,
): Int =
    if (lastCardBottomAligns || lastCardHeightPx == null) 0
    else (bandHeightPx - lastCardHeightPx).coerceAtLeast(0)

/**
 * The value the deck's [androidx.compose.foundation.gestures.snapping.SnapPosition]
 * returns for item [itemIndex]: the item-start offset measured from the START OF
 * THE CONTENT AREA (after any content padding), which is the frame the snap
 * framework lands the item in (`beforeContentPaddingPx + this`).
 *
 * The snap line itself is computed in the FULL viewport ([layoutSizePx]) by
 * [dynamicSnapStartPx], exactly the frame the focused-index math uses (it reasons
 * in viewportEnd - viewportStart, which nets out to the full size). We then
 * subtract [beforeContentPaddingPx] to express that full-viewport line as the
 * content-relative offset the framework wants. Top-align uses NO content
 * padding, so in practice [beforeContentPaddingPx] is 0 and the provider offset
 * equals the snap line; the subtraction is kept so the frame-agreement invariant
 * (`beforeContentPadding + providerOffset == snapLine`) holds for any padding the
 * framework might still report.
 *
 * Pure (no Compose) so the snap/focus frame agreement is unit-tested directly:
 * if the provider and the focus math ever computed their snap lines in different
 * frames the deck would snap to one line and read focus off another.
 */
fun dynamicSnapProviderOffsetPx(
    itemIndex: Int,
    itemSizePx: Int,
    layoutSizePx: Int,
    beforeContentPaddingPx: Int,
    itemCount: Int,
    lastCardBottomAligns: Boolean = true,
): Int =
    dynamicSnapStartPx(itemIndex, itemSizePx, layoutSizePx, itemCount, lastCardBottomAligns) -
        beforeContentPaddingPx

/**
 * Which item of the dynamic deck is the FOCUSED one: the item whose CURRENT
 * start sits nearest its OWN snap line ([dynamicSnapStartPx]). The deck snaps
 * per item (cards 0..n-2 to the top, the last card to the bottom), so after a
 * snap settles this is exactly the snapped card; mid-list it is the card closest
 * to settling. Picking by distance-to-own-snap (rather than nearest-centre or
 * nearest-top) is what keeps focus agreeing with the new snap: when a non-last
 * card rests flush at the top IT is focused, and when the last card rests flush
 * at the bottom IT is. Ties break toward the EARLIER index so focus never jumps
 * ahead of the snap. Empty list (deck cleared mid-frame) falls back to 0,
 * matching the pager's realIndexOf guard.
 *
 * [itemCount] is passed through so the last card's snap line is computed as its
 * bottom-align line, the same line the provider lands it on.
 */
fun dynamicFocusedIndex(
    visible: List<DynamicVisibleItem>,
    bandHeightPx: Int,
    itemCount: Int,
    lastCardBottomAligns: Boolean = true,
): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    for (item in visible) {
        val snapStart =
            dynamicSnapStartPx(item.index, item.sizePx, bandHeightPx, itemCount, lastCardBottomAligns)
        val distance = kotlin.math.abs(item.offsetPx - snapStart)
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

/**
 * The settled focus the chrome counter / wheel routing should read, given the
 * raw scroll-derived focus ([scrollFocus], picked by [dynamicFocusedIndex] from
 * where the cards actually rest) and an optional [stickyTarget] the user
 * EXPLICITLY selected (a wheel step, a pip jump or a title tap that fired a
 * programmatic scroll).
 *
 * WHY this exists: the deck bottom-aligns the last card, so when the last two
 * (or last few) cards fit together within the band, the bottom scroll clamp is
 * reached before the second-to-last card's top can rise to the chrome. That card
 * can never sit on its top-snap line, so [dynamicFocusedIndex] (distance to a
 * card's OWN line) always hands focus to the bottom-aligned last card (distance
 * 0) and the second-to-last is unreachable, unselectable, and SKIPPED by the pip
 * (4/6 jumps straight to 6/6). When the user explicitly targets such a stuck
 * card the scroll honestly cannot bring it onto its line, so the scroll-derived
 * focus would immediately override the selection back to the last card.
 *
 * The rule: a live [stickyTarget] wins. Once the user explicitly selects a card,
 * that index stays focused regardless of where the scroll could settle, so the
 * stuck second-to-last is selectable and the wheel can step onto it. The caller
 * clears [stickyTarget] the moment the user scrolls BY HAND to a card that
 * settles cleanly (a non-programmatic rest), at which point the scroll-derived
 * focus takes over again. A null [stickyTarget] (no explicit selection pending)
 * is the common case and just passes the scroll focus through unchanged.
 *
 * Pure (no Compose) so the override decision is unit-tested without a
 * composition. The sticky target is clamped defensively to the live deck.
 */
fun resolveSettledFocus(scrollFocus: Int, stickyTarget: Int?, itemCount: Int): Int =
    when {
        itemCount <= 0 -> 0
        stickyTarget != null -> stickyTarget.coerceIn(0, itemCount - 1)
        else -> scrollFocus.coerceIn(0, itemCount - 1)
    }
