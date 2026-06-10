package com.github.itskenny0.r1ha.feature.areas

/**
 * Pure, Compose-free helpers that build the spoken labels for the Areas
 * surface. Kept out of [AreasScreen] so the exact wording is asserted in unit
 * tests without a Compose runtime.
 */

/**
 * Spoken phrase for the two-pane summary, e.g. "Areas. 12 areas, 240 entities.
 * Open an area to control its entities." Replaces the pane's bare numerals +
 * bare labels (four disjoint nodes) with one sentence.
 */
internal fun areasSummaryDescription(areas: Int, entities: Int): String {
    val stats = listOf(
        areasCountPhrase(areas, "area", "areas"),
        areasCountPhrase(entities, "entity", "entities"),
    ).joinToString(", ")
    return "Areas. $stats. Open an area to control its entities."
}

/** "1 area" / "5 areas" / "no areas", pluralised for speech. */
private fun areasCountPhrase(count: Int, singular: String, plural: String): String = when {
    count <= 0 -> "no $plural"
    count == 1 -> "1 $singular"
    else -> "$count $plural"
}
