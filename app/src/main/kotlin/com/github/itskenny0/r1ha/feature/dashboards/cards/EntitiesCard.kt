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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

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
    val title = card.title?.takeUnless { it.isBlank() }
    // HA's `state_color: true` tints a row's name + state with the entity's
    // domain accent when it's "on" (lights, switches, ...). Default is false
    // (rows read neutral) except for a handful of domains HA colours anyway,
    // which our per-row accent already covers via the state chip.
    val stateColor = card.raw["state_color"]?.let { (it as? JsonPrimitive)?.booleanOrNull } ?: false
    // `show_header_toggle` surfaces a master on/off control in the header that
    // flips every toggleable entity in the card at once. HA shows it whenever
    // the card has at least one toggleable entity and the option isn't false.
    val toggleableIds = remember(card.entities) {
        card.entities.map { it.entityId }.filter { headerToggleableDomain(it) }
    }
    val showToggle = (card.showHeaderToggle ?: true) && toggleableIds.isNotEmpty()

    CardSurface(modifier = modifier, title = if (showToggle) null else title) {
        if (showToggle) {
            HeaderToggleRow(
                title = title,
                toggleableIds = toggleableIds,
                stateMap = stateMap,
                onAction = onAction,
            )
        }
        if (card.entities.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        card.entities.forEachIndexed { idx, row ->
            if (idx > 0) Divider1dp()
            EntityRowItem(row = row, stateMap = stateMap, onAction = onAction, stateColor = stateColor)
        }
    }
}

/**
 * Domains the entities-card master toggle acts on. Mirrors HA, which only
 * counts entities with a real on/off notion toward the header toggle (so a
 * card of pure sensors gets no toggle).
 */
private fun headerToggleableDomain(entityId: String): Boolean =
    when (entityId.substringBefore('.', missingDelimiterValue = "")) {
        "light", "switch", "input_boolean", "fan", "automation", "lock",
        "cover", "media_player", "humidifier", "siren", "remote", "group",
        "script" -> true
        else -> false
    }

/**
 * The master-toggle header. Shows the card title (when set) on the left and a
 * single on/off pill on the right that turns every toggleable entity on when
 * any is currently off, else turns them all off (HA's behaviour). Targets the
 * whole set in one `homeassistant.turn_on` / `turn_off` call.
 */
@Composable
private fun HeaderToggleRow(
    title: String?,
    toggleableIds: List<String>,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val anyOn = toggleableIds.any { stateMap.byRaw(it)?.isOn == true }
    val accent = if (anyOn) R1.AccentWarm else R1.InkSoft
    val service = if (anyOn) "homeassistant.turn_off" else "homeassistant.turn_on"
    val data = JsonObject(
        mapOf("entity_id" to JsonArray(toggleableIds.map { JsonPrimitive(it) })),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title ?: "",
            style = R1.sectionHeader,
            color = R1.InkSoft,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .r1Pressable(onClick = { onAction(LovelaceAction.CallService(service, null, data)) })
                .background(accent.copy(alpha = 0.16f), shape = R1.ShapeM)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = if (anyOn) "ALL OFF" else "ALL ON", style = R1.labelMicro, color = accent)
        }
    }
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun EntityRowItem(
    row: EntityRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean = false,
) {
    val state = stateMap.byRaw(row.entityId)
    val name = resolveName(row.name, state, row.entityId)
    val secondary = row.secondaryInfo?.let { secondaryInfoLine(it, state) }
    // Genuinely-absent state hides the readout rather than printing a "."
    // placeholder; a blank chip just looks like a rendering glitch.
    val stateText = state?.let { compactStateText(it) }
    val accent = stateAccentFor(row.entityId, state)
    // `state_color: true` tints the name with the entity's accent while it's
    // active; otherwise the name reads neutral ink and only the chip carries
    // colour (HA's default).
    val nameColor = if (stateColor && state?.isOn == true) accent else R1.Ink
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(defaultTapAction(row.entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = domainGlyph(row.entityId, state),
            style = R1.numeralS,
            color = accent,
            modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = nameColor,
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
        if (stateText != null) {
            Spacer(Modifier.width(10.dp))
            StateChip(text = stateText, accent = accent)
        }
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
        // No raw state to show: render an empty string; callers that can hide
        // the chip do, and an inline readout collapses to nothing rather than a dot.
        raw.isBlank() -> ""
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
 * Map HA's named card `color` (tile / button accept a theme colour name like
 * "red", "blue", "amber") to the nearest R1 accent. R1's palette is deliberately
 * small, so families collapse onto the closest token rather than reproducing
 * HA's full Material swatch set. Null / unknown names return null so the caller
 * keeps its state-derived accent.
 */
internal fun haColorAccent(name: String?): androidx.compose.ui.graphics.Color? {
    if (name.isNullOrBlank()) return null
    return when (name.trim().lowercase()) {
        "red", "pink", "deep-orange" -> R1.StatusRed
        "orange", "amber", "brown", "accent" -> R1.AccentWarm
        "yellow" -> R1.StatusAmber
        "green", "light-green", "teal", "lime" -> R1.AccentGreen
        "blue", "light-blue", "cyan", "indigo" -> R1.AccentCool
        "grey", "gray", "blue-grey", "disabled", "black", "white" -> R1.AccentNeutral
        // "primary" tracks HA's brand accent, which on R1 is the warm orange.
        "primary" -> R1.AccentWarm
        // A literal hex value HA also accepts ("#rrggbb").
        else -> parseHexColor(name.trim())
    }
}

/** Parse a `#rrggbb` / `#aarrggbb` hex string into a Compose colour, or null. */
private fun parseHexColor(s: String): androidx.compose.ui.graphics.Color? {
    if (!s.startsWith("#")) return null
    val hex = s.removePrefix("#")
    val v = hex.toLongOrNull(16) ?: return null
    return when (hex.length) {
        6 -> androidx.compose.ui.graphics.Color(0xFF000000 or v)
        8 -> androidx.compose.ui.graphics.Color(v)
        else -> null
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
        // Numeric cover/light attributes HA surfaces under the row. Each reads
        // the live attribute and renders nothing when the entity doesn't carry
        // it (so a non-cover gets no spurious "Position:" line).
        "position" -> numericAttr(state, "current_position")?.let { "Position: ${Math.round(it)}" }
        "tilt-position" -> numericAttr(state, "current_tilt_position")?.let { "Tilt: ${Math.round(it)}" }
        // HA reports brightness 0..255; show it as a percentage like the frontend.
        "brightness" -> numericAttr(state, "brightness")?.let { "${Math.round(it / 255.0 * 100)}%" }
        else -> null
    }
}

/** Read a numeric attribute out of the live attributes JSON, or null when the
 *  entity doesn't carry it. Used by the cover/light `secondary_info` variants. */
private fun numericAttr(state: EntityState, attr: String): Double? {
    val el = state.attributesJson?.get(attr) as? JsonPrimitive ?: return null
    return el.content.trim().toDoubleOrNull()
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
