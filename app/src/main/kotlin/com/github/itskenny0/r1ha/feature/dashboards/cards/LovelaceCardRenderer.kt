package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConditionContext
import com.github.itskenny0.r1ha.core.lovelace.evaluateMediaQuery
import com.github.itskenny0.r1ha.core.lovelace.evaluateTimeWindow
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.nav.Routes

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
        is LovelaceCard.Gauge -> GaugeCard(card, stateMap, onAction, modifier)
        is LovelaceCard.WeatherForecast -> WeatherForecastCard(card, stateMap, modifier)
        is LovelaceCard.Markdown -> MarkdownCard(card, onAction, modifier)
        is LovelaceCard.Heading -> HeadingCard(card, stateMap, onAction, modifier)
        is LovelaceCard.VerticalStack -> VerticalStackCard(card, stateMap, onAction, modifier)
        is LovelaceCard.HorizontalStack -> HorizontalStackCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Grid -> GridCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Sensor -> SensorCard(card, stateMap, onAction, modifier)
        is LovelaceCard.PictureGlance -> PictureGlanceCard(card, stateMap, onAction, modifier)
        is LovelaceCard.PictureEntity -> PictureEntityCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Area -> AreaCard(card, stateMap, onAction, modifier)
        is LovelaceCard.HistoryGraph -> HistoryGraphCard(card, stateMap, onAction, modifier)
        is LovelaceCard.AlarmPanel -> AlarmPanelCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Map -> MapCard(card, stateMap, modifier)
        is LovelaceCard.Thermostat -> ThermostatCard(card, stateMap, onAction, modifier)
        is LovelaceCard.MediaControl -> MediaControlCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Humidifier -> HumidifierCard(card, stateMap, onAction, modifier)
        is LovelaceCard.EntityFilter -> EntityFilterCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Statistic -> StatisticCard(card, stateMap, onAction, modifier)
        is LovelaceCard.StatisticsGraph -> StatisticsGraphCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Picture -> PicturePlainCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Logbook -> LogbookCard(card, modifier)
        is LovelaceCard.Clock -> ClockCard(card, modifier)
        is LovelaceCard.Shortcut -> ShortcutCard(card, onAction, modifier)
        is LovelaceCard.Calendar -> CalendarCard(card, modifier)
        is LovelaceCard.HomeSummary -> HomeSummaryCard(card, onAction, modifier)
        is LovelaceCard.Updates -> UpdatesCard(card, onAction, modifier)
        is LovelaceCard.Repairs -> RepairsCard(card, onAction, modifier)
        is LovelaceCard.EmptyState -> EmptyStateCard(card, modifier)
        is LovelaceCard.Distribution -> DistributionCard(card, stateMap, onAction, modifier)
        is LovelaceCard.PictureElements -> PictureElementsCard(card, stateMap, onAction, modifier)
        is LovelaceCard.Conditional -> {
            // Read the live condition context (current user, window size, local
            // clock, column count) and re-evaluate on every recomposition the
            // context or state map triggers. The context's clock advances on each
            // time-condition boundary tick (see rememberLovelaceConditionContext),
            // so a `time` gate flips live without a per-card timer here.
            val context = rememberLovelaceConditionContext(card.conditions)
            val passes = remember(card.conditions, stateMap, context) {
                evaluateConditions(card.conditions, stateMap, context)
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
 * Execution path: a [LovelaceAction.confirmation] is consulted FIRST via
 * [confirmGate] (which the screen backs with a native dialog); a dismissed
 * confirmation aborts the action. Then the action type dispatches:
 *  - `CallService`  → `haRepository.call(ServiceCall)` for a single-entity
 *    target, or `callRawService` (with any device/area/floor/label target
 *    merged into the body and passed through to HA) otherwise.
 *  - `Builtin("toggle")` → opposite-direction service for modelled domains,
 *    else `homeassistant.toggle`.
 *  - `Builtin("more-info")` → [onMoreInfo] for the action-level entity
 *    override, then the card fallback.
 *  - `Builtin("assist")` → [onAssist] (the native Assist screen).
 *  - `Navigate` / `Url` → [onNavigate] / [onOpenUrl].
 *  - `Invalid` → [onError] (non-crashing toast), matching HA's failure toasts
 *    for navigate-without-path / url-without-url / call-service-without-service.
 *
 * [confirmGate] returns true to proceed, false to abort. Its default proceeds
 * unconditionally so the function stays usable in isolation; the real screen
 * supplies a dialog-backed gate. [onError] defaults to the app's toast.
 */
suspend fun dispatchLovelaceAction(
    action: LovelaceAction,
    fallbackEntityId: String?,
    haRepository: HaRepository,
    onNavigate: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onMoreInfo: (String) -> Unit,
    stateLookup: (String) -> EntityState? = { null },
    onAssist: () -> Unit = { onNavigate(Routes.ASSIST) },
    confirmGate: suspend (com.github.itskenny0.r1ha.core.lovelace.ActionConfirmation, LovelaceAction) -> Boolean = { _, _ -> true },
    onError: (String) -> Unit = { com.github.itskenny0.r1ha.core.util.Toaster.error(it) },
) {
    // Confirmation gate (HA: shown before any action type runs). Exemption is
    // resolved inside the gate the screen supplies, which knows the current
    // user id (today: none, so the gate always prompts; see isConfirmationExempt).
    action.confirmation?.let { c ->
        if (!confirmGate(c, action)) return
    }
    when (action) {
        is LovelaceAction.CallService -> {
            val parts = action.service.split('.', limit = 2)
            if (parts.size != 2) {
                onError("Malformed service: ${action.service}")
                return
            }
            val domain = parts[0]
            val service = parts[1]
            val payload = action.data ?: kotlinx.serialization.json.JsonObject(emptyMap())
            val target = action.target
            // A target carrying device/area/floor/label ids (or multiple entity
            // ids) can't go through the single-EntityId WS path; merge the whole
            // target into the REST body and let HA expand it server-side.
            val needsRawTarget = target != null && !target.isEmpty &&
                (target.deviceId.isNotEmpty() || target.areaId.isNotEmpty() ||
                    target.floorId.isNotEmpty() || target.labelId.isNotEmpty() ||
                    target.entityId.size > 1)
            when {
                needsRawTarget -> haRepository.callRawService(
                    domain = domain,
                    service = service,
                    data = mergeTargetIntoData(payload, target!!),
                )
                else -> {
                    val single = action.entityId ?: target?.entityId?.firstOrNull() ?: fallbackEntityId
                    if (single != null) {
                        safeEntityId(single)?.let { eid ->
                            haRepository.call(ServiceCall(target = eid, service = service, data = payload))
                        } ?: haRepository.callRawService(domain = domain, service = service, data = payload)
                    } else {
                        haRepository.callRawService(domain = domain, service = service, data = payload)
                    }
                }
            }
        }
        is LovelaceAction.Navigate -> onNavigate(action.path)
        is LovelaceAction.Url -> onOpenUrl(action.url)
        is LovelaceAction.Invalid -> onError(action.reason)
        is LovelaceAction.Builtin -> when (action.name) {
            "toggle" -> {
                // Prefer the action's own entity id (cards now attach it), then the
                // card-level fallback. Without one there's nothing to toggle.
                val target = action.entityId ?: fallbackEntityId
                if (target == null) {
                    onError("No entity to toggle")
                    return
                }
                safeEntityId(target)?.let { eid ->
                    // For domains we model, read the live on/off state and pick the
                    // opposite-direction service (turn_off when on, open_cover when
                    // closed, etc.) via the strongly-typed tapAction helper. The
                    // earlier stub always reported "off", so an already-on entity
                    // got another turn_on and visibly did nothing.
                    val isOn = stateLookup(target)?.isOn ?: false
                    haRepository.call(ServiceCall.tapAction(eid, isOn))
                } ?: run {
                    // Domain isn't in our [Domain] enum (custom integration, etc.).
                    // homeassistant.toggle is state-agnostic server-side, so it flips
                    // correctly without us needing to model the domain.
                    haRepository.callRawService(
                        domain = "homeassistant",
                        service = "toggle",
                        data = kotlinx.serialization.json.buildJsonObject {
                            put("entity_id", kotlinx.serialization.json.JsonPrimitive(target))
                        },
                    )
                }
            }
            // HA: actionConfig.entity (the override) || config.entity (the card).
            "more-info" -> {
                val entity = action.entityId ?: fallbackEntityId
                if (entity != null) onMoreInfo(entity) else onError("No entity for more-info")
            }
            // Open the native Assist screen. pipeline_id / start_listening are
            // parsed but not forwarded: the Assist route accepts no such params.
            "assist" -> onAssist()
            "none" -> Unit
            else -> Unit
        }
    }
}

/**
 * Merge an [ActionTarget] into a service-call [data] body so HA's REST
 * `/api/services` path receives the target alongside the data. HA accepts
 * target keys (entity_id / device_id / area_id / floor_id / label_id) in the
 * POST body and performs device/area/floor/label expansion server-side, so
 * R1HA passes them through verbatim rather than resolving them client-side.
 * A single id collapses to a string; multiple stay an array (HA accepts both).
 * Existing keys already in [data] are not overwritten.
 */
private fun mergeTargetIntoData(
    data: kotlinx.serialization.json.JsonObject,
    target: com.github.itskenny0.r1ha.core.lovelace.ActionTarget,
): kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.buildJsonObject {
    data.forEach { (k, v) -> put(k, v) }
    fun putIds(key: String, ids: List<String>) {
        if (ids.isEmpty() || data.containsKey(key)) return
        if (ids.size == 1) {
            put(key, kotlinx.serialization.json.JsonPrimitive(ids.first()))
        } else {
            put(
                key,
                kotlinx.serialization.json.JsonArray(ids.map { kotlinx.serialization.json.JsonPrimitive(it) }),
            )
        }
    }
    putIds("entity_id", target.entityId)
    putIds("device_id", target.deviceId)
    putIds("area_id", target.areaId)
    putIds("floor_id", target.floorId)
    putIds("label_id", target.labelId)
}

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
            LovelaceAction.Builtin("toggle", entityId)
        "scene" -> LovelaceAction.CallService("scene.turn_on", entityId, null)
        "script" -> LovelaceAction.CallService("script.turn_on", entityId, null)
        "button" -> LovelaceAction.CallService("button.press", entityId, null)
        "input_button" -> LovelaceAction.CallService("input_button.press", entityId, null)
        else -> LovelaceAction.Builtin("more-info", entityId)
    }
}

