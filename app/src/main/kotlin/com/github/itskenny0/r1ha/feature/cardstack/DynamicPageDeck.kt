package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.EntityCard
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * The DYNAMIC deck layout: the same mixed deck PageDeck renders, but as a
 * snapping [LazyColumn] whose items are sized per kind instead of a uniform
 * full-viewport pager.
 *
 *  - ENTITY items keep the full viewport height ([deckItemFillsViewport]):
 *    an entity card still snaps in full-screen and the wheel / value-bar
 *    surface is byte-for-byte the FULLSCREEN one.
 *  - LOVELACE items hug their content height, capped at the viewport with
 *    internal scroll past that (the [DeckCardSurface] measure contract), so a
 *    one-line toggle takes one line instead of marooning itself in a screen
 *    of black.
 *
 * Cards remain discrete snap targets: a snapping fling
 * ([SnapLayoutInfoProvider] over the list state, [SnapPosition.Start]) settles
 * every gesture with some card's start on the snap line at the viewport top,
 * and the focused card (nearest the line, see [dynamicFocusedIndex]) is the
 * one the wheel, tap-to-toggle and long-press act on. Non-focused visible
 * cards wear the same tap-to-navigate scrim peek neighbours do, so a tap on a
 * peeking card snaps it in rather than actuating it.
 *
 * Deliberately NOT supported here (FULLSCREEN keeps both):
 *  - Infinite scroll: see [dynamicSnapTarget] for why a per-item-height lazy
 *    list has no honest analogue of the pager's virtual-page wrap. The deck
 *    is finite in this layout regardless of [AppSettings].ui.infiniteScroll.
 *  - The peek-deck presentation: superseded, the dynamic list already shows
 *    neighbouring cards at their real heights.
 */
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
                dynamicFocusedIndex(
                    listState.layoutInfo.visibleItemsInfo.map {
                        DynamicVisibleItem(index = it.index, offsetPx = it.offset)
                    },
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
    // Hardware-key card steps (CARD_UP / CARD_DOWN push signed deltas into the
    // shared navRequests flow). Clamped to the finite deck; runCatching keeps
    // an animateScrollToItem cancelled by a user touch from killing the
    // collector for the rest of the session.
    LaunchedEffect(listState, navRequests, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        navRequests.collect { delta ->
            if (cards.isEmpty() || delta == 0) return@collect
            val target = dynamicSnapTarget(settledFocus.intValue + delta, cards.size)
            if (target != settledFocus.intValue) {
                runCatching { listState.animateScrollToItem(target) }
            }
        }
    }
    // Jump-to-card / widget deep-link targets. animateScrollToItem aligns the
    // item's start with the viewport top, which IS the snap position, so a
    // programmatic jump lands exactly where a fling would have snapped.
    LaunchedEffect(listState, jumpRequests, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        jumpRequests.collect { targetIdx ->
            if (cards.isEmpty()) return@collect
            runCatching { listState.animateScrollToItem(dynamicSnapTarget(targetIdx, cards.size)) }
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
        // Reachability padding. With start-snapping, a SHORT last card could
        // never reach the snap line (the list stops scrolling when its end
        // hits the viewport bottom), so that card could never become the
        // focused one. Padding the list's end by (band - last item height)
        // raises the max scroll exactly enough for the last card's start to
        // reach the line; the height is read from the live layout info, so
        // until the last card has been composed once the padding is 0
        // (conservative; it appears as the user approaches the end).
        val lastItemHeightPx = remember(pageId) { mutableIntStateOf(Int.MAX_VALUE) }
        LaunchedEffect(listState, cards.size) {
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
        val reachPadBottom = with(LocalDensity.current) {
            (bandHeightPx - lastItemHeightPx.intValue).coerceAtLeast(0).toDp()
        }
        // Snapping fling: decay the gesture's velocity (so a hard flick still
        // carries through several cards, same physics family as the pager),
        // then snap the nearest card's start onto the line with the same
        // crisp, critically-damped spring PageDeck tunes its pager with: no
        // bounce, never rests between cards. The decay honours the user's
        // scroll-sensitivity dial through the identical friction mapping.
        val sensitivity = appSettings.ui.cardScrollSensitivity.coerceIn(1, 100)
        val flingFriction = (0.8f / (sensitivity / 100f)).coerceIn(0.5f, 4f)
        val deckFling = remember(listState, flingFriction) {
            snapFlingBehavior(
                snapLayoutInfoProvider = SnapLayoutInfoProvider(
                    lazyListState = listState,
                    snapPosition = SnapPosition.Start,
                ),
                decayAnimationSpec = exponentialDecay(frictionMultiplier = flingFriction),
                snapAnimationSpec = spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = Spring.StiffnessMedium,
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
            contentPadding = PaddingValues(bottom = reachPadBottom),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(cards, key = { _, item -> item.key }) { idx, item ->
                val entityCard = (item as? DeckItem.Entity)?.state
                val isFocusedSlot = idx == settledFocus.intValue
                val longPressTarget = entityCard
                    ?.let { appSettings.entityOverrides[it.id.value]?.longPressTarget }
                val itemLightMode = entityCard?.let { lightWheelModes[it.id] }
                // Per-kind height policy (pure, unit-tested): entity items
                // fill the band, Lovelace items hug content capped at it.
                val fillsViewport = deckItemFillsViewport(item)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(
                        modifier = Modifier
                            .widthIn(max = deckMaxCardWidth)
                            .fillMaxWidth()
                            .then(
                                if (fillsViewport) {
                                    Modifier.height(bandHeight)
                                } else {
                                    Modifier.heightIn(max = bandHeight)
                                },
                            ),
                    ) {
                        if (entityCard != null) {
                            // Same focused-only on-card "..." gate the pager
                            // applies to peek neighbours.
                            CompositionLocalProvider(
                                com.github.itskenny0.r1ha.core.theme.LocalOnCardMoreInfo provides
                                    if (isFocusedSlot) {
                                        com.github.itskenny0.r1ha.core.theme.LocalOnCardMoreInfo.current
                                    } else {
                                        null
                                    },
                            ) {
                                EntityCard(
                                    state = entityCard,
                                    onTapToggle = { vm.tapToggle() },
                                    // vm.tapToggle / setSwitchOn / fireLongPress all
                                    // act on the ACTIVE card, so only the focused
                                    // slot may expose them; non-focused slots are
                                    // tap-to-navigate via the scrim below.
                                    tapToToggleEnabled = isFocusedSlot &&
                                        appSettings.behavior.tapToToggle,
                                    onSetOn = { on -> vm.setSwitchOn(on) },
                                    onLongPress = if (!isFocusedSlot) {
                                        null
                                    } else {
                                        longPressTarget?.let { target ->
                                            { vm.fireLongPress(target) }
                                        }
                                    },
                                    lightWheelMode = itemLightMode,
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            // Static panel treatment (no pager
                                            // offset to animate against): the
                                            // focused card casts the pager's
                                            // full shadow, neighbours a softer
                                            // one so depth still marks focus.
                                            shadowElevation =
                                                (if (isFocusedSlot) 24.dp else 8.dp).toPx()
                                            shape = cardShape
                                            clip = true
                                        },
                                )
                            }
                        } else if (item is DeckItem.Card) {
                            LovelaceDeckCard(
                                item = item,
                                hooks = lovelaceHooks,
                                states = lovelaceStates,
                                scope = deckScope,
                                isFocused = isFocusedSlot,
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
                        // Tap-to-navigate scrim on every non-focused card: the
                        // same affordance peek neighbours wear. A tap snaps the
                        // card onto the line (making it the focused target for
                        // wheel / buttons) instead of actuating its controls.
                        if (!isFocusedSlot) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(if (entityCard != null) cardShape else R1.ShapeM)
                                    .background(R1.Bg.copy(alpha = 0.28f))
                                    .r1Pressable(
                                        onClick = {
                                            deckScope.launch {
                                                runCatching {
                                                    listState.animateScrollToItem(idx)
                                                }
                                            }
                                        },
                                        contentDescription = "Show ${item.displayName()}",
                                    ),
                            )
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
