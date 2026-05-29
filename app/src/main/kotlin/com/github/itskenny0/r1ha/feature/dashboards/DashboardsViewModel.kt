package com.github.itskenny0.r1ha.feature.dashboards

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConfig
import com.github.itskenny0.r1ha.core.lovelace.LovelaceDashboard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrides
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.lovelace.OverrideOp
import com.github.itskenny0.r1ha.core.lovelace.ViewOverride
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
     * Set of entity ids referenced by the currently-rendered view. Used
     * to drive [HaRepository.observe] so the renderer only subscribes to
     * the entities actually on screen. Updated whenever the rendered
     * view changes.
     */
    private val _renderedEntities = MutableStateFlow<Set<EntityId>>(emptySet())

    /**
     * Live entity-state map for whatever the current view is showing.
     * `null` when the view isn't loaded or has no entities; otherwise
     * a stable Map keyed by EntityId.
     */
    val entities: StateFlow<Map<EntityId, com.github.itskenny0.r1ha.core.ha.EntityState>?> =
        combine(_renderedEntities, _state) { ids, _ -> ids }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(5_000),
                initialValue = emptySet(),
            )
            .let { idsFlow ->
                MutableStateFlow<Map<EntityId, com.github.itskenny0.r1ha.core.ha.EntityState>?>(null).also { sink ->
                    viewModelScope.launch {
                        idsFlow.collect { ids ->
                            if (ids.isEmpty()) {
                                sink.value = emptyMap()
                            } else {
                                haRepository.observe(ids).collect { map -> sink.value = map }
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
    fun loadConfig(urlPath: String?, force: Boolean = false) {
        viewModelScope.launch {
            val cacheKey = urlPath ?: DEFAULT_KEY
            if (!force && _state.value.configs.containsKey(cacheKey)) return@launch
            _state.update { it.copy(isLoadingConfig = true, configError = null) }
            val result = haRepository.fetchLovelaceConfig(urlPath)
            result.fold(
                onSuccess = { raw ->
                    val parsed = runCatching { LovelaceParser.parseConfig(raw) }
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
        _renderedEntities.value = collectEntityIds(view.cards)
    }

    private fun refreshRenderedEntities() {
        val key = currentDashboardUrlPath() ?: return _renderedEntities.let { Unit }
        val config = _state.value.configs[key ?: DEFAULT_KEY] ?: return
        val viewPath = _state.value.selectedViewPath
        val view = viewPath?.let { p -> config.views.firstOrNull { it.path == p } } ?: return
        _renderedEntities.value = collectEntityIds(view.cards)
    }

    private fun collectEntityIds(cards: List<com.github.itskenny0.r1ha.core.lovelace.LovelaceCard>): Set<EntityId> {
        val out = mutableSetOf<EntityId>()
        cards.forEach { collectEntityIdsFromCard(it, out) }
        return out
    }

    private fun collectEntityIdsFromCard(
        card: com.github.itskenny0.r1ha.core.lovelace.LovelaceCard,
        sink: MutableSet<EntityId>,
    ) {
        when (card) {
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Entities ->
                card.entities.forEach { sink.addOptional(it.entityId) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Glance ->
                card.entities.forEach { sink.addOptional(it.entityId) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Button ->
                card.entityId?.let { sink.addOptional(it) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Tile -> sink.addOptional(card.entityId)
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Light -> sink.addOptional(card.entityId)
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Gauge -> sink.addOptional(card.entityId)
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.WeatherForecast -> sink.addOptional(card.entityId)
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.VerticalStack ->
                card.cards.forEach { collectEntityIdsFromCard(it, sink) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.HorizontalStack ->
                card.cards.forEach { collectEntityIdsFromCard(it, sink) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Grid ->
                card.cards.forEach { collectEntityIdsFromCard(it, sink) }
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Conditional ->
                collectEntityIdsFromCard(card.card, sink)
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Markdown -> Unit
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Heading -> Unit
            is com.github.itskenny0.r1ha.core.lovelace.LovelaceCard.Unsupported -> Unit
        }
    }

    private fun MutableSet<EntityId>.addOptional(raw: String) {
        if (raw.isBlank() || '.' !in raw) return
        runCatching { EntityId(raw) }.getOrNull()?.let { add(it) }
    }

    companion object {

        /** Storage key for the synthesised default-dashboard entry. */
        const val DEFAULT_KEY: String = "_default_"

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
