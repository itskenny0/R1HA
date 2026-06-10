package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Renderer for HA's generic `entity` card (hui-entity-card.ts): one entity
 * shown as a glyph disc + friendly name with the live state value as a large
 * readout on the right. Tapping fires the card's `tap_action` when present, or
 * the entity's default action (toggle for toggleable domains, more-info
 * otherwise); `hold_action` / `double_tap_action` ride the shared dispatcher.
 *
 * R1HA's typed model doesn't carry a dedicated `entity`-card variant, so this
 * draws its config off the [LovelaceCard.Unsupported.raw] JSON (name / icon /
 * attribute / unit / state_color / actions / footer) with the entity id taken
 * from the captured entity ref. The entity is already subscribed (the ref drives
 * the per-card state slice), so the readout is live. An entity HA doesn't serve
 * renders the not-found warning card rather than an empty body.
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
    // HA's entity card shows a warning card for an entity the backend doesn't
    // serve (createEntityNotFoundWarning). Match that rather than show a blank.
    if (state == null) {
        EntityNotFoundCard(entityId, modifier)
        return
    }
    // `name:` may be a plain string or a structured EntityNameItem object/array
    // (HA 2025.11+); the structured form composes entity/device/area/floor + text
    // parts against the registry, degrading to the friendly name when absent.
    val nameOverride = (card.raw["name"] as? JsonPrimitive)?.content
    val nameItems = LovelaceParser.parseStructuredNameConfig(card.raw["name"])
    val name = resolveStructuredName(nameOverride, nameItems, null, state, entityId)
    val accent = stateAccentFor(entityId, state)
    val configIcon = (card.raw["icon"] as? JsonPrimitive)?.content
    val icon = cardEntityIcon(entityId, state, configIcon)
    // HA's entity card can pin the readout to a named attribute instead of the
    // entity state (`attribute:`). Resolve it from the live attributes when set.
    val attribute = (card.raw["attribute"] as? JsonPrimitive)?.content
    val unit = (card.raw["unit"] as? JsonPrimitive)?.content ?: state.unit
    val value = when {
        attribute != null -> {
            val attr = state.attributesJson?.get(attribute)
            (attr as? JsonPrimitive)?.content?.let { v -> unit?.let { "$v $it" } ?: v }
        }
        else -> compactStateText(state).takeUnless { it.isBlank() }
    } ?: "-"
    // HA `state_color`: true tints the value with the domain accent (default for
    // lights). false / unset reads neutral ink for non-light domains. The icon
    // disc always carries the accent (its purpose is the at-a-glance colour).
    val stateColor = (card.raw["state_color"] as? JsonPrimitive)?.let {
        it.content.toBooleanStrictOrNull()
    } ?: (entityId.substringBefore('.', "") == "light")
    val valueColor = if (stateColor) accent else R1.Ink

    // Tap / hold / double-tap via the shared dispatcher; an absent tap falls back
    // to the entity's domain-default action.
    val actions = resolveCardActions(
        tapAction = LovelaceParser.parseActionConfig(card.raw["tap_action"] as? JsonObject),
        holdAction = LovelaceParser.parseActionConfig(card.raw["hold_action"] as? JsonObject),
        doubleTapAction = LovelaceParser.parseActionConfig(card.raw["double_tap_action"] as? JsonObject),
        cardEntityId = entityId,
    )
    val footer = LovelaceParser.parseHeaderFooter(card.raw["footer"])

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent.copy(alpha = 0.25f), R1.ShapeM)
            .r1CardActions(actions = actions, onAction = onAction, contentDescription = name)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
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
                color = valueColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        footer?.let {
            Spacer(Modifier.height(8.dp))
            CardHeaderFooterSlot(it, stateMap, onAction)
        }
    }
}

/**
 * HA's entity-not-found warning card: a muted surface naming the missing entity.
 * Mirrors createEntityNotFoundWarning so a typo'd or removed entity reads as a
 * deliberate warning rather than a blank card.
 */
@Composable
internal fun EntityNotFoundCard(entityId: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Entity not available: $entityId",
            style = R1.body,
            color = R1.StatusRed,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
