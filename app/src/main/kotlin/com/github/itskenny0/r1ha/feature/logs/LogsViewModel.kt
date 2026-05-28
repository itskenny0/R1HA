package com.github.itskenny0.r1ha.feature.logs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the full Logs viewer. Pulls `/api/error_log` through
 * [HaRepository.fetchErrorLogFull], which streams the body and keeps
 * only the tail capped at [TAIL_CAP_BYTES]; the System Health screen
 * caps at 32 KB, but a power-user looking for what's actually broken
 * wants more context.
 *
 * Filtering + searching happen in-VM on a pre-split `List<Line>` so
 * the screen renders the visible subset through a [LazyColumn] rather
 * than handing 512 KB to a single TextField (which is exactly how the
 * old SystemHealth log panel OOM'd on the R1's 512MB heap).
 */
class LogsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /** Levels we parse out of the standard HA log-line prefix. ALL is the
     *  default and means "no level filter". The order matters — the chips
     *  render in declaration order. */
    enum class Level(val label: String) {
        ALL("ALL"),
        ERROR("ERROR"),
        WARN("WARN"),
        INFO("INFO"),
        DEBUG("DEBUG"),
    }

    @androidx.compose.runtime.Stable
    data class Line(
        /** Stable index for LazyColumn key. */
        val index: Int,
        /** Parsed level, or null when the line didn't match the standard
         *  HA prefix (continuation lines, stack traces, etc.). */
        val level: Level?,
        /** Raw line text including any timestamp / logger / level prefix. */
        val text: String,
    )

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        /** Every line read from the server. Kept whole so toggling filters
         *  is instant and doesn't require re-fetching from HA. */
        val lines: List<Line> = emptyList(),
        /** Bytes the server actually had — drives the "showing last N of
         *  M" hint. Equals [shownBytes] when nothing was truncated. */
        val totalBytes: Long = 0L,
        /** Bytes currently in [lines] (the tail we received). */
        val shownBytes: Long = 0L,
        /** True when the server's full body exceeded [TAIL_CAP_BYTES]. */
        val truncated: Boolean = false,
        val level: Level = Level.ALL,
        val query: String = "",
        /** Live-updating refresh toggle. When on, the VM pulls the log on
         *  a 10s cadence; switching off only stops the next tick. */
        val autoRefresh: Boolean = false,
        val error: String? = null,
        /** Last-fetched wall-clock millis. Drives the "fetched 12 s ago"
         *  hint in the header. 0 = nothing fetched yet. */
        val fetchedAtMillis: Long = 0L,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Lines after level + substring filters applied. Re-derived on every
     *  ui state change; cheap because parsing already happened on fetch. */
    fun filteredLines(state: UiState = _ui.value): List<Line> {
        val q = state.query.trim().lowercase()
        val needsLevel = state.level != Level.ALL
        if (!needsLevel && q.isBlank()) return state.lines
        return state.lines.filter { l ->
            (!needsLevel || l.level == state.level) &&
                (q.isBlank() || l.text.lowercase().contains(q))
        }
    }

    fun setLevel(level: Level) {
        _ui.value = _ui.value.copy(level = level)
    }

    fun setQuery(query: String) {
        _ui.value = _ui.value.copy(query = query)
    }

    fun toggleAutoRefresh() {
        _ui.value = _ui.value.copy(autoRefresh = !_ui.value.autoRefresh)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.fetchErrorLogFull(maxBytes = TAIL_CAP_BYTES).fold(
                onSuccess = { tail ->
                    val parsed = parseLines(tail.body)
                    R1Log.i(
                        "Logs",
                        "fetched bytes=${tail.totalBytes} tail=${tail.body.length} " +
                            "truncated=${tail.truncated} lines=${parsed.size}",
                    )
                    _ui.value = _ui.value.copy(
                        loading = false,
                        lines = parsed,
                        totalBytes = tail.totalBytes,
                        shownBytes = tail.body.length.toLong(),
                        truncated = tail.truncated,
                        error = null,
                        fetchedAtMillis = System.currentTimeMillis(),
                    )
                },
                onFailure = { t ->
                    R1Log.w("Logs", "fetch failed: ${t.message}")
                    Toaster.error("Log load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    /** Concat the visible lines back to a single string so the COPY chip
     *  can hand it to the system clipboard. Filter-aware — copies what the
     *  user can see, not the unfiltered tail. */
    fun copyableText(): String = filteredLines().joinToString(separator = "\n") { it.text }

    companion object {
        /** 512 KB tail keeps memory bounded on the R1's 512MB heap while
         *  still being meaningful for debugging. The OOM-bug fix in
         *  fetchErrorLogFull's underlying stream-tail helper means even a
         *  multi-MB body never lands in memory whole. */
        const val TAIL_CAP_BYTES = 512 * 1024

        /** Parse the level out of HA's log prefix. HA uses Python
         *  logging's default format, which puts the level after a
         *  timestamp + a space; the levels we surface are the five
         *  Python ones HA emits. We accept either upper or lowercase
         *  (a custom integration occasionally emits the latter). */
        private val LEVEL_PATTERN = Regex(
            "^\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.?\\d* (ERROR|WARNING|INFO|DEBUG|CRITICAL)\\b",
            RegexOption.IGNORE_CASE,
        )

        private fun parseLines(body: String): List<Line> {
            // splitToSequence avoids materialising a full intermediate list;
            // we index into the result anyway for stable LazyColumn keys.
            val raw = body.split('\n')
            return raw.mapIndexed { idx, text ->
                val match = LEVEL_PATTERN.find(text)?.groupValues?.getOrNull(1)?.uppercase()
                val level = when (match) {
                    "ERROR", "CRITICAL" -> Level.ERROR
                    "WARNING" -> Level.WARN
                    "INFO" -> Level.INFO
                    "DEBUG" -> Level.DEBUG
                    else -> null
                }
                Line(index = idx, level = level, text = text)
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { LogsViewModel(haRepository) }
        }
    }
}
