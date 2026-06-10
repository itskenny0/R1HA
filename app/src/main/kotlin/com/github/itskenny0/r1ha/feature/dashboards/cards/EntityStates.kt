package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Immutable
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.ConditionalRowPayload
import com.github.itskenny0.r1ha.core.lovelace.EntitiesItem
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.SpecialRow

/**
 * Stable, value-equal wrapper around a slice of the live entity-state map.
 *
 * Compose treats a bare `Map<K, V>` as an UNSTABLE parameter: any card that
 * takes a `Map` recomposes whenever the map *reference* changes, which the
 * dashboards renderer does on every single websocket state event (the
 * repository emits a fresh map each tick). On a view with a dozen cards a
 * single light toggling would recompose all twelve.
 *
 * Wrapping the map in an [Immutable] holder with value-based [equals] lets
 * Compose skip a card whose own slice didn't change. Combined with the
 * per-card slicing in the renderer (each card is handed only the entities it
 * references), an entity update only recomposes the card(s) that actually
 * show that entity.
 *
 * The holder exposes [get] so existing renderer call sites (`stateMap[eid]`)
 * keep working verbatim after the parameter type swap.
 */
@Immutable
class EntityStates private constructor(private val map: Map<String, EntityState>) {

    /** Look up by typed id. Kept so existing `stateMap[eid]` call sites work. */
    operator fun get(id: EntityId): EntityState? = map[id.value]

    /**
     * Look up by the raw `domain.object_id` string. This is the domain-agnostic
     * path: an entity whose domain isn't in R1HA's [com.github.itskenny0.r1ha.core.ha.Domain]
     * enum (a custom integration, `sun`, `device_tracker`, etc.) can't be turned
     * into an [EntityId], but HA still serves its state. Cards key on the raw id
     * so those entities render their reading instead of a blank.
     */
    fun byRaw(rawId: String): EntityState? = map[rawId]

    val isEmpty: Boolean get() = map.isEmpty()

    /**
     * Narrow this holder to just the entities a [card] (and its descendants)
     * references. Returns the same instance when the card has no entity
     * references so we don't allocate an empty holder per render.
     */
    fun sliceFor(card: LovelaceCard): EntityStates {
        if (map.isEmpty()) return this
        val ids = LinkedHashSet<String>()
        collectEntityIds(card, ids)
        if (ids.isEmpty()) return EMPTY
        val slice = LinkedHashMap<String, EntityState>(ids.size)
        for (id in ids) {
            map[id]?.let { slice[id] = it }
        }
        return EntityStates(slice)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EntityStates) return false
        return map == other.map
    }

    override fun hashCode(): Int = map.hashCode()

    companion object {
        val EMPTY: EntityStates = EntityStates(emptyMap())

        /** Build from a raw-id-keyed map (the domain-agnostic dashboards path). */
        fun ofRaw(map: Map<String, EntityState>): EntityStates =
            if (map.isEmpty()) EMPTY else EntityStates(map)

        /** Build from a typed-id-keyed map (legacy/favourites path). */
        fun of(map: Map<EntityId, EntityState>): EntityStates =
            if (map.isEmpty()) EMPTY else EntityStates(map.mapKeys { it.key.value })
    }
}

/**
 * Walk a card tree collecting every referenced entity id. Mirrors the
 * traversal in DashboardsViewModel.collectEntityIdsFromCard but stays in the
 * renderer layer so the per-card slicing is self-contained.
 */
internal fun collectEntityIds(card: LovelaceCard, sink: MutableSet<String>) {
    when (card) {
        is LovelaceCard.Entities -> {
            // Walk the full rowItems list so entity refs inside special rows
            // (attribute, button, buttons, conditional) are included in the
            // slice and the card re-evaluates when those entities change.
            card.rowItems.forEach { item ->
                when (item) {
                    is EntitiesItem.Entity -> sink.addEntity(item.row.entityId)
                    is EntitiesItem.Special -> collectSpecialRowEntityIds(item.row, sink)
                }
            }
        }
        is LovelaceCard.Glance -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.Button -> card.entityId?.let { sink.addEntity(it) }
        is LovelaceCard.Tile -> sink.addEntity(card.entityId)
        is LovelaceCard.Light -> sink.addEntity(card.entityId)
        is LovelaceCard.Gauge -> sink.addEntity(card.entityId)
        is LovelaceCard.WeatherForecast -> sink.addEntity(card.entityId)
        is LovelaceCard.VerticalStack -> card.cards.forEach { collectEntityIds(it, sink) }
        is LovelaceCard.HorizontalStack -> card.cards.forEach { collectEntityIds(it, sink) }
        is LovelaceCard.Grid -> card.cards.forEach { collectEntityIds(it, sink) }
        is LovelaceCard.Conditional -> {
            // A conditional gates on its own condition entities AND renders a
            // child, so the slice must cover both or the wrapper would never
            // re-evaluate when the gating entity changes. Recurses through
            // and/or/not groups and picks up cross-entity numeric bounds.
            card.conditions.forEach { collectConditionEntities(it, sink) }
            collectEntityIds(card.card, sink)
        }
        is LovelaceCard.Sensor -> sink.addEntity(card.entityId)
        is LovelaceCard.PictureGlance -> {
            card.cameraImage?.let { sink.addEntity(it) }
            card.entities.forEach { sink.addEntity(it.entityId) }
        }
        is LovelaceCard.PictureEntity -> {
            sink.addEntity(card.entityId)
            card.imageEntity?.let { sink.addEntity(it) }
        }
        is LovelaceCard.Area -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.HistoryGraph -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.AlarmPanel -> sink.addEntity(card.entityId)
        is LovelaceCard.Map -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.Thermostat -> sink.addEntity(card.entityId)
        is LovelaceCard.MediaControl -> sink.addEntity(card.entityId)
        is LovelaceCard.Humidifier -> sink.addEntity(card.entityId)
        is LovelaceCard.EntityFilter -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.Statistic -> sink.addEntity(card.entityId)
        is LovelaceCard.StatisticsGraph -> card.entityIds.forEach { sink.addEntity(it) }
        is LovelaceCard.Picture -> card.imageEntity?.let { sink.addEntity(it) }
        is LovelaceCard.Shortcut -> Unit
        is LovelaceCard.Distribution -> card.entries.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.Markdown -> Unit
        is LovelaceCard.Heading -> card.badges.forEach { it.entityId?.let(sink::addEntity) }
        is LovelaceCard.Logbook -> Unit
        is LovelaceCard.Clock -> Unit
        is LovelaceCard.PictureElements -> {
            card.cameraImage?.let { sink.addEntity(it) }
            card.elements.forEach { el -> el.entityId?.let { sink.addEntity(it) } }
        }
        is LovelaceCard.Unsupported -> card.entityRefs.forEach { sink.addEntity(it) }
    }
}

