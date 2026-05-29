package com.github.itskenny0.r1ha.feature.dashboards.cards

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * Top-level dispatch from a parsed [LovelaceCard] to the matching
 * Compose renderer. Centralised here so adding a new card type means
 * one new file + one new branch (vs. wiring the dispatch into every
 * stack / grid / conditional consumer).
 *
 * Contract:
 *  - [card] is the post-overrides card.
 *  - [stateMap] is the live entity-state map keyed by EntityId. Empty
 *    map is fine; renderers handle the "I have a config but no state"
 *    case gracefully (skeleton + entity_id).
 *  - [onAction] dispatches a parsed [LovelaceAction]. Returns Unit; the
 *    caller wires up service calls / navigation / URL launching.
 */
@Composable
fun LovelaceCardRenderer(
    card: LovelaceCard,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (card) {
        is LovelaceCard.Entities -> EntitiesCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Glance -> GlanceCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Button -> ButtonCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Tile -> TileCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Light -> LightCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Gauge -> GaugeCard(card, stateMap, modifier)
        is LovelaceCard.WeatherForecast -> WeatherForecastCard(card, stateMap, modifier)
        is LovelaceCard.Markdown -> MarkdownCard(card, modifier)
        is LovelaceCard.Heading -> HeadingCard(card, modifier)
        is LovelaceCard.VerticalStack -> VerticalStackCard(card, stateMap, onAction, modifier)
        is LovelaceCard.HorizontalStack -> HorizontalStackCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Grid -> GridCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Sensor -> SensorCard(card, stateMap, modifier)
        is LovelaceCard.PictureGlance -> PictureGlanceCard(card, stateMap, onAction, modifier)
        is LovelaceCard.PictureEntity -> PictureEntityCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Area -> AreaCard(card, stateMap, onAction, modifier)
        is LovelaceCard.HistoryGraph -> HistoryGraphCard(card, stateMap, modifier)
        is LovelaceCard.AlarmPanel -> AlarmPanelCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Map -> MapCard(card, stateMap, modifier)
        is LovelaceCard.Thermostat -> ThermostatCard(card, stateMap, onAction, modifier)
        is LovelaceCard.MediaControl -> MediaControlCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Humidifier -> HumidifierCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Conditional -> {
            val passes = remember(card.conditions, stateMap) {
                evaluateConditions(card.conditions, stateMap)
            }
            if (passes) {
                LovelaceCardRenderer(card.card, stateMap, onAction, modifier)
            } else {
                // Mirror HA: when conditions don't pass, the card collapses
                // to nothing. We don't render a placeholder because that
                // would defeat the point of the conditional wrapper.
                Spacer(Modifier.height(0.dp))
            }
        }
        is LovelaceCard.Unsupported -> UnsupportedCard(card, stateMap, onAction, modifier)
    }
}

/**
 * Bridge between an [onAction] callback and HA's repository. The screen
 * layer (DashboardViewScreen) wires this up once; per-card renderers stay
 * Compose-pure.
 *
 * Handles the four [LovelaceAction] variants:
 *  - `CallService`  → `haRepository.call(ServiceCall)` for entity-targeted
 *    actions, or `callRawService` when there's no target.
 *  - `Toggle` (Builtin) → routes to `homeassistant.toggle` for any entity
 *    we can resolve a domain for.
 *  - `MoreInfo` / `Navigate` / `Url` → bubbles via [onNavigate] /
 *    [onOpenUrl] so the screen can navigate / launch a browser intent
 *    without per-card surfaces having to know about Compose Navigation.
 */
suspend fun dispatchLovelaceAction(
    action: LovelaceAction,
    fallbackEntityId: String?,
    haRepository: HaRepository,
    onNavigate: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onMoreInfo: (String) -> Unit,
) {
    when (action) {
        is LovelaceAction.CallService -> {
            val parts = action.service.split('.', limit = 2)
            if (parts.size != 2) return
            val domain = parts[0]
            val service = parts[1]
            val target = action.entityId ?: fallbackEntityId
            val payload = action.data ?: kotlinx.serialization.json.JsonObject(emptyMap())
            if (target != null) {
                safeEntityId(target)?.let { eid ->
                    haRepository.call(ServiceCall(target = eid, service = service, data = payload))
                } ?: run {
                    haRepository.callRawService(domain = domain, service = service, data = payload)
                }
            } else {
                haRepository.callRawService(domain = domain, service = service, data = payload)
            }
        }
        is LovelaceAction.Navigate -> onNavigate(action.path)
        is LovelaceAction.Url -> onOpenUrl(action.url)
        is LovelaceAction.Builtin -> when (action.name) {
            "toggle" -> {
                val target = fallbackEntityId ?: return
                safeEntityId(target)?.let { eid ->
                    // For domains we know, use the strongly-typed setSwitch helper
                    // so the right HA service name is picked (turn_on/turn_off for
                    // switches, open_cover/close_cover for covers, etc.). Fall back
                    // to homeassistant.toggle for anything outside our [Domain] enum.
                    val state = stateMapForToggle()
                    val isOn = state?.let { runCatching { it[eid] }.getOrNull()?.isOn } ?: false
                    haRepository.call(ServiceCall.tapAction(eid, isOn))
                } ?: run {
                    haRepository.callRawService(
                        domain = "homeassistant",
                        service = "toggle",
                        data = kotlinx.serialization.json.buildJsonObject {
                            put("entity_id", kotlinx.serialization.json.JsonPrimitive(target))
                        },
                    )
                }
            }
            "more-info" -> fallbackEntityId?.let(onMoreInfo)
            "none" -> Unit
            else -> Unit
        }
    }
}

