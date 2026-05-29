package com.github.itskenny0.r1ha.feature.devices

import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
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
