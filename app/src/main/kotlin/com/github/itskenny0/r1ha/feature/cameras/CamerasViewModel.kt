package com.github.itskenny0.r1ha.feature.cameras

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
import kotlinx.serialization.json.booleanOrNull

/**
 * Drives the Cameras surface. Pulls every `camera.*` entity HA reports
 * via [HaRepository.listRawEntitiesByDomain], extracts the friendly
 * name + state ("idle" / "recording" / "streaming"), and exposes the
 * list to [CamerasScreen] for tap-to-view.
 *
 * The actual snapshot polling lives in
 * [com.github.itskenny0.r1ha.ui.components.CameraSnapshot]; this VM
 * just holds the directory.
 */
class CamerasViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Camera(
        val entityId: String,
        val name: String,
        /** HA-reported state: usually "idle" / "recording" / "streaming" /
         *  "unavailable". Surfaced as a small chip on each row. */
        val state: String,
        /** `attributes.motion_detection` from HA: whether the camera's
         *  motion detection is currently armed. Null when the integration
         *  doesn't report it (most cloud cameras omit it). Surfaced as a
         *  "MOTION" badge so the directory matches what HA's picture-glance
         *  card exposes for the domain. */
        val motionDetection: Boolean? = null,
        /** `last_changed` from HA: when the state last flipped. Drives a
         *  relative "since X" label so a stuck/offline camera reads as
         *  stale at a glance. Null when HA omitted or it was unparseable. */
        val lastChanged: java.time.Instant? = null,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val cameras: List<Camera> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRawEntitiesByDomain("camera").fold(
                onSuccess = { rows ->
                    val list = rows.map { row ->
                        // motion_detection is a JSON bool when present; HA omits it
                        // for integrations that don't model motion arming, so null
                        // means "unknown", not "disabled".
                        val motion = (row.attributes["motion_detection"]
                            as? kotlinx.serialization.json.JsonPrimitive)
                            ?.booleanOrNull
                        Camera(
                            entityId = row.entityId,
                            name = row.friendlyName,
                            state = row.state,
                            motionDetection = motion,
                            lastChanged = row.lastChanged,
                        )
                    }.sortedBy { it.name.lowercase() }
                    R1Log.i("Cameras", "loaded ${list.size}")
                    _ui.value = _ui.value.copy(loading = false, cameras = list, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Cameras", "list failed: ${t.message}")
                    Toaster.error("Cameras load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { CamerasViewModel(haRepository) }
        }
    }
}
