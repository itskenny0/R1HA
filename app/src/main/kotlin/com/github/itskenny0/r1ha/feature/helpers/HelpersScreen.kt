package com.github.itskenny0.r1ha.feature.helpers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.launch
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.formatFixed
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Helpers browser, mirrors HA's frontend Helpers configuration panel
 * (Settings, Devices & Services, Helpers).
 *
 * Each helper domain gets its own per-row control archetype:
 *   - input_boolean: ON / OFF chip (tap toggles)
 *   - input_number: -/+ stepper with current value + unit
 *   - counter: -/+ + RESET (counter only steps by the configured `step`)
 *   - input_select: tap to cycle through options (long-press shows full list)
 *   - input_text: current value + EDIT chip opening an inline text dialog
 *     (input_text.set_value, clamped to the helper's max length)
 *   - input_datetime: current value + EDIT chip opening a date / time entry
 *     dialog (input_datetime.set_datetime; only the fields the helper carries)
 *   - input_button: PRESS chip
 *   - timer: state label + START / PAUSE / CANCEL pills
 *
 * Editing covers each helper's native value via the existing HA service
 * surface; structural CRUD of the helpers themselves (create / rename /
 * delete) still lives in HA's web UI since those are WS config commands,
 * not service calls. Matches HA Companion's helpers list parity item.
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
    // entries / counts are getters on UiState that re-filter + re-count the full
    // helper set on every read. They're read in several spots per recomposition
    // (chip counts, the empty-state branch, the items() call) and recompose fires
    // on every search keystroke, so memoise both against their real inputs to run
    // the filter once per state change rather than several times per frame.
    val entries = androidx.compose.runtime.remember(ui.all, ui.bucket, ui.query) { ui.entries }
    val counts = androidx.compose.runtime.remember(ui.all) { ui.counts }
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
                // Look up against the full set, not the filtered view: the wheel
                // target is keyed by entity id and shouldn't be lost if a search
                // filter hides the row, and scanning `all` skips the filter cost.
                val entry = vm.ui.value.all.firstOrNull { it.id.value == targetId }
                    ?: return@collect
                if (entry.kind != HelpersViewModel.Kind.NUMBER) return@collect
                val value = entry.numericValue ?: return@collect
                val up = event.direction ==
                    com.github.itskenny0.r1ha.core.input.WheelEvent.Direction.UP
                // Step via the unit-tested grid-snapping path so each detent
                // lands on a value HA accepts rather than raw value +/- step.
                val next = HelpersLogic.stepNumber(value, up, entry.min, entry.max, entry.step)
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
                // REFRESH chip, same idiom the Energy / Zones /
                // Automations surfaces use. The list also auto-pulls
                // on every helper service dispatch with a 300-500 ms
                // settle, but a manual refresh is still useful after
                // an external HA change.
                R1Chip(
                    text = if (ui.loading) "…" else "REFRESH",
                    variant = R1ChipVariant.Action,
                    onClick = { vm.refresh() },
                    contentDescription = "Refresh helpers",
                )
            },
        )
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        BucketChips(current = ui.bucket, counts = counts, onSelect = { vm.setBucket(it) })
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
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Helpers load failed: ${ui.error}",
                    style = responsiveType(R1.body),
                    color = R1.StatusRed,
                )
            }
            ui.all.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No helpers defined. Add them under Settings, " +
                        "Devices & Services, Helpers in HA's web UI.",
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
            entries.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (ui.query.isNotBlank()) "No matches for '${ui.query}'."
                    else "No helpers in '${ui.bucket.label}'.",
                    style = responsiveType(R1.body),
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
                        contentPadding = PaddingValues(
                            horizontal = R1.space.m, vertical = R1.space.s,
                        ),
                        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    ) {
                        items(items = entries, key = { it.id.value }) { entry ->
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
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        HelpersViewModel.Bucket.entries.forEach { bucket ->
            val count = counts[bucket] ?: 0
            // Hide empty per-kind chips (except ALL) so the strip stays
            // tight on small installs.
            if (count == 0 && bucket != HelpersViewModel.Bucket.ALL) return@forEach
            R1Chip(
                text = "${bucket.label}  $count",
                variant = R1ChipVariant.Filter,
                selected = bucket == current,
                onClick = { onSelect(bucket) },
            )
        }
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
                placeholder = "kitchen, away, …",
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
    // Helper rows are bespoke control cards (each domain renders a different
    // per-kind affordance below the header), so they stay hand-rolled rather
    // than collapsing to R1Row. They adopt the canonical boxed surface and the
    // spacing scale so they read flush with the rest of the app.
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
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Leading per-domain glyph from the in-house icon set, tinted to
            // the kind's accent so the eye can scan by type as well as by the
            // grouped sort order.
            Icon(
                imageVector = R1Icons.forEntity(entry.id.value),
                contentDescription = null,
                tint = accentForKind(entry.kind),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = entry.name,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
                Spacer(Modifier.width(R1.space.s))
            }
            // ☆ pin-to-favourites, only for helpers whose entity_id
            // domain is recognised by the card stack (input_boolean
            // renders as SwitchCard, input_number as a scalar slider,
            // input_select as SelectCard, input_button as ActionCard).
            // counter / timer / input_text / input_datetime aren't on
            // the card stack's supported-domain list yet, so the star
            // would silently no-op for those, hide it instead of
            // misleading the user.
            if (entry.kind in CARD_STACK_FRIENDLY_KINDS) {
                Box(
                    modifier = Modifier
                        .size(R1.MinTarget)
                        .r1Pressable(
                            onClick = { vm.addToFavorites(entry) },
                            contentDescription = if (isFavorite) {
                                "Pinned to favourites"
                            } else {
                                "Pin to favourites"
                            },
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (isFavorite) "★" else "☆",
                        style = R1.labelMicro,
                        color = if (isFavorite) R1.AccentWarm else R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.width(R1.space.xs))
            R1Chip(
                text = entry.kind.name,
                variant = R1ChipVariant.Pill,
                tone = accentForKind(entry.kind),
            )
        }
        Text(
            text = entry.id.value,
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(R1.space.s))
        // Per-kind control row, branches on entry.kind so each domain
        // gets the affordance that fits HA's native semantic. When the helper
        // is reporting unavailable / unknown we show the sentinel read-only
        // rather than an interactive control, mirroring HA which disables the
        // control: stepping / toggling / selecting against an entity that
        // isn't reporting would only no-op or surface an error toast.
        if (entry.inactive) {
            UnavailableValue(entry.state)
            return@Column
        }
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
            HelpersViewModel.Kind.TEXT -> TextControl(entry, vm)
            HelpersViewModel.Kind.DATETIME -> DateTimeControl(entry, vm)
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
        R1Chip(
            text = if (isOn) "ON" else "OFF",
            variant = R1ChipVariant.Filter,
            selected = isOn,
            tone = R1.AccentGreen,
            onClick = { vm.toggleBoolean(entry) },
            contentDescription = if (isOn) "Turn ${entry.name} off" else "Turn ${entry.name} on",
        )
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
            // Step via the grid-snapping logic so off-grid bases still land on
            // a value HA accepts; setNumber re-clamps as a backstop.
            if (value != null) vm.setNumber(
                entry,
                HelpersLogic.stepNumber(value, up = false, min = entry.min, max = entry.max, step = entry.step),
            )
        })
        Spacer(Modifier.width(R1.space.s))
        // Live value display, formatted as integer when the step is
        // whole, otherwise as a one-decimal float so 0.5° helpers
        // don't get rounded to an unhelpful integer. Tap toggles wheel
        // stepping for this row so the user can dial a value with the
        // R1 wheel instead of repeatedly hitting +/-. Visual accent
        // matches the row's WHEEL chip / border highlight.
        val formatted = when {
            value == null -> entry.state
            step % 1.0 == 0.0 -> "${value.toInt()}"
            else -> formatFixed(value, 1)
        }
        val withUnit = if (entry.unit.isNullOrBlank()) formatted else "$formatted ${entry.unit}"
        Text(
            text = withUnit,
            style = responsiveType(R1.bodyEmph),
            color = if (isWheelActive) R1.AccentWarm else R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .r1Pressable(
                    onClick = onToggleWheel,
                    contentDescription = if (isWheelActive)
                        "Stop wheel stepping" else "Use wheel to step this value",
                )
                .padding(vertical = R1.space.xs),
        )
        StepPill(label = "+", onClick = {
            if (value != null) vm.setNumber(
                entry,
                HelpersLogic.stepNumber(value, up = true, min = entry.min, max = entry.max, step = entry.step),
            )
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
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = value?.toInt()?.toString() ?: entry.state,
            style = responsiveType(R1.bodyEmph),
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        StepPill(label = "+", onClick = { vm.counterIncrement(entry) })
        Spacer(Modifier.width(R1.space.s))
        // RESET wipes the running count and some users hang days-since-X counters
        // off these. Two-stage confirm (tap arms, second tap commits within 3s)
        // so a stray tap doesn't blow away a multi-month accumulation.
        R1Chip(
            text = if (armed.value) "CONFIRM" else "RESET",
            variant = R1ChipVariant.Action,
            selected = armed.value,
            tone = R1.StatusAmber,
            onClick = {
                if (armed.value) {
                    armed.value = false
                    vm.counterReset(entry)
                } else {
                    armed.value = true
                }
            },
            contentDescription = "Reset ${entry.name}",
        )
    }
}

@Composable
private fun SelectControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    // Tap cycles forward; long-press cycles backward. For lists longer than a
    // few entries, that gets tedious fast; the small "···" chip opens a
    // full-list picker so users can jump directly. Short lists still benefit
    // from cycle-on-tap because no dialog round-trip is needed.
    val options = entry.options
    // -1 when the current state isn't one of the helper's options (a fresh
    // helper reads `unknown`, or options were edited out). Distinct from index
    // 0 so a forward tap lands on options[0] instead of skipping it, and the
    // position counter reads 0 / N rather than a misleading 1 / N.
    val currentIdx = HelpersLogic.selectCurrentIndex(entry.state, options)
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
                            // From "no current selection" (-1) the first tap
                            // should land on options[0]; from a valid index it
                            // advances normally with wraparound.
                            val nextIdx = if (currentIdx < 0) 0
                            else HelpersLogic.cycleSelectIndex(currentIdx, options.size, forward = true)
                            vm.selectOption(entry, options[nextIdx])
                        }
                    },
                    onLongPress = {
                        if (options.isNotEmpty()) {
                            val prevIdx = if (currentIdx < 0) options.size - 1
                            else HelpersLogic.cycleSelectIndex(currentIdx, options.size, forward = false)
                            vm.selectOption(entry, options[prevIdx])
                        }
                    },
                )
                .heightIn(min = R1.space.xxl)
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = entry.state,
                style = responsiveType(R1.bodyEmph),
                color = R1.AccentWarm,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.semantics {
                    contentDescription =
                        "Current option ${entry.state}, tap for next, long-press for previous"
                },
            )
        }
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = "${currentIdx + 1} / ${options.size.coerceAtLeast(1)}",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
        )
        // Always offer the full-list picker, even for 2-3 option selects:
        // long-press (cycle backward) is undiscoverable on its own, so the
        // picker chip is the visible affordance for jumping to any option.
        if (options.isNotEmpty()) {
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = "···",
                variant = R1ChipVariant.Action,
                onClick = { showPicker.value = true },
                contentDescription = "Pick from full list",
            )
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
 * Full-list option picker for input_select. Rendered as a Dialog so it
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
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
        ) {
            Text(
                text = label.uppercase(),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 1,
            )
            Spacer(Modifier.height(R1.space.s))
            androidx.compose.foundation.lazy.LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp),
                verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
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
                            .heightIn(min = R1.MinTarget)
                            .padding(horizontal = R1.space.m, vertical = R1.space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = opt,
                            style = responsiveType(R1.body),
                            color = if (isCurrent) R1.AccentWarm else R1.Ink,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (isCurrent) {
                            Text(text = "•", style = responsiveType(R1.body), color = R1.AccentWarm)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ButtonControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        R1Chip(
            text = "PRESS",
            variant = R1ChipVariant.Action,
            selected = true,
            tone = R1.AccentGreen,
            onClick = { vm.pressButton(entry) },
            contentDescription = "Press ${entry.name}",
        )
        // input_button's state is the ISO timestamp of the last press; surface
        // it as a relative "pressed N ago" the way HA shows last-changed. Never
        // pressed (state was `unknown`) parses to null and the label hides.
        if (entry.pressedAt != null) {
            Spacer(Modifier.width(R1.space.s))
            Text(text = "pressed", style = R1.labelMicro, color = R1.InkMuted)
            Spacer(Modifier.width(R1.space.xs))
            RelativeTimeLabel(at = entry.pressedAt, color = R1.InkSoft, style = R1.labelMicro)
        }
    }
}

