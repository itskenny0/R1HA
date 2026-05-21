package com.github.itskenny0.r1ha.wear.feature.helpers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
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
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.feature.helpers.HelpersViewModel

/**
 * Wear OS Helpers screen.
 *
 * Renders every HA helper entity in a [ScalingLazyColumn], adapting
 * the control surface to the helper's kind:
 *
 *  - BOOLEAN (input_boolean) → [ToggleChip]
 *  - NUMBER (input_number) / COUNTER → ─/+ chips around the current value
 *  - SELECT (input_select) → cycle chip (cycles through available options)
 *  - BUTTON (input_button) → single PRESS chip
 *  - TIMER → start / pause / cancel chips
 *  - TEXT / DATETIME → read-only display row
 */
@Composable
fun WearHelpersScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: HelpersViewModel = viewModel(
        factory = HelpersViewModel.factory(haRepository, settings),
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
                        Text(
                            text = "🔧 Helpers",
                            style = MaterialTheme.typography.title3,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                        )
                    }

                    if (ui.entries.isEmpty()) {
                        item {
                            Text(
                                text = "No helpers found.",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(ui.entries) { entry ->
                            HelperRow(entry = entry, vm = vm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HelperRow(
    entry: HelpersViewModel.Entry,
    vm: HelpersViewModel,
) {
    when (entry.kind) {
        HelpersViewModel.Kind.BOOLEAN -> {
            val isOn = entry.state == "on"
            ToggleChip(
                checked = isOn,
                onCheckedChange = { vm.toggleBoolean(entry) },
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
                        imageVector = ToggleChipDefaults.switchIcon(checked = isOn),
                        contentDescription = null,
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        HelpersViewModel.Kind.NUMBER, HelpersViewModel.Kind.COUNTER -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Decrement
                Chip(
                    label = { Text("−", style = MaterialTheme.typography.caption1) },
                    onClick = {
                        if (entry.kind == HelpersViewModel.Kind.COUNTER) {
                            vm.counterDecrement(entry)
                        } else {
                            val step = entry.step ?: 1.0
                            val cur = entry.numericValue ?: 0.0
                            val min = entry.min ?: Double.MIN_VALUE
                            vm.setNumber(entry, (cur - step).coerceAtLeast(min))
                        }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
                // Value + name
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = entry.name,
                        style = MaterialTheme.typography.caption2,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${entry.numericValue?.let {
                            if (it % 1.0 == 0.0) it.toInt().toString()
                            else "%.1f".format(it)
                        } ?: entry.state} ${entry.unit ?: ""}".trim(),
                        style = MaterialTheme.typography.caption1,
                    )
                }
                // Increment
                Chip(
                    label = { Text("+", style = MaterialTheme.typography.caption1) },
                    onClick = {
                        if (entry.kind == HelpersViewModel.Kind.COUNTER) {
                            vm.counterIncrement(entry)
                        } else {
                            val step = entry.step ?: 1.0
                            val cur = entry.numericValue ?: 0.0
                            val max = entry.max ?: Double.MAX_VALUE
                            vm.setNumber(entry, (cur + step).coerceAtMost(max))
                        }
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                )
            }
        }

        HelpersViewModel.Kind.SELECT -> {
            val options = entry.options
            val currentIdx = options.indexOf(entry.state).coerceAtLeast(0)
            val nextOption = if (options.isEmpty()) null
            else options[(currentIdx + 1) % options.size]
            Chip(
                label = {
                    androidx.compose.foundation.layout.Column {
                        Text(
                            text = entry.name,
                            style = MaterialTheme.typography.caption2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "▶ ${entry.state}",
                            style = MaterialTheme.typography.caption1,
                        )
                    }
                },
                onClick = { nextOption?.let { vm.selectOption(entry, it) } },
                enabled = nextOption != null,
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        HelpersViewModel.Kind.BUTTON -> {
            Chip(
                label = { Text("${entry.name} — PRESS", style = MaterialTheme.typography.caption1) },
                onClick = { vm.pressButton(entry) },
                colors = ChipDefaults.primaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }

        HelpersViewModel.Kind.TIMER -> {
            val isActive = entry.state == "active"
            val isPaused = entry.state == "paused"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "⏱ ${entry.name}",
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!isActive) {
                    Chip(
                        label = { Text("▶", style = MaterialTheme.typography.caption2) },
                        onClick = { vm.timerService(entry, "start") },
                        colors = ChipDefaults.primaryChipColors(),
                    )
                }
                if (isActive) {
                    Chip(
                        label = { Text("⏸", style = MaterialTheme.typography.caption2) },
                        onClick = { vm.timerService(entry, "pause") },
                        colors = ChipDefaults.secondaryChipColors(),
                    )
                }
                if (isActive || isPaused) {
                    Chip(
                        label = { Text("✕", style = MaterialTheme.typography.caption2) },
                        onClick = { vm.timerService(entry, "cancel") },
                        colors = ChipDefaults.outlinedChipColors(
                            contentColor = MaterialTheme.colors.error,
                        ),
                    )
                }
            }
        }

        else -> {
            // TEXT, DATETIME, UNKNOWN — read-only
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.caption2,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.state,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                )
            }
        }
    }
}
