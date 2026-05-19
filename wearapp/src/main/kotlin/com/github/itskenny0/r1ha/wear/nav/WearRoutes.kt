package com.github.itskenny0.r1ha.wear.nav

/**
 * Route string constants for the Wear OS nav graph.
 *
 * The watch exposes a focused subset of the full phone app's navigation:
 * - [ONBOARDING]  — first-run server URL + LLAT token entry
 * - [CARD_STACK]  — main screen: swipeable entity card pager
 * - [SCENES]      — flat list of scenes/scripts for quick activation
 * - [SETTINGS]    — server URL, token, disconnect
 */
object WearRoutes {
    const val ONBOARDING = "wear_onboarding"
    const val CARD_STACK = "wear_card_stack"
    const val SCENES     = "wear_scenes"
    const val SETTINGS   = "wear_settings"
}
