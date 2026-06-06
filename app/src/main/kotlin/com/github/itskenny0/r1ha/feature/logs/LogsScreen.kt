package com.github.itskenny0.r1ha.feature.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Full Logs viewer. Replaces the dependence on HA's WebView log panel
 * with a native scroller that streams tail-bounded log content, parses
 * level prefixes for chip-driven filtering, and supports substring
 * search + clipboard copy.
 *
 * The whole point of this screen is to render a multi-hundred-KB log
 * without OOM-ing on the R1's small heap. We never put the full body
 * into a single text field; the LazyColumn renders one row per line and
 * recycles aggressively so the heap pressure stays proportional to the
 * visible window, not the total log size.
 */
@Composable
fun LogsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: LogsViewModel = viewModel(factory = LogsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    // Derive the filtered view once here so the scroll-to-tail target and the
    // body render off the same list. Re-derive only when the underlying lines
    // or the level/query filters change, not on every recomposition: the body
    // can be up to 512 KB of lines and re-scanning it per keystroke/scroll tick
    // was wasteful.
    val visible = remember(ui.lines, ui.level, ui.query) { vm.filteredLines(ui) }

    // A log viewer is most useful pinned to the newest line. We follow the tail
    // automatically as long as the user is already parked at the bottom; if the
    // user has scrolled up to read older context we leave their position
    // untouched and surface a "NEWEST" jump affordance instead (mirrors HA's
    // new-logs-indicator). atBottom is derived so it only recomputes when the
    // relevant layout fields actually change, not on every scroll pixel.
    val atBottom by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()
            // No items rendered yet (treat as bottom for first-load follow), or
            // the last visible item is the last item in the list.
            last == null || last.index >= info.totalItemsCount - 1
        }
    }
    // Re-anchor to the tail whenever a fresh fetch lands while the user is at
    // the bottom. Keyed on the fetch timestamp AND the filtered size so the
    // scroll fires after the new items have been handed to the LazyColumn
    // (scrolling to a stale totalItemsCount would land short of the real tail).
    // Typing in the filter or toggling a chip keeps fetchedAtMillis fixed, so
    // those recompositions never yank the scroll position.
    LaunchedEffect(ui.fetchedAtMillis, visible.size) {
        if (ui.fetchedAtMillis > 0L && atBottom && visible.isNotEmpty()) {
            runCatching { listState.scrollToItem(visible.lastIndex) }
        }
    }
    if (ui.autoRefresh) {
        // 10s cadence — fast enough to surface a freshly-logged error
        // while debugging an integration, slow enough that the R1 isn't
        // burning the link for no reason if the user leaves the screen
        // open in the background. AutoRefresh pauses on lifecycle pause
        // so backgrounded screens stop polling.
        AutoRefresh(everyMillis = 10_000L) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "LOGS",
            onBack = onBack,
            action = {
                Row(
                    // On the R1 panel the title plus up to four action chips outruns the
                    // bar width; let the chip cluster pan horizontally so every chip stays
                    // reachable instead of clipping off the right edge. Roomier tiers never
                    // overflow, so the scroll is a no-op there.
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    // NEWEST chip — jump back to the tail after scrolling up
                    // to read older context. Only shown when there is content
                    // and the user has actually scrolled away from the bottom,
                    // so it doesn't clutter the bar in the common pinned case.
                    if (visible.isNotEmpty() && !atBottom) {
                        R1Chip(
                            text = "NEWEST",
                            variant = R1ChipVariant.Action,
                            onClick = {
                                scope.launch {
                                    runCatching { listState.animateScrollToItem(visible.lastIndex) }
                                }
                            },
                            contentDescription = "Scroll to newest log line",
                        )
                    }
                    // COPY chip — hand the currently-filtered text to the
                    // clipboard. Filter-aware so a "copy only the errors"
                    // workflow is one tap rather than a manual selection.
                    if (ui.lines.isNotEmpty()) {
                        R1Chip(
                            text = "COPY",
                            variant = R1ChipVariant.Action,
                            onClick = {
                                clipboard.setText(AnnotatedString(vm.copyableText()))
                                Toaster.show("Copied")
                            },
                            contentDescription = "Copy log to clipboard",
                        )
                    }
                    R1Chip(
                        text = if (ui.autoRefresh) "AUTO·ON" else "AUTO",
                        variant = R1ChipVariant.Action,
                        selected = ui.autoRefresh,
                        onClick = { vm.toggleAutoRefresh() },
                        contentDescription = "Toggle auto refresh",
                    )
                    R1Chip(
                        text = if (ui.loading) "…" else "REFRESH",
                        variant = R1ChipVariant.Action,
                        onClick = { vm.refresh() },
                        contentDescription = "Refresh log",
                    )
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize().padding(horizontal = R1.space.m, vertical = R1.space.s)) {
                SizeHint(ui)
                Spacer(Modifier.size(R1.space.s))
                LevelChips(current = ui.level, onSelect = { vm.setLevel(it) })
                Spacer(Modifier.size(R1.space.s))
                SearchField(query = ui.query, onChange = { vm.setQuery(it) })
                Spacer(Modifier.size(R1.space.s))
                LogBody(ui = ui, visible = visible, listState = listState)
            }
        }
    }
}

