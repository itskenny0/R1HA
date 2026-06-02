package com.github.itskenny0.r1ha.feature.zones

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.RawEntityRow
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the Zones surface — HA's zone registry presented as a list
 * of "where is everyone right now". Each row:
 *   - zone name + icon + a count of persons currently inside
 *   - the list of persons/devices in that zone
 *   - the zone's radius (for orientation)
 *
 * Powered by `/api/states` for both `zone.*` and `person.*` /
 * `device_tracker.*`. Decoding the raw rows is the view-model's job;
 * the actual membership matching and map projection live in the pure
 * helpers in ZonePresence.kt so they can be unit-tested without
 * Android/Compose.
 */
class ZonesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val zones: List<ResolvedZone> = emptyList(),
        /** Trackers reporting GPS, for the abstract map. */
        val trackers: List<MappableTracker> = emptyList(),
        /** Persons whose state is "not_home" (or any other away/unknown
         *  bucket) — surfaced under an OUTSIDE bucket so they're not
         *  invisible just because no zone matches. */
        val outside: List<String> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // Three parallel fetches — zones AND person/device_tracker.
            // /api/states returns the full registry per domain.
            val zoneJob = async { haRepository.listRawEntitiesByDomain("zone") }
            val personJob = async { haRepository.listRawEntitiesByDomain("person") }
            val trackerJob = async { haRepository.listRawEntitiesByDomain("device_tracker") }
            awaitAll(zoneJob, personJob, trackerJob)
            val zoneRes = zoneJob.await()
            val personRes = personJob.await()
            val trackerRes = trackerJob.await()
            if (zoneRes.isFailure) {
                val t = zoneRes.exceptionOrNull()
                R1Log.w("Zones", "zone load failed: ${t?.message}")
                Toaster.error("Zones load failed: ${t?.message ?: "unknown"}")
                _ui.value = _ui.value.copy(loading = false, error = t?.message)
                return@launch
            }
            val zoneRows = zoneRes.getOrNull().orEmpty()
            val peopleRows = personRes.getOrNull().orEmpty()
            val trackerRows = trackerRes.getOrNull().orEmpty()

            // Decode raw rows into the pure helper's plain inputs, then let
            // ZonePresence resolve membership + select mappable trackers.
            val zoneInputs = zoneRows.map { it.toZoneInput() }
            val trackerInputs = (peopleRows + trackerRows).map { it.toTrackedInput() }
            val resolution = resolveZoneMembership(zoneInputs, trackerInputs)
            val homeZoneName = zoneInputs.firstOrNull { it.isHome }?.name
            val trackers = mappableTrackers(trackerInputs, homeZoneName)

            R1Log.i(
                "Zones",
                "zones=${resolution.zones.size} outside=${resolution.outside.size} mapped=${trackers.size}",
            )
            _ui.value = _ui.value.copy(
                loading = false,
                zones = resolution.zones,
                trackers = trackers,
                outside = resolution.outside,
                error = null,
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ZonesViewModel(haRepository) }
        }
    }
}

private fun RawEntityRow.attrDouble(key: String): Double? =
    (attributes[key] as? JsonPrimitive)?.content?.toDoubleOrNull()

private fun RawEntityRow.attrString(key: String): String? =
    (attributes[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

private fun RawEntityRow.attrBool(key: String): Boolean =
    (attributes[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() == true

private fun RawEntityRow.toZoneInput(): ZoneInput = ZoneInput(
    entityId = entityId,
    name = friendlyName,
    latitude = attrDouble("latitude"),
    longitude = attrDouble("longitude"),
    radiusMeters = attrDouble("radius"),
    icon = attrString("icon"),
    // HA core does not put an `is_home` attribute on zone entities; the home
    // zone is canonically the entity `zone.home`. Keep the attribute as a
    // best-effort fallback for any custom integration that does set it.
    isHome = entityId == "zone.home" || attrBool("is_home"),
    passive = attrBool("passive"),
)

private fun RawEntityRow.toTrackedInput(): TrackedInput = TrackedInput(
    entityId = entityId,
    name = friendlyName,
    state = state,
    latitude = attrDouble("latitude"),
    longitude = attrDouble("longitude"),
)
