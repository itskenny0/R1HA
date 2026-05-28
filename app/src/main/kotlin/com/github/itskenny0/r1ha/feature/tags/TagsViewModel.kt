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

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loading = true, error = null)
            haRepository.listTags().fold(
                onSuccess = { tags ->
                    R1Log.i("Tags", "fetched ${tags.size} tag(s)")
                    val sorted = tags.sortedByDescending { it.lastScanned?.toEpochMilli() ?: 0L }
                    _ui.value = _ui.value.copy(loading = false, tags = sorted, error = null)
                },
                onFailure = { t ->
                    R1Log.w("Tags", "list failed: ${t.message}")
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
