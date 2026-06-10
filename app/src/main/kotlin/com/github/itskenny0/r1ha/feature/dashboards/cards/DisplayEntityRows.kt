package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.TimestampFormat
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.formatTimestamp
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId
import java.util.Locale

// ── Event entity row ─────────────────────────────────────────────────────────

/**
 * `event.*` row. HA's hui-event-entity-row: the row's state column shows the
 * event's last-fired timestamp (relative by default, overridden by `format:`),
 * and the secondary line shows the `event_type` attribute. When the state is
 * "unavailable" or "unknown" the timestamp is replaced by the raw state string.
 */
@Composable
internal fun EventEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityNotFoundRow(row.entityId)
        return
    }
    val name = resolveDisplayName(row.name, row.nameType, state, row.entityId)
    val nameColor = if (stateColor && state.isOn) accent else R1.Ink
    val secondary = row.secondaryInfo?.let { secondaryInfoLine(it, state) }
        ?: state.attrString("event_type")
    val rawState = state.rawState.orEmpty()
    val noValue = rawState.equals("unavailable", ignoreCase = true) ||
        rawState.equals("unknown", ignoreCase = true)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = cardEntityIcon(row.entityId, state, row.icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        if (noValue) {
            StateChip(text = rawState, accent = R1.InkMuted)
        } else {
            val fmt = row.format ?: TimestampFormat.RELATIVE
            val at = parseHaInstant(rawState)
            if (at != null) {
                LiveTimestampChip(at = at, format = fmt, accent = accent)
            } else {
                StateChip(text = rawState, accent = accent)
            }
        }
    }
}

// ── Weather entity row ───────────────────────────────────────────────────────

/**
 * `weather.*` row. Shows the condition icon, the entity name, and the
 * temperature as the primary state chip. The secondary line mirrors
 * HA's getSecondaryWeatherAttribute: humidity + wind speed when available
 * (same logic as the weather card's detail line).
 */
@Composable
internal fun WeatherEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityNotFoundRow(row.entityId)
        return
    }
    val name = resolveDisplayName(row.name, row.nameType, state, row.entityId)
    val nameColor = if (stateColor && state.isOn) accent else R1.Ink
    // Secondary: secondary_info override if set, otherwise humidity + wind.
    val secondary = row.secondaryInfo?.let { secondaryInfoLine(it, state) }
        ?: weatherSecondaryLine(state)
    // Primary state: temperature + unit if available; fall back to raw state.
    val tempVal = state.attrString("temperature")?.toDoubleOrNull()
    val tempUnit = state.attrString("temperature_unit") ?: "°"
    val stateText: String = when {
        !state.isAvailable -> "unavailable"
        state.rawState.equals("unknown", ignoreCase = true) -> "unknown"
        tempVal != null -> "${"%.0f".format(Locale.US, tempVal)}$tempUnit"
        else -> state.rawState.orEmpty()
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = cardEntityIcon(row.entityId, state, row.icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (stateText.isNotBlank()) {
            Spacer(Modifier.width(10.dp))
            StateChip(text = stateText, accent = accent)
        }
    }
}

/**
 * Secondary line for the weather row. Mirrors HA's getSecondaryWeatherAttribute:
 * humidity and/or wind speed, each omitted when the integration doesn't report
 * them. Returns null when neither is present.
 */
internal fun weatherRowSecondaryLine(state: EntityState): String? {
    val humidity = state.attrString("humidity")?.toDoubleOrNull()
    val wind = state.attrString("wind_speed")?.toDoubleOrNull()
    val windUnit = state.attrString("wind_speed_unit")
    return weatherSecondaryLine(state, humidity, wind, windUnit)
}

private fun weatherSecondaryLine(
    state: EntityState,
    humidity: Double? = state.attrString("humidity")?.toDoubleOrNull(),
    wind: Double? = state.attrString("wind_speed")?.toDoubleOrNull(),
    windUnit: String? = state.attrString("wind_speed_unit"),
): String? {
    val parts = mutableListOf<String>()
    if (humidity != null) parts += "Humidity ${"%.0f".format(Locale.US, humidity)}%"
    if (wind != null) {
        val unit = windUnit?.takeIf { it.isNotBlank() }?.let { " $it" } ?: ""
        parts += "Wind ${"%.0f".format(Locale.US, wind)}$unit"
    }
    return parts.takeIf { it.isNotEmpty() }?.joinToString("  ·  ")
}

