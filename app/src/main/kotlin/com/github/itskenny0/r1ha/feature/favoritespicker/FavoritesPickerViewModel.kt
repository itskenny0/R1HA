package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Filter chip — groups related domains into a single user-facing label. Picked so that the
 * chip row stays readable on a 240 px display (six or seven chips, not fifteen).
 */
enum class PickerFilter(val label: String, val matches: (Domain) -> Boolean) {
    ALL("ALL", { true }),
    FAVS("★ FAVS", { true }),  // "isFavorite" filter applied outside `matches`; this entry is special-cased.
    LIGHTS("LIGHTS", { it == Domain.LIGHT }),
    SWITCHES("SWITCHES", { it == Domain.SWITCH || it == Domain.INPUT_BOOLEAN || it == Domain.AUTOMATION }),
    COVERS("COVERS", { it == Domain.COVER }),
    // Valves get their own chip rather than living under COVERS — HA keeps the two
    // domains distinct (water valves vs window covers) and grouping them confused
    // discovery for users who knew they had a `valve.foo` entity but couldn't find it
    // by searching "valve".
    VALVES("VALVES", { it == Domain.VALVE }),
    CLIMATE("CLIMATE", { it == Domain.CLIMATE || it == Domain.HUMIDIFIER || it == Domain.FAN || it == Domain.WATER_HEATER }),
    // LOCKS bucket also catches alarm control panels — they share the security
    // affordance vocabulary on the deck (PIN keypad, armed-state framing) so
    // grouping them under one chip keeps discovery sensible without inflating
    // the chip strip with a one-domain ALARMS bucket.
    LOCKS("LOCKS", { it == Domain.LOCK || it == Domain.ALARM_CONTROL_PANEL }),
    MEDIA("MEDIA", { it == Domain.MEDIA_PLAYER }),
    // Action-only entities — scene/script/button/input_button. SCENES is the
    // human-friendly umbrella label even though it also covers scripts/buttons,
    // because that's the most-searched-for kind in this group.
    SCENES("SCENES", { it.isAction }),
    SENSORS("SENSORS", { it.isSensor }),
    // Number / input_number — settable scalars common in MQTT integrations (pump
    // speeds, calibration knobs, manual setpoints). Previously hidden inside ALL
    // because no chip filtered for them.
    NUMBERS("NUMBERS", { it == Domain.NUMBER || it == Domain.INPUT_NUMBER }),
    VACUUMS("VACUUMS", { it == Domain.VACUUM || it == Domain.LAWN_MOWER }),
    // Settable-enum entities — select / input_select. Useful for fan-mode selectors,
    // operating-mode pickers, room-target selectors for vacuums, etc.
    SELECTS("SELECTS", { it.isSelect }),
}

