package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.attrString
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's modern `tile` card. Compact horizontal layout: a
 * round accent-coloured icon disc on the left, friendly name + state
 * on the right. Mirrors HA's tile-card chrome: state-coloured background
 * for the icon disc, soft surface for the body, no border emphasis.
 *
 * Vertical mode flips the layout to icon-on-top so a wider screen can
 * pack tiles two-up in a column without label truncation. We honour
 * `vertical: true` from the card config when set.
 *
 * HA splits the tile into two action surfaces: the body taps to more-info by
 * default while the ICON taps to toggle / press (for a toggleable domain) or
 * nothing. We honour `icon_tap_action` / `icon_hold_action` /
 * `icon_double_tap_action`, falling back to HA's domain-default icon action.
 */
@Composable
fun TileCard(
    card: LovelaceCard.Tile,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(card.entityId)
    // HA renders a hui-warning when the configured entity has no state object.
    // Match that with the shared not-found card naming the entity, rather than
    // a normal-looking tile showing a prettified id and a dead grey accent.
    if (state == null) {
        EntityNotFoundCard(card.entityId, modifier)
        return
    }
    // HA's tile tints the icon with a configured `color`, a light's live
    // `rgb_color` when on, then the state-derived accent (gated by `state_color`).
    // An unavailable tile always reads red. The precedence lives in the pure
    // [tileIconAccent] so it is unit-tested directly.
    val accent = tileIconAccent(
        entityId = card.entityId,
        state = state,
        configAccent = haColorAccent(card.color),
        stateColor = card.stateColor,
    )
    val name = resolveStructuredName(card.name, card.nameItems, card.nameType, state, card.entityId)
    val icon = cardEntityIcon(card.entityId, state, card.icon)
    // Body tap (with HA's domain-default fallback) plus hold / double-tap.
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )
    // The icon's own gesture surface. A null icon_tap_action falls back to HA's
    // domain-default (toggle / press / none). "none" yields no tap so the icon
    // is inert and the body action still fires from the surrounding surface.
    val iconActions = resolveIconActions(card)
    val stateText = when {
        card.stateContent.isNotEmpty() ->
            resolveStateContent(card.stateContent, state).takeUnless { it.isBlank() }
        else ->
            compactStateText(state).takeUnless { it.isBlank() }
    }

    // HA's tile renders its `features:` as a control row below the body. We
    // keep it OUTSIDE the body's tap-to-act pressable (so tapping a feature
    // chip doesn't also fire the tile's tap action) but inside the same card
    // surface, separated by a hairline. Only renders when the entity supports
    // at least one configured feature.
    val hasFeatures = card.features.any { it !is com.github.itskenny0.r1ha.core.lovelace.LovelaceTileFeature.Unsupported }
    // HA's "inline" feature position renders features beside the body; "bottom"
    // (default) renders them below. Vertical tiles always render below.
    val inlineFeatures = !card.vertical &&
        card.featuresPosition?.equals("inline", ignoreCase = true) == true

    val cardSurface = Modifier
        .fillMaxWidth()
        .clip(R1.ShapeM)
        .background(R1.Surface)
        .border(1.dp, accent.copy(alpha = 0.25f), R1.ShapeM)

    val bodyTap = Modifier
        .fillMaxWidth()
        .r1CardActions(actions = actions, onAction = onAction, contentDescription = name)
        .padding(horizontal = 14.dp, vertical = 12.dp)

    Column(modifier = modifier.then(cardSurface)) {
        if (inlineFeatures && hasFeatures) {
            // Body and a compact trailing feature column share one row.
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TileBody(
                    card = card,
                    state = state,
                    accent = accent,
                    name = name,
                    icon = icon,
                    stateText = stateText,
                    iconActions = iconActions,
                    onAction = onAction,
                    modifier = bodyTap.weight(1f),
                )
                Column(modifier = Modifier.padding(end = 12.dp, top = 8.dp, bottom = 8.dp).width(120.dp)) {
                    TileFeatureRows(
                        features = card.features,
                        entityId = card.entityId,
                        state = state,
                        accent = accent,
                        onAction = onAction,
                    )
                }
            }
        } else {
            TileBody(
                card = card,
                state = state,
                accent = accent,
                name = name,
                icon = icon,
                stateText = stateText,
                iconActions = iconActions,
                onAction = onAction,
                modifier = bodyTap,
            )
            if (hasFeatures) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(R1.Hairline),
                )
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                    TileFeatureRows(
                        features = card.features,
                        entityId = card.entityId,
                        state = state,
                        accent = accent,
                        onAction = onAction,
                    )
                }
            }
        }
    }
}

