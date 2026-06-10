package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Shared resolver for an area card's member entities.
 *
 * The area registry isn't reachable over plain REST, so (like the Areas browser)
 * we render a one-pass Jinja template that hands back every area's id and member
 * entity ids, plus each entity's owning device id (for the summary's per-device
 * dedupe). The same dashboard can host several area cards, each recomposing
 * independently, so the result is cached process-wide keyed by nothing (the
 * registry map is global) with a short TTL: every card instance shares one
 * template call rather than firing one per instance per recomposition.
 *
 * Member *state* still comes from [HaRepository.listAllEntities] (the same
 * snapshot the rest of the dashboard uses), joined to the registry map here.
 */
object AreaRegistryCache {
    /** One snapshot of the area-to-entities map plus per-entity device ids. */
    data class Snapshot(
        /** area_id -> ordered member entity ids. */
        val entitiesByArea: Map<String, List<String>>,
        /** entity_id -> owning device_id (absent when the entity has no device). */
        val deviceByEntity: Map<String, String>,
        val fetchedAtMs: Long,
    )

    private const val TTL_MS = 60_000L
    private val mutex = Mutex()

    @Volatile
    private var cached: Snapshot? = null

    /** Force the next [get] to refetch (used after a registry-changing action). */
    fun invalidate() {
        cached = null
    }

    /**
     * Return the registry snapshot, refetching when stale or absent. Concurrent
     * callers coalesce behind the mutex so a dashboard of N area cards fires one
     * template call, not N.
     */
    suspend fun get(repo: HaRepository, nowMs: Long): Snapshot? {
        cached?.let { if (nowMs - it.fetchedAtMs < TTL_MS) return it }
        return mutex.withLock {
            cached?.let { if (nowMs - it.fetchedAtMs < TTL_MS) return it }
            val fresh = fetch(repo, nowMs)
            if (fresh != null) cached = fresh
            fresh ?: cached
        }
    }

    private suspend fun fetch(repo: HaRepository, nowMs: Long): Snapshot? {
        // One pass: every area's id + member entities + each entity's device id.
        val tpl = """
            {%- set out = namespace(items=[]) -%}
            {%- for area in areas() -%}
              {%- set ents = namespace(list=[]) -%}
              {%- for e in area_entities(area) -%}
                {%- set ents.list = ents.list + [{"e": e, "d": device_id(e)}] -%}
              {%- endfor -%}
              {%- set out.items = out.items + [{"id": area, "entities": ents.list}] -%}
            {%- endfor -%}
            {{ out.items | tojson }}
        """.trimIndent()
        return repo.renderTemplate(tpl).fold(
            onSuccess = { rendered ->
                runCatching {
                    val arr = Json.parseToJsonElement(rendered) as? JsonArray
                        ?: error("area template not an array")
                    val byArea = LinkedHashMap<String, List<String>>()
                    val byEntity = HashMap<String, String>()
                    for (el in arr) {
                        val o = el as? JsonObject ?: continue
                        val areaId = (o["id"] as? JsonPrimitive)?.content ?: continue
                        val ents = o["entities"] as? JsonArray ?: continue
                        val ids = ArrayList<String>(ents.size)
                        for (entEl in ents) {
                            val eo = entEl as? JsonObject ?: continue
                            val eid = (eo["e"] as? JsonPrimitive)?.content ?: continue
                            ids.add(eid)
                            val dev = (eo["d"] as? JsonPrimitive)?.content
                            if (!dev.isNullOrBlank() && dev != "null") byEntity[eid] = dev
                        }
                        byArea[areaId] = ids
                    }
                    Snapshot(byArea, byEntity, nowMs)
                }.getOrElse { t ->
                    R1Log.w("AreaResolver", "parse failed: ${t.message}")
                    null
                }
            },
            onFailure = { t ->
                R1Log.w("AreaResolver", "fetch failed: ${t.message}")
                null
            },
        )
    }
}

/** The resolved members of one area card: the live states, the device-key
 *  lookup for summary dedupe, and a loaded flag so the card can hold its layout
 *  while the first resolve is in flight. */
data class AreaMembers(
    val states: List<EntityState>,
    val deviceKeyOf: (EntityState) -> String,
    val loaded: Boolean,
)

private val EMPTY_AREA_MEMBERS = AreaMembers(emptyList(), { it.id.value }, loaded = false)

/**
 * Resolve [areaId]'s member entities to live states, excluding [excludeEntities].
 * Backed by the shared [AreaRegistryCache] (one template call shared across all
 * area cards) joined to the entity-state snapshot. Refetches on a coarse cadence
 * so a dashboard stays fresh without hammering the template endpoint.
 */
@Composable
fun rememberAreaMembers(
    repo: HaRepository?,
    areaId: String,
    excludeEntities: Set<String>,
): AreaMembers {
    var members by remember(areaId) { mutableStateOf(EMPTY_AREA_MEMBERS) }
    if (repo != null) {
        LaunchedEffect(areaId, excludeEntities) {
            while (true) {
                val now = System.currentTimeMillis()
                val snapshot = AreaRegistryCache.get(repo, now)
                val memberIds = snapshot?.entitiesByArea?.get(areaId).orEmpty()
                    .filter { it !in excludeEntities }
                if (memberIds.isNotEmpty()) {
                    repo.listAllEntities().onSuccess { all ->
                        val byId = all.associateBy { it.id.value }
                        val states = memberIds.mapNotNull { byId[it] }
                        val devMap = snapshot?.deviceByEntity.orEmpty()
                        members = AreaMembers(
                            states = states,
                            deviceKeyOf = { devMap[it.id.value] ?: it.id.value },
                            loaded = true,
                        )
                    }
                } else {
                    members = members.copy(loaded = true)
                }
                kotlinx.coroutines.delay(60_000L)
            }
        }
    }
    return members
}
