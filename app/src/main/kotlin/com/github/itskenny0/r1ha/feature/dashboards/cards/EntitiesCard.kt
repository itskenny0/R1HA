package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntitiesItem
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.SpecialRow
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.formatWithPrecision
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.resolveTimestampFormat
import com.github.itskenny0.r1ha.ui.components.timestampInstantOrNull
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
    val toggleableIds = remember(card.rowItems) {
        card.rowItems.filterIsInstance<EntitiesItem.Entity>()
            .map { it.row.entityId }.filter { headerToggleableDomain(it) }
    }
    val showToggle = (card.showHeaderToggle ?: true) && toggleableIds.isNotEmpty()
    // HA's entities card draws an optional card-level `icon:` next to the title
    // (hui-entities-card.ts). When set we render the title row ourselves (icon +
    // text) and pass title=null to the surface so it isn't drawn twice.
    val titleIcon = (card.raw["icon"] as? JsonPrimitive)?.content
    val ownTitleRow = !showToggle && title != null && titleIcon != null
    CardSurface(modifier = modifier, title = if (showToggle || ownTitleRow) null else title) {
        // HA `header:` slot renders above the card body (and above any title's
        // rows). The shared header-footer subsystem dispatches the slot type.
        card.header?.let { header ->
            CardHeaderFooterSlot(header, stateMap, onAction)
            Spacer(Modifier.height(4.dp))
        }
        if (ownTitleRow) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Icon(
                    imageVector = cardEntityIcon(entityId = "", state = null, configIcon = titleIcon),
                    contentDescription = null,
                    tint = R1.InkSoft,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = title!!,
                    style = R1.sectionHeader,
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(2.dp))
        }
        if (showToggle) {
            HeaderToggleRow(
                title = title,
                toggleableIds = toggleableIds,
                stateMap = stateMap,
                onAction = onAction,
            )
        }
        if (card.rowItems.isEmpty()) {
            EmptyRow(text = "No entities configured")
        } else {
            // Dividers are drawn between consecutive entity/non-divider rows only;
            // section headers and dividers themselves don't get an extra separator.
            var needsDivider = false
            card.rowItems.forEach { item ->
                when (item) {
                    is EntitiesItem.Entity -> {
                        if (needsDivider) Divider1dp()
                        EntityRowItem(row = item.row, stateMap = stateMap, onAction = onAction, stateColor = stateColor)
                        needsDivider = true
                    }
                    is EntitiesItem.Special -> {
                        // Section and divider rows reset the divider so no double-line appears.
                        val isSeparator = item.row is SpecialRow.Section || item.row is SpecialRow.Divider
                        if (needsDivider && !isSeparator) Divider1dp()
                        SpecialRowItem(
                            row = item.row,
                            stateMap = stateMap,
                            onAction = onAction,
                            stateColor = stateColor,
                        )
                        needsDivider = !isSeparator
                    }
                }
            }
        }
        // HA `footer:` slot renders below the card body.
        card.footer?.let { footer ->
            Spacer(Modifier.height(4.dp))
            CardHeaderFooterSlot(footer, stateMap, onAction)
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
        // HA's header control is a real toggle switch, not a labelled pill: it
        // reads "on" when ANY toggleable entity is on, and flipping it turns the
        // whole set on / off in one call. We render an actual switch track + thumb
        // so it reads like the frontend's control.
        ToggleSwitch(
            checked = anyOn,
            onClick = { onAction(LovelaceAction.CallService(service, null, data)) },
        )
    }
    Spacer(Modifier.height(2.dp))
}

/**
 * A compact on/off switch in the R1 idiom: a pill track whose fill + thumb
 * slide between off (muted, thumb left) and on (warm accent, thumb right).
 * Mirrors HA's entities-card header toggle, which is a real `ha-switch` rather
 * than a text button.
 */
