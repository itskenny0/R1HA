package com.github.itskenny0.r1ha.wear.feature.automations

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
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
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.feature.automations.AutomationsViewModel

/**
 * Wear OS Automations screen.
 *
 * Lists every `automation.*` entity with its enabled state and a RUN
 * button. Toggle chip enables/disables the automation; RUN button fires
 * it immediately (with skip_condition = true). A RELOAD chip at the top
 * re-reads automations.yaml on HA's side.
 */
@Composable
fun WearAutomationsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: AutomationsViewModel = viewModel(
        factory = AutomationsViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
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
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "⚙ Automations",
                                style = MaterialTheme.typography.title3,
                            )
                            Chip(
                                label = { Text("RELOAD", style = MaterialTheme.typography.caption2) },
                                onClick = { vm.reload() },
                                enabled = !ui.reloading,
                                colors = ChipDefaults.outlinedChipColors(),
                                modifier = Modifier.padding(start = 4.dp),
                            )
                        }
                    }

                    if (ui.entries.isEmpty()) {
                        item {
                            Text(
                                text = "No automations found.",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(ui.entries) { entry ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                // Toggle = enable/disable
                                ToggleChip(
                                    checked = entry.enabled,
                                    onCheckedChange = { checked -> vm.setEnabled(entry, checked) },
                                    label = {
                                        Text(
                                            text = entry.name,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                            style = MaterialTheme.typography.caption1,
                                        )
                                    },
                                    toggleControl = {
                                        Icon(
                                            imageVector = ToggleChipDefaults.switchIcon(checked = entry.enabled),
                                            contentDescription = null,
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                )
                                // RUN button
                                Chip(
                                    label = { Text("▶", style = MaterialTheme.typography.caption2) },
                                    onClick = { vm.trigger(entry) },
                                    colors = ChipDefaults.primaryChipColors(),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
