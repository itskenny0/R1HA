package com.github.itskenny0.r1ha.feature.tags

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.HaTag
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Tags screen. Wraps the three `tag/` WS calls (list / update /
 * delete) and re-fetches after every mutation so the row count, names,
 * and last-scanned timestamps stay consistent with the server without
 * the user having to pull-to-refresh.
 */
class TagsViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        val loading: Boolean = true,
        val tags: List<HaTag> = emptyList(),
        val error: String? = null,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /**
     * User-initiated refresh (REFRESH chip, post-mutation re-fetch). Flips the
     * loading flag so the chip shows progress and toasts on failure when rows
     * are already on screen.
     */
    fun refresh() = load(quiet = false)

    /**
     * Background poll on the AutoRefresh cadence. Stays [quiet]: it never
     * toggles `loading` (so the REFRESH chip doesn't flicker to "…" every 30s)
     * and never toasts on failure (so a flaky link doesn't spam error toasts
     * while the user is idling on the screen). A failed quiet poll just leaves
     * the existing list untouched and tries again next tick.
     */
    fun refreshQuiet() = load(quiet = true)

    private fun load(quiet: Boolean) {
        viewModelScope.launch {
            if (!quiet) {
                _ui.value = _ui.value.copy(loading = true, error = null)
            }
            haRepository.listTags().fold(
                onSuccess = { tags ->
                    R1Log.i("Tags", "fetched ${tags.size} tag(s)")
                    val sorted = tags.sortedByDescending { it.lastScanned?.toEpochMilli() ?: 0L }
                    _ui.value = _ui.value.copy(loading = false, tags = sorted, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Tags", "list failed (quiet=$quiet): ${t.message}")
                    if (quiet) {
                        // Background tick: leave the (possibly stale) list in place
                        // and don't surface the failure. A quiet poll never set
                        // loading, so there's nothing to clear either.
                        return@fold
                    }
                    // When we already have rows on screen, a failed re-fetch must not
                    // silently blank the inline error slot (which only renders on an
                    // empty list); surface it as a toast so the stale list stays put
                    // but the user still learns the refresh didn't land.
                    if (_ui.value.tags.isNotEmpty()) {
                        Toaster.error("Tag refresh failed")
                    }
                    _ui.value = _ui.value.copy(loading = false, error = t.message)
                },
            )
        }
    }

    fun update(tag: HaTag, name: String, description: String) {
        viewModelScope.launch {
            haRepository.updateTag(
                tagId = tag.id,
                name = name,
                description = description,
            ).fold(
                onSuccess = {
                    Toaster.show("Tag updated")
                    refresh()
                },
                onFailure = { t ->
                    Toaster.errorExpandable(
                        shortText = "Tag update failed",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
        }
    }

    fun delete(tag: HaTag) {
        viewModelScope.launch {
            haRepository.deleteTag(tag.id).fold(
                onSuccess = {
                    Toaster.show("Tag deleted")
                    refresh()
                },
                onFailure = { t ->
                    Toaster.errorExpandable(
                        shortText = "Tag delete failed",
                        fullText = t.message ?: t.toString(),
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { TagsViewModel(haRepository) }
        }
    }
}
