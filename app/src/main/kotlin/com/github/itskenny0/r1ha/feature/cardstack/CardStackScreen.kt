package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.EntityCard
import com.github.itskenny0.r1ha.ui.components.HamburgerGlyph
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.SettingsCogGlyph
import androidx.compose.foundation.horizontalScroll
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@Composable
fun CardStackScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    /** Surfaced from the QuickActions sheet (long-press hamburger →
     *  TODAY). Lets the user jump to the at-a-glance dashboard without
     *  going through Settings. */
    onOpenDashboard: () -> Unit = {},
    /** Surfaced via long-press on the chrome's settings gear. Jumps
     *  to the Universal Quick Search dialog from anywhere on the
     *  card stack. */
    onOpenSearch: () -> Unit = {},
    /** Tap the chrome's mic glyph to open the HA Assist surface
     *  directly. Default no-op for previews. */
    onOpenAssist: () -> Unit = {},
    /** Browse-everything sheet shortcuts. The QuickActions sheet
     *  (long-press hamburger) doubles as a navigation drawer in the
     *  HA-Companion idiom; these callbacks are routed from there so
     *  the user can jump to every major surface without first
     *  walking through Settings. */
    onOpenAutomations: () -> Unit = {},
    onOpenEnergy: () -> Unit = {},
    onOpenScenes: () -> Unit = {},
    onOpenNotifications: () -> Unit = {},
    onOpenZones: () -> Unit = {},
    onOpenDevice: () -> Unit = {},
    /** Context-aware quick-jump targets surfaced in the QuickActions sheet's
     *  GO TO row, which adapts to the focused card's domain (media_player →
     *  Media Browse, person → Persons, weather → Weather, climate/sensor →
     *  that entity's History). [onOpenHistory] takes the focused entity_id.
     *  Default no-ops keep previews / tests cheap. */
    onOpenCameras: () -> Unit = {},
    onOpenMediaBrowse: () -> Unit = {},
    /** Open media-browse pre-seeded with a specific player (more-info "Browse
     *  media"). Default no-op keeps previews / tests cheap. */
    onOpenMediaBrowseFor: (entityId: String) -> Unit = {},
    onOpenWeather: () -> Unit = {},
    onOpenPersons: () -> Unit = {},
    onOpenHistory: (entityId: String) -> Unit = {},
    /** Open the native Logbook scoped to an entity (the more-info logbook SHOW
     *  MORE). Default no-op keeps previews / tests cheap. */
    onOpenLogbook: (entityId: String) -> Unit = {},
    /** Navigate to a pinned Lovelace dashboard VIEW by its full
     *  [com.github.itskenny0.r1ha.nav.Routes.dashboardsViewRoute] string. Surfaced
     *  in the QuickActions drawer's DASHBOARDS section so phone users (no nav rail)
     *  can reach their pinned views. Default no-op keeps previews / tests cheap. */
    onOpenDashboardRoute: (String) -> Unit = {},
    /** Navigate to a pinned nav SURFACE by its [com.github.itskenny0.r1ha.nav.Routes]
     *  id (e.g. Routes.ENERGY). Surfaced in the QuickActions drawer's PINNED section so
     *  phone users (no nav rail) reach the same pinned surfaces the tablet sidebar shows.
     *  Mirrors [onOpenDashboardRoute]'s wiring. Default no-op keeps previews / tests cheap. */
    onOpenRoute: (String) -> Unit = {},
) {
    val vm: CardStackViewModel = viewModel(
        factory = CardStackViewModel.factory(
            haRepository = haRepository,
            settings = settings,
            wheelInput = wheelInput,
        )
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val connection by haRepository.connection.collectAsStateWithLifecycle()
    // 'WS silent' = WebSocket reports Connected but no state_changed event has
    // arrived for >60 s, which is the soft-broken-proxy case the REST heartbeat
    // fallback was added to mitigate (the user's friend's reverse-proxied install
    // surfaced this earlier in development). We surface it as an amber chrome dot
    // so the user has a visible signal that the WS isn't carrying its weight,
    // even though the connection-state machine reads Connected. Ticks every 10 s
    // (cheap; only re-reads two StateFlow values) so the dot flips into amber
    // promptly after the WS goes silent and back to none when an event lands.
    val lastEventAt by haRepository.lastEventAtMillis.collectAsStateWithLifecycle()
    // produceState binds the 10s tick to composition lifecycle automatically: the
    // previous manual LaunchedEffect + mutableLongStateOf pair spelled out the
    // cancellation behaviour produceState gives for free.
    val nowTick by androidx.compose.runtime.produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            kotlinx.coroutines.delay(10_000L)
        }
    }
    // derivedStateOf so this only invalidates readers when wsSilent actually flips, not
    // every 10s when nowTick ticks. Without it the entire CardStackScreen scope recomposed
    // on every tick (nowTick read at the outer scope = invalidates everything that reads
    // the outer composable's state).
    // Key-less remember: derivedStateOf already reads connection / lastEventAt /
    // nowTick inside its block and tracks them as Snapshot dependencies,
    // so re-creating the derived state every time one changes (the previous
    // `remember(connection, lastEventAt) { derivedStateOf { ... } }`) defeated the
    // memoisation. With a stable remember the derived value is computed once and
    // invalidates only when wsSilent actually flips.
    val wsSilent by androidx.compose.runtime.remember {
        androidx.compose.runtime.derivedStateOf {
            connection is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected &&
                lastEventAt > 0L && (nowTick - lastEventAt) > 60_000L
        }
    }

    // Wheel events are processed ONLY while CardStackScreen is composed. Navigating away
    // (e.g. into FavoritesPicker or Settings) suspends the collection so spinning the wheel
    // there can't silently move the active card's brightness behind the user's back.
    //
    // For read-only cards (sensors) the wheel doesn't drive any value, so we promote it
    // to card-stack navigation instead — wheel up = previous card, wheel down = next.
    // The pager state lives inside VerticalCardPager so we publish a navigation
    // request through this MutableSharedFlow which the pager observes.
    // Signed delta — positive = forward (next card), negative = back. Carrying the
    // delta (rather than a Direction enum) lets the wheel handler scale it up on fast
    // spins: a sustained spin at 12 events/sec on a 30-card deck can move 3-4 cards
    // per detent so the user reaches the far end in a couple of seconds, while a
    // gentle tap-tap still moves exactly one card per event.
    val pagerNavRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<Int>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    // Jump-target index pushed from the jump-to-card sheet. Each PageDeck collects
    // this flow and animates its VerticalPager to the target index when the deck
    // belongs to the active page. Decoupling this from a directly-held PagerState
    // (the prior single-deck model) lets every page maintain its own pager state
    // while still being addressable from screen scope.
    val jumpRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<Int>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    // Target page-id pushed from the tab strip's chip taps. Lives at screen scope
    // because the chip strip and the horizontal pager render in different scopes
    // (chips above the pager); the inner-branch composable that owns the pager
    // collects this flow and animates the pager directly. Driving the pager
    // straight from the tap avoids the round-trip through the settings store and
    // the activePageId LaunchedEffect, which left rapid taps stranded an extra
    // page short.
    val tabTapRequests = remember {
        kotlinx.coroutines.flow.MutableSharedFlow<String>(
            extraBufferCapacity = 4,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )
    }
    // Per-card accumulator for select-option cycling. Needs two same-direction
    // detents (or one same-direction detent within 800 ms of the last) to
    // fire, so a brushing motion doesn't accidentally cycle a select. Tracks
    // the entity it's accumulating for so a tab swap doesn't carry a stale
    // partial count into the new card.
    val selectAccumulatorEntity = remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    val selectAccumulatorSum = remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val selectAccumulatorAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // Transient hint shown on read-only / explicit-button-only cards when the user
    // spins the wheel — they previously expected nav, but the wheel no longer moves
    // between cards (swipe / pip-tap are the deck-nav affordances). The hint surfaces
    // inline on the card for ~2 s then fades, so the user learns the new gesture
    // vocabulary without a permanent piece of chrome. Declared here (rather than
    // inside the chrome-render block) so the LaunchedEffect that observes wheel events
    // can capture it.
    val wheelHintAt = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    // Hoisted LazyListState for the jump-to-card overlay so the wheel handler can
    // animateScrollBy it while the overlay is open — without this hoist the wheel
    // would fall through to the active card's onWheel and adjust e.g. brightness
    // behind the overlay, which the user noticed and reported.
    val jumpListState = androidx.compose.foundation.lazy.rememberLazyListState()
    // Tab-management overlay state. Holds the page id being managed; the sentinel
    // [NEW_PAGE_SENTINEL] means "add a fresh page" rather than editing an existing
    // one. Null = overlay closed. Lifted to screen scope so the management modal
    // can render above the TabStrip + card stack.
    val tabManagementForId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    /** Quick-actions sheet visibility — opened by a long-press on the chrome
     *  hamburger. Holds 'all off on this page' as the only action today;
     *  designed to grow into a generic per-page quick-actions surface. */
    val quickActionsOpen = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Ambient slide-out controller provided by the shell on portrait tiers. When it reports
    // available (PhoneNavStyle.SLIDEOUT + side panel enabled) the hamburger tap opens the
    // shell-hosted navigation slide-out instead of the QuickActions modal; otherwise it's
    // null / inert and the modal is used. Long-press always opens the modal regardless.
    val navDrawer = com.github.itskenny0.r1ha.ui.components.LocalNavDrawerController.current
    // This screen's NavBackStackEntry, used to tell whether the deck has finished
    // animating in. The entry is only RESUMED once the NavHost cross-fade settles;
    // chrome taps that land while it's still STARTED (mid-transition) are the ones
    // that raced the transition into a black screen, so we drop them.
    val navEntryLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    // Jump-to-card overlay visibility — tapped open from the chrome counter to let
    // the user pick a target card by name rather than scrolling through the deck.
    // Declared here (rather than at the chrome-render site) so the wheel-events
    // LaunchedEffect can read its value to gate scroll-routing.
    val jumpPickerOpen = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // Customize-dialog entry from the card stack. `customizingId` is the entity_id under
    // edit; null means the dialog is closed. We hold it locally because the dialog is a
    // transient UI overlay — no need to thread it through the VM state. Declared up here
    // (alongside the other overlay-visibility flags) so the wheel handler's modal gate
    // below can drop wheel events while the dialog is open — otherwise a spin reaches
    // past the full-screen dialog and adjusts the card underneath.
    val customizingId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    // Hoisted state for the screen-level effect picker overlay. When non-null, an
    // overlay sheet renders above all card chrome listing the bulb's effects. Lifted
    // here (rather than inside each card) so the picker can use the full screen rather
    // than being clipped to the card body — a Nanoleaf can ship 30+ effects and a
    // card-bound picker would be cramped on the R1's 320 px tall display. Hoisted above
    // the wheel handler so its modal gate can read it.
    val effectPickerFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    // Parallel state for the select-option picker overlay (Server Fan Mode = auto /
    // manual, etc.). Same screen-scope hoisting as the effect picker so it can use the
    // full display rather than being clipped to the card body.
    val selectPickerFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    // Fan preset-mode picker overlay. Lifted to screen scope for the same reason as
    // the effect picker (full-screen list rather than a card-bound popup) and also
    // because the in-card chip row was eating the horizontal swipe used to switch
    // tabs on the card stack.
    val fanPresetPickerFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    // Colour-wheel overlay for light cards, opened by the HUE mode button. Hoisted to
    // screen scope like the pickers above so the wheel renders full-screen. NOTE: this
    // one is deliberately NOT in the wheel handler's modal gate below: the HUE button
    // put the card into HUE wheel mode before opening the overlay, so the R1's physical
    // wheel keeps cycling hue while the overlay is up. Touch (the wheel thumb) and the
    // physical wheel both write hs_color and the overlay reconciles to the entity echo,
    // so letting wheel events through is the synergy, not a leak.
    val colorWheelFor = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityId?>(null)
    }
    // Entity whose ultra-detail more-info sheet is open; null = closed. Opened
    // from the card context menu's MORE INFO action AND from a wheel-spin on a
    // read-only card (sensor / action / wheel-disabled) when the effective
    // moreInfoEnabled flag is on — routing the otherwise-inert wheel to the
    // detail view makes the ultra-detail surface discoverable from the deck.
    // Hoisted to screen scope (alongside the other overlay-visibility flags) so
    // the wheel handler below can both read it (modal gate) and set it (open).
    val moreInfoEntityId = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    // "Any pager mid-animation" gates wheel events. Two writers feed this:
    //   - the screen-level HorizontalPager (tab swipes) — wired below
    //     where the pager state itself is created
    //   - the active PageDeck's VerticalPager (card swipes) — wired
    //     from inside PageDeck when isActive == true
    //
    // The race we're closing: vm.state.activeState is computed from
    // state.currentIndex / state.activePageId, both of which only
    // update on the corresponding pager's SETTLE event. If the user
    // released a swipe and started spinning the wheel mid-fling,
    // activeState was the previous card while the user was already
    // looking at the next one — modifications landed on the wrong
    // entity and looked like "the app jumped". Dropping wheel events
    // while a pager is in flight makes the active-card identity
    // reliable: when the user spins, they always edit what they see.
    val horizontalPagerAnimating = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val verticalPagerAnimating = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        wheelInput.events.collect { event ->
            // Modal gate: if any full-screen overlay above this scope is open,
            // the wheel shouldn't reach past the overlay and silently adjust the
            // card or page underneath. Covers tab management, quick actions, the
            // customize dialog, and the effect / select / fan-preset picker
            // sheets — every modal that draws over the active card. The
            // jumpPickerOpen branch lower down is intentional (the picker wants
            // wheel input as scroll), so it stays out of this gate.
            if (tabManagementForId.value != null ||
                quickActionsOpen.value ||
                navDrawer?.isOpen == true ||
                customizingId.value != null ||
                effectPickerFor.value != null ||
                selectPickerFor.value != null ||
                fanPresetPickerFor.value != null ||
                moreInfoEntityId.value != null
            ) {
                return@collect
            }
            // One-shot: first time the user spins the wheel, retire the
            // tutorial hint. We test the flag inline rather than holding a
            // remember so a fresh wheel event after sign-out / reset properly
            // re-shows the hint.
            if (!appSettings.behavior.wheelTutorialSeen) {
                scope.launch {
                    settings.update { s ->
                        s.copy(behavior = s.behavior.copy(wheelTutorialSeen = true))
                    }
                }
            }
            // Defensive: never let a wheel event crash the collector — a single
            // bad event in the wheel-handler pipeline would tear down the
            // LaunchedEffect for the rest of the session and the user would
            // have to relaunch to recover. The runCatching wrap logs at ERROR
            // level so the dev-menu log viewer surfaces the cause; downstream
            // (the toast feed at ERROR is always on) flashes a red toast so the
            // user knows something went wrong without losing wheel input
            // entirely.
            runCatching {
            // Pager-animation gate. If either the horizontal tab pager
            // or the active vertical card pager is mid-fling, vm.state
            // .activeState still reflects the card the user left, not
            // the card they can now see — letting a wheel event through
            // here would modify a card the user isn't even looking at
            // and read as "the app jumped to a different card or tab".
            // Drop the event silently; the user will spin again once
            // the pager settles, by which point activeState has
            // updated via setCurrentIndex / setActivePage.
            if (horizontalPagerAnimating.value || verticalPagerAnimating.value) {
                return@collect
            }
            val active = vm.state.value.activeState
            val dir = event.direction
            // Wheel never navigates the deck — that's swipe-and-tap-the-pip only. So
            // on cards with nothing to drive (sensors, actions, non-scalar switches
            // when the toggle setting is off) the wheel becomes a no-op and we surface
            // a transient hint so the user learns the new vocabulary.
            val sign = com.github.itskenny0.r1ha.core.input.WheelInput.applyDirection(
                dir, appSettings.wheel.invertDirection,
            )
            // When the jump-to-card overlay is open the wheel should scroll the list
            // rather than reach past the modal and adjust the card underneath. One
            // detent ≈ one row of pixel height — same idea as a desktop scroll
            // wheel scrolling a focused list. Direction inversion is applied via
            // [sign] above so the user's wheel-direction preference still wins.
            if (jumpPickerOpen.value) {
                // 60 px per detent ≈ one row on the R1's default density; lets a
                // couple-second sustained spin scan a long favourites list end to
                // end. Sign convention: wheel-down ⇒ user wants to see further-
                // down items ⇒ animateScrollBy(positive pixels). [sign] is +1 for
                // UP and -1 for DOWN after invertDirection, so negating it yields
                // the right scroll direction for both wheel orientations.
                pagerScope.launch { jumpListState.animateScrollBy(-sign * 60f) }
                return@collect
            }
            val now = event.timestampMillis
            // Per-card wheel override: explicit On / Off / Inherit. Defaults
            // depend on the domain — select / input_select default OFF
            // because cycling on every detent was too easy to trigger
            // accidentally; every other domain defaults ON. The user can
            // flip either side from the card's customize dialog.
            val perCardOverride = active?.id?.value?.let { appSettings.entityOverrides[it] }
                ?: com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE
            val wheelEnabledHere = active?.let {
                perCardOverride.resolvedWheelEnabled(it.id.domain.prefix)
            } ?: false
            // What to do when the wheel has nothing to drive on this card
            // (sensors, actions, non-scalar switches with the toggle off, or a
            // per-card wheel-disabled override). Historically these spins only
            // flashed a "wheel is inert here" hint. To make the ultra-detail
            // more-info surface DISCOVERABLE from the deck, route that same
            // otherwise-wasted spin to open MoreInfoSheet for the focused entity
            // when the effective per-entity moreInfoEnabled flag is on
            // (override ?: global default — same gate the '…' context menu uses).
            // Setting moreInfoEntityId trips the modal gate above, so a sustained
            // spin opens the sheet exactly once rather than re-firing per detent.
            // When the flag is off we keep the old hint so the wheel still teaches
            // the swipe-and-pip navigation vocabulary.
            val inertWheel: () -> Unit = inert@{
                val a = active ?: run { return@inert }
                val effectiveMoreInfo = perCardOverride.moreInfoEnabled
                    ?: appSettings.ui.moreInfoEnabledDefault
                if (effectiveMoreInfo) {
                    moreInfoEntityId.value = a.id.value
                } else {
                    wheelHintAt.longValue = now
                }
            }
            when {
                active == null -> Unit
                // Per-card wheel-disabled override (or per-domain default for
                // select). Open the detail sheet (or hint) so the wheel does
                // something useful rather than being silently inert.
                !wheelEnabledHere ->
                    inertWheel()
                // Sensors / actions have nothing to drive — open detail (or hint).
                active.id.domain.isSensor || active.id.domain.isAction ->
                    inertWheel()
                // Non-scalar entities (locks, covers without position, vacuums, plain
                // switches) — if the user hasn't opted into wheel-toggles-switches via
                // Settings, the wheel is a no-op here too (open detail or hint). When
                // the setting IS on, fall through to the scalar path's setSwitch via
                // vm.onWheel for the actual toggle.
                !active.supportsScalar && !appSettings.behavior.wheelTogglesSwitches ->
                    inertWheel()
                // Select entities — wheel steps one option per accumulated
                // pair of detents. Accumulator threshold mitigates the
                // "too easy to trigger accidentally" feel: a brushing
                // motion of one or two detents won't cycle, a deliberate
                // spin of three+ will. Anchor resets after 800 ms of no
                // wheel events (a deliberate slow rotate still counts).
                active.id.domain.isSelect -> {
                    val anchor = selectAccumulatorEntity.value
                    val activeId = active.id
                    if (anchor != activeId || now - selectAccumulatorAt.longValue > 800L) {
                        selectAccumulatorEntity.value = activeId
                        selectAccumulatorSum.intValue = 0
                    }
                    selectAccumulatorAt.longValue = now
                    selectAccumulatorSum.intValue += sign
                    val accum = selectAccumulatorSum.intValue
                    if (accum >= 2) {
                        selectAccumulatorSum.intValue = 0
                        vm.cycleSelectOption(activeId, +1)
                    } else if (accum <= -2) {
                        selectAccumulatorSum.intValue = 0
                        vm.cycleSelectOption(activeId, -1)
                    }
                }
                else -> vm.onWheel(event)
            }
            }.onFailure { t ->
                com.github.itskenny0.r1ha.core.util.R1Log.e(
                    "CardStack.wheel", "handler threw on event=$event", t,
                )
            }
        }
    }

    val view = LocalView.current
    // Honour the user's "Haptic feedback" toggle and throttle to ~20 Hz so a fast wheel spin
    // doesn't fire a continuous unpleasant buzz from the haptic motor. Keying on both id and
    // percent so swiping to a new card with the same percent still fires a tactile click.
    // R1Haptic routes through the system Vibrator (EFFECT_TICK on capable devices, a soft
    // 12 ms one-shot otherwise) so phones whose vendor ROM mutes performHapticFeedback —
    // Xiaomi MIUI in particular — still get tactile feedback per detent.
    val cardStackHaptic = com.github.itskenny0.r1ha.ui.components.rememberR1Haptic()
    val lastHapticMs = remember { longArrayOf(0L) }
    // Coalesce the haptic key into a single "perceived value" so a switch entity doesn't
    // tick twice per toggle (once on optimistic, then again when the cache catches up and
    // the optimistic clears — for switch entities the cached percent is always null, so
    // applying the override and then clearing it flips percent null→100→null and the
    // earlier key (percent only) double-fired). For scalar entities the value is the
    // percent itself; for switches it's 0 or 1 keyed on isOn.
    val hapticKey = state.activeState?.let { active ->
        when {
            // Select / input_select: the meaningful change is the picked
            // option string. Keying on it makes the haptic fire once per
            // accepted wheel-cycle (optimistic snap immediately, then again
            // only if HA echoes a different string — we coalesce that via
            // the optimistic clearing logic so the second tick is rare).
            active.id.domain.isSelect -> active.currentOption
            active.supportsScalar -> active.percent
            active.isOn -> 1
            else -> 0
        }
    }
    LaunchedEffect(state.activeState?.id, hapticKey) {
        // Defensive: View.performHapticFeedback can theoretically fail when
        // the view is detaching or the device's haptic motor is in a weird
        // state. Wrap in runCatching so a haptic miss doesn't tear down the
        // LaunchedEffect for the whole session.
        runCatching {
            if (state.activeState == null || !appSettings.behavior.haptics) return@runCatching
            val now = System.currentTimeMillis()
            if (now - lastHapticMs[0] < 50L) return@runCatching
            lastHapticMs[0] = now
            cardStackHaptic.tick(view)
        }
    }

    DisposableEffect(appSettings.behavior.keepScreenOn) {
        view.keepScreenOn = appSettings.behavior.keepScreenOn
        onDispose { view.keepScreenOn = false }
    }

    // Surface a toast when the area-driven page generator completes so the
    // user sees how many tabs were created (zero = no HA areas had
    // controllable entities; common on a fresh HA install).
    androidx.compose.runtime.LaunchedEffect(vm) {
        vm.pagesGenerated.collect { count ->
            val msg = when {
                count < 0 -> "Couldn't reach HA. Try again when you're back online."
                count == 0 -> "No HA areas with controllable entities. Set areas in HA first."
                count == 1 -> "1 page generated from HA areas."
                else -> "$count pages generated from HA areas."
            }
            com.github.itskenny0.r1ha.core.util.Toaster.show(msg)
        }
    }

    // Auto-surface the last crash report if one exists on disk. Fires once
    // per CardStackScreen composition (i.e. once per launch). The expandable
    // error toast carries the full trace; tapping it expands so the user can
    // share with the developer. Deletes the file after surfacing so we don't
    // re-pop on every recomposition.
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        runCatching {
            val crashFile = java.io.File(context.filesDir, "last_crash.txt")
            if (crashFile.exists() && crashFile.length() > 0L) {
                // Cap the read at 32 KB — a crash report is typically a few
                // KB; any more and we're holding it in memory unnecessarily.
                // Truncation suffix tells the user there's more available
                // via the dev menu's LAST CRASH button (which reads the
                // full file).
                val maxBytes = 32 * 1024L
                val raw = if (crashFile.length() <= maxBytes) {
                    crashFile.readText(Charsets.UTF_8)
                } else {
                    crashFile.bufferedReader(Charsets.UTF_8).use { reader ->
                        val buf = CharArray(maxBytes.toInt())
                        val n = reader.read(buf, 0, buf.size)
                        String(buf, 0, n.coerceAtLeast(0)) +
                            "\n\n[truncated. Full report in dev menu LAST CRASH]"
                    }
                }
                com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                    shortText = "Crash detected. Tap for trace",
                    fullText = raw,
                )
                // Don't delete — keep it accessible via the dev menu's
                // LAST CRASH button in case the user wants to revisit. Just
                // rename the file with a 'seen' suffix so we don't auto-pop
                // again on next launch.
                runCatching {
                    java.io.File(context.filesDir, "last_crash_seen.txt").writeText(raw)
                    crashFile.delete()
                }
            }
        }
    }

    // Stable callback holders — each lambda is remembered keyed on `vm` (which
    // doesn't change across recompositions) so the reference identity stays
    // stable. The local-provider stack is staticCompositionLocalOf which
    // invalidates the WHOLE subtree on a value-identity change; without
    // remember, every wheel detent flipped these 11 references and forced
    // every card to recompose from scratch. With remember, the providers
    // hold the same lambda for the lifetime of the screen and Compose can
    // skip the subtree on most state changes.
    val onCycleLightMode = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> vm.cycleLightWheelMode(id) }
    }
    val onSetLightWheelMode = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId,
          mode: com.github.itskenny0.r1ha.core.ha.LightWheelMode -> vm.setLightWheelMode(id, mode) }
    }
    val onCycleLightEffect = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> vm.cycleLightEffect(id) }
    }
    val onSetLightEffect = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId, effect: String? -> vm.setLightEffect(id, effect) }
    }
    val onOpenEffectPicker = androidx.compose.runtime.remember(effectPickerFor) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> effectPickerFor.value = id }
    }
    val onMediaTransport = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId,
          action: com.github.itskenny0.r1ha.core.ha.MediaTransport -> vm.mediaTransport(id, action) }
    }
    val onOpenSelectPicker = androidx.compose.runtime.remember(selectPickerFor) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> selectPickerFor.value = id }
    }
    val onOpenFanPresetPicker = androidx.compose.runtime.remember(fanPresetPickerFor) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> fanPresetPickerFor.value = id }
    }
    val onOpenColorWheel = androidx.compose.runtime.remember(colorWheelFor) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId -> colorWheelFor.value = id }
    }
    val onCustomServiceCall = androidx.compose.runtime.remember(vm) {
        { domain: String, service: String, data: kotlinx.serialization.json.JsonObject ->
            vm.callRawService(domain, service, data)
        }
    }
    val onSetSelectOption = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId, option: String -> vm.setSelectOption(id, option) }
    }
    val onSetEntityPercent = androidx.compose.runtime.remember(vm) {
        { id: com.github.itskenny0.r1ha.core.ha.EntityId, pct: Int -> vm.setEntityPercent(id, pct) }
    }
    val onEntityCall = androidx.compose.runtime.remember(vm) {
        { call: com.github.itskenny0.r1ha.core.ha.ServiceCall -> vm.callService(call) }
    }
    // The DETAIL "..." affordance now lives on the card body (bottom-right, beside
    // the value bar) rather than the chrome row. We hand the scaffold a more-info
    // opener only when the DETAIL chrome button is enabled, so the existing toggle
    // still governs it; peek neighbours override this local back to null so only
    // the focused card shows the button.
    val detailEnabled = appSettings.ui.chromeButtons.any {
        it.ref == com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.DETAIL && it.enabled
    }
    val onCardMoreInfo: ((com.github.itskenny0.r1ha.core.ha.EntityId) -> Unit)? =
        if (detailEnabled) {
            { id -> moreInfoEntityId.value = id.value }
        } else {
            null
        }
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalHaRepository provides haRepository,
        com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl provides appSettings.server?.url,
        com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides appSettings.entityOverrides,
        com.github.itskenny0.r1ha.core.theme.LocalThemeAccentOverride provides appSettings.themeAccentArgb
            ?.let { androidx.compose.ui.graphics.Color(it) },
        com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightMode provides onCycleLightMode,
        com.github.itskenny0.r1ha.core.theme.LocalOnSetLightWheelMode provides onSetLightWheelMode,
        com.github.itskenny0.r1ha.core.theme.LocalOnCycleLightEffect provides onCycleLightEffect,
        com.github.itskenny0.r1ha.core.theme.LocalOnSetLightEffect provides onSetLightEffect,
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenEffectPicker provides onOpenEffectPicker,
        com.github.itskenny0.r1ha.core.theme.LocalOnMediaTransport provides onMediaTransport,
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenSelectPicker provides onOpenSelectPicker,
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenFanPresetPicker provides onOpenFanPresetPicker,
        com.github.itskenny0.r1ha.core.theme.LocalOnOpenColorWheel provides onOpenColorWheel,
        com.github.itskenny0.r1ha.core.theme.LocalOnCustomServiceCall provides onCustomServiceCall,
        com.github.itskenny0.r1ha.core.theme.LocalOnSetSelectOption provides onSetSelectOption,
        com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent provides onSetEntityPercent,
        com.github.itskenny0.r1ha.core.theme.LocalOnEntityCall provides onEntityCall,
        com.github.itskenny0.r1ha.core.theme.LocalOnCardMoreInfo provides onCardMoreInfo,
    ) {
    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        // No max-width cap on the card column. An earlier 600 dp clamp here
        // was meant to keep a card from stretching across a 1280 dp tablet,
        // but it letterboxed the cardstack on every wide display, leaving
        // the deck occupying roughly half the screen. Cards (and their
        // theme renderers) adapt naturally to any width via the existing
        // weight-based interior layout, so the cap was more harmful than
        // helpful. Matches the earlier fix that turned ResponsiveColumn
        // into a passthrough for the same reason.
        // Outer-scope card list for chrome counter, pip overlay, tutorial gate, and the
        // jump-picker / context-menu overlays. We bind to the raw reference-stable
        // [state.cards] rather than [state.displayedCards] here: the optimistic overlay
        // only rewrites percent / select-option on individual cards, never adds, removes
        // or reorders them, and none of these consumers render an optimistic-affected
        // value (chrome shows the count, the pip shows position, the jump rows show
        // friendly name + domain). Reading displayedCards at this hot outer scope would
        // re-run its mapping allocation on every wheel detent (optimisticPercents changes
        // each detent) for no visible difference. The per-card optimistic view still
        // flows to each EntityCard via PageDeck's own pageCards derivation.
        val cards = state.cards
        Box(modifier = Modifier.fillMaxSize()) {
        when {
            // Cold-start splash. DataStore is async on first read so for a brief
            // window the VM has its default state. Without this branch the user
            // momentarily saw the 'No favourites yet' EmptyState before the real
            // data arrived, which they read as a permanent error. Plain throbber,
            // no copy — once settings load we route into the horizontal pager.
            !state.settingsLoaded -> StartupSplash()
            state.pages.isEmpty() -> {
                // Defensive: settings loaded but pages list is empty (shouldn't
                // happen post-migration, but the migration runs on first read so a
                // half-loaded state could theoretically slip through here). Fall
                // through to the legacy single-deck rendering.
                val reconnectAt by haRepository.reconnectNextAttemptAtMillis
                    .collectAsStateWithLifecycle()
                EmptyState(
                    loading = state.favouritesCount > 0,
                    favouritesCount = state.favouritesCount,
                    connection = connection,
                    reconnectAt = reconnectAt,
                    onOpenFavoritesPicker = onOpenFavoritesPicker,
                    onOpenSettings = onOpenSettings,
                    onRetry = { haRepository.reconnectNow() },
                )
            }
            else -> {
                // Horizontal pager — one slot per FavoritePage. The user swipes
                // left/right to switch decks; the active page's id syncs back to
                // the VM so wheel routing and chrome state follow the visible
                // page. Each PageDeck holds its own VerticalPager state so a
                // swipe-away-and-back lands on the user's previous card.
                // pageIds + activePageIndex memoised. pageIds was being
                // rebuilt as a fresh List on every screen recomposition
                // even when state.pages was unchanged; the LaunchedEffect
                // keys then compared the new list to the old (structurally
                // equal, but it's still N comparisons) and the
                // rememberPagerState key() ran an equals check. Memoising
                // makes both no-op when pages haven't changed.
                // Peek-deck decision, resolved once per composition from the
                // window tier + orientation + the user's CardPeekMode setting.
                // Threaded into every PageDeck so the active deck and its
                // peek-composed neighbours all agree on the layout. effectivePeek
                // is a pure function (unit-tested) so this call site stays a thin
                // read of already-available inputs.
                val windowTier = LocalWindowTier.current.tier
                val isPortrait = androidx.compose.ui.platform.LocalConfiguration.current
                    .orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                // Raw window pixels (density- and ROM-independent) gate the R1 out of AUTO
                // peek: its 240 px panel can report a COMPACT-range width in dp, but never
                // clears the pixel floor. See PEEK_MIN_SHORTEST_SIDE_PX.
                val windowPx = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
                val peekDeck = effectivePeek(
                    mode = appSettings.ui.cardPeekMode,
                    tier = windowTier,
                    isPortrait = isPortrait,
                    shortestSidePx = minOf(windowPx.width, windowPx.height),
                )
                val pageIds = androidx.compose.runtime.remember(state.pages) {
                    state.pages.map { it.id }
                }
                val activePageIndex = androidx.compose.runtime.remember(
                    state.pages, state.activePageId,
                ) {
                    state.pages.indexOfFirst { it.id == state.activePageId }.coerceAtLeast(0)
                }
                // Rebuild the horizontal pager state whenever the page set changes
                // (add/delete/rename moves indices around). Keyed on the list of
                // ids so re-ordering ALSO rebuilds — otherwise the pager would
                // remember its previous currentPage while pageIds shifted under
                // it and we'd land on the wrong page.
                val horizontalPagerState = androidx.compose.runtime.key(pageIds) {
                    androidx.compose.foundation.pager.rememberPagerState(
                        initialPage = activePageIndex,
                        pageCount = { state.pages.size },
                    )
                }
                // Sync activePageId → horizontal pager: when the user taps a tab
                // chip or a page is added programmatically, animate the pager so
                // the chrome and the deck stay in lockstep.
                //
                // Compare against [targetPage] (where the pager is HEADING) not
                // [currentPage] (the dominant visible page). If the pager is
                // already animating toward the new active page (e.g., the
                // user is mid-swipe and snapshotFlow has pushed the new id
                // back to the VM), targetPage already equals idx and we
                // skip the redundant animate. This was the source of an
                // observable tab-flicker loop — without the targetPage
                // check, calling animateScrollToPage mid-fling could re-aim
                // the pager between two pages back and forth.
                androidx.compose.runtime.LaunchedEffect(
                    horizontalPagerState, state.activePageId, pageIds,
                ) {
                    val idx = state.pages.indexOfFirst { it.id == state.activePageId }
                    if (idx < 0) return@LaunchedEffect
                    // If a scroll is already in flight (user tapped a chip /
                    // pressed PAGE_LEFT/RIGHT / swiped), the original source
                    // already chose a target and is animating toward it.
                    // Calling animateScrollToPage again here cancels that
                    // in-flight animation and re-aims from the current visual
                    // offset — on rapid input that left the pager stranded
                    // an extra page short, which the user perceived as a
                    // "back one page" race. Skip and trust the existing
                    // scroll; settledPage will write activePageId back when
                    // it lands.
                    if (horizontalPagerState.isScrollInProgress) return@LaunchedEffect
                    if (idx != horizontalPagerState.targetPage) {
                        horizontalPagerState.animateScrollToPage(idx)
                    }
                }
                // Sync horizontal pager → activePageId: when the user swipes to a
                // different page, push the new active id back into the VM so the
                // tab strip's active highlight follows and wheel routing targets
                // the visible deck. Fires a CLOCK_TICK haptic on settle when the
                // user's "Haptic feedback" setting is on, giving the swipe a
                // tactile end-state to match the wheel and card-swipe haptics.
                // Skips the first emission so opening the screen doesn't fire
                // a phantom haptic for the initial-page settle.
                androidx.compose.runtime.LaunchedEffect(horizontalPagerState, pageIds) {
                    var firstSettle = true
                    snapshotFlow { horizontalPagerState.settledPage }
                        .distinctUntilChanged()
                        .collect { idx ->
                            val pageId = state.pages.getOrNull(idx)?.id
                            if (pageId != null && pageId != state.activePageId) {
                                vm.setActivePage(pageId)
                                if (!firstSettle && appSettings.behavior.haptics) {
                                    // Route through R1Haptic so the pager-settle tick fires
                                    // reliably on vendor ROMs that mute performHapticFeedback.
                                    cardStackHaptic.tick(view)
                                }
                            }
                            firstSettle = false
                        }
                }
                // Mirror the horizontal pager's animation state into the
                // screen-level gate read by the wheel handler. Without
                // this, wheel events fired during a tab fling land on
                // the previous tab's active card instead of the one the
                // user just swiped to.
                androidx.compose.runtime.LaunchedEffect(horizontalPagerState) {
                    snapshotFlow { horizontalPagerState.isScrollInProgress }
                        .distinctUntilChanged()
                        .collect { horizontalPagerAnimating.value = it }
                }
                // Tab-chip tap consumer: drives the pager directly. Same
                // child-Job cancellation pattern as the hardware key handler
                // so that a rapid sequence of taps (re-targeting mid-
                // animation) cancels the prior scroll cleanly rather than
                // leaving an in-flight animateScrollToPage CancellationException
                // to kill the collector.
                androidx.compose.runtime.LaunchedEffect(horizontalPagerState, pageIds) {
                    var tabScrollJob: kotlinx.coroutines.Job? = null
                    tabTapRequests.collect { id ->
                        val targetIdx = state.pages.indexOfFirst { it.id == id }
                        if (targetIdx >= 0 && targetIdx != horizontalPagerState.targetPage) {
                            tabScrollJob?.cancel()
                            tabScrollJob = launch {
                                horizontalPagerState.animateScrollToPage(targetIdx)
                            }
                        }
                    }
                }
                // Hardware-key actions that target the card stack: CARD_UP/DOWN
                // push a ±1 delta into the same pagerNavRequests channel the
                // wheel uses (so the active PageDeck animates its VerticalPager
                // by one card), and PAGE_LEFT/RIGHT animate the horizontal
                // pager directly. Scoped to this composable so it auto-cancels
                // when the screen leaves composition.
                //
                // Each page-scroll runs in its own child coroutine, NOT inside
                // the collect lambda. Two reasons:
                //
                //   1. animateScrollToPage suspends for ~300 ms; if the user
                //      fires a second key press while the first is animating,
                //      the second event sits in the bus buffer until the first
                //      completes and a sequential collector would lag behind
                //      every fast input.
                //
                //   2. animateScrollToPage cancels any in-flight scroll on the
                //      same PagerState — that cancellation surfaces as a
                //      CancellationException. If it propagates out of the
                //      collect lambda, the entire collector dies and PAGE_LEFT
                //      / PAGE_RIGHT stop responding entirely until the
                //      composable recomposes. (Wheel scroll keeps working
                //      because it goes through a separate, non-suspending
                //      pagerNavRequests channel.) Launching into a child Job
                //      and cancelling it explicitly on the next request keeps
                //      the cancellation scoped to that child, never the
                //      collector itself.
                androidx.compose.runtime.LaunchedEffect(horizontalPagerState) {
                    var pageScrollJob: kotlinx.coroutines.Job? = null
                    com.github.itskenny0.r1ha.core.input.KeyActionBus.events.collect { action ->
                        when (action) {
                            com.github.itskenny0.r1ha.core.input.KeyAction.CARD_UP ->
                                pagerNavRequests.tryEmit(-1)
                            com.github.itskenny0.r1ha.core.input.KeyAction.CARD_DOWN ->
                                pagerNavRequests.tryEmit(+1)
                            com.github.itskenny0.r1ha.core.input.KeyAction.PAGE_LEFT -> {
                                pageScrollJob?.cancel()
                                pageScrollJob = launch {
                                    // Compute the next target relative to where
                                    // the pager is HEADING (targetPage), not
                                    // where it currently sits (currentPage).
                                    // currentPage lags during an in-flight
                                    // animation, so a rapid second PAGE_LEFT
                                    // would otherwise read the same source
                                    // page as the first and re-aim at the
                                    // same target — the user spam-presses and
                                    // only advances once.
                                    val from = horizontalPagerState.targetPage
                                    val target = (from - 1).coerceAtLeast(0)
                                    if (target != from) {
                                        horizontalPagerState.animateScrollToPage(target)
                                    }
                                }
                            }
                            com.github.itskenny0.r1ha.core.input.KeyAction.PAGE_RIGHT -> {
                                pageScrollJob?.cancel()
                                pageScrollJob = launch {
                                    val last = (state.pages.size - 1).coerceAtLeast(0)
                                    val from = horizontalPagerState.targetPage
                                    val target = (from + 1).coerceAtMost(last)
                                    if (target != from) {
                                        horizontalPagerState.animateScrollToPage(target)
                                    }
                                }
                            }
                            com.github.itskenny0.r1ha.core.input.KeyAction.ACTIVATE ->
                                vm.tapToggle()
                            else -> Unit
                        }
                    }
                }
                androidx.compose.foundation.pager.HorizontalPager(
                    state = horizontalPagerState,
                    modifier = Modifier.fillMaxSize(),
                    // Pre-compose one page on each side of the visible one so
                    // a swipe between tabs reveals fully-rendered cards
                    // immediately. The crash trace identified the pip-thumb
                    // spring overshoot as the cause, not this peek; restored.
                    beyondViewportPageCount = 1,
                    // Stable per-page key = the page's id (a UUID), not the slot
                    // index. Without it Compose matches pages positionally, so a
                    // page insert / delete / reorder threw away every PageDeck's
                    // saved sub-composition (its VerticalPager state, card layout)
                    // and rebuilt from scratch. Keying on id lets the pager carry
                    // an unchanged page's deck across a tab-set mutation. Falls
                    // back to the index for the rare out-of-range slot.
                    key = { pageIdx -> state.pages.getOrNull(pageIdx)?.id ?: pageIdx },
                ) { pageIdx ->
                    val page = state.pages.getOrNull(pageIdx) ?: return@HorizontalPager
                    val pageCardsRaw = state.cardsByPage[page.id].orEmpty()
                    // Apply optimistic overrides to this page's cards so wheel
                    // changes track instantly even when the page becomes active
                    // mid-edit. Same path the legacy displayedCards used; just
                    // scoped per-page.
                    //
                    // Memoised — the prior version allocated a fresh
                    // List<EntityState> via .map { ... } on every recomp,
                    // including the ones HorizontalPager peek triggered for
                    // both neighbour pages. With beyondViewportPageCount=1,
                    // three pages re-derived their list on every wheel
                    // detent at 50 Hz. remember keyed on the raw list +
                    // optimistic map identity means we only re-map when
                    // either actually changes; the no-optimistic case
                    // returns the existing list reference verbatim.
                    val isActive = page.id == state.activePageId
                    // Only the active page shows the wheel-driven optimistic overrides; peek
                    // neighbours never receive wheel events, so paying the per-detent re-map
                    // cost on them just churns recompositions of cards the user isn't even
                    // touching. Gating saves N-cards re-allocation × 2 peek neighbours per
                    // wheel detent during sustained spins (R1's perf-critical path).
                    val pageCards = androidx.compose.runtime.remember(
                        pageCardsRaw, if (isActive) state.optimisticPercents else null,
                    ) {
                        if (!isActive || state.optimisticPercents.isEmpty()) {
                            pageCardsRaw
                        } else {
                            pageCardsRaw.map { card ->
                                val overridePct = state.optimisticPercents[card.id]
                                if (overridePct != null) {
                                    if (card.supportsScalar) card.copy(percent = overridePct)
                                    else card.copy(percent = overridePct, isOn = overridePct > 0)
                                } else {
                                    card
                                }
                            }
                        }
                    }
                    if (pageCards.isEmpty()) {
                        val reconnectAt by haRepository.reconnectNextAttemptAtMillis
                            .collectAsStateWithLifecycle()
                        EmptyState(
                            loading = page.favorites.isNotEmpty(),
                            favouritesCount = page.favorites.size,
                            connection = connection,
                            reconnectAt = reconnectAt,
                            onOpenFavoritesPicker = onOpenFavoritesPicker,
                            onOpenSettings = onOpenSettings,
                            onRetry = { haRepository.reconnectNow() },
                        )
                    } else {
                        PageDeck(
                            pageId = page.id,
                            cards = pageCards,
                            initialIndex = state.indexByPage[page.id] ?: 0,
                            isActive = isActive,
                            peekDeck = peekDeck,
                            vm = vm,
                            appSettings = appSettings,
                            navRequests = pagerNavRequests,
                            jumpRequests = jumpRequests,
                            lightWheelModes = state.lightWheelMode,
                            // Only the active deck's pager state gates the
                            // wheel — neighbour decks (peek-composed via
                            // beyondViewportPageCount = 1) animate
                            // independently and shouldn't affect input
                            // routing on the page the user is actually
                            // looking at.
                            onActivePagerAnimatingChange = { animating ->
                                verticalPagerAnimating.value = animating
                            },
                        )
                    }
                }
            }
        }

        // Top chrome stack: ChromeRow on top, TabStrip directly under it. The two
        // are siblings inside the outer Box so the page chips sit above the active
        // card without affecting the pager's contentPadding (which is already
        // tuned for ChromeRow's 64 dp tall area). When there's only one page the
        // strip is empty visual chrome — collapses to zero height. Hidden during
        // the cold-start splash so the user sees a clean throbber and not chrome
        // perched above a loading spinner.
        if (state.settingsLoaded) androidx.compose.foundation.layout.Column(
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // Effective position-pip slot for the currently-active card.
            // Per-card override wins; otherwise inherit the global setting.
            // Recomputed per recomposition so a wheel-driven page change
            // (which swaps activeState) immediately moves the pip if the
            // new card carries its own override.
            val activePip = state.activeState?.id?.value
                ?.let { appSettings.entityOverrides[it]?.positionDotLocation }
                ?: appSettings.ui.positionDotLocation
            ChromeRow(
                connection = connection,
                wsSilent = wsSilent,
                cardsCount = cards.size,
                currentIndex = state.currentIndex,
                // Pip only renders in the chrome row when the effective
                // position is TOP_CENTER (the historical default). All other
                // positions get a screen-level overlay outside the chrome
                // column so the pip can sit at corner / mid-edge positions
                // without affecting the chrome row's right-cluster layout.
                showCounter = cards.size > 1 &&
                    activePip == com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_CENTER,
                onOpenFavoritesPicker = onOpenFavoritesPicker,
                onOpenSettings = onOpenSettings,
                onEditActive = {
                    // Only allow editing when there's an active card to edit — empty deck
                    // is a no-op.
                    state.activeState?.let { customizingId.value = it.id.value }
                },
                onMoreInfoActive = {
                    // Open the ultra-detail more-info sheet for the focused card. An
                    // explicit user tap always opens it (the per-card moreInfoEnabled
                    // flag governs only the implicit wheel-open path); empty deck no-ops.
                    state.activeState?.id?.value?.let { moreInfoEntityId.value = it }
                },
                onTapCounter = { jumpPickerOpen.value = true },
                onTapHamburger = {
                    // Ignore a hamburger tap that lands while the deck is still animating
                    // in from a back navigation (the NavBackStackEntry isn't RESUMED yet).
                    // Opening the slide-out / modal mid-transition raced the NavHost's
                    // cross-fade and could strand the app on a black screen needing a
                    // restart; once the transition settles the tap behaves normally.
                    if (navEntryLifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                        // Slide-out when the shell offers it (SLIDEOUT style on a portrait tier);
                        // otherwise the QuickActions modal, which long-press also opens.
                        if (navDrawer?.available == true) navDrawer.open() else quickActionsOpen.value = true
                    }
                },
                onLongPressHamburger = { quickActionsOpen.value = true },
                onLongPressGear = onOpenSearch,
                onOpenAssist = onOpenAssist,
                solidBackdrop = appSettings.ui.hideCardTailAbove,
                // Battery indicator surfaces only when the system status bar is hidden
                // AND the user explicitly opted in — otherwise the system bar already
                // shows battery and we'd be redundant.
                showBatteryIndicator = appSettings.behavior.hideStatusBar &&
                    appSettings.behavior.showBatteryWhenStatusBarHidden,
                onOpenDevice = onOpenDevice,
                chromeButtons = appSettings.ui.chromeButtons,
            )
            // Tab strip — chip per page. Tap to switch active. Long-press opens a
            // management overlay (add / rename / delete). The '+' chip on the
            // right is the discovery surface for adding more pages, so the strip
            // is rendered whenever there's at least one page (always, post-
            // migration). On single-page installs the user just sees "HOME" plus
            // the '+' chip — a low-noise hint that more pages are possible.
            if (appSettings.pages.isNotEmpty()) {
                TabStrip(
                    pages = appSettings.pages,
                    activePageId = appSettings.activePageId,
                    onTapPage = { id ->
                        // Route through the shared flow rather than the
                        // settings.update → state.activePageId → LaunchedEffect
                        // round-trip. That round-trip's LaunchedEffect cancels
                        // its prior animateScrollToPage on every activePageId
                        // change, and the cancellation left rapid-tap sequences
                        // stranded one page short — what the user perceived as
                        // "back one page." vm.setActivePage still fires so the
                        // active tab highlight updates immediately; the
                        // LaunchedEffect that observes activePageId is now
                        // gated by isScrollInProgress and stays out of the way
                        // while the pager is already animating from here.
                        tabTapRequests.tryEmit(id)
                        vm.setActivePage(id)
                    },
                    onLongPressPage = { id -> tabManagementForId.value = id },
                    onAddPage = { tabManagementForId.value = NEW_PAGE_SENTINEL },
                    onReorder = { from, to -> vm.reorderPages(from, to) },
                    solidBackdrop = appSettings.ui.hideCardTailAbove,
                )
            }
            // Guest-mode banner — small read-only indicator surfaced
            // immediately below the tab strip so the user has a constant
            // visual reminder that the app won't fire service calls. Tap
            // jumps to Settings so they can flip it off if they actually
            // wanted to control something.
            if (appSettings.guestModeEnabled) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(R1.AccentWarm.copy(alpha = 0.18f))
                        .r1Pressable(onClick = onOpenSettings)
                        .padding(horizontal = 14.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "READ ONLY  ·  TAP TO DISABLE",
                        style = R1.labelMicro,
                        color = R1.AccentWarm,
                    )
                }
            }
        }

        // ── Position-pip screen overlay ─────────────────────────────────────────────
        // When the effective position-pip slot for the active card is anything
        // OTHER than TOP_CENTER (the chrome-row default), we render the pip as a
        // screen-level overlay aligned to the chosen slot. HIDDEN omits the
        // overlay entirely; TOP_CENTER falls through to ChromeRow's existing
        // pip rendering above. Per-card overrides win over the global setting,
        // so changing the active card can move the pip mid-flight — that's the
        // point: a card whose layout collides with the global slot can move
        // the pip out of the way without changing the deck-wide default.
        if (state.settingsLoaded && cards.size > 1) {
            val activeId = state.activeState?.id?.value
            val effectivePip = activeId
                ?.let { appSettings.entityOverrides[it]?.positionDotLocation }
                ?: appSettings.ui.positionDotLocation
            val pipAlignment: Alignment? = when (effectivePip) {
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_CENTER -> null
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.HIDDEN -> null
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_LEFT -> Alignment.TopStart
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_RIGHT -> Alignment.TopEnd
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.LEFT_CENTER -> Alignment.CenterStart
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.RIGHT_CENTER -> Alignment.CenterEnd
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_LEFT -> Alignment.BottomStart
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_CENTER -> Alignment.BottomCenter
                com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_RIGHT -> Alignment.BottomEnd
            }
            if (pipAlignment != null) {
                // Outer Box uses statusBarsPadding + navigationBarsPadding so
                // the pip never sits under the system bars on devices that
                // don't have edge-to-edge themed (the R1 itself has no
                // visible bars, but the phone/tablet builds do). Inner
                // padding keeps the pip a few dp away from the screen edge
                // for visual breathing room.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    contentAlignment = pipAlignment,
                ) {
                    VerticalPagePip(
                        count = cards.size,
                        current = state.currentIndex,
                        onClick = { jumpPickerOpen.value = true },
                    )
                }
            }
        }

        // ── Wheel-no-op hint ────────────────────────────────────────────────────────
        // When the active card has nothing for the wheel to drive (sensors, actions,
        // non-scalar switches when the toggle setting is off) the wheel becomes a
        // no-op. Surface a transient hint so the user learns to swipe or tap the pip
        // to navigate, rather than wondering why the wheel does nothing. Auto-fades
        // after 2 s of no fresh wheel events.
        // PERF: pass the MutableLongState itself, not its value — so the
        // .longValue State read happens INSIDE WheelHintOverlay's scope.
        // Reading .longValue at the call site here subscribed the WHOLE
        // CardStackScreen to wheelHintAt changes, which meant every wheel
        // event on a sensor/action card (which is when wheelHintAt fires)
        // recomposed the whole card-stack. Pushing the read into the
        // overlay's scope isolates the subscription.
        WheelHintOverlay(state = wheelHintAt)

        // First-launch tutorial sticker. Fresh installs land on the card stack
        // with no obvious indication that the wheel is the primary input; this
        // small bottom-aligned hint dismisses on the first wheel event (handled
        // in the collect block above via the wheelTutorialSeen flag flip) OR
        // on tap. Only shows when there's actually content to interact with.
        if (!appSettings.behavior.wheelTutorialSeen && cards.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 18.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentWarm.copy(alpha = 0.18f))
                        .border(1.dp, R1.AccentWarm.copy(alpha = 0.55f), R1.ShapeS)
                        .r1Pressable(
                            onClick = {
                                scope.launch {
                                    settings.update { s ->
                                        s.copy(behavior = s.behavior.copy(wheelTutorialSeen = true))
                                    }
                                }
                            },
                            contentDescription = "Dismiss wheel tutorial hint",
                        )
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                ) {
                    // Two-line hint: primary affordance on top, phone fallback
                    // (volume rocker maps to the same wheel keycodes via
                    // KEYCODE_VOLUME_UP/DOWN) on the secondary line so users
                    // running the app on a regular Android phone aren't left
                    // wondering which physical input drives the cards.
                    androidx.compose.foundation.layout.Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "↻ SPIN WHEEL TO ADJUST",
                            style = R1.labelMicro,
                            color = R1.AccentWarm,
                        )
                        Text(
                            text = "OR USE VOLUME UP / DOWN",
                            style = R1.labelMicro,
                            color = R1.AccentWarm.copy(alpha = 0.75f),
                        )
                    }
                }
            }
        }

        } // end card-content island (max 600 dp on wide screens)

        // ── Overlays — rendered at the outer full-screen Box level so they cover the
        // full display regardless of the card island width. ─────────────────────────

        // ── Customize dialog ────────────────────────────────────────────────────────
        // Reuses the favourites-picker's RenameDialog so the customize flow is identical
        // from both entry points. Renders OVER the chrome since it's part of the screen-
        // level Box stack here, not inside any pager content.
        val editingId = customizingId.value
        if (editingId != null) {
            val entity = state.displayedCards.firstOrNull { it.id.value == editingId }
                ?: state.cards.firstOrNull { it.id.value == editingId }
            if (entity != null) {
                val initialOverride = appSettings.entityOverrides[editingId]
                    ?: com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE
                // Seed from the raw override only (empty when none is set) so a
                // SAVE without editing doesn't persist friendly_name as a no-op
                // override. The dialog shows friendly_name as the placeholder and
                // treats blank as "no override". Mirrors FavoritesPickerScreen.
                val initialName = appSettings.nameOverrides[editingId].orEmpty()
                com.github.itskenny0.r1ha.feature.favoritespicker.RenameDialog(
                    entity = entity,
                    initialName = initialName,
                    initialOverride = initialOverride,
                    onSave = { newName, newOverride ->
                        vm.saveCustomize(editingId, newName, newOverride)
                        customizingId.value = null
                    },
                    onCancel = { customizingId.value = null },
                )
            } else {
                // Stale id (entity removed from favourites while dialog was open) — drop it.
                customizingId.value = null
            }
        }

        // ── Effect picker overlay ───────────────────────────────────────────────────
        // Renders ABOVE the chrome (this Box stack draws bottom-up) so the picker
        // covers everything, not just the card body. Active entity is looked up in
        // displayedCards by id — if it's no longer present (e.g. user un-favourited
        // mid-pick) we close instead of rendering an empty list.
        val pickerEntityId = effectPickerFor.value
        if (pickerEntityId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == pickerEntityId }
                ?: state.cards.firstOrNull { it.id == pickerEntityId }
            if (entity != null && entity.effectList.isNotEmpty()) {
                com.github.itskenny0.r1ha.core.theme.EffectPickerSheet(
                    entityId = pickerEntityId,
                    current = entity.effect,
                    effects = entity.effectList,
                    accent = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                    onPick = { effect ->
                        vm.setLightEffect(pickerEntityId, effect)
                        effectPickerFor.value = null
                    },
                    onDismiss = { effectPickerFor.value = null },
                )
            } else {
                effectPickerFor.value = null
            }
        }

        // ── Select-option picker overlay ────────────────────────────────────────────
        // Same shape as the effect picker — fullscreen list, tap to apply, system-back
        // / CLOSE chip to dismiss. Mirrors the effect-picker pattern rather than
        // building a second variant; the only difference at render time is the source
        // of the list (entity.selectOptions vs. entity.effectList) and the apply
        // callback. The picker sheet is reused as-is via [SelectPickerSheet].
        val selectId = selectPickerFor.value
        if (selectId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == selectId }
                ?: state.cards.firstOrNull { it.id == selectId }
            if (entity != null && entity.selectOptions.isNotEmpty()) {
                com.github.itskenny0.r1ha.core.theme.SelectPickerSheet(
                    entityId = selectId,
                    current = entity.currentOption,
                    options = entity.selectOptions,
                    accent = com.github.itskenny0.r1ha.core.theme.R1.AccentCool,
                    onPick = { option ->
                        vm.setSelectOption(selectId, option)
                        selectPickerFor.value = null
                    },
                    onDismiss = { selectPickerFor.value = null },
                )
            } else {
                selectPickerFor.value = null
            }
        }

        // ── Fan preset-mode picker overlay ──────────────────────────────────────────
        // Same shape as the select / effect pickers. Reads the entity's current
        // preset_mode + preset_modes list from the cards; on pick fires
        // fan.set_preset_mode via the same callRawService path the panel chips used.
        val fanPresetId = fanPresetPickerFor.value
        if (fanPresetId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == fanPresetId }
                ?: state.cards.firstOrNull { it.id == fanPresetId }
            if (entity != null && entity.fanPresetModes.isNotEmpty()) {
                com.github.itskenny0.r1ha.core.theme.FanPresetPickerSheet(
                    entityId = fanPresetId,
                    current = entity.fanPresetMode,
                    presets = entity.fanPresetModes,
                    accent = com.github.itskenny0.r1ha.core.theme.R1.AccentWarm,
                    onPick = { preset ->
                        vm.callService(com.github.itskenny0.r1ha.core.ha.ServiceCall.setPresetMode(fanPresetId, preset))
                        fanPresetPickerFor.value = null
                    },
                    onDismiss = { fanPresetPickerFor.value = null },
                )
            } else {
                fanPresetPickerFor.value = null
            }
        }

        // ── Colour-wheel overlay ────────────────────────────────────────────────────
        // Spawned by the HUE mode button on light cards. Same hosting shape as the
        // effect picker; the picker content is the live HS colour wheel instead of a
        // list. Current hs is read fresh from the entity each recomposition, so when
        // the user is NOT dragging, the thumb tracks HA's echoed hs_color, including
        // changes made by the R1's physical wheel, which keeps cycling hue while this
        // overlay is up (the HUE button set the wheel mode before opening us).
        val wheelEntityId = colorWheelFor.value
        if (wheelEntityId != null) {
            val entity = state.displayedCards.firstOrNull { it.id == wheelEntityId }
                ?: state.cards.firstOrNull { it.id == wheelEntityId }
            if (entity != null) {
                val hs = com.github.itskenny0.r1ha.ui.components.hsFromAttributes(entity.attributesJson)
                com.github.itskenny0.r1ha.ui.components.ColorWheelOverlaySheet(
                    title = entity.friendlyName.ifBlank { entity.id.value },
                    // Saturation 0 (centred / white thumb) when the bulb isn't
                    // currently reporting a colour; a neutral starting point that
                    // doesn't pretend the bulb is red.
                    hue = hs?.first ?: entity.hue?.toFloat() ?: 0f,
                    saturation = hs?.second ?: 0f,
                    // Per-move updates go through the VM's hs debouncer (~6-8
                    // calls/sec mid-drag, exact value on the trailing edge), so we
                    // can forward every position without flooding HA.
                    onHsChange = { h, s -> vm.setLightHs(wheelEntityId, h.toDouble(), (s * 100f).toDouble()) },
                    onHsChangeFinished = { h, s -> vm.setLightHs(wheelEntityId, h.toDouble(), (s * 100f).toDouble()) },
                    onDismiss = { colorWheelFor.value = null },
                )
            } else {
                colorWheelFor.value = null
            }
        }

        // ── Jump-to-card overlay ────────────────────────────────────────────────────
        // Opens from a tap on the chrome's position pip — lists every card in the
        // deck by friendly name so the user can hop straight to an entity by name
        // instead of scrolling. In infinite-scroll mode we land on the nearest
        // virtual page that maps to the chosen index (relative to current page) so
        // the wrap-around scroll stays seamless; in finite mode we just animate to
        // that page directly.
        // Per-row context menu opened by long-pressing a JumpRow. Holds the index
        // of the card whose menu is open; null = closed. Lifted to screen scope
        // so the menu can render above the JumpToCardSheet itself (matches the
        // pattern used by [tabManagementForId]).
        val cardContextMenuIdx = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf<Int?>(null)
        }
        // moreInfoEntityId is hoisted to screen scope above (so the wheel
        // handler can both gate on and open it). Opened from the card context
        // menu's MORE INFO action and from a wheel-spin on a read-only card.
        if (jumpPickerOpen.value && cards.size > 1) {
            JumpToCardSheet(
                cards = cards,
                currentIndex = state.currentIndex,
                listState = jumpListState,
                onPick = { targetIdx ->
                    // Publish the target into [jumpRequests]; the active page's
                    // PageDeck collects it and animates its VerticalPager to the
                    // matching virtual / real page. Decoupling from a hoisted
                    // pagerState lets every page hold its own state.
                    pagerScope.launch { jumpRequests.emit(targetIdx) }
                    jumpPickerOpen.value = false
                },
                onReorder = { from, to -> vm.reorderFavorite(from, to) },
                onOpenMenu = { idx -> cardContextMenuIdx.value = idx },
                onDismiss = { jumpPickerOpen.value = false },
            )
        }

        // Context menu on the long-pressed JumpRow. Surfaces page-move actions
        // and a duplicate of the remove affordance in a focused modal. Hidden
        // when there's only one page (nowhere to move to AND remove already on
        // the row) so the long-press is a no-op rather than opening an empty
        // sheet.
        val ctxIdx = cardContextMenuIdx.value
        if (ctxIdx != null) {
            val ctxCard = cards.getOrNull(ctxIdx)
            if (ctxCard == null) {
                cardContextMenuIdx.value = null
            } else {
                val ctxContext = androidx.compose.ui.platform.LocalContext.current
                CardContextMenu(
                    entityName = ctxCard.friendlyName,
                    entityId = ctxCard.id.value,
                    pages = appSettings.pages,
                    sourcePageId = appSettings.activePageId,
                    haServerUrl = appSettings.server?.url,
                    onMove = { targetPageId ->
                        vm.moveFavoriteToPage(ctxCard.id.value, targetPageId)
                        cardContextMenuIdx.value = null
                    },
                    onRemove = {
                        vm.removeFavorite(ctxCard.id.value)
                        cardContextMenuIdx.value = null
                    },
                    onOpenInHa = { url ->
                        runCatching {
                            ctxContext.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                        cardContextMenuIdx.value = null
                    },
                    onMoreInfo = run {
                        // Effective per-entity flag = override ?: global default.
                        val effective = appSettings.entityOverrides[ctxCard.id.value]?.moreInfoEnabled
                            ?: appSettings.ui.moreInfoEnabledDefault
                        if (effective) {
                            {
                                moreInfoEntityId.value = ctxCard.id.value
                                cardContextMenuIdx.value = null
                            }
                        } else null
                    },
                    onDismiss = { cardContextMenuIdx.value = null },
                )
            }
        }

        // ── Ultra-detail more-info sheet ────────────────────────────────────────────
        // Opened from the card context menu's MORE INFO action (which only
        // offers itself when the effective per-entity moreInfoEnabled is true).
        // Rendered at the outer full-screen Box level so it covers the chrome
        // and card, matching the other picker overlays.
        val moreInfoId = moreInfoEntityId.value
        if (moreInfoId != null) {
            com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoSheet(
                haRepository = haRepository,
                settings = settings,
                entityId = moreInfoId,
                onDismiss = { moreInfoEntityId.value = null },
                // "Show more" in the history embed opens the native History screen
                // for the entity (the card stack already wires onOpenHistory).
                onOpenHistory = { eid -> onOpenHistory(eid) },
                // "Browse media" opens the media-browse screen with the player
                // preselected.
                onOpenMediaBrowse = { eid -> onOpenMediaBrowseFor(eid) },
                // Logbook "Show more" opens the native Logbook scoped to the entity.
                onOpenLogbook = { eid -> onOpenLogbook(eid) },
            )
        }

        // ── Tab manage modal ────────────────────────────────────────────────────────
        // Opened from a long-press on a page chip (edit mode) or a tap on the '+'
        // chip (add mode, signalled by NEW_PAGE_SENTINEL). The dialog renders ABOVE
        // every other overlay in this Box stack so the user can never lose track of
        // it behind the chrome or a picker sheet.
        val manageId = tabManagementForId.value
        if (manageId != null) {
            val targetPage = if (manageId == NEW_PAGE_SENTINEL) null
                else appSettings.pages.firstOrNull { it.id == manageId }
            val targetIdx = appSettings.pages.indexOfFirst { it.id == targetPage?.id }
            TabManageDialog(
                isAdd = manageId == NEW_PAGE_SENTINEL,
                page = targetPage,
                canDelete = appSettings.pages.size > 1,
                canMoveLeft = targetIdx > 0,
                canMoveRight = targetIdx >= 0 && targetIdx < appSettings.pages.lastIndex,
                onAdd = { name ->
                    vm.addPage(name)
                    tabManagementForId.value = null
                },
                onGenerateFromAreas = {
                    vm.generatePagesFromAreas()
                    tabManagementForId.value = null
                },
                onRename = { id, name ->
                    vm.renamePage(id, name)
                    tabManagementForId.value = null
                },
                onDelete = { id ->
                    vm.deletePage(id)
                    tabManagementForId.value = null
                },
                onMoveLeft = { id -> vm.movePageLeft(id) },
                onMoveRight = { id -> vm.movePageRight(id) },
                onSetAccent = { id, argb -> vm.setPageAccent(id, argb) },
                onSetIcon = { id, icon -> vm.setPageIcon(id, icon) },
                onDismiss = { tabManagementForId.value = null },
            )
        }

        // Quick-actions sheet — currently only 'all off' on the active page.
        // Long-press the chrome's hamburger to open. Two-stage confirm via
        // armed/commit pattern (same as page delete) since this fires N
        // service calls and the user can't undo with one tap.
        if (quickActionsOpen.value) {
            val activePageCards = state.cardsByPage[appSettings.activePageId].orEmpty()
            val playingMediaCount = activePageCards.count { ent ->
                ent.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER &&
                    ent.rawState?.equals("playing", ignoreCase = true) == true
            }
            // ── Context-aware GO TO jumps ────────────────────────────────────
            // Build a short list of quick-jumps tailored to the focused card's
            // domain so the user can hop straight to the surface that makes
            // sense for what they're looking at (a media_player → Media Browse,
            // a person → Persons, weather → Weather, climate/sensor → that
            // entity's History). Always-available broad jumps (Cameras, History
            // for the focused entity) round out the row. Each closes the sheet
            // first, then navigates — same pattern as the BROWSE shortcuts.
            val focused = state.activeState
            val close: (() -> Unit) -> Unit = { nav -> quickActionsOpen.value = false; nav() }
            val contextJumps = buildList {
                if (focused != null) {
                    when (focused.id.domain) {
                        com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER ->
                            add(QuickJump("♫", "MEDIA") { close(onOpenMediaBrowse) })
                        com.github.itskenny0.r1ha.core.ha.Domain.PERSON ->
                            add(QuickJump("☻", "PERSONS") { close(onOpenPersons) })
                        com.github.itskenny0.r1ha.core.ha.Domain.WEATHER ->
                            add(QuickJump("☀", "WEATHER") { close(onOpenWeather) })
                        else -> Unit
                    }
                    // History for the focused entity — most useful on the
                    // numeric/stateful domains (climate, sensors, binary
                    // sensors, person presence) where a trend matters.
                    val d = focused.id.domain
                    val historyWorthwhile = d.isSensor ||
                        d == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                        d == com.github.itskenny0.r1ha.core.ha.Domain.MEDIA_PLAYER
                    if (historyWorthwhile) {
                        val eid = focused.id.value
                        add(QuickJump("◷", "HISTORY") { close { onOpenHistory(eid) } })
                    }
                }
                // Always-available broad jump: Cameras isn't a card archetype,
                // so it never appears as a focused domain — surface it here so
                // the deck can still reach the camera wall.
                add(QuickJump("▣", "CAMERAS") { close(onOpenCameras) })
            }
            QuickActionsSheet(
                activePageName = appSettings.pages.firstOrNull { it.id == appSettings.activePageId }?.name
                    ?: "this page",
                cardCount = state.cards.size,
                playingMediaCount = playingMediaCount,
                focusedName = focused?.friendlyName,
                contextJumps = contextJumps,
                // Pinned Lovelace views — the phone's stand-in for the tablet nav rail's
                // dashboard pins. Each closes the sheet then navigates to its route.
                pinnedDashboards = appSettings.navPanel.pinnedDashboards,
                // Pinned nav surfaces — the phone's stand-in for the tablet nav rail's
                // pinned-surface tier. Resolve the persisted route-id list to renderable
                // surfaces (dropping any unknown ids written by a newer build).
                pinnedSurfaces = com.github.itskenny0.r1ha.nav.PinnableSurfaces.resolve(
                    appSettings.navPanel.pinnedSurfaces,
                ),
                onOpenDashboardRoute = { route ->
                    quickActionsOpen.value = false
                    onOpenDashboardRoute(route)
                },
                onOpenRoute = { route ->
                    quickActionsOpen.value = false
                    onOpenRoute(route)
                },
                onOpenDashboard = {
                    quickActionsOpen.value = false
                    onOpenDashboard()
                },
                onOpenAssist = {
                    quickActionsOpen.value = false
                    onOpenAssist()
                },
                onOpenSearch = {
                    quickActionsOpen.value = false
                    onOpenSearch()
                },
                onOpenAutomations = {
                    quickActionsOpen.value = false
                    onOpenAutomations()
                },
                onOpenEnergy = {
                    quickActionsOpen.value = false
                    onOpenEnergy()
                },
                onOpenScenes = {
                    quickActionsOpen.value = false
                    onOpenScenes()
                },
                onOpenNotifications = {
                    quickActionsOpen.value = false
                    onOpenNotifications()
                },
                onOpenZones = {
                    quickActionsOpen.value = false
                    onOpenZones()
                },
                onOpenDevice = {
                    quickActionsOpen.value = false
                    onOpenDevice()
                },
                onAllOn = {
                    vm.turnOnActivePage()
                    quickActionsOpen.value = false
                },
                onAllOff = {
                    vm.turnOffActivePage()
                    quickActionsOpen.value = false
                },
                onPauseMedia = {
                    vm.pauseAllMedia()
                    quickActionsOpen.value = false
                },
                onDismiss = { quickActionsOpen.value = false },
            )
        }
    }
    }
}

