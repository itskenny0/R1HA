package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.CardActions
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.PictureElement
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.HuiImage
import com.github.itskenny0.r1ha.ui.components.ImageEngine
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Renderer for HA's `picture-elements` card. A background image with interactive
 * overlay elements (icons, state chips, labels, images, buttons) each positioned
 * by a percentage or pixel offset over the rendered image box.
 *
 * The background is sized by [LovelaceCard.PictureElements.aspectRatio] when set,
 * else intrinsically (HuiImage). Percentage positions anchor against that box so
 * an element at left:40% sits 40% across the image actually drawn, not the card
 * frame. HA's default element transform `translate(-50%,-50%)` centres each
 * element on its anchor point unless the element supplies its own transform.
 *
 * Element types: `state-badge`, `state-icon`, `state-label`, `icon`, `image`,
 * `service-button` / `action-button`, and `conditional` (a transparent wrapper
 * whose children render only when its conditions pass). An unrecognised type
 * renders a small labelled placeholder chip at its position rather than vanishing.
 */
@Composable
fun PictureElementsCard(
    card: LovelaceCard.PictureElements,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cameraEntityId = card.cameraImage?.takeUnless { it.isBlank() }
    val camState = cameraEntityId?.let { stateMap.byRaw(it) }
    val entityState = card.entity?.let { stateMap.byRaw(it)?.rawState }

    // state_image / image_entity resolution for the background.
    @Suppress("UNCHECKED_CAST")
    val imageUrl = ImageEngine.resolveStateImage(card.stateImage as kotlin.collections.Map<String, String>?, entityState)
        ?: card.image
        ?: card.imageEntity?.let { entityPictureOf(stateMap.byRaw(it)) }
        ?: entityPictureOf(camState)

    @Suppress("UNCHECKED_CAST")
    val effectiveFilter = entityState?.let { (card.stateFilter as kotlin.collections.Map<String, String>?)?.get(it) }
        ?: card.filter

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM),
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
            // Background: HuiImage handles aspect-ratio sizing and camera polling.
            // The overlay Box matches its size so percentage positions are truthful.
            HuiImage(
                imageUrl = imageUrl,
                cameraEntityId = if (cameraEntityId != null && ImageEngine.cameraMode(cameraEntityId, card.cameraView) != ImageEngine.CameraMode.Static) cameraEntityId else null,
                cameraView = card.cameraView,
                entityState = entityState,
                entityId = card.entity,
                filter = effectiveFilter,
                aspectRatioStr = card.aspectRatio,
                darkModeFilter = card.darkModeFilter,
                contentDescription = card.title,
                modifier = Modifier.fillMaxWidth(),
            )

            // Overlay elements: positioned against the same HuiImage box.
            BoxWithConstraints(modifier = Modifier.matchParentSize()) {
                val density = LocalDensity.current
                val boxWidthPx = with(density) { maxWidth.toPx() }
                val boxHeightPx = with(density) { maxHeight.toPx() }
                card.elements.forEach { element ->
                    // A conditional element is a transparent container with its
                    // own hooks (it remembers a condition context); routing it to
                    // a distinct composable keeps each branch's call structure
                    // stable across recompositions.
                    if (element.type == "conditional") {
                        ConditionalElement(element, boxWidthPx, boxHeightPx, stateMap, onAction)
                    } else {
                        PositionedElement(element, boxWidthPx, boxHeightPx, stateMap, onAction)
                    }
                }
            }
        }
    }
}

/**
 * A `conditional` element is a transparent container: it carries no position of
 * its own (HA keeps it static), so its children render at THEIR positions only
 * when the gate passes. The condition context drives a live re-evaluation when a
 * gating entity / the clock / the current user changes.
 */