/** Stub used by the toggle path. currently always null because HA's
 *  per-domain `<domain>.toggle` services inspect server-side state.
 *  Kept as a function so a future caller can plumb the real state map
 *  through without touching every renderer. */
private fun stateMapForToggle(): Map<EntityId, EntityState>? = null

/**
 * Default action when a card-level `tap_action` is missing. Mirrors HA:
 *  - `light.*`, `switch.*`, `input_boolean.*`, `automation.*`,
 *    `fan.*`, `lock.*`, `cover.*` → toggle.
 *  - `scene.*`, `script.*`, `button.*`, `input_button.*` → call .turn_on
 *    (or .press, for buttons) so the card acts as a fire-and-forget.
 *  - Anything else → `more-info` (which the host screen turns into a
 *    drill-in toast for now).
 */
fun defaultTapAction(entityId: String): LovelaceAction {
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    return when (domain) {
        "light", "switch", "input_boolean", "automation", "fan", "lock",
        "cover", "media_player", "humidifier", "climate", "remote", "siren",
        "valve", "vacuum", "lawn_mower", "water_heater" ->
            LovelaceAction.Builtin("toggle")
        "scene" -> LovelaceAction.CallService("scene.turn_on", entityId, null)
        "script" -> LovelaceAction.CallService("script.turn_on", entityId, null)
        "button" -> LovelaceAction.CallService("button.press", entityId, null)
        "input_button" -> LovelaceAction.CallService("input_button.press", entityId, null)
        else -> LovelaceAction.Builtin("more-info")
    }
}

/**
 * Pure helper: evaluate the (already-parsed) conditional rules against
 * the live state map. Empty conditions list → true (HA semantics).
 */
fun evaluateConditions(
    conditions: List<LovelaceCondition>,
    stateMap: EntityStates,
): Boolean {
    if (conditions.isEmpty()) return true
    return conditions.all { cond ->
        when (cond) {
            is LovelaceCondition.StateEquals -> {
                val eid = safeEntityId(cond.entityId) ?: return@all true
                // Fail closed when the gating entity has no live state: HA hides
                // a conditional whose entity is missing/unknown rather than
                // showing it. Condition entities are subscribed (see the
                // ViewModel + EntityStates traversal), so a genuinely-present
                // entity will have state here; only truly-absent entities fail.
                val state = stateMap[eid] ?: return@all false
                state.rawState.equals(cond.state, ignoreCase = true)
            }
            is LovelaceCondition.NumericState -> {
                val eid = safeEntityId(cond.entityId) ?: return@all true
                val state = stateMap[eid] ?: return@all false
                val value = state.raw?.toDouble() ?: state.rawState?.toDoubleOrNull() ?: return@all false
                val above = cond.above?.let { value > it } ?: true
                val below = cond.below?.let { value < it } ?: true
                above && below
            }
            LovelaceCondition.AlwaysTrue -> true
        }
    }
}

/**
 * Shared chrome for every card body: surface background, hairline border,
 * 4dp radius. Cards override this when they need a different fill (e.g.
 * markdown gets a softer surface; stacks get no chrome of their own
 * because they only wrap children).
 */
@Composable
fun CardSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    accent: androidx.compose.ui.graphics.Color = R1.Hairline,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, accent, R1.ShapeM)
            .padding(vertical = 10.dp),
    ) {
        if (!title.isNullOrBlank()) {
            Text(
                text = title,
                style = R1.sectionHeader,
                color = R1.InkSoft,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 4.dp),
            )
            Spacer(Modifier.height(2.dp))
        }
        content()
    }
}

/**
 * Convenience: resolve the friendly name with the card-side override
 * applied. Falls back to HA's friendly_name, then the entity_id local
 * part, then the empty string. Lives here so each card variant doesn't
 * reimplement the resolution.
 */
fun resolveName(
    override: String?,
    state: EntityState?,
    entityIdRaw: String,
): String {
    if (!override.isNullOrBlank()) return override
    state?.friendlyName?.takeUnless { it.isBlank() }?.let { return it }
    val local = entityIdRaw.substringAfter('.', missingDelimiterValue = entityIdRaw)
    return local.replace('_', ' ').replaceFirstChar { it.uppercase() }
}
