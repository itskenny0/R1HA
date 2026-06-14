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
 * picked by the distance from its own SNAP LINE, matching the per-item snap.
 */
data class DynamicVisibleItem(val index: Int, val offsetPx: Int, val sizePx: Int)

/**
 * Where item [itemIndex] of the dynamic deck WANTS its start to sit, measured
 * from the band top, given the band span ([bandHeightPx]) and the item's
 * measured height ([itemSizePx]). This is the per-item snap rule, the single
 * source of truth shared by the custom snap provider (which feeds it through a
 * [androidx.compose.foundation.gestures.snapping.SnapPosition]) and the
 * focused-index math, so the two never disagree about a card's rest line.
 *
 *  - Item 0 anchors FLUSH at the band TOP (offset 0). The user wants the deck
 *    to open with the first card pinned under the chrome, not floated to the
 *    centre with a gap above it. With top content-padding of 0 this is also the
 *    list's natural clamp for item 0, so it is a clean, achievable rest.
 *  - Items 1..n CENTRE in the band: start at (band - itemHeight) / 2, the same
 *    arithmetic as [SnapPosition.Center]. Earlier cards scrolling off the top
 *    is fine (a LazyColumn allows it), so these never need top padding to
 *    centre.
 *
 * REGRESSION FIX: a uniform [SnapPosition.Center] with zero top padding left
 * item 1 an unreachable rest. Centre-snapping item 0 needs free space ABOVE it,
 * but the zero top pad clamps item 0's start to 0; near that clamp item 0 and
 * item 1 collapse onto overlapping snap offsets, so the fling skips past item 1
 * and never settles on it. Giving item 0 its own TOP-aligned snap line (the
 * offset it is already clamped to) frees items 1..n to centre as DISTINCT rest
 * positions, so every card, the second one especially, is a stable target.
 *
 * A band-filling (or taller) card centres at <= 0, which the caller may clamp;
 * here we return the raw centre so the provider/focus maths see one consistent
 * value. Item indices below 0 are treated as item 0 (defensive).
 */
fun dynamicSnapStartPx(itemIndex: Int, itemSizePx: Int, bandHeightPx: Int): Int =
    if (itemIndex <= 0) 0 else (bandHeightPx - itemSizePx) / 2

/**
 * The value the deck's [androidx.compose.foundation.gestures.snapping.SnapPosition]
 * returns for item [itemIndex]: the item-start offset measured from the START OF
 * THE CONTENT AREA (after the top content padding), which is the frame the snap
 * framework lands the item in (`beforeContentPaddingPx + this`).
 *
 * The snap line itself is computed in the FULL viewport ([layoutSizePx]) by
 * [dynamicSnapStartPx], exactly the frame the focused-index math uses (it reasons
 * in viewportEnd - viewportStart, which nets out to the full size). We then
 * subtract [beforeContentPaddingPx] to express that full-viewport line as the
 * content-relative offset the framework wants. Item 0's full-band line is 0 (the
 * true top), so it returns `-beforeContentPaddingPx`: the item lands flush at the
 * chrome instead of floated a whole top-pad below it. Items 1..n land on the full
 * band centre, the same line the focus math picks them out by.
 *
 * Pure (no Compose) so the snap/focus frame agreement is unit-tested directly:
 * the regression was the provider centring in the PADDED band while the focus
 * math used the full one, so the two disagreed and the deck snapped erratically.
 */
fun dynamicSnapProviderOffsetPx(
    itemIndex: Int,
    itemSizePx: Int,
    layoutSizePx: Int,
    beforeContentPaddingPx: Int,
): Int = dynamicSnapStartPx(itemIndex, itemSizePx, layoutSizePx) - beforeContentPaddingPx

/**
 * The largest main-axis START offset (px, in the band frame) the SECOND deck
 * item (index 1) can ever occupy. Item 1 sits directly under item 0, so its
 * lowest-on-screen position is reached at the list's top scroll clamp: item 0
 * resting at the bottom of the top content padding ([topPaddingPx] below the
 * band top) with item 1 one card-plus-gap below it. Scrolling only moves the
 * stack UP from there, shrinking item 1's offset; it can never sit lower. So
 * this is item 1's ceiling, and its CENTRE snap line is physically reachable
 * only when that line is at or above this ceiling (see
 * [dynamicSecondItemCentreReachable]).
 *
 * The top padding is the load-bearing term: without it ([topPaddingPx] == 0)
 * item 1's ceiling is just `firstItemHeightPx + gap` below the band top, which
 * for a short first card is far above the band centre, so item 1's centre line
 * is out of reach and a fling can never settle focus on it. This is the exact
 * arithmetic behind "the second card is unfocusable unless it (or the first) is
 * tall": a tall card's centre line rides up to where the ceiling already is.
 */
