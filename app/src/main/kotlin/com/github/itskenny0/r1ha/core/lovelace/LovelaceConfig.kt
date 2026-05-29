package com.github.itskenny0.r1ha.core.lovelace

import androidx.compose.runtime.Immutable
import kotlinx.serialization.json.JsonObject

/**
 * Typed in-memory model for a parsed HA Lovelace dashboard configuration.
 *
 * Mirrors the subset of `lovelace/config` shape that the dashboards renderer
 * understands today. Any card type we don't model is preserved as
 * [LovelaceCard.Unsupported] with its raw JSON payload intact, so the screen
 * can still show a placeholder + the original config for debugging without
 * dropping it from the view.
 *
 * Read-only on R1HA's side: parsing happens once per dashboard fetch and the
 * tree is treated as immutable. Local-only edits land as a separate overrides
 * layer (see [LovelaceOverrides]) keyed by `(viewPath, cardIndex)`, applied at
 * render time so the imported HA config is never mutated.
 *
 * @Immutable so Compose can skip recomposition when references haven't changed.
 */
@Immutable
data class LovelaceConfig(
    val title: String?,
    val views: List<LovelaceView>,
)

/** One view inside a dashboard. corresponds to a tab in HA's frontend. */
@Immutable
data class LovelaceView(
    /** User-facing title; falls back to the path or index when empty. */
    val title: String?,
    /**
     * URL-style identifier HA uses to address the view (e.g. "lights",
     * "default_view"). Stable across HA restarts; we key local overrides
     * against it so reordering views in HA doesn't shuffle our overrides.
     * Falls back to the index-as-string when the YAML omits it.
     */
    val path: String,
    /** Optional MDI icon string (`mdi:lightbulb` etc.). */
    val icon: String?,
    /**
     * `true` when the view should render its cards in panel mode (single
     * card filling the viewport). We honour this when rendering: a `panel: true`
     * view with one card hides the surrounding scroll/padding chrome.
     */
    val panel: Boolean,
    /**
     * The parsed cards in display order. Empty when the view exists but
     * has no cards (legitimate during early-edit on HA's side).
     */
    val cards: List<LovelaceCard>,
)

/**
 * Sealed hierarchy of card types R1HA renders natively. Adding a new
 * type means: declare a data class here, parse it in [LovelaceParser],
 * and add a branch in [com.github.itskenny0.r1ha.feature.dashboards.cards.LovelaceCardRenderer].
 *
 * Every variant carries [raw] so the editor's JSON view can show + edit
 * the original config without round-tripping through our typed model
 * (which would silently drop fields we don't understand yet).
 */
@Immutable
sealed class LovelaceCard {
    /** Card type string from the original config (`"entities"`, `"tile"`, etc.). */
    abstract val type: String

    /** Raw JSON config as HA returned it; the editor + Unsupported renderer
     *  both rely on this being preserved verbatim. */
    abstract val raw: JsonObject

    /** Vertical list of entity rows. */
    @Immutable
    data class Entities(
        override val raw: JsonObject,
        val title: String?,
        val showHeaderToggle: Boolean?,
        val entities: List<EntityRow>,
    ) : LovelaceCard() {
        override val type: String = "entities"
    }

    /** Compact grid of entity glyphs + state readouts. */
    @Immutable
    data class Glance(
        override val raw: JsonObject,
        val title: String?,
        val entities: List<EntityRow>,
        val columns: Int?,
        val showName: Boolean,
        val showState: Boolean,
        val showIcon: Boolean,
    ) : LovelaceCard() {
        override val type: String = "glance"
    }

    /** Single tappable action button. Either a bare action (no entity) or
     *  wired to a specific entity for state-coloured display. */
    @Immutable
    data class Button(
        override val raw: JsonObject,
        val entityId: String?,
        val name: String?,
        val icon: String?,
        val showName: Boolean,
        val showIcon: Boolean,
        val showState: Boolean,
        val tapAction: LovelaceAction?,
    ) : LovelaceCard() {
        override val type: String = "button"
    }

    /** Modern compact tile (HA's current first-class card type). */
    @Immutable
    data class Tile(
        override val raw: JsonObject,
        val entityId: String,
        val name: String?,
        val icon: String?,
        val hideState: Boolean,
        val vertical: Boolean,
        val color: String?,
        val tapAction: LovelaceAction?,
    ) : LovelaceCard() {
        override val type: String = "tile"
    }

    /** Dedicated light card. Brightness + on/off; for full colour control
     *  the card-stack archetype is still the right surface. */
    @Immutable
    data class Light(
        override val raw: JsonObject,
        val entityId: String,
        val name: String?,
        val icon: String?,
    ) : LovelaceCard() {
        override val type: String = "light"
    }

    /** Gauge readout for one numeric entity. */
    @Immutable
    data class Gauge(
        override val raw: JsonObject,
        val entityId: String,
        val name: String?,
        val unit: String?,
        val min: Double,
        val max: Double,
        val needle: Boolean,
        val severity: GaugeSeverity?,
    ) : LovelaceCard() {
        override val type: String = "gauge"
    }

