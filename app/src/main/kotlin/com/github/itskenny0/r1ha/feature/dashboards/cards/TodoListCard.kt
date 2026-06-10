package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.ToDoItem
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive

/** Cap rendered rows so a long shopping list doesn't blow up the card. */
private const val MAX_TODO_ROWS = 40

/**
 * Renderer for HA's `todo-list` card (hui-todo-list-card.ts). Fetches the
 * configured todo entity's items off [LocalHaRepository] and lists them with a
 * checkbox glyph reflecting completed state. Tapping a row flips that item's
 * completed status (HA's primary interaction) and re-fetches; an item with no
 * stable `uid` is shown but not tappable (the provider gave us nothing to
 * target). A transport failure or an entity the integration can't serve falls
 * back to a quiet placeholder.
 *
 * R1HA's typed model has no dedicated todo-list variant, so the entity id is
 * taken from the [LovelaceCard.Unsupported.entityRefs] the parser captures off
 * the card's `entity` key (the entity is subscribed for its item count).
 *
 * `hide_create` (HA 2024.6): when true, the add-item input row is hidden so the
 * card acts as a read-only view of the list. When false (default), an inline text
 * field appears at the bottom of the card so items can be added from the dashboard.
 */
@Composable
fun TodoListCard(
    card: LovelaceCard.Unsupported,
    stateMap: EntityStates,
    modifier: Modifier = Modifier,
) {
    val entityId = card.entityRefs.firstOrNull()
    val repo = LocalHaRepository.current
    val scope = rememberCoroutineScope()
    val title = card.raw["title"]?.let { (it as? JsonPrimitive)?.content }
        ?: entityId?.let { resolveName(null, stateMap.byRaw(it), it) }
    // `hide_completed` mirrors HA's option to drop already-done items.
    val hideCompleted = card.raw["hide_completed"]?.let { (it as? JsonPrimitive)?.content?.toBoolean() } ?: false
    // `hide_create` (HA 2024.6): suppress the add-item input row.
    val hideCreate = card.raw["hide_create"]?.let { (it as? JsonPrimitive)?.content?.toBoolean() } ?: false
    // `sort` (HA 2025.2): "alpha" = alphabetical by summary, "duedate" = by due date,
    // null/"manual" = server order (no-op).
    val sortMode = card.raw["sort"]?.let { (it as? JsonPrimitive)?.content }
    var newItemText by remember(entityId) { mutableStateOf("") }

    var items by remember(entityId) { mutableStateOf<List<ToDoItem>?>(null) }
    // Bump to force a re-fetch after a mutation without re-keying on the list.
    var refreshTick by remember(entityId) { mutableIntStateOf(0) }

    if (repo != null && entityId != null) {
        LaunchedEffect(entityId, refreshTick) {
            repo.fetchTodoItems(entityId)
                .onSuccess { items = it }
                .onFailure { items = emptyList() }
        }
    }

    CardSurface(modifier = modifier, title = title?.takeUnless { it.isNullOrBlank() }) {
        val rows = items
        when {
            entityId == null -> EmptyRow(text = "No to-do list configured")
            repo == null -> EmptyRow(text = "To-do list unavailable")
            rows == null -> EmptyRow(text = "Loading…")
            else -> {
                val sorted = when (sortMode?.lowercase()) {
                    "alpha" -> rows.sortedBy { it.summary.lowercase() }
                    "duedate" -> rows.sortedWith(
                        compareBy(nullsLast()) { it.due },
                    )
                    else -> rows
                }
                val visible = (if (hideCompleted) sorted.filter { !it.completed } else sorted)
                    .take(MAX_TODO_ROWS)
                if (visible.isEmpty()) {
                    EmptyRow(text = if (rows.isEmpty()) "No items" else "All done")
                } else {
                    visible.forEachIndexed { idx, item ->
                        if (idx > 0) TodoDivider()
                        TodoRow(
                            item = item,
                            onToggle = if (item.uid != null) {
                                {
                                    scope.launch {
                                        repo.updateTodoItem(entityId, item.uid, !item.completed)
                                        refreshTick++
                                    }
                                }
                            } else null,
                        )
                    }
                }
            }
        }
        // Add-item input: shown by default, hidden when hide_create=true or
        // the entity/repo is unavailable (nothing to submit to).
        if (!hideCreate && entityId != null && repo != null) {
            TodoDivider()
            TodoAddRow(
                value = newItemText,
                onValueChange = { newItemText = it },
                onAdd = {
                    val summary = newItemText.trim()
                    if (summary.isNotBlank()) {
                        newItemText = ""
                        scope.launch {
                            repo.addTodoItem(entityId, summary)
                            refreshTick++
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun TodoRow(item: ToDoItem, onToggle: (() -> Unit)?) {
    val accent = if (item.completed) R1.AccentGreen else R1.InkSoft
    val base = Modifier
        .fillMaxWidth()
        .heightIn(min = R1.MinTarget)
    val rowMod = if (onToggle != null) base.r1Pressable(onClick = onToggle) else base
    Row(
        modifier = rowMod.padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(R1.ShapeS)
                .background(if (item.completed) accent.copy(alpha = 0.18f) else R1.SurfaceMuted)
                .border(1.dp, if (item.completed) accent else R1.Hairline, R1.ShapeS),
            contentAlignment = Alignment.Center,
        ) {
            if (item.completed) {
                Text(text = "✓", style = R1.labelMicro, color = accent)
            }
        }
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.summary,
                style = R1.body,
                color = if (item.completed) R1.InkMuted else R1.Ink,
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val sub = item.due ?: item.description
            if (!sub.isNullOrBlank()) {
                Spacer(Modifier.padding(top = 2.dp))
                Text(
                    text = sub,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * Inline add-item input row. Pressing the keyboard action or the add button
 * submits [onAdd]; the field is cleared by the caller after submission so it
 * stays empty for the next item. [ImeAction.Done] lets the user stay in the
 * field and add multiple items without lifting their finger.
 */
@Composable
private fun TodoAddRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAdd: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = {
                Text(text = "Add item…", style = R1.body, color = R1.InkMuted)
            },
            singleLine = true,
            textStyle = R1.body,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onAdd() }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                errorContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f),
        )
        Text(
            text = "ADD",
            style = R1.labelMicro,
            color = if (value.isNotBlank()) R1.AccentWarm else R1.InkMuted,
            modifier = Modifier
                .r1Pressable(onClick = onAdd)
                .padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

@Composable
private fun TodoDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}
