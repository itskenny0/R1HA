package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.HuiImage
import com.github.itskenny0.r1ha.ui.components.ImageEngine
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's `picture-glance` card. A background image (static URL,
 * camera entity, or state-mapped URL) with a row of small state chips overlaid
 * along the bottom. Delegates image sizing, camera polling, and filtering to
 * [HuiImage].
 */
@Composable
fun PictureGlanceCard(
    card: LovelaceCard.PictureGlance,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val camEntityId = card.cameraImage?.let { safeEntityId(it)?.let { _ -> it } }
    val camState = camEntityId?.let { stateMap.byRaw(it) }

    val entityState = camState?.rawState

    // state_image resolution: if configured and entity state matches, use that URL;
    // otherwise fall back to card.image, then entity_picture of the camera entity.
    @Suppress("UNCHECKED_CAST")
    val resolvedImage = ImageEngine.resolveStateImage(card.stateImage as kotlin.collections.Map<String, String>?, entityState)
        ?: card.image
        ?: entityPictureOf(camState)

    // state_filter resolution
    @Suppress("UNCHECKED_CAST")
    val effectiveFilter = entityState?.let { (card.stateFilter as kotlin.collections.Map<String, String>?)?.get(it) }
        ?: card.filter

    // Whole-card action (HA fires the card's tap_action on the image area). The
    // card has no single entity field beyond the camera/image entity; with no
    // explicit tap_action HA defaults to more-info on that entity when one is
    // present, else the image area is inert.
    val cardActions = CardActions(
        tap = card.tapAction?.boundTo(camEntityId)
            ?: camEntityId?.let { LovelaceAction.Builtin("more-info", it) },
        hold = card.holdAction?.boundTo(camEntityId),
        doubleTap = card.doubleTapAction?.boundTo(camEntityId),
    )

    // Split the chips into HA's two groups: the left "dialog" group (domains not
    // in DOMAINS_TOGGLE, default tap = more-info) and the right "toggle" group
    // (default tap = toggle). HA lays the box out with space-between.
    val groups = glanceGroups(card.entities, forceDialog = card.forceDialog)

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
        Box(modifier = Modifier.fillMaxWidth()) {
            HuiImage(
                imageUrl = resolvedImage,
                cameraEntityId = if (card.cameraImage != null && ImageEngine.cameraMode(card.cameraImage, card.cameraView) != ImageEngine.CameraMode.Static) card.cameraImage else null,
                cameraView = card.cameraView,
                entityState = entityState,
                entityId = camEntityId,
                filter = effectiveFilter,
                aspectRatioStr = card.aspectRatio,
                fitMode = card.fitMode,
                darkModeFilter = card.darkModeFilter,
                contentDescription = card.title,
                modifier = Modifier.fillMaxWidth(),
            )
            // Chip strip overlaid along the bottom edge, on a darkening scrim.
            // Dialog group left, toggle group right (space-between like HA).
            Row(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(R1.Bg.copy(alpha = 0.55f))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.dialog.forEach { row ->
                        PictureGlanceChip(row, card.showState, dialogGroup = true, stateMap, onAction)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    groups.toggle.forEach { row ->
                        PictureGlanceChip(row, card.showState, dialogGroup = false, stateMap, onAction)
                    }
                }
            }
        }
    }
}

/**
 * Renderer for HA's `picture-entity` card. A background image bound to one
 * entity, with the entity's name + state overlaid; tapping fires the
 * entity's action. Delegates all image logic to [HuiImage].
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

    // camera_image is a separate camera entity (gap item #10)
    val camEntityId = card.cameraImage?.takeUnless { it.isBlank() }
    val camState = camEntityId?.let { stateMap.byRaw(it) }
    val entityState = state?.rawState

    // state_image resolution. When show_entity_picture is set HA prefers the
    // entity's own picture before the configured image.
    @Suppress("UNCHECKED_CAST")
    val resolvedImage = (if (card.showEntityPicture) entityPictureOf(state) else null)
        ?: ImageEngine.resolveStateImage(card.stateImage as kotlin.collections.Map<String, String>?, entityState)
        ?: card.image
        ?: entityPictureOf(imageEntityState)
        ?: entityPictureOf(state)

    // state_filter resolution
    @Suppress("UNCHECKED_CAST")
    val effectiveFilter = entityState?.let { (card.stateFilter as kotlin.collections.Map<String, String>?)?.get(it) }
        ?: card.filter

    // HA's picture-entity hardcodes tap_action: more-info (it does NOT use the
    // domain toggle default). Hold / double-tap pass through unchanged.
    val actions = CardActions(
        tap = card.tapAction?.boundTo(card.entityId)
            ?: LovelaceAction.Builtin("more-info", card.entityId),
        hold = card.holdAction?.boundTo(card.entityId),
        doubleTap = card.doubleTapAction?.boundTo(card.entityId),
    )
    val accent = stateAccentFor(card.entityId, state)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = name),
    ) {
        HuiImage(
            imageUrl = resolvedImage,
            cameraEntityId = if (camEntityId != null && ImageEngine.cameraMode(camEntityId, card.cameraView) != ImageEngine.CameraMode.Static) camEntityId else null,
            cameraView = card.cameraView,
            entityState = entityState,
            entityId = card.entityId,
            filter = effectiveFilter,
            aspectRatioStr = card.aspectRatio,
            fitMode = card.fitMode,
            darkModeFilter = card.darkModeFilter,
            contentDescription = name,
            modifier = Modifier.fillMaxWidth(),
        )
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
 * Delegates image sizing, camera support, and filtering to [HuiImage].
 */
