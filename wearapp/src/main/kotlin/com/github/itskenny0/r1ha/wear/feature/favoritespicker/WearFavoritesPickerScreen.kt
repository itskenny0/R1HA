package com.github.itskenny0.r1ha.wear.feature.favoritespicker

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.feature.favoritespicker.FavoritesPickerViewModel

/**
 * Wear OS favourites picker.
 *
 * Shows every HA entity as a [ToggleChip]; checked = in the current page's
 * favourites list. The user toggles entries on/off to build their card-stack
 * deck without needing the phone app. Entities are grouped and ordered the
 * same way as the phone picker — toggling writes through to
 * [SettingsRepository] immediately so the card stack picks it up live.
 */
@Composable
fun WearFavoritesPickerScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: FavoritesPickerViewModel = viewModel(
        factory = FavoritesPickerViewModel.factory(repo = haRepository, settings = settings),
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        when {
            ui.loading -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            ui.error != null -> {
                androidx.compose.foundation.layout.Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Error: ${ui.error}",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            else -> {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Text(
                            text = "Favourites",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                    }

                    if (ui.rows.isEmpty()) {
                        item {
                            Text(
                                text = "No entities found.\nCheck your HA connection.",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                    } else {
                        items(ui.rows) { row ->
                            ToggleChip(
                                checked = row.isFavorite,
                                onCheckedChange = { vm.toggle(row.state.id.value) },
                                label = {
                                    Text(
                                        text = row.displayName,
                                        maxLines = 2,
                                        style = MaterialTheme.typography.body2,
                                    )
                                },
                                secondaryLabel = {
                                    Text(
                                        text = row.state.id.domain.name.lowercase()
                                            .replace('_', ' '),
                                        style = MaterialTheme.typography.caption2,
                                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                    )
                                },
                                toggleControl = {
                                    Icon(
                                        imageVector = ToggleChipDefaults.switchIcon(
                                            checked = row.isFavorite,
                                        ),
                                        contentDescription = null,
                                    )
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 4.dp, vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
