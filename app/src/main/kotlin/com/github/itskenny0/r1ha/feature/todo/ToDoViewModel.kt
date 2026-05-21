package com.github.itskenny0.r1ha.feature.todo

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ToDoItem
import com.github.itskenny0.r1ha.core.ha.ToDoList
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the To-Do screen. Two layered fetches: a top-level list of
 * todo entities (one per HA todo integration: Local, Shopping List,
 * Google Tasks, CalDAV, etc.) and, for the active list, the items
 * inside it. Both go through the REST `?return_response=true`
 * mechanism because HA exposes todo items only through a WS or
 * service-response call rather than as state attributes on the
 * todo entity.
 */
class ToDoViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    @androidx.compose.runtime.Stable
    data class UiState(
        // Default to true so the first composition shows a spinner rather
        // than briefly flashing the "No todo entities found" empty state
        // before the initial refresh launches.
        val loadingLists: Boolean = true,
        val loadingItems: Boolean = false,
        val lists: List<ToDoList> = emptyList(),
        val activeEntityId: String? = null,
        val items: List<ToDoItem> = emptyList(),
        val draft: String = "",
        val error: String? = null,
        /** Set of item summaries with an in-flight update so the row can
         *  show a transient state and reject double-taps. */
        val pendingItems: Set<String> = emptySet(),
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingLists = true, error = null)
            haRepository.listTodoEntities().fold(
                onSuccess = { lists ->
                    val firstId = _ui.value.activeEntityId ?: lists.firstOrNull()?.entityId
                    _ui.value = _ui.value.copy(
                        loadingLists = false,
                        lists = lists,
                        activeEntityId = firstId,
                    )
                    firstId?.let { fetchItems(it) }
                },
                onFailure = { t ->
                    R1Log.w("ToDo", "list entities failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        loadingLists = false,
                        error = t.message ?: "Failed to load lists",
                    )
                },
            )
        }
    }

    fun selectList(entityId: String) {
        if (_ui.value.activeEntityId == entityId) return
        _ui.value = _ui.value.copy(activeEntityId = entityId, items = emptyList())
        fetchItems(entityId)
    }

    private fun fetchItems(entityId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingItems = true)
            haRepository.fetchTodoItems(entityId).fold(
                onSuccess = { items ->
                    R1Log.i("ToDo", "$entityId → ${items.size} items")
                    _ui.value = _ui.value.copy(loadingItems = false, items = items, error = null)
                },
                onFailure = { t ->
                    R1Log.w("ToDo", "fetch $entityId failed: ${t.message}")
                    _ui.value = _ui.value.copy(
                        loadingItems = false,
                        error = t.message ?: "Failed to load items",
                    )
                },
            )
        }
    }

    fun setDraft(text: String) {
        _ui.value = _ui.value.copy(draft = text)
    }

    fun addDraftItem() {
        val entity = _ui.value.activeEntityId ?: return
        val summary = _ui.value.draft.trim()
        if (summary.isEmpty()) return
        _ui.value = _ui.value.copy(draft = "")
        viewModelScope.launch {
            haRepository.addTodoItem(entity, summary).fold(
                onSuccess = { fetchItems(entity) },
                onFailure = { t ->
                    Toaster.error("Add failed: ${t.message ?: "unknown"}")
                },
            )
        }
    }

    fun toggleCompleted(item: ToDoItem) {
        val entity = _ui.value.activeEntityId ?: return
        // Key the pending-set by uid so duplicate-summary rows don't block
        // each other's taps. With summary-keyed gating, toggling one
        // "Apples" row froze the second one until the first settled.
        if (item.uid in _ui.value.pendingItems) return
        _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems + item.uid)
        // Optimistic flip.
        _ui.value = _ui.value.copy(
            items = _ui.value.items.map {
                if (it.uid == item.uid) it.copy(completed = !it.completed) else it
            },
        )
        viewModelScope.launch {
            haRepository.updateTodoItem(entity, item.uid, !item.completed).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        pendingItems = _ui.value.pendingItems - item.uid,
                    )
                },
                onFailure = { t ->
                    Toaster.error("Update failed: ${t.message ?: "unknown"}")
                    // Roll back optimistic flip.
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.map {
                            if (it.uid == item.uid) it.copy(completed = item.completed) else it
                        },
                        pendingItems = _ui.value.pendingItems - item.uid,
                    )
                },
            )
        }
    }

    fun clearCompleted() {
        val entity = _ui.value.activeEntityId ?: return
        val completedCount = _ui.value.items.count { it.completed }
        if (completedCount == 0) return
        viewModelScope.launch {
            haRepository.clearCompletedTodoItems(entity).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.filterNot { it.completed },
                    )
                    Toaster.show("Cleared $completedCount completed item${if (completedCount == 1) "" else "s"}")
                },
                onFailure = { t ->
                    Toaster.error("Clear failed: ${t.message ?: "unknown"}")
                },
            )
        }
    }

    fun remove(item: ToDoItem) {
        val entity = _ui.value.activeEntityId ?: return
        if (item.uid in _ui.value.pendingItems) return
        _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems + item.uid)
        viewModelScope.launch {
            haRepository.removeTodoItem(entity, item.uid).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.filterNot { it.uid == item.uid },
                        pendingItems = _ui.value.pendingItems - item.uid,
                    )
                },
                onFailure = { t ->
                    Toaster.error("Remove failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        pendingItems = _ui.value.pendingItems - item.uid,
                    )
                },
            )
        }
    }

    companion object {
        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ToDoViewModel(haRepository) }
        }
    }
}
