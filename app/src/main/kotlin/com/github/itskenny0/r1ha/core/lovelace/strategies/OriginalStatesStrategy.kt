package com.github.itskenny0.r1ha.core.lovelace.strategies

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Port of HA's `original-states` strategy + its legacy core
 * `generate-lovelace-config.ts`. This is HA's default auto-dashboard when the
 * user has never configured Lovelace: every visible entity, grouped into cards
 * by area, then device, then domain, with a person grid and an optional energy
 * card.
 *
 * Fidelity notes vs HA:
 *  - The hidden-entity filter (entity_category / hidden / hide-platform /
 *    HIDE_DOMAIN) is faithful where the entity registry is reachable; an entity
 *    with no registry entry is treated as visible (HA does the same: the filter
 *    only excludes entities it has registry rows for).
 *  - HA's legacy `group.*` splitting (`splitByGroups`) is intentionally omitted:
 *    R1HA targets modern HA where the legacy group integration is rarely present,
 *    and the area/device/domain grouping below covers the same entities. This is
 *    a documented degradation, not a dropped requirement.
 *  - `geo_location` map card and the energy-distribution card are emitted when
 *    their preconditions hold.
 */
object OriginalStatesStrategy {

    private val HIDE_DOMAIN = setOf(
        "ai_task", "automation", "configurator", "device_tracker", "event",
        "geo_location", "notify", "persistent_notification", "script", "sun",
        "tag", "todo", "zone",
        // ASSIST_ENTITIES
        "assist_satellite", "conversation", "stt", "tts", "wake_word",
    )

    private val HIDE_PLATFORM = setOf("backup", "mobile_app")

    /** HA's SENSOR_ENTITIES: domains sorted to the bottom of an entities card. */
    private val SENSOR_ENTITIES = setOf(
        "sensor", "binary_sensor", "calendar", "camera", "device_tracker",
        "weather",
    )

    /** HA's HELPER_DOMAINS: folded into one "_helpers" card at the end. */
    private val HELPER_DOMAINS = setOf(
        "input_boolean", "input_button", "input_text", "input_number",
        "input_datetime", "input_select", "counter", "timer", "schedule",
    )

    fun dashboard(strategy: JsonObject, data: StrategyData): JsonObject {
        // HA's original-states dashboard strategy emits one view that itself
        // carries the original-states VIEW strategy; we expand directly to the
        // concrete view so the engine doesn't need a second pass.
        return StrategyEngine.singleViewDashboard(view(strategy, data))
    }

    fun view(strategy: JsonObject, data: StrategyData): JsonObject {
        if (data.starting) return StrategyEngine.placeholderViewBody("Home Assistant is starting...")
        if (data.recoveryMode) return StrategyEngine.placeholderViewBody("Home Assistant is in recovery mode.")

        val visible = computeDefaultViewStates(data)
        val cards = mutableListOf<JsonObject>()

        // Split visible entities into area / device / "other" buckets the same
        // way HA's splitByAreaDevice does, then build cards per bucket.
        val split = splitByAreaDevice(data, visible)

        // Domain-grouped cards for the leftover ("other") entities, incl. the
        // person grid + helpers card (generateViewConfig).
        cards += generateViewConfig(data, split.otherEntities)

        // Area cards (one entities/grid group per area, areas in registry order).
        val areaCards = mutableListOf<JsonObject>()
        for ((areaId, entityIds) in split.areasWithEntities) {
            val area = data.areas[areaId] ?: continue
            areaCards += computeCards(data, entityIds.sortedBy { friendlyName(data, it).lowercase() }, area.name)
        }

        // Device cards (devices sorted by display name).
        val deviceCards = mutableListOf<JsonObject>()
        for ((deviceId, entityIds) in split.devicesWithEntities.entries.sortedBy {
            (data.devices[it.key]?.displayName ?: "").lowercase()
        }) {
            val device = data.devices[deviceId] ?: continue
            deviceCards += computeCards(data, entityIds.sortedBy { friendlyName(data, it).lowercase() }, device.displayName)
        }

        // Energy distribution card (when a grid source is configured).
        val energyCard: JsonObject? = if (data.hasEnergyGrid) {
            buildJsonObject {
                put("type", "energy-distribution")
                put("title", "Energy distribution today")
                put("link_dashboard", true)
            }
        } else {
            null
        }

        // HA unshift order: areaCards, then groupCards (omitted), then energy,
        // before the domain cards; deviceCards are pushed at the very end.
        val ordered = mutableListOf<JsonObject>()
        ordered += areaCards
        if (energyCard != null) ordered += energyCard
        ordered += cards
        ordered += deviceCards

        // Add a geo_location map when any geo_location entity exists.
        if (visible.keys.any { it.substringBefore('.', "") == "geo_location" } ||
            data.states.keys.any { it.substringBefore('.', "") == "geo_location" }
        ) {
            ordered += buildJsonObject {
                put("type", "map")
                putJsonArray("geo_location_sources") { add("all") }
            }
        }

        if (ordered.isEmpty()) {
            // No entities: empty-state card in a panel view.
            return buildJsonObject {
                put("type", "panel")
                put("panel", true)
                putJsonArray("cards") {
                    addJsonObject {
                        put("type", "empty-state")
                        put("icon", "mdi:home-assistant")
                        put("content_only", true)
                        put("title", "Welcome home")
                        put(
                            "content",
                            "It looks like you have no entities yet. Add an integration in Home Assistant to get started.",
                        )
                    }
                }
            }
        }

        return buildJsonObject {
            put("path", "default_view")
            put("title", "Home")
            put("cards", JsonArray(ordered))
        }
    }

