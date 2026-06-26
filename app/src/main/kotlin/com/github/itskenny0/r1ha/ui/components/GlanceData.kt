package com.github.itskenny0.r1ha.ui.components

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.SecondaryInfo
import com.github.itskenny0.r1ha.core.prefs.TimestampStyle
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

/**
 * Trend direction derived from a numeric history series. Produced by [computeTrend]
 * and consumed by card-face sparkline annotations and secondary-info chips.
 */
enum class TrendArrow { UP, DOWN, FLAT }

/**
 * Paired result from [computeTrend]: a direction arrow and a pre-formatted signed delta
 * string ready for display (e.g. "+1.2 °C", "-3 %", ""). The [deltaText] is always ""
 * when [arrow] is [TrendArrow.FLAT].
 */
data class TrendResult(val arrow: TrendArrow, val deltaText: String)

/**
 * Derive a trend direction and formatted delta from a list of numeric history [points].
 * Only the first and last points form the delta; intermediate values drive the sparkline
 * shape but do not affect the trend annotation.
 *
 * Returns [TrendArrow.FLAT] with an empty [TrendResult.deltaText] when fewer than two
 * points are supplied or when the absolute change is below the noise threshold.
 *
 * Decimal formatting pins [Locale.US] so the separator is always "." regardless of the
 * device locale, matching the repo-wide convention for machine-readable numeric output.
 *
 * @param points   raw numeric samples in chronological order (oldest first).
 * @param unit     optional unit suffix ("°C", "%", "W", ...); appended with a space
 *                 when non-null and non-blank (e.g. "+1.2 °C", "-3 %").
 * @param decimals number of decimal places for the delta string; 0 = integer display.
 */
fun computeTrend(points: List<Float>, unit: String?, decimals: Int): TrendResult {
    if (points.size < 2) return TrendResult(TrendArrow.FLAT, "")
    val delta = points.last() - points.first()
    val epsilon = 1e-4f
    val arrow = when {
        delta > epsilon -> TrendArrow.UP
        delta < -epsilon -> TrendArrow.DOWN
        else -> TrendArrow.FLAT
    }
    if (arrow == TrendArrow.FLAT) return TrendResult(TrendArrow.FLAT, "")
    val sign = if (delta > 0f) "+" else ""
    val formatted = String.format(Locale.US, "%s%.${decimals}f", sign, delta)
    val text = if (!unit.isNullOrBlank()) "$formatted $unit" else formatted
    return TrendResult(arrow, text)
}

/**
 * Resolve the secondary-info line text for a card given the resolved [kind] setting.
 * Returns null when the line should be hidden ([SecondaryInfo.NONE]) or when the
 * backing data is absent for this entity (no battery attr, no media playing, etc.).
 *
 * Pure: [now] is always caller-supplied. This function never calls [Instant.now]
 * internally so it is safe under desugar environments and trivially unit-testable
 * with a fixed clock.
 *
 * @param state          current entity state snapshot.
 * @param kind           which secondary-info mode to render.
 * @param now            reference instant for relative and absolute time formatting.
 * @param timestampStyle RELATIVE for a live-ticking delta ("5m ago"), ABSOLUTE for a
 *                       wall-clock string ("14:32", "3 Jun 14:32").
 * @param zone           local time zone used only when [timestampStyle] is ABSOLUTE.
 *                       Defaults to the JVM system zone, the correct value for
 *                       on-device rendering.
 * @param use24h         whether to format times in 24-hour style under ABSOLUTE mode.
 *                       Defaults to true.
 */
fun secondaryInfoText(
    state: EntityState,
    kind: SecondaryInfo,
    now: Instant,
    timestampStyle: TimestampStyle,
    zone: ZoneId = ZoneId.systemDefault(),
    use24h: Boolean = true,
): String? = when (kind) {
    SecondaryInfo.NONE -> null

    SecondaryInfo.LAST_CHANGED ->
        if (timestampStyle == TimestampStyle.RELATIVE) {
            formatRelativeTime(state.lastChanged, now)
        } else {
            formatAbsoluteTimestamp(state.lastChanged, now, zone, use24h)
        }

    SecondaryInfo.LAST_TRIGGERED -> state.lastTriggered?.let { t ->
        if (timestampStyle == TimestampStyle.RELATIVE) {
            formatRelativeTime(t, now)
        } else {
            formatAbsoluteTimestamp(t, now, zone, use24h)
        }
    }

    SecondaryInfo.CHANGED_BY ->
        state.attrString("changed_by")?.takeIf { it.isNotBlank() }

    SecondaryInfo.BATTERY -> {
        // Prefer the explicit `battery_level` attribute (present on most battery-backed
        // devices regardless of domain). Fall back to [EntityState.percent] / [raw]
        // only when the entity is itself a battery sensor (device_class == "battery"),
        // where the numeric state IS the battery percentage.
        val fromAttr = state.attrString("battery_level")?.toDoubleOrNull()?.toInt()
        val level = fromAttr
            ?: if (state.deviceClass == "battery") (state.percent ?: state.raw?.toInt()) else null
        level?.let { "$it%" }
    }

    SecondaryInfo.MEDIA -> {
        // Join whichever of artist / title are present with a middle dot separator
        // (U+00B7). Null or blank halves are omitted so a player reporting only a
        // title does not produce a leading " · ".
        val parts = listOfNotNull(
            state.mediaArtist?.takeIf { it.isNotBlank() },
            state.mediaTitle?.takeIf { it.isNotBlank() },
        )
        if (parts.isEmpty()) null else parts.joinToString(" · ")
    }
}