@Composable
private fun TextControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val showEditor = androidx.compose.runtime.remember(entry.id.value) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    // HA renders password-mode input_text as a masked field; mirror that on
    // the read row so a stored secret isn't shown in the clear on a list that
    // a glance can take in. The editor still works on the real value.
    val isPassword = entry.mode.equals("password", ignoreCase = true)
    val display = when {
        entry.state.isBlank() -> "(empty)"
        isPassword -> HelpersLogic.maskText(entry.state)
        else -> entry.state
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = display,
            style = responsiveType(R1.body),
            color = if (entry.state.isBlank()) R1.InkSoft else R1.Ink,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(
            text = "EDIT",
            variant = R1ChipVariant.Action,
            onClick = { showEditor.value = true },
            contentDescription = "Edit ${entry.name}",
        )
    }
    if (showEditor.value) {
        TextEditDialog(
            initial = entry.state,
            label = entry.name.ifBlank { entry.id.value },
            min = entry.textMin,
            max = entry.textMax,
            onConfirm = { value ->
                vm.setText(entry, value)
                showEditor.value = false
            },
            onDismiss = { showEditor.value = false },
        )
    }
}

/**
 * Inline single-line editor for an input_text helper. Live character count
 * against the helper's max; the SAVE chip disables when the value is shorter
 * than the configured min (HA would reject it). Auto-focuses on open so the
 * keyboard appears without a stray tap.
 */
