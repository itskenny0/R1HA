package com.github.itskenny0.r1ha.feature.zones

import java.util.Locale

/**
 * Pure, dependency-free spoken-label builders for the Zones surface. Kept out
 * of Compose so TalkBack content descriptions can be unit-tested directly.
 * Each builder returns a single merged sentence so a screen reader announces
 * one coherent phrase per row instead of reading scattered icon and count
 * tokens. State is always spoken in words (occupant counts, names) rather than
 * conveyed by colour alone.
 */
object ZoneA11y {

    /** "152 metres" / "1.2 kilometres" for the spoken radius. */
    private fun spokenRadius(meters: Double): String =
        if (meters >= 1000) {
            val km = "%.1f".format(Locale.US, meters / 1000.0)
            "$km kilometres"
        } else {
            "${meters.toInt()} metres"
        }

    /**
     * Merged spoken label for one zone row. Speaks the zone name, how many
     * people are inside (in words), their names when present, and the radius.
     * The member list is the accessible path into a zone's occupancy.
     */
    fun zoneRowLabel(
        name: String,
        occupants: List<String>,
        radiusMeters: Double?,
        isHome: Boolean = false,
        passive: Boolean = false,
    ): String {
        val cleanName = name.trim().ifEmpty { "Unnamed zone" }
        val occ = occupants.map { it.trim() }.filter { it.isNotEmpty() }
        val lead = if (isHome) "Home zone $cleanName" else "Zone $cleanName"
        val parts = mutableListOf(lead)
        if (passive) parts += "passive"
        parts += when (occ.size) {
            0 -> "empty"
            1 -> "1 person inside, ${occ[0]}"
            else -> "${occ.size} people inside, ${occ.joinToString(", ")}"
        }
        radiusMeters?.let { parts += "radius ${spokenRadius(it)}" }
        return parts.joinToString(". ")
    }

    /** Merged spoken label for the OUTSIDE bucket row. */
    fun outsideRowLabel(names: List<String>): String {
        val clean = names.map { it.trim() }.filter { it.isNotEmpty() }
        return when (clean.size) {
            0 -> "Outside any zone. Nobody."
            1 -> "Outside any zone. 1 person, ${clean[0]}"
            else -> "Outside any zone. ${clean.size} people, ${clean.joinToString(", ")}"
        }
    }

    /**
     * Spoken description for the abstract zone map. The map is decorative
     * geometry; this sentence tells a screen-reader user what it depicts and
     * points them at the zone list below as the real accessible path.
     */
    fun mapDescription(zoneCount: Int, trackerCount: Int): String {
        val z = if (zoneCount == 1) "1 zone" else "$zoneCount zones"
        val t = if (trackerCount == 1) "1 tracked person" else "$trackerCount tracked people"
        return "Map showing $z and $t by location. " +
            "See the zone list below for occupancy details."
    }
}
