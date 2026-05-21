package com.github.itskenny0.r1ha.wear.feature.mediaplayer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.MediaTransport
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

data class WearMediaPlayerUiState(
    val entity: EntityState? = null,
    val sourceList: List<String> = emptyList(),
    val currentSource: String? = null,
    val loading: Boolean = true,
)

class WearMediaPlayerViewModel(
    private val haRepository: HaRepository,
    private val entityId: EntityId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearMediaPlayerUiState())
    val uiState: StateFlow<WearMediaPlayerUiState> = _uiState

    init { load() }

    private fun load() {
        viewModelScope.launch {
            haRepository.listAllEntities().onSuccess { all ->
                val entity = all.find { it.id == entityId } ?: return@onSuccess
                _uiState.value = WearMediaPlayerUiState(
                    entity = entity,
                    sourceList = parseSourceList(entity),
                    currentSource = parseCurrentSource(entity),
                    loading = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false)
            }
        }
    }

    private fun parseSourceList(entity: EntityState): List<String> {
        val arr = entity.attributesJson?.get("source_list") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun parseCurrentSource(entity: EntityState): String? {
        val el = entity.attributesJson?.get("source") as? JsonPrimitive ?: return null
        return el.contentOrNull
    }

    private fun refresh() {
        viewModelScope.launch {
            delay(800)
            haRepository.listAllEntities().onSuccess { all ->
                val entity = all.find { it.id == entityId } ?: return@onSuccess
                _uiState.value = _uiState.value.copy(
                    entity = entity,
                    sourceList = parseSourceList(entity),
                    currentSource = parseCurrentSource(entity),
                )
            }
        }
    }

    fun onTransport(action: MediaTransport) {
        val entity = _uiState.value.entity ?: return
        viewModelScope.launch {
            haRepository.call(ServiceCall.mediaTransport(entityId, action, entity.isVolumeMuted))
            refresh()
        }
    }

    fun onPowerToggle() {
        val entity = _uiState.value.entity ?: return
        // Optimistic flip
        _uiState.value = _uiState.value.copy(entity = entity.copy(isOn = !entity.isOn))
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entityId, entity.isOn))
            refresh()
        }
    }

    fun onSelectSource(source: String) {
        _uiState.value = _uiState.value.copy(currentSource = source)
        viewModelScope.launch {
            haRepository.call(ServiceCall.selectMediaSource(entityId, source))
            refresh()
        }
    }

    companion object {
        fun factory(haRepository: HaRepository, entityId: EntityId) = viewModelFactory {
            initializer { WearMediaPlayerViewModel(haRepository, entityId) }
        }
    }
}
