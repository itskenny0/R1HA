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
