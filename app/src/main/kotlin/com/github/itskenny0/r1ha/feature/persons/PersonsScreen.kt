package com.github.itskenny0.r1ha.feature.persons

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * "Who's home" surface — combines `person.*` (high-level humans the
 * user has configured in HA) with `device_tracker.*` (per-phone /
 * per-router pings that power the person entities) into one screen.
 *
 * Two sub-headings: PEOPLE and DEVICES. People come first because
 * they're the higher-fidelity view; devices are the raw plumbing
 * underneath, useful for "why does HA think X is away".
 */
@Composable
fun PersonsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Drill into the entity's location-state history. Wired from
     *  AppNavGraph; defaults to a no-op so previews / tests don't
     *  have to thread the callback through. */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: PersonsViewModel = viewModel(factory = PersonsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.personsRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "WHO'S HOME", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        when {
            ui.loading -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "no person integrations" — the request
                // itself failed (auth, network, server down).
                Text(
                    text = "Persons load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.people.isEmpty() && ui.devices.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No people or device trackers in HA. Add a person integration to see them here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (ui.people.isNotEmpty()) {
                        item {
                            Text(
                                text = "PEOPLE · ${ui.people.size}",
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                        items(items = ui.people, key = { it.entityId }) { e ->
                            PersonRow(e, onTap = { onOpenHistory(e.entityId) })
                        }
                    }
                    if (ui.devices.isNotEmpty()) {
                        item {
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = "DEVICE TRACKERS · ${ui.devices.size}",
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                modifier = Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp),
                            )
                        }
                        items(items = ui.devices, key = { it.entityId }) { e ->
                            PersonRow(e, onTap = { onOpenHistory(e.entityId) })
                        }
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun PersonRow(entry: PersonsViewModel.Entry, onTap: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // State chip — green when "home", neutral for a named zone, amber
        // when "away" / "not_home", red when unavailable.
        val (label, color) = when (entry.state.lowercase()) {
            "home" -> "HOME" to R1.AccentGreen
            "not_home", "away" -> "AWAY" to R1.StatusAmber
            "unknown", "unavailable" -> "?" to R1.StatusRed
            else -> entry.state.uppercase() to R1.AccentCool
        }
        Text(text = label, style = R1.labelMicro, color = color)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.name,
                    style = R1.body,
                    color = R1.Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // Relative timestamp on the right of the name — 'since
                // 2h' so the user can see how long the person/device has
                // been in their current state. Same ticker as the rest
                // of the app, so live-updates without us touching it.
                Spacer(Modifier.width(6.dp))
                com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel(
                    at = entry.since,
                    color = R1.InkMuted,
                    style = R1.labelMicro,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.entityId,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                if (entry.source != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = entry.source.uppercase(),
                        style = R1.labelMicro,
                        color = R1.AccentNeutral,
                    )
                }
                if (entry.batteryLevel != null) {
                    Spacer(Modifier.width(6.dp))
                    // Colour the battery digit by threshold so a low
                    // phone battery on a person tracker stands out at
                    // a glance — same red/amber/muted ramp the other
                    // battery surfaces use.
                    val batteryColor = when {
                        entry.batteryLevel < 10 -> R1.StatusRed
                        entry.batteryLevel < 25 -> R1.StatusAmber
                        else -> R1.AccentNeutral
                    }
                    Text(
                        text = "${entry.batteryLevel}%",
                        style = R1.labelMicro,
                        color = batteryColor,
                    )
                }
                if (entry.gpsAccuracy != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "±${entry.gpsAccuracy}m",
                        style = R1.labelMicro,
                        color = R1.AccentNeutral,
                    )
                }
            }
        }
    }
}
