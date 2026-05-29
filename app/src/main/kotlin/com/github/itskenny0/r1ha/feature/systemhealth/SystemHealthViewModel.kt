package com.github.itskenny0.r1ha.feature.systemhealth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaConfig
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaWebSocketClient
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Drives the System Health screen. Layers HA's `system_health/info` websocket
 * report (grouped per integration domain) on top of the existing `/api/config`
 * + `/api/error_log` diagnostics. Each source fails independently and is
 * surfaced on its own; one outage doesn't blank the others.
 *
 * `system_health/info` has no dedicated [HaRepository] method, so we issue it
 * directly over the shared [HaWebSocketClient] using its public send/await
 * primitives. That keeps the feature self-contained without changing any
 * core/ha signatures.
 */
class SystemHealthViewModel(
    private val haRepository: HaRepository,
    private val ws: HaWebSocketClient?,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val config: HaConfig? = null,
        val configError: String? = null,
        val errorLog: String = "",
        val errorLogError: String? = null,
        /** Parsed, grouped `system_health/info` sections (empty until loaded). */
        val healthSections: List<HealthSection> = emptyList(),
        /** Non-null when the `system_health/info` fetch itself failed. */
        val healthError: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(
                loading = true,
                configError = null,
                errorLogError = null,
                healthError = null,
            )
            // Sequential is fine: /api/config returns in <50ms and the error
            // log is the slower leg; all share the same HTTP/WS client so
            // parallelism wouldn't buy much.
            val configResult = haRepository.fetchHaConfig()
            val errorLogResult = haRepository.fetchErrorLog()
            val healthResult = fetchSystemHealthInfo()
            R1Log.i(
                "SystemHealth",
                "config=${configResult.isSuccess} errorLog=${errorLogResult.isSuccess} " +
                    "health=${healthResult.isSuccess}",
            )
            if (configResult.isFailure) {
                val msg = configResult.exceptionOrNull()?.message ?: "Config fetch failed"
                Toaster.error("Config: $msg")
            }
            _ui.value = _ui.value.copy(
                loading = false,
                config = configResult.getOrNull(),
                configError = configResult.exceptionOrNull()?.message,
                errorLog = errorLogResult.getOrNull().orEmpty(),
                errorLogError = errorLogResult.exceptionOrNull()?.message,
                healthSections = healthResult.getOrNull().orEmpty(),
                healthError = healthResult.exceptionOrNull()?.message,
            )
        }
    }

    /**
     * Issue `system_health/info` over the shared websocket and parse the reply
     * into grouped sections. Matches the response by request id off the raw
     * inbound text stream, so it never depends on the repository having a typed
     * accessor for this command.
     */
    private suspend fun fetchSystemHealthInfo(): Result<List<HealthSection>> {
        val client = ws ?: return Result.failure(IllegalStateException("No websocket"))
        if (client.state.value !is ConnectionState.Connected) {
            return Result.failure(IllegalStateException("Not connected to Home Assistant"))
        }
        val id = client.nextRequestId()
        val deferred = CompletableDeferred<Result<JsonElement?>>()
        val collector = viewModelScope.launch {
            client.inboundRawText.collect { raw ->
                val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
                    .getOrNull() ?: return@collect
                val frameId = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (frameId != id) return@collect
                if ((obj["type"] as? JsonPrimitive)?.content != "result") return@collect
                val success = (obj["success"] as? JsonPrimitive)?.content == "true"
                if (success) {
                    deferred.complete(Result.success(obj["result"]))
                } else {
                    val err = (obj["error"] as? JsonObject)
                        ?.let { (it["message"] as? JsonPrimitive)?.content }
                        ?: "system_health/info failed"
                    deferred.complete(Result.failure(IllegalStateException(err)))
                }
            }
        }
        val frame = buildJsonObject {
            put("id", JsonPrimitive(id))
            put("type", JsonPrimitive("system_health/info"))
        }
        if (!client.sendRawText(frame.toString())) {
            collector.cancel()
            return Result.failure(IllegalStateException("Websocket send refused"))
        }
        val payload = withTimeoutOrNull(15_000) { deferred.await() }
        collector.cancel()
        return when {
            payload == null ->
                Result.failure(IllegalStateException("system_health/info timed out after 15s"))
            payload.isFailure ->
                Result.failure(payload.exceptionOrNull() ?: IllegalStateException("Unknown error"))
            else -> Result.success(SystemHealthInfo.parse(payload.getOrNull()))
        }
    }

    companion object {
        fun factory(haRepository: HaRepository, ws: HaWebSocketClient?) = viewModelFactory {
            initializer { SystemHealthViewModel(haRepository, ws) }
        }
    }
}
