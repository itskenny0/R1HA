package com.github.itskenny0.r1ha.feature.repairs

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.RepairIssue
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Repairs surface. Calls `repairs/list_issues` on entry + every refresh, sorts
 * the result with severity-first / created-newest-second / ignored-last, and exposes an
 * ignore action that flips the server-side ignore bit + re-fetches. On first load it also
 * resolves HA's web-UI base URL (from `/api/config`) so fixable issues can deep-link into
 * HA's own multi-step fix flow, falling back to a plain "Fix in Home Assistant" hint when
 * no usable URL is configured.
 */
class RepairsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val issues: List<RepairIssue> = emptyList(),
        val error: String? = null,
        /** HA web-UI deep link to the repairs dashboard, null when no base URL is known. */
        val repairsUrl: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listRepairs().fold(
                onSuccess = { issues ->
                    val sorted = RepairsLogic.sortIssues(issues)
                    _ui.value = _ui.value.copy(loading = false, issues = sorted, error = null)
                    R1Log.i("Repairs", "fetched ${sorted.size} issue(s)")
                },
                onFailure = { t ->
                    R1Log.w("Repairs", "fetch failed: ${t.message}")
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
        resolveRepairsUrl()
    }

    /**
     * Best-effort resolve of HA's web-UI base URL for the fix-flow deep link.
     * Runs alongside the list fetch and never blocks or surfaces an error: a
     * missing URL just means rows fall back to plain "Fix in Home Assistant".
     * Skips the round-trip once a URL is already cached.
     */
    private fun resolveRepairsUrl() {
        if (_ui.value.repairsUrl != null) return
        viewModelScope.launch {
            haRepository.fetchHaConfig().onSuccess { config ->
                val base = config.externalUrl?.takeIf { it.isNotBlank() } ?: config.internalUrl
                val url = RepairsLogic.repairsDashboardUrl(base)
                if (url != null) _ui.value = _ui.value.copy(repairsUrl = url)
            }
        }
    }

    fun ignore(issue: RepairIssue) {
        viewModelScope.launch {
            haRepository.ignoreRepair(issue.domain, issue.issueId, ignore = !issue.ignored).fold(
                onSuccess = {
                    Toaster.show(
                        if (issue.ignored) "Restored ${issue.issueId}"
                        else "Ignored ${issue.issueId}",
                    )
                    refresh()
                },
                onFailure = { t ->
                    Toaster.errorExpandable(
                        shortText = "Ignore failed",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { RepairsViewModel(haRepository) }
        }
    }
}
