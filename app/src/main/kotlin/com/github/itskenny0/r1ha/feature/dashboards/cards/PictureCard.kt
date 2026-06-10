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
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import androidx.compose.ui.layout.ContentScale
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `picture-glance` card. A background image (static URL
 * or a camera / entity's `entity_picture`) with a row of small state chips
 * overlaid along the bottom. Each chip fires its entity's default action.
 *
 * Image URLs are resolved + authenticated via the same [AsyncBitmap] the
 * media cards use; when no image is available the card falls back to a
 * muted surface so the chips remain usable.
 */
@Composable
fun PictureGlanceCard(
    card: LovelaceCard.PictureGlance,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraState = card.cameraImage?.let { safeEntityId(it)?.let { id -> stateMap[id] } }
    val imageUrl = card.image ?: entityPictureOf(cameraState)
    // Whole-card action (HA fires the card's tap_action on the image area). The
    // card carries no single entity, so the slots pass through unchanged; chips
    // below have their own per-entity actions.
    val cardActions = CardActions(
        tap = card.tapAction,
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = cardActions, onAction = onAction),
    ) {
        if (!card.title.isNullOrBlank()) {
            Text(
                text = card.title,
                style = R1.sectionHeader,
                color = R1.InkSoft,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
        ) {
            PictureBackground(imageUrl, Modifier.fillMaxWidth().height(160.dp), fitModeScale(card.fitMode))
            // Chips strip overlaid along the bottom edge, on a darkening scrim.
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(R1.Bg.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                card.entities.take(6).forEach { row ->
                    PictureChip(row, stateMap, onAction)
                }
            }
        }
    }
}

/**
 * Renderer for HA's `picture-entity` card. A background image bound to one
 * entity, with the entity's name + state overlaid; tapping fires the
 * entity's action (or the configured `tap_action`).
 */
@Composable
fun PictureEntityCard(
    card: LovelaceCard.PictureEntity,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state = stateMap.byRaw(card.entityId)
    val name = resolveName(card.name, state, card.entityId)
    val imageEntityState = card.imageEntity?.let { stateMap.byRaw(it) }
    val imageUrl = card.image ?: entityPictureOf(imageEntityState) ?: entityPictureOf(state)
    val actions = resolveCardActions(
        tapAction = card.tapAction,
        holdAction = card.holdAction,
        doubleTapAction = card.doubleTapAction,
        cardEntityId = card.entityId,
    )
    val accent = stateAccentFor(card.entityId, state)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = name),
    ) {
        PictureBackground(imageUrl, Modifier.fillMaxWidth().height(160.dp), fitModeScale(card.fitMode))
        Row(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(R1.Bg.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (card.showName) {
                Text(
                    text = name,
                    style = R1.bodyEmph,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (card.showState) {
                Text(
                    text = state?.let(::compactStateText)?.takeUnless { it.isBlank() } ?: "-",
                    style = R1.labelMicro,
                    color = accent,
                )
            }
        }
    }
}

/**
 * Renderer for HA's plain `picture` card. Shows [card.image] (a static URL) or,
 * when [card.imageEntity] is set, that entity's `entity_picture` attribute.
 * The whole card fires [card.tapAction] when set.
 */
@Composable
fun PicturePlainCard(
    card: LovelaceCard.Picture,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageEntityState = card.imageEntity?.let { stateMap.byRaw(it) }
    val imageUrl = card.image ?: entityPictureOf(imageEntityState)
    // Whole-card action; the plain picture card has no entity, so a missing
    // tap_action leaves the surface inert (no domain default to fall back to).
    val actions = CardActions(
        tap = card.tapAction,
        hold = card.holdAction,
        doubleTap = card.doubleTapAction,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction),
    ) {
        PictureBackground(imageUrl, Modifier.fillMaxWidth().height(160.dp))
    }
}

@Composable
private fun PictureBackground(url: String?, modifier: Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (url.isNullOrBlank()) {
        Box(modifier = modifier.background(R1.SurfaceMuted))
        return
    }
    AsyncBitmap(
        url = url,
        serverUrl = LocalHaServerUrl.current,
        bearerToken = LocalHaBearerToken.current,
        modifier = modifier,
        contentDescription = null,
        contentScale = contentScale,
    )
}

/** Map HA's fit_mode string to a Compose ContentScale. Defaults to Crop (HA's default). */
private fun fitModeScale(fitMode: String?): ContentScale = when (fitMode?.lowercase()) {
    "contain" -> ContentScale.Fit
    "fill" -> ContentScale.FillBounds
    else -> ContentScale.Crop
}

@Composable
private fun PictureChip(
    row: EntityRow,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val eid = safeEntityId(row.entityId)
    val state = eid?.let { stateMap[it] }
    val accent = stateAccentFor(row.entityId, state)
    val actions = resolveCardActions(
        tapAction = row.tapAction,
        holdAction = row.holdAction,
        doubleTapAction = row.doubleTapAction,
        cardEntityId = row.entityId,
    )
    Box(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.SurfaceMuted.copy(alpha = 0.9f))
            .r1CardActions(actions = actions, onAction = onAction)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = state?.let(::compactStateText)?.uppercase() ?: "·",
            style = R1.labelMicro,
            color = accent,
        )
    }
}

/** Pull an entity's `entity_picture` attribute (camera snapshot, person
 *  avatar, etc.) for use as a card background. Null when the attribute
 *  isn't present. */
internal fun entityPictureOf(state: EntityState?): String? {
    val prim = state?.attributesJson?.get("entity_picture") as? JsonPrimitive ?: return null
    return prim.content.takeUnless { it.isBlank() }
}
