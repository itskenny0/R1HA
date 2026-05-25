package com.github.itskenny0.r1ha.feature.scenes

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Fast-fire launcher for HA scenes + scripts. Pulls the full entity list
 * via the REST `/api/states` endpoint (same call the favourites picker
 * uses), filters to scene.* / script.*, and renders a dense LazyColumn
 * the user can scroll with the wheel. Tap a row → fires the appropriate
 * service (scene.turn_on for scenes, script.<script_id> for scripts) +
 * shows a brief confirmation toast.
 *
 * Why a dedicated surface: scenes / scripts are the muscle-memory
 * affordances of a HA setup — 'movie night', 'dinner mode', 'all off'.
 * Putting each one as a card on the card stack works but requires
 * scrolling to it. A flat list with a tap-fire interaction is faster.
 */
@Composable
fun ScenesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: ScenesViewModel = viewModel(factory = ScenesViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "SCENES & SCRIPTS", onBack = onBack)
        // All content inside AdaptiveContent so action buttons, search, chips, and
        // list all centre at 800 dp together on tablets.
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        MasterActionsRow(
            inFlight = ui.masterActionInFlight,
            onLightsOff = { vm.allLightsOff() },
            onLightsOn = { vm.allLightsOn() },
            onMediaPause = { vm.allMediaPause() },
            onSwitchesOff = { vm.allSwitchesOff() },
        )
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
        FilterChips(
            current = ui.filter,
            counts = ui.counts,
            onSelect = { vm.setFilter(it) },
        )
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
                ui.entries.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    // Distinguish "the install has no scenes" from "the search /
                    // filter chip excluded everything" so the user knows which
                    // dial to twist to see anything.
                    val hasAny = ui.all.isNotEmpty()
                    val msg = when {
                        !hasAny -> "No scenes or scripts in HA. Define them in HA's UI to see them here."
                        ui.query.isNotBlank() -> "No matches for '${ui.query}'. Clear the search or try different terms."
                        else -> "Nothing under this filter. Switch to ALL to see everything."
                    }
                    Text(text = msg, style = R1.body, color = R1.InkMuted)
                }
                // Pull-to-refresh wrap — re-issue /api/states to pick up any
                // new scenes / scripts the user added in HA without backing
                // out and re-entering the screen.
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
                        items(items = ui.entries, key = { it.id.value }) { entry ->
                            SceneRow(
                                entry,
                                onFire = { vm.fire(entry) },
                                onLongPress = { vm.showDetail(entry) },
                            )
                        }
                    }
                }
            }
        } // AdaptiveContent
    }
}

