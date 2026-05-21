package com.github.itskenny0.r1ha.wear.feature.climate

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

data class WearClimateUiState(
    val entity: EntityState? = null,
    /** All modes the entity supports (from `hvac_modes` attribute). */
    val hvacModes: List<String> = emptyList(),
    /** Current ambient temperature (from `current_temperature` attribute). */
    val currentTemp: Double? = null,
    /** Editable target temperature (mirrors entity.raw until the user adjusts). */
    val targetTemp: Double? = null,
    val loading: Boolean = true,
)

class WearClimateViewModel(
    private val haRepository: HaRepository,
    private val entityId: EntityId,
) : ViewModel() {

    private val _uiState = MutableStateFlow(WearClimateUiState())
    val uiState: StateFlow<WearClimateUiState> = _uiState

    init { load() }

    private fun load() {
        viewModelScope.launch {
            haRepository.listAllEntities().onSuccess { all ->
                val entity = all.find { it.id == entityId } ?: return@onSuccess
                _uiState.value = WearClimateUiState(
                    entity = entity,
                    hvacModes = parseHvacModes(entity),
                    currentTemp = parseCurrentTemp(entity),
                    targetTemp = entity.raw?.toDouble(),
                    loading = false,
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(loading = false)
            }
        }
    }

    private fun parseHvacModes(entity: EntityState): List<String> {
        val arr = entity.attributesJson?.get("hvac_modes") as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }

    private fun parseCurrentTemp(entity: EntityState): Double? {
        val el = entity.attributesJson?.get("current_temperature") as? JsonPrimitive ?: return null
        return el.doubleOrNull
    }

    private fun refresh() {
        viewModelScope.launch {
            delay(800)
            haRepository.listAllEntities().onSuccess { all ->
                val entity = all.find { it.id == entityId } ?: return@onSuccess
                _uiState.value = _uiState.value.copy(
                    entity = entity,
                    hvacModes = parseHvacModes(entity),
                    currentTemp = parseCurrentTemp(entity),
                    // Keep targetTemp if user is mid-adjustment, otherwise take fresh value
                    targetTemp = _uiState.value.targetTemp ?: entity.raw?.toDouble(),
                )
            }
        }
    }

    fun onPowerToggle() {
        val entity = _uiState.value.entity ?: return
        val optimistic = entity.copy(isOn = !entity.isOn)
        _uiState.value = _uiState.value.copy(entity = optimistic)
        viewModelScope.launch {
            haRepository.call(ServiceCall.tapAction(entityId, entity.isOn))
            refresh()
        }
    }

    fun onSetHvacMode(mode: String) {
        val entity = _uiState.value.entity ?: return
        // Optimistic: update rawState to new mode; isOn = mode != "off"
        _uiState.value = _uiState.value.copy(
            entity = entity.copy(rawState = mode, isOn = mode != "off"),
        )
        viewModelScope.launch {
            haRepository.call(ServiceCall.setHvacMode(entityId, mode))
            refresh()
        }
    }

    fun onAdjustTemp(delta: Double) {
        val current = _uiState.value.targetTemp ?: return
        val entity = _uiState.value.entity ?: return
        val min = entity.minRaw ?: 50.0
        val max = entity.maxRaw ?: 95.0
        // Step: prefer 1°F increments for Fahrenheit (unit contains "F"), else 0.5°C
        val step = if (entity.unit?.contains("F", ignoreCase = true) == true) 1.0 else 0.5
        val newTemp = (current + delta * step).coerceIn(min, max)
        val rounded = Math.round(newTemp / step) * step
        _uiState.value = _uiState.value.copy(targetTemp = rounded)
        viewModelScope.launch {
            haRepository.call(ServiceCall.setTemperature(entityId, rounded))
            refresh()
        }
    }

    companion object {
        fun factory(haRepository: HaRepository, entityId: EntityId) = viewModelFactory {
            initializer { WearClimateViewModel(haRepository, entityId) }
        }
    }
}
