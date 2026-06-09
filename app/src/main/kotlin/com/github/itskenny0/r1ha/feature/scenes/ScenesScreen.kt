package com.github.itskenny0.r1ha.feature.scenes

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.rememberRelativeTime
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Fast-fire launcher for HA scenes + scripts. Pulls the full entity list
 * via the REST `/api/states` endpoint (same call the favourites picker
 * uses), filters to scene.* / script.*, and renders a dense LazyColumn
 * the user can scroll with the wheel. Tap a row fires the appropriate
 * service (scene.turn_on for scenes, script.<script_id> for scripts) +
 * shows a brief confirmation toast.
 *
 * Why a dedicated surface: scenes / scripts are the muscle-memory
 * affordances of a HA setup ('movie night', 'dinner mode', 'all off').
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
    // entries is a getter that re-filters the full scene/script set on every read;
    // it's read both for the empty-state check and the items() call, and recompose
    // fires per search keystroke. Memoise against its inputs so the filter runs
    // once per state change instead of twice per frame.
    val entries = remember(ui.all, ui.filter, ui.query) { ui.entries }
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
                ui.loading && ui.all.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Loading scenes and scripts"
                        },
                ) {
                    SkeletonList()
                }
                // Dedicated error state: only when the load failed AND we have
                // nothing cached to show. A transient failure over a populated list
                // keeps the list (the toast already reported the failure) so a blip
                // doesn't blank a working screen.
                ui.error != null && ui.all.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(R1.space.xl)
                        .semantics {
                            liveRegion = LiveRegionMode.Polite
                            contentDescription = "Failed to load scenes and scripts: ${ui.error}"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(R1.space.m),
                    ) {
                        Text(
                            text = "Couldn't load scenes and scripts: ${ui.error}",
                            style = responsiveType(R1.body),
                            color = R1.StatusRed,
                        )
                        // Explicit retry: the empty / error Box isn't scrollable so
                        // pull-to-refresh can't fire here. Give the user a direct way
                        // to re-issue the /api/states fetch without leaving the screen.
                        R1Chip(
                            text = "RETRY",
                            modifier = Modifier.height(R1.MinTarget),
                            variant = R1ChipVariant.Action,
                            onClick = { vm.refresh() },
                            contentDescription = "Retry loading scenes and scripts",
                        )
                    }
                }
                entries.isEmpty() -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(R1.space.xl)
                        .semantics { liveRegion = LiveRegionMode.Polite },
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
                    Text(text = msg, style = responsiveType(R1.body), color = R1.InkMuted)
                }
                // Pull-to-refresh wrap re-issues /api/states to pick up any
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
                        contentPadding = PaddingValues(
                            horizontal = R1.space.m, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        items(items = entries, key = { it.id.value }) { entry ->
                            SceneRow(
                                entry,
                                firing = entry.id in ui.firing,
                                onFire = { vm.fire(entry) },
                                onStop = { vm.stopScript(entry) },
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
    firing: Boolean,
    onFire: () -> Unit,
    onStop: () -> Unit,
    onLongPress: () -> Unit,
) {
    val kindLabel = when (entry.kind) {
        ScenesViewModel.Kind.SCENE -> "SCENE"
        ScenesViewModel.Kind.SCRIPT -> "SCRIPT"
    }
    val kindTone = when (entry.kind) {
        ScenesViewModel.Kind.SCENE -> R1.AccentWarm
        ScenesViewModel.Kind.SCRIPT -> R1.AccentCool
    }
    // Resolve the last-activated phrase once so both the visible trailing label
    // and the spoken row description stay in lockstep. Empty when the entity has
    // never run or the timestamp is unknown.
    val relative = entry.lastActivated?.let { rememberRelativeTime(it) }.orEmpty()
    // Full spoken label: fold name, kind, in-flight status, and the freshness
    // hint into one phrase so TalkBack reads the card as a single unit. While the
    // turn_on is in flight the label flips to "Activating <name>" / "Running
    // <name>" and the row becomes a polite live region so the change is
    // announced without the user re-focusing the row.
    val rowLabel = sceneRowLabel(
        name = entry.name,
        kind = entry.kind,
        firing = firing,
        available = entry.available,
        running = entry.running,
        runningCount = entry.runningCount,
        lastActivatedSpoken = relative,
    )
    // Announce on the two transient/notable states: the tap echo (firing) and a
    // script that's actively executing. Both warrant a polite re-read without the
    // user re-focusing the row.
    val rowSemantics = if (firing || entry.running) {
        Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = rowLabel
        }
    } else {
        Modifier
    }
    // Canonical row: friendly name primary, entity_id secondary, kind shown as a
    // leading domain icon (scene vs script glyph). Tap fires the scene/script (or
    // stops a running script); long-press surfaces the detail toast (entity_id +
    // service) since it's the non-destructive gesture, so the row stays on
    // r1RowPressable rather than R1Row's single onClick.
    // Trailing freshness label: scenes set their state to the last-activated
    // timestamp (scripts report it via last_triggered), surfaced as a subtle
    // 'activated <relative>' so the user can tell which scenes ran recently. It
    // self-omits when the entity has never run (label resolves to "").
    // Unavailable entries dim their leading icon too, so the whole row reads
    // as inert at a glance rather than just the muted label text. A running script
    // tints its icon green to match the RUNNING / STOP badge.
    val iconTone = when {
        !entry.available -> R1.InkMuted
        entry.running -> R1.AccentGreen
        else -> kindTone
    }
    R1Row(
        label = entry.name,
        description = entry.id.value,
        boxed = true,
        enabled = entry.available,
        leadingContent = {
            Icon(
                imageVector = R1Icons.forDomain(
                    if (entry.kind == ScenesViewModel.Kind.SCRIPT) "script" else "scene",
                ),
                // The text kind label ("SCENE" / "SCRIPT") moves into the content
                // description so the icon keeps the same a11y meaning the old pill had.
                contentDescription = kindLabel,
                tint = iconTone,
                modifier = Modifier.size(20.dp),
            )
        },
        // Trailing priority: the transient tap echo (firing spinner) wins, then the
        // unavailable badge (can't be fired), then a running script's STOP control
        // (tap to fire script.turn_off), and finally the subtle last-activated label.
        trailing = when {
            firing -> {
                {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = kindTone,
                    )
                }
            }
            !entry.available -> {
                {
                    R1Chip(
                        text = "UNAVAILABLE",
                        variant = R1ChipVariant.Pill,
                        tone = R1.StatusAmber,
                    )
                }
            }
            entry.running -> {
                {
                    // A running script's badge doubles as a STOP control: tapping it
                    // fires script.turn_off (same as tapping the row body). It reads
                    // "STOP x2" when several copies are in flight so the user knows the
                    // tap cancels the run rather than restarting it.
                    R1Chip(
                        text = entry.runningCount?.let { "STOP x$it" } ?: "STOP",
                        variant = R1ChipVariant.Action,
                        selected = true,
                        tone = R1.StatusRed,
                        onClick = onStop,
                        contentDescription = "Stop ${entry.name} script",
                    )
                }
            }
            relative.isNotEmpty() -> {
                {
                    Text(
                        text = "activated $relative",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            else -> null
        },
        modifier = Modifier
            .then(rowSemantics)
            .r1RowPressable(
                onTap = onFire,
                onLongPress = onLongPress,
                contentDescription = rowLabel,
            ),
    )
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
    val armedKey = remember { mutableStateOf<String?>(null) }
    LaunchedEffect(armedKey.value) {
        if (armedKey.value != null) {
            delay(3_000L)
            armedKey.value = null
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
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
    // the only pill with a hidden second action (long-press turns ON, because
    // turning all lights on is a common kiosk-wakeup intent), and without a
    // hint nobody would find it. Single muted line under the row keeps the
    // visual weight low while still surfacing the gesture.
    Text(
        text = "Tap to arm, tap again to confirm; long-press LIGHTS for ON",
        style = responsiveType(R1.labelMicro),
        color = R1.InkMuted,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.l, vertical = R1.space.xxs),
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
    // Spoken label tracks the pill's three visual states so the icon-free pill is
    // never announced as a bare word. Armed and in-flight are the states a sighted
    // user reads from the fill change; mirror them for screen readers.
    val actionLabel = when {
        inFlight -> "$label action in progress"
        armed -> "Confirm $label action"
        else -> "$label master action"
    }
    val pressable = if (onLongClick != null) {
        Modifier.r1RowPressable(
            onTap = { if (!inFlight) onClick() },
            onLongPress = { if (!inFlight) onLongClick() },
            contentDescription = actionLabel,
        )
    } else {
        Modifier.r1Pressable(
            onClick = { if (!inFlight) onClick() },
            contentDescription = actionLabel,
        )
    }
    val fill = when {
        inFlight -> R1.SurfaceMuted
        armed -> accent.copy(alpha = 0.38f)
        else -> accent.copy(alpha = 0.18f)
    }
    Box(
        modifier = modifier
            .height(R1.MinTarget)
            .clip(R1.ShapeS)
            .background(fill)
            .then(pressable),
        contentAlignment = Alignment.Center,
    ) {
        // Micro hint glyph in the corner when the pill has a hidden long-press action.
        // The same affordance EntityCard uses for its long-press indicator, so the
        // semantic is consistent across the app.
        if (onLongClick != null && !inFlight && !armed) {
            Text(
                text = "⋯",
                style = R1.labelMicro,
                color = accent.copy(alpha = 0.7f),
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(end = R1.space.xs, top = R1.space.xxs),
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
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = R1.space.s),
        )
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.semantics {
                    contentDescription = "Filter scenes and scripts"
                },
                placeholder = "bedroom, scene, movie, ...",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier
                    .size(R1.MinTarget)
                    .r1Pressable({ onQueryChange("") }, contentDescription = "Clear search"),
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
    // Scrolls horizontally so the three count chips never clip on the R1's ~240dp
    // panel (three labels + counts at the small gap can exceed the width once the
    // counts hit double digits). Roomier tiers just show them inline with slack.
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        for ((filter, label) in items) {
            R1Chip(
                text = "$label  ${counts[filter] ?: 0}",
                variant = R1ChipVariant.Filter,
                selected = filter == current,
                onClick = { onSelect(filter) },
            )
        }
    }
}
