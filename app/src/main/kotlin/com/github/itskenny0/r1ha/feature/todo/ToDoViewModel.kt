package com.github.itskenny0.r1ha.feature.todo

import androidx.compose.runtime.Stable
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Drives the To-Do screen. Two layered fetches: a top-level list of
 * todo entities (one per HA todo integration: Local, Shopping List,
 * Google Tasks, CalDAV, etc.) and, for the active list, the items
 * inside it. Both go through the REST `?return_response=true`
 * mechanism because HA exposes todo items only through a WS or
 * service-response call rather than as state attributes on the
 * todo entity.
 *
 * Item identity is split in two on purpose:
 *  - [Item.key] is a stable, collision-free identity used for the
 *    LazyColumn key, the pending-set, and matching rows during
 *    optimistic updates. It NEVER goes on the wire.
 *  - [Item.serverRef] is what HA's `update_item` / `remove_item`
 *    services target (the provider uid when present, otherwise the
 *    summary). Multiple rows can legitimately share a serverRef (two
 *    "Apples" on a shopping list with no uid); they must NOT share a
 *    key. See [itemKey] for the derivation and the unit tests for the
 *    collision the old summary-as-uid scheme produced.
 */
class ToDoViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    /**
     * One row as the screen sees it. [key] is the stable identity;
     * [serverRef] is the wire reference for service calls; [due] and
     * [description] are optional provider metadata shown when present.
     */
    @Stable
    data class Item(
        val key: String,
        val serverRef: String,
        val summary: String,
        val completed: Boolean,
        val due: String? = null,
        val description: String? = null,
    )

    @Stable
    data class UiState(
        // Default to true so the first composition shows a spinner rather
        // than briefly flashing the "No todo entities found" empty state
        // before the initial refresh launches.
        val loadingLists: Boolean = true,
        val loadingItems: Boolean = false,
        val lists: List<ToDoList> = emptyList(),
        val activeEntityId: String? = null,
        val items: List<Item> = emptyList(),
        val draft: String = "",
        val error: String? = null,
        /** Keys of rows with an in-flight update so the row can show a
         *  transient state and reject double-taps. Keyed by the stable
         *  [Item.key], not the serverRef, so duplicate-summary rows that
         *  share a serverRef don't block each other. */
        val pendingItems: Set<String> = emptySet(),
        /** When false the completed section is collapsed out of the body.
         *  Mirrors the Lovelace card's `hide_completed` toggle. Defaults to
         *  shown so a fresh list reveals everything; the user collapses it
         *  to focus on what's still outstanding. */
        val showCompleted: Boolean = true,
    ) {
        val activeList: ToDoList?
            get() = lists.firstOrNull { it.entityId == activeEntityId }

        /** Rows still needing action, in wire order. */
        val activeItems: List<Item>
            get() = items.filterNot { it.completed }

        /** Rows already completed, in wire order. */
        val completedItems: List<Item>
            get() = items.filter { it.completed }
    }

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    fun refresh() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingLists = true, error = null)
            haRepository.listTodoEntities().fold(
                onSuccess = { lists ->
                    val firstId = _ui.value.activeEntityId?.takeIf { id -> lists.any { it.entityId == id } }
                        ?: lists.firstOrNull()?.entityId
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
        _ui.value = _ui.value.copy(activeEntityId = entityId, items = emptyList(), error = null)
        fetchItems(entityId)
    }

    private fun fetchItems(entityId: String) {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(loadingItems = true)
            haRepository.fetchTodoItems(entityId).fold(
                onSuccess = { raw ->
                    R1Log.i("ToDo", "$entityId → ${raw.size} items")
                    _ui.value = _ui.value.copy(
                        loadingItems = false,
                        items = toItems(entityId, raw),
                        error = null,
                    )
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
                    // Restore the text the user typed so the failed add isn't
                    // silently lost. Only refill if they haven't started a new
                    // draft in the meantime.
                    if (_ui.value.draft.isBlank()) {
                        _ui.value = _ui.value.copy(draft = summary)
                    }
                },
            )
        }
    }

    /** Collapse / reveal the completed section, mirroring HA's hide_completed. */
    fun toggleShowCompleted() {
        _ui.value = _ui.value.copy(showCompleted = !_ui.value.showCompleted)
    }

    fun toggleCompleted(item: Item) {
        val entity = _ui.value.activeEntityId ?: return
        if (item.key in _ui.value.pendingItems) return
        _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems + item.key)
        // Optimistic flip, matched by stable key so duplicate-summary rows
        // flip independently.
        _ui.value = _ui.value.copy(
            items = _ui.value.items.map {
                if (it.key == item.key) it.copy(completed = !it.completed) else it
            },
        )
        viewModelScope.launch {
            haRepository.updateTodoItem(entity, item.serverRef, !item.completed).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        pendingItems = _ui.value.pendingItems - item.key,
                    )
                },
                onFailure = { t ->
                    Toaster.error("Update failed: ${t.message ?: "unknown"}")
                    // Roll back optimistic flip.
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.map {
                            if (it.key == item.key) it.copy(completed = item.completed) else it
                        },
                        pendingItems = _ui.value.pendingItems - item.key,
                    )
                },
            )
        }
    }

    /**
     * Rename / edit an item's summary via `todo.update_item`. Optimistically
     * updates the row's summary, rolling back on failure. The serverRef does
     * not change for uid-backed items; for summary-keyed items HA re-keys the
     * item server-side, so we re-fetch to pick up the new identity.
     */
    fun rename(item: Item, newSummary: String) {
        val entity = _ui.value.activeEntityId ?: return
        val trimmed = newSummary.trim()
        if (trimmed.isEmpty() || trimmed == item.summary) return
        if (item.key in _ui.value.pendingItems) return
        _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems + item.key)
        _ui.value = _ui.value.copy(
            items = _ui.value.items.map {
                if (it.key == item.key) it.copy(summary = trimmed) else it
            },
        )
        // Rename rides HA's `todo.update_item` with a `rename` field, issued
        // through the generic raw-service path so the slice needs no new core
        // method. `item` targets the existing row (uid or summary); `rename`
        // is the new summary.
        val payload = buildJsonObject {
            put("entity_id", JsonPrimitive(entity))
            put("item", JsonPrimitive(item.serverRef))
            put("rename", JsonPrimitive(trimmed))
        }
        viewModelScope.launch {
            haRepository.callRawService("todo", "update_item", payload).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems - item.key)
                    fetchItems(entity)
                },
                onFailure = { t ->
                    Toaster.error("Rename failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.map {
                            if (it.key == item.key) it.copy(summary = item.summary) else it
                        },
                        pendingItems = _ui.value.pendingItems - item.key,
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

    fun remove(item: Item) {
        val entity = _ui.value.activeEntityId ?: return
        if (item.key in _ui.value.pendingItems) return
        _ui.value = _ui.value.copy(pendingItems = _ui.value.pendingItems + item.key)
        viewModelScope.launch {
            haRepository.removeTodoItem(entity, item.serverRef).fold(
                onSuccess = {
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.filterNot { it.key == item.key },
                        pendingItems = _ui.value.pendingItems - item.key,
                    )
                },
                onFailure = { t ->
                    Toaster.error("Remove failed: ${t.message ?: "unknown"}")
                    _ui.value = _ui.value.copy(
                        pendingItems = _ui.value.pendingItems - item.key,
                    )
                },
            )
        }
    }

    /** How urgent a parsed due date is relative to "today". */
    enum class DueUrgency { OVERDUE, TODAY, UPCOMING }

    /** A due date rendered for the screen: a short label plus its urgency. */
    @Stable
    data class DueDisplay(val label: String, val urgency: DueUrgency)

    companion object {
        /**
         * Parse and humanise a todo item's `due` value for display.
         *
         * HA hands `due` over in one of two ISO-8601 shapes: a bare date
         * ("2026-06-02") for date-only due dates, or a datetime
         * ("2026-06-02T17:30:00+00:00") when the provider sets a due time.
         * Anything else (a provider that emits a non-standard string) is shown
         * verbatim and treated as UPCOMING so we never crash on an odd value.
         *
         * [today] is injected so the relative-day logic ("Today", "Overdue")
         * is deterministic and unit-testable without a real clock.
         *
         * The label is intentionally compact for the R1's narrow column:
         *  - same day      -> "TODAY" (+ time when the provider gave one)
         *  - in the past   -> the date, flagged OVERDUE so the UI can redden it
         *  - tomorrow      -> "TOMORROW"
         *  - otherwise     -> the ISO date
         */
        fun formatDue(raw: String?, today: java.time.LocalDate): DueDisplay? {
            val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            // Try datetime first (it carries a date too); fall back to date-only.
            val dueDate: java.time.LocalDate? = runCatching {
                java.time.OffsetDateTime.parse(value).toLocalDate()
            }.getOrNull()
                ?: runCatching { java.time.LocalDateTime.parse(value).toLocalDate() }.getOrNull()
                ?: runCatching { java.time.LocalDate.parse(value) }.getOrNull()

            // The clock portion, present only when the provider supplied a
            // datetime (not a bare date). Both datetime shapes funnel through
            // one HH:mm rendering path.
            val clock: String? = (
                runCatching { java.time.OffsetDateTime.parse(value).toLocalTime() }.getOrNull()
                    ?: runCatching { java.time.LocalDateTime.parse(value).toLocalTime() }.getOrNull()
                )?.let { "%02d:%02d".format(it.hour, it.minute) }

            if (dueDate == null) {
                // Unparseable: surface it raw rather than dropping the field.
                return DueDisplay(value.uppercase(), DueUrgency.UPCOMING)
            }

            val urgency = when {
                dueDate.isBefore(today) -> DueUrgency.OVERDUE
                dueDate.isEqual(today) -> DueUrgency.TODAY
                else -> DueUrgency.UPCOMING
            }
            val dayLabel = when {
                dueDate.isEqual(today) -> "TODAY"
                dueDate.isEqual(today.plusDays(1)) -> "TOMORROW"
                dueDate.isEqual(today.minusDays(1)) -> "YESTERDAY"
                else -> dueDate.toString() // ISO yyyy-MM-dd
            }
            val label = if (clock != null) "$dayLabel $clock" else dayLabel
            return DueDisplay(label, urgency)
        }

        /**
         * Map the repository's raw items into keyed screen items, assigning a
         * stable, collision-free [Item.key] per row. Position is the tiebreaker
         * for uidless rows, so a refresh that preserves item order preserves
         * keys (and LazyColumn animations / pending state survive).
         */
        fun toItems(entityId: String, raw: List<ToDoItem>): List<Item> =
            raw.mapIndexed { index, it ->
                Item(
                    key = itemKey(entityId, index, it),
                    // The wire reference is the provider uid when present, otherwise
                    // the summary (HA accepts the summary as an item selector on
                    // providers that expose no uid). The repository now keeps uid
                    // null for uidless items rather than substituting the summary
                    // itself, so the fallback lives here at the view layer.
                    serverRef = it.uid ?: it.summary,
                    summary = it.summary,
                    completed = it.completed,
                    due = it.due,
                    description = it.description,
                )
            }

        /**
         * Derive a stable identity for a todo row.
         *
         * The bug this replaces: items without a provider uid were given the
         * SUMMARY as their uid, so two rows reading "Apples" collapsed into one
         * (LazyColumn key clash, pending-set cross-talk, and complete / delete
         * hitting the wrong row). Here, a uid-backed item keys off
         * `entityId + uid` (globally unique, survives reorders); a uidless item
         * keys off `entityId + index + summary`, which is unique within the list
         * and deterministic across a refresh that keeps item order.
         *
         * A provider uid is treated as "present" when the repository surfaced a
         * non-null one; uidless items (uid == null) fall back to position keying.
         */
        fun itemKey(entityId: String, index: Int, item: ToDoItem): String {
            val realUid = item.uid?.takeIf { it.isNotEmpty() }
            return if (realUid != null) {
                "$entityId|uid:$realUid"
            } else {
                "$entityId|idx:$index|sum:${item.summary}"
            }
        }

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { ToDoViewModel(haRepository) }
        }
    }
}
