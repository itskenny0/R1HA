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
        haRepository.observe(allIds)
            .onEach { stateMap ->
                _uiState.value = _uiState.value.copy(entityStates = stateMap)
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
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entity.id, entity.isOn))
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
