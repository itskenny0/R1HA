package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Renderer for HA's `button` card: a tactile fire-control face. Anatomy, top
 * to bottom: a large accent disc holding the glyph (the actuator), the name in
 * full-contrast title type (the label plate), and a hairline-flanked all-caps
 * verb derived from the resolved tap action ("TAP TO SEND" for IR commands,
 * "TAP TO TOGGLE" for a light, see [buttonTapHint]). Pressing lights the face:
 * the disc fills solid accent and flips its glyph dark, the frame brightens to
 * full accent, and the verb tints warm, all springing in lockstep with the
 * shared press dip because the card hands its interaction source to
 * [r1CardActions]. Hold / double-tap stay wired through the same modifier.
 *
 * Deck composition: the deck slot always paints its own micro identity header
 * above every card face. The split here is hierarchy, not suppression: that
 * header is muted slot chrome, while the face's name is the button's hero
 * label, the same small-header / big-face pairing entity cards use. The verb
 * footer is the line the old face lacked, which is what made it read as a
 * disabled box instead of a control.
 */
@Composable
fun ButtonCard(
    card: LovelaceCard.Button,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = card.entityId?.let { stateMap.byRaw(it) }
    // HA renders a hui-warning when the button is bound to an entity the backend
    // doesn't serve. A bare action button (no entity) is fine and skips this.
    if (card.entityId != null && state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }
    // HA precedence: an explicit `color` wins; else `state_color` gates the
    // state-derived tint; else neutral. R1HA divergence: a bare action button
    // is always warm-accented (see buttonAccent), never disabled-grey.
    val accent = buttonAccent(card.color, card.stateColor, card.entityId, state)
    val label = card.name?.takeUnless { it.isBlank() }
        ?: card.entityId?.let { resolveStructuredName(null, card.nameItems, null, state, it) }
        ?: "Action"
    // HA's `icon_height` sizes the glyph; default one notch above the tile
    // discs (56 vs 48) because the disc is this card's hero, not a row marker.
    val discSize = iconHeightDp(card.iconHeight)?.dp ?: 56.dp
    // Resolve tap (with HA's domain-default fallback) plus hold / double-tap,
    // all bound to the card entity, in one shot via the shared action layer.
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )
    // Share the press stream with r1CardActions so the accent flash below
    // animates in lockstep with the modifier's own scale/alpha dip.
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressSpring = spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow,
    )
    // Press flash: disc wash 18% (the shared CardIconDisc resting fill) ->
    // solid, glyph accent -> dark, frame 45% -> full. Springs (not snaps) so
    // even a quick IR tap shows a visible pulse decaying out, the "command
    // fired" cue.
    val discFill by animateFloatAsState(
        targetValue = if (pressed) 1f else 0.18f,
        animationSpec = pressSpring,
        label = "button-card-disc-fill",
    )
    val glyphTint by animateColorAsState(
        targetValue = if (pressed) R1.Bg else accent,
        label = "button-card-glyph-tint",
    )
    val frame by animateColorAsState(
        targetValue = if (pressed) accent else accent.copy(alpha = 0.45f),
        label = "button-card-frame",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, frame, R1.ShapeM)
            .r1CardActions(
                actions = actions,
                onAction = onAction,
                contentDescription = label,
                interactionSource = interaction,
            )
            .padding(horizontal = R1.space.l, vertical = R1.space.l),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // We honour `show_icon` and show a glyph when we can derive one: from
        // the bound entity (with the config icon as override), or for a bare
        // action button (no entity, e.g. a pinned IR-command button) from the
        // config `icon:` alone. Only an unresolvable / absent icon skips the
        // disc, rather than drawing a meaningless placeholder.
        val icon = when {
            !card.showIcon -> null
            card.entityId != null -> cardEntityIcon(card.entityId, state, card.icon)
            else -> com.github.itskenny0.r1ha.ui.icons.R1Icons.forMdi(card.icon)
        }
        if (icon != null) {
            CardIconDisc(
                icon = icon,
                accent = accent,
                discSize = discSize,
                iconSize = discSize * 0.5f,
                fillAlpha = discFill,
                iconTint = glyphTint,
            )
            Spacer(Modifier.height(R1.space.m))
        }
        if (card.showName) {
            Text(
                text = label,
                style = R1.titleCard,
                color = R1.Ink,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (card.showState && state != null) {
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = compactStateText(state),
                style = R1.labelMicro,
                color = accent,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // Affordance footer: hairline rules flanking the action verb. The
        // rules give the wide face horizontal structure (no more cavernous
        // empty plate) and the verb says what a tap does. Skipped entirely on
        // an inert face (no resolvable tap) so a dead surface never promises.
        val hint = buttonTapHint(actions.tap)
        if (hint != null) {
            Spacer(Modifier.height(R1.space.m))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(R1.Hairline),
                )
                Text(
                    text = hint,
                    style = R1.labelMicro,
                    color = if (pressed) accent else R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.padding(horizontal = R1.space.s),
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(1.dp)
                        .background(R1.Hairline),
                )
            }
        }
    }
}
