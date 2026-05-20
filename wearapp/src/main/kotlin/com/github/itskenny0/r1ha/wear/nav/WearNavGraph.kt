package com.github.itskenny0.r1ha.wear.nav

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import okhttp3.OkHttpClient
import com.github.itskenny0.r1ha.wear.feature.assist.WearAssistScreen
import com.github.itskenny0.r1ha.wear.feature.automations.WearAutomationsScreen
import com.github.itskenny0.r1ha.wear.feature.cardstack.WearCardStackScreen
import com.github.itskenny0.r1ha.wear.feature.dashboard.WearDashboardScreen
import com.github.itskenny0.r1ha.wear.feature.favoritespicker.WearFavoritesPickerScreen
import com.github.itskenny0.r1ha.wear.feature.helpers.WearHelpersScreen
import com.github.itskenny0.r1ha.wear.feature.menu.WearMenuScreen
import com.github.itskenny0.r1ha.wear.feature.notifications.WearNotificationsScreen
import com.github.itskenny0.r1ha.wear.feature.onboarding.WearOnboardingScreen
import com.github.itskenny0.r1ha.wear.feature.remote.WearRemoteScreen
import com.github.itskenny0.r1ha.wear.feature.scenes.WearScenesScreen
import com.github.itskenny0.r1ha.wear.feature.search.WearSearchScreen
import com.github.itskenny0.r1ha.wear.feature.settings.WearSettingsScreen

/**
 * Wear OS navigation host.
 *
 * Uses [SwipeDismissableNavHost] — swipe-from-edge goes back, consistent
 * with Wear OS system behaviour.
 */
@Composable
fun WearNavGraph(
    navController: NavHostController,
    startDestination: String,
    haRepository: HaRepository,
    settings: SettingsRepository,
    tokens: TokenStore,
    wheelInput: WheelInput,
    currentSettings: AppSettings,
    http: OkHttpClient,
) {
    fun nav(route: String) = navController.navigate(route) { launchSingleTop = true }

    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(WearRoutes.ONBOARDING) {
            WearOnboardingScreen(
                settings = settings,
                tokens = tokens,
                http = http,
                onConnected = {
                    navController.navigate(WearRoutes.CARD_STACK) {
                        popUpTo(WearRoutes.ONBOARDING) { inclusive = true }
                    }
                },
            )
        }

        composable(WearRoutes.CARD_STACK) {
            WearCardStackScreen(
                haRepository = haRepository,
                settings = settings,
                wheelInput = wheelInput,
                onOpenMenu = { nav(WearRoutes.MENU) },
                onOpenSettings = { nav(WearRoutes.SETTINGS) },
                onOpenFavoritesPicker = { nav(WearRoutes.FAVORITES_PICKER) },
                onOpenRemote = { nav(WearRoutes.REMOTE) },
            )
        }

        composable(WearRoutes.MENU) {
            WearMenuScreen(
                onOpenFavouritesPicker = { nav(WearRoutes.FAVORITES_PICKER) },
                onOpenSearch = { nav(WearRoutes.SEARCH) },
                onOpenAssist = { nav(WearRoutes.ASSIST) },
                onOpenScenes = { nav(WearRoutes.SCENES) },
                onOpenAutomations = { nav(WearRoutes.AUTOMATIONS) },
                onOpenNotifications = { nav(WearRoutes.NOTIFICATIONS) },
                onOpenDashboard = { nav(WearRoutes.DASHBOARD) },
                onOpenHelpers = { nav(WearRoutes.HELPERS) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.FAVORITES_PICKER) {
            WearFavoritesPickerScreen(
                haRepository = haRepository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.SCENES) {
            WearScenesScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.ASSIST) {
            WearAssistScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.AUTOMATIONS) {
            WearAutomationsScreen(
                haRepository = haRepository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.NOTIFICATIONS) {
            WearNotificationsScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.DASHBOARD) {
            WearDashboardScreen(
                haRepository = haRepository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.HELPERS) {
            WearHelpersScreen(
                haRepository = haRepository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.SEARCH) {
            WearSearchScreen(
                haRepository = haRepository,
                settings = settings,
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.SETTINGS) {
            WearSettingsScreen(
                settings = settings,
                tokens = tokens,
                onDisconnect = {
                    navController.navigate(WearRoutes.ONBOARDING) {
                        popUpTo(0) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(WearRoutes.REMOTE) {
            WearRemoteScreen(
                haRepository = haRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