@Composable
private fun TextEditDialog(
    initial: String,
    label: String,
    min: Int?,
    max: Int?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val draft = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initial)
    }
    val focusRequester = androidx.compose.runtime.remember {
        androidx.compose.ui.focus.FocusRequester()
    }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    val meetsMin = HelpersLogic.textMeetsMinLength(draft.value, min)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
        ) {
            Text(
                text = label.uppercase(),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 1,
            )
            Spacer(Modifier.height(R1.space.s))
            R1TextField(
                value = draft.value,
                onValueChange = { draft.value = HelpersLogic.clampText(it, min, max) },
                monospace = false,
                focusRequester = focusRequester,
            )
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = "${draft.value.length} / ${max ?: 100}" +
                    if (min != null && min > 0) "  (min $min)" else "",
                style = responsiveType(R1.labelMicro),
                color = if (meetsMin) R1.InkSoft else R1.StatusAmber,
            )
            Spacer(Modifier.height(R1.space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                R1Chip(
                    text = "CANCEL",
                    variant = R1ChipVariant.Action,
                    onClick = onDismiss,
                    contentDescription = "Cancel edit",
                )
                R1Chip(
                    text = "SAVE",
                    variant = R1ChipVariant.Action,
                    selected = meetsMin,
                    tone = R1.AccentGreen,
                    onClick = { if (meetsMin) onConfirm(draft.value) },
                    contentDescription = "Save value",
                )
            }
        }
    }
}

