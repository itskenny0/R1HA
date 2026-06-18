package com.github.itskenny0.r1ha.nav

/**
 * The keep / drop route partition for the slim "legacy" build (the `legacy` product
 * flavour, minSdk 21). One source of truth so the nav graph, the pinnable-surface
 * catalogue and any future affordance gating all agree on what survives.
 *
 * The legacy build keeps only the card stack and the surfaces directly reachable from
 * it or its more-info sheets; everything else is registered to a placeholder so the
 * real screen is dead-code-eliminated by R8 (BuildConfig.IS_LEGACY is a compile-time
 * constant — see [AppNavGraph] and app/build.gradle.kts).
 *
 * Safety contract enforced by LegacyFeaturesTest: any route an affordance can navigate
 * to is either in [KEPT_ROUTES] (real screen) or [PLACEHOLDER_ROUTES] (the "not in this
 * build" stub) — never neither, or the legacy variant would crash on navigate().
 */
object LegacyFeatures {

    /**
     * Routes whose real screen is retained in the legacy build: the shell, the
     * card-stack favourites flow, settings (with its core categories), and the
     * more-info drill-ins (history / logbook / media-browse) plus the lightweight
     * Lovelace / pinned-panel WebViews.
     */
    val KEPT_ROUTES: Set<String> = setOf(
        // Shell + onboarding
        Routes.ONBOARDING,
        Routes.CARD_STACK,
        Routes.CARD_STACK_FOCUS,
        Routes.FAVORITES_PICKER,
        // Settings and its standalone sub-screens that aren't device-mode features
        Routes.SETTINGS,
        Routes.SETTINGS_KEY_BINDINGS,
        Routes.SIDEBAR_CONFIG,
        Routes.THEME_PICKER,
        Routes.MODIFIED_SETTINGS,
        Routes.ABOUT,
        Routes.DEV_MENU,
        Routes.LONG_LIVED_TOKEN,
        Routes.DEVICE,
        // Diagnostics — cheap, and System Health links only to Logs (also kept)
        Routes.SYSTEM_HEALTH,
        Routes.LOGS,
        // more-info drill-ins (the only routes a more-info sheet ever navigates to)
        Routes.HISTORY,
        Routes.LOGBOOK,
        Routes.LOGBOOK_FOR,
        Routes.MEDIA_BROWSE,
        Routes.MEDIA_BROWSE_FOR,
        // Lightweight WebView fallbacks
        Routes.LOVELACE,
        Routes.PANEL_VIEWER,
    )

    /**
     * Dropped routes that are still registered to a placeholder screen in the legacy
     * build so any lingering affordance lands on a friendly "not in this build" stub
     * instead of crashing. No-arg routes only — see LegacyFeaturesTest. Arg-bearing
     * dropped routes (e.g. DASHBOARDS_VIEW) are reached only from another dropped
     * screen, so they're never navigated to.
     */
    val PLACEHOLDER_ROUTES: List<String> = listOf(
        // Device-mode settings
        Routes.SETTINGS_SYNC,
        Routes.SETTINGS_IOT_CAMERA,
        Routes.SETTINGS_IOT_SENSORS,
        Routes.SETTINGS_MQTT,
        // Voice / assist
        Routes.ASSIST,
        Routes.VOICE_SATELLITE,
        // Standalone feature screens
        Routes.SCENES,
        Routes.TEMPLATE,
        Routes.SERVICE_CALLER,
        Routes.NOTIFICATIONS,
        Routes.TODO,
        Routes.CAMERAS,
        Routes.WEATHER,
        Routes.PERSONS,
        Routes.CALENDARS,
        Routes.AREAS,
        Routes.LABELS,
        Routes.FLOORS,
        Routes.SERVICES,
        Routes.SEARCH,
        Routes.AUTOMATIONS,
        Routes.HELPERS,
        Routes.UPDATES,
        Routes.REPAIRS,
        Routes.BACKUPS,
        Routes.ZHA_PAIRING,
        Routes.BROADLINK,
        Routes.ENERGY,
        Routes.ZONES,
        Routes.DEVICES,
        Routes.INTEGRATIONS,
        Routes.USERS,
        Routes.TAGS,
        Routes.BLUEPRINTS,
        Routes.STATISTICS,
        Routes.DASHBOARDS,
        Routes.DASHBOARD,
    )

    /** Is [route]'s real screen retained in the legacy build? */
    fun isAvailable(route: String?): Boolean = route != null && route in KEPT_ROUTES
}
