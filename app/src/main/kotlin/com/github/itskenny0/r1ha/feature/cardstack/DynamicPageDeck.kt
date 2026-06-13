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
 * machinery calls [position] for each candidate item with the viewport span and
 * the item's measured size; we delegate to the pure [dynamicSnapStartPx] so the
 * snap line and the focused-index math are computed by one shared function.
 *
 * [position]'s contract: return the desired offset of the item's START from the
 * viewport start (after content padding). The band span the snap maths reason
 * in is the layout size minus the symmetric content padding, exactly what
 * [dynamicSnapStartPx] treats as the band height. Item 0 returns 0 (flush at the
 * top, matching SnapPosition.Start); items 1..n return the Center arithmetic.
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
        val bandSpan = layoutSize - beforeContentPadding - afterContentPadding
        return dynamicSnapStartPx(
            itemIndex = itemIndex,
            itemSizePx = itemSize,
            bandHeightPx = bandSpan,
        )
    }
}

/**
 * Animate the [index] item to its SNAP line: item 0 to the band TOP, every
 * other item to the band CENTRE, matching where a fling would have settled
 * under [dynamicSnapPosition]. [LazyListState.animateScrollToItem] aligns an
 * item's START with the viewport start, so this first scrolls there (already
 * the correct rest for item 0), then (once the target is measured and visible)
 * recentres a non-first item by the half-difference between the snap viewport
 * span and the item's real height. Splitting it in two is what lets a FAR jump
 * work: the destination item's height is unknown until it is brought on-screen,
 * so the centre offset can only be computed after the first scroll. A
 * band-filling (or taller) card is already its own centre, so the recentre
 * offset clamps to <= 0 (never pushes it back down).
 */
private suspend fun androidx.compose.foundation.lazy.LazyListState.animateScrollToItemCentered(
    index: Int,
) {
    // Bring the item on-screen with its start at the viewport top.
    animateScrollToItem(index)
    // Item 0 snaps top-aligned: the start-aligned scroll above already lands it
    // exactly, so leave it flush at the band top (no recentre).
    if (index <= 0) return
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    val bandSpan = info.viewportEndOffset - info.viewportStartOffset
    // Negative scroll-offset shifts the item DOWN into the centre; clamp so a
    // band-filling card is not pushed past centre.
    val centerOffset = -((bandSpan - item.size) / 2).coerceAtLeast(0)
    if (centerOffset != 0) animateScrollToItem(index, centerOffset)
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
                runCatching { listState.animateScrollToItemCentered(target) }
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
                listState.animateScrollToItemCentered(dynamicSnapTarget(targetIdx, cards.size))
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
        val centerPadTop = 0.dp
        val centerPadBottom = with(LocalDensity.current) {
            dynamicCenterPaddingPx(
                bandHeightPx = bandHeightPx,
                endItemHeightPx = lastItemHeightPx.intValue.takeIf { it >= 0 },
            ).toDp()
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
            modifier = Modifier.fillMaxSize(),
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
