package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One item inside a HA todo entity. HA returns these from `todo.get_items`
 * with the user-visible `summary` and a status of either "needs_action" or
 * "completed", plus optional provider-specific fields.
 *
 * [uid] is the stable identifier the provider assigns (used to reference the
 * item for update / delete). It is NULLABLE on purpose: Local To-do, Google
 * Tasks, and the legacy Shopping List always supply one, but some CaldAV
 * providers omit it. A null uid means "this provider gave us no stable id",
 * so the caller must fall back to position-based identity rather than treating
 * the summary as a surrogate key (which silently collapsed two rows that read
 * the same text). Service calls that target a uidless item use the summary.
 *
 * [position] is the item's index within the list as HA returned it. It is the
 * tiebreaker for uidless rows: a refresh that preserves item order preserves
 * identity, so list animations and pending state survive.
 *
 * [due] and [description] are surfaced by some providers (Google Tasks,
 * CalDAV, Local To-do). HA returns the due value under either `due` (date or
 * datetime) or `due_datetime`; both map here. Null when the provider does not
 * supply them.
 *
 * @Stable so Compose treats this as skippable inside its parent list.
 */
@Stable
data class ToDoItem(
    val uid: String?,
    val summary: String,
    val completed: Boolean,
    val position: Int = 0,
    val due: String? = null,
    val description: String? = null,
)

/**
 * HA todo entity (todo.shopping_list, todo.groceries, etc.) along with its
 * friendly name and a count of items reported by the integration. The
 * count comes from the entity's state attribute (HA writes the count
 * there); the item list itself is fetched separately via
 * [HaRepository.fetchTodoItems] because HA doesn't surface items as state
 * attributes.
 */
@Stable
data class ToDoList(
    val entityId: String,
    val friendlyName: String,
    val itemCount: Int,
)
