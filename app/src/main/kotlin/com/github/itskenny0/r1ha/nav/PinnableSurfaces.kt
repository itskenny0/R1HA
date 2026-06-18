package com.github.itskenny0.r1ha.nav

import androidx.compose.runtime.Immutable
import com.github.itskenny0.r1ha.BuildConfig

/**
 * One surface the user is allowed to PIN to the side navigation rail / drawer
 * (see [com.github.itskenny0.r1ha.core.prefs.NavPanelSettings.pinnedSurfaces]).
 *
 * [route] is the [Routes] string constant the shell navigates to and the stable id
 * persisted in the pin list. [label] is the human title shown in the drawer / used in
 * the pin affordance's spoken description. [glyph] is the short monospace mark drawn in
 * the rail / drawer, matching the text-glyph contract the existing
 * [com.github.itskenny0.r1ha.ui.components.NavDestination] uses (the shell renders
 * glyphs as text, so there is zero coupling to the per-glyph vector composables).
 */
@Immutable
data class PinnableSurface(
    val route: String,
    val label: String,
    val glyph: String,
)

/**
 * The catalogue of surfaces a user can pin to the side navigation panel. Shared by the
 * pin affordance (which looks a surface up by current route to decide whether to show a
 * pin toggle) and the shell host (which maps the persisted pin list onto renderable
 * destinations). Kept in `nav/` next to [Routes] so both consumers depend on one source
 * of truth and the route ids never drift from the registered nav graph.
 *
 * Ordering here is the order surfaces are offered in any "pick what to pin" UI; the
 * user's chosen pin order is independent (it follows their pin sequence, persisted in
 * [com.github.itskenny0.r1ha.core.prefs.NavPanelSettings.pinnedSurfaces]).
 *
 * The core always-present destinations (Home / Today / Search / Assist / Settings) are
 * intentionally absent: they are rendered by the shell unconditionally and are never
 * "pinned" in the user-configurable sense.
 */
object PinnableSurfaces {

    /**
     * All pinnable surfaces, in offer order. On the slim legacy build (R1HAL) the
     * catalogue is filtered to surfaces whose screen survives (see [LegacyFeatures]),
     * so the drawer never offers a destination that resolves only to the
     * "not in this build" placeholder.
     */
    val ALL: List<PinnableSurface> = listOf(
        PinnableSurface(Routes.DASHBOARD, "Today", "◴"),
        PinnableSurface(Routes.DASHBOARDS, "Dashboards", "▤"),
        PinnableSurface(Routes.CAMERAS, "Cameras", "◉"),
        PinnableSurface(Routes.ENERGY, "Energy", "⚡"),
        PinnableSurface(Routes.AUTOMATIONS, "Automations", "⚙"),
        PinnableSurface(Routes.SCENES, "Scenes", "✦"),
        PinnableSurface(Routes.LOGBOOK, "Logbook", "≡"),
        PinnableSurface(Routes.HELPERS, "Helpers", "⊹"),
        PinnableSurface(Routes.AREAS, "Areas", "▢"),
        PinnableSurface(Routes.FLOORS, "Floors", "≣"),
        PinnableSurface(Routes.ZONES, "Zones", "⌖"),
        PinnableSurface(Routes.LABELS, "Labels", "❏"),
        PinnableSurface(Routes.DEVICES, "Devices", "⌗"),
        PinnableSurface(Routes.INTEGRATIONS, "Integrations", "⧉"),
        PinnableSurface(Routes.WEATHER, "Weather", "☼"),
        PinnableSurface(Routes.PERSONS, "People", "☻"),
        PinnableSurface(Routes.CALENDARS, "Calendars", "▦"),
        PinnableSurface(Routes.TODO, "To-do", "☑"),
        PinnableSurface(Routes.NOTIFICATIONS, "Notifications", "✉"),
        PinnableSurface(Routes.MEDIA_BROWSE, "Media", "♪"),
        PinnableSurface(Routes.UPDATES, "Updates", "↑"),
        PinnableSurface(Routes.REPAIRS, "Repairs", "✚"),
        PinnableSurface(Routes.STATISTICS, "Statistics", "▥"),
        PinnableSurface(Routes.SYSTEM_HEALTH, "System", "♡"),
        PinnableSurface(Routes.LOGS, "Logs", "⎙"),
        PinnableSurface(Routes.USERS, "Users", "⚇"),
        PinnableSurface(Routes.TAGS, "Tags", "⌁"),
        PinnableSurface(Routes.BLUEPRINTS, "Blueprints", "❖"),
        PinnableSurface(Routes.SERVICES, "Actions", "▷"),
        PinnableSurface(Routes.SERVICE_CALLER, "Call action", "⎆"),
        PinnableSurface(Routes.TEMPLATE, "Templates", "{}"),
        PinnableSurface(Routes.BACKUPS, "Backups", "⤓"),
    ).let { all ->
        if (BuildConfig.IS_LEGACY) all.filter { LegacyFeatures.isAvailable(it.route) } else all
    }

    private val byRoute: Map<String, PinnableSurface> = ALL.associateBy { it.route }

    /** The surface for [route], or null when [route] is not pinnable (core
     *  destinations, onboarding, drill-in routes like history/{id}). */
    fun forRoute(route: String?): PinnableSurface? = route?.let { byRoute[it] }

    /** Is [route] a surface the user can pin? */
    fun isPinnable(route: String?): Boolean = route != null && route in byRoute

    /** Resolve a persisted pin list to renderable surfaces, dropping any unknown
     *  route ids (forward-compat with pin lists written by a newer build). */
    fun resolve(routeIds: List<String>): List<PinnableSurface> =
        routeIds.mapNotNull { byRoute[it] }
}