fun dynamicSecondItemMaxStartPx(
    topPaddingPx: Int,
    firstItemHeightPx: Int,
    interCardGapPx: Int,
): Int = topPaddingPx + firstItemHeightPx + interCardGapPx

/**
 * The MINIMAL top content padding (px) that lets the SECOND card reach its
 * CENTRE snap line, and no more.
 *
 * The top pad exists for exactly one reason: item 0 is pinned flush at the band
 * top with zero slack above it, so without padding item 1's highest reachable
 * start ([dynamicSecondItemMaxStartPx] at `topPad == 0`) is just
 * `firstHeight + gap` below the band top. For a short first card that ceiling
 * sits well above item 1's centre line, so a fling can never settle focus on
 * the second card (the real bug). The pad is the slack the stack scrolls DOWN
 * into when card 2 takes focus.
 *
 * We give it the SMALLEST pad that makes the ceiling reach the centre line, not
 * the symmetric `(band - firstHeight) / 2` mirror of the bottom pad. The mirror
 * is far more slack than needed: all of it is user-scrollable empty space ABOVE
 * card 0, so the user can drag card 0 down into the middle and it springs back,
 * an awkward overscroll. Solving `centre1 <= P + firstHeight + gap` for the
 * least P:
 *
 *   centre1 = (band - secondHeight) / 2                 (item 1's centre line)
 *   P_min   = max(0, centre1 - firstHeight - gap)
 *           = max(0, (band - secondHeight) / 2 - firstHeight - gap)
 *
 * Worked from real logs (band 2043, card0 542, card1 536, gap 30): the mirror
 * pad is (2043 - 542)/2 = 750; this minimal pad is (2043 - 536)/2 - 542 - 30 =
 * 753 - 572 = 181. So ~181 px of slack instead of 750, dramatically less
 * overscroll, and the second card still centres exactly (the inequality becomes
 * an equality at P_min, proven in [dynamicSecondItemCentreReachable]).
 *
 *  - [firstCardHeightPx] / [secondCardHeightPx] null = that card has not been
 *    measured yet (never composed, or the deck's head just changed). We return 0
 *    until BOTH are known: with no measured heights there is nothing to centre
 *    toward, and a speculative pad would just float card 0 down for nothing. The
 *    caller drops a cached height whenever the head item changes identity, the
 *    same discipline the tail pad uses, so a stale height never leaks a gap.
 *  - A second card at least as tall as the band centres at <= 0, already at or
 *    below the ceiling, so P_min is 0: a band-filling card never needs help.
 *  - Clamped into `[0, band]` defensively, mirroring [dynamicCenterPaddingPx].
 */
fun dynamicMinTopPaddingPx(
    bandHeightPx: Int,
    firstCardHeightPx: Int?,
    secondCardHeightPx: Int?,
    gapPx: Int,
): Int {
    if (firstCardHeightPx == null || secondCardHeightPx == null) return 0
    val centre1 = (bandHeightPx - secondCardHeightPx) / 2
    val minPad = centre1 - firstCardHeightPx - gapPx
    return minPad.coerceIn(0, bandHeightPx.coerceAtLeast(0))
}

/**
 * The CONSISTENCY FLOOR for the top pad: a fixed fraction of the band that every
 * multi-card stack gets as top slack regardless of its content.
 *
 * [dynamicMinTopPaddingPx] alone is content-dependent: it is positive only when
 * the first two cards are short enough that the second card needs room to reach
 * centre, and 0 otherwise (a tall first card needs no slack). On device that
 * read as arbitrary, some stacks had a little top give and some were rock-solid
 * at the top with no obvious reason (it was the first two cards' heights). User
 * call: give the stacks that do NOT need slack the same give as the ones that
 * do, so the deck feels uniform. This floor is that baseline.
 *
 * Sized as `band / 10` (~10% of the visible band): close to the ~9% the worked
 * short-card example needed, so most stacks land exactly on the floor and feel
 * identical, and well under the ~37% the old `(band - firstHeight)/2` mirror
 * left (the overscroll that felt awkward). A fraction (not a fixed dp) so the
 * give scales with the screen.
 */
