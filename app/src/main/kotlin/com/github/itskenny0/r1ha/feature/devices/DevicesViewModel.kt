package com.github.itskenny0.r1ha.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the native Devices browser. Fetches the device registry, the
 * area registry, and the entity registry in parallel (one round trip
 * each, then merge client-side) so drill-in is local and instant.
 *
 * Read-only surface: editing a device's name / area / disabled state
 * lives in HA's web UI because each flow wants its own confirm UX.
 */
class DevicesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Grouping { AREA, MANUFACTURER }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val devices: List<DeviceInfo> = emptyList(),
        val areas: List<AreaInfo> = emptyList(),
        val entities: List<EntityRegistryEntry> = emptyList(),
        val query: String = "",
        val grouping: Grouping = Grouping.AREA,
        val error: String? = null,
    ) {
        val areaName: Map<String, String>
            get() = areas.associate { it.areaId to it.name }

        /** Devices filtered by [query]; matches device name, manufacturer,
         *  model, or area name (all lowercased). Empty query returns all. */
        val filteredDevices: List<DeviceInfo>
            get() {
                if (query.isBlank()) return devices
                val q = query.trim().lowercase()
                val nameForArea = areaName
                return devices.filter { d ->
                    d.displayName.lowercase().contains(q) ||
                        (d.manufacturer?.lowercase()?.contains(q) ?: false) ||
                        (d.model?.lowercase()?.contains(q) ?: false) ||
                        (d.areaId?.let { nameForArea[it]?.lowercase()?.contains(q) } ?: false)
                }
            }

        /** Devices grouped under a section label (area name, manufacturer
         *  name, or "(unassigned)"). Sections sorted alphabetically; the
         *  "(unassigned)" group is forced to the bottom. */
        val sections: List<Pair<String, List<DeviceInfo>>>
            get() {
                val nameForArea = areaName
                val unassignedLabel = when (grouping) {
                    Grouping.AREA -> "(no area)"
                    Grouping.MANUFACTURER -> "(no manufacturer)"
                }
                val grouped = filteredDevices.groupBy { d ->
                    when (grouping) {
                        Grouping.AREA -> d.areaId?.let { nameForArea[it] } ?: unassignedLabel
                        Grouping.MANUFACTURER -> d.manufacturer?.takeIf { it.isNotBlank() }
                            ?: unassignedLabel
                    }
                }
                return grouped.entries
                    .sortedWith(
                        compareBy(
                            { it.key == unassignedLabel },
                            { it.key.lowercase() },
                        ),
                    )
                    .map { (k, v) -> k to v.sortedBy { it.displayName.lowercase() } }
            }

        /** Entities owned by [deviceId], sorted by display name. */
        fun entitiesFor(deviceId: String): List<EntityRegistryEntry> =
            entities.filter { it.deviceId == deviceId }
                .sortedBy { it.displayName.lowercase() }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun setGrouping(g: Grouping) {
        if (_ui.value.grouping == g) return
        _ui.value = _ui.value.copy(grouping = g)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Parallel fan-out; each WS call is independent, blocking on
            // the slowest rather than the sum keeps cold-start snappy.
            val devicesDef = async { haRepository.listDevices() }
            val areasDef = async { haRepository.listAreas() }
            val entitiesDef = async { haRepository.listEntityRegistry() }
            val results = awaitAll(devicesDef, areasDef, entitiesDef)
            @Suppress("UNCHECKED_CAST")
            val devicesRes = results[0] as Result<List<DeviceInfo>>
            @Suppress("UNCHECKED_CAST")
            val areasRes = results[1] as Result<List<AreaInfo>>
            @Suppress("UNCHECKED_CAST")
            val entitiesRes = results[2] as Result<List<EntityRegistryEntry>>

            val devices = devicesRes.getOrNull().orEmpty()
            val areas = areasRes.getOrNull().orEmpty()
            val entities = entitiesRes.getOrNull().orEmpty()
            val firstError = listOf(devicesRes, areasRes, entitiesRes)
                .firstOrNull { it.isFailure }?.exceptionOrNull()
            if (firstError != null && devices.isEmpty()) {
                R1Log.w("Devices", "load failed: ${firstError.message}")
                Toaster.error("Devices load failed: ${firstError.message ?: "unknown"}")
                _ui.value = _ui.value.copy(loading = false, error = firstError.message)
            } else {
                R1Log.i(
                    "Devices",
                    "loaded ${devices.size} device(s), ${areas.size} area(s), ${entities.size} entity row(s)",
                )
                _ui.value = _ui.value.copy(
                    loading = false,
                    devices = devices,
                    areas = areas,
                    entities = entities,
                    error = null,
                )
            }
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { DevicesViewModel(haRepository) }
        }
    }
}
