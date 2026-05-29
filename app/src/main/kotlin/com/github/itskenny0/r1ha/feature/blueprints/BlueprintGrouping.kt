package com.github.itskenny0.r1ha.feature.blueprints

import com.github.itskenny0.r1ha.core.ha.BlueprintInfo
import java.util.Locale

/**
 * Pure (no Android / no coroutine) helpers that turn the two parallel
 * `blueprint/list` round-trips into the sectioned UI state the screen renders.
 *
 * Kept out of [BlueprintsViewModel] so the fold logic (partial-failure
 * tolerance, stable ordering, source/description fallbacks) is unit-testable
 * without a fake repository or a coroutine harness.
 */
object BlueprintGrouping {

    /**
     * Outcome of folding the automation + script list results together.
     *
     * [error] is non-null only when BOTH domain calls failed; a partial
     * result (one bucket fails, the other succeeds, e.g. an old HA install
     * that refuses the script domain) still renders whatever did load so the
     * surface degrades gracefully instead of going dark.
     */
    data class Grouped(
        val automations: List<BlueprintInfo>,
        val scripts: List<BlueprintInfo>,
        val error: String?,
    ) {
        val totalCount: Int get() = automations.size + scripts.size
    }

    /**
     * Fold the per-domain results into a [Grouped]. Each bucket is sorted by
     * display name (case-insensitive, [Locale.US]) so the list ordering is
     * deterministic regardless of the map iteration order HA hands back.
     */
    fun group(
        automationResult: Result<List<BlueprintInfo>>,
        scriptResult: Result<List<BlueprintInfo>>,
    ): Grouped {
        val autos = automationResult.getOrNull().orEmpty().sortedByName()
        val scripts = scriptResult.getOrNull().orEmpty().sortedByName()
        val bothFailed = automationResult.isFailure && scriptResult.isFailure
        val error = if (bothFailed) {
            firstErrorMessage(automationResult, scriptResult)
        } else {
            null
        }
        return Grouped(automations = autos, scripts = scripts, error = error)
    }

    /**
     * Extract a human message from the first failed result, falling back to a
     * generic label when an exception carries no message.
     */
    fun firstErrorMessage(vararg results: Result<*>): String? =
        results.firstOrNull { it.isFailure }
            ?.exceptionOrNull()
            ?.let { it.message?.takeIf(String::isNotBlank) ?: "Unknown error" }

    /** Deterministic display order: name first (case-insensitive), then path
     *  as a tiebreaker so two blueprints with the same name don't flicker. */
    fun List<BlueprintInfo>.sortedByName(): List<BlueprintInfo> =
        sortedWith(
            compareBy(
                { it.name.lowercase(Locale.US) },
                { it.path.lowercase(Locale.US) },
            ),
        )

    /**
     * Pluralized INPUT chip label: "1 INPUT" vs "3 INPUTS". Returns null when
     * a blueprint declares no inputs so the caller can skip the chip entirely.
     */
    fun inputChipLabel(inputCount: Int): String? = when {
        inputCount <= 0 -> null
        inputCount == 1 -> "1 INPUT"
        else -> "$inputCount INPUTS"
    }

    /**
     * Whether a previewed blueprint can be committed via `blueprint/save`:
     * HA returned the verbatim YAML, suggested a target path, and reported no
     * validation errors. Drives the INSTALL button's enabled state.
     */
    fun canInstall(preview: BlueprintInfo?): Boolean =
        preview != null &&
            !preview.rawYaml.isNullOrBlank() &&
            preview.path.isNotBlank() &&
            preview.validationErrors.isNullOrBlank()
}
