package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/**
 * Raw `/api/states` row for domains we don't model in the [Domain]
 * enum — cameras, persons, weather, calendars, climate-like helpers.
 * Adding these to [Domain] would force exhaustive-when updates across
 * 5+ files for read-only or special-handling entities that don't fit
 * the card-stack tile model anyway, so we keep the lightweight shape
 * here and let dedicated screens (CamerasScreen, WeatherScreen, etc.)
 * decode the attributes they care about.
 *
 * @Stable so Compose can skip recomposition when a row reference
 * hasn't changed across a refresh.
 */
@Stable
data class RawEntityRow(
    /** Full HA entity_id e.g. "camera.front_door". */
    val entityId: String,
    /** Friendly name from `attributes.friendly_name`, falling back to
     *  the entity_id when HA didn't include one. */
    val friendlyName: String,
    /** HA's raw state string ("idle", "home", "rainy", etc.). */
    val state: String,
    /** Full attributes JSON — caller picks out the fields it cares
     *  about (e.g. cameras read `entity_picture`, weather reads
     *  `temperature` + `condition`). */
    val attributes: JsonObject,
    /** When HA last reported a state change for this entity. Decoded
     *  from the `last_changed` field on `/api/states`; null when HA
     *  didn't include one or the timestamp was unparseable. Used by
     *  the Persons screen + similar surfaces to show 'since X'
     *  relative timestamps. */
    val lastChanged: Instant? = null,
)

private val rawStatesJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

/**
 * Domain-agnostic decode of a single `/api/states`-shaped row (also the shape of
 * a `state_changed` event's `new_state` / a `subscribe_trigger`'s `to_state`)
 * into a [RawEntityRow]. Unlike the typed `decodeStatesBody` path, this keeps
 * entities of EVERY domain, including ones not in the [Domain] enum (`sun.sun`,
 * custom integration sensors, `device_tracker.*`), so the dashboards renderer can
 * show their last-known value instead of a blank box.
 *
 * Returns null only when the row is structurally unusable: no `entity_id`, no
 * `domain.object_id` separator, or no `state` field. The `state` string is kept
 * verbatim, including `"unavailable"` / `"unknown"`, so the renderer can surface
 * those too.
 */
fun decodeRawRow(row: JsonObject): RawEntityRow? {
    val eid = (row["entity_id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
    if (eid.isBlank() || !eid.contains('.')) return null
    val stateStr = (row["state"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
    val attrs = row["attributes"] as? JsonObject ?: JsonObject(emptyMap())
    val friendly = (attrs["friendly_name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: eid
    val lastChanged = (row["last_changed"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.let {
        runCatching { Instant.parse(it) }.getOrNull()
    }
    return RawEntityRow(
        entityId = eid,
        friendlyName = friendly,
        state = stateStr,
        attributes = attrs,
        lastChanged = lastChanged,
    )
}

/**
 * Parses a full `/api/states` response body into [RawEntityRow]s for ALL domains
 * (no supported-domain filter). Resilient per-row: one malformed entry is skipped
 * rather than blanking the whole list. Shared by the dashboards raw-state seed and
 * the per-domain REST listing.
 */
fun decodeRawStatesBody(body: String): List<RawEntityRow> {
    val rows = rawStatesJson
        .decodeFromString<List<kotlinx.serialization.json.JsonElement>>(body)
    return rows.mapNotNull { (it as? JsonObject)?.let { obj -> decodeRawRow(obj) } }
}