@Composable
private fun DateTimeControl(entry: HelpersViewModel.Entry, vm: HelpersViewModel) {
    val showEditor = androidx.compose.runtime.remember(entry.id.value) {
        androidx.compose.runtime.mutableStateOf(false)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = entry.state.ifBlank { "(unset)" },
            style = responsiveType(R1.body),
            color = if (entry.state.isBlank()) R1.InkSoft else R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(
            text = "EDIT",
            variant = R1ChipVariant.Action,
            onClick = { showEditor.value = true },
            contentDescription = "Edit ${entry.name}",
        )
    }
    if (showEditor.value) {
        val (initialDate, initialTime) = HelpersLogic.splitDateTimeState(
            entry.state, entry.hasDate, entry.hasTime,
        )
        DateTimeEditDialog(
            label = entry.name.ifBlank { entry.id.value },
            hasDate = entry.hasDate,
            hasTime = entry.hasTime,
            initialDate = initialDate.orEmpty(),
            initialTime = initialTime.orEmpty(),
            onConfirm = { date, time ->
                vm.setDateTime(entry, date.ifBlank { null }, time.ifBlank { null })
                showEditor.value = false
            },
            onDismiss = { showEditor.value = false },
        )
    }
}

/**
 * Date / time entry dialog for an input_datetime helper. Shows a YYYY-MM-DD
 * field and / or an HH:MM[:SS] field depending on which components the helper
 * carries. SAVE disables until every shown field validates so we never spend
 * a round-trip on input HA would reject. Typed rather than picker-driven: a
 * native date/time picker overlay is heavy on the R1's small display and the
 * field is usually a quick correction.
 */