    /** Weather card with current conditions + optional forecast strip. */
    @Immutable
    data class WeatherForecast(
        override val raw: JsonObject,
        val entityId: String,
        val name: String?,
        val showCurrent: Boolean,
        val showForecast: Boolean,
        val forecastType: String?,
        val forecastSlots: Int?,
    ) : LovelaceCard() {
        override val type: String = "weather-forecast"
    }

    /** Markdown body, optionally Jinja-templated at render time. */
    @Immutable
    data class Markdown(
        override val raw: JsonObject,
        val title: String?,
        val content: String,
    ) : LovelaceCard() {
        override val type: String = "markdown"
    }

    /** Static section heading inside a view. */
    @Immutable
    data class Heading(
        override val raw: JsonObject,
        val heading: String,
        val headingStyle: String,
        val icon: String?,
    ) : LovelaceCard() {
        override val type: String = "heading"
    }

    /** Vertical stack of child cards. */
    @Immutable
    data class VerticalStack(
        override val raw: JsonObject,
        val title: String?,
        val cards: List<LovelaceCard>,
    ) : LovelaceCard() {
        override val type: String = "vertical-stack"
    }

    /** Horizontal stack of child cards. */
    @Immutable
    data class HorizontalStack(
        override val raw: JsonObject,
        val title: String?,
        val cards: List<LovelaceCard>,
    ) : LovelaceCard() {
        override val type: String = "horizontal-stack"
    }

    /** Grid of child cards in `columns` columns. */
    @Immutable
    data class Grid(
        override val raw: JsonObject,
        val title: String?,
        val columns: Int,
        val square: Boolean,
        val cards: List<LovelaceCard>,
    ) : LovelaceCard() {
        override val type: String = "grid"
    }

    /** Shows its [card] only when every condition in [conditions] passes. */
    @Immutable
    data class Conditional(
        override val raw: JsonObject,
        val conditions: List<LovelaceCondition>,
        val card: LovelaceCard,
    ) : LovelaceCard() {
        override val type: String = "conditional"
    }

    /** Any card type we don't natively render. The renderer surfaces a
     *  placeholder with the [type] label and a debug-only expander that
     *  shows the raw JSON. */
    @Immutable
    data class Unsupported(
        override val raw: JsonObject,
        override val type: String,
    ) : LovelaceCard()
}

/**
 * One row inside an entities-card-like list (entities / glance).
 *
 * HA accepts either a bare entity_id string or a richer object with name +
 * icon + secondary_info overrides; both shapes resolve to this same row.
 */
@Immutable
data class EntityRow(
    val entityId: String,
    /** Override label; null falls back to the entity's `friendly_name`. */
    val name: String?,
    /** Override icon string ("mdi:lightbulb"). Null = derived from domain. */
    val icon: String?,
    /**
     * What to render in the small secondary line under the primary state.
     * HA accepts a fixed enum: "last-changed", "last-triggered",
     * "last-updated", "area", "position", "state", "tilt-position",
     * "brightness", "entity-id". Free-form for now; renderers handle the
     * subset that maps cleanly to data we already have.
     */
    val secondaryInfo: String?,
)

/**
 * Subset of HA's action_config we understand. Tap on a button card or
 * tile card fires one of these; unknown variants degrade to a no-op.
 */
@Immutable
sealed class LovelaceAction {
    @Immutable
    data class CallService(
        val service: String,
        val entityId: String?,
        val data: JsonObject?,
    ) : LovelaceAction()

    @Immutable
    data class Navigate(val path: String) : LovelaceAction()

    @Immutable
    data class Url(val url: String) : LovelaceAction()

    /** `toggle` / `more-info` / `none`. */
    @Immutable
    data class Builtin(val name: String) : LovelaceAction()
}

/** Severity bands for the gauge card. colour the needle when the value
 *  passes a threshold. */
@Immutable
data class GaugeSeverity(
    val green: Double?,
    val yellow: Double?,
    val red: Double?,
)

/**
 * Condition for [LovelaceCard.Conditional]. We model the two most common
 * shapes (state equals / numeric state) and treat anything else as a
 * permissive `true` so the wrapped card always renders rather than being
 * silently hidden by an unsupported condition.
 */
@Immutable
sealed class LovelaceCondition {
    @Immutable
    data class StateEquals(val entityId: String, val state: String) : LovelaceCondition()

    @Immutable
    data class NumericState(
        val entityId: String,
        val above: Double?,
        val below: Double?,
    ) : LovelaceCondition()

    /** Catch-all: condition shape we don't understand. Evaluates to true. */
    @Immutable
    data object AlwaysTrue : LovelaceCondition()
}

/** Dashboard descriptor from `lovelace/dashboards/list`. The default
 *  dashboard is identified by a null [urlPath] (HA's convention). */
@Immutable
data class LovelaceDashboard(
    /** Stable id assigned by HA. Null for the default dashboard. */
    val id: String?,
    /**
     * URL path the user types in HA's web UI (`lovelace`, `energy`,
     * `my-dashboard`). Null means the default dashboard, which is what
     * the WS call expects when fetching its config too.
     */
    val urlPath: String?,
    val title: String,
    val icon: String?,
    val showInSidebar: Boolean,
    val requireAdmin: Boolean,
    /** `yaml` = config in storage / files; `storage` = UI-managed. */
    val mode: String?,
)
