package com.github.itskenny0.r1ha.feature.logbook

/**
 * Pure, Compose-free label builders for the Logbook (Recent Activity) surface.
 * Folds each entry's domain, name, message, resulting state, attribution, and
 * relative time into one spoken phrase so TalkBack reads a row as a single unit
 * instead of disjoint fragments, and so the wording is unit-testable.
 */
object LogbookA11y {

    /**
     * Spoken domain word for the row's leading glyph column, e.g.
     * "light" / "binary sensor". HA domains are snake_case; underscores become
     * spaces so they are read naturally. Null / blank yields "event".
     */
    fun domainWord(domain: String?): String {
        val d = domain?.trim().orEmpty()
        return if (d.isEmpty()) "event" else d.replace('_', ' ')
    }

    /**
     * Merged content description for a logbook row, e.g.
     * "light, Kitchen light, turned on, now on, by Evening automation, 2m ago".
     * Each segment self-omits when blank so a sparse system event still reads
     * cleanly. [relativeTime], [triggeredBy], and [state] are the already-
     * resolved phrases the visible row shows.
     */
    fun rowDescription(
        domain: String?,
        name: String,
        message: String?,
        state: String?,
        triggeredBy: String?,
        relativeTime: String?,
    ): String {
        val parts = mutableListOf(domainWord(domain), name)
        message?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        state?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "now $it" }
        triggeredBy?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        relativeTime?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "$it ago" }
        return parts.joinToString(", ")
    }

    /** Spoken hint for the row's tap target. The row tap drills into history. */
    fun rowActionLabel(name: String): String = "Open history for $name"
}
