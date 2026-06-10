package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.HuiImage

/**
 * The generic entity-row contract, shared by every entities-card row type.
 *
 * Layout mirrors HA's hui-generic-entity-row: a leading state badge, a
 * name + optional secondary line, and a trailing controls slot. Only the
 * name/badge area carries the row's tap / hold / double-tap gestures (the
 * default opens more-info via the action dispatcher); the trailing [controls]
 * are SEPARATE touch targets, so an interactive control (toggle, stepper,
 * button) handles its own tap without also firing the row's more-info. This
 * is the Compose equivalent of HA's `catchInteraction` slot behaviour.
 *
 * Per-row tap_action / hold_action / double_tap_action override the default
 * via [resolveCardActions]; the caller passes the resolved [CardActionsSource]
 * so a row whose tap is explicitly "none" gets an inert name area.
 */
@Composable
internal fun EntityRowScaffold(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
    onAction: (LovelaceAction) -> Unit,
    stateColor: Boolean = false,
    secondaryOverride: String? = null,
    controls: @Composable (RowScope.() -> Unit)? = null,
) {
    val name = resolveDisplayName(row.name, row.nameType, state, row.entityId)
    val secondary = secondaryOverride
        ?: row.secondaryInfo?.let { secondaryInfoLine(it, state) }
    val nameColor = if (stateColor && state?.isOn == true) accent else R1.Ink

    val actions = resolveCardActions(
        tapAction = row.tapAction,
        holdAction = row.holdAction,
        doubleTapAction = row.doubleTapAction,
        cardEntityId = row.entityId,
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Name + badge area: the more-info / per-row action target.
        Row(
            modifier = Modifier
                .weight(1f)
                .r1CardActions(actions = actions, onAction = onAction, contentDescription = name),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowBadge(row = row, state = state, accent = accent)
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
        }
        if (controls != null) {
            Spacer(Modifier.width(10.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
                content = controls,
            )
        }
    }
}

/** The leading badge: a per-row `image:` entity-picture override when set,
 *  otherwise the domain / device-class icon. */
@Composable
private fun RowBadge(
    row: EntityRow,
    state: EntityState?,
    accent: androidx.compose.ui.graphics.Color,
) {
    val image = row.image?.takeUnless { it.isBlank() }
    if (image != null) {
        HuiImage(
            imageUrl = image,
            contentDescription = null,
            modifier = Modifier.size(22.dp),
        )
    } else {
        Icon(
            imageVector = cardEntityIcon(row.entityId, state, row.icon),
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(22.dp),
        )
    }
}

/**
 * The entity-not-found / unavailable warning row. A configured entity that HA
 * doesn't serve renders this in place of a control, matching HA's warning row,
 * rather than crashing or silently disappearing.
 */
@Composable
internal fun EntityNotFoundRow(entityId: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = cardEntityIcon(entityId, null, null),
            contentDescription = null,
            tint = R1.StatusRed,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entityId,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(text = "Entity not found", style = R1.body, color = R1.StatusRed)
        }
    }
}