/**
 * Attach a card's own entity id to a parsed [tap_action] that doesn't carry
 * one. A config `tap_action: toggle` / `more-info` parses to a [Builtin] with
 * no entity (the parser sees only the action object, not the card), and a
 * `call-service` may omit `target`. Binding the card's entity here means the
 * dispatcher always has a target to act on. Actions that already name an
 * entity, or that don't need one (navigate / url), pass through unchanged.
 */
fun LovelaceAction.boundTo(entityId: String?): LovelaceAction = when (this) {
    is LovelaceAction.Builtin -> if (this.entityId == null && entityId != null) copy(entityId = entityId) else this
    is LovelaceAction.CallService -> if (this.entityId == null && entityId != null) copy(entityId = entityId) else this
    else -> this
}

/**
 * Whether a [card] will render any content given the live [stateMap]. A
 * conditional card (an explicit `type: conditional` OR any card carrying a
 * per-card `visibility:` array, both of which the parser models as
 * [LovelaceCard.Conditional]) contributes nothing when its conditions fail.
 *
 * Layout containers (the view body, stacks, grid) consult this BEFORE
 * allocating a slot / inter-card gap so a hidden conditional consumes no
 * layout, matching HA where a failed condition removes the card entirely
 * rather than leaving a hole. Recurses through a conditional that wraps
 * another conditional (e.g. a `visibility:` gate on a `type: conditional`).
 */