@Composable
fun PicturePlainCard(
    card: LovelaceCard.Picture,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val imageEntityState = card.imageEntity?.let { stateMap.byRaw(it) }
    val entityState = imageEntityState?.rawState

    // state_image resolution
    @Suppress("UNCHECKED_CAST")
    val resolvedImage = ImageEngine.resolveStateImage(card.stateImage as kotlin.collections.Map<String, String>?, entityState)
        ?: card.image
        ?: entityPictureOf(imageEntityState)

    @Suppress("UNCHECKED_CAST")
    val effectiveFilter = entityState?.let { (card.stateFilter as kotlin.collections.Map<String, String>?)?.get(it) }
        ?: card.filter

    val camEntityId = card.cameraImage?.takeUnless { it.isBlank() }

    // Whole-card action. HA: when an image_entity is set the default tap is
    // more-info on that entity; otherwise tap defaults to `none` (inert).
    val actions = CardActions(
        tap = card.tapAction?.boundTo(card.imageEntity)
            ?: card.imageEntity?.let { LovelaceAction.Builtin("more-info", it) },
        hold = card.holdAction?.boundTo(card.imageEntity),
        doubleTap = card.doubleTapAction?.boundTo(card.imageEntity),
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction),
    ) {
        HuiImage(
            imageUrl = resolvedImage,
            cameraEntityId = if (camEntityId != null && ImageEngine.cameraMode(camEntityId, card.cameraView) != ImageEngine.CameraMode.Static) camEntityId else null,
            cameraView = card.cameraView,
            entityState = entityState,
            entityId = card.imageEntity,
            filter = effectiveFilter,
            aspectRatioStr = card.aspectRatio,
            darkModeFilter = card.darkModeFilter,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * One picture-glance chip: an icon reflecting the entity state (coloured when
 * [stateActive], dimmed when off/unavailable, mirroring HA's `.state-on`
 * class) over an optional state-text caption. The chip fires the entity's
 * action; the default tap depends on its group ([dialogGroup] → more-info, else
 * toggle), and per-entity tap/hold/double-tap overrides apply.
 */
@Composable
private fun PictureGlanceChip(
    row: EntityRow,
    cardShowState: Boolean,
    dialogGroup: Boolean,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val state = stateMap.byRaw(row.entityId)
    val active = stateActive(row.entityId, state)
    // HA dims the off/unavailable icon (#a9a9a9) and shows the active icon white;
    // map onto R1's state accent for active, a muted ink for inactive.
    val iconTint = when {
        state != null && !state.isAvailable -> R1.StatusRed
        active -> stateAccentFor(row.entityId, state)
        else -> R1.InkMuted
    }
    val icon = R1Icons.forMdi(row.icon)
        ?: R1Icons.forEntity(
            entityId = row.entityId,
            deviceClass = state?.deviceClass,
            state = state?.rawState,
        )

    // Per-entity tap default: more-info (dialog) / toggle (toggle) unless an
    // explicit tap_action is set. Hold default is more-info in HA.
    val tap = row.tapAction?.boundTo(row.entityId)
        ?: glanceChipDefaultTap(row.entityId, dialogGroup)
    val hold = row.holdAction?.boundTo(row.entityId)
        ?: LovelaceAction.Builtin("more-info", row.entityId)
    val actions = CardActions(
        tap = tap,
        hold = hold,
        doubleTap = row.doubleTapAction?.boundTo(row.entityId),
    )

    // show_state: the card flag OR the per-entity flag turns the caption on.
    val showState = cardShowState || (row.showState == true)
    val stateText: String? = if (showState) {
        if (row.attribute != null) {
            val attrs = state?.attributesJson
            val v = (attrs?.get(row.attribute) as? JsonPrimitive)?.content ?: ""
            buildString {
                if (!row.prefix.isNullOrEmpty()) append(row.prefix)
                append(v)
                if (!row.suffix.isNullOrEmpty()) append(row.suffix)
            }
        } else {
            state?.let(::compactStateText)?.takeUnless { it.isBlank() }
        }
    } else {
        null
    }

    Column(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .r1CardActions(actions = actions, onAction = onAction),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        androidx.compose.material3.Icon(
            imageVector = icon,
            contentDescription = resolveName(row.name, state, row.entityId),
            tint = iconTint,
            modifier = Modifier.size(22.dp),
        )
        if (!stateText.isNullOrBlank()) {
            Text(
                text = stateText,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
    }
}

/** Pull an entity's `entity_picture` attribute for use as a card background. */
internal fun entityPictureOf(state: EntityState?): String? {
    val prim = state?.attributesJson?.get("entity_picture") as? JsonPrimitive ?: return null
    return prim.content.takeUnless { it.isBlank() }
}
