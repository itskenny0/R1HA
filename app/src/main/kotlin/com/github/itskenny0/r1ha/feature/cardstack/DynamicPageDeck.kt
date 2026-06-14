package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.ui.components.rememberR1Haptic
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.EntityCard
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The DYNAMIC deck layout: the same mixed deck PageDeck renders, but as a
 * snapping [LazyColumn] whose items are sized per kind instead of a uniform
 * full-viewport pager.
 *
 *  - ENTITY items wrap to their content height (EntityCard's fillSlot = false
 *    path: natural heights everywhere, the value bar at the concrete
 *    [DYNAMIC_VALUE_BAR_HEIGHT_DP] band) so every control is visible: a
 *    climate card keeps its mode buttons and temperature scale, a light its
 *    scene/effect rows. Capped at the viewport with internal scroll past
 *    that, mirroring the Lovelace items. Only FULLSCREEN keeps the
 *    full-viewport wheel surface; the wheel still drives whichever card is
 *    snapped into focus.
 *  - LOVELACE items hug their content height, capped at the viewport with
 *    internal scroll past that (the [DeckCardSurface] measure contract), so a
 *    one-line toggle takes one line instead of marooning itself in a screen
 *    of black.
 *
 * Cards remain discrete snap targets: a snapping fling
 * ([SnapLayoutInfoProvider] over the list state, the PER-ITEM
 * [dynamicSnapPosition]) settles every gesture on a card's snap line. Item 0
 * rests FLUSH at the band top (the deck opens with the first card pinned under
 * the chrome); cards 1..n rest CENTRED in the band with their neighbours
 * peeking, matching the fullscreen peek deck. The focused card (nearest its own
 * snap line, see [dynamicFocusedIndex]) is the one the WHEEL and hardware keys
 * drive.
 *
 * Unlike the fullscreen peek deck, every dynamic card is fully laid out and
 * directly hittable: a tap goes straight to the tapped card's own content and
 * fires it (button activate, script run, switch toggle, light tap), regardless
 * of which card the wheel is centred on. There is no tap-to-snap scrim or
 * dimming over non-focused cards; focus is a WHEEL-routing concept only and
 * never gates tap actuation. (The fullscreen peek deck keeps tap-to-navigate,
 * correct there since its neighbours are off-screen peeks, not laid-out cards.)
 *
 * Deliberately NOT supported here (FULLSCREEN keeps both):
 *  - Infinite scroll: see [dynamicSnapTarget] for why a per-item-height lazy
 *    list has no honest analogue of the pager's virtual-page wrap. The deck
 *    is finite in this layout regardless of [AppSettings].ui.infiniteScroll.
 *  - The peek-deck presentation: superseded, the dynamic list already shows
 *    neighbouring cards at their real heights.
 */
/**
 * The dynamic deck's PER-ITEM snap rule, as a [SnapPosition]. The snap
 * machinery calls [position] for each candidate item with the viewport size and
 * the item's measured size; we delegate to the pure [dynamicSnapStartPx] so the
 * snap line and the focused-index math are computed by one shared function.
 *
 * FRAME: the snap line is computed in the FULL viewport ([layoutSize]) so it
 * matches the focused-index math, which reasons in the full span
 * (viewportEnd - viewportStart). Item 0's line is the true top (0), items 1..n
 * the full-band centre ((layoutSize - itemSize) / 2). [position]'s return value,
 * though, is the item-start offset measured from the START OF THE CONTENT AREA
 * (i.e. after [beforeContentPadding]) -- the framework lands the item at
 * `beforeContentPadding + returned`. So we subtract [beforeContentPadding] to
 * convert the full-viewport snap line into that content-relative offset; a line
 * of 0 (item 0's top) becomes `-beforeContentPadding`, which lands item 0 flush
 * at the true top rather than floated a whole top-pad below the chrome.
 *
 * REGRESSION FIX: the previous revision computed the centre inside the PADDED
 * band (`layoutSize - before - after`) and returned it un-shifted. With the top
 * centring pad that band collapsed below the card height (so every centre line
 * degenerated toward the top) and item 0 snapped to `beforeContentPadding`
 * (floated), while the focus math kept using the full span -- the two frames
 * disagreed and the deck snapped inconsistently. Centring in the full viewport
 * and shifting by the pad makes the snap line and the focus line identical.
 *
 * Object (not a lambda) so it is a stable singleton across recompositions; it
 * holds no state, only the index-keyed rule.
 */