// ── Timer entity row ─────────────────────────────────────────────────────────

/**
 * `timer.*` row. HA's hui-timer-entity-row: shows the remaining time while
 * active (live countdown), the frozen remaining time when paused, and the
 * entity's raw state ("idle") when idle. The countdown is computed from the
 * `finishes_at` attribute (an ISO-8601 timestamp of when the active timer
 * completes) minus the current time. When `finishes_at` is absent or invalid,
 * the `remaining` attribute (HH:MM:SS) is used directly.
 */
@Composable
internal fun TimerEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    if (state == null) {
        EntityNotFoundRow(row.entityId)
        return
    }
    val name = resolveDisplayName(row.name, row.nameType, state, row.entityId)
    val nameColor = if (stateColor && state.isOn) accent else R1.Ink
    val secondary = row.secondaryInfo?.let { secondaryInfoLine(it, state) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = cardEntityIcon(row.entityId, state, row.icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = nameColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        TimerChip(state = state, accent = accent)
    }
}

/**
 * Live countdown chip for a timer entity. Ticks every second while active.
 * Paused shows the frozen remaining. Idle shows the raw state ("idle").
 */
@Composable
private fun TimerChip(state: EntityState, accent: androidx.compose.ui.graphics.Color) {
    val rawState = state.rawState.orEmpty()
    val isActive = rawState.equals("active", ignoreCase = true)
    val isPaused = rawState.equals("paused", ignoreCase = true)

    val text by produceState(
        initialValue = timerDisplayText(state, Instant.now()),
        state,
    ) {
        while (true) {
            value = timerDisplayText(state, Instant.now())
            delay(if (isActive) 1_000L else 60_000L)
        }
    }
    StateChip(text = text, accent = if (isActive) accent else R1.InkSoft)
}

/**
 * Pure: compute the timer chip text for a given [now].
 * Active: live countdown from `finishes_at` (ISO-8601) or `remaining` attr.
 * Paused: frozen `remaining` attribute.
 * Idle: raw state string.
 */
internal fun timerDisplayText(state: EntityState, now: Instant): String {
    val rawState = state.rawState.orEmpty()
    val isActive = rawState.equals("active", ignoreCase = true)
    val isPaused = rawState.equals("paused", ignoreCase = true)
    return when {
        isActive -> {
            val finishesAt = state.attrString("finishes_at")?.let { parseHaInstant(it) }
            if (finishesAt != null) {
                val remainMs = finishesAt.toEpochMilli() - now.toEpochMilli()
                formatTimerTotal(remainMs.coerceAtLeast(0) / 1000)
            } else {
                // Fall back to the `remaining` attribute which HA sets at start.
                state.attrString("remaining") ?: rawState
            }
        }
        isPaused -> state.attrString("remaining") ?: rawState
        else -> rawState
    }
}

/**
 * Format total seconds as HH:MM:SS (or H:MM:SS when hours >= 10, etc.).
 * Matches HA's TOTAL format used by the timer remaining display.
 */
internal fun formatTimerTotal(totalSeconds: Long): String {
    val secs = totalSeconds.coerceAtLeast(0)
    val h = secs / 3600
    val m = (secs % 3600) / 60
    val s = secs % 60
    return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
}

// ── Live timestamp chip (shared by event + future sensor display) ────────────

/**
 * A state chip whose text is formatted from a live or static [Instant].
 * RELATIVE and TOTAL formats tick every second; DATE / TIME / DATETIME are
 * static strings (they change on day/minute boundaries but we re-evaluate on
 * the second tick anyway for simplicity).
 *
 * Formatting delegates to the shared timestamp engine in
 * [com.github.itskenny0.r1ha.ui.components.formatTimestamp] (Batch D), the single
 * source of truth for the five [TimestampFormat] variants.
 */
@Composable
internal fun LiveTimestampChip(
    at: Instant,
    format: TimestampFormat,
    accent: androidx.compose.ui.graphics.Color,
) {
    val use24h = rememberUse24HourClock()
    val text by produceState(
        initialValue = formatTimestamp(at, format, Instant.now(), ZoneId.systemDefault(), use24h),
        at, format, use24h,
    ) {
        while (true) {
            value = formatTimestamp(at, format, Instant.now(), ZoneId.systemDefault(), use24h)
            delay(if (format == TimestampFormat.RELATIVE || format == TimestampFormat.TOTAL) 1_000L else 30_000L)
        }
    }
    StateChip(text = text, accent = accent)
}
