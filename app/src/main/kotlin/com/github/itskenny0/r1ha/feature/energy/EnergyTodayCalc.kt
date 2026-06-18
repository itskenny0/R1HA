package com.github.itskenny0.r1ha.feature.energy

import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import java.time.Instant

/**
 * Pure, real-time "energy used today" math from raw state history, so the TODAY
 * tile reflects a live since-midnight delta instead of HA's hourly recorder
 * statistics (which lag an hour and read empty at the very start of a day).
 *
 * For a cumulative `total_increasing` energy meter, today's consumption is the
 * rise in its reading from local midnight to now. Summing positive consecutive
 * deltas (not just last − first) is what makes this correct across a METER RESET:
 * a `total_increasing` counter can drop to 0 (device reboot, midnight cycle), and
 * HA's own `change` aggregate is defined the same way — a drop contributes 0 and
 * accumulation simply resumes from the new value. The value at/just before
 * midnight is the baseline so the window starts exactly at the day boundary.
 *
 * Kept Android-free (only [HistoryPoint] + java.time) so the delta + reset logic
 * unit-tests on a plain JVM, mirroring [EnergyTemplates] / [EnergyCsv].
 */
object EnergyTodayCalc {

    /**
     * Consumption (in the meter's native unit) for ONE sensor since [midnight],
     * given its [points] (any order). The baseline is the last numeric reading at
     * or before [midnight] (the meter's value as the day began); if every point is
     * after midnight (no pre-midnight sample) the first today point seeds the
     * baseline instead, which at worst drops the sliver of consumption before the
     * first sample. Positive consecutive deltas are summed; a negative delta (a
     * reset) contributes 0 and the running value resumes from the new reading.
     * Non-numeric / non-finite samples (unavailable, unknown) are ignored.
     */
    fun sensorConsumptionSinceMidnight(points: List<HistoryPoint>, midnight: Instant): Double {
        val numeric = points
            .filter { it.numeric != null && it.numeric.isFinite() }
            .sortedBy { it.timestamp }
        if (numeric.isEmpty()) return 0.0
        val baseline = numeric.lastOrNull { !it.timestamp.isAfter(midnight) }?.numeric
        val after = numeric.filter { it.timestamp.isAfter(midnight) }
        var prev = baseline ?: after.firstOrNull()?.numeric ?: return 0.0
        var total = 0.0
        for (p in after) {
            val v = p.numeric ?: continue
            val delta = v - prev
            if (delta > 0) total += delta
            prev = v
        }
        return total
    }

    /**
     * Total energy used today in kWh across every energy meter: each sensor's
     * [sensorConsumptionSinceMidnight] converted to kWh via its unit (Wh / MWh /
     * GWh / kWh) and summed. [unitToKwh] is injected (the VM already owns the
     * conversion table) so this file stays free of that lookup's home.
     *
     * Returns null when NO sensor yielded any history (every list empty / failed),
     * so the caller can fall back to the recorder figure rather than show a
     * spurious 0.0; a present-but-zero result (meters idle today) returns 0.0.
     */
    fun todayKwh(
        pointsById: Map<String, List<HistoryPoint>>,
        unitById: Map<String, String?>,
        midnight: Instant,
        unitToKwh: (String?) -> Double,
    ): Double? {
        if (pointsById.values.all { it.isEmpty() }) return null
        var total = 0.0
        for ((id, points) in pointsById) {
            if (points.isEmpty()) continue
            total += sensorConsumptionSinceMidnight(points, midnight) * unitToKwh(unitById[id])
        }
        return total
    }
}
