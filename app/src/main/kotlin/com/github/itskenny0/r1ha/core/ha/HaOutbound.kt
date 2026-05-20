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
}
