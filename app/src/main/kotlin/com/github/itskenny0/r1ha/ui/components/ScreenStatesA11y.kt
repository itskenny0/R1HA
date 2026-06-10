package com.github.itskenny0.r1ha.ui.components

/**
 * Pure, Compose-free helpers that build the spoken phrases for the canonical
 * empty/error states in [ScreenStates.kt]. Kept out of the composables so the
 * exact wording is asserted in unit tests without a Compose runtime, matching
 * the per-feature *A11y helpers.
 *
 * The visible title arrives uppercase ("COULDN'T LOAD DEVICES"); speech gets a
 * sentence-cased rendering so TalkBack doesn't spell short all-caps words out
 * letter by letter.
 */

/** Sentence-cased spoken form of an uppercase state title, e.g.
 *  "COULDN'T LOAD DEVICES" -> "Couldn't load devices". */
internal fun stateTitleSpoken(title: String): String =
    title.trim().lowercase().replaceFirstChar { it.uppercase() }

/**
 * The whole state as one spoken unit: sentence-cased title, then the body,
 * e.g. "No areas. Define areas under Settings in HA's web UI." Read by the
 * merged scaffold column so the title and body announce together instead of
 * as two disjoint fragments.
 */
internal fun stateAnnouncement(title: String, body: String?): String {
    val spokenTitle = stateTitleSpoken(title)
    val trimmedBody = body?.trim().takeUnless { it.isNullOrEmpty() }
    return if (trimmedBody == null) spokenTitle else "$spokenTitle. $trimmedBody"
}

/**
 * Spoken label for the RETRY chip, tied to what it retries so it doesn't
 * announce as a context-free "Retry": "Retry, couldn't load devices".
 */
internal fun retryActionDescription(title: String): String =
    "Retry, ${title.trim().lowercase()}"

/** Sentence-cased spoken label for a generic uppercase action chip, e.g.
 *  "OPEN SETTINGS" -> "Open settings". */
internal fun stateActionDescription(actionText: String): String =
    stateTitleSpoken(actionText)
