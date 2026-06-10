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
 * Port of HA's `areas` dashboard strategy plus the `areas-overview` and `area`
 * view strategies. The dashboard emits a floor-grouped area-card overview view
 * and one subview per area with domain-grouped tiles (lights / covers / climate
 * / media / security / actions / others), temp+humidity badges, and the inline
 * area-controls feature.
 *
 * Fidelity notes vs HA:
 *  - Floor grouping uses the floor registry where present; an "Other areas"
 *    section catches floor-less areas, exactly as HA does. Without a floor
 *    registry every area lands in that single "Areas" section.
 *  - `getAreaControlEntities` is approximated: HA inspects each area's
 *    controllable domains (light/cover/fan/switch-excluded) to decide which
 *    area-controls toggles to surface. R1HA emits the area-controls feature when
 *    the area has any light or cover member; the feature itself resolves its
 *    live controls at render time.
 *  - The per-domain auto card-feature (light-brightness / cover-open-close /
 *    target-temperature / fan-speed / alarm-modes / lock-commands) is chosen
 *    from the entity's domain + advertised features in [computeAreaTileCard].
 */
object AreasStrategy {

    private val GROUP_ICONS = mapOf(
        "lights" to "mdi:lamps",
        "covers" to "mdi:blinds-horizontal",
        "climate" to "mdi:home-thermometer",
        "media_players" to "mdi:multimedia",
        "security" to "mdi:security",
        "actions" to "mdi:robot",
        "others" to "mdi:shape",
    )

    private val GROUP_TITLES = mapOf(
        "lights" to "Lights",
        "covers" to "Covers",
        "climate" to "Climate",
        "media_players" to "Media players",
        "security" to "Security",
        "actions" to "Actions",
        "others" to "Others",
    )

    // --- Dashboard ----------------------------------------------------------

    fun dashboard(strategy: JsonObject, data: StrategyData): JsonObject {
        val hidden = strategy.stringList("areas_display", "hidden")
        val order = strategy.stringList("areas_display", "order")
        val areas = orderedAreas(data, hidden, order)

        val views = buildJsonArray {
            // Overview view (carries the areas-overview view strategy verbatim so
            // the engine's view pass expands it).
            addJsonObject {
                put("icon", "mdi:home")
                put("path", "home")
                put(
                    "strategy",
                    buildJsonObject {
                        put("type", "areas-overview")
                        strategy["areas_display"]?.let { put("areas_display", it) }
                        strategy["floors_display"]?.let { put("floors_display", it) }
                        strategy["areas_options"]?.let { put("areas_options", it) }
                    },
                )
            }
            for (area in areas) {
                addJsonObject {
                    put("title", area.name)
                    put("path", areaPath(area.areaId))
                    put("subview", true)
                    put(
                        "strategy",
                        buildJsonObject {
                            put("type", "area")
                            put("area", area.areaId)
                            optionsGroups(strategy, area.areaId)?.let { put("groups_options", it) }
                        },
                    )
                }
            }
        }
        return buildJsonObject { put("views", views) }
    }

    // --- areas-overview view -----------------------------------------------

    fun overviewView(strategy: JsonObject, data: StrategyData): JsonObject {
        val hidden = strategy.stringList("areas_display", "hidden")
        val order = strategy.stringList("areas_display", "order")
        val displayedAreas = orderedAreas(data, hidden, order)
        val floorOrder = strategy.stringList("floors_display", "order")
        val floors = orderedFloors(data, floorOrder)

        // floor sections + a trailing "Other areas" section for floor-less areas.
        val floorBuckets = floors.map { it.floorId to it } + (UNASSIGNED_FLOOR to null)
        val sections = mutableListOf<JsonObject>()
        val nonEmpty = floorBuckets.filter { (floorId, _) ->
            displayedAreas.any { it.floorId == floorId || (it.floorId == null && floorId == UNASSIGNED_FLOOR) }
        }
        for ((floorId, floor) in nonEmpty) {
            val areasInFloor = displayedAreas.filter {
                it.floorId == floorId || (it.floorId == null && floorId == UNASSIGNED_FLOOR)
            }
            val noFloors = nonEmpty.size == 1 && floorId == UNASSIGNED_FLOOR
            val headingTitle = if (noFloors) "Areas" else (floor?.name ?: "Other areas")
            val headingIcon = floor?.icon ?: "mdi:floor-plan"

            val cards = buildJsonArray {
                addJsonObject {
                    put("type", "heading")
                    put("heading_style", "title")
                    put("heading", headingTitle)
                    put("icon", headingIcon)
                }
                for (area in areasInFloor) {
                    add(overviewAreaCard(area, data))
                }
            }
            sections += buildJsonObject {
                put("type", "grid")
                put("max_columns", 3)
                put("cards", cards)
            }
        }
        return buildJsonObject {
            put("type", "sections")
            put("max_columns", 3)
            put("sections", JsonArray(sections))
        }
    }