fun cardWillRender(
    card: LovelaceCard,
    stateMap: EntityStates,
    context: LovelaceConditionContext = LovelaceConditionContext.EMPTY,
): Boolean =
    if (card is LovelaceCard.Conditional) {
        evaluateConditions(card.conditions, stateMap, context) && cardWillRender(card.card, stateMap, context)
    } else {
        true
    }

/** HA state strings that mean "no usable value"; a state/numeric gate over one
 *  of these fails closed (mirrors the core evaluator's UNUSABLE_STATES). */
private val UNUSABLE_CONDITION_STATES = setOf("unavailable", "unknown", "none", "")

/**
 * Pure helper: evaluate the (already-parsed) conditional rules against the live
 * state map and a runtime [context] (current user, window size, local time,
 * column count, host-card entity fallback). Empty conditions list → true (HA
 * semantics). This is the EntityStates-backed twin of
 * [com.github.itskenny0.r1ha.core.lovelace.evaluateLovelaceConditions]; unlike
 * the core (state-only) evaluator it resolves `attribute:` comparisons from the
 * live attributes JSON and reads numeric values from the parsed
 * [com.github.itskenny0.r1ha.core.ha.EntityState] rather than re-parsing strings.
 * The runtime conditions (`screen` / `time` / `user` / `location` /
 * `view_columns`) delegate to the shared pure helpers so both evaluators agree.
 */
