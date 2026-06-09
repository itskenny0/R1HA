package com.github.itskenny0.r1ha.feature.dashboards.cards

/**
 * Pure, stateless helper that resolves a card's `name_type` to a display
 * string using pre-built registry lookup maps.
 *
 * All maps are keyed by the stable HA ids their names imply and are
 * expected to be built once after the relevant registries load. When a
 * map is empty (registries unavailable or still loading) every resolution
 * returns null, and callers fall back to the entity's friendly_name.
 *
 * Resolution order per entity:
 *  - device name:  entityToDevice[entityId] -> deviceToName[deviceId]
 *  - area name:    entityToArea[entityId] wins; if absent, entityToDevice[entityId]
 *                  -> deviceToArea[deviceId] -> areaToName[areaId]
 *  - floor name:   area resolution as above, then areaToFloor[areaId]
 *                  -> floorToName[floorId]
 */
class DashboardNameResolver(
    /** entity_id -> device_id (null when entity has no device). */
    private val entityToDevice: Map<String, String?>,
    /** entity_id -> area_id direct assignment (null when unset). */
    private val entityToArea: Map<String, String?>,
    /** device_id -> display name. */
    private val deviceToName: Map<String, String>,
    /** device_id -> area_id (null when device is unassigned). */
    private val deviceToArea: Map<String, String?>,
    /** area_id -> display name. */
    private val areaToName: Map<String, String>,
    /** area_id -> floor_id (null when area has no floor). */
    private val areaToFloor: Map<String, String?>,
    /** floor_id -> display name. */
    private val floorToName: Map<String, String>,
) {

    /** The display name of the device owning [entityId], or null. */
    fun deviceName(entityId: String): String? {
        val deviceId = entityToDevice[entityId] ?: return null
        return deviceToName[deviceId]
    }

    /**
     * The display name of the area assigned to [entityId], or null.
     *
     * Uses the entity's own area_id first; if unset, falls through to the
     * device's area_id. This matches HA's own resolution order in
     * `entity_registry.py` (entity area overrides device area).
     */
    fun areaName(entityId: String): String? {
        val areaId = resolveAreaId(entityId) ?: return null
        return areaToName[areaId]
    }

    /**
     * The display name of the floor the [entityId]'s area belongs to,
     * or null when no floor assignment exists.
     */
    fun floorName(entityId: String): String? {
        val areaId = resolveAreaId(entityId) ?: return null
        val floorId = areaToFloor[areaId] ?: return null
        return floorToName[floorId]
    }

    /**
     * Resolve `name_type` for [entityId]. The value is a space or comma
     * separated list of tokens from {"entity", "device", "area", "floor"}.
     * Each token that resolves to a non-blank string contributes one part;
     * parts are joined with a space. Returns null when no part resolves
     * (caller falls back to friendly_name).
     *
     * "entity" is handled by the caller (it means friendly_name), so if the
     * only non-null part is "entity", this returns null to let the existing
     * path handle it without calling the registry maps.
     */
    fun resolveParts(nameType: String, entityId: String): String? {
        val tokens = nameType.split(' ', ',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        val parts = tokens.mapNotNull { token ->
            when (token) {
                "device" -> deviceName(entityId)
                "area" -> areaName(entityId)
                "floor" -> floorName(entityId)
                "entity" -> null // entity = friendly_name, handled by caller
                else -> null
            }
        }
        return if (parts.isEmpty()) null else parts.joinToString(" ")
    }

    private fun resolveAreaId(entityId: String): String? {
        entityToArea[entityId]?.let { return it }
        val deviceId = entityToDevice[entityId] ?: return null
        return deviceToArea[deviceId]
    }

    companion object {
        /** An empty resolver that always returns null for every lookup.
         *  Used as the CompositionLocal default so callers gracefully
         *  degrade to friendly_name when the registries haven't loaded. */
        val EMPTY = DashboardNameResolver(
            entityToDevice = emptyMap(),
            entityToArea = emptyMap(),
            deviceToName = emptyMap(),
            deviceToArea = emptyMap(),
            areaToName = emptyMap(),
            areaToFloor = emptyMap(),
            floorToName = emptyMap(),
        )

        /**
         * Build a resolver from the raw registry lists returned by
         * [com.github.itskenny0.r1ha.core.ha.HaRepository]. Any of the
         * three lists may be empty when the corresponding registry call
         * failed; the resulting resolver simply returns null for any
         * resolution that requires that registry, and callers fall back
         * to friendly_name.
         */
        fun from(
            entityRegistry: List<com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry>,
            devices: List<com.github.itskenny0.r1ha.core.ha.DeviceInfo>,
            areas: List<com.github.itskenny0.r1ha.core.ha.AreaInfo>,
        ): DashboardNameResolver {
            val entityToDevice = entityRegistry.associate { it.entityId to it.deviceId }
            val entityToArea = entityRegistry.associate { it.entityId to it.areaId }
            val deviceToName = devices.associate { it.id to it.displayName }
            val deviceToArea = devices.associate { it.id to it.areaId }
            val areaToName = areas.associate { it.areaId to it.name }
            val areaToFloor = areas.associate { it.areaId to it.floorId }
            // Floor names: derived from the AreaInfo.floorId field. HA does not
            // expose a dedicated floor_registry list via the standard WS API (it
            // exists as floor_registry/list but is undocumented and not part of the
            // stable contract). Instead we use the FloorsViewModel's template approach
            // to obtain floor names from the existing floor data embedded in AreaInfo.
            // Since AreaInfo does not carry the floor name (only the floor_id), the
            // floor name is unavailable from the registries alone; floor resolution
            // therefore returns null (PARTIAL). Callers fall back to area name when
            // nameType contains "floor" and no floor name is resolvable.
            val floorToName = emptyMap<String, String>()
            return DashboardNameResolver(
                entityToDevice = entityToDevice,
                entityToArea = entityToArea,
                deviceToName = deviceToName,
                deviceToArea = deviceToArea,
                areaToName = areaToName,
                areaToFloor = areaToFloor,
                floorToName = floorToName,
            )
        }
    }
}
