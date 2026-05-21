package com.github.itskenny0.r1ha.wear.nav

/**
 * Route string constants for the Wear OS nav graph.
 */
object WearRoutes {
    const val ONBOARDING         = "wear_onboarding"
    const val CARD_STACK         = "wear_card_stack"
    const val MENU               = "wear_menu"
    const val FAVORITES_PICKER   = "wear_favorites_picker"
    const val SCENES             = "wear_scenes"
    const val ASSIST             = "wear_assist"
    const val AUTOMATIONS        = "wear_automations"
    const val NOTIFICATIONS      = "wear_notifications"
    const val DASHBOARD          = "wear_dashboard"
    const val HELPERS            = "wear_helpers"
    const val SEARCH             = "wear_search"
    const val SETTINGS           = "wear_settings"
    const val REMOTE             = "wear_remote"
    const val FAVORITES_VIEW     = "wear_favorites_view"
    const val MEDIA_PLAYER_DETAIL = "wear_media_player/{entityId}"
    const val CLIMATE_DETAIL     = "wear_climate/{entityId}"

    fun mediaPlayerDetail(entityId: String) = "wear_media_player/$entityId"
    fun climateDetail(entityId: String) = "wear_climate/$entityId"
}
