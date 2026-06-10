package com.github.itskenny0.r1ha.feature.cameras

/**
 * Pure, Compose-free helpers that build the spoken labels for the Cameras
 * surface. Kept out of [CamerasScreen] so the exact wording is asserted in
 * unit tests without a Compose runtime.
 */

/**
 * Spoken phrase for the two-pane summary, e.g. "Cameras. 8 cameras, 3
 * streaming, 1 recording, 2 offline. Select a camera for a live view." The
 * status counts mirror the visible stat cluster (including zeros, so the
 * reader hears that nothing is offline rather than wondering), folded into
 * one sentence instead of four bare numerals + four bare labels.
 */
internal fun camerasSummaryDescription(
    total: Int,
    streaming: Int,
    recording: Int,
    offline: Int,
): String {
    val totalPhrase = when {
        total <= 0 -> "no cameras"
        total == 1 -> "1 camera"
        else -> "$total cameras"
    }
    return "Cameras. $totalPhrase, $streaming streaming, $recording recording, " +
        "$offline offline. Select a camera for a live view."
}
