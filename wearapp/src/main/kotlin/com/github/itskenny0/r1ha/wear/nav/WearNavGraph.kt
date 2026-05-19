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
import com.github.itskenny0.r1ha.wear.feature.cardstack.WearCardStackScreen
import com.github.itskenny0.r1ha.wear.feature.onboarding.WearOnboardingScreen
import com.github.itskenny0.r1ha.wear.feature.scenes.WearScenesScreen
import com.github.itskenny0.r1ha.wear.feature.settings.WearSettingsScreen

/**
 * Wear OS navigation host.
 *
 * Uses [SwipeDismissableNavHost] — the standard Wear navigation container that
 * allows swipe-from-edge to go back (consistent with Wear OS system behaviour).
 *
 * Destinations are intentionally minimal: the watch is a glance-and-go device.
 * Complex screens (logbook, energy, device details, etc.) stay phone-only.
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
) {
    SwipeDismissableNavHost(
        navController = navController,
        startDestination = startDestination,
    ) {
        composable(WearRoutes.ONBOARDING) {
            WearOnboardingScreen(
                settings = settings,
                tokens = tokens,
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
                onOpenScenes = { navController.navigate(WearRoutes.SCENES) },
                onOpenSettings = { navController.navigate(WearRoutes.SETTINGS) },
            )
        }

        composable(WearRoutes.SCENES) {
            WearScenesScreen(
                haRepository = haRepository,
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
    }
}
