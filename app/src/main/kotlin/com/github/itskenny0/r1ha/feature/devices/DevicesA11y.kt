package com.github.itskenny0.r1ha.feature.devices

import java.util.Locale

/**
 * Pure, Compose-free label builders for the Devices browser and drill-in.
 * Kept out of the screen files so the exact spoken wording is asserted in one
 * place and unit-tested without a Compose runtime. Every label spells out the
 * meaning in words (entity count, disabled-state, area, live state) so a screen
 * reader never has to infer anything from colour alone.
 */
object DevicesA11y {

    /**
     * Merged content description for a device row in the list, e.g.
     * "Living room lamp, 4 entities, in Kitchen, by Philips, opens device".
     * Disabled devices are announced as disabled rather than just dimmed; the
     * trailing hint marks the row as a drill-in target.
     */
    fun deviceRowDescription(
        name: String,
        entityCount: Int,
        areaName: String?,
        manufacturer: String?,
        model: String?,
        disabled: Boolean,
    ): String {
        val parts = mutableListOf(name)
        if (disabled) parts += "disabled"
        parts += entityCountPhrase(entityCount)
        areaName?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "in $it" }
        manufacturer?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += "by $it" }
        model?.trim()?.takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += "opens device"
        return parts.joinToString(", ")
    }

    /** "1 entity" / "5 entities" / "no entities", pluralised for speech. */
    fun entityCountPhrase(count: Int): String = when {
        count <= 0 -> "no entities"
        count == 1 -> "1 entity"
        else -> "$count entities"
    }

    /**
     * Spoken coverage phrase for the drill-in entities header, e.g. "3 of 5
     * reporting". Live state only arrives for entities HA is actively
     * reporting, so spelling out the ratio keeps the count honest about how
     * much of the device is actually live. Returns null when every entity is
     * reporting (full coverage needs no caveat) or there are no entities.
     */
    fun reportingCoverageDescription(reporting: Int, total: Int): String? {
        if (total <= 0 || reporting >= total) return null
        return "$reporting of $total reporting"
    }

    /**
     * Spoken header for a section in the list, e.g. "Kitchen, 3 devices". The
     * visible header shows the same label and a count pill, but the pill's digit
     * alone is not announced as part of the heading without this.
     */
    fun sectionHeaderDescription(label: String, count: Int): String {
        val noun = if (count == 1) "device" else "devices"
        return "$label, $count $noun"
    }

    /**
     * Spoken description for the device-detail status row, e.g.
     * "battery 84 percent, charging, 2 of 5 entities unavailable". Spells out
     * the glyph-y chip text ("84%+", "2 UNAVAILABLE") so a screen reader user
     * gets the meaning, not the abbreviation. Returns null when there's nothing
     * to announce (no battery, nothing offline, not disabled).
     */
    fun deviceHealthDescription(
        disabled: Boolean,
        batteryPercent: Int?,
        charging: Boolean,
        unavailableCount: Int,
        liveCount: Int,
    ): String? {
        val parts = mutableListOf<String>()
        if (disabled) parts += "disabled"
        batteryPercent?.let {
            parts += "battery $it percent"
            if (charging) parts += "charging"
        }
        if (unavailableCount > 0) {
            parts += "$unavailableCount of ${entityCountPhrase(liveCount)} unavailable"
        }
        return if (parts.isEmpty()) null else parts.joinToString(", ")
    }

    /**
     * Merged content description for an entity row on the drill-in, e.g.
     * "Ceiling light, on" or "Outdoor temperature, 21.4 degrees, disabled".
     * [stateSpoken] is the already-resolved live-state phrase ("no live state"
     * when HA isn't reporting one); [tags] are extra registry markers
     * (platform, disabled, hidden) folded in so they are spoken, not just shown.
     */
    fun entityRowDescription(
        name: String,
        entityId: String,
        stateSpoken: String,
        tags: List<String>,
    ): String {
        val parts = mutableListOf(name)
        stateSpoken.trim().takeIf { it.isNotEmpty() }?.let { parts += it }
        parts += entityId
        tags.mapNotNull { it.trim().takeIf { t -> t.isNotEmpty() } }
            .forEach { parts += it.lowercase(Locale.US) }
        return parts.joinToString(", ")
    }
}
