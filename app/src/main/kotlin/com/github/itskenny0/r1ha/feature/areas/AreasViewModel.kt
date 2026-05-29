package com.github.itskenny0.r1ha.feature.areas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Drives the HA Areas browser. HA's area_registry isn't reachable from
 * a public REST endpoint — it lives behind the WebSocket
 * `config/area_registry/list` command. Rather than extending
 * HaWebSocketClient to support arbitrary command/result calls (which
 * would touch correlation IDs + result futures), we fetch the area
 * data via a server-side Jinja template through the existing
 * `/api/template` endpoint:
 *
 * ```
 * {% set out = namespace(items=[]) %}
 * {% for area in areas() %}
 *   {% set _ = out.items.append({"name": area_name(area), "entities": area_entities(area)}) %}
 * {% endfor %}
 * {{ out.items | tojson }}
 * ```
 *
 * HA renders the template with full access to its area registry and
 * returns the JSON array directly. We parse, sort, and surface.
 */
class AreasViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class Area(
        val name: String,
        val entityIds: List<String>,
        /** Stable HA area_id (e.g. "kitchen"). Surfaced from the
         *  template's `id` field. Drives the drill-in's entity lookup
         *  and is the handle a future rename call would key on. */
        val areaId: String? = null,
    )

    enum class Sort { ALPHA, COUNT }

    /**
     * One domain bucket inside an area's drill-in: the friendly domain
     * label (e.g. "LIGHTS") plus the resolved entity states sorted by
     * name. Grouping by domain mirrors how Search and the card stack
     * present a mixed entity set.
     */
    @androidx.compose.runtime.Stable
    data class DomainGroup(
        val domain: Domain,
        val label: String,
        val entities: List<EntityState>,
    )

    /**
     * Drill-in state for a single area. [resolved] is the subset of the
     * area's entity_ids we could match to a live [EntityState] and whose
     * domain the app supports; [unmatchedCount] is everything else (an
     * entity the registry assigns to the area that isn't currently in
     * the state machine, or a domain the app doesn't model) so the UI
     * can still account for it honestly.
     */
    @androidx.compose.runtime.Stable
    data class DrillState(
        val area: Area,
        val loading: Boolean = true,
        val groups: List<DomainGroup> = emptyList(),
        val unmatchedCount: Int = 0,
        val error: String? = null,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val areas: List<Area> = emptyList(),
        val sort: Sort = Sort.ALPHA,
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Null = the area list is showing; non-null = drilled into one area. */
    private val _drill = MutableStateFlow<DrillState?>(null)
    val drill: StateFlow<DrillState?> = _drill

    fun setSort(sort: Sort) {
        _ui.value = _ui.value.copy(sort = sort)
    }

    /** Sort applied at the read site so toggling never re-fetches. */
    val sortedAreas: kotlinx.coroutines.flow.StateFlow<List<Area>> =
        _ui.map { s ->
            when (s.sort) {
                Sort.ALPHA -> s.areas.sortedBy { it.name.lowercase() }
                Sort.COUNT -> s.areas.sortedByDescending { it.entityIds.size }
            }
        }
            .distinctUntilChanged()
            .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, emptyList())

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            // HA's templating gives us areas() + area_entities(area_id).
            // We pull both in one pass to avoid a per-area network round
            // trip (which would be O(N) requests on a big install).
            val tpl = """
                {%- set out = namespace(items=[]) -%}
                {%- for area in areas() -%}
                  {%- set _ = out.items.append({"id": area, "name": area_name(area), "entities": area_entities(area)}) -%}
                {%- endfor -%}
                {{ out.items | tojson }}
            """.trimIndent()
            haRepository.renderTemplate(tpl).fold(
                onSuccess = { rendered ->
                    runCatching {
                        val root = Json.parseToJsonElement(rendered)
                        val arr = root as? JsonArray
                            ?: error("Unexpected template response shape. Not an array")
                        val list = arr.mapNotNull { el ->
                            val obj = el as? JsonObject ?: return@mapNotNull null
                            val name = (obj["name"] as? JsonPrimitive)?.content
                                ?: (obj["id"] as? JsonPrimitive)?.content
                                ?: return@mapNotNull null
                            val areaId = (obj["id"] as? JsonPrimitive)?.content
                            val entitiesArr = obj["entities"] as? JsonArray
                            val entities = entitiesArr?.mapNotNull {
                                (it as? JsonPrimitive)?.content
                            }.orEmpty()
                            Area(name = name, entityIds = entities, areaId = areaId)
                        }.sortedBy { it.name.lowercase() }
                        R1Log.i("Areas", "loaded ${list.size}")
                        _ui.value = _ui.value.copy(loading = false, areas = list, error = null)
                    }.onFailure { t ->
                        R1Log.w("Areas", "parse failed: ${t.message}")
                        Toaster.error("Areas parse failed. Try Templates to debug")
                        _ui.value = _ui.value.copy(loading = false, error = t.message)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "fetch failed: ${t.message}")
                    Toaster.error("Areas load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /**
     * Open the drill-in for [area]. Pulls the full entity registry
     * snapshot via [HaRepository.listAllEntities] (one round trip, the
     * same call Search uses) and keeps only the entities this area owns,
     * grouped by domain. Entities whose domain the app doesn't model, or
     * that aren't in the current state set, fall into [DrillState.unmatchedCount].
     */
    fun openArea(area: Area) {
        _drill.value = DrillState(area = area, loading = true)
        viewModelScope.launch {
            haRepository.listAllEntities().fold(
                onSuccess = { all ->
                    val wanted = area.entityIds.toHashSet()
                    val byId = all.associateBy { it.id.value }
                    var unmatched = 0
                    val matched = ArrayList<EntityState>(wanted.size)
                    for (eid in area.entityIds) {
                        val state = byId[eid]
                        if (state != null) matched.add(state) else unmatched++
                    }
                    val groups = matched
                        .groupBy { it.id.domain }
                        .map { (domain, list) ->
                            DomainGroup(
                                domain = domain,
                                label = domainLabel(domain),
                                entities = list.sortedBy { it.friendlyName.lowercase() },
                            )
                        }
                        // Controls first, then sensors, then everything else —
                        // the same altitude Search's CONTROLS / SENSORS buckets use.
                        .sortedWith(
                            compareBy(
                                { domainRank(it.domain) },
                                { it.label },
                            ),
                        )
                    // Only the current drill (the user may have backed out
                    // and reopened a different area while this was in flight).
                    if (_drill.value?.area?.name == area.name) {
                        _drill.value = DrillState(
                            area = area,
                            loading = false,
                            groups = groups,
                            unmatchedCount = unmatched,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "drill load failed: ${t.message}")
                    if (_drill.value?.area?.name == area.name) {
                        _drill.value = _drill.value?.copy(
                            loading = false,
                            error = t.message ?: "load failed",
                        )
                    }
                },
            )
        }
    }

    /** Re-run the drill-in load for the currently open area (pull to refresh). */
    fun refreshDrill() {
        _drill.value?.area?.let { openArea(it) }
    }

    /**
     * Rename the currently drilled-in area via HA's area registry. The drill-in
     * carries the stable [Area.areaId] HA keys on. On success we patch the open
     * drill's name (so the top bar updates immediately), refresh the underlying
     * area list (so the rename shows when the user backs out), and re-resolve the
     * drill so the new name flows through. On failure we surface a toast and leave
     * the existing name untouched.
     */
    fun renameArea(newName: String) {
        val current = _drill.value?.area ?: return
        val areaId = current.areaId
        val trimmed = newName.trim()
        if (areaId.isNullOrBlank()) {
            Toaster.error("This area has no stable id to rename")
            return
        }
        if (trimmed.isEmpty() || trimmed == current.name) return
        viewModelScope.launch {
            haRepository.renameArea(areaId, trimmed).fold(
                onSuccess = {
                    R1Log.i("Areas", "renamed '$areaId' to '$trimmed'")
                    Toaster.show("Renamed to '$trimmed'")
                    // Patch the open drill so the top bar reflects the new name
                    // without waiting for the list refresh to round-trip.
                    _drill.value?.let { d ->
                        if (d.area.areaId == areaId) {
                            _drill.value = d.copy(area = d.area.copy(name = trimmed))
                        }
                    }
                    // Refresh the backing list so the new name is in place when
                    // the user returns to it.
                    refresh()
                },
                onFailure = { t ->
                    R1Log.w("Areas", "rename '$areaId' failed: ${t.message}")
                    Toaster.error("Rename failed: ${t.message ?: "unknown"}")
                },
            )
        }
    }

    /** Close the drill-in and return to the area list. */
    fun closeArea() {
        _drill.value = null
    }

    /**
     * Inline action for an entity row inside the drill-in. Mirrors
     * Search's `activate`: scenes / scripts fire, buttons press,
     * toggleable domains flip via [ServiceCall.tapAction], and read-only
     * entities surface their reading as a toast. After a state-changing
     * call we re-resolve the area so the row reflects the new state.
     */
    fun activate(entity: EntityState) {
        viewModelScope.launch {
            val target = entity.id
            var changed = false
            when {
                target.domain == Domain.SCENE -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired scene '${entity.friendlyName}'")
                }
                target.domain == Domain.SCRIPT -> {
                    haRepository.call(ServiceCall(target, "turn_on", JsonObject(emptyMap())))
                    Toaster.show("Fired script '${entity.friendlyName}'")
                }
                target.domain == Domain.BUTTON || target.domain == Domain.INPUT_BUTTON -> {
                    haRepository.call(ServiceCall(target, "press", JsonObject(emptyMap())))
                    Toaster.show("Pressed '${entity.friendlyName}'")
                }
                target.domain in TOGGLE_DOMAINS -> {
                    haRepository.call(ServiceCall.tapAction(target, entity.isOn))
                    Toaster.show("${if (entity.isOn) "Off" else "On"}: ${entity.friendlyName}")
                    changed = true
                }
                else -> {
                    val parts = buildString {
                        append(entity.friendlyName).append('\n')
                        append(entity.id.value).append('\n')
                        append("state: ").append(entity.rawState ?: if (entity.isOn) "on" else "off")
                        entity.unit?.let { append(' ').append(it) }
                    }
                    Toaster.showExpandable(shortText = entity.friendlyName, fullText = parts)
                }
            }
            // Toggles change state; re-resolve so the row's ON/OFF flips.
            // Fire/press are stateless, info is read-only — no reload needed.
            if (changed) _drill.value?.area?.let { openArea(it) }
        }
    }

    companion object {
        /** Domains whose drill-in row toggles on tap (same set Search treats
         *  as CONTROLS for tap-to-toggle). */
        private val TOGGLE_DOMAINS = setOf(
            Domain.LIGHT, Domain.SWITCH, Domain.FAN, Domain.COVER, Domain.LOCK,
            Domain.MEDIA_PLAYER, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
            Domain.HUMIDIFIER, Domain.CLIMATE, Domain.WATER_HEATER, Domain.VACUUM,
            Domain.LAWN_MOWER, Domain.VALVE,
        )

        /** Group ordering inside a drill-in: actionable controls first,
         *  sensors next, everything else last. */
        private fun domainRank(domain: Domain): Int = when {
            domain == Domain.LIGHT || domain == Domain.SWITCH || domain == Domain.FAN ||
                domain == Domain.COVER || domain == Domain.LOCK || domain == Domain.MEDIA_PLAYER ||
                domain == Domain.INPUT_BOOLEAN || domain == Domain.CLIMATE ||
                domain == Domain.HUMIDIFIER || domain == Domain.VALVE -> 0
            domain.isAction -> 1
            domain.isSensor || domain == Domain.SENSOR || domain == Domain.BINARY_SENSOR -> 2
            else -> 3
        }

        /** Plural, uppercase domain header (e.g. "LIGHTS", "BINARY SENSORS"). */
        private fun domainLabel(domain: Domain): String {
            val base = domain.prefix.replace('_', ' ').uppercase()
            return if (base.endsWith("S")) base else "${base}S"
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { AreasViewModel(haRepository) }
        }
    }
}