@Composable
private fun PageDeck(
    pageId: String,
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    initialIndex: Int,
    isActive: Boolean,
    /** When true, render the half-height peek deck: the active card centred with
     *  the previous and next cards peeking above and below it. Resolved by
     *  [effectivePeek] at the screen scope and passed down so every deck (active
     *  and its peek-composed neighbours) agrees on the page size + padding. */
    peekDeck: Boolean,
    vm: CardStackViewModel,
    appSettings: AppSettings,
    navRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    jumpRequests: kotlinx.coroutines.flow.SharedFlow<Int>,
    lightWheelModes: Map<com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode>,
    /** Reports VerticalPager animation state up to the screen-level
     *  wheel handler. Only the active deck pushes through (the
     *  effect is gated on isActive below) so a neighbour deck's
     *  initial-settle doesn't accidentally lock out input on the page
     *  the user can see. The reported boolean is true while the user's
     *  swipe is mid-fling and clears when the pager settles on its
     *  target page. */
    onActivePagerAnimatingChange: (Boolean) -> Unit,
) {
    // One pager state per page, keyed on pageId + infinite-scroll mode + the
    // presence of cards. Re-keying on the card-presence boolean (rather than
    // cards.size) means adding a fresh card doesn't rebuild the pager state and
    // bounce the user back to the start of the deck.
    val infiniteScroll = appSettings.ui.infiniteScroll
    // Capture cards.size at composition time. Including it in the
    // rememberPagerState key means the pager state rebuilds whenever the
    // deck shrinks or grows — fixes a class of bug where the pageCount
    // lambda closed over a stale `cards` reference (Compose preserves the
    // first-composition closure inside a remembered PagerState) and the
    // pager kept reporting the old size for currentPage validation.
    // Reported symptom: 'scroll-up crash, especially on the first card'
    // — when state mutations (entity add/remove via the new '…' menu, or
    // periodic state-changed events) shifted the deck, the next scroll
    // hit a size mismatch and Compose's Pager would throw on internal
    // invariants. Re-keying on size restores invariant safety.
    val pagerState = androidx.compose.runtime.key(pageId, infiniteScroll, cards.size) {
        androidx.compose.foundation.pager.rememberPagerState(
            initialPage = if (infiniteScroll && cards.isNotEmpty()) {
                val anchor = INFINITE_PAGER_VIRTUAL_PAGES / 2
                val aligned = anchor - (anchor % cards.size)
                aligned + initialIndex.coerceAtLeast(0).coerceAtMost(cards.size - 1)
            } else {
                initialIndex
                    .coerceAtMost((cards.size - 1).coerceAtLeast(0))
                    .coerceAtLeast(0)
            },
            pageCount = {
                if (infiniteScroll && cards.isNotEmpty()) INFINITE_PAGER_VIRTUAL_PAGES else cards.size
            },
        )
    }

    // Map a (possibly virtual) pager page to a real card index. In infinite-scroll mode
    // the pager uses a 200k-page virtual range, so we modulo back into 0..cards.size-1
    // before indexing the cards list or reporting currentIndex up to the VM.
    val realIndexOf: (Int) -> Int = { page ->
        if (cards.isEmpty()) 0
        else ((page % cards.size) + cards.size) % cards.size
    }
    // Whether THIS deck actually renders the peek layout: the resolved peek decision plus a
    // neighbour to peek (a lone card renders full-bleed). Computed once here so the layout,
    // the peek-neighbour gate, and the re-settle guard below all agree.
    val peek = peekActiveForDeck(peekDeck, cards.size)
    // Report the settled card index up to the VM, scoped to this page. Active page
    // writes through setCurrentIndex (which also updates the legacy currentIndex
    // field); inactive pages write through setIndexForPage so background scroll is
    // persisted without disturbing the active deck's state. No haptic here: the
    // screen-level effect keyed on (activeState.id, hapticKey) already ticks when
    // the settled card writes through setCurrentIndex, so a tick in this collector
    // double-fires one frame apart (its 50ms throttle isn't shared with this scope).
    LaunchedEffect(pagerState, cards.size, pageId, isActive) {
        snapshotFlow { pagerState.settledPage }
            .distinctUntilChanged()
            .collect { page ->
                val idx = realIndexOf(page)
                if (isActive) vm.setCurrentIndex(idx) else vm.setIndexForPage(pageId, idx)
            }
    }
    // Stream the pager's isScrollInProgress up to the screen-level
    // wheel handler — only while this deck is the active one. The
    // wheel handler drops events while this is true so a wheel spin
    // mid-fling doesn't land on the previous card. Resetting to false
    // when isActive flips off prevents a stale "true" leaking into the
    // wheel gate after a tab switch (we'd otherwise rely on the next
    // settle to clear it, which on a peek-composed neighbour may not
    // happen for a while).
    LaunchedEffect(pagerState, isActive) {
        if (!isActive) {
            onActivePagerAnimatingChange(false)
            return@LaunchedEffect
        }
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { onActivePagerAnimatingChange(it) }
    }
    // Re-settle guard. The vertical pager can come to rest at a fractional offset — a card
    // left visibly half-scrolled between two neighbours — when its page is restored or becomes
    // active mid-transition (switching tabs, or returning to the card view after navigating
    // away). Compose does not re-snap a restored / interrupted fractional offset on its own,
    // so when this deck is the active one, has stopped scrolling, and is not aligned to a
    // page, snap it to the nearest card. This also backstops the fling: should one ever end
    // between cards, it is pulled onto the nearest. Skipped in peek mode, where the first /
    // last card legitimately rest at a non-zero offset fraction (Center clamps them flush to
    // the band edge) so zeroing it would fight the layout and loop. Re-keyed on isActive so a
    // tab switch re-checks the deck it lands on.
    LaunchedEffect(pagerState, isActive, peek) {
        if (!isActive || peek) return@LaunchedEffect
        snapshotFlow { pagerState.isScrollInProgress }
            .collect { scrolling ->
                if (!scrolling &&
                    kotlin.math.abs(pagerState.currentPageOffsetFraction) > 0.01f
                ) {
                    pagerState.scrollToPage(pagerState.currentPage)
                }
            }
    }
    // Wheel-as-navigation, fired from CardStackScreen when the active card is read-only.
    // animateScrollToPage so the transition is the same gentle spring the user gets when
    // swiping the pager by finger — no jarring snap. In infinite-scroll mode we don't
    // wrap by modulo: we simply animate to currentPage ± 1, which the giant virtual
    // pageCount makes effectively boundless. (Modulo'ing inside the pager's range would
    // make the pager skip from page 199_999 back to 0 with a huge animateScroll instead
    // of a single-page glide.) In finite mode we clamp to [0, lastIndex]. Gated on
    // isActive so a wheel event never moves the deck on a page the user can't see.
    LaunchedEffect(pagerState, navRequests, infiniteScroll, isActive) {
        if (!isActive) return@LaunchedEffect
        navRequests.collect { delta ->
            if (cards.isEmpty() || delta == 0) return@collect
            val current = pagerState.currentPage
            val target = if (infiniteScroll) {
                (current + delta).coerceIn(0, INFINITE_PAGER_VIRTUAL_PAGES - 1)
            } else {
                (current + delta).coerceIn(0, cards.lastIndex)
            }
            if (target != current) pagerState.animateScrollToPage(target)
        }
    }
    // Jump-to-card requests — the active page collects the target index and
    // animates to it. Mirrors the previous direct pagerState.animateScrollToPage
    // call site but routed through a flow so the picker doesn't need a direct
    // reference to whichever page is active.
    LaunchedEffect(pagerState, jumpRequests, infiniteScroll, isActive, cards.size) {
        if (!isActive) return@LaunchedEffect
        jumpRequests.collect { targetIdx ->
            if (cards.isEmpty()) return@collect
            val current = pagerState.currentPage
            val target = if (infiniteScroll) {
                val curIdx = ((current % cards.size) + cards.size) % cards.size
                var diff = targetIdx - curIdx
                if (diff > cards.size / 2) diff -= cards.size
                if (diff < -cards.size / 2) diff += cards.size
                (current + diff).coerceIn(0, INFINITE_PAGER_VIRTUAL_PAGES - 1)
            } else {
                targetIdx.coerceIn(0, cards.lastIndex)
            }
            pagerState.animateScrollToPage(target)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // ChromeRow consumes the status-bar inset via systemBarsPadding,
        // so the actual chrome+tabstrip height on screen is
        //     statusBarHeight + chromeContent (~44 dp) + tabStripHeight (~36 dp).
        // The previous build hard-coded 100 dp which assumed the R1's
        // ~20 dp status bar; on phones with taller bars (notches,
        // pinhole cameras, Pixel-7-class hardware) the card overlapped
        // the tab strip by 10+ dp and the right-edge VerticalTapeMeter
        // got its top edge clipped by the same amount. Reading the
        // actual status-bar inset and adding our chrome-content height
        // on top keeps every device aligned correctly.
        val statusBarTop = androidx.compose.foundation.layout.WindowInsets.statusBars
            .asPaddingValues().calculateTopPadding()
        val pagerTopPadding = statusBarTop + 80.dp
        // Same inset-aware treatment on the bottom: phones with gesture
        // navigation (Pixel 7-class hardware) reserve 24–48 dp at the bottom
        // for the navigation-hint pill. The previous hard-coded 24 dp
        // contentPadding either left the card content brushing against the
        // pill (gesture phones) or left a too-large empty band on the R1
        // (which reports 0 dp nav inset). Adding 16 dp of baseline breathing
        // room on top of the actual inset keeps both extremes consistent.
        val navBarBottom = androidx.compose.foundation.layout.WindowInsets.navigationBars
            .asPaddingValues().calculateBottomPadding()
        val pagerBottomPadding = navBarBottom + 16.dp
        // Card corner shape hoisted out of the per-page content lambda. Previously a
        // fresh RoundedCornerShape(14.dp) was allocated for every page on every
        // composition (and the graphicsLayer block below reads it on every draw
        // frame during a fling); a single remembered instance is value-equal and
        // lets the graphicsLayer skip re-allocating the clip shape.
        val cardShape = androidx.compose.runtime.remember {
            androidx.compose.foundation.shape.RoundedCornerShape(14.dp)
        }
        // On big tiers a full-bleed card stretches into one enormous panel that
        // maroons its content across a 13" width. Cap each page's card to the
        // tier's content width and let the centred page Box letterbox the slack,
        // so the deck reads as a floating panel rather than a wall-to-wall slab.
        // Dp.Unspecified on R1 / compact means "fill" — widthIn(max = Unspecified)
        // is a no-op there, so the tiny panel keeps every pixel.
        val deckMaxCardWidth = rememberResponsiveDimens().maxContentWidth
        // Scope for the peek-deck tap-to-navigate animation. Tapping a peeking
        // neighbour animates the pager to that page rather than actuating the
        // card's control.
        val deckScope = androidx.compose.runtime.rememberCoroutineScope()
        // Peek-deck layout. When [peek] is on (a phone-portrait deck of at least two
        // cards) the pager is inset BELOW the chrome via its modifier and given zero
        // content padding, then snapped with SnapPosition.Center. Center settles a card
        // by centring it in the inset band, so the FIRST and LAST cards clamp flush to
        // the band's top and bottom (there is no card to scroll past on the outer side)
        // while every card in between rests centred: the topmost card snaps to the top,
        // the bottommost to the bottom, the rest to the middle. Each page is a fraction
        // (PEEK_PAGE_FRACTION) of the band so the neighbours peek into the leftover
        // space; the 8 dp spacing keeps the peeking slices reading as distinct cards.
        //
        // Why the modifier inset rather than contentPadding for the chrome clearance:
        // Center positions a card at available-space/2, and the pager lets an edge card
        // scroll into the leading / trailing CONTENT padding to reach that centre. A
        // chrome-sized top content padding would therefore let the first card slide down
        // into it and near-centre with dead space above it — the opposite of "the
        // topmost card snaps to the top". Insetting the pager itself (content padding 0)
        // removes that slack so the first card stays flush under the chrome. Center also
        // fixes a short-deck bug: under the default Start snap a fractional two-card deck
        // can never scroll its last card to the top snap, so the second card never
        // settled and could never be activated; Center makes every index the
        // nearest-snap page at some reachable scroll offset.
        val deckPageSize = if (peek) FractionPageSize(PEEK_PAGE_FRACTION) else PageSize.Fill
        val deckPageSpacing = if (peek) 8.dp else 0.dp
        // Velocity fling. One motion carries the deck through as many cards as the flick's
        // velocity projects, up to the whole stack: atMost(cards.size - 1) lets a hard flick
        // reach any card from any card, while the pager's own bounds stop it past the ends.
        // (The default caps every fling at a single page regardless of velocity.) The snap
        // uses a crisp, critically-damped spring (StiffnessMedium, no bounce): a critically
        // damped spring approaches its target monotonically, so the fling always settles
        // decisively ONTO a card and never overshoots or rests between two, on a 60 Hz budget
        // phone as cleanly as a 120 Hz flagship. Programmatic moves (wheel / hardware keys /
        // jump-to-card / tap-to-navigate) go through animateScrollToPage, which bypasses the
        // fling behaviour, so a detent or tap still advances exactly one card / to its target.
        //
        // Scroll sensitivity. The user-tunable [UiOptions.cardScrollSensitivity] (0..100,
        // default 80) feeds the decay's friction multiplier. Compose's stock exponential
        // decay uses friction 1.0; we anchor the default 80 to exactly that, so the
        // out-of-the-box feel is unchanged. The mapping is inverse-proportional:
        //
        //     friction = 0.8 / (sensitivity / 100)        // == 80 / sensitivity
        //
        // 80 → 1.0 (stock feel), 100 → 0.8 (less friction, the flick coasts further and
        // faster = more inertia), 40 → 2.0 (more friction, the deck brakes sooner = less
        // inertia). Sensitivity is coerced to 1..100 here so a stored 0 can't divide-by-
        // zero; the friction is clamped to a sane band so neither extreme makes the deck
        // unusable (never overshoots the whole stack, never freezes mid-card).
        val sensitivity = appSettings.ui.cardScrollSensitivity.coerceIn(1, 100)
        val flingFriction = (0.8f / (sensitivity / 100f)).coerceIn(0.5f, 4f)
        val deckFling = PagerDefaults.flingBehavior(
            state = pagerState,
            pagerSnapDistance = PagerSnapDistance.atMost(maxOf(1, cards.size - 1)),
            decayAnimationSpec = androidx.compose.animation.core.exponentialDecay(
                frictionMultiplier = flingFriction,
            ),
            snapAnimationSpec = androidx.compose.animation.core.spring<Float>(
                dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
            ),
        )
        VerticalPager(
            state = pagerState,
            // Peek: zero content padding — the chrome / nav clearance is a modifier inset
            // below, so SnapPosition.Center can flush-align the edge cards (see the
            // layout note above). Full-viewport: the historical chrome + nav content
            // padding, no modifier inset, so cards fill edge to edge and slide under the
            // translucent chrome on a drag.
            contentPadding = if (peek) {
                PaddingValues(0.dp)
            } else {
                PaddingValues(top = pagerTopPadding, bottom = pagerBottomPadding)
            },
            pageSize = deckPageSize,
            // Centre the active card in the inset band; the first and last cards clamp
            // flush to its top / bottom. Start (the full-viewport default) is identical
            // to Center for a Fill page, so non-peek decks are unaffected by setting it.
            snapPosition = if (peek) SnapPosition.Center else SnapPosition.Start,
            flingBehavior = deckFling,
            // Compose the immediate neighbours in peek mode so the peeking slices render
            // fully-laid-out cards the moment the deck settles. A fast multi-card fling
            // may briefly compose its landing card as it arrives, which is cheaper than
            // holding N neighbours alive at rest on every wheel detent. Full-viewport
            // mode keeps the default (0): neighbours compose lazily on drag, the cheaper
            // path the R1 wants.
            beyondViewportPageCount = if (peek) 1 else 0,
            pageSpacing = deckPageSpacing,
            // Stable per-card key in FINITE mode = the card's entity_id, so a deck
            // mutation (favourite added / removed / reordered) keeps each surviving
            // card's sub-composition instead of rebuilding positionally. In
            // INFINITE-scroll mode the same card id repeats across the 200k virtual
            // pages, so an id key would collide; there the virtual page index is the
            // only unique identity and we fall back to the pager default (null).
            key = if (infiniteScroll) {
                null
            } else {
                { page -> cards.getOrNull(page)?.id?.value ?: page }
            },
            // Peek: inset the pager below the chrome (top) and above the nav inset
            // (bottom) so its viewport is exactly the band the cards centre within.
            modifier = if (peek) {
                Modifier
                    .fillMaxSize()
                    .padding(top = pagerTopPadding, bottom = pagerBottomPadding)
            } else {
                Modifier.fillMaxSize()
            },
        ) { page ->
            // ~85% viewport — pad the card inward so the bg shows around it. Combined with a
            // rounded corner shape (hoisted to PageDeck scope above) and the shadow
            // elevation, the card looks like a free-floating panel rather than a
            // full-screen surface.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Look up the per-card long-press action so EntityCard only wires the
                // gesture when there's actually something to fire (otherwise the heavier
                // r1RowPressable would replace the cheaper r1Pressable for no gain).
                // Infinite-scroll uses a virtual page index well past cards.size, so we
                // modulo back into the real card index before any lookup.
                //
                // Guard against the cards list shrinking under us mid-frame. The
                // pager's content lambda can be invoked with a stale `page` index
                // when state transitions (entity removed via the '…' menu, or the
                // persister-loaded cache is overwritten by a smaller fresh state
                // push) shrink cards.size between composition cycles. Without the
                // guard, `cards[cardIdx]` was throwing IOOB on swipes that
                // coincided with the state transition — surfaced by the user as
                // 'scrolling up on cards crashes, especially the top card' when
                // the persister had loaded N cards and HA echoed back N-1.
                if (cards.isEmpty()) return@Box
                val realSize = cards.size
                val cardIdx = realIndexOf(page).coerceIn(0, realSize - 1)
                val card = cards.getOrNull(cardIdx) ?: return@Box
                val longPressTarget = appSettings.entityOverrides[card.id.value]?.longPressTarget
                val pageLightMode = lightWheelModes[card.id]
                // In peek mode a non-centred page is a peeking neighbour: its
                // controls are inert and a tap navigates to it instead of
                // actuating it. settledPage is the snapped page (not the live
                // currentPage, which flips mid-fling) so a card only counts as
                // "the active one" once the deck has come to rest on it — this
                // keeps a tap during a settle from being read as an actuation.
                val isPeekNeighbour = peek && page != pagerState.settledPage
                // Width-capped, full-height host so the card + its peek overlay
                // share one centred panel on big tiers. widthIn(max = Unspecified)
                // is a passthrough on R1 / compact, so the small panels stay
                // edge-to-edge; medium+ centres the card at the tier's content
                // width instead of stretching it wall-to-wall.
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .widthIn(max = deckMaxCardWidth)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                // Only the focused card surfaces the on-card "..." detail button;
                // peek neighbours null the opener out (their tap overlay would
                // swallow the tap anyway, but this also hides the glyph on the
                // half-visible sliver).
                androidx.compose.runtime.CompositionLocalProvider(
                    com.github.itskenny0.r1ha.core.theme.LocalOnCardMoreInfo provides
                        if (isPeekNeighbour) {
                            null
                        } else {
                            com.github.itskenny0.r1ha.core.theme.LocalOnCardMoreInfo.current
                        },
                ) {
                EntityCard(
                    state = card,
                    onTapToggle = { vm.tapToggle() },
                    // Peek neighbours never actuate on whole-card tap; the
                    // tap-to-navigate overlay below owns their tap instead.
                    tapToToggleEnabled = !isPeekNeighbour && appSettings.behavior.tapToToggle,
                    onSetOn = { on -> vm.setSwitchOn(on) },
                    // Suppress the long-press action on peek neighbours so a hold
                    // on a half-visible card can't fire its long-press target.
                    onLongPress = if (isPeekNeighbour) null
                        else longPressTarget?.let { target -> { vm.fireLongPress(target) } },
                    lightWheelMode = pageLightMode,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            // Compute pageOffset INSIDE graphicsLayer so the
                            // state read (pagerState.currentPage +
                            // currentPageOffsetFraction) happens at the draw
                            // phase, not at composition. Previously this was
                            // captured in the composable scope, which meant
                            // every fractional change during a fling forced a
                            // recomposition of every visible card just to
                            // re-run the graphicsLayer block. Now the layer
                            // re-invalidates on State change without
                            // recomposing.
                            val pageOffset = (
                                (pagerState.currentPage - page) +
                                    pagerState.currentPageOffsetFraction
                            )
                            val abs = kotlin.math.abs(pageOffset)
                            // The active page (offset ≈ 0) casts a strong shadow that fades
                            // to nothing as the page slides off screen.
                            shadowElevation = (24.dp.toPx() * (1f - abs).coerceIn(0f, 1f))
                            // Slight scale-down on the incoming card so the active one feels
                            // forward in the stack.
                            val scale = 1f - (abs * 0.04f).coerceIn(0f, 0.04f)
                            scaleX = scale
                            scaleY = scale
                            // Clip = true with a rounded shape applies the radius AND makes
                            // the shadow follow the contour.
                            shape = cardShape
                            clip = true
                        },
                )
                }
                // Tap-to-navigate overlay for peeking neighbours. Drawn last so it
                // sits on top of the card and intercepts every pointer event over
                // the half-visible neighbour: a tap (or any press) animates the
                // pager to that page rather than actuating the card's value bar /
                // toggle underneath. Matched to the card's rounded corner + inset
                // so the tap target lines up with the visible card body. A faint
                // scrim reinforces that the neighbour is a navigation affordance,
                // not the live card; it clears the instant the deck settles on it.
                if (isPeekNeighbour) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(cardShape)
                            .background(R1.Bg.copy(alpha = 0.28f))
                            .r1Pressable(
                                onClick = {
                                    deckScope.launch {
                                        runCatching { pagerState.animateScrollToPage(page) }
                                    }
                                },
                                contentDescription = "Show ${card.friendlyName}",
                            ),
                    )
                }
                }
            }
        }

        // ── Chevron hint ──────────────────────────────────────────────────────────────
        // Down hint at the bottom edge when there's a next card. The up hint was dropped
        // because it landed underneath the chrome's vertical position pip — redundant
        // information at best, visual collision at worst. The down hint stays useful
        // because the bottom of the card is otherwise empty.
        val currentPage = pagerState.currentPage
        // Chevron hint at the bottom — visible whenever there's a next card to scroll
        // to. In infinite-scroll mode there's *always* a next card (the deck wraps), so
        // the hint shows on every page; in finite mode it hides on the last card.
        val hasNext = if (appSettings.ui.infiniteScroll) cards.size > 1
            else currentPage < cards.size - 1
        androidx.compose.animation.AnimatedVisibility(
            visible = hasNext,
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

/**
 * Cold-start splash shown until [CardStackUiState.settingsLoaded] flips true.
 * Wordmark over a throbber so the user knows the app is loading (a bare
 * spinner during the brief DataStore read window could look like the device
 * froze; on the R1's slow boot path the splash can sit visible for a couple
 * of hundred ms). Once settings arrive the screen routes into either
 * [EmptyState] (with onboarding copy) or [VerticalCardPager] (with the
 * user's deck) as appropriate.
 *
 * Tag-style 'R1 · HA' uses the same uppercase letterspaced numeral the rest
 * of the dashboard uses for section headers, so the splash reads as part of
 * the design language rather than a generic loader.
 */
@Composable
private fun StartupSplash() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "R1 · HA",
            style = R1.sectionHeader,
            color = R1.AccentWarm,
        )
        Spacer(Modifier.height(14.dp))
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = R1.AccentWarm,
        )
    }
}