private val dynamicSnapPosition = object : SnapPosition {
    override fun position(
        layoutSize: Int,
        itemSize: Int,
        beforeContentPadding: Int,
        afterContentPadding: Int,
        itemIndex: Int,
        itemCount: Int,
    ): Int {
        return dynamicSnapProviderOffsetPx(
            itemIndex = itemIndex,
            itemSizePx = itemSize,
            layoutSizePx = layoutSize,
            beforeContentPaddingPx = beforeContentPadding,
        )
    }
}

/**
 * Put the [index] item on its SNAP line: item 0 FLUSH at the chrome, every
 * other item at the band CENTRE, matching where a fling would have settled under
 * [dynamicSnapPosition]. [animated] picks the smooth scroll (nav / jump targets)
 * or an instant one (initial placement, so a tab switch lands already-positioned
 * instead of sliding).
 *
 * [LazyListState.scrollToItem] aligns an item's START with the start of the
 * CONTENT area (after the top padding), so:
 *  - item 0: the start-aligned scroll leaves it floated a top-pad below the
 *    chrome, so we scroll on by [LazyListLayoutInfo.beforeContentPadding] to pull
 *    it flush. (The old version skipped this and left item 0 floated on a
 *    jump-to-first.)
 *  - items 1..n: recentre by the half-difference between the FULL viewport span
 *    and the item's real height. The height is only known once the item is
 *    on-screen, which is why this is a two-step scroll; that is what lets a FAR
 *    jump work. A band-filling card is already its own centre (clamp <= 0).
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToItemCentered(
    index: Int,
    animated: Boolean = true,
) {
    suspend fun go(i: Int, offset: Int = 0) =
        if (animated) animateScrollToItem(i, offset) else scrollToItem(i, offset)
    // Bring the item on-screen with its start at the content-area top.
    go(index)
    if (index <= 0) {
        // Consume the top centring pad so item 0 rests flush under the chrome.
        val before = layoutInfo.beforeContentPadding
        if (before > 0) go(0, before)
        return
    }
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val bandSpan = info.viewportEndOffset - info.viewportStartOffset
    // Negative scroll-offset shifts the item DOWN into the centre; clamp so a
    // band-filling card is not pushed past centre.
    val centerOffset = -((bandSpan - item.size) / 2).coerceAtLeast(0)
    if (centerOffset != 0) go(index, centerOffset)
}

@Composable
internal fun DynamicPageDeck(
    pageId: String,
    /** The page's mixed deck: entity cards and pinned Lovelace cards sharing
     *  one index space, in the user's interleaved order. */
    items: List<DeckItem>,
    initialIndex: Int,
    isActive: Boolean,
    vm: CardStackViewModel,
    appSettings: AppSettings,
    navRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    lightWheelModes: Map<com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode>,
    lovelaceStates: com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates,
    lovelaceHooks: LovelaceDeckHooks,
    /** Reports list scroll-in-progress up to the screen-level wheel gate,
     *  exactly like PageDeck's pager-animating report: while true the wheel
     *  handler drops events so a spin mid-fling can't land on the card the
     *  user just left. */
    onActivePagerAnimatingChange: (Boolean) -> Unit,
) {
    val cards = items
    // Re-keyed on the page only: unlike the pager (whose pageCount lambda
    // captures the list size), a LazyColumn re-reads `cards` on every
    // composition, so a deck mutation doesn't need a state rebuild and the
    // user's scroll position survives it (items are keyed below).
    val listState = key(pageId) {
        rememberLazyListState(
            initialFirstVisibleItemIndex = initialIndex
                .coerceIn(0, (cards.size - 1).coerceAtLeast(0)),
        )
    }
    // The settled focused index: which card is THE card (wheel target, chrome
    // counter, actuation rights). Updated only when the list is at rest so a
    // card never counts as focused mid-fling; mirrors pagerState.settledPage.
    val settledFocus = remember(pageId) { mutableIntStateOf(initialIndex.coerceAtLeast(0)) }
    LaunchedEffect(listState, pageId, isActive, cards.size) {
        snapshotFlow {
            // null while scrolling = "no settle yet"; the focused index is
            // only sampled at rest, when the snap has put some card's start
            // on (or nearest to) the snap line.
            if (listState.isScrollInProgress) {
                null
            } else {
                val info = listState.layoutInfo
                // Centre-snap: the band centre is the midpoint of the snap
                // viewport (viewportStart/End already net out the symmetric
                // content padding). Item offsets share that coordinate space,
                // so feeding the span as the band height lets the helper pick
                // the card whose centre is nearest the band centre.
                val bandSpan = info.viewportEndOffset - info.viewportStartOffset
                dynamicFocusedIndex(
                    info.visibleItemsInfo.map {
                        DynamicVisibleItem(
                            index = it.index,
                            // Offset is absolute in viewport coords; subtract
                            // the viewport start so item centre and band centre
                            // share a 0-based origin.
                            offsetPx = it.offset - info.viewportStartOffset,
                            sizePx = it.size,
                        )
                    },
                    bandHeightPx = bandSpan,
                )
            }
        }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { idx ->
                settledFocus.intValue = idx
                // Active page writes through setCurrentIndex (feeds activeState,
                // the wheel routing and the chrome counter); inactive pages
                // persist their position via setIndexForPage, same split as
                // PageDeck's settled-page collector.
                if (isActive) vm.setCurrentIndex(idx) else vm.setIndexForPage(pageId, idx)
            }
    }
    // Scroll-in-progress stream for the wheel gate; reset to false when this
    // deck stops being the active one so a stale `true` can't lock the wheel
    // out after a tab switch (see PageDeck's identical collector).
    LaunchedEffect(listState, isActive) {
        if (!isActive) {
            onActivePagerAnimatingChange(false)
            return@LaunchedEffect
        }
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { onActivePagerAnimatingChange(it) }
    }
    // Snap-LOCK haptic: one crisp tick when a card SETTLES onto its snap line,
    // fired on the scroll's rest transition (scrolling -> stopped), never per
    // frame. distinctUntilChanged collapses the stream to true/false edges, so
    // a whole fling-then-snap settle is exactly one false edge = one tick; the
    // first emission (the deck's initial at-rest state on open) is skipped so
    // opening a page doesn't fire a phantom lock. Routed through R1Haptic.lock
    // (a notch stronger than the wheel-detent tick) so the magnet reads as a
    // decisive click, and through the Vibrator path so vendor ROMs that mute
    // performHapticFeedback still feel it. Honours the user's haptics toggle.
    val lockView = LocalView.current
    val lockHaptic = rememberR1Haptic()
    val hapticsEnabled = appSettings.behavior.haptics
    LaunchedEffect(listState, isActive, hapticsEnabled) {
        if (!isActive || !hapticsEnabled) return@LaunchedEffect
        var firstSettle = true
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (!scrolling) {
                    if (!firstSettle) {
                        // A card just locked onto its snap line.
                        runCatching { lockHaptic.lock(lockView) }
                    }
                    firstSettle = false
                }
            }
    }
    // Hardware-key card steps (CARD_UP / CARD_DOWN push signed deltas into the
    // shared navRequests flow). Clamped to the finite deck; runCatching keeps
    // a scroll cancelled by a user touch from killing the collector for the
    // rest of the session.
    LaunchedEffect(listState, navRequests, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        navRequests.collect { delta ->
            if (cards.isEmpty() || delta == 0) return@collect
            val target = dynamicSnapTarget(settledFocus.intValue + delta, cards.size)
            if (target != settledFocus.intValue) {
                runCatching { listState.scrollToItemCentered(target) }
            }
        }
    }
    // Jump-to-card / widget deep-link targets. Centre the target so a
    // programmatic jump lands exactly where a centre-snap fling would have
    // settled (focused card in the middle of the band).
    LaunchedEffect(listState, jumpRequests, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        jumpRequests.collect { targetIdx ->
            if (cards.isEmpty()) return@collect
            runCatching {
                listState.scrollToItemCentered(dynamicSnapTarget(targetIdx, cards.size))
            }
        }
    }

    // Chrome / nav clearance as a MODIFIER inset (not contentPadding), the
    // same choice the peek deck made: the inset band is then exactly the
    // snap viewport, so item offset 0 = flush under the chrome and the
    // snap maths never see the padding.
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = statusBarTop + 80.dp, bottom = navBarBottom + 16.dp),
    ) {
        // The band height: what a full-viewport (entity) item measures, and
        // the cap a tall Lovelace card scrolls internally past.
        val bandHeight = maxHeight
        val bandHeightPx = constraints.maxHeight
        // Centring padding for the BOTTOM end only. The FIRST card deliberately
        // sits FLUSH at the band top (no top padding): the user wants the deck
        // to open with card 1 anchored at the top, not floated to centre with a
        // gap above it. With centre-snapping the list would otherwise clamp the
        // LAST card flush to the band bottom, so it could never reach the band
        // centre (nor become the focused wheel target); bottom padding of
        // (band - last item height)/2 raises the scroll range exactly enough
        // for the last card to centre (see [dynamicCenterPaddingPx]). The
        // height is read from the live layout info, so until the last card has
        // been composed once its padding is 0 (conservative; it appears as the
        // user approaches the end).
        //
        // The measurement is keyed on the last item's identity, not just the
        // page: a deck mutation (conditional card hidden / shown, card
        // removed) swaps which card is last, and carrying the height measured
        // for the PREVIOUS last card left a stale gap behind.
        val lastItemKey = cards.lastOrNull()?.key
        // -1 = not measured yet; the padding helper treats it as "no padding"
        // until the real last card reports a size.
        val lastItemHeightPx = remember(pageId, lastItemKey, cards.size) {
            mutableIntStateOf(-1)
        }
        LaunchedEffect(listState, lastItemKey, cards.size) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .lastOrNull()
                    ?.takeIf { it.index == cards.lastIndex }
                    ?.size
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { lastItemHeightPx.intValue = it }
        }
        // TOP padding: the MINIMAL slack above card 0 that lets the SECOND card
        // reach its centre snap line. Without it item 1's highest reachable start
        // sits above its centre line (item 0 is pinned flush at the top with zero
        // slack above it), so a short second card can never be focused: the "I can
        // never focus the second card unless it's huge" bug. We size the pad to
        // exactly P_min = (band - secondHeight)/2 - firstHeight - gap (see
        // dynamicMinTopPaddingPx), NOT the old (band - firstHeight)/2 mirror of
        // the bottom pad: the mirror left far more scrollable empty space above
        // card 0 than needed, so the user could drag card 0 down into the middle
        // and it sprang back (the awkward overscroll). At P_min the ceiling lands
        // exactly on the second card's centre line, so it still centres, with the
        // least possible slack. Item 0 still SNAPS to the top line (0), so once
        // settled it rests flush under the chrome.
        //
        // P_min needs BOTH the first and the second card's measured heights, so we
        // track the second card's height too. Each measurement is keyed on its
        // item's identity (head item key for the first, second item's key for the
        // second), so a head mutation drops a height measured for a different card,
        // exactly as the tail pad does, and a stale height never leaks a gap.
        val firstItemKey = cards.firstOrNull()?.key
        val firstItemHeightPx = remember(pageId, firstItemKey, cards.size) {
            mutableIntStateOf(-1)
        }
        LaunchedEffect(listState, firstItemKey, cards.size) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull()
                    ?.takeIf { it.index == 0 }
                    ?.size
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { firstItemHeightPx.intValue = it }
        }
        // The SECOND card's height, the other term P_min needs. Keyed on the
        // second item's identity (cards[1]) so a head/second mutation drops a
        // height measured for a different card. -1 until measured; the pad helper
        // treats a null (the <0 -> null mapping below) as "no padding yet".
        val secondItemKey = cards.getOrNull(1)?.key
        val secondItemHeightPx = remember(pageId, secondItemKey, cards.size) {
            mutableIntStateOf(-1)
        }
        LaunchedEffect(listState, secondItemKey, cards.size) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == 1 }
                    ?.size
            }
                .filterNotNull()
                .distinctUntilChanged()
                .collect { secondItemHeightPx.intValue = it }
        }
        // The inter-card gap (Arrangement.spacedBy below) is part of item 1's
        // ceiling, so it enters P_min. Resolved once from the same R1.space.m the
        // LazyColumn uses, in px, so the pure pad math sees the real spacing.
        val interCardGapPx = with(LocalDensity.current) { R1.space.m.roundToPx() }
        // Only a multi-card deck needs the top slack: a lone card has no second
        // card to reach, so padding would just float it down for nothing. A
        // one-card page stays flush at the top, and the pad is 0 until BOTH the
        // first and second cards have reported a height.
        val centerPadTopPx = if (cards.size >= 2) {
            dynamicMinTopPaddingPx(
                bandHeightPx = bandHeightPx,
                firstCardHeightPx = firstItemHeightPx.intValue.takeIf { it >= 0 },
                secondCardHeightPx = secondItemHeightPx.intValue.takeIf { it >= 0 },
                gapPx = interCardGapPx,
            )
        } else {
            0
        }
        val centerPadTop = with(LocalDensity.current) { centerPadTopPx.toDp() }
        // NOTE: there is deliberately NO "self-healing flush" effect that pins
        // card 0 to the top whenever the list rests at the raw top clamp (offset
        // 0). With the MINIMAL top pad, the raw top clamp IS the second card's
        // centred rest (card 0 floated by exactly the pad, card 1 on its centre
        // line), so a flush firing there would scroll the user straight back off
        // the second card every time they tried to focus it -- the second card
        // became unfocusable. Card 0 is instead pinned flush by the pre-paint
        // placement (on open / revisit) and by the fling snap (provider target
        // for item 0 is the true top) on scroll-back, neither of which fights the
        // second-card-centred rest.
        // Pre-paint placement: keep the deck HIDDEN until the focused card has
        // been put on its snap line, so a tab switch (which rebuilds this list at
        // the floated raw-top) never paints that floated state and then corrects
        // -- the correction is what read as "cards weirdly moving about". The list
        // still lays out while hidden (alpha only), so the card measures; we then
        // land the focused card instantly and reveal with a short fade. A timeout
        // guards the reveal so the deck can never stay stuck invisible if a
        // measurement never reports.
        val placed = remember(pageId) { mutableStateOf(false) }
        val deckAlpha = animateFloatAsState(
            targetValue = if (placed.value) 1f else 0f,
            animationSpec = tween(durationMillis = 110),
            label = "deckPlace",
        ).value
        // Keyed on cards becoming non-empty (not on every size change) so it runs
        // once per page load and does not re-hide / re-jump on a deck mutation.
        LaunchedEffect(listState, pageId, cards.isNotEmpty()) {
            if (cards.isEmpty()) return@LaunchedEffect // stay hidden; nothing to place
            val focus = initialIndex.coerceIn(0, cards.size - 1)
            // Reveal only once (a) the focused card is on-screen, so its height
            // and centre line are known, AND (b) the top centring pad is fully
            // APPLIED in the layout. Gating on the applied pad is what kills the
            // "renders in the middle then snaps to top" flash: the pad depends on
            // the first card's measured height, so it lands a frame or two after
            // composition; if we placed + revealed before it applied, the pad
            // would then shove the card down post-reveal and the flush would yank
            // it back visibly. Waiting for `beforeContentPadding` to reach the
            // intended pad means the whole float-then-flush happens while hidden.
            withTimeoutOrNull(400) {
                snapshotFlow {
                    val info = listState.layoutInfo
                    val focusVisible = info.visibleItemsInfo.any { it.index == focus }
                    val firstH = firstItemHeightPx.intValue
                    val secondH = secondItemHeightPx.intValue
                    // The pad needs both head heights, so the reveal waits for
                    // both: gating on a half-measured pad would reveal early and
                    // let the pad shove the card post-reveal (the flash). A
                    // single-card deck has no pad and is "measured" immediately.
                    val measured = cards.size < 2 || (firstH >= 0 && secondH >= 0)
                    val intendedPad =
                        if (cards.size >= 2) {
                            dynamicMinTopPaddingPx(
                                bandHeightPx = bandHeightPx,
                                firstCardHeightPx = firstH.takeIf { it >= 0 },
                                secondCardHeightPx = secondH.takeIf { it >= 0 },
                                gapPx = interCardGapPx,
                            )
                        } else {
                            0
                        }
                    // Tolerance absorbs the px -> dp -> px round-trip the content
                    // padding makes (it can land a pixel under the intended pad,
                    // which would otherwise stall the reveal until the timeout).
                    val padApplied = measured && info.beforeContentPadding >= intendedPad - 3
                    focusVisible && padApplied
                }
                    .filter { it }
                    .first()
            }
            runCatching { listState.scrollToItemCentered(focus, animated = false) }
            placed.value = true
        }
        val centerPadBottom = with(LocalDensity.current) {
            dynamicCenterPaddingPx(
                bandHeightPx = bandHeightPx,
                endItemHeightPx = lastItemHeightPx.intValue.takeIf { it >= 0 },
            ).toDp()
        }
        // DIAGNOSTIC, gated behind the Dev-menu "Deck snap diagnostics" toggle
        // (default OFF). When on, on every settle it ships a full snapshot of the
        // snap geometry so the on-device snapping can be read from the log
        // receiver instead of guessed at: the ACTUAL content paddings Compose
        // applied (before/after) versus what we intended, the band span and
        // viewport frame, and every visible card's normalised offset against its
        // computed snap line (d = how far it rested OFF its line: a large d on the
        // focused card is a between-cards rest), plus the focus the deck chose.
        // R1Log.i survives release builds and ships. Keyed on the toggle so
        // flipping it off tears the collector down (no snapshotFlow runs at all).
        val deckSnapDiag = appSettings.logShipping.deckSnapDiagnostics
        LaunchedEffect(listState, pageId, isActive, deckSnapDiag) {
            if (!isActive || !deckSnapDiag) return@LaunchedEffect
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (scrolling) return@collect
                    val info = listState.layoutInfo
                    val span = info.viewportEndOffset - info.viewportStartOffset
                    val vis = info.visibleItemsInfo.joinToString(" | ") { it ->
                        val norm = it.offset - info.viewportStartOffset
                        val snap = dynamicSnapStartPx(it.index, it.size, span)
                        "#${it.index} off=${it.offset} norm=$norm sz=${it.size} snap=$snap d=${norm - snap}"
                    }
                    val focus = dynamicFocusedIndex(
                        info.visibleItemsInfo.map {
                            DynamicVisibleItem(it.index, it.offset - info.viewportStartOffset, it.size)
                        },
                        span,
                    )
                    com.github.itskenny0.r1ha.core.util.R1Log.i(
                        "DeckSnap",
                        "settle page=$pageId layoutH=$bandHeightPx span=$span " +
                            "before=${info.beforeContentPadding} after=${info.afterContentPadding} " +
                            "padTopWant=$centerPadTopPx vpStart=${info.viewportStartOffset} " +
                            "vpEnd=${info.viewportEndOffset} spacing=${info.mainAxisItemSpacing} " +
                            "focus=$focus settledFocus=${settledFocus.intValue} items=[$vis]",
                    )
                }
        }
        // Snapping fling: decay the gesture's velocity (so a hard flick still
        // carries through several cards, same physics family as the pager),
        // then snap the nearest card's start onto its line with a CRISP,
        // critically-damped spring: no bounce, never rests between cards. The
        // decay honours the user's scroll-sensitivity dial through the identical
        // friction mapping.
        val sensitivity = appSettings.ui.cardScrollSensitivity.coerceIn(1, 100)
        val flingFriction = (0.8f / (sensitivity / 100f)).coerceIn(0.5f, 4f)
        val deckFling = remember(listState, flingFriction) {
            snapFlingBehavior(
                snapLayoutInfoProvider = SnapLayoutInfoProvider(
                    lazyListState = listState,
                    // PER-ITEM snap (not the uniform SnapPosition.Center it
                    // replaced): item 0 snaps TOP-aligned so the first card
                    // sits flush under the chrome on open, items 1..n snap
                    // CENTRE-aligned so the focused card rests in the middle of
                    // the band with its neighbours peeking. The single source of
                    // truth is [dynamicSnapStartPx] (shared with the focused
                    // index math). REGRESSION FIX: a uniform Center with zero
                    // top padding collapsed item 0 and item 1 onto overlapping
                    // rest offsets, so the fling skipped item 1 and it could
                    // never be focused; giving item 0 its own top line frees the
                    // rest to centre as distinct, reachable snap targets.
                    snapPosition = dynamicSnapPosition,
                ),
                decayAnimationSpec = exponentialDecay(frictionMultiplier = flingFriction),
                // Terminal snap stiffened from StiffnessMedium to StiffnessHigh:
                // the lock clicks decisively into place (a stronger magnet)
                // instead of easing in floatily. Kept NoBouncy so the crisper
                // pull never overshoots the snap line. Only the terminal spring
                // is stiffened; the decay-then-snap fling reach above is
                // untouched so the user's sensitivity dial still governs how far
                // a flick carries.
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessHigh,
                ),
            )
        }
        // Entity cards share the FULLSCREEN slots' rounded-panel treatment.
        val cardShape = remember { RoundedCornerShape(14.dp) }
        val deckMaxCardWidth = rememberResponsiveDimens().maxContentWidth
        val deckScope = rememberCoroutineScope()
        LazyColumn(
            state = listState,
            flingBehavior = deckFling,
            // The deliberate inter-card gap: enough air that two hugging
            // blocks read as separate cards, small enough that a screenful
            // of one-line toggles still feels like one deck.
            verticalArrangement = androidx.compose.foundation.layout.Arrangement
                .spacedBy(R1.space.m),
            contentPadding = PaddingValues(top = centerPadTop, bottom = centerPadBottom),
            // Hidden until the focused card is placed on its snap line (see the
            // pre-paint placement effect); alpha only, so layout/measure still run
            // underneath and the placement scroll has real heights to work with.
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = deckAlpha },
        ) {
            itemsIndexed(cards, key = { _, item -> item.key }) { idx, item ->
                val entityCard = (item as? DeckItem.Entity)?.state
                val isFocusedSlot = idx == settledFocus.intValue
                val longPressTarget = entityCard
                    ?.let { appSettings.entityOverrides[it.id.value]?.longPressTarget }
                val itemLightMode = entityCard?.let { lightWheelModes[it.id] }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    // Per-kind height policy collapsed into one rule: every
                    // item hugs its content, capped at the viewport band.
                    // Entity interiors wrap for real now (fillSlot = false
                    // gives every fill-height element a natural or concrete
                    // height; see EntityCard's fillSlot KDoc), so a switch
                    // card is switch-height while a climate card is tall
                    // enough for its mode buttons and temperature scale.
                    // Both kinds flow, several cards share the screen.
                    Box(
                        modifier = Modifier
                            .widthIn(max = deckMaxCardWidth)
                            .fillMaxWidth()
                            .heightIn(max = bandHeight),
                    ) {
                        if (entityCard != null) {
                            // Every dynamic card is fully laid out and directly
                            // interactive, so the on-card "..." more-info
                            // affordance is live on all of them (not gated to
                            // the focused slot the way the pager gates its
                            // off-screen peek neighbours). Focus here is a
                            // WHEEL-routing concept only.
                            run {
                                // Content-height surface with the Lovelace
                                // items' overflow contract: verticalScroll
                                // measures the card unbounded so it wraps to
                                // its real height, the band cap above clamps
                                // a taller-than-viewport card, and the scroll
                                // only claims drag gestures while content
                                // genuinely overflows (enabled gate), so a
                                // fitting card face stays gesture-inert and
                                // deck/tab swipes pass through exactly as
                                // they do over DeckCardSurface.
                                val entityScroll = rememberScrollState()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            // Static panel treatment (no pager
                                            // offset to animate against): the
                                            // centred card casts the pager's
                                            // full shadow, neighbours a softer
                                            // one so depth still marks focus
                                            // (a subtle, non-blocking cue: it
                                            // never gates taps).
                                            shadowElevation =
                                                (if (isFocusedSlot) 24.dp else 8.dp).toPx()
                                            shape = cardShape
                                            clip = true
                                        }
                                        .verticalScroll(
                                            entityScroll,
                                            enabled = entityScroll.maxValue > 0,
                                        ),
                                ) {
                                    EntityCard(
                                        state = entityCard,
                                        // Every visible card actuates on its
                                        // OWN tap, regardless of wheel focus:
                                        // vm.tapToggle / setSwitchOn /
                                        // fireLongPress act on the active card,
                                        // so a tapped card first claims focus
                                        // (setCurrentIndex) and then fires, in
                                        // the same gesture, via these callbacks.
                                        onTapToggle = {
                                            if (isActive) vm.setCurrentIndex(idx)
                                            vm.tapToggle()
                                        },
                                        tapToToggleEnabled = appSettings.behavior.tapToToggle,
                                        onSetOn = { on ->
                                            if (isActive) vm.setCurrentIndex(idx)
                                            vm.setSwitchOn(on)
                                        },
                                        onLongPress = longPressTarget?.let { target ->
                                            {
                                                if (isActive) vm.setCurrentIndex(idx)
                                                vm.fireLongPress(target)
                                            }
                                        },
                                        lightWheelMode = itemLightMode,
                                        // Content-height path: no vertical fill,
                                        // the card wraps to the sum of its
                                        // controls (see fillSlot's KDoc).
                                        // FULLSCREEN's call site keeps the
                                        // default fill.
                                        fillSlot = false,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                }
                            }
                        } else if (item is DeckItem.Card) {
                            LovelaceDeckCard(
                                item = item,
                                hooks = lovelaceHooks,
                                states = lovelaceStates,
                                scope = deckScope,
                                // Every dynamic card is directly interactive, so
                                // the on-card "..." menu affordance is live on
                                // all of them (the pager gates it to focus only
                                // because its peek neighbours are half-visible).
                                isFocused = true,
                                // Wrap height (the slot IS the content block
                                // here, unlike the pager's full-page slot).
                                modifier = Modifier.fillMaxWidth(),
                                surfaceModifier = Modifier.graphicsLayer {
                                    shadowElevation =
                                        (if (isFocusedSlot) 24.dp else 8.dp).toPx()
                                    shape = R1.ShapeM
                                    clip = true
                                },
                            )
                        }
                        // No tap-to-navigate scrim here (unlike the fullscreen
                        // peek deck): every dynamic card is fully laid out, so
                        // its own content receives taps directly and actuates on
                        // the FIRST tap regardless of wheel focus. An overlay
                        // would swallow that first tap. Wheel focus is tracked
                        // separately via the settled-focus collector.
                    }
                }
            }
        }
        // Down-chevron hint whenever there is more deck below the fold,
        // mirroring the pager's has-next hint.
        androidx.compose.animation.AnimatedVisibility(
            visible = listState.canScrollForward,
            enter = androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 8.dp),
        ) {
            Chevron(direction = ChevronDirection.Down, size = 14.dp, tint = R1.InkMuted)
        }
    }
}
