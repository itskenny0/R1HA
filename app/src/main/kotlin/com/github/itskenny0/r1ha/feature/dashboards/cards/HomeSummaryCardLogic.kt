package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.nav.Routes

/**
 * Pure decision logic for the `home-summary` card. Contains the 7 summary
 * categories HA models (light, climate, security, media_players, maintenance,
 * energy, persons) and their R1HA equivalents (label, MDI icon, nav destination).
 *
 * All functions are stateless and testable without Compose or Android.
 */
object HomeSummaryCardLogic {

    /** The seven categories HA's home-summary card supports. */
    val KNOWN_SUMMARIES = setOf(
        "light", "climate", "security", "media_players",
        "maintenance", "energy", "persons",
    )

    /**
     * Human-readable title for a summary category. Falls back to the raw value
     * for unknown categories (additive / future-proof).
     */
    fun labelFor(summary: String): String = when (summary) {
        "light" -> "Lights"
        "climate" -> "Climate"
        "security" -> "Security"
        "media_players" -> "Media"
        "maintenance" -> "Maintenance"
        "energy" -> "Energy"
        "persons" -> "People"
        else -> summary.replace('_', ' ').replaceFirstChar { it.uppercase() }
    }

    /**
     * MDI icon identifier for a summary category. These are the same icons HA
     * uses in HOME_SUMMARIES_ICONS (home-summaries.ts).
     */
    fun iconFor(summary: String): String = when (summary) {
        "light" -> "mdi:lamps"
        "climate" -> "mdi:home-thermometer"
        "security" -> "mdi:security"
        "media_players" -> "mdi:multimedia"
        "maintenance" -> "mdi:wrench"
        "energy" -> "mdi:lightning-bolt"
        "persons" -> "mdi:account-multiple"
        else -> "mdi:home"
    }

    /**
     * R1HA navigation route for a summary category. Tapping the card navigates
     * to the nearest native screen for that category. Unknown categories fall
     * back to the searches screen (shows all entities).
     *
     * Routes used:
     *  - light, climate, security, media_players: the entity list filtered by domain.
     *  - maintenance: the Updates screen (covers software + repair issues).
     *  - energy: the Energy screen.
     *  - persons: the Persons screen.
     */
    fun navRouteFor(summary: String): String = when (summary) {
        "light" -> Routes.SEARCH          // filter by domain=light in search
        "climate" -> Routes.SEARCH        // filter by domain=climate
        "security" -> Routes.SEARCH       // filter by domain=alarm_control_panel
        "media_players" -> Routes.SEARCH  // filter by domain=media_player
        "maintenance" -> Routes.UPDATES
        "energy" -> Routes.ENERGY
        "persons" -> Routes.PERSONS
        else -> Routes.SEARCH
    }

    /**
     * A summary description line from a live entity count for display in the card.
     * [activeCount] is the number of relevant entities in a "notable" state
     * (lights on, media playing, etc.); [totalCount] is the total number of
     * entities in the domain. A null [activeCount] (state not yet loaded) yields
     * a loading placeholder.
     */
    fun statusLine(summary: String, activeCount: Int?, totalCount: Int): String {
        if (activeCount == null) return "..."
        return when (summary) {
            "light" -> if (activeCount == 0) "All off" else "$activeCount on"
            "climate" -> if (activeCount == 0) "All idle" else "$activeCount active"
            "security" -> if (activeCount == 0) "All secure" else "$activeCount open"
            "media_players" -> if (activeCount == 0) "Nothing playing" else "$activeCount playing"
            "maintenance" -> if (activeCount == 0) "All good" else "$activeCount issue${if (activeCount != 1) "s" else ""}"
            "energy" -> if (activeCount == 0) "No data" else "$activeCount kWh today"
            "persons" -> if (activeCount == 0) "Nobody home" else "$activeCount home"
            else -> if (totalCount == 0) "No devices" else "$totalCount device${if (totalCount != 1) "s" else ""}"
        }
    }
}
