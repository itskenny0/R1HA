package com.github.itskenny0.r1ha.feature.helpers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
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
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Helpers browser — mirrors HA's frontend Helpers configuration panel
 * (Settings → Devices & Services → Helpers).
 *
 * Each helper domain gets its own per-row control archetype:
 *   - input_boolean → ON / OFF chip (tap toggles)
 *   - input_number → −/+ stepper with current value + unit
 *   - counter → −/+ + RESET (counter only steps by the configured `step`)
 *   - input_select → tap to cycle through options (long-press shows full list — future)
 *   - input_text / input_datetime → read-only value display (editing
 *     these on a wheel-input device isn't great UX; we display only)
 *   - input_button → PRESS chip
 *   - timer → state label + START / PAUSE / CANCEL pills
 *
 * The screen never tries to be a full editor; for that the user has the
 * web UI. This is the at-a-glance "what helpers do I have, can I poke
 * them?" surface — matches HA Companion's helpers list parity item.
 */
@Composable
fun HelpersScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: HelpersViewModel = viewModel(
        factory = HelpersViewModel.factory(haRepository, settings),
    )
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val activeFavourites = androidx.compose.runtime.remember(
        appSettings.activePageId, appSettings.pages,
    ) {
        appSettings.pages.firstOrNull { it.id == appSettings.activePageId }
            ?.favorites?.toSet() ?: emptySet()
    }
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    // Entity id of the input_number row currently grabbing the wheel for value
    // stepping, or null when the wheel scrolls the list normally. Tap on the
    // value text activates; tapping again (or 5 s of no wheel events) hands the
    // wheel back. Stored as a string so the same `remember` survives recompose
    // even if HelpersViewModel.Entry instances are reconstructed.
    val numberWheelTarget = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    // Auto-release the wheel target after a quiet period so a user who walked
    // away mid-edit doesn't lose normal scrolling forever. Each wheel detent
    // bumps this; expiry clears the target.
    val numberWheelLastEvent = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableLongStateOf(0L)
    }
    WheelScrollFor(
        wheelInput = wheelInput,
        listState = listState,
        settings = settings,
        // Hand the wheel off to the per-row stepper while a number row is
        // active; otherwise the same detent would scroll the list AND step the
        // value, which felt twitchy in early testing.
        enabled = numberWheelTarget.value == null,
    )
    val tickHaptic = com.github.itskenny0.r1ha.ui.components.rememberTickHaptic()
    androidx.compose.runtime.LaunchedEffect(numberWheelTarget.value) {
        val targetId = numberWheelTarget.value ?: return@LaunchedEffect
        // Seed the watchdog with "now" so the 5 s timeout starts from entry
        // into wheel mode rather than from 1970 (which would fire instantly).
        numberWheelLastEvent.longValue = System.currentTimeMillis()
        // Auto-release watchdog: if no wheel events arrive for 5 s while
        // stepping is active, drop the target so list scrolling resumes.
        // 5 s is comfortably longer than a deliberate pause between detents
        // (the user thinking about which way to go), shorter than the
        // "user walked away" threshold. Runs as a child of the LE so it's
        // cancelled cleanly when the target changes (which itself cancels
        // and re-runs the LE).
        val watchdog = launch {
            while (true) {
                val sinceLast = System.currentTimeMillis() - numberWheelLastEvent.longValue
                val waitMs = (5_000L - sinceLast).coerceAtLeast(250L)
                kotlinx.coroutines.delay(waitMs)
                if (System.currentTimeMillis() - numberWheelLastEvent.longValue >= 5_000L) {
                    numberWheelTarget.value = null
                    break
                }
            }
        }
        try {
            wheelInput.events.collect { event ->
                val entry = vm.ui.value.entries.firstOrNull { it.id.value == targetId }
                    ?: return@collect
                if (entry.kind != HelpersViewModel.Kind.NUMBER) return@collect
                val value = entry.numericValue ?: return@collect
                val step = entry.step ?: 1.0
                val sign = if (event.direction ==
                    com.github.itskenny0.r1ha.core.input.WheelEvent.Direction.UP) +1 else -1
                val next = (value + sign * step)
                    .coerceAtLeast(entry.min ?: Double.NEGATIVE_INFINITY)
                    .coerceAtMost(entry.max ?: Double.POSITIVE_INFINITY)
                if (next != value) {
                    vm.setNumber(entry, next)
                    tickHaptic()
                }
                numberWheelLastEvent.longValue = System.currentTimeMillis()
            }
        } finally {
            watchdog.cancel()
        }
    }
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "HELPERS",
            onBack = onBack,
            action = {
                // REFRESH chip — same idiom the Energy / Zones /
                // Automations surfaces use. The list also auto-pulls
                // on every helper service dispatch with a 300-500 ms
                // settle, but a manual refresh is still useful after
                // an external HA change.
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        BucketChips(current = ui.bucket, counts = ui.counts, onSelect = { vm.setBucket(it) })
        SearchBar(query = ui.query, onQueryChange = { vm.setQuery(it) })
        when {
            ui.loading && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            ui.error != null && ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Helpers load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No helpers defined. Add them under Settings → " +
                        "Devices & Services → Helpers in HA's web UI.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            ui.entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                    else "No helpers in '${ui.bucket.label}'.",
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
                        items(items = ui.entries, key = { it.id.value }) { entry ->
                            HelperRow(
                                entry = entry,
                                vm = vm,
                                isFavorite = entry.id.value in activeFavourites,
                                isWheelActive = numberWheelTarget.value == entry.id.value,
                                onToggleWheel = {
                                    val cur = numberWheelTarget.value
                                    numberWheelTarget.value = if (cur == entry.id.value) null
                                    else entry.id.value
                                },
                            )
                        }
                    }
                }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun BucketChips(
    current: HelpersViewModel.Bucket,
    counts: Map<HelpersViewModel.Bucket, Int>,
    onSelect: (HelpersViewModel.Bucket) -> Unit,
) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        HelpersViewModel.Bucket.entries.forEach { bucket ->
            val count = counts[bucket] ?: 0
            // Hide empty per-kind chips (except ALL) so the strip stays
            // tight on small installs.
            if (count == 0 && bucket != HelpersViewModel.Bucket.ALL) return@forEach
            val active = bucket == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(bucket) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${bucket.label} · $count",
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
        }
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
                placeholder = "kitchen, away, …",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier.size(48.dp).r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun HelperRow(
    entry: HelpersViewModel.Entry,
    vm: HelpersViewModel,
    isFavorite: Boolean,
    /**
     * True when this row is currently grabbing the screen-level wheel input for
     * value stepping. Drives a border-highlight and a WHEEL chip on the row so
     * the user can see which value the wheel is driving. Only meaningful for
     * input_number rows (the wheel handler ignores every other kind).
     */
    isWheelActive: Boolean = false,
    /**
     * Toggles wheel-stepping mode for this row. Fired by a tap on the value
     * display. Tapping again (or 5 s of no wheel activity) hands the wheel
     * back to the list scroller.
     */
    onToggleWheel: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Highlight the row's border in the warm accent when the wheel is
            // driving its value, so the user can see at a glance which row is
            // the wheel target without scrolling around to verify.
            .border(
                1.dp,
                if (isWheelActive) R1.AccentWarm else R1.Hairline,
                R1.ShapeS,
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = entry.name,
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (isWheelActive) {
                // Visual confirmation of the wheel hand-off. Disappears the
                // moment focus leaves the row (timeout or tap-elsewhere).
                Text(
                    text = "WHEEL",
                    style = R1.labelMicro,
                    color = R1.AccentWarm,
                )
                Spacer(Modifier.width(6.dp))
            }
            Spacer(Modifier.width(6.dp))
            // ☆ pin-to-favourites — only for helpers whose entity_id
            // domain is recognised by the card stack (input_boolean
            // renders as SwitchCard, input_number as a scalar slider,
            // input_select as SelectCard, input_button as ActionCard).
            // counter / timer / input_text / input_datetime aren't on
            // the card stack's supported-domain list yet, so the star
            // would silently no-op for those — hide it instead of
            // misleading the user.
            if (entry.kind in CARD_STACK_FRIENDLY_KINDS) {
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .r1Pressable(onClick = { vm.addToFavorites(entry) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isFavorite) "★" else "☆",
                        style = R1.labelMicro,
                        color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                text = entry.kind.name,
                style = R1.labelMicro,
                color = accentForKind(entry.kind),
            )
        }
        Text(
            text = entry.id.value,
            style = R1.labelMicro,
            color = R1.InkSoft,
            maxLines = 1,
        )
        Spacer(Modifier.size(6.dp))
        // Per-kind control row — branches on entry.kind so each domain
        // gets the affordance that fits HA's native semantic.
        when (entry.kind) {
            HelpersViewModel.Kind.BOOLEAN -> BooleanControl(entry, vm)
            HelpersViewModel.Kind.NUMBER -> NumberControl(
                entry = entry,
                vm = vm,
                isWheelActive = isWheelActive,
                onToggleWheel = onToggleWheel,
            )
            HelpersViewModel.Kind.COUNTER -> CounterControl(entry, vm)
            HelpersViewModel.Kind.SELECT -> SelectControl(entry, vm)
            HelpersViewModel.Kind.TEXT -> ReadOnlyValue(entry.state)
            HelpersViewModel.Kind.DATETIME -> ReadOnlyValue(entry.state)
            HelpersViewModel.Kind.BUTTON -> ButtonControl(entry, vm)
            HelpersViewModel.Kind.TIMER -> TimerControl(entry, vm)
            HelpersViewModel.Kind.UNKNOWN -> ReadOnlyValue(entry.state)
        }
    }
}