@Composable
private fun EmptyState(
    loading: Boolean,
    favouritesCount: Int,
    connection: ConnectionState,
    reconnectAt: Long?,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetry: () -> Unit,
) {
    // After STALLED_AFTER_MS of loading without any cards arriving, surface a "Stuck?"
    // affordance pointing to Settings. Without it, an unreachable HA leaves the user on a
    // pure spinner with no idea what to do; the reconnect-backoff in the repo can be 30s
    // between attempts and the user shouldn't be expected to wait that out blindly.
    val stalled = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(loading) {
        stalled.value = false
        if (loading) {
            kotlinx.coroutines.delay(STALLED_AFTER_MS)
            stalled.value = true
        }
    }
    // Reconnect countdown — when the repo has a backoff in flight, tick a once-per-second
    // recomputed "RECONNECTING IN Xs…" so the user can see the indefinite-spinner state is
    // actually doing something. We only need a coarse 1-Hz refresh; the actual reconnect
    // fires from the repo's coroutine, not from this tick. Driven by a wall-clock-now
    // mutableState that the LaunchedEffect rewrites every second while there's a future
    // target — cheap to recompose on, and goes silent once reconnectAt clears.
    val nowMs = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(System.currentTimeMillis()) }
    androidx.compose.runtime.LaunchedEffect(reconnectAt) {
        if (reconnectAt == null) return@LaunchedEffect
        while (true) {
            nowMs.value = System.currentTimeMillis()
            // 1 Hz is more than enough fidelity for human-readable seconds; faster ticks
            // just burn frames without changing the rendered string.
            kotlinx.coroutines.delay(1_000)
        }
    }
    val countdownSeconds = reconnectAt?.let {
        ((it - nowMs.value) / 1000L).coerceAtLeast(0L)
    }

    // Cap + centre the hero copy on big tiers so it stays a centred block rather
    // than a marooned full-width run; step the type up via responsiveType so the
    // body doesn't read tiny on a 13" panel. Dp.Unspecified on R1 / compact keeps
    // the full-width hero the small panels want (widthIn(max = Unspecified) no-op).
    val emptyMaxWidth = rememberResponsiveDimens().maxContentWidth
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
    Column(
        modifier = Modifier
            .widthIn(max = emptyMaxWidth)
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.height(20.dp))
        }
        Text(
            text = (if (loading) "Loading entities" else "No favourites yet").uppercase(),
            style = responsiveType(R1.sectionHeader),
            color = R1.InkSoft,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = if (loading) {
                "Connecting to $favouritesCount favourite${if (favouritesCount == 1) "" else "s"}…"
            } else {
                "Pick the lights, fans, covers, and media players you want\non the wheel."
            },
            style = responsiveType(R1.body),
            color = R1.InkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        // Countdown chip — only meaningful while we're loading AND there's a backoff
        // scheduled. (Without the loading gate, a transient reconnectAt during normal use
        // would briefly leak through here.) Friendlier than the bare spinner: the user
        // can see something will happen in 14 seconds, not just "loading forever". We
        // suppress it once seconds reaches zero — at that point the repo has fired and
        // is actively reconnecting; the spinner alone is correct.
        if (loading && countdownSeconds != null && countdownSeconds > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "RECONNECTING IN ${countdownSeconds}s…",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
        Spacer(Modifier.height(28.dp))
        R1Button(
            text = if (loading) "EDIT FAVOURITES" else "ADD FAVOURITES",
            onClick = onOpenFavoritesPicker,
        )
        // Stalled-loading affordance. Two paths once we know the spinner has lingered too
        // long: a one-tap "retry connection" (cancels the backoff, fires immediately) and a
        // fallback "open settings" for the case where the auth tokens themselves are the
        // problem and reconnecting won't help. The status colour follows the connection
        // state: amber while still optimistically retrying, red once we know auth or the
        // server actively refused us.
        if (loading && stalled.value) {
            val color = when (connection) {
                is ConnectionState.AuthLost -> R1.StatusRed
                is ConnectionState.Disconnected -> R1.StatusRed
                else -> R1.StatusAmber
            }
            Spacer(Modifier.height(20.dp))
            // Give the retry chip a visible border + surface so it reads as a button
            // rather than just inline copy. Previously a bare Text inside a Box made the
            // tap target invisible against the empty-state backdrop.
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(color.copy(alpha = 0.12f))
                    .border(1.dp, color.copy(alpha = 0.4f), R1.ShapeS)
                    .r1Pressable(onRetry)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "STILL LOADING · TAP TO RETRY",
                    style = R1.labelMicro,
                    color = color,
                )
            }
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .r1Pressable(onOpenSettings)
                    .padding(horizontal = 14.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "OPEN SETTINGS →",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
    }
}

