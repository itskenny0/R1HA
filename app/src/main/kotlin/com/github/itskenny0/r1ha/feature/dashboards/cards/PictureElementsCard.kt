package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.PictureElement
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AsyncBitmap
import com.github.itskenny0.r1ha.ui.components.HuiImage
import com.github.itskenny0.r1ha.ui.components.ImageEngine
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Renderer for HA's `picture-elements` card. A background image with interactive
 * overlay elements (icons, state chips, labels, images) each positioned by a
 * percentage offset over the image box.
 *
 * The background is sized by [LovelaceCard.PictureElements.aspectRatio] when set,
 * else intrinsically. This ensures percentage-positioned elements are truthful to
 * the actual image dimensions (with a fixed-height box + ContentScale.Crop, elements
 * were offset from the cropped region rather than the full image).
 *
 * Supported element types: `state-badge`, `state-icon`, `state-label`, `icon`, `image`.
 * Unknown types are skipped gracefully.
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
    val imageUrl = card.image ?: entityPictureOf(camState)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(1.dp, R1.Hairline, R1.ShapeM),
    ) {
        // Background: HuiImage handles aspect-ratio sizing and camera polling.
        // The overlay Box must be the same size so percentage positions are truthful.
        HuiImage(
            imageUrl = imageUrl,
            cameraEntityId = if (cameraEntityId != null && ImageEngine.cameraMode(cameraEntityId, card.cameraView) != ImageEngine.CameraMode.Static) cameraEntityId else null,
            cameraView = card.cameraView,
            aspectRatioStr = card.aspectRatio,
            contentDescription = null,
            modifier = Modifier.fillMaxWidth(),
        )

        // Overlay elements: positioned against the same HuiImage box dimensions.
        BoxWithConstraints(modifier = Modifier.matchParentSize()) {
            val density = LocalDensity.current
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val maxHeightPx = with(density) { maxHeight.toPx() }
            val halfSizePx = with(density) { 16.dp.toPx() }.roundToInt()

            card.elements.forEach { element ->
                val anchorX = (element.leftPct / 100.0 * maxWidthPx).roundToInt()
                val anchorY = (element.topPct / 100.0 * maxHeightPx).roundToInt()

                PictureOverlayElement(
                    element = element,
                    stateMap = stateMap,
                    onAction = onAction,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset { IntOffset(anchorX - halfSizePx, anchorY - halfSizePx) },
                )
            }
        }
    }
}

/**
 * Renders one overlay element at the position already applied by the caller.
 * Each element is individually tappable: fires [element.tapAction] bound to its
 * entity, or the domain-default action when no tap_action is configured and the
 * element has an entity. Elements with no entity and no tap action show the
 * visual only (no tap target).
 */
@Composable
private fun PictureOverlayElement(
    element: PictureElement,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entityId = element.entityId
    val state = entityId?.let { stateMap.byRaw(it) }
    val accent = stateAccentFor(entityId ?: "", state)

    val effectiveAction: LovelaceAction? = when {
        element.tapAction != null -> element.tapAction.boundTo(entityId)
        entityId != null -> defaultTapAction(entityId)
        else -> null
    }

    val pressModifier = effectiveAction?.let {
        Modifier.r1Pressable(onClick = { onAction(it) })
    } ?: Modifier

    when (element.type) {
        "state-badge" -> {
            val displayName = element.name
                ?: state?.let { s -> entityId?.let { resolveName(null, s, it) } }
                ?: entityId?.substringAfter('.', "") ?: ""
            val stateText = state?.let(::compactStateText)?.takeUnless { it.isBlank() }
                ?: if (entityId != null) "..." else ""
            Box(
                modifier = modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(R1.SurfaceMuted.copy(alpha = 0.92f))
                    .then(pressModifier)
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
            val icon = R1Icons.forMdi(element.icon)
                ?: R1Icons.forEntity(
                    entityId = entityId ?: "",
                    deviceClass = state?.deviceClass,
                    state = state?.rawState,
                )
            Box(
                modifier = modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(accent.copy(alpha = 0.18f))
                    .then(pressModifier),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = element.name ?: entityId,
                    tint = accent,
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
                modifier = modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(R1.Bg.copy(alpha = 0.75f))
                    .then(pressModifier)
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
                modifier = modifier
                    .size(32.dp)
                    .then(pressModifier),
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
            // Show entity_picture when an entity is bound; else the static image URL.
            val imgUrl = entityPictureOf(state) ?: element.image
            if (!imgUrl.isNullOrBlank()) {
                Box(
                    modifier = modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .then(pressModifier),
                ) {
                    AsyncBitmap(
                        url = imgUrl,
                        serverUrl = LocalHaServerUrl.current,
                        bearerToken = LocalHaBearerToken.current,
                        modifier = Modifier.fillMaxSize(),
                        contentDescription = element.name ?: entityId,
                        contentScale = ContentScale.Crop,
                    )
                }
            }
        }

        else -> Unit
    }
}
