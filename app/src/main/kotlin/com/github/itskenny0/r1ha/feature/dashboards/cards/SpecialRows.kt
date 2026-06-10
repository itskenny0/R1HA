package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.core.lovelace.ConditionalRowPayload
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.lovelace.SpecialRow
import com.github.itskenny0.r1ha.core.lovelace.evaluateLovelaceConditions
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.attrString
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Dispatch one special row to its typed renderer. Called from the entities card's
 * main loop for every [EntitiesItem.Special] in the card's row list.
 */
@Composable
internal fun SpecialRowItem(
    row: SpecialRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    when (row) {
        is SpecialRow.Section -> SectionRow(row)
        is SpecialRow.Divider -> DividerRow()
        is SpecialRow.Attribute -> AttributeRow(row, stateMap, onAction)
        is SpecialRow.Button -> ButtonRow(row, stateMap, onAction)
        is SpecialRow.Buttons -> ButtonsRow(row, stateMap, onAction)
        is SpecialRow.Conditional -> ConditionalRow(row, stateMap, onAction, stateColor)
        is SpecialRow.Text -> TextRow(row)
        is SpecialRow.Weblink -> WeblinkRow(row, onAction)
        is SpecialRow.Cast -> CastPlaceholderRow()
        is SpecialRow.Unknown -> UnknownRow(row.typeName)
    }
}

// ── Section row ───────────────────────────────────────────────────────────────

/**
 * `type: section` — a full-width hairline rule optionally headed by a label.
 * When [row.label] is set it renders above the rule as a small section header;
 * a section without a label is just a visual break exactly like a divider.
 */