private const val STALLED_AFTER_MS = 10_000L

/** Fraction of the (chrome-adjusted) viewport height each card occupies in the peek
 *  deck. Just over half so the active card dominates while still leaving a clear slice
 *  for the previous and next cards to peek above and below it. Tuned with the 8 dp
 *  page spacing so the two peek slices read as distinct cards rather than a continuous
 *  strip. */
private const val PEEK_PAGE_FRACTION = 0.62f

/** Virtual page count used by the pager when infinite-scroll is enabled. Big enough
 *  that even an entire afternoon of aggressive swiping doesn't run out of pages (200 k
 *  pages ÷ 1 swipe-per-half-second × 60 s × 60 min = ~28 hours of continuous swiping
 *  before hitting an end), small enough that the pager's per-page Compose bookkeeping
 *  stays cheap. Capped well under Int.MAX_VALUE to avoid arithmetic overflow corners. */
private const val INFINITE_PAGER_VIRTUAL_PAGES = 200_000

/** Sentinel id meaning 'open the TabManageDialog in "add new page" mode'. Real
 *  page ids are random UUIDs so this fixed string never collides. */
private const val NEW_PAGE_SENTINEL = "__new_page__"

/**
 * Tab strip — one chip per page, plus a trailing '+' chip to add a new page. The
 * active page chip fills with accent; others sit on the muted surface.
 *
 * Gesture map:
 *  - Tap: switch to that page.
 *  - Long-press + drag horizontally: live-reorder. A swap fires every time the
 *    finger crosses half a chip-width worth of travel (~40 dp). Same pattern as
 *    [DragReorderColumn] but flipped to the horizontal axis.
 *  - Long-press + release without dragging: open the manage modal (rename /
 *    delete / explicit MOVE LEFT / MOVE RIGHT).
 *
 * Sits directly under the chrome row when there's more than one page. Hidden on
 * single-page installs so the pre-tabs aesthetic is preserved for users who
 * never opt into multi-page.
 */
