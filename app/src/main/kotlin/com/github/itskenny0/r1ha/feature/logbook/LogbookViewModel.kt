package com.github.itskenny0.r1ha.feature.logbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.LogbookEntry
import com.github.itskenny0.r1ha.core.ha.parseHaInstant
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives the Logbook (Recent Activity) surface. Pulls
 * `/api/logbook/<since>` and surfaces the result as a reverse-
 * chronological list with a single PULL-TO-REFRESH affordance.
 *
 * The 12-hour default window catches "what did the automations do
 * overnight?" without slurping a multi-megabyte payload on big HA
 * installs; the user can extend it via the WINDOW chip (12 h / 24 h /
 * 3 d) at the top of the screen.
 */
class LogbookViewModel(
    private val haRepository: HaRepository,
    private val settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
) : ViewModel() {

    enum class Window(val hours: Int, val label: String) {
        H1(1, "1 H"),
        H12(12, "12 H"),
        H24(24, "24 H"),
        D3(72, "3 D"),
        D7(168, "7 D"),
        ;

        companion object {
            /** Snap an arbitrary hours value to the nearest available
             *  chip. Used to honour the
             *  Settings → INTEGRATIONS → 'Logbook default window' value
             *  (which lets the user pick any 1..168 h) without
             *  expanding the chip vocabulary. */
            fun forHours(hours: Int): Window =
                entries.minByOrNull { kotlin.math.abs(it.hours - hours) } ?: H12
        }
    }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val window: Window = Window.H12,
        /** Full set of entries from the last fetch. [visibleEntries] applies
         *  the search filter on top so we don't have to re-fetch from HA on
         *  every keystroke. */
        val all: List<LogbookEntry> = emptyList(),
        val query: String = "",
        /** Optional entity_id filter, set via the entity picker. Null = all
         *  entities. Matches Lovelace logbook-card's per-entity scoping. */
        val entityFilter: String? = null,
        /** Optional domain filter, set via the domain chips. Null = all
         *  domains. */
        val domainFilter: String? = null,
        val error: String? = null,
        /** TAIL mode: subscribed to HA's logbook_entry event stream so new
         *  events arrive in real time and prepend to [all]. */
        val tail: Boolean = false,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Snapshot of the filter dimensions, used to key the derived flows so the
     *  filter only re-runs when one actually changes (not on, e.g., a `tail`
     *  toggle or the per-second timestamp tick). */
    private data class FilterKey(
        val all: List<LogbookEntry>,
        val query: String,
        val entity: String?,
        val domain: String?,
    )

    private val filterKey =
        _ui.map { FilterKey(it.all, it.query, it.entityFilter, it.domainFilter) }
            .distinctUntilChanged()

    /**
     * Filtered subset shown in the list, derived off Main. Hoisting it to a
     * Default-dispatched StateFlow keyed on the filter dimensions means the
     * filter runs once per data/filter change, off the main thread, with a
     * stable identity between unrelated recompositions (e.g. the per-second
     * RelativeTimeLabel ticks). Delegates to the pure [applyFilters] helper so
     * the entity / domain / text scoping stays unit-testable.
     */
    val visibleEntries: StateFlow<List<LogbookEntry>> =
        filterKey
            .map { key -> applyFilters(key.all, key.entity, key.domain, key.query) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Domains present in the current window, for the filter chip row. Derived
     *  off the full (unfiltered) set so the chips don't disappear once a domain
     *  filter is applied. */
    val domains: StateFlow<List<String>> =
        _ui.map { it.all }
            .distinctUntilChanged()
            .map { availableDomains(it) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** First fetch needs to honour the user's
     *  Settings → INTEGRATIONS → 'Logbook default window' value. Track
     *  whether we've done that snap so subsequent vm.refresh() calls
     *  don't re-snap if the user manually picked a different chip. */
    private var defaultWindowApplied = false

    fun refresh() {
        viewModelScope.launch {
            // On the very first refresh, snap the active window to the
            // closest chip for the configured default-window hours.
            if (!defaultWindowApplied) {
                val defaultHours = settings.settings.first().integrations.logbookDefaultWindowHours
                _ui.value = _ui.value.copy(window = Window.forHours(defaultHours))
                defaultWindowApplied = true
            }
            _ui.value = _ui.value.copy(loading = true, error = null)
            val window = _ui.value.window
            haRepository.fetchLogbook(hours = window.hours).fold(
                onSuccess = { entries ->
                    R1Log.i("Logbook", "loaded ${entries.size} entries (${window.label})")
                    _ui.value = _ui.value.let { current ->
                        // While TAIL is on the REST fetch is the authoritative
                        // window, but events that arrived over the live stream
                        // since the last fetch may not be in HA's response yet
                        // (the logbook endpoint lags the event bus by a moment).
                        // Merge rather than replace so a periodic AutoRefresh tick
                        // can't blank freshly-tailed rows, and dedupe so an event
                        // present in both the stream and the REST fill shows once.
                        val merged = if (current.tail) {
                            mergeLogbook(rest = entries, live = current.all)
                                .take(tailBufferCap)
                        } else {
                            entries
                        }
                        current.copy(
                            loading = false,
                            all = merged,
                            error = null,
                        )
                    }
                },
                onFailure = { t ->
                    R1Log.w("Logbook", "fetch failed: ${t.message}")
                    Toaster.error("Logbook load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        loading = false,
                        error = t.message ?: "Failed to load logbook",
                    )
                },
            )
        }
    }

    fun setWindow(window: Window) {
        if (_ui.value.window == window) return
        _ui.value = _ui.value.copy(window = window)
        refresh()
    }

    fun setQuery(query: String) {
        if (_ui.value.query == query) return
        _ui.value = _ui.value.copy(query = query)
    }

    /** Scope the feed to a single entity_id (from the picker), or clear it with
     *  null. Local-only: re-filters [all] without re-fetching from HA. */
    fun setEntityFilter(entityId: String?) {
        val next = entityId?.takeIf { it.isNotBlank() }
        if (_ui.value.entityFilter == next) return
        _ui.value = _ui.value.copy(entityFilter = next)
    }

    /** Toggle the domain filter: selecting the active domain again clears it.
     *  Local-only, like the entity filter. */
    fun setDomainFilter(domain: String?) {
        val next = domain?.takeIf { it.isNotBlank() }
        val resolved = if (_ui.value.domainFilter == next) null else next
        if (_ui.value.domainFilter == resolved) return
        _ui.value = _ui.value.copy(domainFilter = resolved)
    }

    /** Clear all local filters (entity, domain, text) in one shot. */
    fun clearFilters() {
        val s = _ui.value
        if (s.entityFilter == null && s.domainFilter == null && s.query.isEmpty()) return
        _ui.value = s.copy(entityFilter = null, domainFilter = null, query = "")
    }

    /** Live subscription to HA's logbook_entry events. Active when TAIL is on. */
    @Volatile
    private var tailSubscription: HaRepository.EventSubscription? = null

    /** Cap on the in-memory log buffer when TAIL is on. Without this a busy HA
     *  install can grow the list unbounded over an overnight tail session. */
    private val tailBufferCap = 1000

    fun setTail(enabled: Boolean) {
        if (_ui.value.tail == enabled) return
        _ui.value = _ui.value.copy(tail = enabled, error = null)
        if (enabled) startTail() else viewModelScope.launch {
            tailSubscription?.cancel()
            tailSubscription = null
        }
    }

    private fun startTail() {
        viewModelScope.launch {
            // Defensive: tear down any handle left over from a prior session so a
            // double-start (rapid toggle) can't strand the earlier subscription.
            tailSubscription?.let { stale ->
                tailSubscription = null
                runCatching { stale.cancel() }
            }
            haRepository.subscribeEvents("logbook_entry") { eventObj ->
                // logbook_entry events look like {data: {name, message, entity_id,
                // domain, state}, time_fired: ISO, ...}. parseHaInstant tolerates
                // HA's numeric-offset timestamps (the desugared Instant.parse does
                // not); fall back to "now" if HA omits time_fired.
                val data = (eventObj["data"] as? kotlinx.serialization.json.JsonObject)
                    ?: return@subscribeEvents
                fun str(key: String): String? =
                    (data[key] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val name = str("name") ?: return@subscribeEvents
                val message = str("message").orEmpty()
                val entityIdRaw = str("entity_id")
                val entityId = entityIdRaw?.let {
                    runCatching { com.github.itskenny0.r1ha.core.ha.EntityId(it) }.getOrNull()
                }
                val timeFired = (eventObj["time_fired"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.content
                val ts = timeFired?.let { parseHaInstant(it) }
                    ?: java.time.Instant.now()
                val entry = LogbookEntry(
                    timestamp = ts,
                    name = name,
                    message = message,
                    entityId = entityId,
                    domain = str("domain") ?: entityIdRaw?.substringBefore('.'),
                    state = str("state"),
                    // Best-effort context attribution to match the REST fill. The
                    // bare logbook_entry event bus payload usually omits these
                    // resolved fields, so they're commonly null on a tailed row;
                    // we still read them so any HA build that does emit them gets
                    // the same "triggered by" line as a refreshed row.
                    contextUserId = str("context_user_id"),
                    contextEntityId = str("context_entity_id"),
                    contextName = str("context_entity_id_name") ?: str("context_name"),
                )
                _ui.value = _ui.value.let { current ->
                    // Drop the event if an identical one is already buffered: HA
                    // can echo a logbook_entry that the REST fill also carried,
                    // and a flaky WS can redeliver on reconnect. Otherwise splice
                    // it in by timestamp so the newest-first invariant the day
                    // grouping and list keys rely on holds even when HA delivers
                    // an event slightly out of order (clock skew, batched flush).
                    if (current.all.any { sameLogbookEvent(it, entry) }) {
                        current
                    } else {
                        current.copy(all = insertNewestFirst(current.all, entry).take(tailBufferCap))
                    }
                }
            }.fold(
                onSuccess = { sub ->
                    // subscribeEvents suspends, so the user may have toggled TAIL
                    // back off (or onCleared may have fired) while it was in
                    // flight. If so, the subscription we just got is orphaned:
                    // cancel it now instead of stashing a handle nothing will tear
                    // down, which would leak the WS until process death.
                    if (_ui.value.tail) {
                        tailSubscription = sub
                        R1Log.i("Logbook.tail", "subscribe registered")
                    } else {
                        R1Log.i("Logbook.tail", "subscribe resolved after toggle-off; cancelling")
                        runCatching { sub.cancel() }
                    }
                },
                onFailure = { t ->
                    R1Log.w("Logbook.tail", "subscribe failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        tail = false,
                        error = "Live tail unavailable: ${t.message}",
                    )
                },
            )
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Best-effort teardown so a screen-exit doesn't leak the WS subscription.
        // onCleared runs on the main thread and viewModelScope is already cancelled
        // by the time it fires, so fire the unsubscribe on a short-lived detached IO
        // scope rather than runBlocking: blocking here would stall the UI while a
        // (possibly dead) WS round-trips for up to the socket timeout. cancel() is
        // safe to run detached because the subscription's inbound collector lives on
        // the repository's own scope, not this ViewModel's, so it survives the frame.
        val sub = tailSubscription
        tailSubscription = null
        if (sub != null) {
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { sub.cancel() }
            }
        }
    }

    /**
     * Surface the full event detail as a long-form toast — entity_id,
     * state and message, plus the absolute timestamp. The relative
     * timestamp on the row is fine for "how recent" but a user trying
     * to correlate with an HA automation needs the absolute time.
     *
     * Tap is the natural drilldown affordance even though the row
     * itself doesn't navigate anywhere — putting the toast on the
     * ToastHost's expand-on-tap path means the user can read a long
     * automation trigger message without it being clipped.
     */
    fun showDetail(entry: LogbookEntry) {
        val short = entry.entityId?.value ?: entry.name
        val full = buildString {
            append(entry.name).append('\n')
            if (entry.entityId != null) {
                append(entry.entityId.value).append('\n')
            }
            append(entry.message).append('\n')
            if (entry.state != null) append("→ ").append(entry.state).append('\n')
            // Absolute wall-clock so the user can scroll back to find what HA
            // triggered when at, e.g. "did the alarm fire at the right time?"
            append(entry.timestamp.toString())
        }
        Toaster.showExpandable(shortText = short, fullText = full)
    }

    companion object {
        fun factory(
            haRepository: HaRepository,
            settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
        ) = viewModelFactory {
            initializer { LogbookViewModel(haRepository, settings) }
        }
    }
}
