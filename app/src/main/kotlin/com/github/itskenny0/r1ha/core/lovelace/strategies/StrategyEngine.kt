package com.github.itskenny0.r1ha.core.lovelace.strategies

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Client-side expander for HA Lovelace *strategies*. Mirrors HA's
 * `expandLovelaceConfigStrategies` (`get-strategy.ts`): a strategy can sit at
 * the dashboard, view, or section level; each one is resolved to a concrete
 * config and substituted in place. The output is raw `lovelace/config` JSON in
 * exactly the shape HA's server would have produced, so R1HA's existing
 * [com.github.itskenny0.r1ha.core.lovelace.LovelaceParser] can parse it and the
 * existing renderer draws it: the strategy machinery never touches the card
 * renderer.
 *
 * Pure and synchronous: all registry/state IO is done up front by the loader
 * and handed in as a [StrategyData] snapshot, so this object is fixture-testable
 * with no Compose or coroutines.
 *
 * Resolution semantics (faithful to HA):
 *  - A dashboard-level `strategy:` expands to a set of views; the remaining
 *    dashboard keys (title, background) are preserved around it.
 *  - Each view with a `strategy:` expands to a concrete view; its own base keys
 *    (title, path, icon, subview) survive.
 *  - Inside a sections view each section with a `strategy:` expands in place, so
 *    a view that MIXES strategy sections with concrete sections keeps both
 *    (HA's per-section expansion).
 *  - An UNKNOWN strategy type (including any `custom:` JS strategy, which can't
 *    run natively) expands to a single explanatory markdown card rather than a
 *    blank, mirroring the unsupported-card decision.
 */
object StrategyEngine {

    /** Strategy type strings R1HA expands natively at the dashboard level. */
    private val DASHBOARD_STRATEGIES = setOf(
        "original-states", "areas", "home", "map", "iframe",
    )

    /** Strategy type strings R1HA expands natively at the view level. */
    private val VIEW_STRATEGIES = setOf(
        "original-states", "areas-overview", "area",
        "home-overview", "home-media-players", "home-other-devices", "home-area",
        "map", "iframe",
    )

    /** Strategy type strings R1HA expands natively at the section level. */
    private val SECTION_STRATEGIES = setOf("common-controls")

    /**
     * `true` when [rawConfig] references a strategy anywhere (dashboard, a view,
     * or a section). The screen uses this to decide whether to run the engine at
     * all rather than rendering the raw config directly.
     */
    fun hasAnyStrategy(rawConfig: JsonObject): Boolean {
        if (rawConfig["strategy"] is JsonObject) return true
        val views = rawConfig["views"] as? JsonArray ?: return false
        return views.any { v ->
            val view = v as? JsonObject ?: return@any false
            if (view["strategy"] is JsonObject) return@any true
            val sections = view["sections"] as? JsonArray ?: return@any false
            sections.any { (it as? JsonObject)?.get("strategy") is JsonObject }
        }
    }

    /**
     * `true` when [rawConfig] references the `common-controls` section strategy
     * (directly or via a dashboard/view strategy that emits one). Used to decide
     * whether to fire the usage-prediction WS call during the data load. A
     * dashboard/view strategy that CAN emit common-controls (home) also returns
     * true so the prediction is ready before its sections expand.
     */
    fun referencesUsagePrediction(rawConfig: JsonObject): Boolean {
        val root = (rawConfig["strategy"] as? JsonObject)?.typeOrNull()
        if (root == "home") return true
        val views = rawConfig["views"] as? JsonArray ?: return root == "home"
        return views.any { v ->
            val view = v as? JsonObject ?: return@any false
            val viewType = (view["strategy"] as? JsonObject)?.typeOrNull()
            if (viewType == "home-overview") return@any true
            val sections = view["sections"] as? JsonArray ?: return@any false
            sections.any { (it as? JsonObject)?.get("strategy")?.let { s -> (s as? JsonObject)?.typeOrNull() } == "common-controls" }
        }
    }

    /**
     * Expand every strategy reference in [rawConfig] against [data], returning a
     * concrete `lovelace/config` JSON object. Always returns a parseable config,
     * even on an unknown strategy (explanatory card) or empty home (empty-state).
     */
    fun expand(rawConfig: JsonObject, data: StrategyData): JsonObject {
        // Dashboard-level strategy: expand to a base config, then keep walking
        // its views (a dashboard strategy can itself emit views that carry
        // their own view/section strategies, e.g. areas -> areas-overview).
        val dashboardStrategy = (rawConfig["strategy"] as? JsonObject)
        val base: JsonObject = if (dashboardStrategy != null && rawConfig["views"] == null) {
            val type = dashboardStrategy.typeOrNull()
            val generated = when {
                data.starting -> placeholderDashboard(STARTING_TEXT)
                data.recoveryMode -> placeholderDashboard(RECOVERY_TEXT)
                type in DASHBOARD_STRATEGIES -> expandDashboard(type!!, dashboardStrategy, data)
                else -> placeholderDashboard(unknownStrategyText(type))
            }
            // Preserve the dashboard's sibling keys (title/background) around it.
            buildJsonObject {
                rawConfig.forEach { (k, v) -> if (k != "strategy") put(k, v) }
                generated.forEach { (k, v) -> put(k, v) }
            }
        } else {
            rawConfig
        }

        val views = (base["views"] as? JsonArray) ?: JsonArray(emptyList())
        val expandedViews = buildJsonArray {
            for (viewEl in views) {
                val view = viewEl as? JsonObject ?: continue
                add(expandView(view, data))
            }
        }
        return buildJsonObject {
            base.forEach { (k, v) -> if (k != "views") put(k, v) }
            put("views", expandedViews)
        }
    }

    // --- Dashboard-level expansion ------------------------------------------

    private fun expandDashboard(type: String, strategy: JsonObject, data: StrategyData): JsonObject =
        when (type) {
            "original-states" -> OriginalStatesStrategy.dashboard(strategy, data)
            "areas" -> AreasStrategy.dashboard(strategy, data)
            "home" -> HomeStrategy.dashboard(strategy, data)
            "map" -> singleViewDashboard(MapStrategy.view(strategy, data))
            "iframe" -> singleViewDashboard(IframeStrategy.view(strategy, data))
            else -> placeholderDashboard(unknownStrategyText(type))
        }

    // --- View-level expansion -----------------------------------------------

    private fun expandView(view: JsonObject, data: StrategyData): JsonObject {
        val viewStrategy = view["strategy"] as? JsonObject
        val expanded: JsonObject = when {
            viewStrategy != null -> {
                val type = viewStrategy.typeOrNull()
                val generated = when {
                    data.starting -> placeholderViewBody(STARTING_TEXT)
                    data.recoveryMode -> placeholderViewBody(RECOVERY_TEXT)
                    type in VIEW_STRATEGIES -> generateView(type!!, viewStrategy, data)
                    else -> placeholderViewBody(unknownStrategyText(type))
                }
                // Keep the view's own base keys (title/path/icon/subview), drop
                // the strategy key, layer the generated body on top.
                buildJsonObject {
                    view.forEach { (k, v) -> if (k != "strategy") put(k, v) }
                    generated.forEach { (k, v) -> put(k, v) }
                }
            }
            else -> view
        }
        // Now expand any strategy SECTIONS inside the (possibly already
        // strategy-generated) view, in place.
        val sections = expanded["sections"] as? JsonArray ?: return expanded
        val newSections = buildJsonArray {
            for (secEl in sections) {
                val section = secEl as? JsonObject ?: continue
                val secStrategy = section["strategy"] as? JsonObject
                if (secStrategy == null) {
                    add(section)
                } else {
                    val type = secStrategy.typeOrNull()
                    val body = when {
                        type in SECTION_STRATEGIES -> generateSection(type!!, secStrategy, data)
                        else -> placeholderSectionBody(unknownStrategyText(type))
                    }
                    add(
                        buildJsonObject {
                            section.forEach { (k, v) -> if (k != "strategy") put(k, v) }
                            body.forEach { (k, v) -> put(k, v) }
                        },
                    )
                }
            }
        }
        return buildJsonObject {
            expanded.forEach { (k, v) -> if (k != "sections") put(k, v) }
            put("sections", newSections)
        }
    }

    private fun generateView(type: String, strategy: JsonObject, data: StrategyData): JsonObject =
        when (type) {
            "original-states" -> OriginalStatesStrategy.view(strategy, data)
            "areas-overview" -> AreasStrategy.overviewView(strategy, data)
            "area" -> AreasStrategy.areaView(strategy, data)
            "home-overview" -> HomeStrategy.overviewView(strategy, data)
            "home-area" -> HomeStrategy.areaView(strategy, data)
            "home-media-players" -> HomeStrategy.mediaPlayersView(strategy, data)
            "home-other-devices" -> HomeStrategy.otherDevicesView(strategy, data)
            "map" -> MapStrategy.view(strategy, data)
            "iframe" -> IframeStrategy.view(strategy, data)
            else -> placeholderViewBody(unknownStrategyText(type))
        }

    private fun generateSection(type: String, strategy: JsonObject, data: StrategyData): JsonObject =
        when (type) {
            "common-controls" -> CommonControlsStrategy.section(strategy, data)
            else -> placeholderSectionBody(unknownStrategyText(type))
        }

    // --- Shared placeholder builders ----------------------------------------

    internal fun singleViewDashboard(view: JsonObject): JsonObject = buildJsonObject {
        put("views", buildJsonArray { add(view) })
    }

    private fun placeholderDashboard(text: String): JsonObject = buildJsonObject {
        put("views", buildJsonArray { add(placeholderViewBody(text)) })
    }

    /** A view body carrying one explanatory markdown card. */
    internal fun placeholderViewBody(text: String): JsonObject = buildJsonObject {
        put("cards", buildJsonArray { add(markdownCard(text)) })
    }

    private fun placeholderSectionBody(text: String): JsonObject = buildJsonObject {
        put("type", "grid")
        put("cards", buildJsonArray { add(markdownCard(text)) })
    }

    internal fun markdownCard(text: String): JsonObject = buildJsonObject {
        put("type", "markdown")
        put("content", text)
    }

    private fun unknownStrategyText(type: String?): String =
        if (type != null && type.startsWith("custom:")) {
            "This dashboard uses the custom strategy \"$type\", which runs as a Lovelace plugin on the server. R1HA can't run plugin code, so open the dashboard in the full Lovelace view to see it."
        } else {
            "This dashboard uses the \"${type ?: "unknown"}\" strategy, which R1HA can't expand natively. Open it in the full Lovelace view to see the generated layout."
        }

    private const val STARTING_TEXT =
        "Home Assistant is still starting up. This dashboard will fill in once it finishes loading."

    private const val RECOVERY_TEXT =
        "Home Assistant is in recovery mode. Strategy dashboards are unavailable until it returns to normal operation."

    internal fun JsonObject.typeOrNull(): String? =
        (this["type"] as? JsonPrimitive)?.content?.takeUnless { it.isBlank() }
}