@Composable
private fun BooleanControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val isOn = entry.state.equals("on", ignoreCase = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (isOn) R1.AccentGreen.copy(alpha = 0.22f) else R1.Bg)
                .border(
                    1.dp,
                    if (isOn) R1.AccentGreen.copy(alpha = 0.5f) else R1.Hairline,
                    R1.ShapeS,
                )
                .r1Pressable(onClick = { vm.toggleBoolean(entry) })
                .padding(horizontal = 14.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (isOn) "ON" else "OFF",
                style = R1.body,
                color = if (isOn) R1.AccentGreen else R1.InkSoft,
            )
        }
    }
}

@Composable
private fun NumberControl(
    entry: HelpersViewModel.Entry,
    vm: HelpersViewModel,
    isWheelActive: Boolean = false,
    onToggleWheel: () -> Unit = {},
) {
    val value = entry.numericValue
    val step = entry.step ?: 1.0
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepPill(label = "−", onClick = {
            if (value != null) vm.setNumber(entry, (value - step).coerceAtLeast(entry.min ?: Double.NEGATIVE_INFINITY))
        })
        Spacer(Modifier.width(8.dp))
        // Live value display — formatted as integer when the step is
        // whole, otherwise as a one-decimal float so 0.5° helpers
        // don't get rounded to an unhelpful integer. Tap toggles wheel
        // stepping for this row so the user can dial a value with the
        // R1 wheel instead of repeatedly hitting +/−. Visual accent
        // matches the row's WHEEL chip / border highlight.
        val formatted = when {
            value == null -> entry.state
            step % 1.0 == 0.0 -> "${value.toInt()}"
            else -> "%.1f".format(value)
        }
        val withUnit = if (entry.unit.isNullOrBlank()) formatted else "$formatted ${entry.unit}"
        Text(
            text = withUnit,
            style = R1.body,
            color = if (isWheelActive) R1.AccentWarm else R1.Ink,
            modifier = Modifier
                .weight(1f)
                .r1Pressable(
                    onClick = onToggleWheel,
                    contentDescription = if (isWheelActive)
                        "Stop wheel stepping" else "Use wheel to step this value",
                )
                .padding(vertical = 4.dp),
        )
        StepPill(label = "+", onClick = {
            if (value != null) vm.setNumber(entry, (value + step).coerceAtMost(entry.max ?: Double.POSITIVE_INFINITY))
        })
    }
}

