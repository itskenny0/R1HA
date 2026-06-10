package com.github.itskenny0.r1ha.feature.dashboards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceBadge
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.badgeColorAccent
import com.github.itskenny0.r1ha.feature.dashboards.cards.badgeStateText
import com.github.itskenny0.r1ha.feature.dashboards.cards.evaluateConditions
import com.github.itskenny0.r1ha.feature.dashboards.cards.r1CardActions
import com.github.itskenny0.r1ha.feature.dashboards.cards.rememberLovelaceConditionContext
import com.github.itskenny0.r1ha.feature.dashboards.cards.resolveCardActions
import com.github.itskenny0.r1ha.feature.dashboards.cards.resolveName
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.icons.R1Icons

/**
 * Renders a view's top-level `badges:` array or a heading card's badges as a
 * horizontal row of chips. Each chip carries a leading icon (or entity picture),
 * the entity's live state value, and an optional name, and fires the badge's
 * tap / hold / double-tap action (defaulting to the entity's domain-default
 * action, usually more-info).
 *
 * Badges with `visibility:` conditions are evaluated by the Batch B engine and
 * hidden when the conditions do not pass, leaving no gap in the row. Badges
 * with `disabled: true` are always hidden.
 *
 * The row scrolls horizontally so a view with many badges (10+) never clips:
 * chips size to their content, the value text is single-line plus ellipsised,
 * and the whole strip pans rather than wrapping or truncating off-screen.
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
    // Heading badges (HeadingCard) default an entity badge's tap to NONE; view
    // badges default it to more-info. HA diverges this way between the two.
    headingContext: Boolean = false,
) {
    if (badges.isEmpty()) return

    // Build a single condition context covering every badge's visibility conditions
    // so the row's time-ticking clock covers them all in one coroutine.
    val allConditions = remember(badges) { badges.flatMap { it.conditions } }
    val context = rememberLovelaceConditionContext(allConditions)

    val scroll = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scroll),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        badges.forEach { badge ->
            // Badge-level visibility gate: skip if conditions don't pass.
            if (badge.conditions.isNotEmpty() &&
                !evaluateConditions(badge.conditions, states, context)) return@forEach
            val state = badge.entityId?.let { states.byRaw(it) }
            BadgeChip(badge = badge, state = state, onAction = onAction, headingContext = headingContext)
        }
    }
}

/** Map badge size to (iconSize, textStyle, minHeight). Default = normal. */
@Composable
private fun badgeSizeTokens(size: String?): Triple<Dp, TextStyle, Dp> = when (size?.lowercase()) {
    "small" -> Triple(14.dp, R1.numeralS, 36.dp)
    "large" -> Triple(22.dp, R1.body, 56.dp)
    else -> Triple(18.dp, R1.labelMicro, 48.dp)  // normal / null
}

