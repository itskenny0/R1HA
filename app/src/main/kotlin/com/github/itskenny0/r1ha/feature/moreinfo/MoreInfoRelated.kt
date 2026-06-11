package com.github.itskenny0.r1ha.feature.moreinfo

/**
 * Pure selection for the more-info RELATED section (the R1 equivalent of HA's
 * more-info "Related" tab). HA's related tab lists entities sharing the same
 * device and the same area as the focused entity; R1HA derives the same two
 * groups from the area-registry snapshot already cached for area cards
 * ([com.github.itskenny0.r1ha.feature.dashboards.cards.AreaRegistryCache]).
 *
 * No Compose / Android here so the grouping is unit-testable against the raw
 * registry maps.
 */
object MoreInfoRelated {

    /** The two related groups, each an ordered list of entity ids excluding the
     *  focused entity itself. Empty lists when nothing relates. */
    data class Related(
        val sameDevice: List<String>,
        val sameArea: List<String>,
    ) {
        val isEmpty: Boolean get() = sameDevice.isEmpty() && sameArea.isEmpty()
    }

    /**
     * Compute the related groups for [entityId].
     *
     *  - same-device: every other entity whose owning `device_id` matches the
     *    focused entity's device (from [deviceByEntity]). Empty when the entity
     *    has no device.
     *  - same-area: every other entity in the area that contains the focused
     *    entity (from [entitiesByArea]), minus the same-device entities (they
     *    already show under same-device, so we don't list them twice). Empty when
     *    the entity belongs to no area.
     *
     * Both lists preserve the registry's declaration order and never include the
     * focused entity itself.
     */
    fun compute(
        entityId: String,
        entitiesByArea: Map<String, List<String>>,
        deviceByEntity: Map<String, String>,
    ): Related {
        val device = deviceByEntity[entityId]
        val sameDevice = if (device.isNullOrBlank()) {
            emptyList()
        } else {
            deviceByEntity.entries
                .filter { it.value == device && it.key != entityId }
                .map { it.key }
        }
        // Stable order: walk the registry's area membership (declaration order)
        // rather than the unordered device map, so same-device reads predictably.
        val orderedSameDevice = if (sameDevice.isEmpty()) {
            emptyList()
        } else {
            val set = sameDevice.toSet()
            val ordered = entitiesByArea.values.flatten().filter { it in set }
            // Fall back to map order for device members not in any area.
            (ordered + sameDevice).distinct()
        }

        val area = entitiesByArea.entries.firstOrNull { entityId in it.value }
        val sameArea = if (area == null) {
            emptyList()
        } else {
            val deviceSet = orderedSameDevice.toSet()
            area.value.filter { it != entityId && it !in deviceSet }
        }
        return Related(sameDevice = orderedSameDevice, sameArea = sameArea)
    }
}
