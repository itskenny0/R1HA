package com.github.itskenny0.r1ha.core.ha

/**
 * One panel entry from HA's `get_panels` WebSocket command.
 *
 * HA's sidebar is composed of panels: built-in ones (lovelace, config, logbook,
 * history, map, etc.) and custom panels registered by integrations
 * (component_name "custom") or via the `panel_iframe` / `panel_custom`
 * platforms (component_name "iframe" / "js"). The R1HA client natively renders
 * most of HA's built-in panels, but custom and iframe panels require HA's own
 * frontend. This type models the discovery payload so the settings picker can
 * list genuinely external panels the user might want to pin.
 *
 * The full panel object also carries a `config` object with integration-specific
 * fields. We don't decode it here because the only thing we need from config at
 * render time is already derivable: the URL is always
 * `<serverBase>/<urlPath>` regardless of panel type.
 */
data class HaPanel(
    /**
     * URL path segment used to navigate to this panel in HA's frontend,
     * e.g. "hacs", "esphome", "lovelace", "config". Acts as the stable id
     * for this panel: HA guarantees uniqueness across panels on a given
     * instance, and it never changes while the panel is registered.
     */
    val urlPath: String,
    /**
     * Friendly display title from HA's sidebar. May be a raw string
     * ("HACS", "ESPHome") or null when the panel relies on a frontend
     * translation key. Null panels fall back to the urlPath as a display
     * label in the picker.
     */
    val title: String?,
    /**
     * MDI icon slug (e.g. "mdi:home-assistant-community-store") from the
     * panel registration, or null when the panel didn't register one. The
     * app doesn't render MDI icons natively; null triggers a generic glyph
     * at the nav-entry render site instead.
     */
    val icon: String?,
    /**
     * HA's panel component type. Known values: "lovelace", "config",
     * "iframe", "custom", "history", "logbook", "map", "todo",
     * "media-browser", "developer-tools", "energy". The filter logic in
     * [isNativelyRendered] uses this to exclude panels R1HA already covers
     * with a native screen, so the picker only shows the genuinely external
     * ones.
     */
    val componentName: String,
)

/**
 * url_paths that R1HA renders natively. When HA's panel list includes any
 * of these, the picker excludes them so the user isn't offered a WebView
 * route to a screen they can already reach from the nav rail.
 *
 * Maintained as a Set<String> for O(1) lookup. Adding a new native screen
 * requires adding its url_path here so the panel picker stays in sync.
 *
 * The url_paths here are HA's canonical ones: "lovelace" (any Lovelace
 * dashboard registers under a custom path OR the default "lovelace"),
 * "config" covers Settings in HA's frontend, "logbook" / "history" /
 * "map" / "todo" / "media-browser" / "energy" are the known built-in
 * sidebar panels that R1HA surfaces natively or intentionally skips.
 * "developer-tools" and "profile" are system panels with no meaningful
 * R1HA equivalent (profile is per-user OAuth state, developer-tools is
 * HA's own dev playground).
 */
private val NATIVE_URL_PATHS: Set<String> = setOf(
    "lovelace",
    "config",
    "energy",
    "history",
    "logbook",
    "map",
    "todo",
    "media-browser",
    "developer-tools",
    "profile",
)

/**
 * Component names whose panels are always omitted, regardless of url_path.
 * "lovelace" panels are already covered by the native dashboards feature;
 * "config" is the native HA Settings UI. These are excluded by component_name
 * rather than (only) url_path because custom dashboards register as "lovelace"
 * under non-standard url_paths (e.g. "lovelace-2") and all of them are native.
 */
private val NATIVE_COMPONENT_NAMES: Set<String> = setOf(
    "lovelace",
    "config",
)

/**
 * Returns true when this panel is already rendered natively in R1HA and should
 * be excluded from the external-panel picker. False = the picker should offer it.
 *
 * A panel is native when:
 *   - its [HaPanel.urlPath] appears in [NATIVE_URL_PATHS], OR
 *   - its [HaPanel.componentName] appears in [NATIVE_COMPONENT_NAMES].
 *
 * This errs on the side of hiding rather than showing: a panel R1HA doesn't
 * natively render but whose url_path collides with a known native entry won't
 * appear in the picker, but that collision is unlikely in practice and the
 * user can still reach it via the full Lovelace WebView.
 */
fun HaPanel.isNativelyRendered(): Boolean =
    urlPath in NATIVE_URL_PATHS || componentName in NATIVE_COMPONENT_NAMES
