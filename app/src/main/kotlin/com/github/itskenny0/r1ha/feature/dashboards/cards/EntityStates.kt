package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Immutable
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard

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
class EntityStates(private val map: Map<EntityId, EntityState>) {

    operator fun get(id: EntityId): EntityState? = map[id]

    val isEmpty: Boolean get() = map.isEmpty()

    /**
     * Narrow this holder to just the entities a [card] (and its descendants)
     * references. Returns the same instance when the card has no entity
     * references so we don't allocate an empty holder per render.
     */
    fun sliceFor(card: LovelaceCard): EntityStates {
        if (map.isEmpty()) return this
        val ids = HashSet<EntityId>()
        collectEntityIds(card, ids)
        if (ids.isEmpty()) return EMPTY
        val slice = LinkedHashMap<EntityId, EntityState>(ids.size)
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

        fun of(map: Map<EntityId, EntityState>): EntityStates =
            if (map.isEmpty()) EMPTY else EntityStates(map)
    }
}

/**
 * Walk a card tree collecting every referenced entity id. Mirrors the
 * traversal in DashboardsViewModel.collectEntityIdsFromCard but stays in the
 * renderer layer so the per-card slicing is self-contained.
 */
internal fun collectEntityIds(card: LovelaceCard, sink: MutableSet<EntityId>) {
    when (card) {
        is LovelaceCard.Entities -> card.entities.forEach { sink.addEntity(it.entityId) }
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
            // re-evaluate when the gating entity changes.
            card.conditions.forEach { cond ->
                when (cond) {
                    is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.StateEquals ->
                        sink.addEntity(cond.entityId)
                    is com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.NumericState ->
                        sink.addEntity(cond.entityId)
                    com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition.AlwaysTrue -> Unit
                }
            }
            collectEntityIds(card.card, sink)
        }
        is LovelaceCard.Sensor -> sink.addEntity(card.entityId)
        is LovelaceCard.PictureGlance -> {
            card.cameraImage?.let { sink.addEntity(it) }
            card.entities.forEach { sink.addEntity(it.entityId) }
        }
        is LovelaceCard.PictureEntity -> sink.addEntity(card.entityId)
        is LovelaceCard.Area -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.HistoryGraph -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.AlarmPanel -> sink.addEntity(card.entityId)
        is LovelaceCard.Map -> card.entities.forEach { sink.addEntity(it.entityId) }
        is LovelaceCard.Thermostat -> sink.addEntity(card.entityId)
        is LovelaceCard.MediaControl -> sink.addEntity(card.entityId)
        is LovelaceCard.Humidifier -> sink.addEntity(card.entityId)
        is LovelaceCard.Markdown -> Unit
        is LovelaceCard.Heading -> Unit
        is LovelaceCard.Unsupported -> card.entityRefs.forEach { sink.addEntity(it) }
    }
}

private fun MutableSet<EntityId>.addEntity(raw: String) {
    safeEntityId(raw)?.let { add(it) }
}