@Composable
private fun SectionRow(row: SpecialRow.Section) {
    Column(modifier = Modifier.fillMaxWidth()) {
        if (!row.label.isNullOrBlank()) {
            Text(
                text = row.label,
                style = R1.sectionHeader,
                color = R1.InkSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
    }
}

// ── Divider row ───────────────────────────────────────────────────────────────

/** `type: divider` — a single hairline rule with no label. */
@Composable
private fun DividerRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}

// ── Attribute row ─────────────────────────────────────────────────────────────

/**
 * `type: attribute` — display one attribute value from an entity. The value is
 * wrapped by [prefix] / [suffix] when set. When [row.format] is set the attribute
 * is treated as an ISO-8601 timestamp and rendered via the five [TimestampFormat]
 * variants (relative, total, date, time, datetime) through [LiveTimestampChip].
 * When the entity is unavailable or the attribute is absent the chip shows "—".
 */
@Composable
private fun AttributeRow(
    row: SpecialRow.Attribute,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val state = stateMap.byRaw(row.entityId)
    val accent = stateAccentFor(row.entityId, state)
    // Name: row name override, else entity friendly name, else entityId.
    val name = row.name?.takeUnless { it.isBlank() }
        ?: state?.friendlyName?.takeUnless { it.isBlank() }
        ?: row.entityId
    val rawAttr = state?.attrString(row.attribute)
    val attrDisplay = rawAttr?.let { v ->
        buildString {
            row.prefix?.let { append(it) }
            append(v)
            row.suffix?.let { append(it) }
        }
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
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        // When the attribute is a timestamp format, delegate to a live chip.
        val fmt = row.format
        if (fmt != null && rawAttr != null) {
            val at = parseHaInstant(rawAttr)
            if (at != null) {
                LiveTimestampChip(at = at, format = fmt, accent = accent)
            } else {
                StateChip(text = rawAttr, accent = accent)
            }
        } else {
            StateChip(
                text = attrDisplay ?: "—",
                accent = if (attrDisplay != null) accent else R1.InkMuted,
            )
        }
    }
}

// ── Button row ────────────────────────────────────────────────────────────────

/**
 * `type: button` (and `call-service` / `perform-action`) — a label row with
 * a RUN-style action button on the right. The button fires [row.tapAction]
 * when tapped; [row.actionName] overrides the button label (defaults "RUN").
 * When the row also carries an entity, the entity icon + accent are shown.
 */
@Composable
private fun ButtonRow(
    row: SpecialRow.Button,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val state = row.entityId?.let { stateMap.byRaw(it) }
    val accent = row.entityId?.let { stateAccentFor(it, state) } ?: R1.AccentWarm
    val name = row.name?.takeUnless { it.isBlank() }
        ?: state?.friendlyName?.takeUnless { it.isBlank() }
        ?: row.entityId
        ?: ""
    val buttonLabel = row.actionName?.takeUnless { it.isBlank() } ?: "RUN"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.entityId != null) {
            Icon(
                imageVector = cardEntityIcon(row.entityId, state, row.icon),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        } else if (row.icon != null) {
            Icon(
                imageVector = cardEntityIcon("", null, row.icon),
                contentDescription = null,
                tint = R1.InkSoft,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        R1Button(
            text = buttonLabel,
            onClick = {
                row.tapAction?.let { onAction(it) }
            },
            enabled = row.tapAction != null,
            variant = R1ButtonVariant.Outlined,
            accent = accent,
        )
    }
}

// ── Buttons row ───────────────────────────────────────────────────────────────

/**
 * `type: buttons` — a horizontal scrolling row of compact icon buttons.
 * Each button may carry an entity (for state-derived tint), an icon, a name,
 * and an optional tap_action. The row scrolls horizontally when the entries
 * don't fit (HA renders the same; the R1 wheel scrolls it naturally).
 */
@Composable
private fun ButtonsRow(
    row: SpecialRow.Buttons,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    if (row.entries.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        row.entries.forEach { entry ->
            val state = entry.entityId?.let { stateMap.byRaw(it) }
            val accent = entry.entityId?.let { stateAccentFor(it, state) } ?: R1.InkSoft
            val icon = cardEntityIcon(entry.entityId ?: "", state, entry.icon)
            Column(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    // Each button is at minimum 48dp tap target.
                    .then(
                        if (entry.tapAction != null) {
                            Modifier.r1Pressable(onClick = { onAction(entry.tapAction) })
                        } else {
                            Modifier
                        },
                    )
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = entry.name,
                    tint = if (entry.tapAction != null) accent else R1.InkMuted,
                    modifier = Modifier.size(22.dp),
                )
                if (!entry.name.isNullOrBlank()) {
                    Text(
                        text = entry.name,
                        style = R1.labelMicro,
                        color = if (entry.tapAction != null) R1.InkSoft else R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ── Conditional row ───────────────────────────────────────────────────────────

/**
 * `type: conditional` — evaluates its conditions against live entity states and
 * renders (or hides) the wrapped row. The wrapped row may itself be an entity row
 * or another special row, carried as [ConditionalRowPayload]. Re-evaluates whenever
 * [stateMap] changes (Compose recomposition handles this automatically). The
 * condition states are projected to a plain `Map<String, String>` for the pure
 * evaluator, which never throws.
 */
@Composable
private fun ConditionalRow(
    row: SpecialRow.Conditional,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean,
) {
    // Project only the entity ids referenced in conditions to a raw-state map.
    val stateSnapshot = remember(stateMap, row.conditions) {
        val ids = mutableSetOf<String>()
        row.conditions.forEach { collectConditionEntityIds(it, ids) }
        ids.associateWith { id -> stateMap.byRaw(id)?.rawState.orEmpty() }
    }
    val visible = evaluateLovelaceConditions(row.conditions, stateSnapshot)
    if (!visible) return
    when (val payload = row.row) {
        is ConditionalRowPayload.EntityRowPayload -> {
            val entityRow = payload.row
            val state = stateMap.byRaw(entityRow.entityId)
            val accent = stateAccentFor(entityRow.entityId, state)
            val domain = entityRow.entityId.substringBefore('.', missingDelimiterValue = "")
            when {
                entityRow.explicitType == null && domain == "event" ->
                    EventEntityRow(entityRow, state, accent, onAction, stateColor)
                entityRow.explicitType == null && domain == "weather" ->
                    WeatherEntityRow(entityRow, state, accent, onAction, stateColor)
                entityRow.explicitType == null && domain == "timer" ->
                    TimerEntityRow(entityRow, state, accent, onAction, stateColor)
                else -> {
                    val name = resolveDisplayName(entityRow.name, entityRow.nameType, state, entityRow.entityId)
                    val secondary = entityRow.secondaryInfo?.let { secondaryInfoLine(it, state) }
                    val stateText = state?.let { compactStateText(it) }
                    val nameColor = if (stateColor && state?.isOn == true) accent else R1.Ink
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .r1Pressable(onClick = { onAction(defaultTapAction(entityRow.entityId)) })
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = cardEntityIcon(entityRow.entityId, state, entityRow.icon),
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
                        if (stateText != null && stateText.isNotBlank()) {
                            Spacer(Modifier.width(10.dp))
                            StateChip(text = stateText, accent = accent)
                        }
                    }
                }
            }
        }
        is ConditionalRowPayload.SpecialRowPayload ->
            SpecialRowItem(
                row = payload.row,
                stateMap = stateMap,
                onAction = onAction,
                stateColor = stateColor,
            )
    }
}

/** Collect entity ids referenced by conditions into a mutable set (recursive). */
private fun collectConditionEntityIds(condition: LovelaceCondition, sink: MutableSet<String>) {
    when (condition) {
        is LovelaceCondition.StateEquals -> sink.add(condition.entityId)
        is LovelaceCondition.NumericState -> {
            sink.add(condition.entityId)
            condition.aboveEntity?.let { sink.add(it) }
            condition.belowEntity?.let { sink.add(it) }
        }
        is LovelaceCondition.And -> condition.conditions.forEach { collectConditionEntityIds(it, sink) }
        is LovelaceCondition.Or -> condition.conditions.forEach { collectConditionEntityIds(it, sink) }
        is LovelaceCondition.Not -> condition.conditions.forEach { collectConditionEntityIds(it, sink) }
        is LovelaceCondition.User,
        LovelaceCondition.Never,
        LovelaceCondition.AlwaysTrue -> Unit
    }
}

// ── Text row ──────────────────────────────────────────────────────────────────

/**
 * `type: text` — a static row with an optional icon, a name, and a text value.
 * No tap action; this row is purely informational. The value renders in a chip
 * on the right side of the row, capped to one line.
 */
@Composable
private fun TextRow(row: SpecialRow.Text) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.icon != null) {
            Icon(
                imageVector = cardEntityIcon("", null, row.icon),
                contentDescription = null,
                tint = R1.InkSoft,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(10.dp))
        }
        Text(
            text = row.name,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(10.dp))
        StateChip(text = row.text, accent = R1.InkSoft)
    }
}

// ── Weblink row ───────────────────────────────────────────────────────────────

/**
 * `type: weblink` — a tappable row that fires a [LovelaceAction.Url] with the
 * configured URL. Shows a link icon on the left and the row name on the right.
 * The URL is kept in the action so the dispatch layer opens the system browser.
 */
@Composable
private fun WeblinkRow(row: SpecialRow.Weblink, onAction: (LovelaceAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onAction(LovelaceAction.Url(row.url)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The parser pre-populates icon with "mdi:link" when the row omits it,
        // so cardEntityIcon always has something to resolve here.
        val icon = cardEntityIcon("", null, row.icon)
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = R1.AccentCool,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text = row.name,
            style = R1.bodyEmph,
            color = R1.AccentCool,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

// ── Cast row ──────────────────────────────────────────────────────────────────

/** `type: cast` — browser-only feature, not applicable on R1. Shows a muted placeholder. */
@Composable
private fun CastPlaceholderRow() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "Cast row (browser-only)",
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}

// ── Unknown row ───────────────────────────────────────────────────────────────

/** Fallback for a `type:` we don't model. Shows the type name as a muted label. */
@Composable
private fun UnknownRow(typeName: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = "$typeName (unsupported row)",
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}