@Composable
private fun CounterControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val value = entry.numericValue
    val armed = androidx.compose.runtime.remember(entry.id.value) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    androidx.compose.runtime.LaunchedEffect(armed.value) {
        if (armed.value) {
            kotlinx.coroutines.delay(3_000L)
            armed.value = false
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        StepPill(label = "−", onClick = { vm.counterDecrement(entry) })
        Spacer(Modifier.width(8.dp))
        Text(
            text = value?.toInt()?.toString() ?: entry.state,
            style = R1.body,
            color = R1.Ink,
            modifier = Modifier.weight(1f),
        )
        StepPill(label = "+", onClick = { vm.counterIncrement(entry) })
        Spacer(Modifier.width(6.dp))
        // RESET wipes the running count and some users hang days-since-X counters
        // off these. Two-stage confirm (tap arms, second tap commits within 3s)
        // so a stray tap doesn't blow away a multi-month accumulation.
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (armed.value) R1.StatusAmber.copy(alpha = 0.28f) else R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {
                    if (armed.value) {
                        armed.value = false
                        vm.counterReset(entry)
                    } else {
                        armed.value = true
                    }
                })
                .padding(horizontal = 8.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (armed.value) "CONFIRM" else "RESET",
                style = R1.labelMicro,
                color = R1.StatusAmber,
            )
        }
    }
}