@Composable
private fun TabStrip(
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    activePageId: String,
    onTapPage: (String) -> Unit,
    onLongPressPage: (String) -> Unit,
    onAddPage: () -> Unit,
    onReorder: (fromIdx: Int, toIdx: Int) -> Unit,
    solidBackdrop: Boolean,
) {
    val scroll = androidx.compose.foundation.rememberScrollState()
    val density = androidx.compose.ui.platform.LocalDensity.current
    // Per-chip measured X bounds (left .. right, in pixels relative to the Row's
    // origin). Populated via onGloballyPositioned on each chip and read by the
    // auto-scroll LaunchedEffect so the active chip is brought into view when
    // the user swipes to a new page on the horizontal-pager below. Without
    // this, swiping between pages on a long tab strip would leave the visible
    // page label stuck off-screen.
    val chipBounds = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateMapOf<String, IntRange>()
    }
    // Snap the active chip into view whenever the active page id changes. Pads
    // the scroll target so the chip isn't flush against the viewport edge — a
    // small breathing margin keeps it readable + leaves a hint that more chips
    // exist to either side. Animated rather than instant so the transition
    // visibly mirrors the page swipe happening underneath.
    val pagerScope = androidx.compose.runtime.rememberCoroutineScope()
    androidx.compose.runtime.LaunchedEffect(activePageId, chipBounds.size, scroll.maxValue) {
        val r = chipBounds[activePageId] ?: return@LaunchedEffect
        val viewport = scroll.viewportSize
        if (viewport <= 0) return@LaunchedEffect
        val margin = with(density) { 16.dp.toPx() }.toInt()
        val visibleStart = scroll.value
        val visibleEnd = visibleStart + viewport
        val target = when {
            r.first < visibleStart + margin ->
                (r.first - margin).coerceAtLeast(0)
            r.last > visibleEnd - margin ->
                (r.last - viewport + margin).coerceAtMost(scroll.maxValue)
            else -> return@LaunchedEffect
        }
        pagerScope.launch { scroll.animateScrollTo(target) }
    }
    // rememberUpdatedState lets the long-lived pointerInput lambda reach the
    // *current* pages + callbacks every drag event, even though pointerInput is
    // keyed on the stable page.id (and so isn't rebuilt on every recomposition).
    // Without this, a chip that just got swapped would see the pre-swap pages
    // list and fire a duplicate swap.
    val currentPages = androidx.compose.runtime.rememberUpdatedState(pages)
    val currentOnReorder = androidx.compose.runtime.rememberUpdatedState(onReorder)
    val currentOnLongPress = androidx.compose.runtime.rememberUpdatedState(onLongPressPage)
    // Half-chip's worth of travel. Chips on the R1 are roughly 56-72 dp wide
    // depending on the page name; 40 dp lands somewhere between "easy to
    // trigger" and "easy to overshoot by accident". Same magnitude as
    // DragReorderColumn's vertical threshold so the tactile feel matches.
    val swapThresholdPx = with(density) { 40.dp.toPx() }
    /** ID of the chip currently in flight. Drives the visual lift effect. */
    var draggedKey by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (solidBackdrop) Modifier.background(R1.Bg) else Modifier)
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .horizontalScroll(scroll)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        pages.forEach { page ->
            val active = page.id == activePageId
            val isDragging = draggedKey == page.id
            // Per-chip mutable drag state. Reset whenever the long-press
            // starts so each new drag begins from zero offset. Keyed on
            // page.id so a chip's state survives reorders.
            val hasDragged = androidx.compose.runtime.remember(page.id) {
                androidx.compose.runtime.mutableStateOf(false)
            }
            val dragOffsetPx = androidx.compose.runtime.remember(page.id) {
                androidx.compose.runtime.mutableFloatStateOf(0f)
            }
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    // Record this chip's measured x-bounds (relative to the
                    // Row) so the LaunchedEffect above can scroll the strip
                    // when the active page changes. Stored as IntRange so the
                    // auto-scroll math can compare against scroll.value
                    // directly without a separate width/offset pair.
                    .onGloballyPositioned { coords ->
                        val left = coords.positionInParent().x.toInt()
                        val right = left + coords.size.width
                        chipBounds[page.id] = left..right
                    }
                    // While dragging: translate the chip along the user's
                    // finger via the accumulated offset, lift it slightly with
                    // a scale > 1 (keeps the tap target physically the same)
                    // and dim the alpha so adjacent chips read as 'in the
                    // background'. The translation makes the gesture feel
                    // physical — the finger drags the chip rather than the
                    // chip teleporting between slots on threshold-cross. The
                    // accumulated offset resets toward 0 after each swap, so
                    // the translation magnitude stays bounded.
                    .graphicsLayer {
                        if (isDragging) {
                            translationX = dragOffsetPx.floatValue
                            scaleX = 1.06f
                            scaleY = 1.06f
                            this.alpha = 0.88f
                        }
                    }
                    .clip(R1.ShapeS)
                    .background(
                        if (active) {
                            // Per-page accent override; falls back to the warm
                            // default for pages that haven't been customised.
                            page.accentArgb?.let { androidx.compose.ui.graphics.Color(it) }
                                ?: R1.AccentWarm
                        } else R1.SurfaceMuted,
                    )
                    .r1Pressable(onClick = { onTapPage(page.id) })
                    .pointerInput(page.id) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                hasDragged.value = false
                                dragOffsetPx.floatValue = 0f
                                draggedKey = page.id
                            },
                            onDrag = { change, drag ->
                                change.consume()
                                dragOffsetPx.floatValue += drag.x
                                // Re-resolve current index every event — the
                                // chip may have already shifted due to a prior
                                // swap in the same drag. currentPages is the
                                // up-to-date list via rememberUpdatedState.
                                val curIdx = currentPages.value.indexOfFirst { it.id == page.id }
                                if (curIdx < 0) return@detectDragGesturesAfterLongPress
                                // Swap right.
                                while (dragOffsetPx.floatValue > swapThresholdPx &&
                                    currentPages.value.indexOfFirst { it.id == page.id }
                                        .let { it >= 0 && it < currentPages.value.lastIndex }
                                ) {
                                    val i = currentPages.value.indexOfFirst { it.id == page.id }
                                    currentOnReorder.value(i, i + 1)
                                    dragOffsetPx.floatValue -= swapThresholdPx
                                    hasDragged.value = true
                                }
                                // Swap left.
                                while (dragOffsetPx.floatValue < -swapThresholdPx &&
                                    currentPages.value.indexOfFirst { it.id == page.id } > 0
                                ) {
                                    val i = currentPages.value.indexOfFirst { it.id == page.id }
                                    currentOnReorder.value(i, i - 1)
                                    dragOffsetPx.floatValue += swapThresholdPx
                                    hasDragged.value = true
                                }
                            },
                            onDragEnd = {
                                draggedKey = null
                                // Long-press without any drag movement falls
                                // through to the manage-modal callback so
                                // users still have a way to open it.
                                if (!hasDragged.value) currentOnLongPress.value(page.id)
                            },
                            onDragCancel = {
                                draggedKey = null
                                if (!hasDragged.value) currentOnLongPress.value(page.id)
                            },
                        )
                    }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                // Page icon + name + entity-count badge. Icon is a single
                // Unicode glyph chosen from the manage modal's curated row;
                // rendered first so the user's eye lands on it. Name follows.
                // The "· N" suffix appears only when the page has favourites
                // — empty pages would otherwise get a misleading "· 0" that
                // crowds the chip without conveying anything useful. Same
                // labelMicro style for both segments so they read as one
                // unit; the count is dimmed slightly so it doesn't compete
                // with the page name for attention.
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!page.icon.isNullOrEmpty()) {
                        Text(
                            text = page.icon,
                            style = R1.labelMicro,
                            color = if (active) R1.Bg else R1.InkSoft,
                        )
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(
                        text = page.name,
                        style = R1.labelMicro,
                        color = if (active) R1.Bg else R1.InkSoft,
                    )
                    if (page.favorites.isNotEmpty()) {
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "·",
                            style = R1.labelMicro,
                            color = if (active) R1.Bg.copy(alpha = 0.55f)
                                else R1.InkMuted,
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = page.favorites.size.toString(),
                            style = R1.labelMicro,
                            color = if (active) R1.Bg.copy(alpha = 0.85f)
                                else R1.InkMuted,
                        )
                    }
                }
            }
        }
        // '+' chip — always last. Tap → open the manage modal in 'add' mode.
        Box(
            modifier = Modifier
                .padding(end = 4.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .r1Pressable(onClick = onAddPage, contentDescription = "Add page")
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(text = "+", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}

/**
 * Modal for adding, renaming, or deleting a page. Two modes share the same panel
 * so users learn one surface instead of three:
 *
 *  * **Add mode** ([isAdd] = true, [page] = null) — single text field defaulting
 *    to "NEW", a SAVE button and a CANCEL chip. No DELETE row.
 *  * **Edit mode** ([isAdd] = false, [page] non-null) — text field pre-filled
 *    with the page's current name; SAVE renames, CANCEL discards. A DELETE
 *    button appears below when [canDelete] is true (i.e. there's more than one
 *    page — the user can never delete their last page out from under the deck).
 *
 * Styling follows the rename-dialog conventions: dim backdrop, sharp 2 dp panel,
 * hairline border, monospace where it helps. Back press dismisses, matching the
 * other R1 modals.
 */
@Composable
private fun TabManageDialog(
    isAdd: Boolean,
    page: com.github.itskenny0.r1ha.core.prefs.FavoritePage?,
    canDelete: Boolean,
    /** True when the page being edited has a left neighbour — gates the MOVE LEFT
     *  button. Ignored in add mode. */
    canMoveLeft: Boolean,
    /** Mirror of [canMoveLeft] for the right side. */
    canMoveRight: Boolean,
    onAdd: (String) -> Unit,
    onGenerateFromAreas: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onMoveLeft: (String) -> Unit,
    onMoveRight: (String) -> Unit,
    onSetAccent: (pageId: String, accentArgb: Int?) -> Unit,
    onSetIcon: (pageId: String, icon: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    val initial = if (isAdd) "NEW" else (page?.name ?: "")
    var name by androidx.compose.runtime.remember(isAdd, page?.id) {
        androidx.compose.runtime.mutableStateOf(initial)
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(
                text = if (isAdd) "NEW PAGE" else "EDIT PAGE",
                style = R1.sectionHeader,
                color = R1.AccentWarm,
            )
            if (!isAdd && page != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = page.id,
                    style = R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(12.dp))
            // Auto-focus the name field on dialog open so the keyboard appears
            // without a stray tap. The user just hit '+' (add) or long-pressed
            // a chip (edit) — they want to type. The 50 ms delay gives the
            // dialog a frame to commit composition before we yank focus into
            // the BasicTextField; without it the request occasionally lands
            // before the field is laid out and gets dropped.
            val nameFocus = androidx.compose.runtime.remember(isAdd, page?.id) {
                androidx.compose.ui.focus.FocusRequester()
            }
            androidx.compose.runtime.LaunchedEffect(isAdd, page?.id) {
                kotlinx.coroutines.delay(50)
                runCatching { nameFocus.requestFocus() }
            }
            com.github.itskenny0.r1ha.ui.components.R1TextField(
                value = name,
                onValueChange = { name = it.take(20) },
                placeholder = "PAGE NAME",
                monospace = false,
                focusRequester = nameFocus,
            )
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                R1Button(
                    text = stringResource(R.string.dialog_cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                R1Button(
                    text = stringResource(R.string.dialog_save),
                    onClick = {
                        val trimmed = name.trim().ifBlank { if (isAdd) "NEW" else (page?.name ?: "PAGE") }
                        if (isAdd) onAdd(trimmed) else page?.let { onRename(it.id, trimmed) }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            // Bulk generator — only in add mode. Pulls every HA area with at
            // least one controllable entity and creates one tab per area,
            // pre-populated with that area's lights, switches, climate, etc.
            // Faster than naming and populating a tab manually for each
            // room. The user can rename / re-accent / delete the generated
            // tabs afterwards through the same dialog.
            if (isAdd) {
                Spacer(Modifier.height(8.dp))
                R1Button(
                    text = "GENERATE FROM HA AREAS",
                    onClick = onGenerateFromAreas,
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
            }
            // MOVE LEFT / MOVE RIGHT — shifts the page one slot in either
            // direction in the tab strip. Hidden buttons (canMoveLeft/Right =
            // false) on the leftmost/rightmost page rather than disabled, so
            // the row size adjusts and the dialog stays tidy on the R1's
            // narrow display. The arrow glyphs avoid any text-wrapping at the
            // labelMicro size.
            if (!isAdd && page != null && (canMoveLeft || canMoveRight)) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (canMoveLeft) {
                        R1Button(
                            text = "◀  MOVE LEFT",
                            onClick = { onMoveLeft(page.id) },
                            modifier = Modifier.weight(1f),
                            variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                        )
                    }
                    if (canMoveRight) {
                        R1Button(
                            text = "MOVE RIGHT  ▶",
                            onClick = { onMoveRight(page.id) },
                            modifier = Modifier.weight(1f),
                            variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                        )
                    }
                }
            }
            // Accent colour row — only meaningful in edit mode where there's an
            // existing page to recolour. Six presets matched against the R1
            // palette (warm / cool / amber / red / green / muted) plus a
            // 'default' swatch that clears the override. The active selection
            // gets a hairline border so it's obvious which preset is current;
            // others render as flat swatches.
            if (!isAdd && page != null) {
                Spacer(Modifier.height(14.dp))
                Text(text = "ACCENT", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                val accentPresets = listOf<Pair<String, Int?>>(
                    "DEFAULT" to null,
                    "WARM" to R1.AccentWarm.value.toInt(),
                    "COOL" to R1.AccentCool.value.toInt(),
                    "AMBER" to R1.StatusAmber.value.toInt(),
                    "RED" to R1.StatusRed.value.toInt(),
                    "GREEN" to R1.AccentGreen.value.toInt(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((label, argb) in accentPresets) {
                        val swatchColor = argb?.let { androidx.compose.ui.graphics.Color(it) }
                            ?: R1.SurfaceMuted
                        val selected = page.accentArgb == argb
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(R1.ShapeS)
                                .background(swatchColor)
                                .then(
                                    if (selected) Modifier.border(1.5.dp, R1.Ink, R1.ShapeS)
                                    else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                                )
                                .r1Pressable(onClick = { onSetAccent(page.id, argb) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            // 'DEFAULT' tile gets a tiny label since the muted
                            // colour alone isn't distinguishable from an unset
                            // / disabled state. Coloured tiles speak for
                            // themselves.
                            if (argb == null) {
                                Text(
                                    text = "—",
                                    style = R1.labelMicro,
                                    color = R1.InkMuted,
                                )
                            }
                        }
                    }
                }
            }
            // Icon row — curated set of Unicode glyphs that read cleanly on
            // the R1's mono-style display. Tap to apply; '—' clears the
            // override (no icon prepended to the chip). Edit mode only,
            // mirroring the accent row's gating.
            if (!isAdd && page != null) {
                Spacer(Modifier.height(10.dp))
                Text(text = "ICON", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                val iconPresets = listOf<String?>(
                    null, "⌂", "★", "◆", "◇", "☀", "☾", "♪", "⚙",
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (preset in iconPresets) {
                        val selected = page.icon == preset
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(28.dp)
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .then(
                                    if (selected) Modifier.border(1.5.dp, R1.Ink, R1.ShapeS)
                                    else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                                )
                                .r1Pressable(onClick = {
                                    com.github.itskenny0.r1ha.core.util.R1Log.d(
                                        "TabManage", "setPageIcon ${page.id} -> ${preset ?: "(clear)"}",
                                    )
                                    onSetIcon(page.id, preset)
                                }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = preset ?: "—",
                                style = R1.labelMicro,
                                color = if (preset == null) R1.InkMuted else R1.Ink,
                            )
                        }
                    }
                }
            }
            // DELETE only shows in edit-mode AND when at least one other page would
            // remain afterward. Deleting the last page would leave the user with an
            // empty deck and no way to switch back to a page, so we hide the option
            // entirely rather than relying on a runtime block.
            //
            // Two-stage confirm: first tap arms the button (label flips to
            // 'CONFIRM DELETE · TAP AGAIN'), second tap commits. Auto-disarms
            // after 3 seconds via a LaunchedEffect so a stray arm doesn't sit
            // hot indefinitely. Mirrors how desktop OSes guard accidental
            // destructive actions — a one-tap delete on a populated page was
            // too easy to fire from muscle memory.
            if (!isAdd && page != null && canDelete) {
                Spacer(Modifier.height(8.dp))
                val armed = androidx.compose.runtime.remember {
                    androidx.compose.runtime.mutableStateOf(false)
                }
                androidx.compose.runtime.LaunchedEffect(armed.value) {
                    if (armed.value) {
                        kotlinx.coroutines.delay(3_000)
                        armed.value = false
                    }
                }
                R1Button(
                    text = if (armed.value) "CONFIRM DELETE · TAP AGAIN" else "DELETE",
                    onClick = {
                        if (armed.value) onDelete(page.id) else armed.value = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                    accent = R1.StatusRed,
                )
                if (armed.value) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Will remove the page and its ${page.favorites.size} favourite${if (page.favorites.size == 1) "" else "s"} from this view (HA entities aren't deleted).",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
        }
    }
}

/**
 * Per-card context menu opened by long-pressing a JumpRow. Currently surfaces
 * page-move actions ("Move to PAGE_NAME" once per page other than the source)
 * plus a duplicate REMOVE so the menu is the canonical 'do something to this
 * card' surface. Dismisses on backdrop tap or BackHandler.
 *
 * Visual styling mirrors [TabManageDialog]: dim full-screen backdrop, sharp
 * 2 dp inner panel with hairline border, warm-accent section header, monospace
 * entity_id reminder beneath the friendly name. Keeps the modal language
 * consistent across the dashboard.
 */
@Composable
private fun CardContextMenu(
    entityName: String,
    entityId: String,
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    sourcePageId: String,
    /** HA server URL — used to build the deep-link for the 'Open in HA' button.
     *  Null when the user isn't signed in (the button is then hidden). */
    haServerUrl: String?,
    onMove: (targetPageId: String) -> Unit,
    onRemove: () -> Unit,
    onOpenInHa: (url: String) -> Unit,
    /** Open the in-app ultra-detail more-info sheet for this card's entity.
     *  Null when the effective per-entity `moreInfoEnabled` resolves to false
     *  (or settings haven't loaded); the button is then hidden. */
    onMoreInfo: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 14.dp)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp)
                .verticalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            Text(text = "CARD ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(4.dp))
            Text(
                text = entityName,
                style = R1.body,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Text(
                text = entityId,
                style = R1.labelMicro.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            // Move-to-page entries. Filtered to pages OTHER than the source so
            // we never offer a self-move. When there's only one page total,
            // this section collapses to a 'no other pages' affordance pointing
            // at the '+' chip so the user discovers the page-creation route.
            //
            // Rendered as a wrapping FlowRow of compact chips rather than
            // one full-width R1Button per page — users with 8+ pages were
            // seeing the modal fill the whole screen with MOVE TO buttons.
            // Each chip sizes to its text + a small horizontal padding,
            // wrapping onto multiple rows only when the page count actually
            // requires it. Active accent border so each chip reads as
            // tappable; same labelMicro text style as the page chips on
            // the main tab strip for visual consistency.
            val targetPages = pages.filter { it.id != sourcePageId }
            Spacer(Modifier.height(14.dp))
            Text(text = "MOVE TO", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(6.dp))
            if (targetPages.isEmpty()) {
                Text(
                    text = "No other pages yet. Add one with the '+' chip on the tab strip.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            } else {
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    for (p in targetPages) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .border(1.dp, R1.AccentWarm, R1.ShapeS)
                                .r1Pressable(onClick = { onMove(p.id) })
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                        ) {
                            Text(
                                text = p.name.uppercase(),
                                style = R1.labelMicro,
                                color = R1.AccentWarm,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            // Ultra-detail more-info — opens the in-app attribute / history
            // sheet for this entity. Hidden when the effective per-entity
            // moreInfoEnabled resolved to false (onMoreInfo is then null).
            if (onMoreInfo != null) {
                R1Button(
                    text = "MORE INFO",
                    onClick = onMoreInfo,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
            }
            // Open in HA — deep-link to the entity's history page in the HA
            // web UI. Useful when the user wants to see HA's full sensor
            // history / device controls / configure automations. Hidden
            // when the user isn't signed in.
            if (!haServerUrl.isNullOrBlank()) {
                val url = "${haServerUrl.trimEnd('/')}/history?entity_id=$entityId"
                R1Button(
                    text = "OPEN IN HA",
                    onClick = { onOpenInHa(url) },
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.height(8.dp))
            }
            // Remove from this page — same destructive action surfaced via the
            // inline '✕' chip. Duplicated here so the long-press menu is a
            // complete 'manage this card' surface; a user who long-pressed
            // expecting to remove (and missed that the inline chip existed)
            // still finds the affordance.
            R1Button(
                text = "REMOVE FROM PAGE",
                onClick = onRemove,
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusRed,
            )
            Spacer(Modifier.height(8.dp))
            R1Button(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        }
    }
}

/**
 * Two-line tile — emoji glyph stacked above an all-caps label, both
 * inside the same tappable surface. Used by [QuickActionsSheet]'s
 * BROWSE grid so the four shortcuts in each row read as a navigation
 * cluster rather than four bare text buttons. Same scale-on-press
 * idiom as the rest of the chrome (r1Pressable).
 */
@Composable
private fun DrawerGlyph(
    modifier: Modifier,
    glyph: String,
    label: String,
    onClick: () -> Unit,
) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(text = glyph, style = R1.body)
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
    }
}

/**
 * Quick-actions sheet — opened by long-pressing the chrome hamburger.
 * Doubles as the app's navigation drawer in the HA-Companion idiom:
 *   - BROWSE grid (2×4) of major-surface shortcuts (Today, Assist,
 *     Search, Scenes, Automations, Energy, Alerts)
 *   - ACTIONS list of one-tap operations against the active page
 *     (Turn All On, Pause N media, Turn All Off with confirm)
 */
/**
 * One context-aware quick-jump tile in the QuickActions sheet's GO TO row.
 * [glyph] is a single monochrome codepoint matching the BROWSE grid aesthetic;
 * [onClick] already closes the sheet before navigating.
 */
private data class QuickJump(
    val glyph: String,
    val label: String,
    val onClick: () -> Unit,
)

@Composable
private fun QuickActionsSheet(
    activePageName: String,
    cardCount: Int,
    playingMediaCount: Int,
    /** Friendly name of the focused card, shown as the GO TO row's subtitle so
     *  the context-aware jumps read as "for THIS card". Null hides the row's
     *  per-entity framing. */
    focusedName: String?,
    /** Context-aware jumps tailored to the focused card's domain plus the
     *  always-available broad jumps. Empty list hides the GO TO row entirely. */
    contextJumps: List<QuickJump>,
    /** User-pinned Lovelace dashboard views. Rendered as the DASHBOARDS section
     *  (the phone surface for dashboard pins, since phones show no nav rail).
     *  Empty list hides the section entirely. */
    pinnedDashboards: List<com.github.itskenny0.r1ha.core.prefs.PinnedDashboard>,
    /** User-pinned nav surfaces (resolved from
     *  [com.github.itskenny0.r1ha.core.prefs.NavPanelSettings.pinnedSurfaces]). Rendered
     *  as the PINNED section so the small-screen drawer mirrors the tablet nav rail's
     *  pinned-surface tier. Empty list hides the section entirely. */
    pinnedSurfaces: List<com.github.itskenny0.r1ha.nav.PinnableSurface>,
    /** Navigate to a pinned dashboard view by its full route. Already closes the
     *  sheet before navigating. */
    onOpenDashboardRoute: (String) -> Unit,
    /** Navigate to a pinned nav surface by its Routes id. Already closes the
     *  sheet before navigating. */
    onOpenRoute: (String) -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenAssist: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenDevice: () -> Unit,
    onAllOn: () -> Unit,
    onAllOff: () -> Unit,
    onPauseMedia: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    val armed = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(armed.value) {
        if (armed.value) {
            kotlinx.coroutines.delay(3_000)
            armed.value = false
        }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l, vertical = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                // Vertical scroll so the BROWSE grid + ACTIONS stack
                // doesn't get clipped on shorter screens (e.g. R1
                // landscape, foldable inner display in book mode).
                // No-op when content fits — Column doesn't scroll
                // when its height is unconstrained.
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(R1.space.l),
        ) {
            Text(text = "QUICK ACTIONS", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = activePageName.uppercase(),
                style = R1.body,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(R1.space.m))

            // ── BROWSE row — 2×4 grid of icon-glyph nav shortcuts ──
            // These doubles as the HA-Companion-style 'drawer'
            // navigation: every major surface is reachable from one
            // long-press on the chrome hamburger. Two rows of four so
            // they fit on a single screen of the R1's portrait display.
            Text(text = "BROWSE", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Monochrome typographic glyphs only; the previous emoji set rendered
                // multi-colour on most fonts and visibly broke the otherwise hairline
                // chrome aesthetic. ⌂ ◉ ⌕ ▸ are all single-codepoint and share the
                // chrome ink colour through their parent Text style.
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "⌂", label = "TODAY", onClick = onOpenDashboard)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "◉", label = "ASSIST", onClick = onOpenAssist)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "⌕", label = "SEARCH", onClick = onOpenSearch)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "▸", label = "SCENES", onClick = onOpenScenes)
            }
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "⚙", label = "AUTO", onClick = onOpenAutomations)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "↯", label = "ENERGY", onClick = onOpenEnergy)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "⌖", label = "ZONES", onClick = onOpenZones)
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "▭", label = "DEVICE", onClick = onOpenDevice)
            }
            Spacer(Modifier.height(6.dp))
            // Third row — just the ALERTS tile for now (single-wide
            // since BROWSE grew past the two-row 2×4 layout). Future
            // tiles can fill the remaining three slots.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DrawerGlyph(modifier = Modifier.weight(1f), glyph = "!", label = "ALERTS", onClick = onOpenNotifications)
                // EDIT SIDEBAR — opens the sidebar-config surface so phone users (no nav
                // rail) can manage the PINNED / DASHBOARDS sections of this very drawer.
                // onOpenRoute already closes the sheet before navigating.
                DrawerGlyph(
                    modifier = Modifier.weight(1f),
                    glyph = "✎",
                    label = "EDIT SIDEBAR",
                    onClick = { onOpenRoute(com.github.itskenny0.r1ha.nav.Routes.SIDEBAR_CONFIG) },
                )
                Spacer(Modifier.weight(2f))
            }

            // ── GO TO row — context-aware jumps for the focused card ──
            // Adapts to the focused card's domain: a media_player offers a
            // jump to Media Browse, a person to Persons, weather to Weather,
            // and stateful/numeric domains to that entity's History. Cameras
            // is always offered (it has no card archetype). Laid out as a
            // single row of up-to-four weighted tiles padded to a constant
            // four columns so the glyphs line up with the BROWSE grid above.
            if (contextJumps.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(text = "GO TO", style = R1.labelMicro, color = R1.InkSoft)
                if (focusedName != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = focusedName.uppercase(),
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    contextJumps.take(4).forEach { jump ->
                        DrawerGlyph(
                            modifier = Modifier.weight(1f),
                            glyph = jump.glyph,
                            label = jump.label,
                            onClick = jump.onClick,
                        )
                    }
                    // Pad to four columns so a one- or two-jump row keeps the
                    // tiles the same width as the BROWSE grid rather than
                    // stretching edge-to-edge.
                    repeat(4 - contextJumps.take(4).size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }

            // ── DASHBOARDS row — user-pinned Lovelace views ──
            // The phone surface for dashboard pins (phones show no nav rail, so
            // the rail's dashboard tier lives here instead). A clearly-labelled
            // group of glyph tiles, laid out in rows of four to line up with the
            // BROWSE grid. Each tile navigates straight to its pinned route. The
            // generic ▤ glyph matches the rail's dashboard mark.
            if (pinnedDashboards.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(text = "DASHBOARDS", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                pinnedDashboards.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { dash ->
                            DrawerGlyph(
                                modifier = Modifier.weight(1f),
                                glyph = "▤",
                                label = dash.title.uppercase(),
                                onClick = { onOpenDashboardRoute(dash.route) },
                            )
                        }
                        // Pad the last (possibly short) row to four columns so the
                        // tiles keep the BROWSE grid's width.
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }

            // ── PINNED row — user-pinned nav surfaces ──
            // The phone surface for the tablet nav rail's pinned-surface tier
            // (phones show no rail, so the rail's pins live here instead). A
            // clearly-labelled group of glyph tiles, laid out in rows of four to
            // line up with the BROWSE grid. Each tile navigates straight to its
            // pinned Routes id. The surface's own monospace glyph (from
            // PinnableSurfaces) is reused so the mark matches the rail.
            if (pinnedSurfaces.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(text = "PINNED", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(6.dp))
                pinnedSurfaces.chunked(4).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        rowItems.forEach { surface ->
                            DrawerGlyph(
                                modifier = Modifier.weight(1f),
                                glyph = surface.glyph,
                                label = surface.label.uppercase(),
                                onClick = { onOpenRoute(surface.route) },
                            )
                        }
                        // Pad the last (possibly short) row to four columns so the
                        // tiles keep the BROWSE grid's width.
                        repeat(4 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(14.dp))
            // 'Turn all on' — one-tap fire. Lights/switches/fans coming on
            // accidentally is recoverable (re-tap the card or the all-off
            // route), so the safety bar can be lower than for turn-off.
            R1Button(
                text = "TURN ALL ON",
                onClick = onAllOn,
                modifier = Modifier.fillMaxWidth(),
                accent = R1.AccentGreen,
            )
            // 'Pause N media' — surfaces only when there's at least one
            // playing media_player on the active page. Single-tap fire
            // since pausing is non-destructive and the user can immediately
            // tap play on any card to resume.
            if (playingMediaCount > 0) {
                Spacer(Modifier.height(8.dp))
                R1Button(
                    text = "PAUSE $playingMediaCount MEDIA",
                    onClick = onPauseMedia,
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
            }
            Spacer(Modifier.height(8.dp))
            // 'Turn all off' — two-stage confirm. Off is the more disruptive
            // direction (lights you wanted on, media you wanted playing) so
            // the second-tap guard prevents muscle-memory accidents.
            R1Button(
                text = if (armed.value) "CONFIRM · TURN OFF $cardCount" else "TURN ALL OFF",
                onClick = { if (armed.value) onAllOff() else armed.value = true },
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusAmber,
            )
            if (armed.value) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Fires turn_off on every controllable entity on this page (lights, switches, fans, media_players, covers).",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            Spacer(Modifier.height(8.dp))
            R1Button(
                text = stringResource(R.string.dialog_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        }
    }
}

/**
 * Top chrome — hamburger left, vertical position pip + counter centre, settings gear right
 * with a small connection-state dot overlay. Sits *above* the pager so the peek strip
 * doesn't bleed visually into the icons.
 */
@Composable
private fun ChromeRow(
    connection: ConnectionState,
    /** True when the WS reports Connected but state_changed events have stopped
     *  flowing — the soft-broken-proxy case the REST heartbeat fallback
     *  mitigates. The connection-state dot picks up an amber tint when this
     *  goes true so the user has a visible signal that the WS isn't carrying
     *  its weight even though the state machine reads Connected. Defaults to
     *  false so previews / non-card-stack callers stay on the existing
     *  hide-when-Connected behaviour. */
    wsSilent: Boolean = false,
    cardsCount: Int,
    currentIndex: Int,
    showCounter: Boolean,
    onOpenFavoritesPicker: () -> Unit,
    onOpenSettings: () -> Unit,
    onEditActive: () -> Unit = {},
    /** Tap the DETAIL (...) button to open the ultra-detail more-info sheet for
     *  the focused card. Visible affordance so detail is reachable on every card
     *  (including controllable ones, where the wheel drives the value instead).
     *  Defaulted to a no-op for previews. */
    onMoreInfoActive: () -> Unit = {},
    /** Tap on the position pip / counter opens the jump-to-card picker. Null in
     *  previews; defaults to a no-op so the pip becomes inert when there's no
     *  picker to open. */
    onTapCounter: () -> Unit = {},
    /** Long-press on the hamburger always opens the quick-actions sheet, so the rich modal
     *  stays reachable even when tap is bound to the slide-out. Defaulted to a no-op so
     *  existing previews that don't care about the gesture don't need to thread it through. */
    onLongPressHamburger: () -> Unit = {},
    /** Tap on the hamburger opens the navigation slide-out (or the QuickActions sheet when
     *  the slide-out isn't available). Defaults to [onLongPressHamburger] so previews and
     *  callers that don't distinguish the gestures keep the single-action behaviour. */
    onTapHamburger: () -> Unit = onLongPressHamburger,
    /** Long-press on the settings gear opens the Quick Search dialog —
     *  the natural "I'm looking for X" affordance from anywhere on
     *  the card stack. Defaults to a no-op for preview compatibility. */
    onLongPressGear: () -> Unit = {},
    /** Tap the mic glyph to jump to the HA Assist surface. Surfaced
     *  in the chrome rather than buried in Settings so 'ask HA' is
     *  a single-tap action from anywhere on the card stack. */
    onOpenAssist: () -> Unit = {},
    solidBackdrop: Boolean = true,
    /** Render a tiny BATTERY% pill in the right cluster — used only when
     *  the system status bar is hidden AND the user opted into the
     *  indicator via Settings → Behaviour. Defaults to false so previews
     *  + the typical "status bar visible" path stay un-cluttered. */
    showBatteryIndicator: Boolean = false,
    /** Tap the battery pill to open the Device screen — local controls
     *  for brightness, volume, flashlight. Defaults to a no-op for
     *  previews so the indicator stays non-interactive when the
     *  caller doesn't wire it. */
    onOpenDevice: () -> Unit = {},
    /** Right-cluster button order + visibility. The list is rendered left→right;
     *  each entry's [com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig.enabled]
     *  gates whether the matching widget shows up at all. Defaults to the canonical
     *  pre-config order ([BATTERY, ASSIST_MIC, EDIT, GEAR], all enabled) so previews
     *  that don't pass a value render the existing layout. The battery slot ALSO
     *  honours [showBatteryIndicator] — the user must have hidden the status bar
     *  and opted into the on-chrome pill before the BATTERY config flag takes
     *  effect, otherwise we'd be redundant with the system status bar. */
    chromeButtons: List<com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig> = listOf(
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig(
            com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY,
        ),
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig(
            com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.ASSIST_MIC,
        ),
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig(
            com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.DETAIL,
        ),
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig(
            com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.EDIT,
        ),
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig(
            com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR,
        ),
    ),
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // Solid backdrop hides the previous card's tail as it slides into the
            // 72 dp content-padding area above the active card. Transparent backdrop
            // keeps the deck-overlap aesthetic where the user can see the preceding
            // card peeking under the chrome.
            .then(if (solidBackdrop) Modifier.background(R1.Bg) else Modifier)
            // Consume any tap that lands in the chrome strip but misses one of the
            // explicit buttons (hamburger / pip / pencil / gear). Without this, a
            // tap in the SpaceBetween gaps falls through to the pager content below,
            // which extends UP into the contentPadding zone — the user reported
            // 'top-left corner of cards turns them on' because that's where the gap
            // between hamburger and pip sits. Empty-onClick clickable with no
            // indication / interactionSource so the chrome doesn't paint a ripple.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = {},
            )
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Top-left cluster: menu hamburger + favourites star, side by side. The
        // hamburger now opens the navigation drawer / sidebar (the same surface the
        // tablet nav rail provides); favourites gets its own dedicated star button
        // immediately to its right. Both are compact 32-44 dp tap targets so the
        // pair still fits the R1's 240 dp width alongside the right cluster.
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Hamburger — tap opens the navigation slide-out (or the QuickActions sheet when
            // the slide-out isn't available); long-press always opens the QuickActions sheet.
            // r1RowPressable keeps both gestures on the same tile.
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .r1RowPressable(
                        onTap = onTapHamburger,
                        onLongPress = onLongPressHamburger,
                        contentDescription = "Open menu",
                    ),
                contentAlignment = Alignment.Center,
            ) {
                HamburgerGlyph(size = 18.dp)
            }
            // Favourites — opens the favourites picker (the action the hamburger tap
            // used to do). Clear star mark so it reads as "favourites" at a glance.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .r1Pressable(onOpenFavoritesPicker, contentDescription = "Open favourites"),
                contentAlignment = Alignment.Center,
            ) {
                com.github.itskenny0.r1ha.ui.components.FavoritesGlyph(size = 16.dp)
            }
        }

        // Centre: vertical position indicator. Hairline track + a 3dp filled segment at the
        // current page. Visually communicates "vertical stack" — wheel of cards going up
        // and down — rather than the horizontal row of dots that read as left/right.
        if (showCounter) {
            // The pip carries its own r1Pressable so the tap target follows the
            // intrinsic pill width — wrapping it in a fixed-size Box clipped the
            // counter ("1/30") onto multiple lines when the rounded-rect pill ran
            // out of horizontal room. Tap opens the jump-to-card picker.
            VerticalPagePip(
                count = cardsCount,
                current = currentIndex,
                onClick = onTapCounter,
            )
        } else {
            Spacer(Modifier.size(44.dp))
        }

        // Top-right cluster: order + visibility comes from [chromeButtons]. The cluster
        // is a Row whose children are emitted in list order so the user's Settings →
        // Chrome buttons reorder shows up exactly here. The previous version
        // hard-coded the BATTERY → MIC → EDIT → GEAR order with a fixed conditional
        // for each.
        //
        // The connection-state dot (and its IDLE / CONNECTING amber-pulse / silent-WS
        // amber / disconnected red logic) stays anchored to the GEAR button — both as
        // the natural 'system status' surface and because it's the only button GEAR
        // can't be turned off, guaranteeing the dot always has a host. If the user
        // moves GEAR mid-cluster, the dot follows.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val visibleButtons = chromeButtons.filter { cfg ->
                when (cfg.ref) {
                    // BATTERY needs all three gates: the user's flag in this list, the
                    // system-bar-hidden setting, and the show-battery-when-hidden
                    // opt-in. Otherwise we'd be redundant with Android's own status
                    // bar (or hide a battery readout the user can't get anywhere else).
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY ->
                        cfg.enabled && showBatteryIndicator
                    // GEAR's enabled bit is forced-true at the repo level — the user
                    // can't lock themselves out of Settings.
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR -> true
                    // DETAIL no longer renders in the chrome row — its "..." now
                    // lives on the card body (bottom-right). The config entry's
                    // enabled flag still governs that on-card button (see
                    // onCardMoreInfo), so the toggle is preserved, just relocated.
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.DETAIL -> false
                    else -> cfg.enabled
                }
            }
            visibleButtons.forEachIndexed { idx, cfg ->
                when (cfg.ref) {
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY -> {
                        com.github.itskenny0.r1ha.ui.components.BatteryIndicator(
                            onClick = onOpenDevice,
                        )
                    }
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.ASSIST_MIC -> {
                        // Custom-drawn glyph (not the 🎤 emoji) so the visual weight
                        // matches HamburgerGlyph on the opposite side of the chrome.
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .r1Pressable(onOpenAssist, contentDescription = "Open Assist"),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.github.itskenny0.r1ha.ui.components.AssistMicGlyph(size = 16.dp)
                        }
                    }
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.DETAIL -> {
                        // Detail "..." — opens the ultra-detail more-info sheet for the
                        // focused card. A visible affordance so detail is reachable on
                        // every card; on controllable cards the wheel drives the value,
                        // so without this button detail had no entry point there.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .r1Pressable(onMoreInfoActive, contentDescription = "Card details"),
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                repeat(3) {
                                    Box(
                                        modifier = Modifier
                                            .size(4.dp)
                                            .clip(CircleShape)
                                            .background(R1.Ink.copy(alpha = 0.85f)),
                                    )
                                }
                            }
                        }
                    }
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.EDIT -> {
                        // Edit pencil — opens the customize dialog for the active card.
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .r1Pressable(onEditActive, contentDescription = "Customize card"),
                            contentAlignment = Alignment.Center,
                        ) {
                            com.github.itskenny0.r1ha.ui.components.EditGlyph(
                                size = 14.dp,
                                tint = R1.Ink.copy(alpha = 0.85f),
                            )
                        }
                    }
                    com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR -> {
                        // Settings gear + connection-state dot overlay.
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .clip(CircleShape)
                                .r1RowPressable(
                                    onTap = onOpenSettings,
                                    onLongPress = onLongPressGear,
                                    contentDescription = "Settings",
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            SettingsCogGlyph(size = 18.dp)
            // Connection dot: only visible when NOT connected (subtle when healthy, loud
            // when not). Animated colour transition so the amber→red flip on a failed
            // reconnect reads as deliberate rather than a UI bounce; AnimatedVisibility on
            // the dot itself so its appear/disappear doesn't snap when state crosses the
            // Connected boundary.
            val statusColor = when (connection) {
                // Connected: hide the dot UNLESS the WS has gone silent and the
                // REST heartbeat fallback is doing the lifting. Amber in that
                // case so the user sees the soft-broken state instead of a
                // misleadingly-green chrome.
                is ConnectionState.Connected -> if (wsSilent) R1.StatusAmber else null
                ConnectionState.Idle,
                ConnectionState.Connecting,
                ConnectionState.Authenticating -> R1.StatusAmber
                else -> R1.StatusRed
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = statusColor != null,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 8.dp, end = 8.dp),
            ) {
                // Lock in the *last non-null* colour so the dot doesn't flash a default
                // colour during the exit animation when state transitions back to Connected.
                val animatedColor by androidx.compose.animation.animateColorAsState(
                    targetValue = statusColor ?: R1.StatusAmber,
                    label = "conn-dot-color",
                )
                // While the connection is amber (Idle/Connecting/Authenticating) the
                // Infinite-pulse alpha while connecting / authenticating. The
                // dot pulses between 40% and 100% alpha to signal 'work in
                // progress'. Conditionally composed so the InfiniteTransition
                // coroutine only runs when actually needed — otherwise it
                // burns frames recomputing pulse values for an alpha that's
                // gated to 1f anyway.
                val isWorking = connection is ConnectionState.Connecting ||
                    connection is ConnectionState.Authenticating ||
                    connection == ConnectionState.Idle
                if (isWorking) {
                    val transition = androidx.compose.animation.core.rememberInfiniteTransition(
                        label = "conn-dot-pulse",
                    )
                    val pulse by transition.animateFloat(
                        initialValue = 0.4f,
                        targetValue = 1f,
                        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                            animation = androidx.compose.animation.core.tween(
                                durationMillis = 750,
                                easing = androidx.compose.animation.core.FastOutSlowInEasing,
                            ),
                            repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                        ),
                        label = "conn-dot-pulse-alpha",
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(animatedColor.copy(alpha = pulse.coerceIn(0f, 1f))),
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(animatedColor),
                    )
                }
            }
                        }  // end GEAR Box
                    }  // end GEAR -> when branch
                }  // end when (cfg.ref)
                // Inter-button spacer — small (2 dp) so adjacent monochrome glyphs
                // don't crowd, but no wider than the original layout had between
                // mic / pencil / gear. Skip after the last button so the cluster
                // doesn't get an unbalanced right-edge padding.
                if (idx < visibleButtons.lastIndex) {
                    Spacer(Modifier.width(2.dp))
                }
            }  // end visibleButtons.forEachIndexed
        }  // end right-cluster Row
    }
}

