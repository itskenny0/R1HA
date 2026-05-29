package com.github.itskenny0.r1ha.feature.scenes

/**
 * Pure, Compose-free helpers that build the spoken labels for the Scenes and
 * Scripts surface. Kept out of [ScenesScreen] so they can be unit tested without
 * a Compose runtime, and so the exact wording is asserted in one place.
 *
 * The verbs are chosen per kind: a scene is "activated", a script is "run". The
 * row label folds the name, kind, in-flight status, and the last-activated hint
 * into a single comma-separated phrase so TalkBack reads the whole card as one
 * coherent unit instead of disjoint fragments.
 */

/** Human label for an entry kind, e.g. "scene" or "script" (lower-case for inlining). */
internal fun sceneKindWord(kind: ScenesViewModel.Kind): String =
    when (kind) {
        ScenesViewModel.Kind.SCENE -> "scene"
        ScenesViewModel.Kind.SCRIPT -> "script"
    }

/**
 * Accessibility label for the tap target on a row, e.g. "Activate Movie Night
 * scene" or "Run Nightly Backup script". This is what a screen reader announces
 * for the otherwise icon-free, tap-to-fire row.
 */
internal fun sceneFireActionLabel(name: String, kind: ScenesViewModel.Kind): String {
    val verb = when (kind) {
        ScenesViewModel.Kind.SCENE -> "Activate"
        ScenesViewModel.Kind.SCRIPT -> "Run"
    }
    return "$verb $name ${sceneKindWord(kind)}"
}

/**
 * Spoken status while an entry's turn_on is in flight, e.g. "Activating Movie
 * Night" or "Running Nightly Backup". Drives the polite live region so the user
 * hears that their tap registered.
 */
internal fun sceneInFlightLabel(name: String, kind: ScenesViewModel.Kind): String =
    when (kind) {
        ScenesViewModel.Kind.SCENE -> "Activating $name"
        ScenesViewModel.Kind.SCRIPT -> "Running $name"
    }

/**
 * Full row description combining the fire action, in-flight status, and the
 * last-activated hint. [lastActivatedSpoken] is the already-formatted relative
 * phrase (e.g. "5 minutes ago"), or null/blank when the entry has never run or
 * the timestamp is unknown.
 */
internal fun sceneRowLabel(
    name: String,
    kind: ScenesViewModel.Kind,
    firing: Boolean,
    lastActivatedSpoken: String?,
): String {
    val parts = mutableListOf<String>()
    if (firing) {
        parts += sceneInFlightLabel(name, kind)
    } else {
        parts += sceneFireActionLabel(name, kind)
    }
    if (!lastActivatedSpoken.isNullOrBlank()) {
        parts += "last activated $lastActivatedSpoken"
    }
    return parts.joinToString(separator = ", ")
}
