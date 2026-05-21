package com.github.itskenny0.r1ha.wear.feature.cardstack

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.LovelaceViewInfo
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * UI state for the Lovelace-based Wear card stack.
 *
 * [views] mirrors the user's HA dashboard tabs. [entityStates] is a live map
 * of every entity ID that appears in any view — the screen filters this map per
 * tab to build each tab's chip list. [currentTabIndex] tracks the pager's
 * visible page so the VM can sync if needed in future.
 */
data class WearCardStackUiState(
    val views: List<LovelaceViewInfo> = emptyList(),
    val currentTabIndex: Int = 0,
    val entityStates: Map<EntityId, EntityState> = emptyMap(),
    val loading: Boolean = true,
    val error: String? = null,
)

/**
 * ViewModel for the Lovelace-tab card stack on Wear OS.
 *
 * On init it fetches `/api/lovelace/config`, extracts views (tabs) and their
 * entity IDs, then subscribes to live state updates for all those entities via
 * the WebSocket. The screen renders one pager page per view; within each page
 * a scrollable list of entity chips is shown. The bezel/crown navigates between
 * tabs; swipe scrolls the list within a tab.
 */
class WearCardStackViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearCardStackUiState())
    val uiState: StateFlow<WearCardStackUiState> = _uiState

    init {
        loadViews()
        collectCallFailures()
    }

    // ── Lovelace view loading ────────────────────────────────────────────────

    private fun loadViews() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(loading = true, error = null)
            haRepository.fetchLovelaceViews()
                .onSuccess { views ->
                    R1Log.i("WearCardStack", "loaded ${views.size} views from Lovelace")
                    _uiState.value = _uiState.value.copy(views = views, loading = false)
                    subscribeToAllEntities(views)
                }
                .onFailure { t ->
                    R1Log.w("WearCardStack", "Lovelace fetch failed: ${t.message}")
                    _uiState.value = _uiState.value.copy(
                        loading = false,
                        error = t.message ?: "Failed to load dashboard",
                    )
                }
        }
    }

    private fun subscribeToAllEntities(views: List<LovelaceViewInfo>) {
        val allIds = views
            .flatMap { it.entityIds }
            .distinct()
            .map { EntityId(it) }
            .toSet()
        if (allIds.isEmpty()) return

        // Eagerly fetch current states via REST. The WS cache only seeds the user's
        // favourites, so Lovelace entities not in the favourites list would otherwise
        // show "Connecting…" forever. This one-shot REST call primes entityStates so
        // the tabs paint immediately on first open.
        viewModelScope.launch {
            haRepository.listAllEntities().onSuccess { all ->
                val initial = all.filter { it.id in allIds }.associateBy { it.id }
                if (initial.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(entityStates = initial)
                }
            }
        }

        // Also watch the WS cache for live updates (e.g. entities that ARE favourited).
        // Merge onto the REST baseline — don't replace — so a partial cache emission
        // doesn't clobber states for entities the cache doesn't know about yet.
        haRepository.observe(allIds)
            .onEach { stateMap ->
                if (stateMap.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        entityStates = _uiState.value.entityStates + stateMap,
                    )
                }
            }
            .launchIn(viewModelScope)
    }

    // ── Call-failure handling ────────────────────────────────────────────────

    private fun collectCallFailures() {
        haRepository.callFailures
            .onEach { failedId ->
                R1Log.w("WearCardStack", "call failed for ${failedId.value}")
            }
            .launchIn(viewModelScope)
    }

    // ── Public actions ───────────────────────────────────────────────────────

    fun onTabChanged(newIndex: Int) {
        _uiState.value = _uiState.value.copy(currentTabIndex = newIndex)
    }

    fun onEntityTap(entity: EntityState) {
        // Optimistic flip so the UI reacts instantly without waiting for a network round-trip.
        val optimistic = entity.copy(isOn = !entity.isOn)
        _uiState.value = _uiState.value.copy(
            entityStates = _uiState.value.entityStates + (entity.id to optimistic),
        )
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entity.id, entity.isOn))
            // After HA processes the command it pushes a state_changed over WS, but the
            // WS subscription only covers favourites.  Do a lightweight REST refresh of
            // ALL entities to confirm the new state and correct any optimistic mismatch.
            delay(1_200)
            haRepository.listAllEntities().onSuccess { all ->
                val relevant = all
                    .filter { it.id in _uiState.value.entityStates }
                    .associateBy { it.id }
                if (relevant.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        entityStates = _uiState.value.entityStates + relevant,
                    )
                }
            }
        }
    }

    fun retry() = loadViews()

    // ── Factory ──────────────────────────────────────────────────────────────

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { WearCardStackViewModel(haRepository) }
        }
    }
}
