package com.github.itskenny0.r1ha.feature.services

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaServiceDomain
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Services Browser. Lists every HA service via /api/services
 * with substring search and per-domain expansion. Tap a service to copy
 * "<domain>.<service>" to the clipboard, then paste into the Service
 * Caller to dispatch it.
 */
class ServicesViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val all: List<HaServiceDomain> = emptyList(),
        val query: String = "",
        val error: String? = null,
    ) {
        /** Filtered subset matching [query]. Matches against domain name,
         *  service name, description, or any field name (case-insensitive
         *  substring). Field names are matched too because they're shown in
         *  the row, so searching e.g. "brightness" should surface
         *  `light.turn_on` rather than coming up empty. */
        val domains: List<HaServiceDomain> get() {
            if (query.isBlank()) return all
            val q = query.trim().lowercase()
            return all.mapNotNull { d ->
                if (d.domain.lowercase().contains(q)) return@mapNotNull d
                val matchingServices = d.services.filter {
                    it.name.lowercase().contains(q) ||
                        (it.description?.lowercase()?.contains(q) ?: false) ||
                        it.fieldNames.any { f -> f.lowercase().contains(q) }
                }
                if (matchingServices.isEmpty()) null else d.copy(services = matchingServices)
            }
        }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listServices().fold(
                onSuccess = { domains ->
                    R1Log.i(
                        "Services",
                        "loaded ${domains.size} domains, total ${domains.sumOf { it.services.size }} services",
                    )
                    _ui.value = _ui.value.copy(loading = false, all = domains, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Services", "list failed: ${t.message}")
                    Toaster.error("Services load failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun setQuery(q: String) {
        if (_ui.value.query == q) return
        _ui.value = _ui.value.copy(query = q)
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ServicesViewModel(haRepository) }
        }
    }
}
