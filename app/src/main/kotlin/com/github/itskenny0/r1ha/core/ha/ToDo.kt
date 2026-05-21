package com.github.itskenny0.r1ha.core.ha

import androidx.compose.runtime.Stable

/**
 * One item inside a HA todo entity. HA returns these from `todo.get_items`
 * with a stable `uid` (used to reference the item for update / delete),
 * the user-visible `summary`, and a status of either "needs_action" or
 * "completed". We keep only those three fields because nothing else on
 * the wire is load-bearing for the dashboard view; due dates and
 * descriptions exist on some lists (Google Tasks / CalDAV) but show
 * value isn't proportional to the parsing complexity yet.
 *
 * @Stable so Compose treats this as skippable inside its parent list.
 */
@Stable
data class ToDoItem(
    val uid: String,
    val summary: String,
    val completed: Boolean,
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
