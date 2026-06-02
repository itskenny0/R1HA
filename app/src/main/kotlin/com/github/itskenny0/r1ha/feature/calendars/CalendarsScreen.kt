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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import java.time.Instant

/**
 * Calendars surface: shows each `calendar.*` entity HA exposes with
 * its currently-on / next-up event preview. NOW pill prefixes events
 * that are happening right now (HA state == "on"); the rest show a
 * relative "in 2 h" timestamp.
 *
 * Doesn't drill into the full event list; that's a follow-up using
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
    var showingAgenda by remember { mutableStateOf(false) }
    if (showingAgenda) {
        AgendaScreen(
            haRepository = haRepository,
            settings = settings,
            wheelInput = wheelInput,
            onBack = { showingAgenda = false },
        )
        return
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "CALENDARS",
            onBack = onBack,
            action = {
                R1Chip(
                    text = "AGENDA",
                    variant = R1ChipVariant.Action,
                    onClick = { showingAgenda = true },
                    contentDescription = "Open cross-calendar agenda",
                )
            },
        )
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
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // Calendar registry fetch failed; distinct from "no
                // calendar integrations configured" empty state.
                Text(
                    text = "Calendars load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.calendars.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
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
                        horizontal = R1.space.m, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    val now = Instant.now()
                    items(items = ui.calendars, key = { it.entityId }) { c ->
                        CalendarRow(c, now = now, onTap = { drillingInto = c })
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun CalendarRow(c: CalendarsViewModel.Calendar, now: Instant, onTap: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onTap)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s)
            .clearAndSetSemantics {
                // Spoken timing mirrors the visible hint: "starts in 2 h" for an
                // upcoming event so a screen reader hears more than just the name.
                val spokenTime = if (c.state != "on" && !c.allDay) {
                    relativeFutureHint(c.eventStart, now)
                        .lowercase()
                        .replace("in ", "starts in ")
                } else {
                    ""
                }
                contentDescription = calendarRowDescription(
                    name = c.name,
                    happeningNow = c.state == "on",
                    allDay = c.allDay,
                    relativeTime = spokenTime,
                    message = c.eventMessage,
                    location = c.eventLocation,
                )
            },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.state == "on") {
                // NOW pill: pulled to the front of the line so the user
                // sees "this is happening right now" at a glance.
                R1Chip(text = "NOW", variant = R1ChipVariant.Pill, tone = R1.AccentGreen)
                Spacer(Modifier.width(R1.space.s))
            }
            Text(
                text = c.name,
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (c.allDay && c.state != "on") {
                // ALL-DAY pill: surfaced instead of a relative-time countdown
                // that would be misleading for events without a specific start
                // time. Sits in the position the time hint normally occupies.
                R1Chip(text = "ALL-DAY", variant = R1ChipVariant.Pill, tone = R1.AccentWarm)
            } else {
                // Forward-looking hint for the next event ("IN 2H" / "IN 3D"),
                // or the end of the current one if it's happening now ("ENDS
                // IN 1H"). RelativeTimeLabel only renders past "ago" strings, so
                // it collapsed every upcoming event here to "just now".
                val isNow = c.state == "on"
                val ts = if (isNow) c.eventEnd else c.eventStart
                val hint = relativeFutureHint(ts, now)
                if (hint.isNotBlank()) {
                    val label = if (isNow && hint.startsWith("IN ")) "ENDS $hint" else hint
                    Text(text = label, style = R1.labelMicro, color = R1.InkMuted)
                }
            }
        }
        if (!c.eventMessage.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xs))
            Text(
                text = c.eventMessage,
                style = R1.body,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
        if (!c.eventLocation.isNullOrBlank()) {
            Spacer(Modifier.size(R1.space.xxs))
            Text(
                text = "@ ${c.eventLocation}",
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
    }
}
