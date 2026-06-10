package com.github.itskenny0.r1ha.feature.dashboards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaThemeCatalogue
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConfig
import com.github.itskenny0.r1ha.core.lovelace.LovelaceDashboard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrides
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.lovelace.OverrideOp
import com.github.itskenny0.r1ha.core.lovelace.ViewOverride
import com.github.itskenny0.r1ha.core.lovelace.haThemeVariablesToOverlay
import com.github.itskenny0.r1ha.core.lovelace.strategies.StrategyDataLoader
import com.github.itskenny0.r1ha.core.lovelace.strategies.StrategyEngine
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Backing state for the native-Lovelace-dashboards surface.
 *
 * Two screens share this VM:
 *  - [com.github.itskenny0.r1ha.feature.dashboards.DashboardsListScreen]
 *    lists the dashboards HA exposes (+ the default dashboard sentinel),
 *    plus the views inside the currently-selected dashboard.
 *  - [com.github.itskenny0.r1ha.feature.dashboards.DashboardViewScreen]
 *    renders one view's cards full-screen, with the editor overlay
 *    toggled by [setEditMode] / mutated through the override helpers.
 *
 * The VM keeps a small in-memory cache of parsed dashboards so popping
 * between views in the same dashboard doesn't re-fetch. State changes
 * for the rendered entities flow through the regular [HaRepository.observe]
 * subscription path (driven by [observeEntities] below).
 */
