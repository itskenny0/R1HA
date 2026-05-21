@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package com.github.itskenny0.r1ha.core.ha

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
sealed interface HaOutbound {

    @Serializable @SerialName("auth")
    data class Auth(@SerialName("access_token") val accessToken: String) : HaOutbound

    @Serializable @SerialName("ping")
    data class Ping(val id: Int) : HaOutbound

    @Serializable @SerialName("subscribe_trigger")
    data class SubscribeStateTrigger(
        val id: Int,
        val trigger: StateTrigger = StateTrigger(entityIds = emptyList()),
    ) : HaOutbound {
        constructor(id: Int, entityIds: List<String>) :
            this(id = id, trigger = StateTrigger(entityIds = entityIds))

        @Serializable
        data class StateTrigger(
            @EncodeDefault val platform: String = "state",
            @SerialName("entity_id") val entityIds: List<String>,
        )
    }

    @Serializable @SerialName("unsubscribe_events")
    data class UnsubscribeEvents(val id: Int, val subscription: Int) : HaOutbound

    @Serializable @SerialName("call_service")
    data class CallService(
        val id: Int,
        @SerialName("domain") val haDomain: String,
        val service: String,
        @SerialName("service_data") val data: JsonObject? = null,
        val target: Target,
    ) : HaOutbound {
        constructor(id: Int, haDomain: String, service: String, entityId: String, data: JsonObject?) :
            this(id, haDomain, service, data, Target(entityId))

        @Serializable
        data class Target(@SerialName("entity_id") val entityId: String)
    }

    @Serializable @SerialName("get_states")
    data class GetStates(val id: Int) : HaOutbound

    @Serializable @SerialName("lovelace/config")
    data class GetLovelaceConfig(val id: Int) : HaOutbound

    /**
     * Custom WebSocket command registered by the unified_remote HA integration.
     * HA receives this and synchronously dispatches it to the Unified Remote
     * server running on the local network (UDP/TCP port 9512).
     *
     * [t] is the command discriminator:
     *   "move"         — relative mouse move; [dx]/[dy] in pixels
     *   "scroll"       — wheel scroll;        [dx]/[dy] in pixels
     *   "click"        — left button click
     *   "right_click"  — right button click
     *   "double_click" — double left click
     *   "down"/"up"    — button press/release (for drag)
     *   "volume"       — [action] = "up" | "down" | "mute"
     *   "media"        — [action] = "play_pause" | "previous" | "next" | "stop"
     *   "key"          — [key] = UR key name (e.g. "escape", "tab", "return")
     *   "text"         — [text] = string to type
     */
    @Serializable @SerialName("unified_remote/command")
    data class UnifiedRemoteCommand(
        val id: Int,
        val t: String,
        val dx: Double? = null,
        val dy: Double? = null,
        val action: String? = null,
        val text: String? = null,
        val key: String? = null,
    ) : HaOutbound
}
