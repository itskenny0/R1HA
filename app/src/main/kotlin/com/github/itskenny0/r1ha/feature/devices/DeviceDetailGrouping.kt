package com.github.itskenny0.r1ha.feature.devices

import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState
import java.util.Locale

/**
 * Pure grouping / lookup helpers for the device drill-in. Kept free of
 * Compose and the repository so they can be unit-tested directly: the
 * detail view's correctness is entirely about "which entities belong to
 * this device, how are they bucketed by domain, and which bucket comes
 * first". All of that is decided here.
 */

/**
 * Domain prefix of an `entity_id` ("light.kitchen" -> "light"). Returns
 * the whole string when there's no dot so a malformed id still groups
 * under something predictable rather than throwing. Deliberately does NOT
 * go through [com.github.itskenny0.r1ha.core.ha.EntityId], whose
 * constructor rejects domains the card stack doesn't model (e.g.
 * `device_tracker`, `event`, `sun`); the drill-in lists every registered
 * entity honestly, supported card archetype or not.
 */
fun domainOfEntityId(entityId: String): String {
    val dot = entityId.indexOf('.')
    return if (dot > 0) entityId.substring(0, dot) else entityId
}

/**
 * Domains the user can actuate (toggle, set, fire). Used to float control
 * entities above read-only ones in the detail view so the things you can
 * do with a device sit at the top. This is a display-ordering decision,
 * not a capability contract: the drill-in itself is read-only.
 */
private val CONTROL_DOMAINS: Set<String> = setOf(
    "light",
    "switch",
    "fan",
    "cover",
    "climate",
    "media_player",
    "lock",
    "vacuum",
    "humidifier",
    "valve",
    "water_heater",
    "lawn_mower",
    "select",
    "input_select",
    "number",
    "input_number",
    "input_boolean",
    "scene",
    "script",
    "button",
    "input_button",
    "automation",
    "alarm_control_panel",
    "remote",
    "siren",
    "camera",
    "counter",
    "timer",
)

/** True when [domain] is one the user can act on (see [CONTROL_DOMAINS]). */
fun isControlDomain(domain: String): Boolean = domain in CONTROL_DOMAINS

/**
 * One domain bucket in the detail view: the raw domain prefix, whether it
 * is a control domain (controls sort first), and the entities in it.
 */
data class DeviceEntityGroup(
    val domain: String,
    val isControl: Boolean,
    val entities: List<EntityRegistryEntry>,
)

/**
 * Buckets [entities] (already filtered to one device by the caller) by
 * domain. Control domains come first, then read-only, each block sorted
 * alphabetically by domain; entities inside a bucket are sorted by display
 * name. Stable and deterministic so the view never reshuffles between
 * recompositions.
 */
fun groupEntitiesByDomain(entities: List<EntityRegistryEntry>): List<DeviceEntityGroup> =
    entities
        .groupBy { domainOfEntityId(it.entityId) }
        .map { (domain, list) ->
            DeviceEntityGroup(
                domain = domain,
                isControl = isControlDomain(domain),
                entities = list.sortedBy { it.displayName.lowercase(Locale.US) },
            )
        }
        .sortedWith(
            compareBy(
                { !it.isControl },
                { it.domain.lowercase(Locale.US) },
            ),
        )

/** Entities owned by [deviceId], unsorted (the grouping step sorts). */
fun entitiesForDevice(
    entities: List<EntityRegistryEntry>,
    deviceId: String,
): List<EntityRegistryEntry> = entities.filter { it.deviceId == deviceId }

/**
 * Health summary for a device's drill-in header: its battery reading (if any),
 * whether the battery is charging, and how many of its live-reporting entities
 * are currently unavailable. Mirrors HA's own device page, which surfaces a
 * battery icon + charging state and dims unavailable entities.
 *
 * [batteryPercent] is the integer percent from the highest-priority `battery`
 * device_class entity (HA prefers `sensor` over `binary_sensor`); null when the
 * device exposes none. [charging] reflects a `battery_charging` binary_sensor.
 * [unavailableCount] / [liveCount] count only entities HA is actually reporting
 * a live state for, so the ratio is honest about coverage.
 */
data class DeviceHealth(
    val batteryPercent: Int?,
    val charging: Boolean,
    val unavailableCount: Int,
    val liveCount: Int,
) {
    val allUnavailable: Boolean get() = liveCount > 0 && unavailableCount == liveCount
}

/**
 * Derives a [DeviceHealth] from the device's entities and the live-state map
 * keyed by raw entity-id. [liveStates] only carries the entities whose domain
 * the client models, so battery sensors (sensor / binary_sensor) are present
 * once HA reports them.
 */
fun deviceHealth(
    entities: List<EntityRegistryEntry>,
    liveStates: Map<String, EntityState>,
): DeviceHealth {
    var batteryPercent: Int? = null
    var batterySource: String? = null // domain of the chosen battery entity
    var charging = false
    var live = 0
    var unavailable = 0
    for (entity in entities) {
        val state = liveStates[entity.entityId] ?: continue
        live++
        if (!state.isAvailable) unavailable++
        when (state.deviceClass) {
            "battery" -> {
                val domain = domainOfEntityId(entity.entityId)
                val pct = batteryPercentOf(state)
                // HA prefers a `sensor` battery over a `binary_sensor` one; keep
                // the first sensor we see, otherwise fall back to a binary_sensor.
                if (pct != null && (batteryPercent == null || (batterySource != "sensor" && domain == "sensor"))) {
                    batteryPercent = pct
                    batterySource = domain
                }
            }
            "battery_charging" -> if (state.isAvailable && state.isOn) charging = true
        }
    }
    return DeviceHealth(
        batteryPercent = batteryPercent,
        charging = charging,
        unavailableCount = unavailable,
        liveCount = live,
    )
}

/**
 * Battery percent for a `battery` device_class entity. A `sensor` reports the
 * number in [EntityState.percent] or parseable from [EntityState.rawState]; a
 * `binary_sensor` battery (low-battery flag) has no number, so 0 when on
 * (problem) and 100 when off (ok) keeps the readout sane. Null when the entity
 * is unavailable or carries no usable value.
 */
private fun batteryPercentOf(state: EntityState): Int? {
    if (!state.isAvailable) return null
    state.percent?.let { return it.coerceIn(0, 100) }
    state.rawState?.trim()?.toDoubleOrNull()?.let { return it.toInt().coerceIn(0, 100) }
    // binary_sensor battery: on == low/problem.
    return if (state.isOn) 0 else 100
}
