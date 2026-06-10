package com.github.itskenny0.r1ha.core.lovelace.strategies

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Port of HA's `home` dashboard strategy: an overview view (floor-grouped area
 * cards + summary cards + favourites section), one subview per area, a
 * media-players view, and an other-devices view.
 *
 * Registry-data gaps and per-section degradations (each is local; the dashboard
 * as a whole never fails):
 *  - HA gates the summary cards on `hass.panels.*` (whether the light / climate /
 *    security / maintenance panels are registered) and on energy prefs. R1HA has
 *    no panels list, so it gates each summary purely on whether the matching
 *    entities exist; the energy summary follows [StrategyData.hasEnergyGrid].
 *  - HA's `discovered-devices`, welcome-message header, and `shortcuts` are
 *    admin/onboarding affordances tied to HA's frontend; R1HA omits them.
 *  - The favourites section delegates to the common-controls section strategy
 *    (kept as a strategy section so the engine expands it in place).
 *  - The per-area subview reuses the areas-strategy area grouping
 *    ([AreasStrategy.areaView]).
 */
object HomeStrategy {

    private val OTHER_DEVICES_HIDE_DOMAINS = setOf(
        "ai_task", "automation", "configurator", "device_tracker", "event",
        "geo_location", "notify", "persistent_notification", "script", "sun",
        "tag", "todo", "zone",
        "assist_satellite", "conversation", "stt", "tts", "wake_word",
    )

    private val OTHER_DEVICES_HIDE_PLATFORMS = setOf(
        "automation", "script", "hassio", "backup", "mobile_app", "zone", "person",
    )

    fun dashboard(strategy: JsonObject, data: StrategyData): JsonObject {
        val areas = data.areas.values.toList()
        val views = buildJsonArray {
            addJsonObject {
                put("icon", "mdi:home")
                put("path", "overview")
                put(
                    "strategy",
                    buildJsonObject {
                        put("type", "home-overview")
                        strategy["favorite_entities"]?.let { put("favorite_entities", it) }
                        strategy["hide_suggested_entities"]?.let { put("hide_suggested_entities", it) }
                    },
                )
            }
            for (area in areas) {
                addJsonObject {
                    put("title", area.name)
                    put("path", "areas-${area.areaId}")
                    put("subview", true)
                    put(
                        "strategy",
                        buildJsonObject { put("type", "home-area"); put("area", area.areaId) },
                    )
                }
            }
            addJsonObject {
                put("title", "Media players")
                put("path", "media-players")
                put("subview", true)
                put("icon", "mdi:multimedia")
                put("strategy", buildJsonObject { put("type", "home-media-players") })
            }
            addJsonObject {
                put("title", "Devices")
                put("path", "other-devices")
                put("subview", true)
                put("icon", "mdi:devices")
                put("strategy", buildJsonObject { put("type", "home-other-devices") })
            }
        }
        return buildJsonObject { put("views", views) }
    }

