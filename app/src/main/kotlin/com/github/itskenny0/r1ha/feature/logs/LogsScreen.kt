package com.github.itskenny0.r1ha.feature.logs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
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
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
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
                LogBody(vm = vm, ui = ui, listState = listState)
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
    val freshness = if (ui.fetchedAtMillis > 0L) {
        val deltaSec = (System.currentTimeMillis() - ui.fetchedAtMillis) / 1000
        " · ${deltaSec}s ago"
    } else {
        ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "/api/error_log · $pretty$freshness",
            style = R1.labelMicro,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
        )
        if (ui.error != null) {
            Text(text = "ERROR", style = R1.labelMicro, color = R1.StatusRed)
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
            Text(text = ui.error ?: "", style = R1.labelMicro, color = R1.StatusRed)
        }
    }
}

@Composable
private fun LevelChips(
    current: LogsViewModel.Level,
    onSelect: (LogsViewModel.Level) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
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
    vm: LogsViewModel,
    ui: LogsViewModel.UiState,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    when {
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
        ui.lines.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (ui.error != null) "No log output." else "(empty body from server)",
                style = R1.body,
                color = R1.InkMuted,
            )
        }
        else -> {
            // Re-derive the filtered view only when the underlying lines or the
            // level/query filters change, not on every recomposition. The body can
            // be up to 512 KB of lines; recomputing the substring/level filter on
            // each AutoRefresh tick, keystroke, and scroll-driven recomposition was
            // scanning the whole list redundantly.
            val visible = remember(ui.lines, ui.level, ui.query) { vm.filteredLines(ui) }
            if (visible.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No lines match this filter.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS),
                ) {
                    // The monospace line style is constant; build it once rather than
                    // allocating a fresh TextStyle.copy per line per recomposition.
                    val lineStyle = remember {
                        R1.body.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = TextUnit(11f, TextUnitType.Sp),
                        )
                    }
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = R1.space.s,
                            vertical = R1.space.s,
                        ),
                    ) {
                        items(items = visible, key = { it.index }) { line ->
                            LogLineRow(line, lineStyle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LogLineRow(line: LogsViewModel.Line, style: androidx.compose.ui.text.TextStyle) {
    val accent = when (line.level) {
        LogsViewModel.Level.ERROR -> R1.StatusRed
        LogsViewModel.Level.WARN -> R1.StatusAmber
        LogsViewModel.Level.INFO -> R1.AccentCool
        LogsViewModel.Level.DEBUG -> R1.InkMuted
        else -> R1.InkSoft
    }
    Text(
        text = line.text.ifBlank { " " },
        style = style,
        color = accent,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/** Compact byte-count formatter — "5.3 KB", "412 KB", "1.1 MB". */
private fun humanBytes(n: Long): String = when {
    n < 1024 -> "$n B"
    n < 1024L * 1024L -> "%.1f KB".format(n / 1024.0)
    else -> "%.1f MB".format(n / (1024.0 * 1024.0))
}