@Composable
private fun SizeHint(ui: LogsViewModel.UiState) {
    val total = ui.totalBytes
    val shown = ui.shownBytes
    val pretty = when {
        total <= 0L -> "—"
        ui.truncated -> "showing last ${humanBytes(shown)} of ${humanBytes(total)}"
        else -> "${humanBytes(total)} total"
    }
    // Bucketed, live-ticking relative time ("just now" / "5m ago" / "1h ago") via the
    // app-wide formatter, rather than a raw seconds delta that read absurdly once the fetch
    // aged past a minute ("3600s ago" instead of "1h ago").
    val fetchedInstant = if (ui.fetchedAtMillis > 0L) {
        java.time.Instant.ofEpochMilli(ui.fetchedAtMillis)
    } else {
        null
    }
    val rel = com.github.itskenny0.r1ha.ui.components.rememberRelativeTime(fetchedInstant)
    val freshness = if (rel.isNotEmpty()) " · $rel" else ""
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "/api/error_log · $pretty$freshness",
            style = responsiveType(R1.labelMicro),
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
        )
        if (ui.error != null) {
            Text(text = "ERROR", style = responsiveType(R1.labelMicro), color = R1.StatusRed)
        }
    }
    if (ui.error != null) {
        Spacer(Modifier.size(R1.space.xs))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.StatusRed.copy(alpha = 0.12f))
                .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(text = ui.error ?: "", style = responsiveType(R1.labelMicro), color = R1.StatusRed)
        }
    }
}

@Composable
private fun LevelChips(
    current: LogsViewModel.Level,
    onSelect: (LogsViewModel.Level) -> Unit,
) {
    Row(
        // Five level chips do not fit the R1 panel width side by side; pan the row
        // horizontally so every filter stays tappable rather than clipping at the edge.
        // On wider tiers the chips fit and the scroll never engages.
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        LogsViewModel.Level.entries.forEach { lvl ->
            R1Chip(
                text = lvl.label,
                variant = R1ChipVariant.Filter,
                selected = lvl == current,
                onClick = { onSelect(lvl) },
                contentDescription = "Filter to ${lvl.label}",
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(
                value = query,
                onValueChange = onChange,
                placeholder = "filter substring (case-insensitive)…",
                monospace = false,
            )
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(R1.space.s))
            R1Chip(
                text = "CLEAR",
                variant = R1ChipVariant.Action,
                onClick = { onChange("") },
                contentDescription = "Clear filter",
            )
        }
    }
}

@Composable
private fun LogBody(
    ui: LogsViewModel.UiState,
    visible: List<LogsViewModel.Line>,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    when {
        // LOADING: nothing fetched yet (or a refresh while the buffer is empty).
        ui.loading && ui.lines.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
        }
        // ERROR: the fetch failed and we have nothing to show. The red banner in
        // SizeHint carries the message; this just states the body is unavailable.
        ui.lines.isEmpty() && ui.error != null -> EmptyState(
            text = "No log output.\nThe error log could not be loaded.",
        )
        // CLEAN: the fetch succeeded but the log body was empty. On a healthy HA
        // install this is the normal case, so frame it positively rather than as
        // a fault (matches HA's "no errors logged" copy).
        ui.lines.isEmpty() && ui.loadedOnce -> EmptyState(
            text = "Log is clean.\nNo warnings or errors logged.",
        )
        // CLEAN (pre-load fallback): empty before the first fetch resolves.
        ui.lines.isEmpty() -> EmptyState(text = "No log output yet.")
        // CONTENT present but the active filter excludes everything.
        visible.isEmpty() -> EmptyState(
            text = if (ui.query.isNotBlank()) {
                "No lines match \"${ui.query}\"."
            } else {
                "No ${ui.level.label} lines in this log."
            },
        )
        else -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS),
            ) {
                // The monospace line style is constant; build it once rather than
                // allocating a fresh TextStyle.copy per line per recomposition. The 11sp
                // size is deliberately kept fixed across tiers: a log is read as a fixed
                // mono grid, and scaling it would reflow the columns the reader is scanning.
                val lineStyle = remember {
                    R1.body.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = TextUnit(11f, TextUnitType.Sp),
                    )
                }
                // One shared horizontal-scroll state for every row so long lines pan as a
                // single grid rather than wrapping. On the narrow R1 panel this keeps a
                // long stack-trace line readable end to end; on wide tiers the lines simply
                // fit and the pan never engages.
                val lineScroll = rememberScrollState()
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.s,
                        vertical = R1.space.s,
                    ),
                ) {
                    items(items = visible, key = { it.index }) { line ->
                        LogLineRow(line, lineStyle, lineScroll)
                    }
                }
            }
        }
    }
}

/** Centered muted-ink message used by the empty / clean / error / no-match
 *  states so they share one styling treatment. */
@Composable
private fun EmptyState(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = responsiveType(R1.body),
            color = R1.InkMuted,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun LogLineRow(
    line: LogsViewModel.Line,
    style: androidx.compose.ui.text.TextStyle,
    scrollState: androidx.compose.foundation.ScrollState,
) {
    val accent = when (line.level) {
        LogsViewModel.Level.ERROR -> R1.StatusRed
        LogsViewModel.Level.WARN -> R1.StatusAmber
        LogsViewModel.Level.INFO -> R1.AccentCool
        LogsViewModel.Level.DEBUG -> R1.InkMuted
        else -> R1.InkSoft
    }
    // Keep each line on one row and let the shared horizontal scroll pan past the panel
    // edge instead of wrapping a 200-char stack-trace line into a ragged paragraph that
    // is far harder to scan. softWrap = false preserves the mono column alignment.
    Text(
        text = line.text.ifBlank { " " },
        style = style,
        color = accent,
        softWrap = false,
        modifier = Modifier.horizontalScroll(scrollState).padding(vertical = 1.dp),
    )
}

/** Compact byte-count formatter — "5.3 KB", "412 KB", "1.1 MB". */
private fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024L -> "%.1f KB".format(java.util.Locale.US, n / 1024.0)
    else -> "%.1f MB".format(java.util.Locale.US, n / (1024.0 * 1024.0))
}