    // --- HA computeDefaultViewStates ----------------------------------------

    private fun computeDefaultViewStates(data: StrategyData): Map<String, StrategyEntity> {
        // Hidden by registry: any categorised, hide-platform, or hidden entity.
        val hidden = data.entities.values
            .filter { e ->
                e.entityCategory != null ||
                    (e.platform != null && e.platform in HIDE_PLATFORM) ||
                    e.hiddenBy != null ||
                    e.disabledBy != null
            }
            .map { it.entityId }
            .toSet()
        return data.states.filterKeys { id ->
            val domain = id.substringBefore('.', "")
            domain !in HIDE_DOMAIN && id !in hidden
        }
    }

    // --- HA splitByAreaDevice -----------------------------------------------

    private data class Split(
        val areasWithEntities: LinkedHashMap<String, MutableList<String>>,
        val devicesWithEntities: LinkedHashMap<String, MutableList<String>>,
        val otherEntities: LinkedHashMap<String, StrategyEntity>,
    )

    private fun splitByAreaDevice(data: StrategyData, entities: Map<String, StrategyEntity>): Split {
        val remaining = LinkedHashMap(entities)
        val areas = LinkedHashMap<String, MutableList<String>>()
        val devices = LinkedHashMap<String, MutableList<String>>()

        // HA iterates the entity REGISTRY (not the states) so unregistered
        // entities stay in "other".
        for (reg in data.entities.values) {
            val areaId = reg.areaId ?: reg.deviceId?.let { data.devices[it]?.areaId }
            val id = reg.entityId
            if (id !in remaining) continue
            if (areaId != null && areaId in data.areas) {
                areas.getOrPut(areaId) { mutableListOf() }.add(id)
                remaining.remove(id)
            } else if (reg.deviceId != null && reg.deviceId in data.devices) {
                devices.getOrPut(reg.deviceId) { mutableListOf() }.add(id)
                remaining.remove(id)
            }
        }
        // HA collapses single-entity devices back into "other".
        val singles = devices.filterValues { it.size == 1 }
        for ((deviceId, list) in singles) {
            val only = list.first()
            entities[only]?.let { remaining[only] = it }
            devices.remove(deviceId)
        }
        return Split(areas, devices, remaining)
    }

    // --- HA generateViewConfig (domain grouping + person grid + helpers) ----

    private fun generateViewConfig(data: StrategyData, entities: Map<String, StrategyEntity>): List<JsonObject> {
        val byDomain = LinkedHashMap<String, MutableList<String>>()
        for ((id, _) in entities) {
            val domain = id.substringBefore('.', "")
            byDomain.getOrPut(domain) { mutableListOf() }.add(id)
        }

        val cards = mutableListOf<JsonObject>()

        // Person grid / single-person entities card.
        byDomain.remove("person")?.let { persons ->
            if (persons.size == 1) {
                cards += buildJsonObject {
                    put("type", "entities")
                    putJsonArray("entities") { persons.forEach { add(it) } }
                }
            } else {
                cards += buildJsonObject {
                    put("type", "grid")
                    put("square", true)
                    put("columns", 3)
                    putJsonArray("cards") {
                        for (p in persons) {
                            addJsonObject {
                                put("type", "picture-entity")
                                put("entity", p)
                                put("aspect_ratio", "1")
                                put("show_name", false)
                            }
                        }
                    }
                }
            }
        }

        // Helper entities folded into one "_helpers" pseudo-domain.
        val helperEntities = mutableListOf<String>()
        for (domain in HELPER_DOMAINS) {
            byDomain.remove(domain)?.let { helperEntities += it }
        }

        val domainTitles = LinkedHashMap<String, String>()
        for (domain in byDomain.keys) domainTitles[domain] = domainToName(domain)
        if (helperEntities.isNotEmpty()) {
            byDomain["_helpers"] = helperEntities
            domainTitles["_helpers"] = "Helpers"
        }

        for (domain in byDomain.keys.sortedBy { domainTitles[it]?.lowercase() ?: it }) {
            val ids = byDomain[domain]!!.sortedBy { friendlyName(data, it).lowercase() }
            cards += computeCards(data, ids, domainTitles[domain])
        }
        return cards
    }