@Composable
private fun SceneRow(
    entry: ScenesViewModel.Entry,
    onFire: () -> Unit,
    onLongPress: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Tap = fire the scene/script; long-press = expand detail toast with
            // entity_id + service name. Long press is the right home for the
            // metadata affordance: it's the non-destructive gesture.
            .r1RowPressable(onTap = onFire, onLongPress = onLongPress)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = when (entry.kind) {
                ScenesViewModel.Kind.SCENE -> "SCENE"
                ScenesViewModel.Kind.SCRIPT -> "SCRIPT"
            },
            style = R1.labelMicro,
            color = when (entry.kind) {
                ScenesViewModel.Kind.SCENE -> R1.AccentWarm
                ScenesViewModel.Kind.SCRIPT -> R1.AccentCool
            },
        )
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(text = entry.name, style = R1.body, color = R1.Ink, maxLines = 2)
            Text(
                text = entry.id.value,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun MasterActionsRow(
    inFlight: Boolean,
    onLightsOff: () -> Unit,
    onLightsOn: () -> Unit,
    onMediaPause: () -> Unit,
    onSwitchesOff: () -> Unit,
) {
    // Three side-by-side master actions. Equal weight so the row reads as
    // "panel of mass actions" rather than a primary + secondaries. Each pill
    // arms on first tap and fires on second-within-3s so a fat-fingered tap
    // doesn't cascade through every light in the house. Long-press LIGHTS
    // (turn-all-on) is non-destructive and stays one-tap.
    val armedKey = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(armedKey.value) {
        if (armedKey.value != null) {
            kotlinx.coroutines.delay(3_000L)
            armedKey.value = null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        MasterActionPill(
            modifier = Modifier.weight(1f),
            label = "LIGHTS",
            armedLabel = "CONFIRM",
            armed = armedKey.value == "LIGHTS",
            accent = R1.StatusRed,
            inFlight = inFlight,
            onClick = {
                if (armedKey.value == "LIGHTS") {
                    armedKey.value = null
                    onLightsOff()
                } else {
                    armedKey.value = "LIGHTS"
                }
            },
            onLongClick = onLightsOn,
        )
        MasterActionPill(
            modifier = Modifier.weight(1f),
            label = "MEDIA",
            armedLabel = "CONFIRM",
            armed = armedKey.value == "MEDIA",
            accent = R1.AccentCool,
            inFlight = inFlight,
            onClick = {
                if (armedKey.value == "MEDIA") {
                    armedKey.value = null
                    onMediaPause()
                } else {
                    armedKey.value = "MEDIA"
                }
            },
        )
        MasterActionPill(
            modifier = Modifier.weight(1f),
            label = "SWITCHES",
            armedLabel = "CONFIRM",
            armed = armedKey.value == "SWITCHES",
            accent = R1.AccentWarm,
            inFlight = inFlight,
            onClick = {
                if (armedKey.value == "SWITCHES") {
                    armedKey.value = null
                    onSwitchesOff()
                } else {
                    armedKey.value = "SWITCHES"
                }
            },
        )
    }
    // Discoverability hint for the asymmetric long-press affordance. LIGHTS is
    // the only pill with a hidden second action (long-press → turn ON, because
    // turning all lights on is a common kiosk-wakeup intent), and without a
    // hint nobody would find it. Single muted line under the row keeps the
    // visual weight low while still surfacing the gesture.
    Text(
        text = "Tap = OFF · long-press LIGHTS for ON",
        style = R1.labelMicro,
        color = R1.InkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 2.dp),
    )
}

@Composable
private fun MasterActionPill(
    modifier: Modifier,
    label: String,
    armedLabel: String = label,
    armed: Boolean = false,
    accent: androidx.compose.ui.graphics.Color,
    inFlight: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val pressable = if (onLongClick != null) {
        Modifier.r1RowPressable(
            onTap = { if (!inFlight) onClick() },
            onLongPress = { if (!inFlight) onLongClick() },
        )
    } else {
        Modifier.r1Pressable(onClick = { if (!inFlight) onClick() })
    }
    val fill = when {
        inFlight -> R1.SurfaceMuted
        armed -> accent.copy(alpha = 0.38f)
        else -> accent.copy(alpha = 0.18f)
    }
    Box(
        modifier = modifier
            .height(36.dp)
            .clip(R1.ShapeS)
            .background(fill)
            .then(pressable),
        contentAlignment = Alignment.Center,
    ) {
        // Micro hint glyph in the corner when the pill has a hidden long-press action.
        // ⋯ is the same affordance EntityCard uses for its long-press indicator, so the
        // semantic is consistent across the app.
        if (onLongClick != null && !inFlight && !armed) {
            Text(
                text = "⋯",
                style = R1.labelMicro,
                color = accent.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = 4.dp, top = 2.dp),
            )
        }
        Text(
            text = when {
                inFlight -> "…"
                armed -> armedLabel
                else -> label
            },
            style = R1.labelMicro,
            color = if (inFlight) R1.InkMuted else accent,
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
                placeholder = "bedroom, scene, movie, ...",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun FilterChips(
    current: ScenesViewModel.Filter,
    counts: Map<ScenesViewModel.Filter, Int>,
    onSelect: (ScenesViewModel.Filter) -> Unit,
) {
    val items = listOf(
        ScenesViewModel.Filter.ALL to "ALL",
        ScenesViewModel.Filter.SCENES to "SCENES",
        ScenesViewModel.Filter.SCRIPTS to "SCRIPTS",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for ((filter, label) in items) {
            val active = filter == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(filter) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "$label · ${counts[filter] ?: 0}",
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}