class FavoritesPickerViewModel(
    private val repo: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Row(
        val state: EntityState,
        val isFavorite: Boolean,
        val orderIndex: Int?,
        /** Display name after applying any client-side rename override; defaults to
         *  `state.friendlyName`. UI binds to this so the override appears live without
         *  the row composable needing to know about the override mechanism. */
        val displayName: String,
    )
    /**
     * Sort order applied within a filter tab. FAVS always sorts by orderIndex
     * regardless of this setting (the user is reasoning about card position
     * there, not alphabetical order). Other tabs default to ALPHA and the
     * user can flip into AREA (group physically) or DOMAIN (group by entity
     * type, useful when CONTROLLABLE / ALL chips are showing a heterogeneous
     * mix).
     */
    enum class SortOrder(val label: String) {
        ALPHA("A→Z"),
        AREA("BY AREA"),
        DOMAIN("BY KIND"),
    }

    /** Internal projection of the settings flow the reactive rebuild listens on.
     *  distinctUntilChanged works on this so a settings emission that doesn't touch
     *  the picker (e.g. a wheel-acceleration tweak) doesn't trigger a rebuild. */
    private data class PageSnapshot(
        val overrides: Map<String, String>,
        val favs: List<String>,
        val pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
        val activePageId: String,
    )

    data class UiState(
        val loading: Boolean = true,
        val rows: List<Row> = emptyList(),
        val error: String? = null,
        val filter: PickerFilter = PickerFilter.ALL,
        /** Total counts per filter chip — surfaces a small number next to each chip so
         *  the user can see at a glance how many entities of each kind are available
         *  even before tapping the chip. */
        val countsByFilter: Map<PickerFilter, Int> = emptyMap(),
        /** Free-text search query — applied AFTER the filter chip. Case-insensitive
         *  substring match against display name + entity_id. */
        val query: String = "",
        /** Entity currently being renamed via the rename dialog, or null when no dialog
         *  is open. Picker observes this to show/hide the dialog overlay. */
        val editingEntityId: String? = null,
        /** Per-filter-tab sort selector. Survives navigation back to the picker
         *  but not process death — the picker is short-lived enough that DataStore
         *  persistence felt like over-engineering. FAVS tab ignores this. */
        val sortPerFilter: Map<PickerFilter, SortOrder> = emptyMap(),
        /** Card-stack tab groups the picker can edit. The picker only mutates the
         *  ACTIVE page's favourites; this list drives the page-selector strip so the
         *  user can see which tab they're editing and switch to another without
         *  backing out to the card stack. */
        val pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage> = emptyList(),
        /** Id of the page whose favourites the picker is currently editing. */
        val activePageId: String = "",
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    /** Sort order in force for the current filter tab. FAVS is always orderIndex
     *  (handled inside [buildRows]); every other tab reads its sticky per-tab choice,
     *  defaulting to ALPHA. Centralised so favourite-toggle and reorder rebuilds keep
     *  the active sort instead of silently snapping back to ALPHA. */
    private fun UiState.currentSort(): SortOrder =
        sortPerFilter[filter] ?: SortOrder.ALPHA
    /** Cached list of all controllable entities from the latest /api/states fetch. Toggling or
     *  reordering favourites doesn't change this list, so we can update [_ui] locally without
     *  re-fetching every time the user taps a checkbox. */
    private var entitiesCache: List<EntityState> = emptyList()

    init { refresh() }

    init {
        // Re-build rows whenever the rename-override map changes so the UI picks up a
        // freshly-saved rename even though we haven't refetched HA's entities. Same goes
        // for the favourites list — when the user un-favourites from CardStack, the
        // picker should reflect it. Subscribed for the VM lifetime; cheap because the
        // upstream Flow is distinctUntilChanged'd on the data we care about.
        viewModelScope.launch {
            // Subscribe to the active page's favourites — not the legacy global
            // [favorites]. The flat-union favourites field is still maintained but
            // a 'favourite' in picker terms means 'is in the currently-active page'.
            // Switching pages from the card stack will flow a new active-page id
            // here, the picker re-renders with that page's contents.
            settings.settings
                .map { s ->
                    val active = s.pages.firstOrNull { p -> p.id == s.activePageId }
                    PageSnapshot(
                        overrides = s.nameOverrides,
                        favs = active?.favorites.orEmpty(),
                        pages = s.pages,
                        activePageId = s.activePageId,
                    )
                }
                .distinctUntilChanged()
                .collect { snap ->
                    val cur = _ui.value
                    // Always reflect the page strip (names/active id) even before the
                    // entity fetch lands, so the picker shows which tab it edits while
                    // still on the loading spinner.
                    var next = cur.copy(pages = snap.pages, activePageId = snap.activePageId)
                    if (entitiesCache.isNotEmpty()) {
                        next = next.copy(
                            rows = buildRows(
                                entitiesCache, snap.favs, cur.filter, cur.query, snap.overrides,
                                sortOrder = cur.currentSort(),
                            ),
                            countsByFilter = countsByFilter(entitiesCache, snap.favs),
                        )
                    }
                    _ui.value = next
                }
        }
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            val snapshot = settings.settings.first()
            R1Log.i("FavoritesPicker.refresh", "server=${snapshot.server?.url ?: "null"} favoritesSoFar=${snapshot.favorites.size}")
            // 20s overall ceiling. OkHttp already enforces connect/read timeouts, but
            // a wedged DNS lookup or an HA that accepts the TCP connection and then
            // never responds can still pile multiple sub-timeouts on top of each
            // other. Capping the whole call guarantees the loading state can't
            // outlast the user's patience; on expiry we surface an error rather than
            // letting the picker sit on its spinner indefinitely.
            val all = kotlinx.coroutines.withTimeoutOrNull(20_000L) { repo.listAllEntities() }
                ?: Result.failure(java.util.concurrent.TimeoutException("Took longer than 20 s"))
            // Picker shows favourites of the ACTIVE page (other pages aren't visible
            // here; the user would switch pages on the card stack first). Falls back
            // to flat-union when there's no active page resolved (shouldn't happen
            // after migration, but defensive).
            val favs = snapshot.pages.firstOrNull { it.id == snapshot.activePageId }
                ?.favorites
                ?: snapshot.favorites
            all.fold(
                onSuccess = { list ->
                    // Keep BOTH scalar-controllable and on/off-only entities — on/off ones
                    // render as a switch card on CardStack (wheel up/down flips them, tap
                    // toggles) rather than being hidden entirely.
                    entitiesCache = list
                    val cur = _ui.value
                    _ui.value = cur.copy(
                        loading = false,
                        rows = buildRows(
                            list, favs, cur.filter, cur.query, snapshot.nameOverrides,
                            sortOrder = cur.currentSort(),
                        ),
                        countsByFilter = countsByFilter(list, favs),
                        pages = snapshot.pages,
                        activePageId = snapshot.activePageId,
                    )
                    R1Log.i("FavoritesPicker.refresh", "fetched ${list.size} entities")
                },
                onFailure = {
                    R1Log.e("FavoritesPicker.refresh", "fetch failed", it)
                    Toaster.error("Fetch failed: ${it.message}")
                    // Preserve the page strip + filter/query/sort so an error doesn't
                    // blank the chrome the user was working in; only the row list and
                    // loading/error flags change. A retry via pull-to-refresh then keeps
                    // their context.
                    _ui.value = _ui.value.copy(loading = false, error = it.message, rows = emptyList())
                },
            )
        }
    }

    fun setQuery(q: String) {
        val cur = _ui.value
        if (cur.query == q) return
        // SYNC update of the query string so the search field's value parameter reflects
        // every keystroke immediately. Without this, the previous implementation hopped
        // through viewModelScope.launch → settings.first() before publishing the new
        // query, leaving BasicTextField recomposing with a one-step-old value. The IME's
        // composing region landed on a stale string and characters appeared transposed
        // ("testing" → "tetings"). Filtering work (which needs settings access) hops
        // async below; the visible text stays in lock-step with the user's keystrokes.
        _ui.value = cur.copy(query = q)
        viewModelScope.launch {
            val snapshot = settings.settings.first()
            val favs = snapshot.pages.firstOrNull { it.id == snapshot.activePageId }
                ?.favorites
                ?: snapshot.favorites
            // Read the LATEST query and filter (not the captured `cur`) — by the time
            // this coroutine runs the user may have typed more characters, and we want
            // the result list to reflect that.
            val now = _ui.value
            _ui.value = now.copy(
                rows = buildRows(
                    entitiesCache, favs, now.filter, now.query, snapshot.nameOverrides,
                    sortOrder = now.currentSort(),
                ),
            )
        }
    }

    fun startEditing(entityId: String) {
        _ui.value = _ui.value.copy(editingEntityId = entityId)
    }

    fun cancelEditing() {
        _ui.value = _ui.value.copy(editingEntityId = null)
    }

    /** Save the customize dialog — name + per-card override map. Blank [newName] removes
     *  the name override and restores HA's `friendly_name`; an override matching the
     *  default ([com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE]) is dropped
     *  from the map so a card the user "reset to defaults" doesn't keep an empty entry
     *  hanging around in preferences. */
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
            _ui.value = _ui.value.copy(editingEntityId = null)
        }
    }

    /** Switch the active filter chip. Re-evaluates [buildRows] against the cached entity
     *  set — no network refetch needed, just a local prune. */
    fun setFilter(filter: PickerFilter) {
        val cur = _ui.value
        if (cur.filter == filter) return
        viewModelScope.launch {
            val snapshot = settings.settings.first()
            val favs = snapshot.pages.firstOrNull { it.id == snapshot.activePageId }
                ?.favorites
                ?: snapshot.favorites
            _ui.value = cur.copy(
                filter = filter,
                rows = buildRows(
                    entitiesCache, favs, filter, cur.query, snapshot.nameOverrides,
                    sortOrder = cur.sortPerFilter[filter] ?: SortOrder.ALPHA,
                ),
            )
        }
    }

    /** Cycle the sort order for the active filter tab. Persists in [UiState]
     *  so re-entering this tab later in the same picker session restores it.
     *  FAVS ignores the setter — its sort is locked to orderIndex. */
    fun cycleSortOrder() {
        val cur = _ui.value
        if (cur.filter == PickerFilter.FAVS) return
        val now = cur.sortPerFilter[cur.filter] ?: SortOrder.ALPHA
        val next = when (now) {
            SortOrder.ALPHA -> SortOrder.AREA
            SortOrder.AREA -> SortOrder.DOMAIN
            SortOrder.DOMAIN -> SortOrder.ALPHA
        }
        viewModelScope.launch {
            val snapshot = settings.settings.first()
            val favs = snapshot.pages.firstOrNull { it.id == snapshot.activePageId }
                ?.favorites
                ?: snapshot.favorites
            _ui.value = cur.copy(
                sortPerFilter = cur.sortPerFilter + (cur.filter to next),
                rows = buildRows(
                    entitiesCache, favs, cur.filter, cur.query, snapshot.nameOverrides,
                    sortOrder = next,
                ),
            )
        }
    }

    /** Build the row list from cached entities + the current favourites list. Sorted by name
     *  rather than favourites-first; toggling a checkbox no longer reorders the list, which
     *  prevents the visible page from jumping when the user is selecting several entities
     *  back-to-back. The up/down arrows still mutate favourites order — visible in CardStack.
     *  Applies the active filter chip and (case-insensitive) the [query] substring match
     *  against both the *display* name (override or HA's friendly_name) and the entity_id —
     *  searching by entity_id is useful for HA users who know what they typed in their
     *  configuration but can't remember the friendly name. */
    private fun buildRows(
        entities: List<EntityState>,
        favs: List<String>,
        filter: PickerFilter,
        query: String,
        overrides: Map<String, String>,
        sortOrder: SortOrder = SortOrder.ALPHA,
    ): List<Row> {
        val favOrder = favs.withIndex().associate { (idx, id) -> id to idx }
        val q = query.trim().lowercase()
        return entities
            .asSequence()
            .filter { ent ->
                when (filter) {
                    PickerFilter.ALL -> true
                    PickerFilter.FAVS -> ent.id.value in favOrder
                    else -> filter.matches(ent.id.domain)
                }
            }
            .map { ent ->
                val display = overrides[ent.id.value] ?: ent.friendlyName
                Row(
                    state = ent,
                    isFavorite = ent.id.value in favOrder,
                    orderIndex = favOrder[ent.id.value],
                    displayName = display,
                )
            }
            .filter { row ->
                if (q.isEmpty()) true
                else row.displayName.lowercase().contains(q) ||
                    row.state.id.value.lowercase().contains(q)
            }
            .toList()
            .let { rows ->
                // On the FAVS chip the user is reasoning about their card-stack order, so
                // sort by orderIndex — matches the order they'll see on the main screen
                // and is the order they're reordering via the move-up/down chevrons.
                // Every other view sorts alphabetically by display name (the usual case
                // when they're hunting for something to favourite).
                when {
                    filter == PickerFilter.FAVS ->
                        rows.sortedBy { it.orderIndex ?: Int.MAX_VALUE }
                    sortOrder == SortOrder.AREA ->
                        // Stable alphabetical-within-area: empty area sinks to the bottom.
                        rows.sortedWith(
                            compareBy<Row> { it.state.area?.lowercase() ?: "￿" }
                                .thenBy { it.displayName.lowercase() },
                        )
                    sortOrder == SortOrder.DOMAIN ->
                        rows.sortedWith(
                            compareBy<Row> { it.state.id.domain.name }
                                .thenBy { it.displayName.lowercase() },
                        )
                    else -> rows.sortedBy { it.displayName.lowercase() }
                }
            }
    }

    /** Tally how many entities match each filter — surfaces as a small badge on each chip
     *  so the user knows at a glance which filters are populated. Computed once per refresh
     *  rather than per-render so chip layout stays cheap. */
    private fun countsByFilter(entities: List<EntityState>, favs: List<String>): Map<PickerFilter, Int> {
        val favSet = favs.toSet()
        return PickerFilter.entries.associateWith { f ->
            when (f) {
                PickerFilter.ALL -> entities.size
                PickerFilter.FAVS -> entities.count { it.id.value in favSet }
                else -> entities.count { f.matches(it.id.domain) }
            }
        }
    }

    fun toggle(entityId: String) {
        viewModelScope.launch {
            // Toggle the entity in the ACTIVE PAGE only — pre-tabs builds had a
            // single global favourites list; with tabs, add/remove operations are
            // scoped to whichever page the user has selected in the card stack.
            // updateActivePage handles the mutex + favourites-union recalculation.
            settings.updateActivePage { page ->
                // De-dupe defensively: collapse any pre-existing duplicate entries first
                // (an older build or a hand-edited backup could have introduced them),
                // then toggle. A duplicate in the list would otherwise render the same
                // card twice in the deck and throw off the orderIndex / reorder math.
                val l = page.favorites.distinct().toMutableList()
                if (entityId in l) l.remove(entityId) else l.add(entityId)
                page.copy(favorites = l)
            }
            // Local re-render reads from the active page after the write completes.
            rebuildAfterFavMutation()
        }
    }

    /** Re-derive the row list + counts from the just-persisted active page. Shared by
     *  every favourite mutation so they all preserve the active filter/query/sort
     *  instead of each re-spelling the rebuild (and forgetting the sort, as the
     *  reorder paths previously did, which snapped the tab back to A→Z on every nudge). */
    private suspend fun rebuildAfterFavMutation() {
        val snapshot = settings.settings.first()
        val newFavs = snapshot.pages.firstOrNull { it.id == snapshot.activePageId }?.favorites.orEmpty()
        val cur = _ui.value
        _ui.value = cur.copy(
            rows = buildRows(
                entitiesCache, newFavs, cur.filter, cur.query, snapshot.nameOverrides,
                sortOrder = cur.currentSort(),
            ),
            countsByFilter = countsByFilter(entitiesCache, newFavs),
        )
    }

    fun moveUp(entityId: String) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                val l = page.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx > 0) { l.removeAt(idx); l.add(idx - 1, entityId) }
                page.copy(favorites = l)
            }
            rebuildAfterFavMutation()
        }
    }

    /**
     * Index-based reorder — moves the favourite currently at [fromIndex] to
     * [toIndex] within the active page's favourites list. Backs the drag-reorder
     * gesture in the FAVS view.
     *
     * Index-based (not entity-id based) on purpose: [com.github.itskenny0.r1ha.ui.components.DragReorderColumn]
     * emits a swap for every neighbour the finger crosses, sometimes several within a
     * single frame, and expects each swap applied to the result of the previous one.
     * The previous implementation re-resolved the entity_id from the on-screen `rows`
     * snapshot per swap; because the persist + flow round-trip is async, `rows` was
     * stale by the second same-frame swap and the WRONG entity was moved on fast drags.
     * Each [updateActivePage] runs serially under the settings mutex and reads the
     * latest persisted list, so composing index moves remove/insert correctly — matching
     * the card stack's proven reorder path.
     */
    fun reorderFavorite(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        viewModelScope.launch {
            settings.updateActivePage { page ->
                val l = page.favorites.toMutableList()
                if (fromIndex !in l.indices) return@updateActivePage page
                val clamped = toIndex.coerceIn(0, l.size - 1)
                if (fromIndex == clamped) return@updateActivePage page
                val item = l.removeAt(fromIndex)
                l.add(clamped, item)
                page.copy(favorites = l)
            }
            rebuildAfterFavMutation()
        }
    }

    fun moveDown(entityId: String) {
        viewModelScope.launch {
            settings.updateActivePage { page ->
                val l = page.favorites.toMutableList()
                val idx = l.indexOf(entityId)
                if (idx in 0 until l.size - 1) { l.removeAt(idx); l.add(idx + 1, entityId) }
                page.copy(favorites = l)
            }
            rebuildAfterFavMutation()
        }
    }

    // ── Tab-group (page) management ──────────────────────────────────────────────
    // The picker edits ONE page's favourites at a time (the active page). These let
    // the user switch which page they're editing and do basic create / rename / delete
    // without leaving the picker. All delegate to the repository's page API, which owns
    // the mutex, the favourites-union recalculation, and the "always keep at least one
    // page" / active-id-clamp invariants — the picker never reasons about those itself.

    /** Switch the page whose favourites the picker edits. The reactive settings flow
     *  rebuilds the row list for the new page automatically, so no explicit rebuild
     *  here. No-op for the already-active page (the repository de-dupes too). */
    fun selectPage(pageId: String) {
        viewModelScope.launch { settings.setActivePage(pageId) }
    }

    /** Create a new (empty) tab group and switch to it. Blank names are ignored so a
     *  stray SAVE on an empty field can't spawn an unlabelled tab. */
    fun addPage(name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { settings.addPage(trimmed) }
    }

    /** Rename a tab group. Blank names are ignored so the user can't erase a tab's
     *  label into an unidentifiable blank. */
    fun renamePage(pageId: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch { settings.renamePage(pageId, trimmed) }
    }

    /** Delete a tab group. The repository refuses to delete the only remaining page and
     *  re-points the active id, so the picker can call this unconditionally; we mirror
     *  the guard here only to avoid firing a no-op write. */
    fun deletePage(pageId: String) {
        if (_ui.value.pages.size <= 1) return
        viewModelScope.launch { settings.deletePage(pageId) }
    }

    companion object {
        fun factory(
            repo: HaRepository,
            settings: SettingsRepository,
        ) = viewModelFactory {
            initializer {
                FavoritesPickerViewModel(repo = repo, settings = settings)
            }
        }
    }
}