@Composable
private fun BadgeChip(
    badge: LovelaceBadge,
    state: EntityState?,
    onAction: (LovelaceAction) -> Unit,
    headingContext: Boolean = false,
) {
    // Accent: explicit config colour applied only when the entity is active
    // (matches HA's _computeStateColor gating custom color on stateActive);
    // inactive falls back to the state-derived accent. With no entity at all,
    // apply the config colour unconditionally (entity-less shortcut/button badges).
    val accent = badgeColorAccent(
        configColor = badge.color,
        entityId = badge.entityId,
        state = state,
    )

    val name = if (badge.showName && badge.entityId != null) {
        resolveName(badge.name, state, badge.entityId)
    } else {
        badge.name?.takeUnless { it.isBlank() }
    }

    // State value: use state_content token list when configured, otherwise the
    // plain compact state text. A genuinely-absent state collapses so we don't
    // print a stray dash next to the icon.
    val stateText = if (badge.showState && state != null) {
        badgeStateText(badge, state)
    } else {
        null
    }

    // Tap / hold / double-tap: the badge's own actions, with HA's domain-default
    // tap fallback applied centrally, all bound to the badge's entity. An
    // entity-less badge with no action is inert.
    // View entity badges with no tap_action default to more-info (every domain),
    // unlike cards which toggle/press. Heading entity badges default to NONE, so
    // an action-less heading entity badge is inert (only an explicit tap acts)
    // while still honouring any hold / double-tap. Action badges (shortcut/
    // button) and explicit taps go through the normal resolution.
    val headingInertTap = headingContext && badge.tapAction == null && badge.entityId != null
    val actions = resolveCardActions(
        tapAction = if (headingInertTap) LovelaceAction.Builtin("none", badge.entityId) else badge.tapAction,
        holdAction = badge.holdAction,
        doubleTapAction = badge.doubleTapAction,
        cardEntityId = badge.entityId,
        defaultTapToMoreInfo = !headingContext,
    )

    val label = listOfNotNull(name, stateText).joinToString(" ").ifBlank {
        badge.entityId ?: "badge"
    }

    val (iconSize, textStyle, minHeight) = badgeSizeTokens(badge.size)

    var chip = Modifier
        .clip(R1.ShapeRound)
        .background(R1.Surface)
        .border(1.dp, R1.Hairline, R1.ShapeRound)
    val tapIsNone = actions.tap?.isNone() == true
    if ((actions.tap != null && !tapIsNone) || actions.hasHoldOrDoubleTap) {
        chip = chip.r1CardActions(actions = actions, onAction = onAction, contentDescription = "Badge $label")
    } else {
        chip = chip.semantics { contentDescription = "Badge $label" }
    }
    chip = chip
        .defaultMinSize(minHeight = minHeight)
        .padding(horizontal = R1.space.m, vertical = R1.space.s)

    Row(
        modifier = chip,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Entity picture: when show_entity_picture is true and the entity has an
        // entity_picture attribute, show it in place of the vector icon. Camera
        // entities are served with a 32x32 suffix via HA's camera proxy URL.
        val entityPictureUrl: String? = if (badge.showEntityPicture && state != null) {
            val raw = state.attributesJson?.get("entity_picture")
            (raw as? kotlinx.serialization.json.JsonPrimitive)?.content?.takeUnless { it.isBlank() }
        } else null

        val serverUrl = LocalHaServerUrl.current
        val bearerToken = LocalHaBearerToken.current

        if (badge.showIcon) {
            when {
                entityPictureUrl != null -> {
                    // Append HA's camera-resize suffix when it looks like a camera proxy
                    // path (matches HA's cameraUrlWithWidthHeight behaviour).
                    val thumbUrl = if (entityPictureUrl.contains("/api/camera_proxy/")) {
                        "$entityPictureUrl?width=32&height=32"
                    } else {
                        entityPictureUrl
                    }
                    Box(
                        modifier = Modifier
                            .size(iconSize)
                            .clip(R1.ShapeRound),
                    ) {
                        AsyncBitmap(
                            url = thumbUrl,
                            serverUrl = serverUrl,
                            bearerToken = bearerToken,
                            modifier = Modifier.size(iconSize),
                            contentScale = ContentScale.Crop,
                        )
                    }
                    if (name != null || stateText != null) Spacer(Modifier.width(R1.space.xs))
                }
                badge.entityId != null -> {
                    val iconVector = R1Icons.forEntity(
                        badge.entityId,
                        deviceClass = state?.deviceClass,
                        state = state?.rawState,
                    )
                    if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(iconSize),
                        )
                        if (name != null || stateText != null) Spacer(Modifier.width(R1.space.xs))
                    }
                }
                badge.icon != null -> {
                    val iconVector = R1Icons.forMdi(badge.icon)
                    if (iconVector != null) {
                        Icon(
                            imageVector = iconVector,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(iconSize),
                        )
                        if (name != null || stateText != null) Spacer(Modifier.width(R1.space.xs))
                    }
                }
                else -> Unit
            }
        }
        if (name != null) {
            Text(
                text = name,
                style = textStyle,
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (stateText != null) Spacer(Modifier.width(R1.space.xs))
        }
        if (stateText != null) {
            Text(
                text = stateText,
                style = textStyle,
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
