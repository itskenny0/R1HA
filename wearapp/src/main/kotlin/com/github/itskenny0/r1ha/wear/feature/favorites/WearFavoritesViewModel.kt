package com.github.itskenny0.r1ha.wear.feature.favorites

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class WearFavoritesUiState(
    /** Ordered by the user's favourites list. */
    val entities: List<EntityState> = emptyList(),
    /** True once settings have loaded and there is at least one favourite. */
    val hasFavorites: Boolean = false,
    /** True while the initial entity-state load is in flight. */
    val loading: Boolean = true,
)

/**
 * ViewModel for the Wear OS Favourites screen.
 *
 * Reads the user's pinned entity IDs from [SettingsRepository], then:
 * 1. Does a one-shot REST call via [HaRepository.listAllEntities] to prime the
 *    display immediately (the WS cache only seeds favourites after the WS
 *    authenticates, which can take a moment).
 * 2. Subscribes to [HaRepository.observe] for live WS-pushed state changes,
 *    merging updates on top of the REST baseline.
 */
class WearFavoritesViewModel(
    private val haRepository: HaRepository,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearFavoritesUiState())
    val uiState: StateFlow<WearFavoritesUiState> = _uiState

    init {
        loadFavorites()
    }

    private fun loadFavorites() {
        viewModelScope.launch {
            val favIds = settings.settings.first().favorites
                .mapNotNull { runCatching { EntityId(it) }.getOrNull() }
                .toSet()

            _uiState.value = _uiState.value.copy(
                hasFavorites = favIds.isNotEmpty(),
                loading = favIds.isNotEmpty(), // only "loading" if there's something to load
            )
            if (favIds.isEmpty()) return@launch

            // Prime display from REST — the WS cache may not have these entities yet.
            haRepository.listAllEntities().onSuccess { all ->
                val ordered = favIds.mapNotNull { id -> all.find { it.id == id } }
                if (ordered.isNotEmpty()) {
                    _uiState.value = _uiState.value.copy(entities = ordered, loading = false)
                }
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false)
            }

            // Live WS cache updates — merge on top of REST baseline so a partial
            // cache emission doesn't clobber entities the cache hasn't seen yet.
            haRepository.observe(favIds)
                .onEach { stateMap ->
                    if (stateMap.isNotEmpty()) {
                        _uiState.value = _uiState.value.copy(
                            entities = favIds.mapNotNull { stateMap[it] },
                            loading = false,
                        )
                    }
                }
                .launchIn(viewModelScope)
        }
    }

    fun onEntityTap(entity: EntityState) {
        // Optimistic flip for instant visual feedback.
        val optimistic = entity.copy(isOn = !entity.isOn)
        _uiState.value = _uiState.value.copy(
            entities = _uiState.value.entities.map { if (it.id == entity.id) optimistic else it },
        )
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entity.id, entity.isOn))
            // Refresh confirmed state after HA processes the command.
            delay(1_200)
            haRepository.listAllEntities().onSuccess { all ->
                val current = _uiState.value.entities
                val updated = current.map { e -> all.find { it.id == e.id } ?: e }
                _uiState.value = _uiState.value.copy(entities = updated)
            }
        }
    }

    fun refresh() = loadFavorites()

    companion object {
        fun factory(haRepository: HaRepository, settings: SettingsRepository) = viewModelFactory {
            initializer { WearFavoritesViewModel(haRepository, settings) }
        }
    }
}