    private fun overviewAreaCard(area: StrategyArea, data: StrategyData): JsonObject {
        val sensorClasses = buildList {
            if (area.temperatureEntityId != null) add("temperature")
            if (area.humidityEntityId != null) add("humidity")
        }
        // area-controls feature when the area has any light or cover member.
        val hasLights = anyInArea(data, area.areaId, setOf("light"))
        val hasCovers = anyInArea(data, area.areaId, setOf("cover"))
        return buildJsonObject {
            put("type", "area")
            put("area", area.areaId)
            put("display_type", "compact")
            if (sensorClasses.isNotEmpty()) {
                putJsonArray("sensor_classes") { sensorClasses.forEach { add(it) } }
            }
            put("features_position", "inline")
            put("navigation_path", areaPath(area.areaId))
            if (hasLights || hasCovers) {
                putJsonArray("features") {
                    addJsonObject {
                        put("type", "area-controls")
                        putJsonArray("controls") {
                            if (hasLights) add("light")
                            if (hasCovers) add("cover")
                        }
                    }
                }
            }
        }
    }

    // --- area subview -------------------------------------------------------

    fun areaView(strategy: JsonObject, data: StrategyData): JsonObject {
        val areaId = (strategy["area"] as? JsonPrimitive)?.content
            ?: return StrategyEngine.placeholderViewBody("This area view is missing its area id.")
        val area = data.areas[areaId]
            ?: return StrategyEngine.placeholderViewBody("Unknown area \"$areaId\".")

        val groups = getAreaGroupedEntities(areaId, data)
        val sections = mutableListOf<JsonObject>()
        for (group in listOf("lights", "covers", "climate", "media_players", "security", "actions", "others")) {
            val members = groups[group].orEmpty()
            if (members.isEmpty()) continue
            sections += buildJsonObject {
                put("type", "grid")
                putJsonArray("cards") {
                    addJsonObject {
                        put("type", "heading")
                        put("heading", GROUP_TITLES[group])
                        put("icon", GROUP_ICONS[group])
                    }
                    for (id in members) add(computeAreaTileCard(data, id, area.name))
                }
            }
        }

        val badges = buildJsonArray {
            area.temperatureEntityId?.let { addJsonObject { put("entity", it); put("type", "entity"); put("color", "red") } }
            area.humidityEntityId?.let { addJsonObject { put("entity", it); put("type", "entity"); put("color", "indigo") } }
        }

        return buildJsonObject {
            put("type", "sections")
            put(
                "header",
                buildJsonObject { put("badges_position", "bottom") },
            )
            put("sections", JsonArray(sections))
            if (badges.isNotEmpty()) put("badges", badges)
        }
    }

    /** HA's getAreaGroupedEntities: bucket the area's primary entities by the
     *  fixed group filter table. */
    private fun getAreaGroupedEntities(areaId: String, data: StrategyData): Map<String, List<String>> {
        fun filters(vararg f: StrategyEntityFilter) = f.toList()
        fun byDomain(vararg domains: String) = StrategyEntityFilter(
            data,
            domains = domains.toSet(),
            areaSpec = StrategyEntityFilter.AreaSpec.Id(areaId),
            entityCategoryNone = true,
        )
        val groupFilters = mapOf(
            "lights" to filters(byDomain("light")),
            "covers" to filters(
                byDomain("cover"),
                StrategyEntityFilter(
                    data,
                    domains = setOf("binary_sensor"),
                    areaSpec = StrategyEntityFilter.AreaSpec.Id(areaId),
                    deviceClasses = setOf("door", "garage_door", "window"),
                    entityCategoryNone = true,
                ),
            ),
            "climate" to filters(byDomain("climate", "humidifier", "water_heater", "fan")),
            "media_players" to filters(byDomain("media_player")),
            "security" to filters(byDomain("alarm_control_panel", "lock", "camera")),
            "actions" to filters(byDomain("script", "scene", "automation")),
            "others" to filters(
                byDomain("vacuum", "lawn_mower", "valve"),
                byDomain("switch", "button", "input_boolean", "input_button"),
                byDomain("select", "number", "input_select", "input_number", "counter", "timer"),
            ),
        )
        return groupFilters.mapValues { (_, fs) ->
            // Preserve HA's reduce order: each sub-filter's matches appended.
            fs.flatMap { f -> data.states.keys.filter { f.matches(it) } }.distinct()
        }
    }

