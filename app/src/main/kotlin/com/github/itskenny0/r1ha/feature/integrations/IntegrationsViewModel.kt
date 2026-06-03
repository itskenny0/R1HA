package com.github.itskenny0.r1ha.feature.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.DeviceInfo
import com.github.itskenny0.r1ha.core.ha.EntityRegistryEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Drives the native Integrations browser. Fetches HA's
 * `config_entries/get` once, groups by domain, and exposes the per-row
 * reload action. Best-effort device / entity counts per domain are
 * resolved from the device + entity registries when those calls succeed;
 * they degrade silently to zero if a registry fetch fails so the entry
 * list always renders.
 *
 * Setup / removal / options flows live in HA's web UI; this surface is
 * the operational "view + kick" companion.
 */
class IntegrationsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Filter { ALL, LOADED, PENDING, FAILED }

    /** Resolvable counts for a single integration domain. Counts are per
     *  domain, not per entry: HA's slim registry models the repo exposes
     *  don't carry the `config_entry_id` link, so a domain with two
     *  entries shares one total. */
    data class DomainCounts(val devices: Int, val entities: Int)

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<ConfigEntry> = emptyList(),
        val filter: Filter = Filter.ALL,
        /** Free-text search across domain + title. */
        val query: String = "",
        /** Per-domain device / entity counts keyed by domain, resolved
         *  best-effort from the registries. Empty when neither registry
         *  has loaded yet (or both failed). */
        val countsByDomain: Map<String, DomainCounts> = emptyMap(),
        /** Entry ids currently being reloaded. Reloads can take 5-15s on
         *  larger integrations (Z-Wave, Zigbee); the row chip flips to
         *  a spinner while the deferred is in-flight so the user knows
         *  the tap registered. */
        val reloadingIds: Set<String> = emptySet(),
        val error: String? = null,
    ) {
        /** Entries after [filter] and [query] are applied. */
        val visible: List<ConfigEntry>
            get() = all
                .filter { matchesFilter(it, filter) }
                .filter { matchesQuery(it, query) }

        /** Visible entries grouped by domain, alphabetical sections,
         *  domains sorted by the integration's title within. */
        val sections: List<Pair<String, List<ConfigEntry>>>
            get() = groupByDomain(visible)

        /** Count by bucket for the chip badges, computed off the
         *  unfiltered set so toggling between filters doesn't change the
         *  surfaced totals. */
        val loadedCount: Int get() = all.count { it.state.equals("loaded", ignoreCase = true) }
        val failedCount: Int get() = all.count { stateRank(it.state) == StateBucket.FAILED }

        /** Entries mid-flight or inert (setup_in_progress / not_loaded). HA's
         *  own list makes a stuck "SETTING UP" entry easy to lose; surfacing
         *  this count + a PENDING filter makes it findable. Disabled entries
         *  report not_loaded but are framed by their disabled cause, not as a
         *  pending fault, so they're excluded. */
        val pendingCount: Int get() = all.count {
            it.disabledBy == null && stateRank(it.state) == StateBucket.PENDING
        }
    }

    enum class StateBucket { LOADED, FAILED, PENDING, OTHER }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setFilter(f: Filter) {
        if (_ui.value.filter == f) return
        _ui.value = _ui.value.copy(filter = f)
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listConfigEntries().fold(
                onSuccess = { entries ->
                    R1Log.i(
                        "Integrations",
                        "loaded ${entries.size} entr${if (entries.size == 1) "y" else "ies"}",
                    )
                    _ui.value = _ui.value.copy(
                        loading = false,
                        all = entries.sortedWith(
                            compareBy<ConfigEntry> { it.domain.lowercase(Locale.US) }
                                .thenBy { it.title.lowercase(Locale.US) },
                        ),
                        error = null,
                    )
                },
                onFailure = { t ->
                    R1Log.w("Integrations", "list failed: ${t.message}")
                    Toaster.error("Integrations load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
            // Registry counts are a nicety, not load-bearing: fetch them
            // after the entry list so a registry failure never blocks the
            // surface. Both calls are independent; either result that
            // arrives folds into the running map.
            refreshCounts()
        }
    }

    private suspend fun refreshCounts() {
        val devices = haRepository.listDevices().getOrNull()
        val entities = haRepository.listEntityRegistry().getOrNull()
        if (devices == null && entities == null) return
        _ui.value = _ui.value.copy(
            countsByDomain = countsByDomain(
                devices = devices.orEmpty(),
                entities = entities.orEmpty(),
            ),
        )
    }

    fun reload(entry: ConfigEntry) {
        // Sanity: HA's reload endpoint NACKs entries the integration
        // refuses to unload. Skip the call entirely on those rather than
        // round-tripping to find out.
        if (!entry.supportsUnload) {
            Toaster.show("${entry.title} doesn't support reload")
            return
        }
        viewModelScope.launch {
            _ui.value = _ui.value.copy(reloadingIds = _ui.value.reloadingIds + entry.entryId)
            haRepository.reloadConfigEntry(entry.entryId).fold(
                onSuccess = {
                    Toaster.show("Reloaded ${entry.title}")
                    // Clear the in-flight marker before refreshing; refresh()
                    // preserves reloadingIds across its state copies, so without
                    // this the row's spinner would never stop after a successful
                    // reload.
                    _ui.value = _ui.value.copy(
                        reloadingIds = _ui.value.reloadingIds - entry.entryId,
                    )
                    refresh()
                },
                onFailure = { t ->
                    R1Log.w("Integrations", "reload ${entry.entryId} failed: ${t.message}")
                    Toaster.errorExpandable(
                        shortText = "Reload failed",
                        fullText = t.message ?: t.toString(),
                    )
                    _ui.value = _ui.value.copy(
                        reloadingIds = _ui.value.reloadingIds - entry.entryId,
                    )
                },
            )
        }
    }

    companion object {
        /** Classify a HA state string into a UI bucket. Used both for
         *  the FAILED filter and for chip coloring.
         *
         *  FAILED mirrors HA frontend's `ERROR_STATES`
         *  (`config_entries.ts`): migration_error, setup_error AND
         *  setup_retry. A retrying entry is a failed entry that HA will
         *  keep re-attempting on a timer, so it belongs in the FAILED
         *  bucket (red, surfaced by the FAILED filter), not in PENDING.
         *  `failed_unload` is also a hard error. setup_in_progress and
         *  not_loaded are transient / inert, so they stay PENDING. */
        fun stateRank(state: String): StateBucket = when (state.lowercase(Locale.US)) {
            "loaded" -> StateBucket.LOADED
            "setup_error", "migration_error", "setup_retry", "failed_unload" -> StateBucket.FAILED
            "setup_in_progress", "not_loaded" -> StateBucket.PENDING
            else -> StateBucket.OTHER
        }

        /** Human-readable label for a HA config-entry state. HA sends
         *  snake_case lifecycle tokens; the chip shows a spaced, upper
         *  form. Unknown tokens fall back to a generic upcasing so a new
         *  HA state still renders legibly. */
        fun stateLabel(state: String): String = when (state.lowercase(Locale.US)) {
            "loaded" -> "LOADED"
            "setup_error" -> "SETUP ERROR"
            "setup_retry" -> "SETUP RETRY"
            "setup_in_progress" -> "SETTING UP"
            "not_loaded" -> "NOT LOADED"
            "migration_error" -> "MIGRATION ERROR"
            "failed_unload" -> "UNLOAD FAILED"
            else -> state.replace('_', ' ').uppercase(Locale.US)
        }

        /** Chip label for a disabled entry's cause. HA reports
         *  `disabled_by` as "user", "integration", or "device"; the chip
         *  shows a compact "BY {cause}" alongside the DISABLED state chip
         *  so the reason is legible without repeating the word. Returns
         *  null when the entry is enabled. */
        fun disabledLabel(disabledBy: String?): String? {
            val cause = disabledBy?.takeIf { it.isNotBlank() } ?: return null
            return "BY ${cause.uppercase(Locale.US)}"
        }

        /** True when [entry] belongs in [filter]. */
        fun matchesFilter(entry: ConfigEntry, filter: Filter): Boolean = when (filter) {
            Filter.ALL -> true
            Filter.LOADED -> entry.state.equals("loaded", ignoreCase = true)
            Filter.PENDING ->
                entry.disabledBy == null && stateRank(entry.state) == StateBucket.PENDING
            Filter.FAILED -> stateRank(entry.state) == StateBucket.FAILED
        }

        /** True when [entry] matches a free-text [query] on domain or
         *  title. Blank query matches everything. */
        fun matchesQuery(entry: ConfigEntry, query: String): Boolean {
            val q = query.trim().lowercase(Locale.US)
            if (q.isEmpty()) return true
            return entry.domain.lowercase(Locale.US).contains(q) ||
                entry.title.lowercase(Locale.US).contains(q)
        }

        /** Group entries by domain into alphabetical sections, sorting
         *  each section's entries by title. Pure: backs both the UI and
         *  the unit tests. */
        fun groupByDomain(entries: List<ConfigEntry>): List<Pair<String, List<ConfigEntry>>> =
            entries.groupBy { it.domain }
                .entries
                .sortedBy { it.key.lowercase(Locale.US) }
                .map { (d, v) -> d to v.sortedBy { it.title.lowercase(Locale.US) } }

        /** Roll device + entity registry rows up into per-domain counts.
         *
         *  The slim registry models the repo exposes don't carry a
         *  `config_entry_id`, so counts can't be split per entry. They
         *  CAN be attributed to a domain:
         *   - an entity registry row's [EntityRegistryEntry.platform] is
         *     the integration domain that created it;
         *   - a device's domain is the first element of its first
         *     identifier tuple (e.g. ("hue", "00:11..") -> "hue").
         *  Rows that can't be attributed (no platform / no identifiers)
         *  are dropped from the tally. */
        fun countsByDomain(
            devices: List<DeviceInfo>,
            entities: List<EntityRegistryEntry>,
        ): Map<String, DomainCounts> {
            val deviceCounts = devices
                .mapNotNull { it.identifiers.firstOrNull()?.first?.lowercase(Locale.US) }
                .groupingBy { it }
                .eachCount()
            val entityCounts = entities
                .mapNotNull { it.platform?.lowercase(Locale.US)?.takeIf { p -> p.isNotBlank() } }
                .groupingBy { it }
                .eachCount()
            val domains = deviceCounts.keys + entityCounts.keys
            return domains.associateWith { d ->
                DomainCounts(
                    devices = deviceCounts[d] ?: 0,
                    entities = entityCounts[d] ?: 0,
                )
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { IntegrationsViewModel(haRepository) }
        }
    }
}