/**
 * Transient hint surfaced on the card stack when the user spins the wheel on a card
 * that has nothing for the wheel to drive (sensors, actions, non-scalar switches with
 * wheel-toggles-switches off). Tells them how to actually navigate the deck. The
 * caller drives visibility via a monotonically-increasing [triggerAt] timestamp; each
 * new value re-arms the 2-second visibility window so a rapid wheel spin keeps the
 * hint on screen continuously.
 */
@Composable
private fun BoxScope.WheelHintOverlay(state: androidx.compose.runtime.MutableLongState) {
    // Read the trigger timestamp INSIDE this composable so only this scope
    // (not the parent CardStackScreen) subscribes to the State changes —
    // see call-site comment for the perf rationale.
    val triggerAt = state.longValue
    val visible = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(triggerAt) {
        if (triggerAt > 0L) {
            visible.value = true
            kotlinx.coroutines.delay(2_000)
            visible.value = false
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = visible.value,
        enter = androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.fadeOut(),
        modifier = Modifier
            .align(Alignment.TopCenter)
            // Sit just below the chrome row (~52 dp tall) so the hint reads as
            // belonging to the current card without overlapping the centre pip.
            .padding(top = 56.dp, start = 24.dp, end = 24.dp),
    ) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeRound)
                .background(R1.Bg.copy(alpha = 0.92f))
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = "WHEEL DOES NOTHING HERE · SWIPE OR TAP THE PIP",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * Fullscreen jump-to-card list — opens from a tap on the chrome's position pip and
 * lets the user pick a card by friendly name instead of scrolling through the deck.
 * Mirrors the visual shape of [EffectPickerSheet] / [SelectPickerSheet] so the user
 * only has to learn one picker convention. The current card is highlighted; tapping
 * any row dispatches an animateScrollToPage that handles infinite-scroll's
 * virtual-page math at the call site.
 */
@Composable
private fun JumpToCardSheet(
    cards: List<com.github.itskenny0.r1ha.core.ha.EntityState>,
    currentIndex: Int,
    /** Hoisted LazyListState so the screen-level wheel handler can scroll the list
     *  while the overlay is open. */
    listState: androidx.compose.foundation.lazy.LazyListState,
    onPick: (Int) -> Unit,
    onReorder: (fromIndex: Int, toIndex: Int) -> Unit,
    /** Open the per-card context menu (move-to-page, remove). Surfaced by the
     *  '…' chip on each row — replaces the prior pair of inline '✕' + long-press
     *  affordances. Callback receives the row's index; the screen resolves that
     *  to an entity_id and shows [CardContextMenu]. */
    onOpenMenu: (index: Int) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    // Cap + centre the list column on big tiers so the rows don't stretch into
    // one wall-wide line on a 13" panel. Dp.Unspecified on R1 / compact keeps the
    // full-bleed list the tiny panel wants (widthIn(max = Unspecified) is a no-op).
    val jumpMaxWidth = rememberResponsiveDimens().maxContentWidth
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = jumpMaxWidth)
                .fillMaxSize()
                .padding(horizontal = 18.dp, vertical = 14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "JUMP TO", style = R1.sectionHeader, color = R1.Ink)
                Spacer(Modifier.weight(1f))
                Text(
                    text = "${currentIndex + 1} / ${cards.size}",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .r1Pressable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(text = "CLOSE", style = R1.labelMicro, color = R1.InkSoft)
                }
            }
            Text(
                text = "TAP JUMP · LONG-PRESS DRAG · '…' MENU · WHEEL SCROLLS",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp),
            )
            // On open, snap the list so the current card is roughly centred — on a
            // 30-card deck the user would otherwise have to wheel down to find it.
            // Keyed on currentIndex so a re-open after a deck swap also re-centres.
            androidx.compose.runtime.LaunchedEffect(currentIndex) {
                val target = (currentIndex - 2).coerceAtLeast(0)
                listState.scrollToItem(target)
            }
            com.github.itskenny0.r1ha.ui.components.DragReorderColumn(
                items = cards,
                keyOf = { it.id.value },
                onReorder = onReorder,
                modifier = Modifier.fillMaxSize(),
                listState = listState,
            ) { card, dragHandle, isDragging ->
                val idx = cards.indexOf(card)
                JumpRow(
                    index = idx,
                    name = card.friendlyName,
                    domainPrefix = card.id.domain.prefix.uppercase(),
                    isActive = idx == currentIndex,
                    isDragging = isDragging,
                    onClick = { onPick(idx) },
                    onOpenMenu = { onOpenMenu(idx) },
                    dragHandle = dragHandle,
                )
            }
        }
    }
}

