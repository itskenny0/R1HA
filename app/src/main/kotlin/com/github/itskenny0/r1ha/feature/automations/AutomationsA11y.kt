package com.github.itskenny0.r1ha.feature.automations

/**
 * Pure, Compose-free helpers that build the spoken labels for the Automations
 * surface. Kept out of [AutomationsScreen] so they can be unit tested without a
 * Compose runtime, and so the exact wording lives in one auditable place.
 *
 * The Automations row is dense: an enabled/disabled state, a friendly name, an
 * entity_id, a run mode, a possible running-instance count, and a relative
 * last-triggered hint. A screen reader would otherwise announce each visible
 * fragment in isolation. These helpers fold the lot into a single comma-joined
 * phrase so TalkBack reads the whole row as one coherent unit, and convey every
 * piece of state in words rather than relying on colour (green ON vs muted OFF)
 * or glyphs (the star, the run mode badge).
 */

/** Spoken word for the run mode, e.g. "single mode" or "queued mode". The
 *  visible badge is a terse uppercase token; spell it out for the reader and
 *  omit it entirely when the mode is unknown so nothing dangling is announced. */
internal fun automationModeWord(mode: AutomationsViewModel.Mode): String =
    when (mode) {
        AutomationsViewModel.Mode.SINGLE -> "single mode"
        AutomationsViewModel.Mode.PARALLEL -> "parallel mode"
        AutomationsViewModel.Mode.QUEUED -> "queued mode"
        AutomationsViewModel.Mode.RESTART -> "restart mode"
        AutomationsViewModel.Mode.UNKNOWN -> ""
    }

/** The enabled/disabled state in words. Conveys what the green-vs-muted "ON" /
 *  "OFF" colour communicates visually, so a reader gets the state without
 *  perceiving the colour. */
internal fun automationStateWord(enabled: Boolean): String =
    if (enabled) "enabled" else "disabled"

/**
 * Spoken label for the RUN tap target, e.g. "Run Front Door Lights now". The
 * trailing "now" distinguishes a manual trigger from the row body's
 * enable/disable toggle so the two actions don't read identically.
 */
internal fun automationRunActionLabel(name: String): String = "Run $name now"

/**
 * Spoken status while a manual trigger is in flight, e.g. "Running Front Door
 * Lights". Drives the polite live region so the user hears that their RUN tap
 * registered without re-focusing the control.
 */
internal fun automationRunInFlightLabel(name: String): String = "Running $name"

/**
 * Spoken label for the pin-to-favourites button, conveying the current pinned
 * state in words (the visible cue is only the filled-vs-hollow star glyph).
 */
internal fun automationFavoriteLabel(name: String, isFavorite: Boolean): String =
    if (isFavorite) "$name pinned to favourites" else "Pin $name to favourites"

/**
 * Full row description folding state, name, mode, running-instance count, and
 * the last-triggered hint into one phrase. The row body's gesture toggles the
 * enabled state, so the description is framed as a toggle action: "Disable" when
 * currently enabled, "Enable" when currently disabled.
 *
 * [runningInstances] is the live-instance count (0 most of the time);
 * [lastTriggeredSpoken] is the already-formatted relative phrase (e.g. "5
 * minutes ago"), or null/blank when the automation has never fired.
 */
internal fun automationRowLabel(
    name: String,
    enabled: Boolean,
    mode: AutomationsViewModel.Mode,
    runningInstances: Int,
    lastTriggeredSpoken: String?,
    available: Boolean = true,
): String {
    val parts = mutableListOf<String>()
    // An unavailable automation can't be toggled or run, so frame the row as a
    // read-only status rather than a toggle action; the body opens History on a
    // tap. Long-press still drills into History for the back-story.
    if (!available) {
        val statusParts = mutableListOf("$name, unavailable")
        if (!lastTriggeredSpoken.isNullOrBlank()) {
            statusParts += "last triggered $lastTriggeredSpoken"
        }
        return statusParts.joinToString(separator = ", ")
    }
    // Lead with the toggle the row body performs, plus the current state so the
    // reader knows what tapping will do and where it starts from.
    val toggleVerb = if (enabled) "Disable" else "Enable"
    parts += "$toggleVerb $name, currently ${automationStateWord(enabled)}"
    val modeWord = automationModeWord(mode)
    if (modeWord.isNotBlank()) {
        parts += modeWord
    }
    if (runningInstances > 0) {
        val plural = if (runningInstances == 1) "instance" else "instances"
        parts += "$runningInstances running $plural"
    }
    if (!lastTriggeredSpoken.isNullOrBlank()) {
        parts += "last triggered $lastTriggeredSpoken"
    }
    return parts.joinToString(separator = ", ")
}