/** The tile's tappable body: icon disc + name + state, in either the compact
 *  horizontal layout or the vertical (icon-on-top) layout. Factored out of
 *  [TileCard] so the feature row can sit below it without inheriting its
 *  tap-to-act gesture. */
@Composable
private fun TileBody(
    card: LovelaceCard.Tile,
    state: EntityState?,
    accent: Color,
    name: String,
    icon: ImageVector,
    stateText: String?,
    iconActions: CardActions,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier,
) {
    if (card.vertical) {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            TileIconSurface(
                card = card, state = state, accent = accent, icon = icon,
                discSize = 48.dp, iconSize = 24.dp, iconActions = iconActions, onAction = onAction,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (!card.hideState && stateText != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stateText,
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TileIconSurface(
                card = card, state = state, accent = accent, icon = icon,
                discSize = 40.dp, iconSize = 22.dp, iconActions = iconActions, onAction = onAction,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (!card.hideState && stateText != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = stateText,
                        style = R1.body,
                        color = accent,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            // HA's tile shows a trailing on/off pill only for entities that
            // genuinely toggle (lights, switches, fans, ...). A sensor / numeric
            // tile gets its colour from the icon disc + the inline state line, so
            // a misleading "OFF" pill on a temperature tile is dropped.
            if (isToggleableDomain(card.entityId) && state?.isAvailable == true) {
                Spacer(Modifier.width(8.dp))
                StateChip(text = if (state.isOn) "on" else "off", accent = accent)
            }
        }
    }
}

/**
 * The tile's icon surface: the accent disc (or an entity picture when
 * `show_entity_picture` is set and the entity carries one), an optional status
 * badge overlay, the alarm/lock pulse, and the icon's own tap gesture. Kept
 * here so the badge + picture + pulse logic lives next to the tile and reuses
 * the pure [tileBadgeFor] / [tileIconPulses] decisions.
 */
@Composable
private fun TileIconSurface(
    card: LovelaceCard.Tile,
    state: EntityState?,
    accent: Color,
    icon: ImageVector,
    discSize: Dp,
    iconSize: Dp,
    iconActions: CardActions,
    onAction: (LovelaceAction) -> Unit,
) {
    val pulses = tileIconPulses(card.entityId, state)
    val pulseAlpha = if (pulses) {
        val transition = rememberInfiniteTransition(label = "tile-icon-pulse")
        val a by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.25f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1000),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "tile-icon-pulse-alpha",
        )
        a
    } else {
        1f
    }

    // HA reads `entity_picture_local` || `entity_picture`. We surface the raw
    // attribute (covers person avatars / camera thumbs / album art) and fall
    // back to the media-player-specific [mediaPicture] field.
    val picture = if (card.showEntityPicture) {
        (state?.attrString("entity_picture_local")
            ?: state?.attrString("entity_picture")
            ?: state?.mediaPicture)?.takeUnless { it.isBlank() }
    } else {
        null
    }
    // The icon's tap gesture: a `none` resolution leaves CardActions empty and
    // r1CardActions returns the receiver unchanged (no spurious click target),
    // so the body action still fires.
    val iconGesture = Modifier.r1CardActions(actions = iconActions, onAction = onAction)

    Box(modifier = Modifier.then(iconGesture).alpha(pulseAlpha)) {
        if (picture != null) {
            AsyncBitmap(
                url = picture,
                serverUrl = LocalHaServerUrl.current,
                bearerToken = LocalHaBearerToken.current,
                modifier = Modifier.size(discSize).clip(CircleShape),
                contentDescription = null,
            )
        } else {
            CardIconDisc(icon = icon, accent = accent, discSize = discSize, iconSize = iconSize)
        }
        val badge = tileBadgeFor(card.entityId, state)
        if (badge != null) {
            TileStatusBadge(
                badge = badge,
                modifier = Modifier.align(Alignment.TopEnd),
            )
        }
    }
}

/** Small overlay badge drawn at the icon's top-right corner (HA's
 *  ha-tile-badge). A monochrome glyph in a coloured disc, sized to read at the
 *  R1's pixel density without crowding the icon. Uses the app's font-free glyph
 *  idiom (a single unicode symbol) so it tints cleanly and never depends on a
 *  curated MDI mapping that might be absent. */
@Composable
private fun TileStatusBadge(badge: TileBadge, modifier: Modifier = Modifier) {
    val (glyph, bg) = when (badge) {
        TileBadge.Unavailable -> "!" to R1.StatusAmber
        is TileBadge.Person -> (if (badge.away) "↪" else "⌂") to
            (if (badge.home) R1.AccentGreen else R1.AccentCool)
        is TileBadge.Climate -> climateActionGlyph(badge.action) to climateActionAccent(badge.action)
        is TileBadge.Humidifier -> "≀" to R1.AccentCool
    }
    Box(
        modifier = modifier
            .size(16.dp)
            .clip(CircleShape)
            .background(bg)
            .border(1.dp, R1.Surface, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.labelMicro, color = R1.Surface)
    }
}

/** A glyph for a climate hvac_action: a flame for heating, snowflake for
 *  cooling, drop for drying, fan otherwise. */
private fun climateActionGlyph(action: String): String = when (action.lowercase()) {
    "heating" -> "✦"
    "cooling" -> "❅"
    "drying" -> "≀"
    "fan" -> "✣"
    else -> "❈"
}

/** Accent for a climate hvac_action badge: heating warm, cooling cool, else neutral. */
private fun climateActionAccent(action: String): Color = when (action.lowercase()) {
    "heating" -> R1.StatusRed
    "cooling" -> R1.AccentCool
    "drying" -> R1.StatusAmber
    else -> R1.AccentGreen
}

/**
 * Resolve the tile's icon-gesture slots. A null `icon_tap_action` falls back to
 * HA's domain-default ("toggle" / "none"); "none" produces no tap so the icon
 * is a passthrough. Hold / double-tap are bound only when configured.
 */
private fun resolveIconActions(card: LovelaceCard.Tile): CardActions {
    val tap = card.iconTapAction
        ?: when (getEntityDefaultTileIconAction(card.entityId)) {
            "toggle" -> LovelaceAction.Builtin("toggle", card.entityId)
            else -> null
        }
    return CardActions(
        tap = tap?.boundTo(card.entityId),
        hold = card.iconHoldAction?.boundTo(card.entityId),
        doubleTap = card.iconDoubleTapAction?.boundTo(card.entityId),
    )
}

/**
 * Domains whose tile shows a trailing on/off pill. Mirrors HA's tile, which
 * renders a toggle affordance only for domains with a binary on/off notion
 * (lights, switches, fans, locks, covers, ...) and shows just the state line
 * for read-only sensors / numeric entities.
 */
private fun isToggleableDomain(entityId: String): Boolean =
    when (entityId.substringBefore('.', missingDelimiterValue = "")) {
        "light", "switch", "input_boolean", "fan", "automation", "lock",
        "cover", "media_player", "humidifier", "climate", "siren", "valve",
        "remote", "group" -> true
        else -> false
    }

/**
 * Build the tile's state line from HA's `state_content` token list. Each
 * token is resolved in order and the non-blank results joined with a space:
 *  - "state" -> the entity's raw compact state text
 *  - "last_changed" / "last_updated" -> relative time ("2m", "1h", ...)
 *  - anything else -> the attribute value for that key, stringified
 * Unknown/empty tokens are skipped rather than producing a stray gap.
 */
internal fun resolveStateContent(tokens: List<String>, state: EntityState): String {
    return tokens.mapNotNull { token ->
        when (token) {
            "state" -> compactStateText(state).takeUnless { it.isBlank() }
            "last_changed", "last_updated" -> relativeTimeShort(state.lastChanged).takeUnless { it.isBlank() }
            else -> {
                val v = state.attributesJson?.get(token) as? JsonPrimitive
                v?.content?.takeUnless { it.isBlank() }
            }
        }
    }.joinToString(" ")
}