    fun overviewView(strategy: JsonObject, data: StrategyData): JsonObject {
        val floors = data.floors.values.sortedBy { it.level ?: Int.MAX_VALUE }
        val areasByFloor = data.areas.values.groupBy { it.floorId }
        val floorCount = floors.count { (areasByFloor[it.floorId]?.size ?: 0) > 0 } +
            if (areasByFloor[null].orEmpty().isNotEmpty()) 1 else 0

        val sections = mutableListOf<JsonObject>()

        // Favourites section (common-controls strategy section, expanded in place).
        val hideSuggested = (strategy["hide_suggested_entities"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val favourites = strategy.idList("favorite_entities").filter { it in data.states }
        if (!hideSuggested) {
            val limit = maxOf(8, favourites.size)
            sections += buildJsonObject {
                put(
                    "strategy",
                    buildJsonObject {
                        put("type", "common-controls")
                        put("limit", limit)
                        putJsonArray("include_entities") { favourites.forEach { add(it) } }
                        put("hide_empty", true)
                        put(
                            "heading",
                            buildJsonObject {
                                put("type", "heading"); put("heading", "Favourites"); put("heading_style", "title")
                            },
                        )
                    },
                )
            }
        }

        // Summary cards (repairs/updates + home-summary tiles).
        val summaryCards = buildJsonArray {
            addJsonObject { put("type", "repairs"); put("hide_empty", true) }
            addJsonObject { put("type", "updates"); put("hide_empty", true) }
            for (summary in summaryBuilders(data)) add(summary)
        }
        // The summary array always has the repairs/updates cards; only add the
        // section when at least one home-summary tile or a non-empty card exists.
        sections += buildJsonObject {
            put("type", "grid")
            putJsonArray("cards") {
                addJsonObject { put("type", "heading"); put("heading", "Summaries"); put("heading_style", "title") }
                summaryCards.forEach { add(it) }
            }
        }

        // Floor sections of area cards.
        for (floor in floors) {
            val areas = areasByFloor[floor.floorId].orEmpty()
            if (areas.isEmpty()) continue
            sections += floorSection(if (floorCount > 1) floor.name else "Areas", floor.icon, areas, data)
        }
        val unassigned = areasByFloor[null].orEmpty()
        if (unassigned.isNotEmpty()) {
            val heading = if (floors.any { areasByFloor[it.floorId].orEmpty().isNotEmpty() }) "Other areas" else "Areas"
            sections += floorSection(heading, null, unassigned, data)
        }

        if (data.areas.isEmpty()) {
            return buildJsonObject {
                put("type", "panel")
                put("panel", true)
                putJsonArray("cards") {
                    addJsonObject {
                        put("type", "empty-state")
                        put("icon", "mdi:home-assistant")
                        put("content_only", true)
                        put("title", "Welcome home")
                        put("content", "Set up areas in Home Assistant to organise your home here.")
                    }
                }
            }
        }

        return buildJsonObject {
            put("type", "sections")
            put("max_columns", 3)
            put("sections", JsonArray(sections))
        }
    }

    private fun floorSection(
        heading: String,
        icon: String?,
        areas: List<StrategyArea>,
        data: StrategyData,
    ): JsonObject = buildJsonObject {
        put("type", "grid")
        put("column_span", 3)
        putJsonArray("cards") {
            addJsonObject {
                put("type", "heading"); put("heading", heading); put("heading_style", "title")
                if (icon != null) put("icon", icon)
            }
            for (area in areas) {
                addJsonObject {
                    put("type", "area")
                    put("area", area.areaId)
                    put("display_type", "compact")
                    if (area.temperatureEntityId != null) {
                        putJsonArray("sensor_classes") { add("temperature") }
                    }
                    put("vertical", true)
                    put(
                        "tap_action",
                        buildJsonObject {
                            put("action", "navigate"); put("navigation_path", "areas-${area.areaId}")
                        },
                    )
                }
            }
        }
    }

    /** home-summary tiles, gated on the presence of matching entities. */
    private fun summaryBuilders(data: StrategyData): List<JsonObject> {
        fun has(vararg domains: String): Boolean =
            data.states.values.any { it.domain in domains && data.entities[it.entityId]?.entityCategory == null }
        val out = mutableListOf<JsonObject>()
        if (has("light")) out += summaryTile("light")
        if (has("climate")) out += summaryTile("climate")
        if (has("alarm_control_panel", "lock")) out += summaryTile("security")
        if (has("media_player")) out += summaryTile("media_players")
        if (data.hasEnergyGrid) out += summaryTile("energy")
        return out
    }

    private fun summaryTile(summary: String): JsonObject = buildJsonObject {
        put("type", "home-summary")
        put("summary", summary)
    }

    fun areaView(strategy: JsonObject, data: StrategyData): JsonObject =
        // home-area reuses the areas-strategy per-area grouping.
        AreasStrategy.areaView(strategy, data)

    fun mediaPlayersView(strategy: JsonObject, data: StrategyData): JsonObject {
        val players = data.states.values
            .filter { it.domain == "media_player" && data.entities[it.entityId]?.entityCategory == null }
            .map { it.entityId }
        if (players.isEmpty()) {
            return buildJsonObject {
                put("type", "panel")
                put("panel", true)
                putJsonArray("cards") { add(StrategyEngine.markdownCard("No media players found.")) }
            }
        }
        // Group by area; players with no area land in a trailing section.
        val byArea = LinkedHashMap<String?, MutableList<String>>()
        for (id in players) {
            val areaId = data.entities[id]?.let { it.areaId ?: it.deviceId?.let { d -> data.devices[d]?.areaId } }
            byArea.getOrPut(areaId) { mutableListOf() }.add(id)
        }
        val sections = mutableListOf<JsonObject>()
        for ((areaId, ids) in byArea) {
            if (ids.isEmpty()) continue
            val heading = areaId?.let { data.areas[it]?.name } ?: "Other media players"
            sections += buildJsonObject {
                put("type", "grid")
                putJsonArray("cards") {
                    addJsonObject { put("type", "heading"); put("heading_style", "subtitle"); put("heading", heading) }
                    for (id in ids) addJsonObject { put("type", "media-control"); put("entity", id) }
                }
            }
        }
        return buildJsonObject {
            put("type", "sections")
            put("max_columns", 2)
            put("sections", JsonArray(sections))
        }
    }

    fun otherDevicesView(strategy: JsonObject, data: StrategyData): JsonObject {
        // OTHER_DEVICES_FILTERS: no area, primary, not a hidden domain/platform.
        val candidates = data.states.keys.filter { id ->
            val domain = id.substringBefore('.', "")
            if (domain in OTHER_DEVICES_HIDE_DOMAINS) return@filter false
            val reg = data.entities[id] ?: return@filter false
            if (reg.entityCategory != null) return@filter false
            if (reg.platform != null && reg.platform in OTHER_DEVICES_HIDE_PLATFORMS) return@filter false
            // Unassigned area: entity has no area and its device has no area.
            val areaId = reg.areaId ?: reg.deviceId?.let { data.devices[it]?.areaId }
            areaId == null && reg.deviceId != null
        }
        val byDevice = LinkedHashMap<String, MutableList<String>>()
        for (id in candidates) {
            val deviceId = data.entities[id]?.deviceId ?: continue
            byDevice.getOrPut(deviceId) { mutableListOf() }.add(id)
        }
        val sections = mutableListOf<JsonObject>()
        for ((deviceId, ids) in byDevice) {
            if (ids.isEmpty()) continue
            val heading = data.devices[deviceId]?.displayName ?: "Device"
            sections += buildJsonObject {
                put("type", "grid")
                putJsonArray("cards") {
                    addJsonObject { put("type", "heading"); put("heading", heading) }
                    addJsonObject {
                        put("type", "entities")
                        putJsonArray("entities") { ids.forEach { add(it) } }
                    }
                }
            }
        }
        if (sections.isEmpty()) {
            return buildJsonObject {
                put("type", "panel")
                put("panel", true)
                putJsonArray("cards") {
                    addJsonObject {
                        put("type", "empty-state")
                        put("icon", "mdi:check-all")
                        put("content_only", true)
                        put("title", "All organised")
                        put("content", "Every device is assigned to an area.")
                    }
                }
            }
        }
        return buildJsonObject {
            put("type", "sections")
            put("header", buildJsonObject { put("badges_position", "bottom") })
            put("max_columns", 3)
            put("sections", JsonArray(sections))
        }
    }

    private fun JsonObject.idList(key: String): List<String> {
        val arr = this[key] as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }
}
