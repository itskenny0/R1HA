package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

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
 * [DynamicSnapPosition]) settles every gesture on a card's snap line. The deck
 * TOP-ALIGNS: every card snaps so its TOP edge sits flush under the chrome. The
 * LAST card is the HYBRID case (see [dynamicLastCardBottomAligns]): when it is
 * tall / alone it BOTTOM-aligns so its bottom edge sits flush with the band
 * bottom (nothing follows it, so top-aligning it would leave a void) and it owns
 * the bottom scroll clamp; but when the last two cards FIT in the band together,
 * bottom-aligning would strand the second-to-last (the clamp blocks its rise to
 * the chrome), so the last card TOP-aligns like the others with a bottom spacer
 * ([dynamicLastCardTopPaddingPx]) and the whole tail becomes scroll-steppable.
 * The natural scroll order falls straight out of that: scroll up and the first
 * card is focused at the top; scroll down and each next card rises to the top and
 * takes focus; scroll to the bottom and the last card rests (flush, or top-aligned
 * with the spacer below it in the fitting-tail case), focused. There is no float
 * to hide: the first card is flush at the top from the very first frame. The
 * focused card (nearest its own snap line, see [dynamicFocusedIndex]) is the one
 * the WHEEL and hardware keys drive.
 *
 * Unlike the fullscreen peek deck, every dynamic card is fully laid out and
 * directly hittable: a tap goes straight to the tapped card's own content and
 * fires it (button activate, script run, switch toggle, light tap), regardless
 * of which card the wheel is centred on. There is no tap-to-snap scrim or
 * dimming over non-focused cards; focus is a WHEEL-routing concept only and
 * never gates tap actuation. (The fullscreen peek deck keeps tap-to-navigate,
 * correct there since its neighbours are off-screen peeks, not laid-out cards.)
 *
 * A short deck whose content all fits on screen cannot scroll, so the first
 * card stays focused and a later card is only reachable by tap; that is fine,
 * since a tap actuates any card regardless of focus (existing behaviour).
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
 * (viewportEnd - viewportStart). Cards 0..n-2 snap to the true top (0), the last
 * card to its bottom-align line ((layoutSize - itemSize), clamped >= 0).
 * [position]'s return value is the item-start offset measured from the START OF
 * THE CONTENT AREA (i.e. after [beforeContentPadding]); the framework lands the
 * item at `beforeContentPadding + returned`. Top-align uses no content padding so
 * [beforeContentPadding] is 0 and the returned offset is the snap line itself; we
 * still subtract it so the frame stays correct for any padding the framework
 * reports.
 *
 * Carries the hybrid [lastCardBottomAligns] flag (the caller recreates it, keyed
 * on the live decision, like the fling) so the last card's snap line flips
 * between its bottom-align rest and a top-align rest exactly when
 * [dynamicLastCardBottomAligns] does. Holds only that flag, no scroll state.
 */
private class DynamicSnapPosition(
    private val lastCardBottomAligns: Boolean,
) : SnapPosition {
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
            itemCount = itemCount,
            lastCardBottomAligns = lastCardBottomAligns,
        )
    }
}