@Composable
internal fun ToggleSwitch(checked: Boolean, onClick: () -> Unit) {
    val trackColor = if (checked) R1.AccentWarm.copy(alpha = 0.5f) else R1.SurfaceMuted
    val thumbColor = if (checked) R1.AccentWarm else R1.InkSoft
    Box(
        modifier = Modifier
            .r1Pressable(onClick = onClick)
            .size(width = 40.dp, height = 24.dp)
            .background(trackColor, shape = androidx.compose.foundation.shape.CircleShape)
            .padding(2.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .background(thumbColor, shape = androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/**
 * Dispatch one entities-card row to the interactive per-domain renderer for its
 * domain (EntityRows.kt), to the event / weather / timer display rows, or to the
 * generic read-only display row for sensor-style domains. An explicit per-row
 * `type:` override (e.g. "toggle", "simple") forces the generic renderer
 * regardless of domain, matching HA's create-row-element behaviour. An entity HA
 * doesn't serve renders the not-found warning row rather than crashing.
 */
@Composable
internal fun EntityRowItem(
    row: EntityRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean = false,
) {
    val state = stateMap.byRaw(row.entityId)
    val accent = stateAccentFor(row.entityId, state)
    // An explicit per-row `type:` forces the generic display row and skips domain
    // dispatch (matches HA's create-row-element: the config type beats the domain).
    // Unknown custom types (e.g. "custom:my-row") also fall through to generic.
    if (row.explicitType != null) {
        DisplayEntityRow(row, state, accent, onAction, stateColor, explicitType = row.explicitType)
        return
    }
    when (rowKindFor(row.entityId)) {
        RowKind.Toggle -> ToggleEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Button -> ButtonEntityRow(row, state, accent, onAction, stateColor, pressService = "button.press")
        RowKind.InputButton -> ButtonEntityRow(row, state, accent, onAction, stateColor, pressService = "input_button.press")
        RowKind.Climate -> ClimateEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Cover -> CoverEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Group -> GroupEntityRow(row, state, accent, onAction, stateColor) { gid ->
            stateMap.byRaw(gid)?.let { groupMembers(it) }
        }
        RowKind.Humidifier -> HumidifierEntityRow(row, state, accent, onAction, stateColor)
        RowKind.InputDatetime -> InputDatetimeEntityRow(row, state, accent, onAction, stateColor)
        RowKind.InputNumber -> NumberEntityRow(row, state, accent, onAction, stateColor, isInputNumber = true)
        RowKind.InputSelect -> SelectEntityRow(row, state, accent, onAction, stateColor, service = "input_select.select_option")
        RowKind.InputText -> InputTextEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Lock -> LockEntityRow(row, state, accent, onAction, stateColor)
        RowKind.MediaPlayer -> MediaPlayerEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Number -> NumberEntityRow(row, state, accent, onAction, stateColor, isInputNumber = false)
        RowKind.Scene -> SceneEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Script -> ScriptEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Select -> SelectEntityRow(row, state, accent, onAction, stateColor, service = "select.select_option")
        RowKind.Update -> UpdateEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Valve -> ValveEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Event -> EventEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Weather -> WeatherEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Timer -> TimerEntityRow(row, state, accent, onAction, stateColor)
        RowKind.Display -> DisplayEntityRow(row, state, accent, onAction, stateColor)
    }
}

/**
 * Read-only display row for sensor-style domains: the generic scaffold plus a
 * state chip. Mirrors the previous EntitiesCard rendering for those rows. An
 * entity HA doesn't serve renders the not-found warning instead. An explicit
 * per-row `type:` we don't model renders a muted placeholder chip rather than
 * the live state, matching HA's "unsupported row" treatment.
 */
@Composable
private fun DisplayEntityRow(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
    explicitType: String? = null,
) {
    if (state == null) {
        EntityNotFoundRow(row.entityId)
        return
    }
    // An explicit `type:` we don't model: render a muted "type" label in the chip
    // area instead of the live state, so an unsupported custom row is visible.
    val isUnknownType = explicitType != null &&
        explicitType !in setOf("toggle", "simple", "display", "button", "lock", "cover")
    val stateText = compactStateText(state)
    EntityRowScaffold(row, state, accent, onAction, stateColor) {
        if (isUnknownType) {
            StateChip(text = explicitType ?: "?", accent = R1.InkMuted)
            return@EntityRowScaffold
        }
        // Timestamp/uptime sensors tick live; everything else is a plain chip.
        val tsFormat = resolveTimestampFormat(row.format, state.deviceClass)
        val tsInstant = if (tsFormat != null) {
            timestampInstantOrNull(state.deviceClass, state.rawState)
        } else null
        if (tsInstant != null && tsFormat != null) {
            LiveTimestampChip(at = tsInstant, format = tsFormat, accent = accent)
        } else if (stateText.isNotBlank()) {
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
        state.unit != null && raw.toDoubleOrNull() != null ->
            "${formatWithPrecision(raw, state.displayPrecision)} ${state.unit}"
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
        "last-changed" -> "since " + relativeTimeShort(state.lastChanged)
        // last-triggered: automation / script "last ran" time. Falls back to
        // last-changed when the entity has never been triggered (lastTriggered null).
        "last-triggered" -> "since " + relativeTimeShort(state.lastTriggered ?: state.lastChanged)
        // last-updated: HA's wire field distinct from last-changed. EntityState does not
        // carry it yet (only lastChanged is mapped); fall back to lastChanged so the
        // label is accurate for states that HA doesn't suppress re-reports on, and
        // silently omit rather than crash. This is a known delta vs. HA's frontend.
        "last-updated" -> "since " + relativeTimeShort(state.lastChanged)
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

internal fun relativeTimeShort(
    t: java.time.Instant,
    now: java.time.Instant = java.time.Instant.now(),
): String {
    val secs = java.time.Duration.between(t, now).seconds.coerceAtLeast(0)
    // Same magnitude buckets as the shared RelativeTime component (minus its
    // ago/in affix), so a long-idle entity reads "1y", not "365d".
    return when {
        secs < 60 -> "${secs}s"
        secs < 3600 -> "${secs / 60}m"
        secs < 86_400 -> "${secs / 3600}h"
        secs < 7 * 86_400 -> "${secs / 86_400}d"
        secs < 30 * 86_400 -> "${secs / (7 * 86_400)}w"
        secs < 365 * 86_400 -> "${secs / (30 * 86_400)}mo"
        else -> "${secs / (365 * 86_400)}y"
    }
}
