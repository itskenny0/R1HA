package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Renderer for HA's `entities` card. A vertical list of compact entity
 * rows topped by an optional title. Each row shows the friendly name +
 * a small accent-coloured state pill on the right; tapping the row
 * fires the entity's default tap action (toggle / press / more-info).
 *
 * Visual idiom: pull from R1's industrial chrome. Surface fill with a
 * single-pixel hairline + 1dp inter-row dividers. No Material elevation;
 * the card is a plain panel, not a popped surface.
 */
@Composable
fun EntitiesCard(
    card: LovelaceCard.Entities,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        if (card.entities.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        card.entities.forEachIndexed { idx, row ->
            if (idx > 0) Divider1dp()
            EntityRowItem(row = row, stateMap = stateMap, onAction = onAction)
        }
    }
}

@Composable
private fun EntityRowItem(
    row: EntityRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val eid = safeEntityId(row.entityId)
    val state = eid?.let { stateMap[it] }
    val name = resolveName(row.name, state, row.entityId)
    val secondary = row.secondaryInfo?.let { secondaryInfoLine(it, state) }
    val stateText = state?.let { compactStateText(it) } ?: ". "
    val accent = stateAccentFor(row.entityId, state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            if (!secondary.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = secondary,
                    style = R1.body,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        StateChip(text = stateText, accent = accent)
    }
}

@Composable
internal fun StateChip(text: String, accent: androidx.compose.ui.graphics.Color) {
    Box(
        modifier = Modifier
            .background(accent.copy(alpha = 0.16f), shape = R1.ShapeM)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(text = text.uppercase(), style = R1.labelMicro, color = accent)
    }
}

@Composable
private fun Divider1dp() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}

@Composable
internal fun EmptyRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.body, color = R1.InkMuted)
    }
}

/** Resolve the small state chip's text for an entity. Uses the friendly
 *  state value (HA's `friendly_state` is computed client-side from the
 *  raw state) so booleans read as "on" / "off" rather than "true". */
internal fun compactStateText(state: EntityState): String {
    if (!state.isAvailable) return "unavailable"
    val raw = state.rawState.orEmpty()
    return when {
        raw.equals("on", ignoreCase = true) -> "on"
        raw.equals("off", ignoreCase = true) -> "off"
        raw.equals("home", ignoreCase = true) -> "home"
        raw.equals("not_home", ignoreCase = true) -> "away"
        raw.equals("unknown", ignoreCase = true) -> "unknown"
        state.unit != null && raw.toDoubleOrNull() != null -> "$raw ${state.unit}"
        raw.isBlank() -> ". "
        else -> raw
    }
}

/**
 * Choose the chip accent. warm for "on" / "active" states, soft for
 * neutral / off, red for unavailable, neutral grey for unknown. Done
 * by entity_id rather than EntityState alone so a row with no live
 * state still gets a sensible muted treatment.
 */
internal fun stateAccentFor(entityId: String, state: EntityState?): androidx.compose.ui.graphics.Color {
    if (state == null) return R1.InkMuted
    if (!state.isAvailable) return R1.StatusRed
    val raw = state.rawState.orEmpty()
    return when {
        state.isOn -> R1.AccentWarm
        raw.equals("off", ignoreCase = true) -> R1.InkSoft
        raw.equals("home", ignoreCase = true) -> R1.AccentGreen
        raw.equals("unknown", ignoreCase = true) -> R1.InkMuted
        else -> R1.AccentCool
    }
}

/**
 * Map HA's `secondary_info` enum to a small one-line readout. Falls
 * through to null (= "render nothing") for variants we can't compute
 * locally, which the row reads as "don't show the second line".
 */
internal fun secondaryInfoLine(kind: String, state: EntityState?): String? {
    if (state == null) return null
    return when (kind) {
        "entity-id" -> state.id.value
        "area" -> state.area
        "state" -> state.rawState
        "last-changed", "last-triggered", "last-updated" -> "since " + relativeTimeShort(state.lastChanged)
        else -> null
    }
}

private fun relativeTimeShort(t: java.time.Instant): String {
    val now = java.time.Instant.now()
    val secs = java.time.Duration.between(t, now).seconds.coerceAtLeast(0)
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        else -> "${secs / 86_400}d"
    }
}
