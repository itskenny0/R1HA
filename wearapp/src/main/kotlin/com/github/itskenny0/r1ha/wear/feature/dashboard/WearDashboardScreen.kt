package com.github.itskenny0.r1ha.wear.feature.dashboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
import com.github.itskenny0.r1ha.core.ha.MediaTransport
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.feature.dashboard.DashboardViewModel

/**
 * Wear OS TODAY Dashboard — glanceable at-a-glance home screen.
 *
 * Shows a curated subset of the phone's Today dashboard:
 *  - Current outdoor weather condition + temperature
 *  - Who's home count
 *  - Active HA timers
 *  - Now-playing media with prev/play/next transport
 *  - DRAW (total power consumption in Watts)
 *  - Notification count
 *
 * Everything above is sourced from the shared [DashboardViewModel] so
 * there is no duplicate logic. Pull-down to refresh is not available on
 * Wear (the scroll gesture is reserved for the crown), so a REFRESH chip
 * sits at the top of the list instead.
 */
@Composable
fun WearDashboardScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    onBack: () -> Unit,
) {
    val vm: DashboardViewModel = viewModel(
        factory = DashboardViewModel.factory(haRepository, settings),
    )
    val ui by vm.ui.collectAsState()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(timeText = { TimeText() }) {
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header + refresh
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(text = "📋 Today", style = MaterialTheme.typography.title3)
                    Chip(
                        label = { Text("↻", style = MaterialTheme.typography.caption1) },
                        onClick = { vm.refresh() },
                        enabled = !ui.loading,
                        colors = ChipDefaults.outlinedChipColors(),
                    )
                }
            }

            // Loading indicator inline
            if (ui.loading) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator()
                    }
                }
            }

            // ── Weather ─────────────────────────────────────────────────────
            ui.weather?.let { w ->
                item {
                    DashRow(
                        icon = conditionEmoji(w.condition),
                        label = w.name,
                        value = w.temperature?.let {
                            "${it.toInt()}${w.temperatureUnit ?: "°"}"
                        } ?: w.condition,
                    )
                }
            }

            // ── Persons home ─────────────────────────────────────────────────
            ui.persons?.let { p ->
                item {
                    DashRow(
                        icon = "👤",
                        label = "Home",
                        value = "${p.homeCount} / ${p.total}",
                    )
                }
            }

            // ── DRAW (power) ──────────────────────────────────────────────────
            if (ui.totalPowerW >= 0) {
                item {
                    DashRow(
                        icon = "⚡",
                        label = "DRAW",
                        value = if (ui.totalPowerW >= 1000)
                            "${"%.1f".format(ui.totalPowerW / 1000.0)} kW"
                        else
                            "${ui.totalPowerW} W",
                    )
                }
            }

            // ── Lights on ────────────────────────────────────────────────────
            if (ui.lightsOnCount >= 0) {
                item {
                    DashRow(
                        icon = "💡",
                        label = "Lights on",
                        value = "${ui.lightsOnCount}",
                    )
                }
            }

            // ── Active timers ────────────────────────────────────────────────
            if (ui.timers.isNotEmpty()) {
                item {
                    Text(
                        text = "Timers",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                items(ui.timers) { timer ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "⏱ ${timer.name}",
                            style = MaterialTheme.typography.caption2,
                            modifier = Modifier.weight(1f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        // Cancel chip
                        Chip(
                            label = { Text("✕", style = MaterialTheme.typography.caption2) },
                            onClick = { vm.timerService(timer.entityId, "cancel") },
                            colors = ChipDefaults.outlinedChipColors(),
                        )
                    }
                }
            }

            // ── Media transport ──────────────────────────────────────────────
            if (ui.media.isNotEmpty()) {
                item {
                    Text(
                        text = "Media",
                        style = MaterialTheme.typography.caption1,
                        color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
                items(ui.media) { m ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                    ) {
                        Text(
                            text = m.title ?: m.name,
                            style = MaterialTheme.typography.caption1,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        m.artist?.let { artist ->
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Chip(
                                label = { Text("⏮", style = MaterialTheme.typography.caption2) },
                                onClick = {
                                    vm.mediaTransport(m.entityId, MediaTransport.PREVIOUS)
                                },
                                colors = ChipDefaults.secondaryChipColors(),
                            )
                            Chip(
                                label = {
                                    Text(
                                        if (m.state == "playing") "⏸" else "▶",
                                        style = MaterialTheme.typography.caption2,
                                    )
                                },
                                onClick = {
                                        val action = MediaTransport.PLAY_PAUSE
                                    vm.mediaTransport(m.entityId, action)
                                },
                                colors = ChipDefaults.primaryChipColors(),
                            )
                            Chip(
                                label = { Text("⏭", style = MaterialTheme.typography.caption2) },
                                onClick = {
                                    vm.mediaTransport(m.entityId, MediaTransport.NEXT)
                                },
                                colors = ChipDefaults.secondaryChipColors(),
                            )
                        }
                    }
                }
            }

            // ── Notifications count ──────────────────────────────────────────
            if (ui.notifications.isNotEmpty()) {
                item {
                    DashRow(
                        icon = "🔔",
                        label = "Alerts",
                        value = "${ui.notifications.size}",
                    )
                }
            }

            // ── Low batteries ────────────────────────────────────────────────
            if (ui.lowBatteries.isNotEmpty()) {
                item {
                    DashRow(
                        icon = "🔋",
                        label = "Low battery",
                        value = "${ui.lowBatteries.size} device(s)",
                    )
                }
            }

            // Spacer at bottom
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun DashRow(icon: String, label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$icon $label",
            style = MaterialTheme.typography.caption1,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.caption1,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.End,
        )
    }
}

private fun conditionEmoji(condition: String): String = when (condition.lowercase()) {
    "sunny", "clear-night" -> "☀"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁"
    "rainy", "pouring" -> "🌧"
    "snowy", "snowy-rainy" -> "❄"
    "lightning", "lightning-rainy" -> "⛈"
    "windy", "windy-variant" -> "💨"
    "fog", "hazy" -> "🌫"
    else -> "🌡"
}
