package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import com.github.itskenny0.r1ha.core.lovelace.ActionConfirmation
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.ui.components.rememberR1Haptic

/**
 * Central action layer for Lovelace cards. Three concerns live here so every
 * card shares one implementation instead of re-deriving gesture wiring and
 * action fallbacks:
 *
 *  1. [r1CardActions] — a Compose gesture modifier that fires tap / hold /
 *     double-tap with HA's precedence (a hold suppresses the tap; a configured
 *     double-tap defers the tap until the double-click window elapses). Reused
 *     by button, tile, entity rows, glance, picture family, shortcut, heading.
 *  2. [resolveTapAction] / [resolveCardActions] — apply HA's "absent tap_action
 *     defaults to the card's domain action" fallback centrally, so cards request
 *     "default" rather than each re-implementing the chain.
 *  3. [isConfirmationExempt] — the confirmation-exemption decision, kept pure so
 *     it is unit-testable independent of the dialog plumbing.
 *
 * Execution itself (confirmation gate then action-type dispatch) lives in
 * [dispatchLovelaceAction] (the screen wires it once); see also the
 * confirmation gate the screen builds around [LovelaceAction.confirmation].
 */

/**
 * Resolve the action a card should fire for its primary tap. Mirrors HA's
 * handleAction default: an explicit [tapAction] wins; a null one falls back to
 * the card's domain-aware default (toggle / press / more-info) when the card
 * has an entity, or to a plain more-info otherwise. The card's own entity id is
 * bound onto the resolved action so the dispatcher always has a target.
 *
 * Pure (no Compose, no IO) so the fallback chain is unit-tested directly.
 */
fun resolveTapAction(tapAction: LovelaceAction?, cardEntityId: String?): LovelaceAction? {
    val base = tapAction
        ?: cardEntityId?.let { defaultTapAction(it) }
        // A card with neither a configured tap nor an entity has nothing to do
        // on tap. HA would default to more-info on the card's entity; with no
        // entity there is no target, so the surface is inert.
        ?: return null
    return base.boundTo(cardEntityId)
}

/**
 * Resolve all three gesture slots for a card in one shot, applying the tap
 * fallback (see [resolveTapAction]) and binding the card entity onto every
 * slot. Hold / double-tap slots stay null when unconfigured (the gesture is
 * then not attached). Returns [CardActions.NONE] when nothing is actionable.
 */
fun resolveCardActions(
    tapAction: LovelaceAction?,
    holdAction: LovelaceAction?,
    doubleTapAction: LovelaceAction?,
    cardEntityId: String?,
): CardActions = CardActions(
    tap = resolveTapAction(tapAction, cardEntityId),
    hold = holdAction?.boundTo(cardEntityId),
    doubleTap = doubleTapAction?.boundTo(cardEntityId),
)

/**
 * Whether [confirmation] should be skipped for [currentUserId]. HA skips the
 * confirm dialog when the current user's id is in the action's exemptions list.
 *
 * [currentUserId] is supplied from the cached `auth/current_user` result (see
 * [com.github.itskenny0.r1ha.core.ha.HaRepository.currentUserId]). When it is
 * null (the server predates the command, the fetch failed, or we are not yet
 * connected) we treat every action as NON-exempt, i.e. the confirmation always
 * shows. This fails safe (the gate is the whole point of confirmation) while
 * honouring per-user exemptions whenever the identity is known.
 */
fun isConfirmationExempt(confirmation: ActionConfirmation, currentUserId: String?): Boolean {
    if (currentUserId == null) return false
    return confirmation.exemptions.contains(currentUserId)
}

/**
 * Gesture modifier shared by every action-capable card surface. Wires
 * [actions] onto tap / long-press / double-tap with HA's precedence, the R1
 * press-state visual (scale + alpha dip), and the matching haptics (a tick on
 * tap, the heavier long-press effect on hold like HA's warning haptic).
 *
 * Built on [combinedClickable] so the tap/hold/double-tap precedence matches
 * the platform's gesture detector exactly (a long-press cancels the click; a
 * configured double-click defers the single click until the 2nd-tap window
 * elapses). The press-state spring is driven from the same interaction source
 * so the dip animates while the user holds, reading as "the hold registered".
 *
 * No-op (returns the receiver unchanged) when [actions] has nothing bound, so a
 * non-interactive card surface stays a plain box with no spurious click target.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.r1CardActions(
    actions: CardActions,
    onAction: (LovelaceAction) -> Unit,
    contentDescription: String? = null,
): Modifier = composed {
    if (actions.tap == null && actions.hold == null && actions.doubleTap == null) {
        return@composed this
    }
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-card-action-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) 0.78f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-card-action-alpha",
    )
    val view = LocalView.current
    val haptic = rememberR1Haptic()
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .combinedClickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                // A surface with only hold / double-tap (no tap) still needs a
                // click target for the platform's gesture detector, but a bare
                // tap must do nothing then.
                actions.tap?.let { haptic.tick(view); onAction(it) }
            },
            onLongClick = actions.hold?.let { a ->
                {
                    // Heavier effect than a tap so the hold reads as distinct,
                    // matching HA firing a warning haptic on hold-trigger.
                    haptic.longPress(view)
                    onAction(a)
                }
            },
            onDoubleClick = actions.doubleTap?.let { a -> { onAction(a) } },
        )
        .then(
            if (contentDescription != null) {
                Modifier.semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = Role.Button
                }
            } else {
                Modifier.semantics(mergeDescendants = true) { role = Role.Button }
            },
        )
}
