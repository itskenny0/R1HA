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
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
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
     * Subscribe to `system_health/info` over the shared websocket and parse the
     * reply into grouped sections. Matches frames by request id off the raw
     * inbound text stream, so it never depends on the repository having a typed
     * accessor for this command.
     *
     * `system_health/info` is a *subscription*, not a one-shot request: HA first
     * replies with `success:true` (no payload), then pushes `event` frames:
     *   - `{"type":"initial","data":{...full per-domain map...}}`
     *   - zero or more `{"type":"update","success":bool,"domain":..,"key":..,"data"/"error":..}`
     *     as each integration's async reachability check resolves
     *   - `{"type":"finish"}` once every check has settled
     * The old code waited for a single `type:"result"` payload, which a subscribe
     * never carries, so the section list always timed out empty. We now accumulate
     * the `initial` snapshot, fold each `update` into it, and return once `finish`
     * arrives (or the timeout fires, in which case we return whatever resolved so
     * far rather than nothing). The subscription is unsubscribed before returning.
     */
    private suspend fun fetchSystemHealthInfo(): Result<List<HealthSection>> {
        val client = ws ?: return Result.failure(IllegalStateException("No websocket"))
        if (client.state.value !is ConnectionState.Connected) {
            return Result.failure(IllegalStateException("Not connected to Home Assistant"))
        }
        val id = client.nextRequestId()
        // Latest accumulated per-domain map; null until the `initial` frame lands.
        val data = MutableStateFlow<JsonObject?>(null)
        // Completes when the stream finishes (Unit) or the subscribe is rejected
        // (failure). First completion wins; later events are ignored.
        val done = CompletableDeferred<Result<Unit>>()
        val collector = viewModelScope.launch {
            client.inboundRawText.collect { raw ->
                val obj = runCatching { Json.parseToJsonElement(raw) as? JsonObject }
                    .getOrNull() ?: return@collect
                val frameId = (obj["id"] as? JsonPrimitive)?.content?.toIntOrNull()
                if (frameId != id) return@collect
                when ((obj["type"] as? JsonPrimitive)?.content) {
                    "result" -> {
                        // The subscribe ack. Only a failure here is interesting; a
                        // success means "stream incoming", so we keep collecting.
                        if ((obj["success"] as? JsonPrimitive)?.booleanOrNull == false) {
                            val err = (obj["error"] as? JsonObject)
                                ?.let { (it["message"] as? JsonPrimitive)?.content }
                                ?: "system_health/info failed"
                            done.complete(Result.failure(IllegalStateException(err)))
                        }
                    }
                    "event" -> {
                        val event = obj["event"] as? JsonObject ?: return@collect
                        when ((event["type"] as? JsonPrimitive)?.content) {
                            "initial" -> data.value = event["data"] as? JsonObject ?: JsonObject(emptyMap())
                            "update" -> data.value = applyUpdate(data.value, event)
                            "finish" -> done.complete(Result.success(Unit))
                        }
                    }
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
        // Wait for the stream to finish, an explicit subscribe failure, or the
        // timeout. null => timed out.
        val outcome = withTimeoutOrNull(15_000) { done.await() }
        // Best-effort unsubscribe so HA stops streaming to a request id we've
        // abandoned. Fire-and-forget: the socket may already be gone.
        client.sendRawText(
            buildJsonObject {
                put("id", JsonPrimitive(client.nextRequestId()))
                put("type", JsonPrimitive("unsubscribe_events"))
                put("subscription", JsonPrimitive(id))
            }.toString(),
        )
        collector.cancel()
        val snapshot = data.value
        return when {
            outcome != null && outcome.isFailure ->
                Result.failure(outcome.exceptionOrNull() ?: IllegalStateException("Unknown error"))
            // Timed out but we already have a snapshot: return what resolved rather
            // than blanking the panel (a slow cloud check shouldn't lose the rest).
            snapshot != null -> Result.success(SystemHealthInfo.parse(snapshot))
            else ->
                Result.failure(IllegalStateException("system_health/info timed out after 15s"))
        }
    }

    /**
     * Fold one `update` event into the accumulated per-domain map, mirroring the
     * frontend's reducer: a successful update writes `data` at `[domain].info[key]`,
     * a failed one writes `{error:true, value:msg}` (which the parser renders red).
     */
    private fun applyUpdate(current: JsonObject?, event: JsonObject): JsonObject {
        val base = current ?: JsonObject(emptyMap())
        val domain = (event["domain"] as? JsonPrimitive)?.content ?: return base
        val key = (event["key"] as? JsonPrimitive)?.content ?: return base
        val success = (event["success"] as? JsonPrimitive)?.booleanOrNull ?: true
        val newValue: JsonElement = if (success) {
            event["data"] ?: JsonNull
        } else {
            val msg = (event["error"] as? JsonObject)
                ?.let { (it["msg"] as? JsonPrimitive)?.content }
            buildJsonObject {
                put("error", JsonPrimitive(true))
                put("value", JsonPrimitive(msg ?: "unknown"))
            }
        }
        val domainObj = base[domain] as? JsonObject ?: JsonObject(emptyMap())
        val info = domainObj["info"] as? JsonObject ?: JsonObject(emptyMap())
        val newInfo = JsonObject(info.toMutableMap().apply { this[key] = newValue })
        val newDomain = JsonObject(domainObj.toMutableMap().apply { this["info"] = newInfo })
        return JsonObject(base.toMutableMap().apply { this[domain] = newDomain })
    }

    companion object {
        fun factory(haRepository: HaRepository, ws: HaWebSocketClient?) = viewModelFactory {
            initializer { SystemHealthViewModel(haRepository, ws) }
        }
    }
}
