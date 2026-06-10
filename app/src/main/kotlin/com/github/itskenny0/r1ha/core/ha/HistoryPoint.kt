package com.github.itskenny0.r1ha.core.ha

import java.time.Instant

/**
 * One state-change snapshot from HA's `/api/history/period/...` endpoint. The raw state
 * is kept as a string (HA's wire format) so the consumer decides whether to parse it as
 * a number for chart rendering or leave it as-is for text history. `numeric` is a
 * convenience: pre-parsed Double, null if the state isn't numeric.
 */
data class HistoryPoint(
    val timestamp: Instant,
    val state: String,
    val numeric: Double?,
) {
    companion object {
        fun fromRaw(state: String, timestamp: Instant): HistoryPoint =
            HistoryPoint(
                timestamp = timestamp,
                state = state,
                numeric = state.toDoubleOrNull()?.takeIf { it.isFinite() },
            )
    }
}

/**
 * One timestamped GPS fix from an attribute-bearing history fetch. Used to draw
 * the map card's location trail (a polyline of an entity's past positions).
 */
data class LocationFix(
    val timestamp: Instant,
    val latitude: Double,
    val longitude: Double,
)
