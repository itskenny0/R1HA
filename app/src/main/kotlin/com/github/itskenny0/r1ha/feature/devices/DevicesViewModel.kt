package com.github.itskenny0.r1ha.feature.devices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.Job
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
        /** Id of the drilled-in device, or null when the list is showing. */
        val openedDeviceId: String? = null,
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

        /** The currently drilled-in device, or null when the list is showing. */
        val openedDevice: DeviceInfo?
            get() = openedDeviceId?.let { id -> devices.firstOrNull { it.id == id } }
    }

    /**
     * Detail-pane state for a drilled-in device: the device itself, its
     * entities bucketed by domain (controls first), the live state map for
     * the entities HA is actually reporting, and the parent (via_device)
     * resolved to a device when set. [liveStates] is keyed by the raw
     * entity-id string so the view can look up state for any entity,
     * including domains the card stack doesn't model.
     */
    @androidx.compose.runtime.Stable
    data class DetailState(
        val device: DeviceInfo,
        val areaName: String?,
        val parent: DeviceInfo?,
        val groups: List<DeviceEntityGroup>,
        val liveStates: Map<String, EntityState> = emptyMap(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    private val _detail = MutableStateFlow<DetailState?>(null)
    val detail: StateFlow<DetailState?> = _detail

    /** Live-state subscription job for the open device; cancelled on close. */
    private var liveJob: Job? = null

    /** Drill into [deviceId]: resolve metadata, group entities, and start a
     *  live-state subscription for the entities whose domain the client
     *  models (others render registry-only, no live state). */
    fun openDevice(deviceId: String) {
        val snapshot = _ui.value
        val device = snapshot.devices.firstOrNull { it.id == deviceId } ?: return
        _ui.value = snapshot.copy(openedDeviceId = deviceId)
        val deviceEntities = entitiesForDevice(snapshot.entities, deviceId)
        val groups = groupEntitiesByDomain(deviceEntities)
        val parent = device.viaDeviceId?.let { p -> snapshot.devices.firstOrNull { it.id == p } }
        _detail.value = DetailState(
            device = device,
            areaName = device.areaId?.let { snapshot.areaName[it] },
            parent = parent,
            groups = groups,
        )
        // Only construct EntityId for domains the value class accepts; its
        // init{} throws on unmodelled domains. Unmodelled entities simply
        // carry no live state and the view labels them "no live state".
        val observable = deviceEntities.mapNotNull { entry ->
            val prefix = domainOfEntityId(entry.entityId)
            if (Domain.isSupportedPrefix(prefix)) {
                runCatching { EntityId(entry.entityId) }.getOrNull()
            } else {
                null
            }
        }.toSet()
        liveJob?.cancel()
        liveJob = if (observable.isEmpty()) {
            null
        } else {
            viewModelScope.launch {
                haRepository.observe(observable).collect { map ->
                    val byString = map.mapKeys { it.key.value }
                    _detail.value = _detail.value?.copy(liveStates = byString)
                }
            }
        }
    }

    /** Close the drill-in and stop observing its entities. */
    fun closeDevice() {
        liveJob?.cancel()
        liveJob = null
        _detail.value = null
        if (_ui.value.openedDeviceId != null) {
            _ui.value = _ui.value.copy(openedDeviceId = null)
        }
    }

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
