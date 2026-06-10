package com.github.itskenny0.r1ha.core.lovelace.strategies

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Port of HA's `map` strategy: a panel view holding a single map card with
 * `show_all` + `auto_fit`. R1HA's map card draws an abstract canvas (no real
 * tiles), so `show_all` (plot every entity with coordinates) is honoured as far
 * as the canvas substrate allows; `auto_fit` is a no-op on the always-fitted
 * abstract canvas and is carried for fidelity.
 */
object MapStrategy {
    fun view(strategy: JsonObject, data: StrategyData): JsonObject = buildJsonObject {
        put("type", "panel")
        // R1HA's view parser flags panel mode from the `panel:` boolean, not the
        // `type: panel` shorthand HA strategies emit, so set both.
        put("panel", true)
        put("title", "Map")
        put("icon", "mdi:map")
        putJsonArray("cards") {
            addJsonObject {
                put("type", "map")
                put("auto_fit", true)
                put("show_all", true)
                putJsonArray("geo_location_sources") { add("all") }
            }
        }
    }
}

/**
 * Port of HA's `iframe` strategy: a panel view holding a single iframe card for
 * the configured `url`. R1HA has no iframe card (no WebView in the dashboard
 * renderer), so the iframe card parses to the labelled Unsupported card, which
 * shows the URL and the raw config rather than a blank panel.
 */
object IframeStrategy {
    fun view(strategy: JsonObject, data: StrategyData): JsonObject {
        val url = (strategy["url"] as? JsonPrimitive)?.content
        val title = (strategy["title"] as? JsonPrimitive)?.content
        return buildJsonObject {
            put("type", "panel")
            put("panel", true)
            if (title != null) put("title", title)
            putJsonArray("cards") {
                addJsonObject {
                    put("type", "iframe")
                    if (url != null) put("url", url)
                }
            }
        }
    }
}

/**
 * Port of HA's `usage_prediction/common-controls` SECTION strategy. Emits a grid
 * section of tiles for the entities the user most commonly controls right now,
 * honouring `limit` / `include_entities` / `exclude_entities` / `hide_empty` /
 * `heading`.
 *
 * Fidelity notes vs HA:
 *  - When the server answers `usage_prediction/common_control`, that ordered
 *    list is used (filtered to existing, non-hidden, non-excluded ids). The
 *    loader fills [StrategyData.commonControls].
 *  - When the integration isn't loaded ([StrategyData.commonControls] is null),
 *    HA renders a "not loaded" note; R1HA instead degrades to the most
 *    recently-changed toggleable entities so the section is still useful, and
 *    documents the divergence here.
 */
object CommonControlsStrategy {

    private const val DEFAULT_LIMIT = 8

    /** Domains R1HA treats as "toggleable" for the recently-changed fallback. */
    private val TOGGLEABLE_DOMAINS = setOf(
        "light", "switch", "fan", "input_boolean", "cover", "lock", "media_player",
        "climate", "humidifier", "vacuum", "scene", "script", "automation",
    )

    fun section(strategy: JsonObject, data: StrategyData): JsonObject {
        val limit = (strategy["limit"] as? JsonPrimitive)?.content?.toIntOrNull() ?: DEFAULT_LIMIT
        val include = strategy.idList("include_entities").filter { it in data.states }
        val exclude = strategy.idList("exclude_entities").toSet()
        val hideEmpty = (strategy["hide_empty"] as? JsonPrimitive)?.content?.toBoolean() ?: false
        val heading = strategy["heading"] as? JsonObject

        // Pinned entities already fill the section.
        if (include.size >= limit) {
            return gridSection(heading, include.take(limit), hideEmpty = false)
        }

        val predicted: List<String> = data.commonControls
            ?.filter { id ->
                id in data.states &&
                    data.entities[id]?.hiddenBy == null &&
                    id !in exclude &&
                    id !in include
            }
            ?: recentlyChangedFallback(data, exclude, include)

        val entities = (include + predicted).distinct().take(limit)
        if (entities.isEmpty()) {
            // HA: a "no data" note, disabled when hide_empty.
            return buildJsonObject {
                put("type", "grid")
                putJsonArray("cards") {
                    heading?.let { add(it) }
                    add(StrategyEngine.markdownCard("No commonly-used controls to suggest yet."))
                }
                if (hideEmpty) put("disabled", true)
            }
        }
        return gridSection(heading, entities, hideEmpty = false)
    }

    private fun recentlyChangedFallback(
        data: StrategyData,
        exclude: Set<String>,
        include: List<String>,
    ): List<String> = data.states.values
        .filter { it.domain in TOGGLEABLE_DOMAINS && it.entityId !in exclude && it.entityId !in include }
        .filter { data.entities[it.entityId]?.hiddenBy == null }
        .sortedByDescending { it.lastChangedMs }
        .map { it.entityId }

    private fun gridSection(heading: JsonObject?, entities: List<String>, hideEmpty: Boolean): JsonObject =
        buildJsonObject {
            put("type", "grid")
            putJsonArray("cards") {
                heading?.let { add(it) }
                for (id in entities) {
                    addJsonObject {
                        put("type", "tile")
                        put("entity", id)
                        putJsonArray("state_content") { add("state"); add("area_name") }
                        put("show_entity_picture", true)
                    }
                }
            }
            if (hideEmpty) put("disabled", true)
        }

    private fun JsonObject.idList(key: String): List<String> {
        val arr = this[key] as? kotlinx.serialization.json.JsonArray ?: return emptyList()
        return arr.mapNotNull { (it as? JsonPrimitive)?.content }
    }
}
