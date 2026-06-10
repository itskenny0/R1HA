package com.github.itskenny0.r1ha.nav

/**
 * Pure resolution of a Lovelace `navigate` action's `navigation_path` to an
 * R1HA navigation target. Kept separate from [AppNavGraph] so the mapping is
 * unit-testable without Compose Navigation plumbing.
 *
 * HA paths look like `/lovelace/<view>`, `/<dashboard>/<view>`, a bare
 * `<view>`, or a system-panel path (`/config/...`, `/history`). The resolver
 * decides which native destination each maps to; the caller turns the result
 * into an actual navigation call.
 */
sealed interface NavigateTarget {
    /** Open the full Lovelace WebView (empty path or an HA system panel). */
    data object Lovelace : NavigateTarget

    /** Open a native dashboard view: [dashboard] (null = current dashboard)
     *  plus the target [view] path within it. */
    data class DashboardView(val dashboard: String?, val view: String) : NavigateTarget
}

/**
 * HA's first-segment system-panel paths. A `navigate` to one of these can't
 * resolve to an R1HA dashboard view, so it routes to the Lovelace WebView
 * (which can render any HA panel) rather than dead-ending on a missing-view
 * scrim.
 */
internal val HA_SYSTEM_PANELS: Set<String> = setOf(
    "config", "history", "logbook", "developer-tools", "profile",
    "map", "energy", "calendar", "media-browser", "todo", "shopping-list",
    "hassio", "hacs",
)

/**
 * Resolve [navPath] against the [currentDashboard] the user is viewing.
 *  - empty path -> [NavigateTarget.Lovelace]
 *  - `/<system-panel>/...` -> [NavigateTarget.Lovelace]
 *  - `/lovelace/<view>` or bare `<view>` -> view in the current dashboard
 *  - `/<dashboard>/<view>` -> view in the named dashboard
 */
fun resolveNavigateTarget(navPath: String, currentDashboard: String?): NavigateTarget {
    val segments = navPath.trim().trim('/').split('/').filter { it.isNotEmpty() }
    if (segments.isEmpty()) return NavigateTarget.Lovelace
    if (segments.first() in HA_SYSTEM_PANELS) return NavigateTarget.Lovelace
    val view = segments.last()
    val dashboard = when {
        segments.size >= 2 && segments[0] != "lovelace" -> segments[0]
        else -> currentDashboard
    }
    return NavigateTarget.DashboardView(dashboard = dashboard, view = view)
}