    /** HA's computeAreaTileCardConfig: a tile per entity, with the auto card
     *  feature chosen by domain + advertised features, plus a stripped name. */
    private fun computeAreaTileCard(data: StrategyData, entityId: String, areaPrefix: String): JsonObject {
        val domain = entityId.substringBefore('.', "")
        if (domain == "camera") {
            return buildJsonObject {
                put("type", "picture-entity")
                put("entity", entityId)
                put("show_state", false)
                put("show_name", false)
            }
        }
        val feature = autoFeature(data, entityId)
        val name = stripPrefix(data.states[entityId]?.friendlyName ?: entityId, areaPrefix)
        return buildJsonObject {
            put("type", "tile")
            put("entity", entityId)
            if (name != null) put("name", name)
            if (feature != null) {
                putJsonArray("features") { addJsonObject { put("type", feature) } }
            }
        }
    }

    /** Mirror of HA's supports* feature checks: pick the first applicable
     *  card feature for the entity's domain. */
    private fun autoFeature(data: StrategyData, entityId: String): String? {
        val ent = data.states[entityId] ?: return null
        return when (ent.domain) {
            "light" -> "light-brightness"
            "cover" -> "cover-open-close"
            "climate" -> "target-temperature"
            "fan" -> "fan-speed"
            "alarm_control_panel" -> "alarm-modes"
            "lock" -> "lock-commands"
            else -> null
        }
    }

    private fun stripPrefix(name: String, prefix: String): String? {
        if (prefix.isBlank()) return name
        val lowerPrefix = prefix.lowercase()
        if (name.lowercase().startsWith("$lowerPrefix ")) {
            val stripped = name.substring(prefix.length).trim()
            if (stripped.isNotBlank()) return stripped
        }
        return name
    }

    // --- shared helpers -----------------------------------------------------

    private fun anyInArea(data: StrategyData, areaId: String, domains: Set<String>): Boolean {
        val f = StrategyEntityFilter(
            data,
            domains = domains,
            areaSpec = StrategyEntityFilter.AreaSpec.Id(areaId),
            entityCategoryNone = true,
        )
        return data.states.keys.any { f.matches(it) }
    }

    private fun orderedAreas(data: StrategyData, hidden: List<String>, order: List<String>): List<StrategyArea> {
        val list = data.areas.values.filter { it.areaId !in hidden }
        if (order.isEmpty()) return list
        return list.sortedBy { orderIndex(order, it.areaId) }
    }

    private fun orderedFloors(data: StrategyData, order: List<String>): List<StrategyFloor> {
        val list = data.floors.values.toList()
        if (order.isEmpty()) return list.sortedBy { it.level ?: Int.MAX_VALUE }
        return list.sortedBy { orderIndex(order, it.floorId) }
    }

    /** HA's orderCompare: items in [order] sort by their index; the rest sort to
     *  the end (index == size). */
    private fun orderIndex(order: List<String>, id: String): Int {
        val idx = order.indexOf(id)
        return if (idx < 0) order.size else idx
    }

    private fun optionsGroups(strategy: JsonObject, areaId: String): JsonObject? {
        val opts = strategy["areas_options"] as? JsonObject ?: return null
        val areaOpts = opts[areaId] as? JsonObject ?: return null
        return areaOpts["groups_options"] as? JsonObject
    }

    private fun areaPath(areaId: String): String = "areas-$areaId"

    private const val UNASSIGNED_FLOOR = "__unassigned__"

    private fun JsonObject.stringList(vararg path: String): List<String> {
        var cur: JsonObject? = this
        for (i in 0 until path.size - 1) {
            cur = cur?.get(path[i]) as? JsonObject
        }
        val arr = cur?.get(path.last()) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }
}