@Composable
private fun ConditionalElement(
    element: PictureElement,
    boxWidthPx: Float,
    boxHeightPx: Float,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val context = rememberLovelaceConditionContext(element.conditions)
    val visible = remember(element.conditions, stateMap, context) {
        evaluateConditions(element.conditions, stateMap, context)
    }
    if (visible) {
        element.children.forEach { child ->
            if (child.type == "conditional") {
                ConditionalElement(child, boxWidthPx, boxHeightPx, stateMap, onAction)
            } else {
                PositionedElement(child, boxWidthPx, boxHeightPx, stateMap, onAction)
            }
        }
    }
}

/**
 * Place one element at its anchor over the image box, applying HA's default
 * centring transform. The element is sized by its intrinsic content; when it
 * carries a `style.transform` other than the default translate, we anchor at the
 * raw point (top-left) instead of centring.
 */
@Composable
private fun PositionedElement(
    element: PictureElement,
    boxWidthPx: Float,
    boxHeightPx: Float,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val anchorX = anchorPx(element.left, boxWidthPx)
    val anchorY = anchorPx(element.top, boxHeightPx)
    val center = elementCentersOnAnchor(element.transformOverride)

    // Capture the element's measured size so the centring offset can subtract
    // half of it (HA's translate(-50%,-50%)). The size feeds back through a
    // remembered state; the offset lambda reads it after layout settles.
    var measured by remember { androidx.compose.runtime.mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
    Box(
        modifier = Modifier
            .onSizeChanged { measured = it }
            .offset {
                if (center) {
                    IntOffset(
                        (anchorX - measured.width / 2f).roundToInt(),
                        (anchorY - measured.height / 2f).roundToInt(),
                    )
                } else {
                    IntOffset(anchorX.roundToInt(), anchorY.roundToInt())
                }
            },
    ) {
        PictureOverlayElement(element, stateMap, onAction)
    }
}

/**
 * Renders one overlay element's visual + gestures. Each element is individually
 * actionable: its tap fires [PictureElement.tapAction] bound to its entity, or
 * more-info on the entity by default (HA's element default); hold / double-tap
 * apply when configured. Elements with neither an action nor an entity are inert.
 */
@Composable
private fun PictureOverlayElement(
    element: PictureElement,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
) {
    val entityId = element.entityId
    val state = entityId?.let { stateMap.byRaw(it) }
    val accent = stateAccentFor(entityId ?: "", state)

    val actions = CardActions(
        tap = elementTapAction(element.tapAction, entityId),
        hold = element.holdAction?.boundTo(entityId),
        doubleTap = element.doubleTapAction?.boundTo(entityId),
    )

    when (element.type) {
        "state-badge" -> {
            val displayName = element.name
                ?: state?.let { s -> entityId?.let { resolveName(null, s, it) } }
                ?: entityId?.substringAfter('.', "") ?: ""
            val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() }
                ?: if (entityId != null) "..." else ""
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(R1.SurfaceMuted.copy(alpha = 0.92f))
                    .r1CardActions(actions = actions, onAction = onAction)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(
                    text = if (displayName.isNotBlank()) "$displayName $stateText" else stateText,
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                )
            }
        }

        "state-icon" -> {
            // state_color (default true) tints with the entity state; when off,
            // the icon stays a neutral ink.
            val tint = if (element.stateColor) accent else R1.InkSoft
            val icon = R1Icons.forMdi(element.icon)
                ?: R1Icons.forEntity(
                    entityId = entityId ?: "",
                    deviceClass = state?.deviceClass,
                    state = state?.rawState,
                )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tint.copy(alpha = 0.18f))
                    .r1CardActions(actions = actions, onAction = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = element.name ?: entityId,
                    tint = tint,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        "state-label" -> {
            val rawValue: String = when {
                element.attribute != null && entityId != null -> {
                    val attrs = state?.attributesJson
                    (attrs?.get(element.attribute) as? JsonPrimitive)?.content ?: "..."
                }
                state != null -> compactStateText(state)
                entityId != null -> "..."
                else -> ""
            }
            val labelText = buildString {
                if (!element.prefix.isNullOrEmpty()) append(element.prefix)
                append(rawValue)
                if (!element.suffix.isNullOrEmpty()) append(element.suffix)
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(R1.Bg.copy(alpha = 0.75f))
                    .r1CardActions(actions = actions, onAction = onAction)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = labelText,
                    style = R1.labelMicro,
                    color = accent,
                    maxLines = 1,
                )
            }
        }

        "icon" -> {
            val icon = R1Icons.forMdi(element.icon)
                ?: R1Icons.forEntity(entityId ?: "", state = state?.rawState)
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .r1CardActions(actions = actions, onAction = onAction),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = element.name,
                    tint = accent,
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        "image" -> {
            // The image element honours the full hui-image option set: a bound
            // entity_picture / image_entity / camera, state_image, filters, and
            // an aspect_ratio. Falls back to the static image URL.
            @Suppress("UNCHECKED_CAST")
            val resolved = ImageEngine.resolveStateImage(element.stateImage as kotlin.collections.Map<String, String>?, state?.rawState)
                ?: (element.imageEntity?.let { entityPictureOf(stateMap.byRaw(it)) })
                ?: entityPictureOf(state)
                ?: element.image
            @Suppress("UNCHECKED_CAST")
            val imgFilter = state?.rawState?.let { (element.stateFilter as kotlin.collections.Map<String, String>?)?.get(it) }
                ?: element.filter
            val camId = element.cameraImage?.takeUnless { it.isBlank() }
            val hasCamera = camId != null && ImageEngine.cameraMode(camId, element.cameraView) != ImageEngine.CameraMode.Static
            if (!resolved.isNullOrBlank() || hasCamera) {
                // A pixel width sizes the element; otherwise a default 40dp box.
                val widthMod = element.widthPx?.let { Modifier.width(it.dp) } ?: Modifier.size(40.dp)
                Box(
                    modifier = widthMod
                        .clip(RoundedCornerShape(6.dp))
                        .r1CardActions(actions = actions, onAction = onAction),
                ) {
                    HuiImage(
                        imageUrl = resolved,
                        cameraEntityId = if (hasCamera) camId else null,
                        cameraView = element.cameraView,
                        entityState = state?.rawState,
                        entityId = element.entityId ?: element.imageEntity,
                        filter = imgFilter,
                        aspectRatioStr = element.aspectRatio,
                        contentDescription = element.name ?: entityId,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        "service-button", "action-button" -> {
            // A text button that performs an action on tap. The configured
            // perform-action (with data/target) is the tap action; hold/double
            // still honour any per-element overrides.
            val serviceTap = element.serviceAction?.takeUnless { it.isBlank() }?.let {
                LovelaceAction.CallService(
                    service = it,
                    entityId = element.serviceTarget?.entityId?.firstOrNull(),
                    data = element.serviceData,
                    target = element.serviceTarget,
                )
            }
            val buttonActions = CardActions(
                tap = element.tapAction ?: serviceTap,
                hold = element.holdAction,
                doubleTap = element.doubleTapAction,
            )
            val label = element.title?.takeUnless { it.isBlank() }
                ?: element.serviceAction?.substringAfter('.', "")?.replace('_', ' ')
                ?: "Run"
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(R1.AccentWarm.copy(alpha = 0.22f))
                    .r1CardActions(actions = buttonActions, onAction = onAction)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = R1.AccentWarm,
                    maxLines = 1,
                )
            }
        }

        else -> {
            // Unknown element type: a small labelled placeholder so it never
            // crashes or silently vanishes. Still actionable if configured.
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(R1.SurfaceMuted.copy(alpha = 0.9f))
                    .border(1.dp, R1.Hairline, RoundedCornerShape(6.dp))
                    .r1CardActions(actions = actions, onAction = onAction)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = element.name ?: element.type,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                )
            }
        }
    }
}