/**
 * Collect every entity id a [condition] gates on, recursing through the
 * and/or/not logical groups and the cross-entity numeric bounds. Keeping this
 * exhaustive guarantees the per-card slice (and the ViewModel's subscription
 * set, which delegates here) covers each gating entity, so a conditional card
 * re-evaluates whenever any of its inputs change.
 */
internal fun collectConditionEntities(
    condition: com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition,
    sink: MutableSet<String>,
) {
    when (condition) {
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.StateEquals -> {
            condition.entityId?.let { sink.addEntity(it) }
            // A listed state value that is itself an entity id is dereferenced at
            // eval time; observe it so its change re-triggers the gate.
            condition.states.forEach { sink.addEntity(it) }
        }
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.NumericState -> {
            condition.entityId?.let { sink.addEntity(it) }
            condition.aboveEntity?.let { sink.addEntity(it) }
            condition.belowEntity?.let { sink.addEntity(it) }
        }
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.And ->
            condition.conditions.forEach { collectConditionEntities(it, sink) }
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Or ->
            condition.conditions.forEach { collectConditionEntities(it, sink) }
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Not ->
            condition.conditions.forEach { collectConditionEntities(it, sink) }
        // `user` / `screen` / `time` / `view_columns` gate on non-entity inputs
        // (current user, window size, clock, column count) supplied by the
        // condition context, so they contribute no entity to observe. `location`
        // gates on the current user's person entity, which isn't statically known
        // here; its live re-evaluation rides the dashboards person-state stream.
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.User,
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Screen,
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Time,
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Location,
        is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.ViewColumns,
        com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.Never,
        com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.AlwaysTrue -> Unit
    }
}

/**
 * Collect entity ids referenced by a special row so they are included in the
 * per-card state slice and the card re-evaluates when those entities change.
 * Recursively handles the conditional row's conditions and wrapped row.
 */
internal fun collectSpecialRowEntityIds(row: SpecialRow, sink: MutableSet<String>) {
    when (row) {
        is SpecialRow.Attribute -> sink.addEntity(row.entityId)
        is SpecialRow.Button -> row.entityId?.let { sink.addEntity(it) }
        is SpecialRow.Buttons -> row.entries.forEach { it.entityId?.let(sink::addEntity) }
        is SpecialRow.Conditional -> {
            // Condition gating entities + entities inside the wrapped row itself.
            row.conditions.forEach { collectConditionEntities(it, sink) }
            when (val payload = row.row) {
                is ConditionalRowPayload.EntityRowPayload -> sink.addEntity(payload.row.entityId)
                is ConditionalRowPayload.SpecialRowPayload -> collectSpecialRowEntityIds(payload.row, sink)
            }
        }
        // Section, divider, text, weblink, cast, and unknown rows carry no entity refs.
        is SpecialRow.Section,
        is SpecialRow.Divider,
        is SpecialRow.Text,
        is SpecialRow.Weblink,
        is SpecialRow.Cast,
        is SpecialRow.Unknown -> Unit
    }
}

/** Add a raw entity id, keyed verbatim. Domain-agnostic: a `domain.object_id`
 *  shape is accepted regardless of whether the domain is in R1HA's enum, so
 *  custom-integration entities are sliced/observed like any other. */
private fun MutableSet<String>.addEntity(raw: String) {
    if (raw.isNotBlank() && raw.indexOf('.').let { it > 0 && it < raw.length - 1 }) add(raw)
}
