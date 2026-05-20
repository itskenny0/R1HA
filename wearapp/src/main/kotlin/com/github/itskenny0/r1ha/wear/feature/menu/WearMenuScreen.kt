package com.github.itskenny0.r1ha.wear.feature.menu

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText

/**
 * Navigation menu screen — reachable via the "≡" chip on the card stack.
 *
 * A flat [ScalingLazyColumn] of destination chips so the user can jump to
 * any major feature without needing a phone. Swipe-from-edge dismisses back
 * to the card stack (handled by the [SwipeDismissableNavHost] container).
 */
@Composable
fun WearMenuScreen(
    onOpenFavouritesPicker: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAssist: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenHelpers: () -> Unit,
    onBack: () -> Unit,
) {
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Text(
                    text = "Menu",
                    style = MaterialTheme.typography.title3,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                )
            }

            item { MenuChip(icon = "⭐", label = "Favourites", onClick = onOpenFavouritesPicker) }
            item { MenuChip(icon = "🔍", label = "Search",      onClick = onOpenSearch) }
            item { MenuChip(icon = "🎤", label = "Assist",      onClick = onOpenAssist) }
            item { MenuChip(icon = "🎬", label = "Scenes",      onClick = onOpenScenes) }
            item { MenuChip(icon = "⚙",  label = "Automations", onClick = onOpenAutomations) }
            item { MenuChip(icon = "🔔", label = "Alerts",      onClick = onOpenNotifications) }
            item { MenuChip(icon = "📋", label = "Dashboard",   onClick = onOpenDashboard) }
            item { MenuChip(icon = "🔧", label = "Helpers",     onClick = onOpenHelpers) }
        }
    }
}

@Composable
private fun MenuChip(icon: String, label: String, onClick: () -> Unit) {
    Chip(
        label = { Text("$icon  $label") },
        onClick = onClick,
        colors = ChipDefaults.secondaryChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 2.dp),
    )
}
