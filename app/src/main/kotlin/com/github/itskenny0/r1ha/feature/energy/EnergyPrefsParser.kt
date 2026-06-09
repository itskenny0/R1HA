package com.github.itskenny0.r1ha.feature.energy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure parser for the `device_consumption` array returned by HA's
 * `energy/get_prefs` WebSocket command.
 *
 * Extracted as a top-level function so it is unit-testable without any
 * repository wiring or live HA connection.
 *
 * Shape expected (HA energy websocket API):
 * ```
 * {
 *   "device_consumption": [
 *     { "stat_consumption": "sensor.fridge_power", "name": "Fridge" },
 *     { "stat_consumption": "sensor.oven_power" }
 *   ]
 * }
 * ```
 *
 * Returns a map of stat/entity id -> custom display name.
 * Entries whose `name` field is absent, null, or blank are omitted.
 * A malformed payload (null, wrong type, missing array) yields an empty map.
 *
 * UNVERIFIED OFFLINE: the exact field names and array shape have not been
 * tested against a live Home Assistant; the implementation follows HA's
 * documented energy websocket API.
 */
fun parseEnergyPrefsJson(payload: kotlinx.serialization.json.JsonElement?): Map<String, String> {
    val obj = payload as? JsonObject ?: return emptyMap()
    val arr = obj["device_consumption"] as? JsonArray ?: return emptyMap()
    val out = mutableMapOf<String, String>()
    for (el in arr) {
        val row = el as? JsonObject ?: continue
        val statId = (row["stat_consumption"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: continue
        val name = (row["name"] as? JsonPrimitive)?.content
            ?.takeIf { it.isNotBlank() } ?: continue
        out[statId] = name
    }
    return out
}