/** Local alias for the foundation verticalScroll modifier so the picker call site
 *  reads cleanly without a fully-qualified Modifier.then() dance. */
private fun Modifier.androidxVerticalScroll(
    state: androidx.compose.foundation.ScrollState,
): Modifier = this.then(verticalScroll(state))

@Composable
private fun JumpRow(
    index: Int,
    name: String,
    domainPrefix: String,
    isActive: Boolean,
    isDragging: Boolean,
    onClick: () -> Unit,
    onOpenMenu: () -> Unit,
    dragHandle: Modifier,
) {
    // Drag-handle modifier wraps the whole row so the user can long-press anywhere
    // on the row to grab it. r1Pressable for the tap-to-jump action sits on top —
    // single tap fires onClick, long-press promotes to drag. The '…' chip on the
    // right opens the per-card context menu (move-to-page, remove); its own
    // r1Pressable absorbs the tap so it doesn't fall through to the row jump.
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(R1.ShapeS)
            .background(
                when {
                    isDragging -> R1.AccentWarm.copy(alpha = 0.65f)
                    isActive -> R1.AccentWarm
                    else -> R1.SurfaceMuted
                },
            )
            .then(dragHandle)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%2d".format(java.util.Locale.US, index + 1),
                style = R1.labelMicro,
                color = if (isActive) R1.Bg else R1.InkMuted,
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = R1.body,
                    color = if (isActive) R1.Bg else R1.Ink,
                    maxLines = 2,
                )
                Text(
                    text = domainPrefix,
                    style = R1.labelMicro,
                    color = if (isActive) R1.Bg.copy(alpha = 0.7f) else R1.InkSoft,
                )
            }
            if (isActive) {
                Text(text = "●", style = R1.labelMicro, color = R1.Bg)
                Spacer(Modifier.width(8.dp))
            }
            // '…' chip — opens the per-card context menu. Replaces the previous
            // inline '✕' remove button; the menu now holds every per-card action
            // (move-to-page + remove) in one place so the row stays clean. Same
            // sizing as the old remove chip so muscle memory carries over.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.Bg.copy(alpha = if (isActive) 0.4f else 0.7f))
                    .r1Pressable(onClick = onOpenMenu, contentDescription = "Card actions for $name")
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "…",
                    style = R1.labelMicro,
                    color = if (isActive) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