class DashboardsViewModel(
    private val haRepository: HaRepository,
    private val overrideStore: LovelaceOverrideStore,
) : ViewModel() {

    private val _state = MutableStateFlow(DashboardsState())
    val state: StateFlow<DashboardsState> = _state.asStateFlow()

    /**
     * Live overrides flow, surfaced to the editor + the renderer so a
     * fresh edit lands without an explicit refresh. Wraps the store's
     * Flow in a StateFlow so consumers can synchronously read the
     * current overrides via [overrides.value].
     */
    val overrides: StateFlow<LovelaceOverrides> = overrideStore.overrides.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = LovelaceOverrides.EMPTY,
    )

    /**
     * Active HA theme catalogue, fetched once per session and updated live when
     * HA fires `themes_updated`. The initial value is [HaThemeCatalogue.EMPTY]
     * so dashboard cards render with R1 defaults until the fetch completes.
     *
     * Exposed so [DashboardViewScreen] can build the theme-lookup lambda that
     * the [com.github.itskenny0.r1ha.core.theme.LocalHaThemeLookup] CompositionLocal
     * provides to per-card renderers.
     */
    private val _themeCatalogue = MutableStateFlow(HaThemeCatalogue.EMPTY)
    val themeCatalogue: StateFlow<HaThemeCatalogue> = _themeCatalogue.asStateFlow()

    /**
     * Live subscription to `themes_updated`; null until the first successful subscribe.
     * Cancelled when the ViewModel is cleared.
     */
    private var themesSub: HaRepository.EventSubscription? = null

    /**
     * Fetch the theme catalogue once per ViewModel lifetime and subscribe to live
     * updates. Called by the dashboard list screen on first entry; idempotent
     * (does nothing if already fetched).
     */
    fun ensureThemesFetched() {
        if (_themeCatalogue.value !== HaThemeCatalogue.EMPTY) return
        if (themesSub != null) return
        viewModelScope.launch {
            val result = haRepository.fetchThemes()
            result.getOrNull()?.let { _themeCatalogue.value = it }
            haRepository.subscribeThemesUpdated { catalogue ->
                _themeCatalogue.value = catalogue
            }.onSuccess { sub -> themesSub = sub }
                .onFailure { R1Log.w("Dashboards", "themes_updated subscribe failed: ${it.message}") }
        }
    }

    /**
     * Set of RAW entity ids referenced by the currently-rendered view. Used
     * to drive [HaRepository.observeRaw] so the renderer subscribes to (and
     * seeds) exactly the entities on screen, regardless of whether their
     * domain is in R1HA's EntityId enum. Updated whenever the rendered view
     * changes.
     */
    private val _renderedEntities = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Live entity-state map for whatever the current view is showing, keyed by
     * the raw `domain.object_id` string. `null` when the view isn't loaded or
     * has no entities; otherwise a stable map. Raw-string keys make the lookup
     * domain-agnostic so a custom-integration sensor renders its value instead
     * of a blank.
     */
    val entities: StateFlow<Map<String, com.github.itskenny0.r1ha.core.ha.EntityState>?> =
        combine(_renderedEntities, _state) { ids, _ -> ids }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet(),
            )
            .let { idsFlow ->
                MutableStateFlow<Map<String, com.github.itskenny0.r1ha.core.ha.EntityState>?>(null).also { sink ->
                    viewModelScope.launch {
                        idsFlow.collect { ids ->
                            if (ids.isEmpty()) {
                                sink.value = emptyMap()
                            } else {
                                haRepository.observeRaw(ids).collect { map -> sink.value = map }
                            }
                        }
                    }
                }
            }

    /**
     * Load the dashboard list once and stash it in state. Idempotent;
     * a re-call refreshes the list (useful after the user adds a dashboard
     * in HA). The default dashboard is always synthesised in slot 0 so
     * the user has a guaranteed-non-empty list to choose from.
     */
    fun loadDashboards() {
        viewModelScope.launch {
            _state.update { it.copy(isLoadingList = true, listError = null) }
            val result = haRepository.listLovelaceDashboards()
            val list = result.fold(
                onSuccess = { arr ->
                    val parsed = LovelaceParser.parseDashboards(arr)
                    // Pin the default-dashboard sentinel at slot 0 so users
                    // who haven't added any custom dashboards still see at
                    // least one entry to drill into. Dedupe against parsed
                    // results just in case HA's list ever decides to include
                    // it (it doesn't today, but the protocol could change).
                    val withDefault = if (parsed.any { it.urlPath == null }) parsed
                    else listOf(DEFAULT_DASHBOARD) + parsed
                    withDefault
                },
                onFailure = {
                    R1Log.w("Dashboards", "listDashboards failed: ${it.message}")
                    listOf(DEFAULT_DASHBOARD)
                },
            )
            _state.update {
                it.copy(
                    dashboards = list,
                    isLoadingList = false,
                    listError = result.exceptionOrNull()?.message,
                )
            }
        }
    }

    /**
     * Fetch + parse a single dashboard's config. Loads from the WS once
     * and caches the parsed tree on [_state.configs]. The cached entry is
     * served on subsequent calls; pass [force] = true to bypass the
     * cache (used by the editor after the user re-imports).
     */
    /**
     * Cache keys whose config is strategy-driven. HA regenerates a strategy
     * dashboard whenever the area/entity registry changes; R1HA's cheap
     * equivalent is to re-expand on every dashboard re-entry (plus the existing
     * 60s [com.github.itskenny0.r1ha.feature.dashboards.cards.AreaRegistryCache]
     * TTL the cards already honour, and the lovelace_updated / reconnect
     * refetches). This is a coarser cadence than HA's live registry events, but
     * it keeps a strategy dashboard fresh without a registry-event subscription.
     */
    private val strategyKeys = mutableSetOf<String>()

    /**
     * Url-paths of every dashboard config loaded this session, keyed by cache
     * key, so a WS reconnect can force-refetch each one. The `lovelace_updated`
     * subscription only fires for live edits; updates that happened while the
     * socket was down are never replayed, so we refetch on reconnect.
     */
    private val loadedUrlPaths = mutableMapOf<String, String?>()

    init {
        observeReconnect()
    }

    /**
     * Refetch every loaded dashboard config when the WS transitions back to
     * [ConnectionState.Connected] from a non-connected state. The first emission
     * (the initial connect) does not trigger a refetch because nothing is loaded
     * yet; [loadConfig] handles the first fetch.
     */
    private fun observeReconnect() {
        viewModelScope.launch {
            var wasConnected = haRepository.connection.value is ConnectionState.Connected
            haRepository.connection.collect { conn ->
                val nowConnected = conn is ConnectionState.Connected
                if (shouldRefetchOnReconnect(wasConnected, nowConnected)) {
                    loadedUrlPaths.values.toList().forEach { urlPath ->
                        loadConfig(urlPath, force = true)
                    }
                }
                wasConnected = nowConnected
            }
        }
    }

    fun loadConfig(urlPath: String?, force: Boolean = false) {
        viewModelScope.launch {
            val cacheKey = urlPath ?: DEFAULT_KEY
            loadedUrlPaths[cacheKey] = urlPath
            // A strategy dashboard always re-expands on (re-)entry so a registry
            // change since the last visit is reflected; concrete dashboards serve
            // the cache.
            val bypassCache = force || cacheKey in strategyKeys
            if (!bypassCache && _state.value.configs.containsKey(cacheKey)) return@launch
            _state.update { it.copy(isLoadingConfig = true, configError = null) }
            // Subscribe to live config updates for this dashboard (idempotent) so
            // an external edit / YAML reload refetches without a manual reload.
            subscribeLovelaceUpdated(urlPath)
            // A forced reload (the manual RELOAD affordance, or a lovelace_updated
            // event) sets HA's `force` flag so a YAML-mode dashboard re-reads its
            // file from disk; a normal cache-miss load serves HA's cached config.
            val result = haRepository.fetchLovelaceConfig(urlPath, forceRefresh = force)
            result.fold(
                onSuccess = { raw ->
                    // Client-side strategy expansion (HA's auto dashboards): when the
                    // config references a strategy anywhere, expand it against a fresh
                    // registry/state snapshot into ordinary card configs the existing
                    // parser + renderer draw, instead of the "open in Lovelace"
                    // fallback. Unknown/custom strategies expand to a labelled card.
                    val effectiveRaw = if (StrategyEngine.hasAnyStrategy(raw)) {
                        strategyKeys.add(cacheKey)
                        runCatching {
                            val data = StrategyDataLoader(haRepository)
                                .load(needsUsagePrediction = StrategyEngine.referencesUsagePrediction(raw))
                            StrategyEngine.expand(raw, data)
                        }.onFailure {
                            R1Log.w("Dashboards", "strategy expand failed: ${it.message}")
                        }.getOrDefault(raw)
                    } else {
                        strategyKeys.remove(cacheKey)
                        raw
                    }
                    val parsed = runCatching { LovelaceParser.parseConfig(effectiveRaw) }
                        .onFailure { R1Log.w("Dashboards", "parse failed: ${it.message}") }
                        .getOrElse { LovelaceConfig(title = null, views = emptyList()) }
                    _state.update { s ->
                        s.copy(
                            configs = s.configs + (cacheKey to parsed),
                            isLoadingConfig = false,
                            configError = null,
                        )
                    }
                },
                onFailure = { t ->
                    R1Log.w("Dashboards", "fetchConfig failed: ${t.message}")
                    _state.update {
                        it.copy(
                            isLoadingConfig = false,
                            configError = t.message ?: "Couldn't load dashboard",
                        )
                    }
                },
            )
        }
    }

    /**
     * Active `lovelace_updated` subscriptions keyed by cache key, so a dashboard
     * is subscribed at most once. HA fires `lovelace_updated` on its event bus
     * whenever a dashboard's stored config changes (an edit from another client,
     * a YAML reload). The handler refetches that dashboard's config live so the
     * rendered view tracks the server without the user reloading.
     */
    private val lovelaceUpdatedSubs =
        mutableMapOf<String, HaRepository.EventSubscription>()

    /**
     * Subscribe to `lovelace_updated` for the dashboard at [urlPath] (idempotent
     * per cache key). HA's event carries a `url_path` in its data; we refetch
     * only when it matches this dashboard (a null url_path is the default
     * dashboard). Best-effort: a server that rejects the subscription just leaves
     * the manual RELOAD affordance as the refresh path.
     */
    private fun subscribeLovelaceUpdated(urlPath: String?) {
        val cacheKey = urlPath ?: DEFAULT_KEY
        if (lovelaceUpdatedSubs.containsKey(cacheKey)) return
        // Reserve the slot synchronously so a rapid re-call doesn't double-subscribe.
        lovelaceUpdatedSubs[cacheKey] = NoopEventSubscription
        viewModelScope.launch {
            haRepository.subscribeEvents("lovelace_updated") { event ->
                val data = event["data"] as? kotlinx.serialization.json.JsonObject
                val eventUrlPath = (data?.get("url_path")
                    as? kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it is kotlinx.serialization.json.JsonNull) null else it.content
                }
                // HA omits url_path (or sends null) for the default dashboard.
                if (eventUrlPath == urlPath) {
                    loadConfig(urlPath, force = true)
                }
            }.onSuccess { sub -> lovelaceUpdatedSubs[cacheKey] = sub }
                .onFailure {
                    R1Log.w("Dashboards", "lovelace_updated subscribe failed: ${it.message}")
                    lovelaceUpdatedSubs.remove(cacheKey)
                }
        }
    }

    override fun onCleared() {
        super.onCleared()
        val subs = lovelaceUpdatedSubs.values.toList()
        lovelaceUpdatedSubs.clear()
        // cancel() is suspend; fire it on the (still-alive briefly) scope so the
        // unsubscribe_events frames go out. Failures are ignored on teardown.
        subs.forEach { sub ->
            viewModelScope.launch { runCatching { sub.cancel() } }
        }
        themesSub?.let { sub ->
            viewModelScope.launch { runCatching { sub.cancel() } }
        }
        themesSub = null
    }

    /**
     * Switch the rendered view. Resets [editMode] (drag-handles always
     * disappear when leaving a view) and updates [_renderedEntities] so
     * the entity-state subscription tracks the new card set. Pass
     * `null` for [viewPath] to surface the dashboard's first view.
     */
    fun selectView(dashboardUrlPath: String?, viewPath: String?) {
        val key = dashboardUrlPath ?: DEFAULT_KEY
        val config = _state.value.configs[key] ?: return
        val view = viewPath?.let { p -> config.views.firstOrNull { it.path == p } }
            ?: config.views.firstOrNull()
        _state.update {
            it.copy(
                selectedDashboardUrlPath = dashboardUrlPath,
                selectedViewPath = view?.path,
                editMode = false,
            )
        }
        updateRenderedEntitiesFromCurrent(config, view)
    }

    fun setEditMode(enabled: Boolean) {
        _state.update { it.copy(editMode = enabled) }
    }

    fun setShowOriginal(enabled: Boolean) {
        _state.update { it.copy(showOriginal = enabled) }
    }

    /**
     * Append a freshly-authored card to the current view. The card body
     * is the JSON the picker emitted (an empty skeleton plus the user's
     * later JSON edits). No-op when no view is selected.
     */
    fun appendCard(rawJson: kotlinx.serialization.json.JsonObject) {
        val (dashKey, viewPath) = currentViewKey() ?: return
        val opKey = LovelaceOverrides.keyFor(currentDashboardUrlPath(), viewPath)
        viewModelScope.launch {
            overrideStore.update { current ->
                current.copy(
                    views = current.views + (opKey to currentViewOverride(current, opKey).let { v ->
                        v.copy(
                            operations = v.operations + com.github.itskenny0.r1ha.core.lovelace.OverrideOp.Append(
                                json = com.github.itskenny0.r1ha.core.lovelace.encodeCardJson(rawJson),
                            ),
                            updatedAt = System.currentTimeMillis(),
                        )
                    }),
                )
            }
            // After the override flow emits the new blob, the renderer
            // re-applies and reflects the appended card. We also nudge
            // the entity-subscription set so a new entity used by the
            // freshly-added card is observed without waiting for the
            // composition to re-trigger.
            refreshRenderedEntities()
        }
    }

    /**
     * Replace the card at [originalIndex] with the JSON object [rawJson].
     * Earlier replacements of the same index get overwritten. only the
     * latest edit survives, matching the editor's "save = current state"
     * semantics.
     */
    fun replaceCard(originalIndex: Int, rawJson: kotlinx.serialization.json.JsonObject) {
        val (_, viewPath) = currentViewKey() ?: return
        val opKey = LovelaceOverrides.keyFor(currentDashboardUrlPath(), viewPath)
        viewModelScope.launch {
            overrideStore.update { current ->
                val view = currentViewOverride(current, opKey)
                val withoutReplace = view.operations.filterNot {
                    it is OverrideOp.Replace && it.index == originalIndex
                }
                val next = view.copy(
                    operations = withoutReplace + com.github.itskenny0.r1ha.core.lovelace.OverrideOp.Replace(
                        index = originalIndex,
                        json = com.github.itskenny0.r1ha.core.lovelace.encodeCardJson(rawJson),
                    ),
                    updatedAt = System.currentTimeMillis(),
                )
                current.copy(views = current.views + (opKey to next))
            }
            refreshRenderedEntities()
        }
    }

    /**
     * Delete the card at [originalIndex]. Stacks with previous deletes
     * so the editor can remove multiple cards without an undo log.
     */
    fun deleteCard(originalIndex: Int) {
        val (_, viewPath) = currentViewKey() ?: return
        val opKey = LovelaceOverrides.keyFor(currentDashboardUrlPath(), viewPath)
        viewModelScope.launch {
            overrideStore.update { current ->
                val view = currentViewOverride(current, opKey)
                val next = view.copy(
                    operations = view.operations + com.github.itskenny0.r1ha.core.lovelace.OverrideOp.Delete(
                        index = originalIndex,
                    ),
                    updatedAt = System.currentTimeMillis(),
                )
                current.copy(views = current.views + (opKey to next))
            }
            refreshRenderedEntities()
        }
    }

    /**
     * Record a reorder operation. [from] and [to] are indices in the
     * current rendered list (post-override-apply) so the editor can pass
     * them straight through from the drag-drop callback.
     */
    fun reorderCard(from: Int, to: Int) {
        if (from == to) return
        val (_, viewPath) = currentViewKey() ?: return
        val opKey = LovelaceOverrides.keyFor(currentDashboardUrlPath(), viewPath)
        viewModelScope.launch {
            overrideStore.update { current ->
                val view = currentViewOverride(current, opKey)
                val next = view.copy(
                    operations = view.operations + com.github.itskenny0.r1ha.core.lovelace.OverrideOp.Reorder(
                        fromIndex = from,
                        toIndex = to,
                    ),
                    updatedAt = System.currentTimeMillis(),
                )
                current.copy(views = current.views + (opKey to next))
            }
        }
    }

    /**
     * Reset every override on the current view. Useful when an edit
     * session went sideways and the user just wants HA's authoritative
     * layout back. No-op when nothing has been overridden.
     */
    fun resetCurrentViewOverrides() {
        val (_, viewPath) = currentViewKey() ?: return
        val opKey = LovelaceOverrides.keyFor(currentDashboardUrlPath(), viewPath)
        viewModelScope.launch {
            overrideStore.update { current ->
                current.copy(views = current.views - opKey)
            }
            refreshRenderedEntities()
        }
    }

    private fun currentDashboardUrlPath(): String? = _state.value.selectedDashboardUrlPath

    private fun currentViewKey(): Pair<String, String>? {
        val s = _state.value
        val viewPath = s.selectedViewPath ?: return null
        val dashKey = s.selectedDashboardUrlPath ?: DEFAULT_KEY
        return dashKey to viewPath
    }

    private fun currentViewOverride(blob: LovelaceOverrides, key: String): ViewOverride =
        blob.views[key] ?: ViewOverride()

    private fun updateRenderedEntitiesFromCurrent(config: LovelaceConfig, view: LovelaceView?) {
        if (view == null) {
            _renderedEntities.value = emptySet()
            return
        }
        _renderedEntities.value = collectRawEntityIds(view)
    }

    private fun refreshRenderedEntities() {
        val key = currentDashboardUrlPath() ?: return _renderedEntities.let { Unit }
        val config = _state.value.configs[key ?: DEFAULT_KEY] ?: return
        val viewPath = _state.value.selectedViewPath
        val view = viewPath?.let { p -> config.views.firstOrNull { it.path == p } } ?: return
        _renderedEntities.value = collectRawEntityIds(view)
    }

    /**
     * Collect the raw entity ids the [view] references: its cards (and their
     * descendants) plus its top-level badges. Delegates the card walk to the
     * renderer-layer traversal so the subscription set and the per-card slice
     * can never drift apart; badge entities are unioned in so the badge row
     * gets live state the same way cards do.
     */
    private fun collectRawEntityIds(
        view: com.github.itskenny0.r1ha.core.lovelace.LovelaceView,
    ): Set<String> {
        val out = LinkedHashSet<String>()
        view.cards.forEach { com.github.itskenny0.r1ha.feature.dashboards.cards.collectEntityIds(it, out) }
        view.badges.forEach { badge -> badge.entityId?.let(out::add) }
        // The header/footer cards render outside the flat `cards` list (so the
        // header's badge placement + the inline footer slot are honoured), so
        // walk them here too or their entities would never be subscribed.
        view.header?.card?.let { com.github.itskenny0.r1ha.feature.dashboards.cards.collectEntityIds(it, out) }
        view.footer?.card?.let { com.github.itskenny0.r1ha.feature.dashboards.cards.collectEntityIds(it, out) }
        return out
    }

    /**
     * Placeholder subscription parked in the map while the real subscribe is in
     * flight, so a rapid second [subscribeLovelaceUpdated] for the same dashboard
     * doesn't double-subscribe. Its cancel is a no-op; the real handle replaces
     * it on success or it is removed on failure.
     */
    private object NoopEventSubscription : com.github.itskenny0.r1ha.core.ha.HaRepository.EventSubscription {
        override suspend fun cancel() {}
    }

    companion object {

        /** Storage key for the synthesised default-dashboard entry. */
        const val DEFAULT_KEY: String = "_default_"

        /**
         * True when the connection just transitioned disconnected -> connected,
         * the cue to refetch dashboard configs (missed `lovelace_updated` events
         * during the gap aren't replayed). Staying connected, or going down, is
         * not a reconnect.
         */
        fun shouldRefetchOnReconnect(wasConnected: Boolean, nowConnected: Boolean): Boolean =
            nowConnected && !wasConnected

        /** Synthetic descriptor for HA's default dashboard. The user-visible
         *  title is intentionally Title-Case-no-em-dashes for parity with the
         *  rest of R1HA's chrome. */
        val DEFAULT_DASHBOARD = LovelaceDashboard(
            id = null,
            urlPath = null,
            title = "Default dashboard",
            icon = "mdi:view-dashboard",
            showInSidebar = true,
            requireAdmin = false,
            mode = "storage",
        )

        fun factory(
            haRepository: HaRepository,
            overrideStore: LovelaceOverrideStore,
        ): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                DashboardsViewModel(
                    haRepository = haRepository,
                    overrideStore = overrideStore,
                )
            }
        }
    }
}

/**
 * Top-level UI state. The list / per-dashboard config / per-view selection
 * are all driven by this one object so screens can read a single
 * `collectAsState()` for everything they need.
 */
data class DashboardsState(
    val dashboards: List<LovelaceDashboard> = emptyList(),
    val configs: Map<String, LovelaceConfig> = emptyMap(),
    val isLoadingList: Boolean = false,
    val isLoadingConfig: Boolean = false,
    val listError: String? = null,
    val configError: String? = null,
    val selectedDashboardUrlPath: String? = null,
    val selectedViewPath: String? = null,
    /** True when the editor overlay is visible (drag handles + chips). */
    val editMode: Boolean = false,
    /** When true, render HA's authoritative card list bypassing any local
     *  overrides. The editor surfaces a toggle so the user can compare. */
    val showOriginal: Boolean = false,
)
