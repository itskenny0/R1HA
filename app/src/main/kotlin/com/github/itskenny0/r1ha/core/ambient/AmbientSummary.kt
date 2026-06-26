package com.github.itskenny0.r1ha.core.ambient

/**
 * Snapshot of the small data set the ambient idle face renders. Every field is
 * nullable / zero-defaulted so a partial or failed fetch degrades gracefully
 * (a missing line just does not render) and a default instance is a valid
 * "nothing known yet" state.
 */
@androidx.compose.runtime.Immutable
data class AmbientSummary(
    val weatherName: String? = null,
    val condition: String? = null,
    val temperature: Double? = null,
    val temperatureUnit: String? = null,
    val apparentTemperature: Double? = null,
    val lightsOn: Int? = null,
    val personsHome: Int? = null,
    val powerWatts: Double? = null,
    val alertCount: Int = 0,
    val activeTimerLabel: String? = null,
)

/** Pure parsers for the plain-text bodies HA's /api/template returns. */
object AmbientParse {
    fun firstInt(raw: String?): Int? = raw?.trim()?.toIntOrNull()
    fun firstDouble(raw: String?): Double? = raw?.trim()?.toDoubleOrNull()
}
