package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.lovelace.LogbookTarget

/**
 * Pure decision logic for the logbook card: target-group resolution, entity /
 * state filtering, and "logbook not loaded" detection. No Compose / Android, so
 * each is unit-tested directly.
 */

/**
 * Resolve a logbook card `target:` block to the set of entity ids it selects,
 * joining against the entity + area registries the repository exposes.
 *
 *  - `area_id`: every registry entity whose own area_id matches, plus entities
 *    that inherit their area from a device in that area.
 *  - `floor_id`: expanded to the areas on that floor (via [areas]) then resolved
 *    as area_ids.
 *  - `device_id`: every registry entity whose device_id matches.
 *  - `label_id`: NOT resolvable here. R1HA's entity registry projection
 *    ([EntityRegistryEntry]) does not carry entity labels, so a label target
 *    contributes nothing and is surfaced as an unresolved-target note by the
 *    renderer rather than silently dropped.
 *
 * Returns the resolved entity ids in stable order. [deviceAreas] maps device id
 * to its area id so an entity that gets its area from its device is matched.
 */
internal fun resolveLogbookTarget(
    target: LogbookTarget,
    registry: List<EntityRegistryEntry>,
    areas: List<AreaInfo>,
    deviceAreas: Map<String, String>,
): Set<String> {
    if (target.isEmpty) return emptySet()
    val out = LinkedHashSet<String>()

    val wantedAreas = LinkedHashSet(target.areaIds)
    // Floors expand to their member areas.
    if (target.floorIds.isNotEmpty()) {
        val floors = target.floorIds.toHashSet()
        areas.forEach { area ->
            if (area.floorId != null && area.floorId in floors) wantedAreas.add(area.areaId)
        }
    }

    val wantedDevices = target.deviceIds.toHashSet()

    registry.forEach { entry ->
        val effectiveArea = entry.areaId ?: entry.deviceId?.let { deviceAreas[it] }
        val deviceMatch = entry.deviceId != null && entry.deviceId in wantedDevices
        val areaMatch = effectiveArea != null && effectiveArea in wantedAreas
        if (deviceMatch || areaMatch) out.add(entry.entityId)
    }
    return out
}

/**
 * Whether a logbook card's `target:` carries selectors this client cannot
 * resolve (only `label_id` today). The renderer uses this to surface a small
 * note rather than silently ignoring the unresolved group.
 */
internal fun hasUnresolvableTarget(target: LogbookTarget): Boolean = target.labelIds.isNotEmpty()

/**
 * Filter logbook entries to the configured entity set and, when set, the
 * `state_filter:` state list. Newest first. An empty [entityIds] keeps every
 * entity; an empty [stateFilter] keeps every state.
 *
 * State matching is case-insensitive on the entry's resulting state (HA's
 * logbook entry carries the new state in [LogbookEntry.state]).
 */
internal fun filterLogbookEntries(
    entries: List<LogbookEntry>,
    entityIds: Set<String>,
    stateFilter: List<String>,
): List<LogbookEntry> {
    val states = stateFilter.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toHashSet()
    return entries
        .asSequence()
        .filter { entityIds.isEmpty() || it.entityId?.value in entityIds }
        .filter { entry ->
            if (states.isEmpty()) {
                true
            } else {
                val s = entry.state?.trim()?.lowercase()
                s != null && s in states
            }
        }
        .sortedByDescending { it.timestamp }
        .toList()
}

/**
 * Detect HA's "logbook integration not loaded" condition from a fetch failure.
 * HA returns a 404 / "not found" / "Integration not found" shape when the
 * `logbook` (or `recorder`) integration is absent. Matches loosely on the error
 * message so the card can show the dedicated warning instead of the generic
 * "no activity" placeholder.
 */
internal fun isLogbookNotLoaded(error: Throwable?): Boolean {
    val msg = error?.message?.lowercase() ?: return false
    return ("logbook" in msg || "recorder" in msg) &&
        ("not found" in msg || "not loaded" in msg || "404" in msg || "unavailable" in msg) ||
        "integration not found" in msg
}
