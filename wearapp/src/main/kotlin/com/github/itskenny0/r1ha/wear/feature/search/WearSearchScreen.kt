package com.github.itskenny0.r1ha.wear.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.feature.search.SearchViewModel
import com.github.itskenny0.r1ha.wear.common.WearRemoteInputChip

/**
 * Wear OS Quick Search screen.
 *
 * Type a substring in the search field to filter every HA entity by
 * name or entity_id. Tap a result to fire (scene/script/button),
 * toggle (light/switch/fan/etc.), or see the state (sensor/read-only).
 *
 * The entity list is loaded once on entry and filtered in-memory so
 * subsequent keystrokes don't hit the network.
 */
@Composable
fun WearSearchScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: SearchViewModel = viewModel(
        factory = SearchViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
    val results = vm.results
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(timeText = { TimeText() }) {
        when {
            ui.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            ui.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                    // Search chip — uses RemoteInput so there are no Samsung keyboard
                    // single-character issues. Opens the watch keyboard as a separate
                    // Activity and returns the full text in one shot.
                    item {
                        WearRemoteInputChip(
                            label = "Search entities",
                            value = ui.query,
                            placeholder = "Tap to type…",
                            inputKey = "search_query",
                            onValueChange = { vm.setQuery(it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 4.dp),
                        )
                    }

                    // Empty state
                    if (ui.query.isBlank()) {
                        item {
                            Text(
                                text = "Type to search all entities",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else if (results.isEmpty()) {
                        item {
                            Text(
                                text = "No results for \"${ui.query}\"",
                                style = MaterialTheme.typography.caption2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    } else {
                        items(results) { entity ->
                            Chip(
                                label = {
                                    Text(
                                        text = entity.friendlyName,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.caption1,
                                    )
                                },
                                secondaryLabel = {
                                    Text(
                                        text = entity.id.value,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.caption2,
                                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                    )
                                },
                                onClick = {
                                    vm.activate(entity)
                                },
                                colors = ChipDefaults.secondaryChipColors(),
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
