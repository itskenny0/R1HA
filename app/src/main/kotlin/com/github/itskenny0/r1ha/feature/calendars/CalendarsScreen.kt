package com.github.itskenny0.r1ha.feature.calendars

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Calendars surface — shows each `calendar.*` entity HA exposes with
 * its currently-on / next-up event preview. NOW pill prefixes events
 * that are happening right now (HA state == "on"); the rest show a
 * relative "in 2 h" timestamp.
 *
 * Doesn't drill into the full event list — that's a follow-up using
 * the dedicated `/api/calendars/<id>?start=...&end=...` endpoint.
 * This surface is the at-a-glance "what's next?" view that fits the
 * R1's small display.
 */
@Composable
fun CalendarsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: CalendarsViewModel = viewModel(factory = CalendarsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.calendarsRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    var drillingInto by remember { mutableStateOf<CalendarsViewModel.Calendar?>(null) }
    val drillTarget = drillingInto
    if (drillTarget != null) {
        CalendarEventsScreen(
            haRepository = haRepository,
            settings = settings,
            wheelInput = wheelInput,
            entityId = drillTarget.entityId,
            calendarName = drillTarget.name,
            onBack = { drillingInto = null },
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "CALENDARS", onBack = onBack)
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
            ui.error != null && ui.calendars.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Calendar registry fetch failed — distinct from "no
                // calendar integrations configured" empty state.
                Text(
                    text = "Calendars load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.calendars.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No calendar entities in HA. Add a calendar integration to see them here.",
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
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = ui.calendars, key = { it.entityId }) { c ->
                        CalendarRow(c, onTap = { drillingInto = c })
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun CalendarRow(c: CalendarsViewModel.Calendar, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.state == "on") {
                // NOW pill — pulled to the front of the line so the user
                // sees "this is happening right now" at a glance.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentGreen.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = "NOW", style = R1.labelMicro, color = R1.AccentGreen)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = c.name,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (c.allDay) {
                // ALL-DAY pill — surfaced instead of a relative-time
                // countdown that would be misleading for events without
                // a specific start time. Sits in the position the
                // RelativeTimeLabel would normally occupy.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentWarm.copy(alpha = 0.18f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = "ALL-DAY", style = R1.labelMicro, color = R1.AccentWarm)
                }
            } else {
                // Relative timestamp for the next event (or current event end
                // if NOW). Same ticker as the rest of the app.
                val ts = if (c.state == "on") c.eventEnd else c.eventStart
                RelativeTimeLabel(at = ts, color = R1.InkMuted, style = R1.labelMicro)
            }
        }
        if (!c.eventMessage.isNullOrBlank()) {
            Spacer(Modifier.size(4.dp))
            Text(
                text = c.eventMessage,
                style = R1.body,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
        if (!c.eventLocation.isNullOrBlank()) {
            Spacer(Modifier.size(2.dp))
            Text(
                text = "@ ${c.eventLocation}",
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
    }
}
