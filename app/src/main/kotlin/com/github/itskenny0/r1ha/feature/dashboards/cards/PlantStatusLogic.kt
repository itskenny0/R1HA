package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pure decision logic for the `plant-status` card (hui-plant-status-card.ts).
 * The plant entity reports its readouts and metadata as attributes:
 *  - `moisture` / `temperature` / `brightness` / `conductivity` / `battery`:
 *    the numeric readouts.
 *  - `unit_of_measurement_dict`: { attribute -> unit } for each readout.
 *  - `problem`: a string listing the readouts currently out of range
 *    ("none" when healthy, e.g. "moisture low, conductivity high" otherwise).
 *  - `sensors`: { attribute -> backing sensor entity_id } for per-readout
 *    more-info navigation.
 *
 * Kept Compose-free so the readout extraction / problem flagging is unit-tested.
 */

/** The readouts HA's plant card renders, in display order. */
internal val PLANT_ATTRIBUTES = listOf("moisture", "temperature", "brightness", "conductivity", "battery")

/** One resolved plant readout: its value + unit, the backing sensor entity, and
 *  whether the plant flags it as a problem. */
data class PlantReadout(
    val attribute: String,
    val value: String,
    val unit: String?,
    val backingEntity: String?,
    val isProblem: Boolean,
)

/**
 * Resolve the plant entity's readouts into [PlantReadout]s in display order. Only
 * attributes the entity actually reports are included (HA filters to
 * `key in stateObj.attributes`). The unit comes from
 * `unit_of_measurement_dict[attribute]`, the backing entity from
 * `sensors[attribute]`, and the problem flag from membership in the `problem`
 * attribute's token list.
 */
fun plantReadouts(state: EntityState?): List<PlantReadout> {
    val attrs = state?.attributesJson ?: return emptyList()
    val units = attrs["unit_of_measurement_dict"] as? JsonObject
    val sensors = attrs["sensors"] as? JsonObject
    val problemTokens = plantProblemTokens((attrs["problem"] as? JsonPrimitive)?.content)
    return PLANT_ATTRIBUTES.mapNotNull { attr ->
        val raw = (attrs[attr] as? JsonPrimitive)?.content?.takeUnless { it.isBlank() } ?: return@mapNotNull null
        PlantReadout(
            attribute = attr,
            value = raw,
            unit = (units?.get(attr) as? JsonPrimitive)?.content?.takeUnless { it.isBlank() },
            backingEntity = (sensors?.get(attr) as? JsonPrimitive)?.content?.takeUnless { it.isBlank() },
            isProblem = attr in problemTokens,
        )
    }
}

/**
 * Parse the plant `problem` attribute into the set of attribute names currently
 * flagged. HA's value is "none" when healthy, otherwise a comma-separated list of
 * "<attribute> <low|high>" phrases; we extract the attribute name from each.
 */
internal fun plantProblemTokens(problem: String?): Set<String> {
    val v = problem?.trim()?.lowercase() ?: return emptySet()
    if (v.isEmpty() || v == "none") return emptySet()
    return v.split(',').mapNotNull { phrase ->
        phrase.trim().split(' ').firstOrNull()?.takeIf { it in PLANT_ATTRIBUTES }
    }.toSet()
}

/** Whether the plant currently reports any problem (its state is "problem" or the
 *  `problem` attribute names at least one out-of-range readout). */
fun plantHasProblem(state: EntityState?): Boolean {
    if (state?.rawState?.equals("problem", ignoreCase = true) == true) return true
    val problem = (state?.attributesJson?.get("problem") as? JsonPrimitive)?.content
    return plantProblemTokens(problem).isNotEmpty()
}
