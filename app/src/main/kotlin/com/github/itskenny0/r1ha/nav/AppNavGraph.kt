package com.github.itskenny0.r1ha.nav

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import kotlinx.coroutines.flow.first
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.feature.about.AboutScreen
import com.github.itskenny0.r1ha.feature.cardstack.CardStackScreen
import com.github.itskenny0.r1ha.feature.favoritespicker.FavoritesPickerScreen
import com.github.itskenny0.r1ha.feature.onboarding.OnboardingScreen
import com.github.itskenny0.r1ha.feature.settings.SettingsScreen
import com.github.itskenny0.r1ha.feature.themepicker.ThemePickerScreen

@Composable
fun AppNavGraph(
    navController: NavHostController,
    startDestination: String,
    haRepository: HaRepository,
    settings: SettingsRepository,
    tokens: TokenStore,
    wheelInput: WheelInput,
    /**
     * Lovelace-overrides DataStore for the native dashboards feature.
     * Defaulted so external entry points (tests, alternative hosts) can
     * skip the dashboards screens entirely without having to wire up a
     * fresh store. The Settings entry row only shows on non-R1 width
     * tiers, so callers that don't construct one will never reach the
     * route that needs it.
     */
    overrideStore: com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore? = null,
) {
    // App-shortcut deep-link consumer — MainActivity emits a route on
    // ShortcutBus whenever a launcher long-press shortcut delivers an
    // intent. We collect once per NavController lifetime and route via
    // navController.navigate(), so the requested screen is pushed on
    // top of whatever the user had open (back-press returns to where
    // they were, exactly like any other in-app nav).
    androidx.compose.runtime.LaunchedEffect(navController) {
        com.github.itskenny0.r1ha.core.util.ShortcutBus.requests.collect { route ->
            val target = when (route) {
                "search" -> Routes.SEARCH
                "assist" -> Routes.ASSIST
                "dashboard" -> Routes.DASHBOARD
                "automations" -> Routes.AUTOMATIONS
                "helpers" -> Routes.HELPERS
                "energy" -> Routes.ENERGY
                "zones" -> Routes.ZONES
                "scenes" -> Routes.SCENES
                "notifications" -> Routes.NOTIFICATIONS
                "cameras" -> Routes.CAMERAS
                "logbook" -> Routes.LOGBOOK
                else -> null
            }
            if (target != null) {
                navController.navigate(target) { launchSingleTop = true }
            }
        }
    }
    NavHost(navController = navController, startDestination = startDestination) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(
                settings = settings,
                tokens = tokens,
                onComplete = {
                    navController.navigate(Routes.CARD_STACK) {
                        popUpTo(Routes.ONBOARDING) { inclusive = true }
                    }
                },
                // Skip-OAuth escape hatch — surfaces a 'Use long-lived
                // token instead' link in the URL form so kiosk users
                // never need to OAuth in just to reach the LLAT setup.
                onOpenLongLivedToken = {
                    navController.navigate(Routes.LONG_LIVED_TOKEN) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.CARD_STACK) {
            CardStackScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                // launchSingleTop = true on every push so a rapid double-tap on the gear or a
                // double-fire of the swipe gesture can't stack two copies of the same screen
                // on the back stack (which would otherwise need two back-presses to escape).
                onOpenFavoritesPicker = {
                    // Guard against duplicate / mid-transition navigation. Rapid taps on
                    // the hamburger (or a tap that lands while a pager swipe is still
                    // animating) could otherwise fire the navigate twice; launchSingleTop
                    // alone has historically not been enough to prevent a second nav from
                    // racing through while the back-stack entry for the first is still
                    // being created. Restricting to the CARD_STACK route makes it a no-op
                    // unless we're actually still on the deck.
                    if (navController.currentDestination?.route == Routes.CARD_STACK) {
                        com.github.itskenny0.r1ha.core.util.R1Log.i(
                            "Nav.openFavoritesPicker",
                            "navigating to FAVORITES_PICKER",
                        )
                        navController.navigate(Routes.FAVORITES_PICKER) { launchSingleTop = true }
                    } else {
                        com.github.itskenny0.r1ha.core.util.R1Log.w(
                            "Nav.openFavoritesPicker",
                            "skipping navigate; currentDestination=${navController.currentDestination?.route}",
                        )
                    }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onOpenDashboard = {
                    navController.navigate(Routes.DASHBOARD) { launchSingleTop = true }
                },
                onOpenSearch = {
                    navController.navigate(Routes.SEARCH) { launchSingleTop = true }
                },
                onOpenAssist = {
                    navController.navigate(Routes.ASSIST) { launchSingleTop = true }
                },
                onOpenAutomations = {
                    navController.navigate(Routes.AUTOMATIONS) { launchSingleTop = true }
                },
                onOpenEnergy = {
                    navController.navigate(Routes.ENERGY) { launchSingleTop = true }
                },
                onOpenScenes = {
                    navController.navigate(Routes.SCENES) { launchSingleTop = true }
                },
                onOpenNotifications = {
                    navController.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                },
                onOpenZones = {
                    navController.navigate(Routes.ZONES) { launchSingleTop = true }
                },
                onOpenDevice = {
                    navController.navigate(Routes.DEVICE) { launchSingleTop = true }
                },
                onOpenCameras = {
                    navController.navigate(Routes.CAMERAS) { launchSingleTop = true }
                },
                onOpenMediaBrowse = {
                    navController.navigate(Routes.MEDIA_BROWSE) { launchSingleTop = true }
                },
                onOpenWeather = {
                    navController.navigate(Routes.WEATHER) { launchSingleTop = true }
                },
                onOpenPersons = {
                    navController.navigate(Routes.PERSONS) { launchSingleTop = true }
                },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
                onOpenDashboardRoute = { route ->
                    navController.navigate(route) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.FAVORITES_PICKER) {
            FavoritesPickerScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS) {
            SettingsRouteContent(
                navController = navController,
                settings = settings,
                tokens = tokens,
                haRepository = haRepository,
                wheelInput = wheelInput,
            )
        }
        composable(Routes.SETTINGS_KEY_BINDINGS) {
            com.github.itskenny0.r1ha.feature.settings.KeyBindingsScreen(
                settings = settings,
                tokens = tokens,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_SYNC) {
            com.github.itskenny0.r1ha.feature.settings.SyncSettingsScreen(
                settings = settings,
                tokens = tokens,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_IOT_CAMERA) {
            com.github.itskenny0.r1ha.feature.settings.IotCameraSettingsScreen(
                settings = settings,
                tokens = tokens,
                onOpenMqttSettings = {
                    navController.navigate(Routes.SETTINGS_MQTT) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_MQTT) {
            com.github.itskenny0.r1ha.feature.settings.MqttSettingsScreen(
                settings = settings,
                tokens = tokens,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SETTINGS_IOT_SENSORS) {
            com.github.itskenny0.r1ha.feature.settings.IotSensorsSettingsScreen(
                settings = settings,
                tokens = tokens,
                onOpenMqttSettings = {
                    navController.navigate(Routes.SETTINGS_MQTT) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.THEME_PICKER) {
            ThemePickerScreen(
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MODIFIED_SETTINGS) {
            com.github.itskenny0.r1ha.feature.settings.ModifiedSettingsScreen(
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ABOUT) {
            AboutScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onOpenDevMenu = {
                    navController.navigate(Routes.DEV_MENU) { launchSingleTop = true }
                },
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEV_MENU) {
            com.github.itskenny0.r1ha.feature.devmenu.DevMenuScreen(
                settings = settings,
                tokens = tokens,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                haRepository = haRepository,
            )
        }
        composable(Routes.ASSIST) {
            com.github.itskenny0.r1ha.feature.assist.AssistScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenVoiceSatellite = {
                    navController.navigate(Routes.VOICE_SATELLITE) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.VOICE_SATELLITE) {
            com.github.itskenny0.r1ha.feature.voicesat.VoiceSatelliteScreen(
                haRepository = haRepository,
                settings = settings,
                tokens = tokens,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SCENES) {
            com.github.itskenny0.r1ha.feature.scenes.ScenesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGBOOK) {
            com.github.itskenny0.r1ha.feature.logbook.LogbookScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.TEMPLATE) {
            com.github.itskenny0.r1ha.feature.template.TemplateScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SERVICE_CALLER) {
            com.github.itskenny0.r1ha.feature.service.ServiceCallerScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.NOTIFICATIONS) {
            com.github.itskenny0.r1ha.feature.notifications.NotificationsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TODO) {
            com.github.itskenny0.r1ha.feature.todo.ToDoScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.CAMERAS) {
            com.github.itskenny0.r1ha.feature.cameras.CamerasScreen(
                haRepository = haRepository,
                settings = settings,
                tokens = tokens,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.WEATHER) {
            com.github.itskenny0.r1ha.feature.weather.WeatherScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.PERSONS) {
            com.github.itskenny0.r1ha.feature.persons.PersonsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.CALENDARS) {
            com.github.itskenny0.r1ha.feature.calendars.CalendarsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LONG_LIVED_TOKEN) {
            com.github.itskenny0.r1ha.feature.longlived.LongLivedTokenScreen(
                settings = settings,
                tokens = tokens,
                haRepository = haRepository,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SYSTEM_HEALTH) {
            com.github.itskenny0.r1ha.feature.systemhealth.SystemHealthScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenFullLog = {
                    navController.navigate(Routes.LOGS) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.AREAS) {
            com.github.itskenny0.r1ha.feature.areas.AreasScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LABELS) {
            com.github.itskenny0.r1ha.feature.labels.LabelsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.FLOORS) {
            com.github.itskenny0.r1ha.feature.floors.FloorsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SERVICES) {
            com.github.itskenny0.r1ha.feature.services.ServicesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.SEARCH) {
            com.github.itskenny0.r1ha.feature.search.SearchScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
                onOpenAssist = {
                    navController.navigate(Routes.ASSIST) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.AUTOMATIONS) {
            com.github.itskenny0.r1ha.feature.automations.AutomationsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.HELPERS) {
            com.github.itskenny0.r1ha.feature.helpers.HelpersScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.UPDATES) {
            com.github.itskenny0.r1ha.feature.updates.UpdatesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.REPAIRS) {
            com.github.itskenny0.r1ha.feature.repairs.RepairsScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.MEDIA_BROWSE) {
            com.github.itskenny0.r1ha.feature.mediabrowse.MediaBrowseScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BACKUPS) {
            com.github.itskenny0.r1ha.feature.backups.BackupsScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ZHA_PAIRING) {
            com.github.itskenny0.r1ha.feature.zha.ZhaPairingScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.ENERGY) {
            com.github.itskenny0.r1ha.feature.energy.EnergyScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
                onOpenHistory = { eid ->
                    navController.navigate(Routes.historyRoute(eid)) { launchSingleTop = true }
                },
            )
        }
        composable(Routes.ZONES) {
            com.github.itskenny0.r1ha.feature.zones.ZonesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOVELACE) {
            com.github.itskenny0.r1ha.feature.lovelace.LovelaceScreen(
                settings = settings,
                tokens = tokens,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEVICE) {
            com.github.itskenny0.r1ha.feature.device.DeviceScreen(
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(
            route = Routes.HISTORY,
            arguments = listOf(
                androidx.navigation.navArgument("entityId") {
                    type = androidx.navigation.NavType.StringType
                },
            ),
        ) { backStackEntry ->
            // Pull the entity_id out of the nav-arg bundle. Compose
            // Navigation stores StringType args under the same key the
            // route template declares; null only happens if the route
            // is malformed which the navArgument schema makes
            // impossible in practice.
            val eid = backStackEntry.arguments?.getString("entityId").orEmpty()
            com.github.itskenny0.r1ha.feature.history.HistoryScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                entityId = eid,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DEVICES) {
            com.github.itskenny0.r1ha.feature.devices.DevicesScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.INTEGRATIONS) {
            com.github.itskenny0.r1ha.feature.integrations.IntegrationsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.LOGS) {
            com.github.itskenny0.r1ha.feature.logs.LogsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.USERS) {
            com.github.itskenny0.r1ha.feature.users.UsersScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.TAGS) {
            com.github.itskenny0.r1ha.feature.tags.TagsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.BLUEPRINTS) {
            com.github.itskenny0.r1ha.feature.blueprints.BlueprintsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.STATISTICS) {
            com.github.itskenny0.r1ha.feature.statistics.StatisticsScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onBack = { navController.popBackStack() },
            )
        }
        composable(Routes.DASHBOARDS) {
            // Defaulted overrideStore is non-null in the production
            // wiring (MainActivity → AppGraph.lovelaceOverrideStore);
            // a missing store falls back to an in-memory throwaway so
            // tests / alternative hosts still render the list.
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val store = overrideStore ?: androidx.compose.runtime.remember(ctx) {
                // Best-effort fallback for callers that didn't wire an
                // overrideStore in via the AppNavGraph arg. The store
                // backs onto the same DataStore file as the production
                // wiring (preferencesDataStore is a singleton per name),
                // so edits actually do persist; this branch just spares
                // us a hard crash when the host forgot to pass one.
                com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore(ctx)
            }
            com.github.itskenny0.r1ha.feature.dashboards.DashboardsListScreen(
                haRepository = haRepository,
                overrideStore = store,
                onOpenView = { dashboardUrlPath, viewPath ->
                    navController.navigate(Routes.dashboardsViewRoute(dashboardUrlPath, viewPath)) {
                        launchSingleTop = true
                    }
                },
                onBack = { navController.popBackStack() },
                settings = settings,
            )
        }
        composable(
            route = Routes.DASHBOARDS_VIEW,
            arguments = listOf(
                androidx.navigation.navArgument("dashboard") { type = androidx.navigation.NavType.StringType },
                androidx.navigation.navArgument("viewPath") { type = androidx.navigation.NavType.StringType },
            ),
        ) { backStackEntry ->
            val ctx = androidx.compose.ui.platform.LocalContext.current
            val store = overrideStore ?: androidx.compose.runtime.remember(ctx) {
                com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore(ctx)
            }
            val dashRaw = backStackEntry.arguments?.getString("dashboard").orEmpty()
            val viewPath = backStackEntry.arguments?.getString("viewPath").orEmpty()
            // Decode the "_default_" sentinel back into null so the
            // repository call hits HA's default-dashboard path.
            val dashboardUrlPath = dashRaw.takeUnless { it == "_default_" }
            // Server base URL so picture/area card relative entity_picture
            // paths resolve (the bearer token is already provided globally
            // by MainActivity). Same source cameras/history use.
            val dashServerUrl by androidx.compose.runtime.produceState<String?>(null, settings) {
                value = settings.settings.first().server?.url
            }
            com.github.itskenny0.r1ha.feature.dashboards.DashboardViewScreen(
                haRepository = haRepository,
                overrideStore = store,
                dashboardUrlPath = dashboardUrlPath,
                viewPath = viewPath,
                serverUrl = dashServerUrl,
                settings = settings,
                onBack = { navController.popBackStack() },
                onOpenLovelace = {
                    navController.navigate(Routes.LOVELACE) { launchSingleTop = true }
                },
                onOpenView = { navPath ->
                    // A `navigate` tap_action's navigation_path. HA paths look
                    // like "/lovelace/lights" or "/my-dash/lights"; the last
                    // segment is the target view's path within the dashboard the
                    // path names (or the current one when it's a bare segment).
                    // Anything that doesn't resolve to a same-dashboard view path
                    // falls back to the full Lovelace WebView so the tap is never
                    // a dead end.
                    val trimmed = navPath.trim().trim('/')
                    if (trimmed.isEmpty()) {
                        navController.navigate(Routes.LOVELACE) { launchSingleTop = true }
                    } else {
                        val segments = trimmed.split('/')
                        val targetView = segments.last()
                        // "/lovelace/<view>" → current dashboard; "/<dash>/<view>"
                        // → that dashboard; bare "<view>" → current dashboard.
                        val targetDash = when {
                            segments.size >= 2 && segments[0] != "lovelace" -> segments[0]
                            else -> dashboardUrlPath
                        }
                        navController.navigate(
                            Routes.dashboardsViewRoute(targetDash, targetView),
                        ) { launchSingleTop = true }
                    }
                },
            )
        }
        composable(Routes.DASHBOARD) { backStackEntry ->
            // canGoBack — true when Dashboard was reached via nav, false
            // when it's the start destination. previousBackStackEntry is
            // null in the latter case. The Dashboard top bar uses this
            // to hide the inert chevron-back.
            val canGoBack = navController.previousBackStackEntry != null
            com.github.itskenny0.r1ha.feature.dashboard.DashboardScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                canGoBack = canGoBack,
                onBack = { navController.popBackStack() },
                onOpenWeather = {
                    navController.navigate(Routes.WEATHER) { launchSingleTop = true }
                },
                onOpenPersons = {
                    navController.navigate(Routes.PERSONS) { launchSingleTop = true }
                },
                onOpenCalendars = {
                    navController.navigate(Routes.CALENDARS) { launchSingleTop = true }
                },
                onOpenCameras = {
                    navController.navigate(Routes.CAMERAS) { launchSingleTop = true }
                },
                onOpenNotifications = {
                    navController.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true }
                },
                onOpenScenes = {
                    navController.navigate(Routes.SCENES) { launchSingleTop = true }
                },
                onOpenEnergy = {
                    navController.navigate(Routes.ENERGY) { launchSingleTop = true }
                },
                onOpenDevice = {
                    navController.navigate(Routes.DEVICE) { launchSingleTop = true }
                },
                onOpenCardStack = {
                    // Kiosk-mode escape hatch — Dashboard is the start
                    // destination so there's nothing to pop back to.
                    // launchSingleTop keeps a rapid double-tap from
                    // stacking copies.
                    navController.navigate(Routes.CARD_STACK) { launchSingleTop = true }
                },
                onOpenSettings = {
                    navController.navigate(Routes.SETTINGS) { launchSingleTop = true }
                },
                onOpenAssist = {
                    navController.navigate(Routes.ASSIST) { launchSingleTop = true }
                },
                onOpenHistory = { entityId ->
                    navController.navigate(Routes.historyRoute(entityId)) { launchSingleTop = true }
                },
            )
        }
    }
}

/**
 * Settings screen invocation for the single [Routes.SETTINGS] entry. Wires the
 * 28-ish onOpenXXX callbacks. The Settings surface owns its own internal drill-in
 * back-stack, so config-category navigation never leaves this route; `onOpenCategory`
 * is invoked only for the standalone feature screens that are their own nav routes
 * (Sync, IoT Camera / Sensors, MQTT).
 */
@Composable
private fun SettingsRouteContent(
    navController: NavHostController,
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: HaRepository,
    wheelInput: WheelInput,
) {
    com.github.itskenny0.r1ha.feature.settings.SettingsScreen(
        settings = settings,
        tokens = tokens,
        haRepository = haRepository,
        wheelInput = wheelInput,
        onOpenCategory = { target ->
            val route = when (target) {
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.SYNC -> Routes.SETTINGS_SYNC
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.IOT_CAMERA -> Routes.SETTINGS_IOT_CAMERA
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.IOT_SENSORS -> Routes.SETTINGS_IOT_SENSORS
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.MQTT -> Routes.SETTINGS_MQTT
                // Config categories drill in internally; they never reach here.
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.ROOT,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.CONNECTION,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.APPEARANCE,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.BEHAVIOUR,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.INTEGRATIONS,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.ADVANCED,
                com.github.itskenny0.r1ha.feature.settings.SettingsCategory.BROWSE,
                -> null
            }
            if (route != null) navController.navigate(route) { launchSingleTop = true }
        },
        onOpenThemePicker = { navController.navigate(Routes.THEME_PICKER) { launchSingleTop = true } },
        onOpenAbout = { navController.navigate(Routes.ABOUT) { launchSingleTop = true } },
        onOpenDevMenu = { navController.navigate(Routes.DEV_MENU) { launchSingleTop = true } },
        onOpenAssist = { navController.navigate(Routes.ASSIST) { launchSingleTop = true } },
        onOpenScenes = { navController.navigate(Routes.SCENES) { launchSingleTop = true } },
        onOpenLogbook = { navController.navigate(Routes.LOGBOOK) { launchSingleTop = true } },
        onOpenTemplate = { navController.navigate(Routes.TEMPLATE) { launchSingleTop = true } },
        onOpenServiceCaller = { navController.navigate(Routes.SERVICE_CALLER) { launchSingleTop = true } },
        onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) { launchSingleTop = true } },
        onOpenCameras = { navController.navigate(Routes.CAMERAS) { launchSingleTop = true } },
        onOpenWeather = { navController.navigate(Routes.WEATHER) { launchSingleTop = true } },
        onOpenPersons = { navController.navigate(Routes.PERSONS) { launchSingleTop = true } },
        onOpenCalendars = { navController.navigate(Routes.CALENDARS) { launchSingleTop = true } },
        onOpenLongLivedToken = { navController.navigate(Routes.LONG_LIVED_TOKEN) { launchSingleTop = true } },
        onOpenSystemHealth = { navController.navigate(Routes.SYSTEM_HEALTH) { launchSingleTop = true } },
        onOpenDashboard = { navController.navigate(Routes.DASHBOARD) { launchSingleTop = true } },
        onOpenAreas = { navController.navigate(Routes.AREAS) { launchSingleTop = true } },
        onOpenLabels = { navController.navigate(Routes.LABELS) { launchSingleTop = true } },
        onOpenFloors = { navController.navigate(Routes.FLOORS) { launchSingleTop = true } },
        onOpenServices = { navController.navigate(Routes.SERVICES) { launchSingleTop = true } },
        onOpenSearch = { navController.navigate(Routes.SEARCH) { launchSingleTop = true } },
        onOpenAutomations = { navController.navigate(Routes.AUTOMATIONS) { launchSingleTop = true } },
        onOpenHelpers = { navController.navigate(Routes.HELPERS) { launchSingleTop = true } },
        onOpenTodo = { navController.navigate(Routes.TODO) { launchSingleTop = true } },
        onOpenUpdates = { navController.navigate(Routes.UPDATES) { launchSingleTop = true } },
        onOpenRepairs = { navController.navigate(Routes.REPAIRS) { launchSingleTop = true } },
        onOpenMediaBrowse = { navController.navigate(Routes.MEDIA_BROWSE) { launchSingleTop = true } },
        onOpenBackups = { navController.navigate(Routes.BACKUPS) { launchSingleTop = true } },
        onOpenZhaPairing = { navController.navigate(Routes.ZHA_PAIRING) { launchSingleTop = true } },
        onOpenEnergy = { navController.navigate(Routes.ENERGY) { launchSingleTop = true } },
        onOpenZones = { navController.navigate(Routes.ZONES) { launchSingleTop = true } },
        onOpenLovelace = { navController.navigate(Routes.LOVELACE) { launchSingleTop = true } },
        onOpenDevice = { navController.navigate(Routes.DEVICE) { launchSingleTop = true } },
        onOpenModifiedSettings = { navController.navigate(Routes.MODIFIED_SETTINGS) { launchSingleTop = true } },
        onOpenKeyBindings = { navController.navigate(Routes.SETTINGS_KEY_BINDINGS) { launchSingleTop = true } },
        onOpenDevices = { navController.navigate(Routes.DEVICES) { launchSingleTop = true } },
        onOpenIntegrations = { navController.navigate(Routes.INTEGRATIONS) { launchSingleTop = true } },
        onOpenLogs = { navController.navigate(Routes.LOGS) { launchSingleTop = true } },
        onOpenUsers = { navController.navigate(Routes.USERS) { launchSingleTop = true } },
        onOpenTags = { navController.navigate(Routes.TAGS) { launchSingleTop = true } },
        onOpenBlueprints = { navController.navigate(Routes.BLUEPRINTS) { launchSingleTop = true } },
        onOpenStatistics = { navController.navigate(Routes.STATISTICS) { launchSingleTop = true } },
        onOpenDashboards = { navController.navigate(Routes.DASHBOARDS) { launchSingleTop = true } },
        onSignedOut = {
            navController.navigate(Routes.ONBOARDING) {
                popUpTo(0) { inclusive = true }
            }
        },
        onBack = { navController.popBackStack() },
    )
}