@Composable
private fun DateTimeEditDialog(
    label: String,
    hasDate: Boolean,
    hasTime: Boolean,
    initialDate: String,
    initialTime: String,
    onConfirm: (date: String, time: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val date = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initialDate)
    }
    val time = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initialTime)
    }
    val dateOk = !hasDate || HelpersLogic.isValidDate(date.value)
    val timeOk = !hasTime || HelpersLogic.isValidTime(time.value)
    val canSave = dateOk && timeOk && (hasDate || hasTime)
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
        ) {
            Text(
                text = label.uppercase(),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 1,
            )
            if (hasDate) {
                Spacer(Modifier.height(R1.space.s))
                Text(text = "DATE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
                Spacer(Modifier.height(R1.space.xxs))
                R1TextField(
                    value = date.value,
                    onValueChange = { date.value = it.trim() },
                    placeholder = "2024-01-15",
                    isError = date.value.isNotBlank() && !HelpersLogic.isValidDate(date.value),
                )
            }
            if (hasTime) {
                Spacer(Modifier.height(R1.space.s))
                Text(text = "TIME", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
                Spacer(Modifier.height(R1.space.xxs))
                R1TextField(
                    value = time.value,
                    onValueChange = { time.value = it.trim() },
                    placeholder = "14:30:00",
                    isError = time.value.isNotBlank() && !HelpersLogic.isValidTime(time.value),
                )
            }
            Spacer(Modifier.height(R1.space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                R1Chip(
                    text = "CANCEL",
                    variant = R1ChipVariant.Action,
                    onClick = onDismiss,
                    contentDescription = "Cancel edit",
                )
                R1Chip(
                    text = "SAVE",
                    variant = R1ChipVariant.Action,
                    selected = canSave,
                    tone = R1.AccentGreen,
                    onClick = { if (canSave) onConfirm(date.value, time.value) },
                    contentDescription = "Save date and time",
                )
            }
        }
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
        Spacer(Modifier.width(R1.space.s))
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
            Spacer(Modifier.width(R1.space.s))
            StepPill(label = "✕", onClick = { vm.timerService(entry, "cancel") })
        }
    }
}

@Composable
private fun StepPill(label: String, onClick: () -> Unit) {
    // Bespoke -/+/timer pill: same surface/border treatment as R1Chip's
    // unselected Action state but on a square 48dp target so the wheel-tap
    // hit area on stepper controls stays comfortable.
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = label)
            .size(R1.MinTarget),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.bodyEmph, color = R1.InkSoft)
    }
}

@Composable
private fun ReadOnlyValue(value: String) {
    Text(
        text = value,
        style = responsiveType(R1.body),
        color = R1.Ink,
        maxLines = 2,
    )
}

/**
 * Read-only stand-in for a helper that isn't reporting an actionable value
 * (unavailable / unknown / blank). Muted ink + a clear word so the row reads
 * as intentionally inert rather than a control that silently does nothing.
 */
@Composable
private fun UnavailableValue(state: String) {
    val label = when (state.lowercase()) {
        "unavailable" -> "UNAVAILABLE"
        "" -> "UNKNOWN"
        else -> state.uppercase()
    }
    Text(
        text = label,
        style = R1.labelMicro,
        color = R1.InkMuted,
        maxLines = 1,
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
