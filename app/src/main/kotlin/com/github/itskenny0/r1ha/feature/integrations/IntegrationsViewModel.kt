package com.github.itskenny0.r1ha.feature.integrations

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.ConfigEntry
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the native Integrations browser. Fetches HA's
 * `config_entries/get` once, groups by domain, and exposes the per-row
 * reload action. Setup / removal / options flows live in HA's web UI;
 * this surface is the operational "view + kick" companion.
 */
class IntegrationsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    enum class Filter { ALL, LOADED, FAILED }

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<ConfigEntry> = emptyList(),
        val filter: Filter = Filter.ALL,
        /** Entry ids currently being reloaded. Reloads can take 5-15s on
         *  larger integrations (Z-Wave, Zigbee); the row chip flips to
         *  a spinner while the deferred is in-flight so the user knows
         *  the tap registered. */
        val reloadingIds: Set<String> = emptySet(),
        val error: String? = null,
    ) {
        /** Entries after [filter] is applied. */
        val visible: List<ConfigEntry>
            get() = when (filter) {
                Filter.ALL -> all
                Filter.LOADED -> all.filter { it.state.equals("loaded", ignoreCase = true) }
                Filter.FAILED -> all.filter { stateRank(it.state) == StateBucket.FAILED }
            }

        /** Visible entries grouped by domain, alphabetical sections,
         *  domains sorted by the integration's title within. */
        val sections: List<Pair<String, List<ConfigEntry>>>
            get() = visible.groupBy { it.domain }
                .entries
                .sortedBy { it.key.lowercase() }
                .map { (d, v) -> d to v.sortedBy { it.title.lowercase() } }

        /** Count by bucket for the chip badges, computed off the
         *  unfiltered set so toggling between filters doesn't change the
         *  surfaced totals. */
        val loadedCount: Int get() = all.count { it.state.equals("loaded", ignoreCase = true) }
        val failedCount: Int get() = all.count { stateRank(it.state) == StateBucket.FAILED }
    }

    enum class StateBucket { LOADED, FAILED, PENDING, OTHER }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun setFilter(f: Filter) {
        if (_ui.value.filter == f) return
        _ui.value = _ui.value.copy(filter = f)
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
                            compareBy<ConfigEntry> { it.domain.lowercase() }
                                .thenBy { it.title.lowercase() },
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
        }
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
         *  the FAILED filter and for chip coloring. */
        fun stateRank(state: String): StateBucket = when (state.lowercase()) {
            "loaded" -> StateBucket.LOADED
            "setup_error", "migration_error", "failed_unload" -> StateBucket.FAILED
            "setup_retry", "setup_in_progress", "not_loaded" -> StateBucket.PENDING
            else -> StateBucket.OTHER
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { IntegrationsViewModel(haRepository) }
        }
    }
}
