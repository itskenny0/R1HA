package com.github.itskenny0.r1ha.wear.feature.favorites

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.wear.common.WearEntityChip

/**
 * Wear OS Favourites screen.
 *
 * Shows the user's pinned entities as a scrollable list of [WearEntityChip]s.
 * Tapping an entity toggles it (same as the Lovelace overview). An "Edit"
 * chip at the bottom opens the Favourites Picker where entities can be
 * added or removed.
 *
 * This is separate from the Lovelace overview (home screen) — the overview
 * reflects the user's HA dashboard tabs; this screen reflects the user's
 * personal curated list.
 */
@Composable
fun WearFavoritesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onOpenPicker: () -> Unit,
    onBack: () -> Unit,
    onOpenMediaPlayer: (EntityState) -> Unit = {},
    onOpenClimate: (EntityState) -> Unit = {},
) {
    val vm: WearFavoritesViewModel = viewModel(
        factory = WearFavoritesViewModel.factory(haRepository, settings),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(8.dp))
                        Text("Loading favourites…", style = MaterialTheme.typography.caption1)
                    }
                }
            }

            !state.hasFavorites -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) {
                        Text(
                            text = "No favourites yet",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Tap Edit to pin entities from your HA instance.",
                            style = MaterialTheme.typography.caption2,
                            color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(12.dp))
                        Chip(
                            label = { Text("✏ Edit") },
                            onClick = onOpenPicker,
                            colors = ChipDefaults.primaryChipColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
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
                            text = "FAVOURITES",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                    }

                    items(state.entities) { entity ->
                        WearEntityChip(
                            entity = entity,
                            onTap = {
                                when (entity.id.domain) {
                                    Domain.MEDIA_PLAYER -> onOpenMediaPlayer(entity)
                                    Domain.CLIMATE      -> onOpenClimate(entity)
                                    else                -> vm.onEntityTap(entity)
                                }
                            },
                        )
                    }

                    item { Spacer(Modifier.height(4.dp)) }

                    item {
                        Chip(
                            label = { Text("✏ Edit favourites") },
                            onClick = onOpenPicker,
                            colors = ChipDefaults.secondaryChipColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
