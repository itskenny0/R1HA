package com.github.itskenny0.r1ha.feature.areas

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.ui.components.formatFixed
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
 * a public REST endpoint; it lives behind the WebSocket
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
        /**
         * Resolved friendly names keyed by entity_id, folded in by
         * [enrichSummaries] off the same state snapshot the summary uses. The
         * in-place expanded peek reads names from here and falls back to the raw
         * entity_id only for entities the snapshot didn't carry.
         */
        val entityNames: Map<String, String> = emptyMap(),
        /** Stable HA area_id (e.g. "kitchen"). Surfaced from the
         *  template's `id` field. Drives the drill-in's entity lookup
         *  and is the handle a future rename call would key on. */
        val areaId: String? = null,
        /**
         * Compact sensor readout for the row's secondary line, mirroring HA's
         * area-card "secondary" (median temperature, then humidity, joined with
         * a dot). Empty when the area has no readable temperature / humidity
         * sensor. Populated lazily by [enrichSummaries] once the live entity
         * snapshot is in; null until then so the row knows summaries are pending.
         */
        val summary: String? = null,
        /**
         * Count of active alert binary sensors in this area (device_class motion /
         * moisture / smoke / gas / etc. currently `on`). Mirrors HA's alert badges.
         * Drives the small amber alert pip on the row.
         */
        val activeAlerts: Int = 0,
    ) {
        /**
         * Stable LazyColumn / expansion key. HA keys areas on area_id, and two
         * areas may share a display name, so we prefer the id and fall back to
         * the name only when the registry didn't hand us one.
         */
        val key: String get() = areaId ?: "name:$name"
    }

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
        /** Temperature / humidity readout for this area, same source as the list
         *  row's [Area.summary]; null when the area has nothing to summarise. */
        val summary: String? = null,
        /** Count of active alert binary sensors in this area (see [isActiveAlert]). */
        val activeAlerts: Int = 0,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        /** True only on the initial load (full-screen spinner). Subsequent
         *  fetches over an already-populated list drive [refreshing] instead so
         *  the list stays on screen and only the pull indicator moves. */
        val loading: Boolean = true,
        val refreshing: Boolean = false,
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
            // First load shows the full-screen spinner; a re-fetch over an
            // already-populated list keeps the rows on screen and drives the
            // pull-to-refresh indicator instead, so the 60s silent refresh never
            // blanks the list back to a spinner.
            val firstLoad = _ui.value.areas.isEmpty()
            _ui.value = _ui.value.copy(
                loading = firstLoad,
                refreshing = !firstLoad,
                error = null,
            )
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
                        _ui.value = _ui.value.copy(
                            loading = false, refreshing = false, areas = list, error = null,
                        )
                        // The template only gives us names + entity_ids; the live
                        // sensor summary (temperature / humidity) and the active-alert
                        // count need the state snapshot. Pull it once and fold the
                        // per-area readouts in, so the list rows match HA's area card
                        // without an O(N) per-area round trip.
                        enrichSummaries(list)
                    }.onFailure { t ->
                        R1Log.w("Areas", "parse failed: ${t.message}")
                        Toaster.error("Areas parse failed. Try Templates to debug")
                        _ui.value = _ui.value.copy(loading = false, refreshing = false, error = t.message)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "fetch failed: ${t.message}")
                    Toaster.error("Areas load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, refreshing = false, error = t.message)
                },
            )
        }
    }

    /**
     * Fold per-area sensor summaries + alert counts into the loaded area list.
     * Runs after [refresh] has surfaced the names so the list paints immediately;
     * the readouts then fill in once the state snapshot arrives. A failure here is
     * non-fatal: the list still works, it just shows counts without summaries.
     */
    private fun enrichSummaries(areas: List<Area>) {
        if (areas.isEmpty()) return
        viewModelScope.launch {
            haRepository.listAllEntities().fold(
                onSuccess = { all ->
                    val byId = all.associateBy { it.id.value }
                    val enriched = areas.map { area ->
                        val states = area.entityIds.mapNotNull { byId[it] }
                        // Resolve friendly names for the in-place expanded peek so
                        // it reads "Kitchen Ceiling" rather than the raw entity_id.
                        val names = area.entityIds.mapNotNull { eid ->
                            byId[eid]?.let { eid to it.friendlyName }
                        }.toMap()
                        area.copy(
                            entityNames = names,
                            summary = sensorSummary(states),
                            activeAlerts = states.count { isActiveAlert(it) },
                        )
                    }
                    // Only patch if the list hasn't been replaced by a newer refresh
                    // (compare by the stable key set, ignoring summary/alert fields).
                    val current = _ui.value.areas
                    if (current.map { it.key } == enriched.map { it.key }) {
                        _ui.value = _ui.value.copy(areas = enriched)
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "summary enrich failed: ${t.message}")
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
                        // Controls first, then sensors, then everything else;
                        // the same altitude Search's CONTROLS / SENSORS buckets use.
                        .sortedWith(
                            compareBy(
                                { domainRank(it.domain) },
                                { it.label },
                            ),
                        )
                    // Only the current drill (the user may have backed out
                    // and reopened a different area while this was in flight).
                    if (_drill.value?.area?.key == area.key) {
                        _drill.value = DrillState(
                            area = area,
                            loading = false,
                            groups = groups,
                            unmatchedCount = unmatched,
                            error = null,
                            summary = sensorSummary(matched),
                            activeAlerts = matched.count { isActiveAlert(it) },
                        )
                    }
                },
                onFailure = { t ->
                    R1Log.w("Areas", "drill load failed: ${t.message}")
                    if (_drill.value?.area?.key == area.key) {
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
            // An unavailable entity can't take a control call: HA would reject it and
            // the toast would read like a success. Surface its details instead so the
            // tap still does something useful (matches HA dimming it but keeping info).
            val controllable = entity.isAvailable ||
                target.domain == Domain.SCENE || target.domain == Domain.SCRIPT
            when {
                !controllable -> {
                    Toaster.showExpandable(
                        shortText = "${entity.friendlyName} is unavailable",
                        fullText = buildString {
                            append(entity.friendlyName).append('\n')
                            append(entity.id.value).append('\n')
                            append("state: ").append(entity.rawState ?: "unavailable")
                        },
                    )
                }
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
            // Fire/press are stateless, info is read-only; no reload needed.
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

        /**
         * binary_sensor device classes HA treats as "alerts" on the area card.
         * Mirrors the card's default alert_classes plus the common safety classes,
         * kept lower-case for a direct match against [EntityState.deviceClass].
         */
        private val ALERT_CLASSES = setOf(
            "motion", "moisture", "smoke", "gas", "safety", "tamper",
            "co", "problem", "door", "window",
        )

        /** An active alert: an `on` binary_sensor whose device_class is an alert class. */
        private fun isActiveAlert(e: EntityState): Boolean =
            e.id.domain == Domain.BINARY_SENSOR &&
                e.isAvailable && e.isOn &&
                (e.deviceClass?.lowercase() in ALERT_CLASSES)

        /**
         * Compact temperature / humidity readout for an area, matching HA's area-card
         * secondary line: the median temperature reading, then the median humidity, each
         * with its unit, joined with a middle dot. Only `sensor` entities of the matching
         * device_class with a parseable numeric, available state contribute. Returns null
         * when the area has nothing readable to summarise.
         */
        private fun sensorSummary(states: List<EntityState>): String? {
            fun median(values: List<Double>): Double? {
                if (values.isEmpty()) return null
                val s = values.sorted()
                val mid = s.size / 2
                return if (s.size % 2 == 0) (s[mid - 1] + s[mid]) / 2.0 else s[mid]
            }
            fun reading(deviceClass: String): String? {
                val matching = states.filter {
                    it.id.domain == Domain.SENSOR &&
                        it.isAvailable &&
                        it.deviceClass?.lowercase() == deviceClass
                }
                if (matching.isEmpty()) return null
                // Take the unit from the first contributing sensor; HA assumes a
                // consistent unit across an area's same-class sensors.
                val unit = matching.firstNotNullOfOrNull { it.unit }
                val values = matching.mapNotNull { it.rawState?.trim()?.toDoubleOrNull() }
                val m = median(values) ?: return null
                val num = if (m == m.toLong().toDouble()) m.toLong().toString() else formatFixed(m, 1)
                return if (unit.isNullOrBlank()) num else "$num$unit"
            }
            val parts = listOfNotNull(reading("temperature"), reading("humidity"))
            return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
        }

        /**
         * Plural, uppercase domain header (e.g. "LIGHTS", "SWITCHES", "BINARY
         * SENSORS"). Sibilant endings (s / x / z / ch / sh) take "ES" so
         * "switch" pluralises to "SWITCHES" rather than the wrong "SWITCHS".
         */
        private fun domainLabel(domain: Domain): String {
            val base = domain.prefix.replace('_', ' ').uppercase()
            return when {
                base.endsWith("S") || base.endsWith("X") || base.endsWith("Z") ||
                    base.endsWith("CH") || base.endsWith("SH") -> "${base}ES"
                else -> "${base}S"
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { AreasViewModel(haRepository) }
        }
    }
}
