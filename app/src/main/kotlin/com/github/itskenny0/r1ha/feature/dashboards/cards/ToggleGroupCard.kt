package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.ActionTarget
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `toggle-group` card (hui-toggle-group-card.ts). HA's card
 * is a single aggregate power tile: it shows an "All on" / "All off" / "N on"
 * label and one tap toggles the WHOLE set (turn_off when any is on, else
 * turn_on; a cover group closes / opens). We lead with that aggregate control
 * and, since the R1 has the room, keep a per-entity segment grid below it so
 * the user can also flip an individual member.
 *
 * R1HA's typed model has no dedicated toggle-group variant, so this reads its
 * entity set from the [LovelaceCard.Unsupported.entityRefs] the parser captures
 * off the card's `entities` array (those entities are subscribed, so the
 * segments are live).
 */
@Composable
fun ToggleGroupCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = card.raw["name"]?.let { (it as? JsonPrimitive)?.content }
        ?: card.raw["title"]?.let { (it as? JsonPrimitive)?.content }
    val configColor = card.raw["color"]?.let { (it as? JsonPrimitive)?.content }

    CardSurface(modifier = modifier, title = title?.takeUnless { it.isBlank() }) {
        if (card.entityRefs.isEmpty()) {
            EmptyRow(text = "No entities configured")
            return@CardSurface
        }
        // Aggregate state: how many of the group are currently on.
        val onCount = card.entityRefs.count { stateMap.byRaw(it)?.isOn == true }
        val total = card.entityRefs.size
        val anyOn = onCount > 0
        // HA colours the aggregate tile with the config `color` (or the first
        // on-entity's state colour) while any member is on, else inactive grey.
        val accent = when {
            !anyOn -> R1.InkSoft
            else -> haColorAccent(configColor) ?: R1.AccentWarm
        }
        // The group keys all its members on the first entity's domain.
        val domain = card.entityRefs.first().substringBefore('.', missingDelimiterValue = "")

        Column(modifier = Modifier.padding(horizontal = 14.dp)) {
            // ── Aggregate toggle-all tile. ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeM)
                    .background(if (anyOn) accent.copy(alpha = 0.16f) else R1.SurfaceMuted)
                    .border(1.dp, if (anyOn) accent.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeM)
                    .r1Pressable(onClick = {
                        val (svcDomain, service) = toggleGroupService(domain, anyOn)
                        onAction(toggleAllAction(svcDomain, service, card.entityRefs))
                    })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = R1Icons.forMdi("mdi:power") ?: R1Icons.forEntity("switch.x"),
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = toggleGroupLabel(onCount, total),
                    style = R1.bodyEmph,
                    color = if (anyOn) accent else R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(8.dp))
            // ── Per-entity segments, two per row. ────────────────────────────
            card.entityRefs.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    pair.forEach { ref ->
                        ToggleSegment(
                            ref = ref,
                            stateMap = stateMap,
                            onAction = onAction,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(2 - pair.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

/**
 * Toggle-all service call: a multi-entity target the dispatcher routes through
 * HA's REST path (it expands the entity_id array server-side). Mirrors HA's
 * `_handleTap`, which calls one service against the whole entity list.
 */
private fun toggleAllAction(domain: String, service: String, entityRefs: List<String>): LovelaceAction.CallService =
    LovelaceAction.CallService(
        service = "$domain.$service",
        entityId = null,
        data = null,
        target = ActionTarget(entityId = entityRefs),
    )

@Composable
private fun ToggleSegment(
    ref: String,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(ref)
    val on = state?.isOn == true
    val name = resolveName(null, state, ref)
    val accent = stateAccentFor(ref, state)
    Row(
        modifier = modifier
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeM)
            .background(if (on) accent.copy(alpha = 0.16f) else R1.SurfaceMuted)
            .border(1.dp, if (on) accent.copy(alpha = 0.5f) else R1.Hairline, R1.ShapeM)
            .r1Pressable(onClick = { onAction(LovelaceAction.Builtin("toggle", ref)) })
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = cardEntityIcon(ref, state),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = name,
            style = R1.labelMicro,
            color = if (on) accent else R1.InkSoft,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}
