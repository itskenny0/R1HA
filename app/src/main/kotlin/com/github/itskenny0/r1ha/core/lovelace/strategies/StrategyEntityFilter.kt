package com.github.itskenny0.r1ha.core.lovelace.strategies

/**
 * Port of HA's `common/entity/entity_filter.ts` `generateEntityFilter`, trimmed
 * to the criteria the area / home strategies actually pass: domain(s), area
 * (with the `null` = "no area" sentinel), device_class(es), and
 * `entity_category: "none"` (only primary entities, no config/diagnostic).
 *
 * The area resolution mirrors HA: an entity's effective area is its own
 * `area_id`, or its device's `area_id` when the entity has none. A null filter
 * area matches entities that resolve to NO area.
 */
class StrategyEntityFilter(
    private val data: StrategyData,
    private val domains: Set<String>? = null,
    /** "__any__" = any area; null = entities with no area; else a specific id. */
    private val areaSpec: AreaSpec = AreaSpec.Any,
    private val deviceClasses: Set<String>? = null,
    private val entityCategoryNone: Boolean = false,
) {
    sealed interface AreaSpec {
        data object Any : AreaSpec
        data object NoArea : AreaSpec
        data class Id(val areaId: String) : AreaSpec
    }

    private fun effectiveArea(entityId: String): String? {
        val reg = data.entities[entityId]
        return reg?.areaId ?: reg?.deviceId?.let { data.devices[it]?.areaId }
    }

    fun matches(entityId: String): Boolean {
        val ent = data.states[entityId] ?: return false
        val domain = ent.domain
        if (domains != null && domain !in domains) return false
        if (entityCategoryNone) {
            val reg = data.entities[entityId]
            if (reg?.entityCategory != null) return false
            if (reg?.hiddenBy != null) return false
        }
        when (areaSpec) {
            is AreaSpec.Any -> Unit
            is AreaSpec.NoArea -> if (effectiveArea(entityId) != null) return false
            is AreaSpec.Id -> if (effectiveArea(entityId) != areaSpec.areaId) return false
        }
        if (deviceClasses != null) {
            val dc = ent.deviceClass
            if (dc == null || dc !in deviceClasses) return false
        }
        return true
    }

    companion object {
        /** All entity ids passing [filter], in the data's iteration order. */
        fun find(data: StrategyData, filters: List<StrategyEntityFilter>): List<String> =
            data.states.keys.filter { id -> filters.any { it.matches(id) } }
    }
}
