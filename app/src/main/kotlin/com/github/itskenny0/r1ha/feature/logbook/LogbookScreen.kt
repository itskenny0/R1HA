package com.github.itskenny0.r1ha.feature.logbook

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
import androidx.compose.foundation.layout.imePadding
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
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.rememberCoroutineScope
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Recent Activity surface — mirrors HA's Logbook panel. Reverse-
 * chronological list of state changes, automation triggers, scene
 * activations, and script invocations. The wheel scrolls the list;
 * pull-to-refresh on the LazyColumn isn't wired (the WINDOW chips
 * implicitly re-fetch on a change and the back-then-forward nav
 * triggers a fresh load via [LaunchedEffect]).
 *
 * The row carries: [domain] chip on the left (accent-coloured), event
 * name + message, and a soft relative timestamp. Tap currently
 * doesn't navigate anywhere — drilling into a specific entity's
 * history is a follow-up; the immediate value is "what just
 * happened?" at a glance.
 */
@Composable
fun LogbookScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Optional callback to drill into the entity's full History
     *  surface — wired from AppNavGraph. The row's tap action becomes
     *  "drill into history" instead of just showing a detail toast,
     *  closing the loop between "what just changed" and "what was it
     *  doing earlier today". */
    onOpenHistory: (entityId: String) -> Unit = {},
) {
    val vm: LogbookViewModel = viewModel(factory = LogbookViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.logbookRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    // Long-press → open the entity's history in HA's web UI via the
    // system browser. The R1's stock browser is rough but works; users on
    // a tablet next to the device are the more likely audience for this
    // drill-down. Server URL comes from the active settings snapshot.
    fun openInHa(entry: LogbookEntry) {
        val entityId = entry.entityId?.value ?: run {
            Toaster.show("No entity_id on this row")
            return
        }
        scope.launch {
            val server = runCatching { settings.settings.first().server?.url }.getOrNull()
            if (server.isNullOrBlank()) {
                Toaster.error("No HA server configured")
                return@launch
            }
            val url = "${server.trimEnd('/')}/history?entity_id=$entityId"
            runCatching {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url),
                    ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.onFailure { t ->
                R1Log.w("Logbook", "open-in-HA failed: ${t.message}")
                Toaster.error("No browser to open ${url}")
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "RECENT ACTIVITY",
            onBack = onBack,
            action = {
                // TAIL chip — subscribes to HA's logbook_entry event stream and
                // prepends events in real-time. The window picker is still
                // honoured for the initial REST fetch; TAIL just adds the
                // live additions.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(if (ui.tail) R1.AccentCool.copy(alpha = 0.18f) else R1.SurfaceMuted)
                        .border(
                            1.dp,
                            if (ui.tail) R1.AccentCool.copy(alpha = 0.6f) else R1.Hairline,
                            R1.ShapeS,
                        )
                        .r1Pressable(onClick = { vm.setTail(!ui.tail) })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.tail) "TAIL · ON" else "TAIL",
                        style = R1.labelMicro,
                        color = if (ui.tail) R1.AccentCool else R1.InkSoft,
                    )
                }
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        WindowChips(current = ui.window, onSelect = { vm.setWindow(it) })
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
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
            ui.entries.isEmpty() && ui.error != null -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = ui.error!!, style = R1.body, color = R1.StatusRed)
            }
            ui.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Distinguish a quiet HA install from a filter that hides
                // everything so the user knows whether to wait, change
                // window, or clear the search.
                val hasAny = ui.all.isNotEmpty()
                val msg = when {
                    !hasAny -> "Nothing happened in the selected window."
                    ui.query.isNotBlank() -> "No matches for '${ui.query}' in this window."
                    else -> "Logbook is empty for the selected window."
                }
                Text(text = msg, style = R1.body, color = R1.InkMuted)
            }
            // Pull-to-refresh wrap — the logbook is naturally append-only
            // so a refresh just re-issues the same window query and picks
            // up anything that landed in the seconds since the last fetch.
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
                    items(
                        items = ui.entries,
                    // Stable key: timestamp nanos + entity-id + name keeps
                    // duplicate-message rows distinct (two automations firing
                    // at the same wall-clock second on different entities).
                    key = { it.timestamp.toEpochMilli().toString() + "|" + (it.entityId?.value ?: it.name) },
                    ) { entry ->
                        LogbookRow(
                            entry,
                            // Tap drills into the entity's history — feels
                            // like a natural follow-on from 'I just saw
                            // this state-change'. Falls back to the
                            // detail toast for entries without an
                            // entity_id (typical for system events,
                            // automation triggers without a target).
                            onTap = {
                                val eid = entry.entityId?.value
                                if (!eid.isNullOrBlank()) onOpenHistory(eid)
                                else vm.showDetail(entry)
                            },
                            onLongPress = { openInHa(entry) },
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun WindowChips(
    current: LogbookViewModel.Window,
    onSelect: (LogbookViewModel.Window) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (w in LogbookViewModel.Window.entries) {
            val active = w == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(w) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = w.label,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun LogbookRow(
    entry: LogbookEntry,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Tap = expand detail toast. Long-press = open the entity's
            // /history view in HA's web UI via the system browser.
            // Tap = drill into the entity's native History view (or fall back
            // to a detail toast when the row has no entity_id, typical for
            // system events). Long-press = open the entity's /history view in
            // HA's web UI via the system browser, for users who want the full
            // HA-native graph view alongside this app.
            .r1RowPressable(onTap = onTap, onLongPress = onLongPress)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Domain accent label — coloured by HA-side domain so a glance
        // separates lights from automations from scenes. Domains we don't
        // recognise get the neutral ink colour.
        Text(
            text = (entry.domain ?: "—").uppercase(),
            style = R1.labelMicro,
            color = accentFor(entry.domain),
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = entry.name, style = R1.body, color = R1.Ink, maxLines = 2)
            Text(
                text = entry.message,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        // Relative timestamp — "2m", "47s", "1h" — produced by the same
        // ticker as elsewhere in the app so all surfaces tick together.
        RelativeTimeLabel(
            at = entry.timestamp,
            color = R1.InkMuted,
            style = R1.labelMicro,
        )
    }
}

@Composable
private fun SearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "kitchen, automation, light.bedroom, ...",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

/** Map HA's domain prefix string to one of the design-token accent
 *  colours. Kept deliberately small — anything not enumerated falls
 *  back to AccentNeutral so a row never goes uncoloured. */
private fun accentFor(domain: String?) = when (domain) {
    "light", "fan", "media_player", "switch", "input_boolean" -> R1.AccentWarm
    "sensor", "binary_sensor", "cover", "valve", "number", "input_number" -> R1.AccentCool
    "scene", "script", "automation", "button", "input_button" -> R1.AccentGreen
    else -> R1.AccentNeutral
}