@Composable
private fun SelectControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    // Tap cycles forward; long-press cycles backward. For lists longer than a
    // few entries, that gets tedious fast; the small "···" chip opens a
    // full-list picker so users can jump directly. Short lists still benefit
    // from cycle-on-tap because no dialog round-trip is needed.
    val options = entry.options
    val currentIdx = options.indexOf(entry.state).coerceAtLeast(0)
    val showPicker = androidx.compose.runtime.remember(entry.id.value) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.AccentWarm.copy(alpha = 0.18f))
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                .r1RowPressable(
                    onTap = {
                        if (options.isNotEmpty()) {
                            val next = options[(currentIdx + 1) % options.size]
                            vm.selectOption(entry, next)
                        }
                    },
                    onLongPress = {
                        if (options.isNotEmpty()) {
                            val prevIdx = if (currentIdx == 0) options.size - 1 else currentIdx - 1
                            vm.selectOption(entry, options[prevIdx])
                        }
                    },
                )
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(text = entry.state, style = R1.body, color = R1.AccentWarm, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = "${currentIdx + 1} / ${options.size.coerceAtLeast(1)}",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        if (options.size > 3) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(
                        onClick = { showPicker.value = true },
                        contentDescription = "Pick from full list",
                    )
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            ) {
                Text(text = "···", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
    if (showPicker.value) {
        SelectOptionPicker(
            options = options,
            current = entry.state,
            label = entry.name.ifBlank { entry.id.value },
            onPick = { picked ->
                vm.selectOption(entry, picked)
                showPicker.value = false
            },
            onDismiss = { showPicker.value = false },
        )
    }
}

/**
 * Full-list option picker for input_select. Rendered as an AlertDialog so it
 * sits above the helpers screen with a scrim and a native dismiss gesture.
 * The current option is highlighted in AccentWarm; everything else reads as
 * a plain row to keep the picker visually quiet against the busy helpers list.
 */
@Composable
private fun SelectOptionPicker(
    options: List<String>,
    current: String,
    label: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            Text(
                text = label.uppercase(),
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 1,
            )
            Spacer(Modifier.height(8.dp))
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items = options, key = { it }) { opt ->
                    val isCurrent = opt == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(
                                if (isCurrent) R1.AccentWarm.copy(alpha = 0.18f) else R1.Bg,
                            )
                            .r1Pressable(onClick = { onPick(opt) })
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = opt,
                            style = R1.body,
                            color = if (isCurrent) R1.AccentWarm else R1.Ink,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(text = "•", style = R1.body, color = R1.AccentWarm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.AccentGreen.copy(alpha = 0.18f))
            .border(1.dp, R1.AccentGreen.copy(alpha = 0.5f), R1.ShapeS)
            .r1Pressable(onClick = { vm.pressButton(entry) })
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(text = "PRESS", style = R1.body, color = R1.AccentGreen)
    }
}

