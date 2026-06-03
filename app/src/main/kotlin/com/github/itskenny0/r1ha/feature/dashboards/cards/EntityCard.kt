package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's generic `entity` card (hui-entity-card.ts): one entity
 * shown as a glyph disc + friendly name with the live state value as a large
 * readout on the right. Tapping fires the entity's default action (toggle for
 * toggleable domains, more-info otherwise), or the card's configured
 * `tap_action` when present.
 *
 * R1HA's typed model doesn't carry a dedicated `entity`-card variant, so this
 * draws its config off the [LovelaceCard.Unsupported.raw] JSON (name / icon /
 * attribute) with the entity id taken from the captured entity ref. The entity
 * is already subscribed (the ref drives the per-card state slice), so the
 * readout is live.
 */
@Composable
fun EntityCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val entityId = card.entityRefs.firstOrNull()
    if (entityId == null) {
        // No resolvable entity: fall back to the raw-config placeholder rather
        // than render an empty card.
        UnsupportedCard(card.copy(entityRefs = emptyList()), stateMap, onAction, modifier)
        return
    }
    val state = stateMap.byRaw(entityId)
    val nameOverride = card.raw["name"]?.let { (it as? JsonPrimitive)?.content }
    val name = resolveName(nameOverride, state, entityId)
    val accent = stateAccentFor(entityId, state)
    val configIcon = card.raw["icon"]?.let { (it as? JsonPrimitive)?.content }
    val icon = cardEntityIcon(entityId, state, configIcon)
    // HA's entity card can pin the readout to a named attribute instead of the
    // entity state (`attribute:`). Resolve it from the live attributes when set.
    val attribute = card.raw["attribute"]?.let { (it as? JsonPrimitive)?.content }
    val unit = card.raw["unit"]?.let { (it as? JsonPrimitive)?.content } ?: state?.unit
    val value = when {
        attribute != null -> {
            val attr = state?.attributesJson?.get(attribute)
            (attr as? JsonPrimitive)?.content?.let { v -> unit?.let { "$v $it" } ?: v }
        }
        else -> state?.let { compactStateText(it) }?.takeUnless { it.isBlank() }
    } ?: "-"

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.25f), R1.ShapeM)
            .r1Pressable(onClick = { onAction(defaultTapAction(entityId)) })
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CardIconDisc(
            icon = icon,
            accent = accent,
            discSize = 36.dp,
            iconSize = 20.dp,
            showBorder = false,
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(10.dp))
        Text(
            text = value,
            style = R1.numeralM,
            color = accent,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