fun evaluateConditions(
    conditions: List<LovelaceCondition>,
    stateMap: EntityStates,
    context: LovelaceConditionContext = LovelaceConditionContext.EMPTY,
): Boolean {
    if (conditions.isEmpty()) return true
    return conditions.all { evaluateOneCondition(it, stateMap, context) }
}

private fun evaluateOneCondition(
    cond: LovelaceCondition,
    stateMap: EntityStates,
    context: LovelaceConditionContext,
): Boolean =
    when (cond) {
        is LovelaceCondition.StateEquals -> {
            // Fail closed when the gating entity has no live state: HA hides a
            // conditional whose entity is missing/unknown rather than showing it.
            // A null entity falls back to the host card's own entity (context).
            val entityId = cond.entityId ?: context.contextEntityId
            val current = entityId?.let { conditionValue(stateMap, it, cond.attribute) }
            if (current == null || current.lowercase() in UNUSABLE_CONDITION_STATES) {
                false
            } else {
                // A listed value that is itself an entity id is ALSO accepted as
                // that entity's current state (HA's getValueFromEntityId).
                val accepted = cond.states.flatMap { value ->
                    val deref = conditionValue(stateMap, value, null)
                        ?.takeUnless { it.lowercase() in UNUSABLE_CONDITION_STATES }
                    if (deref != null) listOf(value, deref) else listOf(value)
                }
                val matches = accepted.any { current.equals(it, ignoreCase = true) }
                if (cond.negate) !matches else matches
            }
        }
        is LovelaceCondition.NumericState -> {
            val entityId = cond.entityId ?: context.contextEntityId
            val value = entityId?.let { conditionNumeric(stateMap, it, cond.attribute) }
            if (value == null) {
                false
            } else {
                // Literal bound wins; otherwise resolve the referenced entity's
                // numeric state. An unresolvable reference fails closed.
                val above = cond.above ?: cond.aboveEntity?.let { conditionNumeric(stateMap, it, null) ?: return false }
                val below = cond.below ?: cond.belowEntity?.let { conditionNumeric(stateMap, it, null) ?: return false }
                val aboveOk = above?.let { value > it } ?: true
                val belowOk = below?.let { value < it } ?: true
                aboveOk && belowOk
            }
        }
        is LovelaceCondition.And -> cond.conditions.all { evaluateOneCondition(it, stateMap, context) }
        is LovelaceCondition.Or ->
            cond.conditions.isEmpty() || cond.conditions.any { evaluateOneCondition(it, stateMap, context) }
        // HA's `not` is the negation of an AND over the group: it passes when NOT
        // every child passes (i.e. at least one fails). An empty group is all-pass,
        // so `not` over it is false-of-true = false... but HA returns true for an
        // absent group, so guard the empty case to match.
        is LovelaceCondition.Not ->
            cond.conditions.isEmpty() || !cond.conditions.all { evaluateOneCondition(it, stateMap, context) }
        // `user`: membership of the current user in the listed ids; unknown user
        // fails closed (HA returns false when hass.user?.id is undefined).
        is LovelaceCondition.User ->
            context.currentUserId != null && cond.userIds.contains(context.currentUserId)
        // `screen`: evaluate the media query against the window; an undecidable
        // query fails open (single-window app).
        is LovelaceCondition.Screen -> {
            val outcome = evaluateMediaQuery(cond.mediaQuery, context.windowWidthPx, context.windowHeightPx)
            if (!outcome.evaluable) true else outcome.matched
        }
        is LovelaceCondition.Time ->
            evaluateTimeWindow(cond, context.nowSecondsOfDay, context.weekday)
        is LovelaceCondition.Location -> {
            if (context.currentUserId == null || cond.locations.isEmpty()) {
                false
            } else {
                val personState = context.personStateForUser()
                personState != null && cond.locations.contains(personState)
            }
        }
        is LovelaceCondition.ViewColumns -> {
            val columns = context.maxColumns
            if (columns == null || columns == 0) {
                true
            } else {
                val minOk = cond.min?.let { columns >= it } ?: true
                val maxOk = cond.max?.let { columns <= it } ?: true
                minOk && maxOk
            }
        }
        // Unmodelled condition shape: fail closed (hide the card).
        LovelaceCondition.Never -> false
        LovelaceCondition.AlwaysTrue -> true
    }

