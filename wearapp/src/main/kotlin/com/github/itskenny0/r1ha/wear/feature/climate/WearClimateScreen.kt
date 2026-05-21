package com.github.itskenny0.r1ha.wear.feature.climate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.wear.theme.WearColors

/**
 * Full-featured Wear OS screen for a `climate` entity.
 *
 * Layout (scrollable):
 * 1. Entity name + HVAC mode label
 * 2. Current ambient temperature
 * 3. Target temperature with − / + buttons
 * 4. HVAC mode picker chips
 * 5. Power off chip
 */
@Composable
fun WearClimateScreen(
    haRepository: HaRepository,
    entityId: EntityId,
    onBack: () -> Unit,
) {
    val vm: WearClimateViewModel = viewModel(
        key = entityId.value,
        factory = WearClimateViewModel.factory(haRepository, entityId),
    )
    val state by vm.uiState.collectAsStateWithLifecycle()
    val listState = rememberScalingLazyListState()

    Scaffold(timeText = { TimeText() }) {
        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            state.entity == null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Entity not found", style = MaterialTheme.typography.caption1)
                }
            }

            else -> {
                val entity = state.entity!!
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // ── Header ───────────────────────────────────────────────
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        ) {
                            Text(
                                text = entity.friendlyName,
                                style = MaterialTheme.typography.title3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = WearColors.Primary,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = hvacModeLabel(entity.rawState),
                                style = MaterialTheme.typography.caption2,
                                color = hvacModeColor(entity.rawState),
                            )
                        }
                    }

                    // ── Current ambient temperature ──────────────────────────
                    if (state.currentTemp != null) {
                        item {
                            Text(
                                text = "Now: %.1f%s".format(
                                    state.currentTemp,
                                    entity.unit ?: "°",
                                ),
                                style = MaterialTheme.typography.caption1,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.7f),
                            )
                        }
                    }

                    // ── Target temperature +/− ───────────────────────────────
                    if (state.targetTemp != null) {
                        item {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.padding(vertical = 4.dp),
                            ) {
                                Text(
                                    text = "SET",
                                    style = MaterialTheme.typography.caption2,
                                    color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    Button(
                                        onClick = { vm.onAdjustTemp(-1.0) },
                                        colors = ButtonDefaults.secondaryButtonColors(),
                                        modifier = Modifier.size(40.dp),
                                    ) { Text("−", fontSize = 20.sp) }

                                    Text(
                                        text = "%.1f%s".format(
                                            state.targetTemp,
                                            entity.unit ?: "°",
                                        ),
                                        style = MaterialTheme.typography.title1,
                                        fontWeight = FontWeight.Bold,
                                        color = WearColors.Primary,
                                    )

                                    Button(
                                        onClick = { vm.onAdjustTemp(+1.0) },
                                        colors = ButtonDefaults.primaryButtonColors(),
                                        modifier = Modifier.size(40.dp),
                                    ) { Text("+", fontSize = 20.sp) }
                                }
                            }
                        }
                    }

                    // ── HVAC mode picker ─────────────────────────────────────
                    if (state.hvacModes.isNotEmpty()) {
                        item {
                            Text(
                                text = "MODE",
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.5f),
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                            )
                        }
                        items(state.hvacModes) { mode ->
                            val isActive = mode == entity.rawState
                            Chip(
                                label = {
                                    Text(
                                        text = "${hvacModeEmoji(mode)}  ${hvacModeLabel(mode)}",
                                        maxLines = 1,
                                    )
                                },
                                onClick = { vm.onSetHvacMode(mode) },
                                colors = if (isActive)
                                    ChipDefaults.primaryChipColors(
                                        backgroundColor = WearColors.Primary.copy(alpha = 0.3f),
                                    )
                                else
                                    ChipDefaults.secondaryChipColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }

                    // ── Power ────────────────────────────────────────────────
                    item {
                        Chip(
                            label = { Text(if (entity.isOn) "⏻ Turn Off" else "⏻ Turn On") },
                            onClick = { vm.onPowerToggle() },
                            colors = if (entity.isOn)
                                ChipDefaults.secondaryChipColors()
                            else
                                ChipDefaults.primaryChipColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 8.dp),
                        )
                    }

                    item { Spacer(Modifier.height(16.dp)) }
                }
            }
        }
    }
}

// ── Helpers ───────────────────────────────────────────────────────────────────

@Composable
private fun hvacModeColor(mode: String?): androidx.compose.ui.graphics.Color =
    when (mode) {
        "heat"      -> androidx.compose.ui.graphics.Color(0xFFFF8C00)
        "cool"      -> androidx.compose.ui.graphics.Color(0xFF4FC3F7)
        "heat_cool", "auto" -> androidx.compose.ui.graphics.Color(0xFF81C784)
        "dry"       -> androidx.compose.ui.graphics.Color(0xFFFFCC80)
        "fan_only"  -> MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
        "off"       -> MaterialTheme.colors.onBackground.copy(alpha = 0.4f)
        else        -> MaterialTheme.colors.onBackground.copy(alpha = 0.6f)
    }

private fun hvacModeLabel(mode: String?): String = when (mode) {
    "heat"      -> "Heating"
    "cool"      -> "Cooling"
    "heat_cool" -> "Heat / Cool"
    "auto"      -> "Auto"
    "dry"       -> "Dry"
    "fan_only"  -> "Fan Only"
    "off"       -> "Off"
    else        -> mode?.replaceFirstChar { it.uppercaseChar() } ?: "Unknown"
}

private fun hvacModeEmoji(mode: String): String = when (mode) {
    "heat"      -> "🔥"
    "cool"      -> "❄️"
    "heat_cool", "auto" -> "🌡"
    "dry"       -> "💧"
    "fan_only"  -> "🌀"
    "off"       -> "⏻"
    else        -> "●"
}
