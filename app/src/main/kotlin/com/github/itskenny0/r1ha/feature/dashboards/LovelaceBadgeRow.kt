package com.github.itskenny0.r1ha.feature.dashboards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceBadge
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.boundTo
import com.github.itskenny0.r1ha.feature.dashboards.cards.compactStateText
import com.github.itskenny0.r1ha.feature.dashboards.cards.defaultTapAction
import com.github.itskenny0.r1ha.feature.dashboards.cards.haColorAccent
import com.github.itskenny0.r1ha.feature.dashboards.cards.resolveName
import com.github.itskenny0.r1ha.feature.dashboards.cards.stateAccentFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Renders a view's top-level `badges:` array as a horizontal row of chips
 * above the cards (HA's "chips on top"). Each chip carries a leading icon, the
 * entity's live state value, and an optional name, and fires the badge's tap
 * action (defaulting to the entity's domain-default action, usually more-info).
 *
 * The row scrolls horizontally so a view with many badges (10+) never clips:
 * chips size to their content, the value text is single-line + ellipsised, and
 * the whole strip pans rather than wrapping or truncating off-screen.
 *
 * Renders nothing when [badges] is empty so the caller can place it
 * unconditionally above the card body without an extra gap on bare views.
 */
@Composable
fun LovelaceBadgeRow(
    badges: List<LovelaceBadge>,
    states: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (badges.isEmpty()) return
    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { badge ->
            val state = badge.entityId?.let { states.byRaw(it) }
            BadgeChip(badge = badge, state = state, onAction = onAction)
        }
    }
}

@Composable
private fun BadgeChip(
    badge: LovelaceBadge,
    state: EntityState?,
    onAction: (LovelaceAction) -> Unit,
) {
    // Accent: explicit config colour wins, else state-derived (warm when on,
    // soft when off, red when unavailable). With no entity at all, stay neutral.
    val accent = haColorAccent(badge.color)
        ?: badge.entityId?.let { stateAccentFor(it, state) }
        ?: R1.InkSoft

    val name = if (badge.showName && badge.entityId != null) {
        resolveName(badge.name, state, badge.entityId)
    } else {
        badge.name?.takeUnless { it.isBlank() }
    }

    // State value, formatted with unit, single-line. A genuinely-absent state
    // collapses so we don't print a stray dash next to the icon.
    val stateText = if (badge.showState && state != null) {
        compactStateText(state).takeUnless { it.isBlank() }
    } else {
        null
    }

    // Tap action: the badge's own action, bound to its entity, else the
    // entity's domain default (more-info for a sensor). An entity-less badge
    // with no action is inert.
    val action: LovelaceAction? = when {
        badge.tapAction != null -> badge.tapAction.boundTo(badge.entityId)
        badge.entityId != null -> defaultTapAction(badge.entityId)
        else -> null
    }

    val label = listOfNotNull(name, stateText).joinToString(" ").ifBlank {
        badge.entityId ?: "badge"
    }

    var chip = Modifier
        .clip(R1.ShapeRound)
        .background(R1.Surface)
        .border(1.dp, R1.Hairline, R1.ShapeRound)
    if (action != null && !action.isNone()) {
        chip = chip.r1Pressable(
            onClick = { onAction(action) },
            contentDescription = "Badge $label",
        )
    } else {
        chip = chip.semantics { contentDescription = "Badge $label" }
    }
    chip = chip
        .defaultMinSize(minHeight = 48.dp)
        .padding(horizontal = R1.space.m, vertical = R1.space.s)

    Row(
        modifier = chip,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val iconVector = when {
            badge.showIcon && badge.entityId != null -> R1Icons.forEntity(
                badge.entityId,
                deviceClass = state?.deviceClass,
                state = state?.rawState,
            )
            badge.icon != null -> R1Icons.forMdi(badge.icon)
            else -> null
        }
        if (iconVector != null) {
            Icon(
                imageVector = iconVector,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(18.dp),
            )
            if (name != null || stateText != null) Spacer(Modifier.width(R1.space.xs))
        }
        if (name != null) {
            Text(
                text = name,
                style = R1.labelMicro,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stateText != null) Spacer(Modifier.width(R1.space.xs))
        }
        if (stateText != null) {
            Text(
                text = stateText,
                style = R1.labelMicro,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A `none` builtin action means "no tap target"; the chip stays inert. */
private fun LovelaceAction.isNone(): Boolean =
    this is LovelaceAction.Builtin && name == "none"
