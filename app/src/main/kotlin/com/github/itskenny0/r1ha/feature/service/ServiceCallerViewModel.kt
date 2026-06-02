package com.github.itskenny0.r1ha.feature.service

import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Drives the Service Caller power-user surface. Lets the user type
 * any `domain.service` pair (e.g. `automation.reload`,
 * `homeassistant.restart`, `notify.mobile_app_pixel`) plus an
 * optional JSON `data` body and dispatches it via HA's REST
 * `/api/services` path.
 *
 * Why REST rather than the WebSocket call_service path the rest of
 * the app uses: WS call_service requires an EntityId target; many of
 * the most useful diagnostic services (restart, reload, set persistent
 * notification, …) don't have one. The REST endpoint accepts naked
 * service calls with optional `entity_id` in the body, which is the
 * shape the user actually wants for power-tool dispatch.
 */
class ServiceCallerViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    @Volatile
    private var historyDepth: Int = 5

    @Stable
    data class RecentCall(
        val domain: String,
        val service: String,
        val data: String,
    )

    @Stable
    data class UiState(
        val domain: String = "homeassistant",
        val service: String = "check_config",
        val data: String = "",
        val inFlight: Boolean = false,
        val result: String = "",
        val error: String? = null,
        /** Last 5 successfully-fired calls, newest first. Lives in
         *  ViewModel state only; not persisted across app restarts.
         *  That's intentional: this is "what did I just try?", not
         *  "what did I do last week" (the latter would want a real
         *  history surface). */
        val recent: List<RecentCall> = emptyList(),
    ) {
        /** True when [domain] is empty or a legal HA domain token. The repo
         *  enforces `[a-z0-9_]+` and throws otherwise; mirroring the check
         *  here lets the field paint red and the FIRE button disable BEFORE
         *  a doomed round-trip, instead of surfacing the failure as an error
         *  panel after the fact. Empty is treated as "not yet invalid" so the
         *  field doesn't glow red before the user has typed. */
        val domainValid: Boolean get() = domain.isBlank() || domain.trim().matches(TOKEN)

        /** True when [service] is empty or a legal HA service token. */
        val serviceValid: Boolean get() = service.isBlank() || service.trim().matches(TOKEN)

        /** True when [data] is blank or parses as a JSON object. Surfaced as
         *  an inline hint so malformed JSON is caught while typing rather
         *  than only on FIRE. */
        val dataValid: Boolean get() = data.isBlank() || runCatching {
            Json.parseToJsonElement(data.trim()) is JsonObject
        }.getOrDefault(false)

        /** All fields present and well-formed, ready to dispatch. */
        val canFire: Boolean get() = domain.isNotBlank() && service.isNotBlank() &&
            domainValid && serviceValid && dataValid
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setDomain(value: String) { _ui.value = _ui.value.copy(domain = value) }
    fun setService(value: String) { _ui.value = _ui.value.copy(service = value) }
    fun setData(value: String) { _ui.value = _ui.value.copy(data = value) }
    fun clearRecent() { _ui.value = _ui.value.copy(recent = emptyList()) }

    /**
     * Job for the current in-flight service call. Stored so [cancel] can abort it
     * when the user taps CANCEL during a slow call; HA's REST service endpoint can
     * sit on a long-running automation script for many seconds and there was no
     * way to back out until now.
     */
    private var fireJob: Job? = null

    fun cancel() {
        fireJob?.cancel()
        fireJob = null
        _ui.value = _ui.value.copy(inFlight = false, error = "Cancelled")
    }

    fun fire() {
        val s = _ui.value
        if (s.inFlight) return
        if (s.domain.isBlank() || s.service.isBlank()) {
            _ui.value = s.copy(error = "Domain + service required")
            return
        }
        // Validate the domain/service tokens up front. The repo throws on
        // anything outside [a-z0-9_]+; catching it here gives a clear,
        // field-specific message instead of leaking the raw require() text.
        if (!s.domainValid || !s.serviceValid) {
            _ui.value = s.copy(
                error = "Domain and service must be lowercase letters, digits, or underscores " +
                    "(e.g. light.turn_on). Drop the dot and any spaces.",
            )
            return
        }
        // Parse the data field as a JsonObject if non-blank; empty = {}.
        val payload = if (s.data.isBlank()) {
            JsonObject(emptyMap())
        } else {
            runCatching {
                Json.parseToJsonElement(s.data.trim())
                    as? JsonObject
                    ?: error("Data must be a JSON object")
            }.getOrElse { t ->
                _ui.value = s.copy(error = "Bad JSON data: ${t.message}")
                return
            }
        }
        _ui.value = s.copy(inFlight = true, error = null, result = "")
        fireJob?.cancel()
        fireJob = viewModelScope.launch {
            // Snapshot history depth from settings so each fire honours
            // the user's current Settings → INTEGRATIONS preference.
            historyDepth = settings.settings.first().integrations.recentHistoryDepth
                .coerceIn(0, 100)
            haRepository.callRawService(s.domain.trim(), s.service.trim(), payload).fold(
                onSuccess = { result ->
                    R1Log.i("ServiceCaller", "${s.domain}.${s.service} OK len=${result.length}")
                    // Push to recent history (dedupe + cap). Newest first.
                    val justFired = RecentCall(s.domain.trim(), s.service.trim(), s.data)
                    val newRecent = (listOf(justFired) + _ui.value.recent.filterNot { it == justFired })
                        .take(historyDepth)
                    _ui.value = _ui.value.copy(
                        result = if (result.isBlank()) {
                            "[] (no state changes)"
                        } else {
                            // Pretty-print the JSON response for readability;
                            // HA's /api/services returns an array of state
                            // dicts that's hard to scan single-line. Falls
                            // back to the raw response if parsing fails (HA
                            // could return non-JSON for some service edge
                            // cases).
                            runCatching {
                                val parsed = Json.parseToJsonElement(result)
                                prettyJson.encodeToString(
                                    JsonElement.serializer(),
                                    parsed,
                                )
                            }.getOrDefault(result)
                        },
                        error = null,
                        inFlight = false,
                        recent = newRecent,
                    )
                },
                onFailure = { t ->
                    R1Log.w("ServiceCaller", "${s.domain}.${s.service} failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        error = t.message ?: "Service call failed",
                        inFlight = false,
                    )
                },
            )
        }
    }

    /** Seed the editor with a [domain]/[service]/[data] preset: used by
     *  the example chips on the screen so the user can try common calls
     *  with one tap. */
    fun load(domain: String, service: String, data: String) {
        _ui.value = _ui.value.copy(
            domain = domain,
            service = service,
            data = data,
            error = null,
            result = "",
        )
    }

    companion object {
        /** Legal HA domain / service token: lowercase letters, digits,
         *  underscores. Mirrors the guard in
         *  [com.github.itskenny0.r1ha.core.ha.HaRepository.callRawService]. */
        private val TOKEN = Regex("[a-z0-9_]+")

        /** Shared Json instance for pretty-printing service-call results.
         *  prettyPrint = true is enough; default 4-space indent reads
         *  fine on the R1's tiny screen. Lives in the companion so the
         *  formatter isn't rebuilt on every fire, also shared with
         *  the screen-side PASTE chip for the same reason. */
        internal val prettyJson = Json { prettyPrint = true }

        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { ServiceCallerViewModel(haRepository, settings) }
        }
    }
}
