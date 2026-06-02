package com.github.itskenny0.r1ha.feature.persons

import java.util.Locale

/**
 * Pure presence/zone derivation helpers for the "Who's home" surface. Kept
 * free of Compose and Android types so they can be unit-tested directly and
 * so a single person's row recomposes without re-running any list-wide logic.
 */

/**
 * Coarse presence category derived from a person/device_tracker state string.
 * The screen maps each bucket to a colour; keeping the bucket separate from
 * the colour keeps this file Android-free and testable.
 */
enum class PresenceKind {
    /** State is exactly "home". */
    HOME,

    /** State is "not_home" / "away". */
    AWAY,

    /** State is "unknown" / "unavailable" / blank. */
    UNKNOWN,

    /** Any other state is treated as a named HA zone (e.g. "Work", "School"). */
    ZONE,
}

/**
 * Presence label + category for a row.
 *
 * [label] is the short chip text: "HOME", "AWAY", "?" for unknown, or the
 * upper-cased zone name for a named zone. [kind] is the colour bucket.
 */
data class PresenceLabel(val label: String, val kind: PresenceKind)

/**
 * Derive the presence chip from a raw HA state. HA reports person/
 * device_tracker location as one of the reserved values "home" / "not_home"
 * (plus the generic "away" some integrations emit), the reserved
 * "unknown" / "unavailable", or the friendly name of a configured zone.
 *
 * The match is case-insensitive; the rendered zone label preserves the
 * original casing's words but is upper-cased to sit with the other chips.
 */
fun presenceLabel(state: String): PresenceLabel {
    val trimmed = state.trim()
    return when (trimmed.lowercase(Locale.US)) {
        "home" -> PresenceLabel("HOME", PresenceKind.HOME)
        "not_home", "away" -> PresenceLabel("AWAY", PresenceKind.AWAY)
        "unknown", "unavailable", "" -> PresenceLabel("?", PresenceKind.UNKNOWN)
        else -> PresenceLabel(trimmed.uppercase(Locale.US), PresenceKind.ZONE)
    }
}

/**
 * Up-to-two-letter initials for the avatar fallback, derived from a display
 * name. Uses the first letter of the first two whitespace-separated words, or
 * the first two letters of a single word. Falls back to "?" when the name
 * has no usable letters (so the avatar never renders blank).
 */
fun initialsFor(name: String): String {
    val words = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    val letters = when {
        words.isEmpty() -> ""
        words.size == 1 -> words[0].take(2)
        else -> "${words[0].first()}${words[1].first()}"
    }
    val cleaned = letters.uppercase(Locale.US)
    return cleaned.ifBlank { "?" }
}

/**
 * Spoken presence phrase for a person/device row, conveyed entirely by words
 * so a screen-reader user gets the same information a sighted user reads from
 * the coloured presence chip (presence must never be colour-only).
 *
 * "Home", "Away", "Location unknown", or "In <zone>" for a named HA zone. The
 * zone name keeps its original casing here (unlike the upper-cased visible
 * chip) so it reads naturally aloud.
 */
fun presenceSpoken(state: String): String {
    val trimmed = state.trim()
    return when (presenceLabel(state).kind) {
        PresenceKind.HOME -> "Home"
        PresenceKind.AWAY -> "Away"
        PresenceKind.UNKNOWN -> "Location unknown"
        PresenceKind.ZONE -> "In $trimmed"
    }
}

/**
 * Home-vs-away tally for a group of person/device rows, shown as a short
 * summary chip in the section header ("3 home, 2 away") so the user gets a
 * who's-in count without scanning every row. ZONE counts as away (the person
 * is somewhere other than home); UNKNOWN is excluded from both buckets so a
 * stale/unavailable tracker doesn't inflate either side.
 */
data class PresenceTally(val home: Int, val away: Int) {
    /** "3 home, 2 away", dropping a zero bucket, or "" when both are zero. */
    fun summary(): String {
        val parts = mutableListOf<String>()
        if (home > 0) parts += "$home home"
        if (away > 0) parts += "$away away"
        return parts.joinToString(", ")
    }
}

/** Tally a group's raw HA states into home / away buckets (see [PresenceTally]). */
fun presenceTally(states: List<String>): PresenceTally {
    var home = 0
    var away = 0
    for (s in states) {
        when (presenceLabel(s).kind) {
            PresenceKind.HOME -> home++
            PresenceKind.AWAY, PresenceKind.ZONE -> away++
            PresenceKind.UNKNOWN -> Unit
        }
    }
    return PresenceTally(home, away)
}

/**
 * One merged accessibility description for a person/device row so a screen
 * reader announces it as a single phrase ("Jane Doe, Home, phone 82 percent")
 * instead of reading the avatar, presence chip, name, entity id, and each
 * metadata chip as disconnected fragments.
 *
 * [name] display name, [state] raw HA state, then the optional metadata that
 * the row renders as chips. [relativeTime] is the already-formatted freshness
 * string (e.g. "5m ago") or null/blank when the row shows none.
 */
fun rowContentDescription(
    name: String,
    state: String,
    relativeTime: String? = null,
    source: String? = null,
    batteryLevel: Int? = null,
    gpsAccuracy: Int? = null,
): String {
    val parts = mutableListOf(name, presenceSpoken(state))
    relativeTime?.takeIf { it.isNotBlank() }?.let { parts += it }
    source?.takeIf { it.isNotBlank() }?.let { parts += it }
    batteryLevel?.let { parts += "battery $it percent" }
    gpsAccuracy?.let { parts += "accuracy $it meters" }
    return parts.joinToString(", ")
}
