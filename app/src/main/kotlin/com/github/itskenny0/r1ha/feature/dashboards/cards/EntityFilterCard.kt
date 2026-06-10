package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.lovelace.EntityFilterEntry
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConditionContext
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.StateFilterRule
import com.github.itskenny0.r1ha.core.lovelace.entityFilterPasses
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Renderer for HA's `entity-filter` card. Each configured entity is run through
 * the card's filter (the modern `conditions:` Batch B evaluator, or the legacy
 * operator-form `state_filter:`, with per-entity overrides taking precedence),
 * and the survivors render as the wrapped `card:` type (default entities card)
 * with the survivor list injected.
 *
 * When nothing passes and `show_empty` is false the card collapses to nothing,
 * mirroring HA; otherwise a small placeholder keeps the card visible so the
 * layout doesn't jump.
 */
@Composable
fun EntityFilterCard(
    card: LovelaceCard.EntityFilter,
    stateMap: EntityStates,
    onAction: (LovelaceAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Live condition context so `conditions:` filters (time / screen / user /
    // numeric cross-entity) re-evaluate when their inputs change. Covers both the
    // card-level and per-entity condition lists so a `time` rule on either ticks.
    val allConditions = remember(card.conditions, card.entries) {
        card.conditions + card.entries.flatMap { it.conditions }
    }
    val context = rememberLovelaceConditionContext(allConditions)
    val survivors = remember(card.entries, card.stateFilter, card.conditions, stateMap, context) {
        filterEntityFilterEntries(card.entries, card.stateFilter, card.conditions, stateMap, context)
    }
    if (survivors.isEmpty() && !card.showEmpty) return

    if (survivors.isEmpty()) {
        CardSurface(modifier = modifier, title = card.title?.takeUnless { it.isBlank() }) {
            EmptyRow(text = "Nothing matches the filter")
        }
        return
    }

    // Render the survivors through the wrapped `card:` type (default entities),
    // injecting the entities list. Reuse the recursive card renderer so any HA
    // card type can be the wrapper. The parser already rejected an entity-filter
    // wrapper, so this can't recurse into another entity-filter.
    val wrappedConfig = remember(card.wrappedCard, survivors, card.title) {
        buildWrappedCardConfig(card.wrappedCard, survivors, card.title)
    }
    val wrappedCard = remember(wrappedConfig) { LovelaceParser.parseCard(wrappedConfig) }
    LovelaceCardRenderer(wrappedCard, stateMap, onAction, modifier)
}

/**
 * Pure filter over the entity-filter entries: keep those that pass the card-level
 * or per-entity filter (see [entityFilterPasses]). Returns the surviving
 * [EntityRow]s in config order. Stateless so the filtering decision is unit-tested
 * separately ([entityFilterPasses]); this wires it to the live state map.
 */
internal fun filterEntityFilterEntries(
    entries: List<EntityFilterEntry>,
    cardStateFilter: List<StateFilterRule>,
    cardConditions: List<LovelaceCondition>,
    stateMap: EntityStates,
    context: LovelaceConditionContext = LovelaceConditionContext.EMPTY,
): List<EntityRow> {
    val states = HashMap<String, String>()
    fun stateOf(raw: String): String? = states.getOrPut(raw) {
        safeEntityId(raw)?.let { stateMap[it]?.rawState } ?: return@getOrPut MISSING
    }.takeUnless { it === MISSING }
    // Pre-fill referenced states lazily through the lookup; entityFilterPasses
    // reads from the snapshot map, so build it from every entity it might touch.
    entries.forEach { stateOf(it.row.entityId) }
    cardConditions.forEach { collectConditionEntities(it).forEach(::stateOf) }
    entries.forEach { e -> e.conditions.forEach { collectConditionEntities(it).forEach(::stateOf) } }
    val snapshot = states.filterValues { it !== MISSING }

    val attributeOf: (String, String) -> String? = { entityId, attr ->
        safeEntityId(entityId)?.let { eid ->
            (stateMap[eid]?.attributesJson?.get(attr) as? JsonPrimitive)?.content
        }
    }
    return entries.filter { entry ->
        entityFilterPasses(entry, cardStateFilter, cardConditions, snapshot, attributeOf, context)
    }.map { it.row }
}

/** Sentinel for "entity has no state" so the lazy lookup memoises absence. */
private val MISSING = String()

/** Gather entity ids a condition references so the filter snapshot includes
 *  their states (cross-entity numeric bounds, dereferenced state values, etc.). */
private fun collectConditionEntities(cond: LovelaceCondition): List<String> = when (cond) {
    is LovelaceCondition.StateEquals -> listOfNotNull(cond.entityId) + cond.states
    is LovelaceCondition.NumericState ->
        listOfNotNull(cond.entityId, cond.aboveEntity, cond.belowEntity)
    is LovelaceCondition.And -> cond.conditions.flatMap(::collectConditionEntities)
    is LovelaceCondition.Or -> cond.conditions.flatMap(::collectConditionEntities)
    is LovelaceCondition.Not -> cond.conditions.flatMap(::collectConditionEntities)
    else -> emptyList()
}

/**
 * Build the wrapped card config: the survivors' entity ids injected as the
 * `entities:` array, merged onto the configured `card:` (default `entities`).
 * The injected entities win over any `entities:` the wrapped config carried (HA
 * spreads the base config then overrides `entities`). The card title is carried
 * onto the wrapper when the wrapper itself has none.
 */
private fun buildWrappedCardConfig(
    wrapped: JsonObject?,
    survivors: List<EntityRow>,
    title: String?,
): JsonObject = buildJsonObject {
    // Base: the wrapped config's keys (minus `entities`, which we inject), or an
    // entities card when no `card:` was given.
    val base = wrapped ?: JsonObject(mapOf("type" to JsonPrimitive("entities")))
    base.forEach { (k, v) -> if (k != "entities") put(k, v) }
    if (base["type"] == null) put("type", JsonPrimitive("entities"))
    if (base["title"] == null && !title.isNullOrBlank()) put("title", JsonPrimitive(title))
    put("entities", JsonArray(survivors.map { rowToConfig(it) }))
}

/** Reconstruct one survivor row back into a card-config entry, preserving the
 *  per-row name / icon overrides the wrapped card consumes. */
private fun rowToConfig(row: EntityRow): JsonObject = buildJsonObject {
    put("entity", JsonPrimitive(row.entityId))
    row.name?.let { put("name", JsonPrimitive(it)) }
    row.icon?.let { put("icon", JsonPrimitive(it)) }
    row.secondaryInfo?.let { put("secondary_info", JsonPrimitive(it)) }
}
