package com.github.itskenny0.r1ha.feature.persons

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the "Who's home" surface. Aggregates `person.*` AND
 * `device_tracker.*` entities into a single home/away directory.
 * People are listed first because they're the higher-fidelity view;
 * raw device_trackers (per phone, per network ping) go below as a
 * secondary group.
 */
class PersonsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Kind { PERSON, DEVICE }

    @androidx.compose.runtime.Stable
    data class Entry(
        val entityId: String,
        val name: String,
        val state: String,
        val kind: Kind,
        /** HA `source` attribute on device_tracker — "router" / "gps" /
         *  "bluetooth_le". Surfaced as a small chip so the user can tell
         *  a phone tracker from a router-based one. Null for person.*. */
        val source: String?,
        /** GPS accuracy in metres — null when not GPS-based. */
        val gpsAccuracy: Int?,
        /** When HA last reported this person/device's state. Used for
         *  the "since X" relative timestamp on each row. */
        val since: java.time.Instant?,
        /** Battery percent from HA's `battery_level` attribute — common
         *  on device_trackers backed by a phone integration. Null when
         *  not reported. */
        val batteryLevel: Int?,
        /** HA `entity_picture` attribute — a relative `/api/...` path or an
         *  absolute URL pointing at the person's avatar. Rendered via
         *  AsyncBitmap with an initials fallback. Null when not set. */
        val entityPicture: String?,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val people: List<Entry> = emptyList(),
        val devices: List<Entry> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Two parallel fetches would be nice but listRawEntitiesByDomain
            // hits the same /api/states; one batched request would be ideal
            // long-term. For now sequential is fine — the response is
            // already cached in HA's RAM.
            val personResult = haRepository.listRawEntitiesByDomain("person")
            val deviceResult = haRepository.listRawEntitiesByDomain("device_tracker")
            val combined = listOf(personResult, deviceResult).firstOrNull { it.isFailure }
            if (combined != null) {
                val t = combined.exceptionOrNull()
                R1Log.w("Persons", "list failed: ${t?.message}")
                Toaster.error("Persons load failed: ${t?.message ?: "unknown"}")
                _ui.value = _ui.value.copy(loading = false, error = t?.message)
                return@launch
            }
            val people = personResult.getOrNull().orEmpty().map { row ->
                Entry(
                    entityId = row.entityId,
                    name = row.friendlyName,
                    state = row.state,
                    kind = Kind.PERSON,
                    source = null,
                    gpsAccuracy = (row.attributes["gps_accuracy"] as? JsonPrimitive)?.content
                        ?.toDoubleOrNull()?.toInt(),
                    since = row.lastChanged,
                    batteryLevel = (row.attributes["battery_level"] as? JsonPrimitive)?.content
                        ?.toDoubleOrNull()?.toInt(),
                    entityPicture = (row.attributes["entity_picture"] as? JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.name.lowercase() }
            val devices = deviceResult.getOrNull().orEmpty().map { row ->
                Entry(
                    entityId = row.entityId,
                    name = row.friendlyName,
                    state = row.state,
                    kind = Kind.DEVICE,
                    source = (row.attributes["source_type"] as? JsonPrimitive)?.content,
                    gpsAccuracy = (row.attributes["gps_accuracy"] as? JsonPrimitive)?.content
                        ?.toDoubleOrNull()?.toInt(),
                    since = row.lastChanged,
                    batteryLevel = (row.attributes["battery_level"] as? JsonPrimitive)?.content
                        ?.toDoubleOrNull()?.toInt(),
                    entityPicture = (row.attributes["entity_picture"] as? JsonPrimitive)?.content
                        ?.takeIf { it.isNotBlank() },
                )
            }.sortedBy { it.name.lowercase() }
            R1Log.i("Persons", "loaded people=${people.size} devices=${devices.size}")
            _ui.value = _ui.value.copy(
                loading = false,
                people = people,
                devices = devices,
                error = null,
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { PersonsViewModel(haRepository) }
        }
    }
}