/**
 * Resolve the value a state/numeric condition compares against: the entity's
 * raw state, or the named [attribute] read out of the live attributes JSON.
 * Returns null when the entity (or attribute) is absent.
 */
private fun conditionValue(
    stateMap: EntityStates,
    entityId: String,
    attribute: String?,
): String? {
    val state = stateMap.byRaw(entityId) ?: return null
    if (attribute == null) return state.rawState
    val attrs = state.attributesJson ?: return null
    val el = attrs[attribute] ?: return null
    return (el as? kotlinx.serialization.json.JsonPrimitive)?.content
}

/** Numeric counterpart of [conditionValue]. Prefers the parsed scalar when
 *  reading the state itself; falls back to parsing the string / attribute. */
private fun conditionNumeric(
    stateMap: EntityStates,
    entityId: String,
    attribute: String?,
): Double? {
    if (attribute == null) {
        val state = stateMap.byRaw(entityId) ?: return null
        val rawState = state.rawState
        if (rawState != null && rawState.lowercase() in UNUSABLE_CONDITION_STATES) return null
        return state.raw?.toDouble() ?: rawState?.trim()?.toDoubleOrNull()
    }
    val raw = conditionValue(stateMap, entityId, attribute) ?: return null
    return raw.trim().toDoubleOrNull()
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

/**
 * Composable name resolver that adds `name_type` awareness on top of [resolveName].
 *
 * Resolution order:
 *  1. If [override] is non-blank, return it unchanged (explicit user override wins).
 *  2. If [nameType] is non-null and not "entity", ask [LocalNameResolver] to resolve
 *     each space/comma-separated part (device, area, floor). If at least one part
 *     resolved, return the joined result.
 *  3. Fall through to [resolveName] (friendly_name then prettified entity_id).
 *
 * This keeps the no-regression path: when [nameType] is null or the resolver has no
 * data for the entity, the output is exactly what [resolveName] would produce.
 */
@androidx.compose.runtime.Composable
fun resolveDisplayName(
    override: String?,
    nameType: String?,
    state: EntityState?,
    entityIdRaw: String,
): String {
    if (!override.isNullOrBlank()) return override
    if (!nameType.isNullOrBlank() && nameType.trim().lowercase() != "entity") {
        val resolver = com.github.itskenny0.r1ha.core.theme.LocalNameResolver.current
        val resolved = resolver.resolveParts(nameType, entityIdRaw)
        if (!resolved.isNullOrBlank()) return resolved
    }
    return resolveName(override, state, entityIdRaw)
}