fun dynamicTopPaddingFloorPx(bandHeightPx: Int): Int =
    (bandHeightPx / 10).coerceAtLeast(0)

/**
 * The top content padding the deck actually applies: the larger of the
 * content-driven minimum ([dynamicMinTopPaddingPx], what the second card needs
 * to centre) and the consistency [dynamicTopPaddingFloorPx] (so every multi-card
 * stack has at least the same baseline give).
 *
 * Taking the MAX means the floor never starves the second card: a short-card
 * stack whose need exceeds the floor still gets its full need (and still centres
 * exactly), while a stack that needs nothing still gets the floor so it does not
 * sit at the top with zero give while its neighbours have some. Returns the floor
 * even before the head heights are measured (the need term is then 0), so the
 * baseline give is present from the first frame; the caller's reveal gate still
 * waits for both heights so a short-card stack whose final pad exceeds the floor
 * does not flash. Clamped into `[0, band]`.
 */
fun dynamicTopPaddingPx(
    bandHeightPx: Int,
    firstCardHeightPx: Int?,
    secondCardHeightPx: Int?,
    gapPx: Int,
): Int {
    val needed = dynamicMinTopPaddingPx(bandHeightPx, firstCardHeightPx, secondCardHeightPx, gapPx)
    val floor = dynamicTopPaddingFloorPx(bandHeightPx)
    return maxOf(needed, floor).coerceIn(0, bandHeightPx.coerceAtLeast(0))
}

/**
 * Whether the SECOND card's CENTRE snap line is physically reachable: its centre
 * target ([dynamicSnapStartPx] for index 1) must sit at or below item 1's
 * ceiling ([dynamicSecondItemMaxStartPx]). When false the deck cannot focus the
 * second card at all (the regression the user hit); the cure is enough top
 * content padding to raise the ceiling to (or past) the centre line.
 *
 * The MINIMAL pad [dynamicMinTopPaddingPx] satisfies this as an EQUALITY: when
 * `P_min = centre1 - firstHeight - gap` is positive, the ceiling lands exactly
 * on the centre line; when it clamps to 0 the second card was already reachable
 * with no padding, so the inequality holds either way. (The old symmetric pad
 * `(band - firstHeight) / 2` over-satisfied it by leaving the second card high
 * above the ceiling, which is exactly the overscroll slack we trimmed.)
 */
fun dynamicSecondItemCentreReachable(
    bandHeightPx: Int,
    firstItemHeightPx: Int,
    secondItemHeightPx: Int,
    interCardGapPx: Int,
    topPaddingPx: Int,
): Boolean =
    dynamicSnapStartPx(1, secondItemHeightPx, bandHeightPx) <=
        dynamicSecondItemMaxStartPx(topPaddingPx, firstItemHeightPx, interCardGapPx)

/**
 * Which item of the dynamic deck is the FOCUSED one: the item whose CURRENT
 * start sits nearest its OWN snap line ([dynamicSnapStartPx]). The deck snaps
 * per item (item 0 to the top, the rest to the centre), so after a snap settles
 * this is exactly the snapped card; mid-list it is the card closest to settling.
 * Picking by distance-to-own-snap (rather than nearest-centre) is what keeps
 * focus agreeing with the new snap: when item 0 rests flush at the top it is
 * focused even though its CENTRE is far above the band centre, and when item 1
 * is centred IT is focused. Ties break toward the EARLIER index so focus never
 * jumps ahead of the snap. Empty list (deck cleared mid-frame) falls back to 0,
 * matching the pager's realIndexOf guard.
 */
fun dynamicFocusedIndex(visible: List<DynamicVisibleItem>, bandHeightPx: Int): Int {
    var bestIndex = 0
    var bestDistance = Int.MAX_VALUE
    for (item in visible) {
        val snapStart = dynamicSnapStartPx(item.index, item.sizePx, bandHeightPx)
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
