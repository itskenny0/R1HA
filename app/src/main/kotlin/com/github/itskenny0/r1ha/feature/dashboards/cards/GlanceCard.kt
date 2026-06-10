package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.TimestampFormat
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.formatTimestamp
import com.github.itskenny0.r1ha.ui.components.rememberNowTick
import com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock
import com.github.itskenny0.r1ha.ui.components.resolveTimestampFormat
import com.github.itskenny0.r1ha.ui.components.timestampInstantOrNull

/**
 * Renderer for HA's `glance` card. A compact tile grid where each tile
 * is icon-on-top, name-below, optional state line. Columns honour the
 * card config's `columns` field, falling back to a width-aware default
 * (3 on tablets, 2 on phones, 2 on R1. except that R1 doesn't surface
 * this feature at all per the breakpoint gate).
 */
@Composable
fun GlanceCard(
    card: LovelaceCard.Glance,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
        if (card.entities.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        val widthDp = LocalConfiguration.current.screenWidthDp
        val cols = card.columns?.coerceIn(1, 6) ?: when {
            widthDp >= 720 -> 4
            widthDp >= 480 -> 3
            else -> 2
        }
        Column(modifier = Modifier.padding(horizontal = 8.dp)) {
            card.entities.chunked(cols).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    row.forEach { e ->
                        GlanceTile(
                            row = e,
                            stateMap = stateMap,
                            showName = card.showName,
                            showState = card.showState,
                            showIcon = card.showIcon,
                            onAction = onAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Pad the last row so the trailing tile doesn't grow to fill.
                    repeat(cols - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun GlanceTile(
    row: EntityRow,
    stateMap: EntityStates,
    showName: Boolean,
    showState: Boolean,
    showIcon: Boolean,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Resolve by raw id, the domain-agnostic path used by EntitiesCard /
    // TileCard (no need to round-trip through a typed EntityId just to read a
    // state slice).
    val state = stateMap.byRaw(row.entityId)
    val name = resolveDisplayName(row.name, row.nameType, state, row.entityId)
    val accent = stateAccentFor(row.entityId, state)
    val actions = resolveCardActions(
        tapAction = row.tapAction,
        holdAction = row.holdAction,
        doubleTapAction = row.doubleTapAction,
        cardEntityId = row.entityId,
    )
    Column(
        modifier = modifier
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = name)
            .padding(horizontal = 8.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (showIcon) {
            CardIconDisc(
                icon = cardEntityIcon(row.entityId, state, row.icon),
                accent = accent,
                discSize = 28.dp,
                iconSize = 18.dp,
                showBorder = false,
            )
            Spacer(Modifier.height(6.dp))
        }
        if (showName) {
            Text(
                text = name,
                style = R1.labelMicro,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        if (showState) {
            GlanceTileState(
                state = state,
                rowFormat = row.format,
                accent = accent,
            )
        }
    }
}

/**
 * State text for a glance tile, extracted into its own composable so the
 * 1-second ticker ([rememberNowTick]) is called unconditionally and Compose's
 * hook ordering rule is respected. When the entity is a timestamp/uptime sensor
 * the ticker drives live-updating relative/total text; otherwise a static
 * [compactStateText] is shown.
 */
@Composable
private fun GlanceTileState(
    state: EntityState?,
    rowFormat: TimestampFormat?,
    accent: androidx.compose.ui.graphics.Color,
) {
    val now by rememberNowTick()
    val use24h = rememberUse24HourClock()
    val tsFormat = resolveTimestampFormat(rowFormat, state?.deviceClass)
    val tsInstant = if (tsFormat != null) timestampInstantOrNull(state?.deviceClass, state?.rawState) else null
    val stateText = if (tsInstant != null && tsFormat != null) {
        runCatching {
            formatTimestamp(
                at = tsInstant,
                format = tsFormat,
                now = now,
                zone = java.time.ZoneId.systemDefault(),
                use24h = use24h,
            )
        }.getOrDefault(state?.rawState.orEmpty())
    } else {
        state?.let(::compactStateText)?.takeUnless { it.isBlank() } ?: "-"
    }
    Spacer(Modifier.height(3.dp))
    Text(
        text = stateText,
        style = R1.labelMicro,
        color = accent,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
    )
}