@Composable
private fun TimerControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val (label, color) = when (entry.state.lowercase()) {
            "active" -> "RUNNING" to R1.AccentGreen
            "paused" -> "PAUSED" to R1.StatusAmber
            "idle" -> "IDLE" to R1.InkSoft
            else -> entry.state.uppercase() to R1.InkSoft
        }
        Text(text = label, style = R1.labelMicro, color = color, modifier = Modifier.width(72.dp))
        Spacer(Modifier.width(6.dp))
        // Show either the static remaining string (paused) or a
        // ticking countdown (active). Falls through to plain text for
        // idle timers.
        if (entry.state.equals("paused", ignoreCase = true) && !entry.remaining.isNullOrBlank()) {
            Text(text = entry.remaining, style = R1.labelMicro, color = color)
        } else if (entry.state.equals("active", ignoreCase = true)) {
            RelativeTimeLabel(at = entry.finishesAt, color = color, style = R1.labelMicro)
        } else if (!entry.remaining.isNullOrBlank()) {
            Text(text = entry.remaining, style = R1.labelMicro, color = R1.InkMuted)
        }
        Spacer(Modifier.weight(1f))
        val isActive = entry.state.equals("active", ignoreCase = true)
        val isIdle = entry.state.equals("idle", ignoreCase = true)
        // START / PAUSE swap based on the live timer state; CANCEL is
        // a constant red secondary action.
        StepPill(
            label = if (isActive) "PAUSE" else "START",
            onClick = {
                vm.timerService(entry, if (isActive) "pause" else "start")
            },
        )
        if (!isIdle) {
            Spacer(Modifier.width(6.dp))
            StepPill(label = "✕", onClick = { vm.timerService(entry, "cancel") })
        }
    }
}

@Composable
private fun StepPill(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(text = label, style = R1.body, color = R1.InkSoft)
    }
}

@Composable
private fun ReadOnlyValue(value: String) {
    Text(
        text = value,
        style = R1.body,
        color = R1.Ink,
        maxLines = 2,
    )
}

private fun accentForKind(kind: HelpersViewModel.Kind): androidx.compose.ui.graphics.Color =
    when (kind) {
        HelpersViewModel.Kind.BOOLEAN -> R1.AccentWarm
        HelpersViewModel.Kind.NUMBER, HelpersViewModel.Kind.COUNTER -> R1.AccentCool
        HelpersViewModel.Kind.SELECT, HelpersViewModel.Kind.TEXT,
        HelpersViewModel.Kind.DATETIME -> R1.AccentNeutral
        HelpersViewModel.Kind.BUTTON -> R1.AccentGreen
        HelpersViewModel.Kind.TIMER -> R1.AccentWarm
        HelpersViewModel.Kind.UNKNOWN -> R1.InkMuted
    }

/** Helper kinds whose entity-id domain is on the card-stack's supported
 *  list (see `core/ha/EntityDomain.kt`). Pinning a helper of one of
 *  these kinds drops a usable card on the active page; pinning anything
 *  else (counter / timer / input_text / input_datetime) would land on
 *  the favourites list but never render because EntityId construction
 *  would silently filter it out. */
private val CARD_STACK_FRIENDLY_KINDS = setOf(
    HelpersViewModel.Kind.BOOLEAN,
    HelpersViewModel.Kind.NUMBER,
    HelpersViewModel.Kind.SELECT,
    HelpersViewModel.Kind.BUTTON,
)