/**
 * "Mission-control" vertical position pip: hairline track + accent-coloured thumb whose
 * position maps to the current page, with a small "N/M" counter on the right. Whole thing
 * sits inside a dark pill so it stays legible against the Colourful Cards gradient.
 */
@Composable
private fun VerticalPagePip(count: Int, current: Int, onClick: (() -> Unit)? = null) {
    val trackHeight = 22.dp
    val thumbHeight = 6.dp
    // Clamp target to [0, 1] — `current` can momentarily exceed `count - 1`
    // when a deck shrinks under the pager (e.g., between observeFavorites
    // emissions). Without the clamp targetFrac > 1 and the spring
    // animation's overshoot landed negative on the *other* side too.
    val targetFrac = if (count <= 1) 0f
        else (current.toFloat() / (count - 1).toFloat()).coerceIn(0f, 1f)
    // CRITICAL: use DampingRatioNoBouncy (was LowBouncy) so the spring
    // never overshoots BELOW 0. The bouncy spring would briefly visit
    // ~-0.05 during settle — and the displayed fraction is fed into
    // .padding(top = travel * animatedFrac), which Compose hard-throws
    // on with IllegalArgumentException('Padding must be non-negative').
    // Confirmed by a user crash trace at r1ha-2026.05.14.1741. Pair with
    // a defensive .coerceIn at the use site so any future change to the
    // animation spec can't reintroduce the bug.
    val animatedFrac by androidx.compose.animation.core.animateFloatAsState(
        targetValue = targetFrac,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = androidx.compose.animation.core.Spring.DampingRatioNoBouncy,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMediumLow,
        ),
        label = "r1-pip-thumb",
    )
    Row(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.Bg.copy(alpha = 0.75f))
            // Pressable applied on the existing pill rather than a wrapping Box so
            // the tap target follows the intrinsic width (which contains the counter
            // text). Wrapping in a fixed Box.size(...) clipped "1/30" to two lines.
            .let { m -> if (onClick != null) m.r1Pressable(onClick = onClick) else m }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vertical track + thumb.
        Box(
            modifier = Modifier
                .height(trackHeight)
                .width(8.dp),
        ) {
            // Track (dim hairline).
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .height(trackHeight)
                    .width(2.dp)
                    .background(R1.Hairline),
            )
            // Thumb — offset down by the animated fraction of available travel.
            // Critically: use Modifier.offset (not .padding) for the dynamic Dp.
            // Modifier.padding throws IllegalArgumentException on negative
            // values, and any spring overshoot or stale arithmetic that
            // briefly visits negative territory would crash the whole
            // composition with 'Padding must be non-negative'.
            // Modifier.offset accepts any Dp (positive, negative, or zero)
            // and just translates the layout — never throws. SwitchCard's
            // ON/OFF thumb hit the same issue and adopted .offset for the
            // same reason; same pattern applies here.
            val travel = trackHeight - thumbHeight
            val safeFrac = animatedFrac.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .offset(y = travel * safeFrac)
                    .height(thumbHeight)
                    .width(4.dp)
                    .background(R1.AccentWarm),
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${current + 1}/$count",
            style = R1.numeralS,
            color = R1.Ink,
        )
    }
}