    // --- HA computeCards -----------------------------------------------------

    /** Build the cards for a titled group of entity ids, splitting domain-special
     *  entities into their own cards and the rest into an entities card. */
    private fun computeCards(data: StrategyData, entityIds: List<String>, title: String?): List<JsonObject> {
        val cards = mutableListOf<JsonObject>()
        val entitiesConf = mutableListOf<String>()
        val footerEntities = mutableListOf<String>()

        for (id in entityIds) {
            val domain = id.substringBefore('.', "")
            when (domain) {
                "alarm_control_panel" -> cards += card("alarm-panel", id)
                "camera" -> cards += card("picture-entity", id)
                "image" -> cards += buildJsonObject { put("type", "picture"); put("image_entity", id) }
                "climate" -> cards += buildJsonObject {
                    put("type", "thermostat")
                    put("entity", id)
                    val modes = data.states[id]?.hvacModesCount ?: 0
                    if (modes > 1) {
                        putJsonArray("features") {
                            addJsonObject { put("type", "climate-hvac-modes") }
                        }
                    }
                }
                "humidifier" -> cards += buildJsonObject {
                    put("type", "humidifier")
                    put("entity", id)
                    putJsonArray("features") { addJsonObject { put("type", "humidifier-toggle") } }
                }
                "media_player" -> cards += card("media-control", id)
                "weather" -> cards += buildJsonObject {
                    put("type", "weather-forecast"); put("entity", id); put("show_forecast", false)
                }
                "scene", "script" -> footerEntities += id
                else -> entitiesConf += id
            }
        }

        // Sort the entities-card rows: controls first, sensors last, then by name.
        val sorted = entitiesConf.sortedWith(
            compareBy<String> { if (it.substringBefore('.', "") in SENSOR_ENTITIES) 1 else 0 }
                .thenBy { friendlyName(data, it).lowercase() },
        )

        // If only footer (scene/script) entities, HA re-runs without the footer
        // split so they render as normal rows.
        if (sorted.isEmpty() && footerEntities.isNotEmpty()) {
            val asRows = (sorted + footerEntities).sortedBy { friendlyName(data, it).lowercase() }
            cards.add(
                0,
                buildJsonObject {
                    put("type", "entities")
                    putJsonArray("entities") { asRows.forEach { add(it) } }
                    if (!title.isNullOrBlank()) put("title", title)
                },
            )
        } else if (sorted.isNotEmpty() || footerEntities.isNotEmpty()) {
            cards.add(
                0,
                buildJsonObject {
                    put("type", "entities")
                    putJsonArray("entities") { sorted.forEach { add(it) } }
                    if (!title.isNullOrBlank()) put("title", title)
                    if (footerEntities.isNotEmpty()) {
                        put(
                            "footer",
                            buildJsonObject {
                                put("type", "buttons")
                                putJsonArray("entities") {
                                    footerEntities.forEach { e ->
                                        addJsonObject {
                                            put("entity", e); put("show_icon", true); put("show_name", true)
                                        }
                                    }
                                }
                            },
                        )
                    }
                },
            )
        }

        if (cards.size < 2) return cards
        // HA wraps multiple cards in a single-column grid so the group reads as a unit.
        return listOf(
            buildJsonObject {
                put("type", "grid")
                put("square", false)
                put("columns", 1)
                put("cards", JsonArray(cards))
            },
        )
    }

    private fun card(type: String, entity: String): JsonObject = buildJsonObject {
        put("type", type); put("entity", entity)
    }

    private fun friendlyName(data: StrategyData, id: String): String =
        data.states[id]?.friendlyName?.takeUnless { it.isBlank() } ?: id

    /** Title-cased domain name, mirroring HA's domainToName fallback (snake to
     *  Title Case). HA localises these; R1HA uses the Title-Cased domain. */
    private fun domainToName(domain: String): String =
        domain.split('_').joinToString(" ") { part ->
            part.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
}
