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
        /** HA `source` attribute on device_tracker: "router" / "gps" /
         *  "bluetooth_le". Surfaced as a small chip so the user can tell
         *  a phone tracker from a router-based one. Null for person.*. */
        val source: String?,
        /** GPS accuracy in metres; null when not GPS-based. */
        val gpsAccuracy: Int?,
        /** When HA last reported this person/device's state. Used for
         *  the "since X" relative timestamp on each row. */
        val since: java.time.Instant?,
        /** Battery percent from HA's `battery_level` attribute: common
         *  on device_trackers backed by a phone integration. Null when
         *  not reported. */
        val batteryLevel: Int?,
        /** HA `entity_picture` attribute: a relative `/api/...` path or an
         *  absolute URL pointing at the person's avatar. Rendered via
         *  AsyncBitmap with an initials fallback. Null when not set. */
        val entityPicture: String?,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        /** True only during the first load before any data lands; drives the
         *  full-screen spinner. */
        val initialLoading: Boolean = true,
        /** True while a refresh runs once data already exists; drives the
         *  pull-to-refresh spinner so auto-refresh ticks don't blip the list
         *  back to the centre spinner. */
        val refreshing: Boolean = false,
        val people: List<Entry> = emptyList(),
        val devices: List<Entry> = emptyList(),
        val error: String? = null,
    ) {
        /** Back-compat alias: the full-screen spinner condition. */
        val loading: Boolean get() = initialLoading
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            val hasData = _ui.value.people.isNotEmpty() || _ui.value.devices.isNotEmpty()
            _ui.value = _ui.value.copy(
                initialLoading = !hasData,
                refreshing = hasData,
                error = null,
            )
            // Two parallel fetches would be nice but listRawEntitiesByDomain
            // hits the same /api/states; one batched request would be ideal
            // long-term. For now sequential is fine: the response is
            // already cached in HA's RAM.
            val personResult = haRepository.listRawEntitiesByDomain("person")
            val deviceResult = haRepository.listRawEntitiesByDomain("device_tracker")
            // Partial-failure tolerance: render whichever fetch succeeded
            // rather than blanking the whole screen. Only when BOTH fail do we
            // surface the hard error state; a single failed domain becomes a
            // toast and we keep the other domain's rows. This avoids a flaky
            // device_tracker integration hiding the perfectly-good person list.
            if (personResult.isFailure && deviceResult.isFailure) {
                val t = personResult.exceptionOrNull() ?: deviceResult.exceptionOrNull()
                R1Log.w("Persons", "both lists failed: ${t?.message}")
                Toaster.error("Persons load failed: ${t?.message ?: "unknown"}")
                _ui.value = _ui.value.copy(
                    initialLoading = false,
                    refreshing = false,
                    error = t?.message,
                )
                return@launch
            }
            if (personResult.isFailure) {
                R1Log.w("Persons", "person list failed: ${personResult.exceptionOrNull()?.message}")
                Toaster.error("People failed to load; showing device trackers only")
            }
            if (deviceResult.isFailure) {
                R1Log.w("Persons", "device list failed: ${deviceResult.exceptionOrNull()?.message}")
                Toaster.error("Device trackers failed to load; showing people only")
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
            // On a partial failure keep the last-known rows for the failed
            // domain rather than wiping them, so a transient blip doesn't empty
            // a section the user was just looking at.
            _ui.value = _ui.value.copy(
                initialLoading = false,
                refreshing = false,
                people = if (personResult.isFailure) _ui.value.people else people,
                devices = if (deviceResult.isFailure) _ui.value.devices else devices,
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