/**
 * Put the [index] item on its SNAP line: cards 0..n-2 FLUSH at the chrome (top),
 * the last card FLUSH at the band bottom, matching where a fling would have
 * settled under [DynamicSnapPosition]. [animated] picks the smooth scroll (nav /
 * jump targets) or an instant one (rare; placement now leans on the list state's
 * initial index instead).
 *
 * [LazyListState.scrollToItem] aligns an item's START with the start of the
 * content area; with no top content padding that is already the flush top, so a
 * non-last target needs only the plain scroll. The last card bottom-aligns (when
 * [lastCardBottomAligns]): we bring it on-screen, then shift it DOWN by the gap
 * between the full band and its measured height so its bottom edge meets the band
 * bottom. The height is only known once the item is on-screen, which is why the
 * bottom-align is a two-step scroll; that is what lets a FAR jump to the last card
 * still land flush. A band-taller last card already fills the band (clamp <= 0),
 * so it just top-aligns. In the fitting-tail hybrid ([lastCardBottomAligns] =
 * false) the last card top-aligns like the others, so the plain scroll already
 * lands it and the second step is skipped.
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.scrollToItemSnapped(
    index: Int,
    itemCount: Int,
    lastCardBottomAligns: Boolean = true,
    animated: Boolean = true,
) {
    suspend fun go(i: Int, offset: Int = 0) =
        if (animated) animateScrollToItem(i, offset) else scrollToItem(i, offset)
    // Bring the item on-screen with its start at the content-area top.
    go(index)
    // Only a BOTTOM-aligned last card needs the second step; everything else
    // (including a top-aligned last card in the fitting-tail hybrid) is already
    // flush at the top after the plain scroll.
    if (index < itemCount - 1 || !lastCardBottomAligns) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val bandSpan = info.viewportEndOffset - info.viewportStartOffset
    // Negative scroll-offset shifts the item DOWN so its bottom meets the band
    // bottom; clamp so a band-filling card is not pushed past the top.
    val bottomOffset = -(bandSpan - item.size).coerceAtLeast(0)
    if (bottomOffset != 0) go(index, bottomOffset)
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
    // user's scroll position survives it (items are keyed below). The restored
    // focus seeds initialFirstVisibleItemIndex: TOP-aligning that item is the
    // list state's natural placement (its start sits at the content top), so a
    // tab switch / revisit opens already-positioned on the focused card with no
    // placement scroll and no float to hide.
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
    // STICKY TARGET: the card the user last EXPLICITLY selected (wheel step, pip
    // jump, or title tap). Held in a -1 sentinel box (no -> -1) because the deck
    // bottom-aligns the last card: when the last cards fit together in the band
    // the bottom scroll clamp is hit before the second-to-last card's top can
    // reach the chrome, so that card can never sit on its snap line and the
    // scroll-derived focus would otherwise snap selection straight back to the
    // bottom-aligned last card (the 5/6-is-skipped bug). While a sticky target is
    // live the settled-focus collector honours IT (see resolveSettledFocus), so
    // every card stays selectable. Cleared on the next BY-HAND settle.
    val stickyTarget = remember(pageId) { mutableIntStateOf(-1) }
    // PROGRAMMATIC-SCROLL depth: > 0 while one of our scrollToItemSnapped calls
    // (nav / jump / tap) is in flight. Used only to tell a programmatic scroll's
    // own isScrollInProgress=true edge apart from a USER drag's: a drag that the
    // user starts by hand clears the sticky target (they moved on), a
    // programmatic scroll must not clear it. A plain box (not Compose state):
    // only effects read/write it, never composition. A depth counter (not a
    // bool) so overlapping programmatic scrolls don't clear each other early.
    val programmaticDepth = remember(pageId) { intArrayOf(0) }
    // HYBRID last-card alignment: does the last card rest FLUSH at the band bottom
    // (true, the default and the look when the last card is tall / alone), or
    // TOP-align like the others (false, when the last two cards fit in the band
    // together so bottom-aligning would strand the second-to-last)? Computed from
    // the live-measured tail heights inside the constraints scope below and
    // written there in an effect; held here as state so the snap provider, the
    // fling, the content padding AND the scroll/focus effects above the
    // constraints scope all read one decision (see [dynamicLastCardBottomAligns]).
    val lastCardBottomAligns = remember(pageId) { mutableStateOf(true) }
    // Select [target] explicitly (wheel step, pip jump, title tap): record it as
    // sticky and scroll it onto its snap line, tagging the scroll programmatic so
    // its own scroll-start edge is not mistaken for a user drag. settledFocus is
    // set DIRECTLY too, not left to the settle collector: a wheel onto the STUCK
    // second-to-last card can't actually move the list (it is already at the
    // bottom clamp), so animateScrollToItem may be a no-op that never toggles
    // isScrollInProgress and so never produces a settle to consume. Writing the
    // focus here makes the selection land even in that no-op case; when the
    // scroll DOES run the settle collector re-resolves to the same sticky target
    // (idempotent). runCatching keeps a scroll cancelled by a user touch from
    // killing the caller's collector; the finally always unwinds the depth.
    suspend fun scrollToTarget(target: Int) {
        stickyTarget.intValue = target
        if (settledFocus.intValue != target) {
            settledFocus.intValue = target
            if (isActive) vm.setCurrentIndex(target) else vm.setIndexForPage(pageId, target)
        }
        programmaticDepth[0]++
        try {
            runCatching {
                listState.scrollToItemSnapped(target, cards.size, lastCardBottomAligns.value)
            }
        } finally {
            programmaticDepth[0]--
        }
    }
    // USER-DRAG sticky clear: a scroll the user STARTS by hand (isScrollInProgress
    // rises while no programmatic scroll is in flight) means they moved on from
    // the explicit selection, so drop the sticky target and let the scroll-derived
    // focus win again at the next settle. A programmatic scroll's own start edge
    // (programmaticDepth > 0) is ignored. Distinct from the settle collector so
    // provenance is read on the START edge (deterministic: the depth is held
    // across the whole programmatic scroll) rather than racing the settle.
    LaunchedEffect(listState, isActive, pageId) {
        if (!isActive) return@LaunchedEffect
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                if (scrolling && programmaticDepth[0] == 0) stickyTarget.intValue = -1
            }
    }
    LaunchedEffect(listState, pageId, isActive, cards.size) {
        snapshotFlow {
            // null while scrolling = "no settle yet"; the focused index is
            // only sampled at rest, when the snap has put some card's start
            // on (or nearest to) the snap line.
            if (listState.isScrollInProgress) {
                null
            } else {
                val info = listState.layoutInfo
                // The band span (viewportEnd - viewportStart) is the full
                // viewport with no content padding to net out; item offsets
                // share that coordinate space once the viewport start is
                // subtracted, so the helper picks the card nearest its own snap
                // line (top for 0..n-2, bottom for the last).
                val bandSpan = info.viewportEndOffset - info.viewportStartOffset
                dynamicFocusedIndex(
                    info.visibleItemsInfo.map {
                        DynamicVisibleItem(
                            index = it.index,
                            // Offset is absolute in viewport coords; subtract
                            // the viewport start so item start and snap line
                            // share a 0-based origin (the band top).
                            offsetPx = it.offset - info.viewportStartOffset,
                            sizePx = it.size,
                        )
                    },
                    bandHeightPx = bandSpan,
                    itemCount = cards.size,
                    // Read the hybrid flag here so the focus math uses the SAME
                    // last-card rest line as the snap provider; snapshotFlow then
                    // re-emits if the flag flips while the deck is at rest.
                    lastCardBottomAligns = lastCardBottomAligns.value,
                )
            }
        }
            // NB: no distinctUntilChanged on the raw scroll focus. When the user
            // wheels onto the STUCK second-to-last card the scroll cannot move it
            // (it stays at the bottom clamp), so the raw focus is unchanged from
            // before the selection; a distinct filter would swallow that settle
            // and the sticky override below would never apply. The null between
            // settles still separates consecutive settles into distinct
            // emissions, and the effective-focus guard below dedups the writes.
            .filterNotNull()
            .collect { scrollFocus ->
                // A live sticky target (an explicit selection that may sit on a
                // stuck card the scroll can't reach) wins over the scroll-derived
                // focus; once the user hand-drags, the drag-start effect above has
                // already cleared the sticky, so the scroll focus takes over here.
                val sticky = stickyTarget.intValue.takeIf { it >= 0 }
                val idx = resolveSettledFocus(scrollFocus, sticky, cards.size)
                if (idx == settledFocus.intValue) return@collect
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
            // Step one card off the SETTLED focus (which now reflects a stuck
            // card too), so wheeling down visits the second-to-last between the
            // card before it and the last, and wheeling up returns from the last
            // to the second-to-last and earlier, with no skipped index.
            val target = dynamicSnapTarget(settledFocus.intValue + delta, cards.size)
            if (target != settledFocus.intValue) scrollToTarget(target)
        }
    }
    // Jump-to-card / widget deep-link targets. Snap the target so a programmatic
    // jump lands exactly where a fling would have settled (top-aligned, or
    // bottom-aligned for the last card).
    LaunchedEffect(listState, jumpRequests, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        jumpRequests.collect { targetIdx ->
            if (cards.isEmpty()) return@collect
            scrollToTarget(dynamicSnapTarget(targetIdx, cards.size))
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
        // HYBRID last-card alignment, measured live. When the last card and the
        // second-to-last FIT in the band together, bottom-aligning the last card
        // strands the second-to-last (the bottom clamp blocks its rise to the
        // chrome, the 5/6-is-skipped bug); in that case the last card TOP-aligns
        // like the others and we pad the list by [dynamicLastCardTopPaddingPx] so
        // it can reach the top, making the whole tail scroll-steppable. The flush
        // bottom is kept only when the last card is tall / alone (the tail does not
        // fit together), where there is no conflict. We probe the last and
        // second-to-last heights from the live layout (keyed on their item
        // identities so a tail mutation drops a stale height); the decision and the
        // pad both flow from [dynamicLastCardBottomAligns].
        val lastCardKey = cards.lastOrNull()?.key
        val secondToLastKey = cards.getOrNull(cards.size - 2)?.key
        val lastCardHeightPx = remember(pageId, lastCardKey, cards.size) { mutableIntStateOf(-1) }
        val secondToLastHeightPx = remember(pageId, secondToLastKey, cards.size) { mutableIntStateOf(-1) }
        LaunchedEffect(listState, lastCardKey, cards.size) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == cards.lastIndex }?.size
            }.filterNotNull().distinctUntilChanged().collect { lastCardHeightPx.intValue = it }
        }
        LaunchedEffect(listState, secondToLastKey, cards.size) {
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == cards.size - 2 }?.size
            }.filterNotNull().distinctUntilChanged().collect { secondToLastHeightPx.intValue = it }
        }
        val interCardGapPx = with(LocalDensity.current) { R1.space.m.roundToPx() }
        // Resolve the hybrid decision into the hoisted state (effect-phase write,
        // so composition above never reads a half-updated value). Read back below
        // for the pad / snap provider and above for the scroll / focus effects.
        val resolvedBottomAligns = dynamicLastCardBottomAligns(
            bandHeightPx = bandHeightPx,
            lastCardHeightPx = lastCardHeightPx.intValue.takeIf { it >= 0 },
            secondToLastCardHeightPx = secondToLastHeightPx.intValue.takeIf { it >= 0 },
            gapPx = interCardGapPx,
            itemCount = cards.size,
        )
        LaunchedEffect(resolvedBottomAligns) { lastCardBottomAligns.value = resolvedBottomAligns }
        // Bottom spacer ONLY when the last card top-aligns (fitting tail), so it
        // can climb to the chrome; 0 (no spacer) for the flush bottom-align case.
        val bottomPad = with(LocalDensity.current) {
            dynamicLastCardTopPaddingPx(
                bandHeightPx = bandHeightPx,
                lastCardHeightPx = lastCardHeightPx.intValue.takeIf { it >= 0 },
                lastCardBottomAligns = resolvedBottomAligns,
            ).toDp()
        }
        // DIAGNOSTIC, gated behind the Dev-menu "Deck snap diagnostics" toggle
        // (default OFF). When on, on every settle it ships a full snapshot of the
        // snap geometry so the on-device snapping can be read from the log
        // receiver instead of guessed at: the ACTUAL content paddings Compose
        // applied (before/after), the band span and viewport frame, and every
        // visible card's normalised offset against its computed snap line (d =
        // how far it rested OFF its line: a large d on the focused card is a
        // between-cards rest), plus the focus the deck chose. R1Log.i survives
        // release builds and ships. Keyed on the toggle so flipping it off tears
        // the collector down (no snapshotFlow runs at all).
        val deckSnapDiag = appSettings.logShipping.deckSnapDiagnostics
        LaunchedEffect(listState, pageId, isActive, deckSnapDiag, cards.size, resolvedBottomAligns) {
            if (!isActive || !deckSnapDiag) return@LaunchedEffect
            snapshotFlow { listState.isScrollInProgress }
                .distinctUntilChanged()
                .collect { scrolling ->
                    if (scrolling) return@collect
                    val info = listState.layoutInfo
                    val span = info.viewportEndOffset - info.viewportStartOffset
                    val vis = info.visibleItemsInfo.joinToString(" | ") { it ->
                        val norm = it.offset - info.viewportStartOffset
                        val snap = dynamicSnapStartPx(it.index, it.size, span, cards.size, resolvedBottomAligns)
                        "#${it.index} off=${it.offset} norm=$norm sz=${it.size} snap=$snap d=${norm - snap}"
                    }
                    val focus = dynamicFocusedIndex(
                        info.visibleItemsInfo.map {
                            DynamicVisibleItem(it.index, it.offset - info.viewportStartOffset, it.size)
                        },
                        span,
                        cards.size,
                        resolvedBottomAligns,
                    )
                    com.github.itskenny0.r1ha.core.util.R1Log.i(
                        "DeckSnap",
                        "settle page=$pageId layoutH=$bandHeightPx span=$span " +
                            "lastBottomAligns=$resolvedBottomAligns " +
                            "before=${info.beforeContentPadding} after=${info.afterContentPadding} " +
                            "vpStart=${info.viewportStartOffset} " +
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
        // Recreated when the hybrid flag flips so the last card's snap line follows
        // the live tail-fits decision (mirrors the fling's friction keying).
        val deckSnapPosition = remember(resolvedBottomAligns) {
            DynamicSnapPosition(lastCardBottomAligns = resolvedBottomAligns)
        }
        val deckFling = remember(listState, flingFriction, deckSnapPosition) {
            snapFlingBehavior(
                snapLayoutInfoProvider = SnapLayoutInfoProvider(
                    lazyListState = listState,
                    // PER-ITEM snap (not the uniform SnapPosition.Center it
                    // replaced): cards 0..n-2 snap TOP-aligned so the focused
                    // card sits flush under the chrome. The last card snaps
                    // BOTTOM-aligned (flush at the band bottom) when it is tall /
                    // alone, or TOP-aligned in the fitting-tail hybrid so the whole
                    // tail is scroll-steppable; [DynamicSnapPosition] carries that
                    // decision. The single source of truth is [dynamicSnapStartPx]
                    // (shared with the focused-index math).
                    snapPosition = deckSnapPosition,
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
            // No TOP padding: a non-last card reaches the flush top by scrolling up
            // and the first card is flush from the first frame. The BOTTOM pad is 0
            // in the flush case (the bottom-aligned last card OWNS the bottom clamp
            // there) and exactly `band - lastHeight` in the fitting-tail hybrid,
            // where the last card TOP-aligns and needs that spacer below it to climb
            // to the chrome (see [dynamicLastCardTopPaddingPx]). It is the one case
            // the snap maths read a non-zero afterContentPadding, which the snap
            // provider already nets out via beforeContentPadding-relative offsets.
            contentPadding = PaddingValues(bottom = bottomPad),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(cards, key = { _, item -> item.key }) { idx, item ->
                val entityCard = (item as? DeckItem.Entity)?.state
                val isFocusedSlot = idx == settledFocus.intValue
                val longPressTarget = entityCard
                    ?.let { appSettings.entityOverrides[it.id.value]?.longPressTarget }
                val itemLightMode = entityCard?.let { lightWheelModes[it.id] }
                // Manual target affordance: a tap on this card's TITLE focuses it
                // and scrolls it to its snap line. Tall cards (a media card fills
                // most of the screen) swallow a touch-drag in their value bar /
                // internal scroll, so scrolling to a later card is impossible on a
                // touch screen; the title tap is the way to select past the first.
                // Routed through LocalOnCardTarget so each theme's title wires it
                // without the deck reaching into the card internals.
                val onCardTarget: () -> Unit = {
                    // Title tap selects this card explicitly: route through
                    // scrollToTarget so the tap STICKS even on the stuck
                    // second-to-last card (whose scroll is a no-op at the bottom
                    // clamp), matching the wheel/jump path. scrollToTarget already
                    // writes the focus through to the view model.
                    deckScope.launch { scrollToTarget(idx) }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                  CompositionLocalProvider(
                    com.github.itskenny0.r1ha.core.theme.LocalOnCardTarget provides onCardTarget,
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
                                            // focused card casts the pager's
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
