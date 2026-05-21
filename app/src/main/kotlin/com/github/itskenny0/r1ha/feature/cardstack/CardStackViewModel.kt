package com.github.itskenny0.r1ha.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.DebouncedCaller
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.WheelSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * @Stable: every field is `val` and the maps reference immutable contents
 * (they're rebuilt on each observe emission). The annotation tells Compose
 * to use the data class's generated equals to decide whether downstream
 * composables can skip — without it, every wheel-event-driven state change
 * would force every reader of the state to recompose even when their slice
 * didn't actually change.
 */
@androidx.compose.runtime.Stable
data class CardStackUiState(
    /** Ordered by the user's favorites list (NOT by entity_id). The HA cache snapshot —
     *  what the server has confirmed. The UI should bind to [displayedCards] instead so
     *  optimistic wheel/tap updates are visible *immediately* rather than after the round-
     *  trip; this raw view is kept so the activeState getter and the optimistic-filter in
     *  `observeFavorites` can still see "what HA actually thinks" separately. */
    val cards: List<EntityState> = emptyList(),
    /**
     * Parallel index from EntityId to its slot in [cards] for O(1) lookups on the
     * hot service-dispatch path (the wheel can fire 30 times/sec, and the debouncer
     * callback previously did a `cards.firstOrNull { it.id == entityId }` scan of
     * up to ~30 favourites on every detent). Computed in step with [cards] so the
     * two never drift; readers must never mutate the map directly.
     */
    val cardsById: Map<EntityId, EntityState> = emptyMap(),
    val currentIndex: Int = 0,
    /** Optimistic percent overrides per entity, applied while waiting for HA state_changed. */
    val optimisticPercents: Map<EntityId, Int> = emptyMap(),
    /**
     * Optimistic select-option overrides per select / input_select entity.
     * Applied to the rendered card so the wheel-cycle feels instant
     * (no waiting for HA's state_changed echo, which can be 200-400 ms
     * away on a busy server). Cleared by the cache observer once HA
     * confirms the new option matches what the user picked.
     */
    val optimisticSelectOptions: Map<EntityId, String> = emptyMap(),
    /**
     * Number of entity IDs in the user's favourites list, regardless of whether HA has
     * sent state for them yet. Distinguishes "no favourites set" (zero) from "favourites
     * set but waiting on HA" (non-zero with empty `cards`).
     */
    val favouritesCount: Int = 0,
    /**
     * Flipped to true on the FIRST emission from the settings flow. Distinguishes
     * "settings haven't loaded yet" (cold start, DataStore I/O still in flight)
     * from "settings loaded but the user has no favourites". Without this flag
     * the screen briefly showed a "No favourites" message during the first ~50
     * ms before settings arrived, which read as a permanent state to the user.
     * Now the screen shows a plain throbber until [settingsLoaded] is true, then
     * decides between [EmptyState] and [VerticalCardPager] based on real data.
     */
    val settingsLoaded: Boolean = false,
    /**
     * Cards per page, keyed by page id. Built once per state emission so every
     * horizontal-pager page can render its own deck without each composable
     * reaching back into [SettingsRepository.settings]. The active page's slice
     * matches [cards]; non-active pages are populated too so the user can swipe
     * horizontally and see the full target deck instantly (no flash of an empty
     * stack while observation 'rebinds'). Map order matches page order in
     * settings.
     */
    val cardsByPage: Map<String, List<EntityState>> = emptyMap(),
    /** Per-page deck scroll position, keyed by page id. Preserved across page
     *  swipes so the user lands where they left off when they swipe back to a
     *  page. */
    val indexByPage: Map<String, Int> = emptyMap(),
    /** Identifier of the currently-active page, mirrored from
     *  [com.github.itskenny0.r1ha.core.prefs.AppSettings.activePageId] so wheel
     *  routing + the screen-level pager state can sync without re-reading
     *  settings on every wheel event. */
    val activePageId: String = "",
    /** Ordered list of pages with stable ids, mirrored from
     *  [com.github.itskenny0.r1ha.core.prefs.AppSettings.pages]. Drives the
     *  horizontal pager's pageCount and the tab strip's chip order. */
    val pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage> = emptyList(),
    /**
     * Per-light transient wheel-mode override. Defaults to BRIGHTNESS for any light
     * the user hasn't toggled. Not persisted — the user re-picks each session, which
     * matches the "tap the readout to cycle" UX (a one-shot adjustment rather than a
     * permanent preference).
     */
    val lightWheelMode: Map<EntityId, com.github.itskenny0.r1ha.core.ha.LightWheelMode> = emptyMap(),
) {
    /**
     * Apply optimistic overrides on top of [cards] so the UI sees the user's intent
     * immediately — without this, the brightness bar only "updates" when HA echoes the
     * state_changed event back, which is the round-trip latency the user perceived as
     * sluggishness. The optimistic clears automatically once HA confirms (see
     * `observeFavorites`'s filter) — at that point the cached value matches the override
     * and the result of [applyOptimistic] is identical to the cached value, so the UI
     * doesn't bounce.
     */
    val displayedCards: List<EntityState>
        get() = if (optimisticPercents.isEmpty() && optimisticSelectOptions.isEmpty()) {
            // Common case: no wheel/tap is in flight. Skip the allocation of
            // the mapped list entirely — recompositions during static viewing
            // (e.g. swiping between pages, just looking at the deck) were
            // allocating a fresh List<EntityState> on every recomp for no
            // reason. cards is already a List<EntityState> and applyOptimistic
            // with a null override returns the same instance.
            cards
        } else {
            cards.map { state ->
                state.applyOptimistic(
                    optimisticPercents[state.id],
                    optimisticSelectOptions[state.id],
                )
            }
        }

    val activeState: EntityState?
        get() {
            // One getOrNull instead of two (the old shape called it once for
            // the card lookup and again inside the optimisticPercents key).
            // Short-circuit the optimistic application when nothing is in
            // flight for the active card.
            val card = cards.getOrNull(currentIndex) ?: return null
            val pctOverride = optimisticPercents[card.id]
            val selOverride = optimisticSelectOptions[card.id]
            if (pctOverride == null && selOverride == null) return card
            return card.applyOptimistic(pctOverride, selOverride)
        }

    /**
     * Convenience for the UI: the currently-displayed (post-optimistic) card. Same as
     * [activeState] but expressed as a `displayedCards[currentIndex]` lookup for symmetry.
     */
    val displayedActiveState: EntityState?
        get() = displayedCards.getOrNull(currentIndex)
}

/** Layer the optimistic override(s) onto a cached state. */
private fun EntityState.applyOptimistic(
    percentOverride: Int?,
    selectOverride: String? = null,
): EntityState {
    val pctApplied = when {
        percentOverride == null -> this
        supportsScalar -> copy(percent = percentOverride)
        else -> copy(percent = percentOverride, isOn = percentOverride > 0)
    }
    if (selectOverride == null) return pctApplied
    // For select / input_select entities the displayed value is
    // currentOption. We also rewrite rawState so themes that fall back to
    // it (SelectCard does, for the "unavailable" path) reflect the pick
    // optimistically too.
    return pctApplied.copy(currentOption = selectOverride, rawState = selectOverride)
}

class CardStackViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
    private val wheelInput: WheelInput,
) : ViewModel() {

    private val _state = MutableStateFlow(CardStackUiState())
    val state: StateFlow<CardStackUiState> = _state

    /** Snapshot of WheelSettings refreshed by the settings observer. We hold this in a
     *  separate field instead of calling settings.first() per wheel event — even though a
     *  hot Flow's first() is fast, doing it 50 times/sec inside the wheel collector lets
     *  events queue + drop in the SharedFlow buffer, manifesting as the readout jumping in
     *  irregular chunks rather than tracking the wheel. */
    @Volatile private var cachedWheel: WheelSettings = WheelSettings()

    /** Recent wheel-event timestamps; used to compute rate for acceleration. */
    private val wheelTimestamps = ArrayDeque<Long>()
    private val rateWindowMillis = 250L

    /** Latest entityOverrides snapshot — same caching pattern as cachedWheel so the
     *  debouncer doesn't have to suspend on settings.first() per fire. Updated by the
     *  init observer below. */
    @Volatile private var cachedOverrides: Map<String, com.github.itskenny0.r1ha.core.prefs.EntityOverride> = emptyMap()

    private val debounced = DebouncedCaller<EntityId, Int>(
        scope = viewModelScope,
        // Trailing window — short so a quick tap-tap dialled by the user fires the
        // wire call almost as fast as the optimistic UI updates. Used to be 250 ms
        // which felt like 'state commits on release' for any in-flight gesture.
        debounceMillis = 60L,
        // Force-fire after 150 ms of continuous events on the same entity so a
        // sustained wheel spin or touch drag still flushes mid-gesture rather than
        // waiting for the user to pause. The repo's own debouncer (60 ms there too)
        // adds a thin second layer of coalescing; in practice this means HA sees
        // ~5-8 service calls per second during a continuous gesture, the optimistic
        // UI tracks every wheel detent instantly, and they reconcile via state_changed
        // within a frame of HA echoing the final value back.
        maxIntervalMillis = 150L,
    ) { entityId, pct ->
        // Look up the entity's current state to pick the right service shape: scalar entities
        // get setPercent (turn_on with brightness_pct, set_percentage, set_cover_position,
        // volume_set), switch entities get the discrete setSwitch (turn_on/turn_off, open/
        // close_cover, media_play/media_pause). Reading the state at fire-time means the
        // wheel-up→ON and wheel-down→OFF intent stays accurate even if HA's state has shifted
        // during the 250 ms debounce.
        val entityState = _state.value.cardsById[entityId]
        val call = when {
            entityState?.supportsScalar == false -> {
                R1Log.i("CardStack.debounced", "sending setSwitch($entityId, on=${pct > 0})")
                ServiceCall.setSwitch(entityId, on = pct > 0)
            }
            // Climate scalar — convert the wheel's 0..100 into the entity's temperature
            // range using minRaw/maxRaw. Falls through to setPercent if the range is
            // missing (which shouldn't happen because supportsScalar=true requires it,
            // but defensive code in case the cached state is stale).
            (entityState?.id?.domain == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                entityState?.id?.domain == com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER) &&
                entityState.minRaw != null && entityState.maxRaw != null -> {
                // Snap to the nearest 0.5° so the wheel's percent-step (~1.67% per detent
                // for a 30° span) doesn't accumulate floating-point drift; the user gets
                // reliable half-degree increments matching thermostat conventions.
                // water_heater shares the same set_temperature service signature.
                val raw = entityState.minRaw + (pct / 100.0) * (entityState.maxRaw - entityState.minRaw)
                val temp = Math.round(raw * 2.0) / 2.0
                R1Log.i("CardStack.debounced", "sending setTemperature($entityId, ${"%.1f".format(temp)})")
                ServiceCall.setTemperature(entityId, temp)
            }
            // Number / input_number scalar — same shape as climate but emits set_value
            // on the entity's own domain. Wheel's 0..100 maps onto min..max from attrs.
            entityState != null && entityState.minRaw != null && entityState.maxRaw != null &&
                (entityState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.NUMBER ||
                    entityState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.INPUT_NUMBER) -> {
                val raw = entityState.minRaw + (pct / 100.0) * (entityState.maxRaw - entityState.minRaw)
                // Snap to the entity's native `step` so a value like 42.7341 lands on a
                // clean multiple (42.7 if step=0.1, 43 if step=1, 45 if step=5). HA
                // would round anyway on receipt, but doing it ourselves keeps the
                // displayed value honest and the optimistic state coherent.
                val step = entityState.step?.takeIf { it > 0.0 }
                val snapped = if (step != null) Math.round(raw / step) * step else raw
                val clamped = snapped.coerceIn(entityState.minRaw, entityState.maxRaw)
                R1Log.i("CardStack.debounced", "sending setNumberValue($entityId, ${"%.3f".format(clamped)} step=$step)")
                ServiceCall.setNumberValue(entityId, clamped)
            }
            // Light wheel-mode dispatch — when the user has cycled into CT or HUE mode
            // for a light, the wheel's 0..100 is reinterpreted into kelvin or degrees
            // and sent on the appropriate service-call shape. Keeps brightness at its
            // current value so changing CT doesn't accidentally also change brightness.
            entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                _state.value.lightWheelMode[entityId] == com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entityState?.minColorTempK ?: 2000
                val maxK = entityState?.maxColorTempK ?: 6500
                val k = (minK + (pct / 100.0) * (maxK - minK)).roundToInt().coerceIn(minK, maxK)
                // Carry over current brightness so the bulb stays at its existing level
                // while we tint it. If the bulb is currently off (percent=0/null) we omit
                // brightness — HA's set_color_temp doesn't turn the bulb on on its own,
                // but at least the call doesn't accidentally turn it OFF either.
                val carryBright = entityState?.percent?.takeIf { it > 0 }
                R1Log.i("CardStack.debounced", "sending setLightColorTemp($entityId, ${k}K, bright=$carryBright)")
                ServiceCall.setLightColorTemp(entityId, k, brightnessPct = carryBright)
            }
            entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                _state.value.lightWheelMode[entityId] == com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val hue = (pct / 100.0) * 360.0
                val carryBright = entityState?.percent?.takeIf { it > 0 }
                R1Log.i("CardStack.debounced", "sending setLightHue($entityId, ${"%.0f".format(hue)}°, bright=$carryBright)")
                ServiceCall.setLightHue(entityId, hue, brightnessPct = carryBright)
            }
            else -> {
                // Standard scalar dispatch. If this is a light AND the user has set a
                // per-card colour-temperature override, fold that into the turn_on so
                // the bulb both brightens and shifts to the preferred CT in a single
                // service call. HA accepts color_temp_kelvin alongside brightness_pct
                // on lights that report `supported_color_modes` containing `color_temp`.
                val override = cachedOverrides[entityId.value]
                val ctK = override?.lightColorTempK
                if (entityId.domain == com.github.itskenny0.r1ha.core.ha.Domain.LIGHT &&
                    ctK != null && pct > 0) {
                    R1Log.i("CardStack.debounced", "sending light.turn_on($entityId, ${pct}% @ ${ctK}K)")
                    ServiceCall(
                        target = entityId,
                        service = "turn_on",
                        data = kotlinx.serialization.json.buildJsonObject {
                            put("brightness_pct", kotlinx.serialization.json.JsonPrimitive(pct))
                            put("color_temp_kelvin", kotlinx.serialization.json.JsonPrimitive(ctK))
                        },
                    )
                } else {
                    R1Log.i("CardStack.debounced", "sending setPercent($entityId, $pct)")
                    ServiceCall.setPercent(entityId, pct)
                }
            }
        }
        // haRepository.call() returns success immediately (the actual HA round-trip lives
        // inside the repo's own debouncer); failures arrive asynchronously via the
        // [callFailures] SharedFlow, which we observe in `init` and translate into an
        // optimistic rollback. On success we do NOTHING — the optimistic is cleared by
        // observeFavorites when HA echoes back the matching state_changed event. That
        // ordering is critical: clearing here would briefly show HA's *old* cached state
        // before the event arrives, which reads as a flicker.
        haRepository.call(call)
    }

    init {
        observeFavorites()
        // Keep a non-suspend snapshot of WheelSettings so onWheel() never has to hit the
        // settings Flow on the hot path.
        settings.settings
            .map { it.wheel }
            .distinctUntilChanged()
            .onEach { cachedWheel = it }
            .launchIn(viewModelScope)
        settings.settings
            .map { it.entityOverrides }
            .distinctUntilChanged()
            .onEach { cachedOverrides = it }
            .launchIn(viewModelScope)
        // Roll back the optimistic override whenever HA tells us a service call failed —
        // the UI bounces back to the last-known cached value so the user can see their
        // input didn't take, rather than the slider sitting stuck on their intent.
        haRepository.callFailures
            .onEach { id ->
                R1Log.w("CardStack.failure", "$id rejected by HA — reverting optimistic")
                _state.value = _state.value.copy(
                    optimisticPercents = _state.value.optimisticPercents - id,
                    optimisticSelectOptions = _state.value.optimisticSelectOptions - id,
                )
            }
            .launchIn(viewModelScope)
        // Wheel events are NOT collected here. They're collected by CardStackScreen only
        // while it is in composition, so spinning the wheel from any other screen does not
        // silently change the active card's brightness.
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeFavorites() {
        // With tabs, the visible cards come from the ACTIVE page's favourites — not
        // the flat union. The HA observe set is still the union across all pages
        // so every page's entities live-update in the background; switching pages
        // is instant and never re-subscribes. Renames flow through the same
        // sourceFlow because override changes need to re-render the active page.
        // PagesSnapshot keeps the full pages list (with their favourites + names + ids)
        // plus the active id and overrides — everything the per-page derivation
        // needs at emission time. distinctUntilChanged on this snapshot still
        // collapses no-op settings emissions cheaply because all four members
        // structurally equal compare.
        data class PagesSnapshot(
            val pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
            val activeId: String,
            val unionFavorites: List<String>,
            val overrides: Map<String, String>,
        )
        val sourceFlow = settings.settings
            .map { s ->
                val pages = s.pages
                val unionFavorites = pages.flatMap { it.favorites }.distinct()
                PagesSnapshot(
                    pages = pages,
                    activeId = s.activePageId,
                    unionFavorites = unionFavorites,
                    overrides = s.nameOverrides,
                )
            }
            .distinctUntilChanged()

        sourceFlow
            .flatMapLatest { snap ->
                // Subscribe to the UNION of every page so switching pages doesn't
                // re-resubscribe; observe state is fast that way. The per-page
                // slicing happens downstream of the entityMap.
                val ids = snap.unionFavorites.mapNotNull { runCatching { EntityId(it) }.getOrNull() }.toSet()
                if (ids.isEmpty()) {
                    flowOf(snap to emptyMap<EntityId, EntityState>())
                } else {
                    haRepository.observe(ids).map { snap to it }
                }
            }
            .onEach { (snap, entityMap) ->
                // Build a Map<pageId, List<EntityState>> in page order so every
                // horizontal pager page has its deck ready before the user swipes
                // to it. The active page's slice doubles as `cards` for legacy
                // call sites (wheel routing, currentIndex clamping, optimistic
                // trim) so nothing downstream needs to know about cardsByPage.
                fun materializeRow(id: String): EntityState? {
                    val eid = runCatching { EntityId(id) }.getOrNull() ?: return null
                    val state = entityMap[eid] ?: return null
                    val renamed = snap.overrides[state.id.value]
                    return if (renamed != null) state.copy(friendlyName = renamed) else state
                }
                // PERF: preserve List<EntityState> reference identity across
                // emissions whenever a page's cards are referentially
                // equivalent to the previous build. Without this, every
                // entityMap emit produced a fresh List for every page even
                // when nothing on that page had changed — and Compose,
                // seeing a new List reference, recomposed every PageDeck
                // (including inactive neighbours peeked via the
                // beyondViewportPageCount=1 horizontal pager). With ref
                // preservation, only pages whose contents actually changed
                // get a new list, so the inactive PageDecks skip
                // recomposition entirely.
                val prevCardsByPage = _state.value.cardsByPage
                val cardsByPage = LinkedHashMap<String, List<EntityState>>()
                for (page in snap.pages) {
                    val newList = page.favorites.mapNotNull { materializeRow(it) }
                    val prev = prevCardsByPage[page.id]
                    cardsByPage[page.id] = if (
                        prev != null &&
                        prev.size == newList.size &&
                        // === checks REFERENCE equality on each EntityState.
                        // HaRepository preserves entity references when the
                        // server's reported state hasn't changed, so this
                        // catches the common case where the entityMap emit
                        // was for an entity NOT on this page.
                        prev.indices.all { prev[it] === newList[it] }
                    ) {
                        prev
                    } else {
                        newList
                    }
                }
                val activeId = snap.pages.firstOrNull { it.id == snap.activeId }?.id
                    ?: snap.pages.firstOrNull()?.id
                    ?: ""
                val ordered = cardsByPage[activeId].orEmpty()
                val favouriteIds = snap.pages.firstOrNull { it.id == activeId }?.favorites.orEmpty()
                R1Log.d("CardStack.observe", "pages=${snap.pages.size} activeCards=${ordered.size}")
                val cur = _state.value
                // Carry forward each page's currentIndex; if the active page's
                // saved index is stale (cards shrank), clamp.
                val nextIndexByPage = cur.indexByPage.toMutableMap()
                cardsByPage.forEach { (pid, list) ->
                    val saved = nextIndexByPage[pid] ?: 0
                    nextIndexByPage[pid] = if (list.isEmpty()) 0 else saved.coerceIn(0, list.size - 1)
                }
                // Drop entries for pages that no longer exist (delete).
                nextIndexByPage.keys.retainAll(cardsByPage.keys)
                val clampedIndex = nextIndexByPage[activeId] ?: 0
                // Trim optimistic entries in three cases:
                //   1) The server caught up — for SCALAR entities, that means cached percent
                //      matches the optimistic value. For SWITCH entities, the repo never
                //      produces a percent (only isOn), so we instead check whether the
                //      cached isOn matches our optimistic intent (override > 0). Without this
                //      switch-aware branch, an automation that flips the entity from outside
                //      the app leaves the optimistic stuck and the card keeps showing the
                //      old state until the user manually toggles it back.
                //   2) The entity is no longer in the favourites set at all (user un-favourited
                //      it before HA echoed back). Without (2) the override map slowly grows
                //      every time someone toggles a favourite off.
                //   3) The cache hasn't seen this entity yet — keep the optimistic so the UI
                //      doesn't bounce while waiting for the first state.
                val favoriteSet = ordered.map { it.id }.toSet()
                val newOptimistic = cur.optimisticPercents.filter { (id, optPct) ->
                    if (id !in favoriteSet) return@filter false
                    val cached = entityMap[id] ?: return@filter true
                    if (cached.supportsScalar) {
                        val cachedPct = cached.percent
                        cachedPct == null || cachedPct != optPct
                    } else {
                        cached.isOn != (optPct > 0)
                    }
                }
                // Same shape for the select-option overrides: keep the
                // override while HA still reports the old option, drop it
                // once the cached value catches up.
                val newSelectOpt = cur.optimisticSelectOptions.filter { (id, optVal) ->
                    if (id !in favoriteSet) return@filter false
                    val cached = entityMap[id] ?: return@filter true
                    cached.currentOption != optVal
                }
                _state.value = cur.copy(
                    cards = ordered,
                    cardsById = ordered.associateBy { it.id },
                    currentIndex = clampedIndex,
                    optimisticPercents = newOptimistic,
                    optimisticSelectOptions = newSelectOpt,
                    favouritesCount = favouriteIds.size,
                    settingsLoaded = true,
                    cardsByPage = cardsByPage,
                    indexByPage = nextIndexByPage,
                    activePageId = activeId,
                    pages = snap.pages,
                )
            }
            .launchIn(viewModelScope)
    }

    /** Called from CardStackScreen when a wheel event arrives. Synchronous on the hot path —
     *  reads only cached state — so that 50 Hz wheel input doesn't back up in the SharedFlow
     *  buffer. The actual HA call is dispatched via the debouncer in its own coroutine. */
    fun onWheel(event: WheelEvent) {
        val wheel = cachedWheel
        val activeState = _state.value.activeState ?: return
        // Ignore wheel on unavailable entities: spinning would create a runaway optimistic
        // override that never reconciles because HA never responds with a state change.
        if (!activeState.isAvailable) return
        // Action-only entities (scenes, scripts, buttons) are tap-to-fire. Spinning the
        // wheel on top of an ActionCard shouldn't accidentally fire the trigger or paint
        // a phantom percent — wheels are deliberately a no-op there. Same story for
        // sensors / binary_sensors which are read-only.
        if (activeState.id.domain.isAction || activeState.id.domain.isSensor) return
        // Code-required locks would route a wheel spin into setSwitch which
        // fires lock.lock / lock.unlock without the code, and HA rejects
        // with `code_required` — the user sees a silent failure. Skip the
        // wheel here so the LockPanel's PIN keypad stays the only path.
        if (activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LOCK &&
            !activeState.lockCodeFormat.isNullOrBlank()) return

        val sign = WheelInput.applyDirection(event.direction, wheel.invertDirection)

        // ── Switch (on/off only) entities: wheel sets absolute state ──────────────────
        if (!activeState.supportsScalar) {
            val newPct = if (sign > 0) 100 else 0
            _state.value = _state.value.copy(
                optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
            )
            viewModelScope.launch { debounced.submit(activeState.id, newPct) }
            return
        }

        // ── Scalar entities: wheel adjusts percent with optional acceleration ─────────
        // Sliding-window rate computation: count events in the last [rateWindowMillis] ms,
        // multiply by (1000 / window) to scale to events/sec.
        val now = event.timestampMillis
        wheelTimestamps.addLast(now)
        while (wheelTimestamps.isNotEmpty() && now - wheelTimestamps.first() > rateWindowMillis) {
            wheelTimestamps.removeFirst()
        }
        val ratePerSec = wheelTimestamps.size * (1000.0 / rateWindowMillis)

        // For climate the user wants 0.5° increments and "half the speed of lights" —
        // i.e. each detent should advance the temperature by a deliberate small amount,
        // not a fixed % of brightness. Compute the percent-equivalent of 0.5° from the
        // entity's min/max range. With a typical 5..35°C range (30° span), 0.5° ≈ 1.67%
        // which rounds to 2% — and combined with the snap-to-0.5° at service-call time
        // the wheel feels exactly like a thermostat dial.
        val step = if ((activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.CLIMATE ||
                activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.WATER_HEATER) &&
            activeState.minRaw != null && activeState.maxRaw != null) {
            val range = activeState.maxRaw - activeState.minRaw
            if (range > 0) {
                ((50.0 / range).roundToInt()).coerceAtLeast(1)
            } else 1
        } else {
            WheelInput.effectiveStep(
                base = wheel.stepPercent,
                ratePerSec = ratePerSec,
                accelerate = wheel.acceleration,
                curve = wheel.accelerationCurve,
            )
        }
        val currentPct = _state.value.optimisticPercents[activeState.id] ?: activeState.percent ?: 0
        val newPct = (currentPct + sign * step).coerceIn(0, 100)
        R1Log.d("CardStack.onWheel", "dir=${event.direction} step=$step (rate=$ratePerSec) -> $currentPct→$newPct")

        // Apply optimistic update synchronously
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )

        // Submit debounced call (trails by debounceMillis). Suspend-free hot path: we launch
        // the submit so onWheel can return immediately and the next event isn't blocked.
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
    }

    /**
     * Set a card's percent to an absolute value (0..100) — used by the touch-drag and
     * tick-tap affordances on the right-edge slider. Reuses the same optimistic-then-
     * debounce path as [onWheel] so a finger drag feels identical to a fast wheel spin
     * end-to-end (instant UI update, single service-call after the user settles).
     *
     * Validation mirrors onWheel: refuse on unavailable entities, action / sensor
     * domains, and non-scalar entities (those use the on/off setSwitch path). For
     * scalar entities the value is clamped to 0..100 — the VM doesn't try to convert
     * it into a native unit; setPercent / the climate-temp dispatch downstream handle
     * that mapping.
     */
    fun setEntityPercent(entityId: EntityId, pct: Int) {
        val card = _state.value.cardsById[entityId] ?: return
        if (!card.isAvailable) return
        if (card.id.domain.isAction || card.id.domain.isSensor) return
        if (!card.supportsScalar) return
        val clamped = pct.coerceIn(0, 100)
        R1Log.d("CardStack.setPercent", "$entityId → $clamped (touch)")
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (entityId to clamped),
        )
        viewModelScope.launch { debounced.submit(entityId, clamped) }
    }

    /**
     * Move a favourite from one index to another. Backs the jump-to-card overlay's
     * drag-reorder gesture so the user can rearrange the deck without leaving the
     * card stack. No-op when the indices are equal or out of range; the underlying
     * settings flow emits the new order and observeFavorites rebuilds the cards
     * list, so the visible deck reorders within a frame.
     */
    fun reorderFavorite(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            // Tabs: reorder only the active page's favourites; other pages' lists
            // are untouched.
            settings.updateActivePage { page ->
                val l = page.favorites.toMutableList()
                if (fromIndex !in l.indices) return@updateActivePage page
                val clamped = toIndex.coerceIn(0, l.size - 1)
                val item = l.removeAt(fromIndex)
                l.add(clamped, item)
                page.copy(favorites = l)
            }
        }
    }

    /** Sync the VM's active-card index with the pager's settled page. The wheel/tap handlers
     *  read activeState off of this index, so it has to track whatever page the user has just
     *  paged to. */
    /** Switch the active tab — fires a settings.update which flows through to the
     *  card stack via observeFavorites's active-page derivation, re-rendering with
     *  the new page's cards within a frame. */
    fun setActivePage(pageId: String) {
        viewModelScope.launch { settings.setActivePage(pageId) }
    }

    /** Add a fresh empty page and make it the active one. */
    fun addPage(name: String) {
        viewModelScope.launch { settings.addPage(name) }
    }

    /**
     * Build one [FavoritePage] per HA area from the currently-loaded entity
     * cache. Each generated page's name is the area display name (uppercased,
     * truncated to 20 chars); its favourites list is every controllable
     * entity HA reports as belonging to that area. Skips areas with no
     * controllable entities so the user doesn't end up with a swarm of
     * empty tabs. Surfaces the new-page count via a one-shot SharedFlow so
     * the caller can toast the result.
     */
    private val _pagesGenerated = MutableSharedFlow<Int>(extraBufferCapacity = 1)
    val pagesGenerated: SharedFlow<Int> = _pagesGenerated.asSharedFlow()
    fun generatePagesFromAreas() {
        viewModelScope.launch {
            // Pull a fresh entity listing rather than relying on the live
            // card cache — the cache is filtered to a single page's
            // favourites, but we want every entity HA reports.
            val result = haRepository.listAllEntities()
            if (result.isFailure) {
                // Distinguish "fetch failed" from "no areas have entities" by
                // emitting -1 so the screen can show a network-error toast
                // instead of the misleading "no areas with entities" one.
                _pagesGenerated.tryEmit(-1)
                return@launch
            }
            val all = result.getOrNull().orEmpty()
            val controllable = all.filter { e ->
                val d = e.id.domain
                // Surface domains the SwitchCard / theme.Card can actually
                // operate on. Sensors / binary_sensors / cameras would just
                // bloat the page since they aren't toggleable.
                d == Domain.LIGHT || d == Domain.SWITCH || d == Domain.FAN ||
                    d == Domain.COVER || d == Domain.MEDIA_PLAYER ||
                    d == Domain.CLIMATE || d == Domain.HUMIDIFIER ||
                    d == Domain.VALVE || d == Domain.WATER_HEATER ||
                    d == Domain.LOCK || d == Domain.VACUUM ||
                    d == Domain.LAWN_MOWER || d == Domain.INPUT_BOOLEAN ||
                    d == Domain.INPUT_NUMBER || d == Domain.NUMBER
            }
            val byArea = controllable
                .filter { !it.area.isNullOrBlank() }
                .groupBy { it.area!! }
            val ordered = byArea.toSortedMap().map { (area, list) ->
                area to list.map { it.id.value }.sorted()
            }
            val created = settings.generatePagesFromAreas(ordered)
            _pagesGenerated.tryEmit(created)
        }
    }

    /** Rename an existing page in place. */
    fun setPageAccent(pageId: String, accentArgb: Int?) {
        viewModelScope.launch { settings.setPageAccent(pageId, accentArgb) }
    }

    fun setPageIcon(pageId: String, icon: String?) {
        viewModelScope.launch { settings.setPageIcon(pageId, icon) }
    }

    fun renamePage(pageId: String, name: String) {
        viewModelScope.launch { settings.renamePage(pageId, name) }
    }

    /** Delete a page. No-op when only one page remains (every install always has
     *  at least one to land on). */
    fun deletePage(pageId: String) {
        viewModelScope.launch { settings.deletePage(pageId) }
    }

    fun setCurrentIndex(index: Int) {
        val cur = _state.value
        val size = cur.cards.size
        if (size == 0) return
        val clamped = index.coerceIn(0, size - 1)
        // Mirror the active page's index into indexByPage so a horizontal swipe
        // away + back lands on the same card the user left.
        val newIndexByPage = cur.indexByPage.toMutableMap().apply {
            if (cur.activePageId.isNotBlank()) put(cur.activePageId, clamped)
        }
        _state.value = cur.copy(currentIndex = clamped, indexByPage = newIndexByPage)
    }

    /**
     * Variant for the horizontal-pager: a card index reported for a *non-active*
     * page (e.g. the user wheels through a peek-visible neighbour). Updates that
     * page's saved index without disturbing the active page's [currentIndex].
     * No-op when [pageId] doesn't exist in the current state's cardsByPage.
     */
    fun setIndexForPage(pageId: String, index: Int) {
        val cur = _state.value
        val list = cur.cardsByPage[pageId] ?: return
        if (list.isEmpty()) return
        val clamped = index.coerceIn(0, list.size - 1)
        if (cur.indexByPage[pageId] == clamped) return
        val updated = cur.indexByPage.toMutableMap().apply { put(pageId, clamped) }
        _state.value = if (pageId == cur.activePageId) {
            cur.copy(currentIndex = clamped, indexByPage = updated)
        } else {
            cur.copy(indexByPage = updated)
        }
    }

    /**
     * Remove [entityId] from the active page's favourites — surfaced through the
     * jump-to-card sheet's '✕' affordance so the user can unfavourite without
     * digging back into the picker.
     */
    fun removeFavorite(entityId: String) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                page.copy(favorites = page.favorites.filter { it != entityId })
            }
        }
    }

    /**
     * Move [entityId] from its current page to [targetPageId]. Atomic — both the
     * source-page removal and the target-page append land in a single
     * [SettingsRepository.update] so the favourites union never sees the entity
     * in zero pages or in two pages at once. Append-at-end on the target page
     * (rather than insert-at-current-index) matches how the picker adds new
     * favourites: predictable, no surprise reorder of the target deck. No-op if
     * the source page can't be resolved (rare race with delete) or if [targetPageId]
     * doesn't exist.
     */
    fun moveFavoriteToPage(entityId: String, targetPageId: String) {
        viewModelScope.launch {
            settings.update { s ->
                if (s.pages.none { it.id == targetPageId }) return@update s
                val updatedPages = s.pages.map { page ->
                    when {
                        page.id == targetPageId -> {
                            // Skip the append if the target already contains the
                            // id — guards against the user moving a card into its
                            // own page via a stale menu entry.
                            if (entityId in page.favorites) page
                            else page.copy(favorites = page.favorites + entityId)
                        }
                        entityId in page.favorites ->
                            page.copy(favorites = page.favorites.filter { it != entityId })
                        else -> page
                    }
                }
                s.copy(pages = updatedPages)
            }
        }
    }

    /**
     * Fire `turn_off` on every controllable entity in the active page —
     * lights, switches, fans, media_players, covers. Sensors / actions
     * skip (no off state to set). Surfaces a confirmation toast with the
     * count of entities targeted. Used by the chrome's long-press
     * 'quick actions' sheet for the 'going to bed' moment.
     *
     * Per-domain service:
     *   light / switch / fan / media_player / cover → turn_off
     *
     * Implementation note: we walk the ACTIVE page only (not the full
     * favourites union) so the user can scope an 'all off' to a room
     * by switching to that room's page first.
     */
    /**
     * Mirror of [turnOffActivePage] for the 'on' direction. Lights / fans /
     * switches that have a meaningful 'on' state get fired with turn_on;
     * media_players are skipped because 'on' without specifying media is
     * mostly a no-op or surfaces 'idle'. The active page only.
     */
    fun turnOnActivePage() {
        viewModelScope.launch {
            val active = _state.value.activePageId
            val pageCards = _state.value.cardsByPage[active].orEmpty()
            val targets = pageCards.filter { ent ->
                val d = ent.id.domain
                d == Domain.LIGHT || d == Domain.SWITCH || d == Domain.FAN ||
                    d == Domain.INPUT_BOOLEAN || d == Domain.AUTOMATION
            }
            R1Log.i("CardStack.allOn", "firing turn_on on ${targets.size} entities in active page")
            for (t in targets) {
                haRepository.call(
                    ServiceCall(
                        target = t.id,
                        service = "turn_on",
                        data = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                )
            }
            com.github.itskenny0.r1ha.core.util.Toaster.show("Turned on ${targets.size} entities")
        }
    }

    /**
     * Fire `media_pause` on every playing media_player in the active page.
     * Sibling of [turnOffActivePage] for users who don't want to turn off
     * their lights but DO want music / videos paused (the 'company is at
     * the door' moment). Skips media_players that aren't currently
     * playing to avoid stomping idle players into a no-op-but-noisy state.
     */
    fun pauseAllMedia() {
        viewModelScope.launch {
            val active = _state.value.activePageId
            val pageCards = _state.value.cardsByPage[active].orEmpty()
            val targets = pageCards.filter { ent ->
                ent.id.domain == Domain.MEDIA_PLAYER &&
                    ent.rawState?.equals("playing", ignoreCase = true) == true
            }
            R1Log.i("CardStack.pauseMedia", "firing media_pause on ${targets.size} players")
            for (t in targets) {
                haRepository.call(
                    ServiceCall(
                        target = t.id,
                        service = "media_pause",
                        data = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                )
            }
            com.github.itskenny0.r1ha.core.util.Toaster.show(
                "Paused ${targets.size} media player${if (targets.size == 1) "" else "s"}",
            )
        }
    }

    fun turnOffActivePage() {
        viewModelScope.launch {
            val active = _state.value.activePageId
            val pageCards = _state.value.cardsByPage[active].orEmpty()
            val targets = pageCards.filter { ent ->
                val d = ent.id.domain
                d == Domain.LIGHT || d == Domain.SWITCH || d == Domain.FAN ||
                    d == Domain.MEDIA_PLAYER || d == Domain.COVER ||
                    d == Domain.INPUT_BOOLEAN || d == Domain.AUTOMATION
            }
            R1Log.i("CardStack.allOff", "firing turn_off on ${targets.size} entities in active page")
            for (t in targets) {
                haRepository.call(
                    ServiceCall(
                        target = t.id,
                        service = "turn_off",
                        data = kotlinx.serialization.json.JsonObject(emptyMap()),
                    ),
                )
            }
            com.github.itskenny0.r1ha.core.util.Toaster.show("Turned off ${targets.size} entities")
        }
    }

    /** Direct page reorder used by the tab strip's drag-reorder gesture.
     *  Indices are interpreted in the current settings' page order; the
     *  underlying [SettingsRepository.reorderPages] clamps + no-ops on
     *  invalid combinations. */
    fun reorderPages(fromIdx: Int, toIdx: Int) {
        viewModelScope.launch { settings.reorderPages(fromIdx, toIdx) }
    }

    /** Move the page at [pageId] one slot to the left, if possible. No-op when
     *  the page is already leftmost. Used by the manage modal's MOVE LEFT
     *  button — quicker than implementing drag-reorder on the tab strip itself,
     *  and ergonomic on the R1's small touch targets. */
    fun movePageLeft(pageId: String) {
        viewModelScope.launch {
            val s = settings.settings.first()
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx > 0) settings.reorderPages(idx, idx - 1)
        }
    }

    /** Mirror of [movePageLeft] — moves [pageId] one slot to the right. */
    fun movePageRight(pageId: String) {
        viewModelScope.launch {
            val s = settings.settings.first()
            val idx = s.pages.indexOfFirst { it.id == pageId }
            if (idx >= 0 && idx < s.pages.lastIndex) settings.reorderPages(idx, idx + 1)
        }
    }

    /**
     * Fire the per-card long-press action. [target] is an HA entity_id (e.g. `scene.x`,
     * `script.y`, `switch.z`); we parse it and dispatch the same tap-action the entity's
     * own card would, which means scenes/scripts fire turn_on, buttons fire press,
     * switches toggle, etc. Invalid or unsupported targets toast and noop.
     */
    /**
     * Cycle the light wheel-mode for [entityId] through its available modes (derived
     * from supportedColorModes). Wraps at the end; one-mode entities (non-coloured
     * dimmable bulbs) become a no-op. Called from the BigReadout-suffix tap on light
     * cards.
     */
    /**
     * Cycle the light's effect through its effect_list. None → first effect → second →
     * … → None (so the user can wrap back to no-effect by stepping past the end). Sends
     * `light.turn_on` with the new effect; HA accepts "None" as the no-effect sentinel.
     */
    fun cycleLightEffect(entityId: EntityId) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (entity.id.domain != Domain.LIGHT || entity.effectList.isEmpty()) return
        // Sequence with null at both ends so cycling wraps cleanly: null → first → ... → last → null.
        val sequence: List<String?> = listOf(null) + entity.effectList
        val currentIdx = sequence.indexOf(entity.effect).let { if (it == -1) 0 else it }
        val next = sequence[(currentIdx + 1) % sequence.size]
        R1Log.i("CardStack.cycleEffect", "$entityId: ${entity.effect ?: "none"} → ${next ?: "none"}")
        viewModelScope.launch {
            haRepository.call(ServiceCall.setLightEffect(entityId, next))
        }
    }

    /**
     * Set the wheel mode for a light card to a specific value (no cycling). Used by the
     * segmented mode buttons on the card so the user can jump directly to BRIGHTNESS /
     * WHITE / COLOUR rather than having to cycle through other modes to reach the one
     * they want. Same optimistic-seeding logic as [cycleLightWheelMode] — the wheel
     * percent gets seeded to whatever the bulb is currently showing in the new mode so
     * the readout doesn't bounce when the user changes modes.
     */
    fun setLightWheelMode(entityId: EntityId, mode: com.github.itskenny0.r1ha.core.ha.LightWheelMode) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (entity.id.domain != Domain.LIGHT) return
        val available = com.github.itskenny0.r1ha.core.ha.LightWheelMode.availableFor(entity.supportedColorModes)
        if (mode !in available) return
        val current = _state.value.lightWheelMode[entityId] ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
        if (current == mode) return
        // Seed the optimistic percent so the wheel starts at the bulb's current value
        // in the new mode — same shape as cycleLightWheelMode().
        val seedPercent = when (mode) {
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> entity.percent ?: 0
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entity.minColorTempK ?: 2000
                val maxK = entity.maxColorTempK ?: 6500
                val k = entity.colorTempK ?: ((minK + maxK) / 2)
                if (maxK > minK) {
                    ((k - minK).toDouble() / (maxK - minK) * 100.0).roundToInt().coerceIn(0, 100)
                } else 50
            }
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val h = entity.hue ?: 0.0
                (h / 360.0 * 100.0).roundToInt().coerceIn(0, 100)
            }
        }
        _state.value = _state.value.copy(
            lightWheelMode = _state.value.lightWheelMode + (entityId to mode),
            optimisticPercents = _state.value.optimisticPercents + (entityId to seedPercent),
        )
    }

    /**
     * Apply a specific effect to a light, picked from its [EntityState.effectList]. Null
     * clears the effect (HA accepts the literal string "None" for "no effect"). Used
     * by the effect picker sheet so the user can jump directly to a named effect rather
     * than tapping through the cycle. Also force the wheel back to BRIGHTNESS — once an
     * effect is running it owns the bulb's colour, so HUE / WHITE wheel modes can't do
     * anything useful and only brightness remains as a meaningful axis to nudge. When
     * the user clears the effect (effect = null) the mode is left alone so they don't
     * lose a HUE/WHITE selection they may want to return to.
     */
    fun setLightEffect(entityId: EntityId, effect: String?) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (entity.id.domain != Domain.LIGHT) return
        R1Log.i("CardStack.setEffect", "$entityId: ${entity.effect ?: "none"} → ${effect ?: "none"}")
        if (!effect.isNullOrBlank()) {
            val brightnessMode = com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
            val cur = _state.value.lightWheelMode[entityId]
            if (cur != null && cur != brightnessMode) {
                setLightWheelMode(entityId, brightnessMode)
            }
        }
        viewModelScope.launch {
            haRepository.call(ServiceCall.setLightEffect(entityId, effect))
        }
    }

    /**
     * Cycle through a select-entity's [EntityState.selectOptions] by [delta] (positive
     * moves forward, negative moves backward). Surfaced from the wheel handler in
     * CardStackScreen — each wheel detent moves the selection one option. Wraps both
     * ways so a fast spin lands somewhere sane regardless of starting position. Skips
     * when the entity has no options or only one (nothing to choose).
     */
    fun cycleSelectOption(entityId: EntityId, delta: Int) {
        val cur = _state.value
        val entity = cur.cardsById[entityId] ?: return
        if (!entity.id.domain.isSelect) return
        val options = entity.selectOptions
        if (options.size < 2) return
        // Use the optimistic option (if any) as the cycle anchor so a quick
        // back-to-back wheel detent advances from where the user left off,
        // not from the stale HA-confirmed value.
        val anchor = cur.optimisticSelectOptions[entityId] ?: entity.currentOption
        val curIdx = options.indexOf(anchor).let { if (it == -1) 0 else it }
        val nextIdx = ((curIdx + delta) % options.size + options.size) % options.size
        val next = options[nextIdx]
        R1Log.i("CardStack.cycleSelect", "$entityId: ${anchor ?: "?"} → $next")
        // Optimistic update so the card snaps to the new option immediately
        // instead of waiting for HA's state_changed echo (typically 200-400 ms
        // away on a busy server). The cache observer in observeFavorites
        // clears the override when HA confirms the new value matches.
        _state.value = cur.copy(
            optimisticSelectOptions = cur.optimisticSelectOptions + (entityId to next),
        )
        viewModelScope.launch {
            haRepository.call(com.github.itskenny0.r1ha.core.ha.ServiceCall.setSelectOption(entityId, next))
        }
    }

    /** Apply a specific option to a select-entity. Used by the picker overlay. */
    fun setSelectOption(entityId: EntityId, option: String) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (!entity.id.domain.isSelect) return
        if (option !in entity.selectOptions) return
        R1Log.i("CardStack.setSelect", "$entityId: ${entity.currentOption ?: "?"} → $option")
        viewModelScope.launch {
            haRepository.call(com.github.itskenny0.r1ha.core.ha.ServiceCall.setSelectOption(entityId, option))
        }
    }

    /**
     * Fire a media-player transport / volume action — play/pause, next, prev, vol+, vol-,
     * mute. Surfaced by the media_player card's [MediaControlsRow]. Each tap is a
     * one-shot service call with no payload; the volume wheel is still the primary way
     * to set absolute volume, but discrete +/- taps are easier for small adjustments.
     */
    /**
     * Generic service-call dispatch from a card panel — vacuum chips, climate
     * mode picker, lock/unlock, valve open/close, water_heater mode, media
     * shuffle/repeat/source. Each panel composes its own [ServiceCall] (with
     * the right service name, target, and data payload) and routes it through
     * here. Identical to [haRepository.call] from the panel's perspective; the
     * indirection keeps panels free of repo references and lets failures
     * surface uniformly via the existing [callFailures] observer.
     */
    fun callService(call: com.github.itskenny0.r1ha.core.ha.ServiceCall) {
        R1Log.i("CardStack.callService", "${call.target} ${call.service}")
        viewModelScope.launch {
            haRepository.call(call)
        }
    }

    fun mediaTransport(entityId: EntityId, action: com.github.itskenny0.r1ha.core.ha.MediaTransport) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (entity.id.domain != Domain.MEDIA_PLAYER) return
        R1Log.i("CardStack.media", "$entityId $action (muted=${entity.isVolumeMuted})")
        viewModelScope.launch {
            haRepository.call(
                com.github.itskenny0.r1ha.core.ha.ServiceCall.mediaTransport(
                    entityId,
                    action,
                    currentlyMuted = entity.isVolumeMuted,
                ),
            )
        }
    }

    fun cycleLightWheelMode(entityId: EntityId) {
        val entity = _state.value.cardsById[entityId] ?: return
        if (entity.id.domain != Domain.LIGHT) return
        val available = com.github.itskenny0.r1ha.core.ha.LightWheelMode.availableFor(entity.supportedColorModes)
        if (available.size <= 1) return
        val current = _state.value.lightWheelMode[entityId] ?: com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS
        val nextIdx = (available.indexOf(current) + 1) % available.size
        val next = available[nextIdx]

        // Seed the optimistic percent so the wheel starts at the bulb's current value
        // in the new mode — switching from BRIGHTNESS 60% into CT mode shouldn't land
        // the wheel at 60% of the CT range; it should land at whatever CT the bulb is
        // currently showing.
        val seedPercent = when (next) {
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.BRIGHTNESS -> entity.percent ?: 0
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.COLOR_TEMP -> {
                val minK = entity.minColorTempK ?: 2000
                val maxK = entity.maxColorTempK ?: 6500
                val k = entity.colorTempK ?: ((minK + maxK) / 2)
                if (maxK > minK) {
                    ((k - minK).toDouble() / (maxK - minK) * 100.0).roundToInt().coerceIn(0, 100)
                } else 50
            }
            com.github.itskenny0.r1ha.core.ha.LightWheelMode.HUE -> {
                val h = entity.hue ?: 0.0
                (h / 360.0 * 100.0).roundToInt().coerceIn(0, 100)
            }
        }

        _state.value = _state.value.copy(
            lightWheelMode = _state.value.lightWheelMode + (entityId to next),
            optimisticPercents = _state.value.optimisticPercents + (entityId to seedPercent),
        )
        R1Log.i("CardStack.cycleMode", "$entityId: $current → $next (seed=$seedPercent%)")
    }

    /** Persist a customize-dialog edit straight from the card-stack. Same write path as
     *  the favourites picker's saveCustomize — name override + entity override map.
     *  Empty new name removes the name override; defaults-only entity override removes
     *  the entry from the entityOverrides map. */
    fun saveCustomize(
        entityId: String,
        newName: String,
        newOverride: com.github.itskenny0.r1ha.core.prefs.EntityOverride,
    ) {
        viewModelScope.launch {
            settings.update { cur ->
                val trimmed = newName.trim()
                val nextNames = cur.nameOverrides.toMutableMap()
                if (trimmed.isBlank()) nextNames.remove(entityId) else nextNames[entityId] = trimmed
                val nextOverrides = cur.entityOverrides.toMutableMap()
                if (newOverride == com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE) {
                    nextOverrides.remove(entityId)
                } else {
                    nextOverrides[entityId] = newOverride
                }
                cur.copy(nameOverrides = nextNames, entityOverrides = nextOverrides)
            }
        }
    }

    fun fireLongPress(target: String) {
        val targetId = runCatching { EntityId(target) }.getOrNull()
        if (targetId == null) {
            R1Log.w("CardStack.longPress", "ignoring invalid target '$target'")
            com.github.itskenny0.r1ha.core.util.Toaster.error(
                "Long-press target '$target' isn't a recognised entity",
            )
            return
        }
        viewModelScope.launch {
            R1Log.i("CardStack.longPress", "firing $targetId")
            // For toggleable domains we tap-toggle relative to the current cached state;
            // for action-only domains (scene/script/button) tapAction always fires the
            // trigger regardless of isOn. The cached state may not exist (the user might
            // point at an entity they haven't favourited) — default to isOn=false so the
            // toggle dispatches a "turn_on" / "open_cover" / etc. which is the natural
            // intent of "activate this from a long press".
            val cachedIsOn = _state.value.cards.firstOrNull { it.id == targetId }?.isOn ?: false
            haRepository.call(ServiceCall.tapAction(targetId, cachedIsOn))
        }
    }

    fun tapToggle() {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return  // can't toggle an unreachable entity
        // Code-required locks: the SwitchTrack is hidden and the PIN keypad
        // surfaced from LockPanel is the only path. A passthrough tapToggle
        // here would fire lock.lock / lock.unlock with no code attached
        // and HA would reject — same silent-failure shape we just fixed
        // for the wheel handler.
        if (activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LOCK &&
            !activeState.lockCodeFormat.isNullOrBlank()) return
        viewModelScope.launch {
            val stopService = when (activeState.id.domain) {
                com.github.itskenny0.r1ha.core.ha.Domain.COVER -> "stop_cover"
                com.github.itskenny0.r1ha.core.ha.Domain.VALVE -> "stop_valve"
                else -> null
            }
            val isMoving = stopService != null &&
                (activeState.rawState == "opening" || activeState.rawState == "closing")
            val call = if (isMoving) {
                R1Log.i("CardStack.tap", "stop ${activeState.id} (state=${activeState.rawState})")
                ServiceCall(
                    target = activeState.id,
                    service = stopService!!,
                    data = kotlinx.serialization.json.JsonObject(emptyMap()),
                )
            } else {
                R1Log.i("CardStack.tap", "toggle ${activeState.id} isOn=${activeState.isOn}")
                ServiceCall.tapAction(activeState.id, activeState.isOn)
            }
            haRepository.call(call)
        }
    }

    /**
     * Set the active switch-card entity to an explicit [on] state — wired to the ON/OFF
     * labels on [SwitchCard]. Re-uses the same optimistic + debouncer pipeline as the wheel
     * so a rapid tap-ON tap-OFF still resolves to the last intent.
     */
    fun setSwitchOn(on: Boolean) {
        val activeState = _state.value.activeState ?: return
        if (!activeState.isAvailable) return
        // Same code-required-lock guard as [tapToggle] and [onWheel] — the
        // PIN keypad in LockPanel is the only path for those entities.
        if (activeState.id.domain == com.github.itskenny0.r1ha.core.ha.Domain.LOCK &&
            !activeState.lockCodeFormat.isNullOrBlank()) return
        val newPct = if (on) 100 else 0
        _state.value = _state.value.copy(
            optimisticPercents = _state.value.optimisticPercents + (activeState.id to newPct)
        )
        viewModelScope.launch { debounced.submit(activeState.id, newPct) }
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: SettingsRepository,
            wheelInput: WheelInput,
        ) = viewModelFactory {
            initializer {
                CardStackViewModel(
                    haRepository = haRepository,
                    settings = settings,
                    wheelInput = wheelInput,
                )
            }
        }
    }
}
