package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Pure entity-selection logic for the map card's auto-populating modes
 * (`show_all`, `geo_location_sources`), plus zone collection for `fit_zones`.
 * Operates on a list of [EntityState] so it is unit-testable without Compose or a
 * live repository.
 */

/** Whether an entity reports both latitude and longitude attributes. */
internal fun hasCoordinates(state: EntityState): Boolean =
    mapLatLon(state, "latitude") != null && mapLatLon(state, "longitude") != null

internal fun mapLatLon(state: EntityState?, key: String): Double? {
    val prim = state?.attributesJson?.get(key) as? JsonPrimitive ?: return null
    return prim.doubleOrNull ?: prim.content.toDoubleOrNull()
}

/**
 * HA's `show_all`: every locatable `device_tracker` / `person` entity (those are
 * the domains HA's map auto-populates). Returns the entity ids in stable order.
 * Mirrors hui-map-card's show_all which plots all non-hidden trackers/persons
 * that carry coordinates.
 */
fun showAllEntityIds(states: List<EntityState>): List<String> =
    states.asSequence()
        .filter { it.id.value.substringBefore('.') in setOf("device_tracker", "person") }
        .filter { hasCoordinates(it) }
        .map { it.id.value }
        .toList()

/**
 * HA's `geo_location_sources`: every `geo_location` entity whose `source`
 * attribute is in [sources] (the special token "all" matches every source).
 * Returns the matching entity ids in stable order.
 */
fun geoLocationEntityIds(states: List<EntityState>, sources: List<String>): List<String> {
    if (sources.isEmpty()) return emptyList()
    val all = sources.any { it.equals("all", ignoreCase = true) }
    val wanted = sources.map { it.lowercase() }.toSet()
    return states.asSequence()
        .filter { it.id.value.substringBefore('.') == "geo_location" }
        .filter { hasCoordinates(it) }
        .filter { state ->
            all || run {
                val source = (state.attributesJson?.get("source") as? JsonPrimitive)?.content?.lowercase()
                source != null && source in wanted
            }
        }
        .map { it.id.value }
        .toList()
}

/** Every `zone` entity with coordinates (HA passively renders all zones). Returns
 *  the entity ids in stable order. */
fun zoneEntityIds(states: List<EntityState>): List<String> =
    states.asSequence()
        .filter { it.id.value.substringBefore('.') == "zone" }
        .filter { hasCoordinates(it) }
        .map { it.id.value }
        .toList()
