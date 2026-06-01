package com.github.itskenny0.r1ha.feature.search

import com.github.itskenny0.r1ha.core.ha.AreaInfo
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Joins the three Home Assistant registries (area, entity, device) to stamp each
 * search candidate with its effective area NAME, so Universal Search can match a
 * query like "kitchen" against the entities that live in the Kitchen area.
 *
 * Why this is needed: `/api/states` does NOT include area assignment in an entity's
 * attributes. Area assignment lives in HA's registries, which are only reachable over
 * the WebSocket. So the `area` field on a freshly-loaded [EntityState] is effectively
 * always null until we resolve it here.
 *
 * Precedence follows HA's own rule for an entity's effective area:
 *  1. The entity-registry entry's own `areaId`, when set. An explicit per-entity
 *     assignment always wins, even if the entity's device sits in a different area.
 *  2. Otherwise, the area of the entity's device: entity-registry `deviceId` then that
 *     device's `areaId`. This is the common case (most entities inherit their device's
 *     area rather than being assigned individually).
 *  3. If neither yields an area_id, or the resolved area_id is not present in the area
 *     registry (stale / unknown slug), the entity keeps `area = null` rather than
 *     surfacing a raw slug.
 *
 * The resolved area_id is mapped to the human-friendly area NAME via the area registry,
 * because that is what the user types and what [SearchRanker] matches against.
 *
 * Pure and side-effect-free: no coroutines, no repository, fully unit-testable. The
 * caller fetches the registries; this just does the join. Lookup maps are built once
 * up front (areaId -> name, entityId -> entry, deviceId -> areaId) so the per-entity
 * resolution is O(1) and the whole pass is O(n), never a nested O(n*m) scan.
 */
object SearchAreaResolver {

    /**
     * Returns [entities] with each entity's `area` set to its resolved area name, or
     * left null when no area resolves. Entities are returned in the same order.
     *
     * @param entities the search candidates loaded from `/api/states`.
     * @param areas the area registry (`config/area_registry/list`).
     * @param entityRegistry the entity registry (`config/entity_registry/list`).
     * @param devices the device registry (`config/device_registry/list`).
     */
    fun resolveAreas(
        entities: List<EntityState>,
        areas: List<AreaInfo>,
        entityRegistry: List<EntityRegistryEntry>,
        devices: List<DeviceInfo>,
    ): List<EntityState> {
        // Build the lookup maps once. associateBy keeps the last entry on key
        // collision, which mirrors how HA itself dedupes a registry by id.
        val areaNameById: Map<String, String> = areas.associate { it.areaId to it.name }
        val entryByEntityId: Map<String, EntityRegistryEntry> =
            entityRegistry.associateBy { it.entityId }
        val deviceAreaById: Map<String, String?> = devices.associate { it.id to it.areaId }

        return entities.map { entity ->
            val entry = entryByEntityId[entity.id.value]
            // Entity-level assignment wins; fall back to the device's area.
            val resolvedAreaId = entry?.areaId
                ?: entry?.deviceId?.let { deviceAreaById[it] }
            val resolvedName = resolvedAreaId?.let { areaNameById[it] }
            // Only overwrite when we resolved a real name; an unknown slug or a
            // missing assignment leaves the entity's existing area untouched (null).
            if (resolvedName != null) entity.copy(area = resolvedName) else entity
        }
    }
}
