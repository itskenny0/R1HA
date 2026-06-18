package com.github.itskenny0.r1ha.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Stub destination for routes that the slim "legacy" build (R1HAL) drops. The dropped
 * feature screens are dead-code-eliminated from that build, so any lingering affordance
 * that still tries to navigate to one lands here instead of crashing the NavHost. In a
 * full build this composable is never registered (the IS_LEGACY branch in AppNavGraph
 * is dead) and R8 strips it.
 *
 * The sidebar already filters dropped surfaces out (see PinnableSurfaces /
 * LegacyFeatures), so in practice this is a safety net for stray Settings-menu rows and
 * deep links rather than something users routinely hit.
 */
@Composable
fun LegacyUnavailableScreen(route: String, onBack: () -> Unit) {
    // Ship a WARN naming the [route] that landed here: every hit means a UI
    // affordance (a settings row, a drawer item, a sheet tile) still navigates to
    // a dropped feature and should be gated. The route is the exact key to look up
    // in LegacyFeatures, so the shipped logs pinpoint each remaining gap instead of
    // needing a screenshot. Once per navigation (keyed on route), not per recompose.
    androidx.compose.runtime.LaunchedEffect(route) {
        com.github.itskenny0.r1ha.core.util.R1Log.w(
            "LegacyGap",
            "Not-in-R1HAL placeholder shown for route='$route' — a UI entry still " +
                "points at this dropped feature; gate that affordance.",
        )
    }
    BackHandler(onBack = onBack)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding(),
    ) {
        Column(Modifier.fillMaxSize()) {
            R1TopBar(title = "Not in R1HAL", onBack = onBack)
            R1EmptyState(
                title = "Not in this build",
                body = "R1HAL is the slim build. This feature lives in the full R1HA app; " +
                    "install it alongside R1HAL to use it.",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(R1.space.l),
            )
        }
    }
}
