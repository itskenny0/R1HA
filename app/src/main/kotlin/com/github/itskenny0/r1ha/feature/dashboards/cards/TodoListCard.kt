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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.ToDoItem
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.theme.LocalHaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.todo.ToDoViewModel
import com.github.itskenny0.r1ha.ui.components.MarkdownView
import com.github.itskenny0.r1ha.ui.components.parseMarkdown
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDate

/** Cap rendered rows so a long shopping list doesn't blow up the card. */
private const val MAX_TODO_ROWS = 40

// HA's TodoListEntityFeature bits (src/data/todo.ts).
private const val TODO_FEATURE_CREATE = 1
private const val TODO_FEATURE_DELETE = 2
private const val TODO_FEATURE_UPDATE = 4
private const val TODO_FEATURE_MOVE = 8

/**
 * Renderer for HA's `todo-list` card (hui-todo-list-card.ts). Fetches the
 * configured todo entity's items off [LocalHaRepository] and lists them split
 * into Unchecked / Completed sections, with full parity for the dashboard card:
 *
 *  - `display_order` sorting (none / alpha_asc / alpha_desc / duedate_asc /
 *    duedate_desc), with the legacy `sort` shorthand still accepted.
 *  - section grouping with headers (`hide_section_headers` to suppress them).
 *  - `due_date_period` (day / week / month / year + offset) calendar filter.
 *  - tapping a row opens an edit dialog (rename, due date via the native picker,
 *    description) firing `todo.update_item`; the checkbox flips completion.
 *  - a per-item delete (DELETE_TODO_ITEM) and a "clear completed" action.
 *  - wheel-driven move up / down (MOVE_TODO_ITEM) via `todo/item/move`.
 *  - due dates render relative with an overdue accent; descriptions render as
 *    markdown.
 *  - live updates: the fetch re-runs whenever the list entity's state changes
 *    (HA writes the item count into the state), so external edits appear without
 *    re-entering the screen.
 *
 * R1HA's typed model has no dedicated todo-list variant, so the entity id is
 * taken from the [LovelaceCard.Unsupported.entityRefs] the parser captures off
 * the card's `entity` key.
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
    val state = entityId?.let { stateMap.byRaw(it) }
    val title = card.raw["title"]?.let { (it as? JsonPrimitive)?.content }
        ?: entityId?.let { resolveName(null, state, it) }
    val hideCompleted = card.raw.boolOption("hide_completed")
    val hideCreate = card.raw.boolOption("hide_create")
    val hideSectionHeaders = card.raw.boolOption("hide_section_headers")
    // `display_order` (HA editor key); `sort` is the earlier R1HA shorthand.
    val sortMode = TodoSortMode.parse(
        (card.raw["display_order"] as? JsonPrimitive)?.content
            ?: (card.raw["sort"] as? JsonPrimitive)?.content,
    )
    val duePeriod = parseDuePeriod((card.raw["due_date_period"] as? JsonPrimitive)?.content)
    val dueOffset = (card.raw["due_date_period_offset"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0

    // Supported-features gating (0 = unknown -> allow, so a provider omitting the
    // bitmask still offers the controls).
    val features = state?.supportedFeatures ?: 0
    val canDelete = features == 0 || (features and TODO_FEATURE_DELETE) != 0
    val canUpdate = features == 0 || (features and TODO_FEATURE_UPDATE) != 0
    val canMove = sortMode == TodoSortMode.NONE && (features and TODO_FEATURE_MOVE) != 0
    val canCreate = features == 0 || (features and TODO_FEATURE_CREATE) != 0

    var newItemText by remember(entityId) { mutableStateOf("") }
    var items by remember(entityId) { mutableStateOf<List<ToDoItem>?>(null) }
    var refreshTick by remember(entityId) { mutableIntStateOf(0) }
    var editing by remember(entityId) { mutableStateOf<ToDoItem?>(null) }

    // Live updates: HA writes the item count into the entity state, so re-fetch
    // whenever that state value changes (external edits / completions) as well as
    // after our own mutations (refreshTick).
    val liveState = state?.rawState
    if (repo != null && entityId != null) {
        LaunchedEffect(entityId, refreshTick, liveState) {
            repo.fetchTodoItems(entityId)
                .onSuccess { items = it }
                .onFailure { items = emptyList() }
        }
    }

    fun refetch() { refreshTick++ }

    CardSurface(modifier = modifier, title = title?.takeUnless { it.isNullOrBlank() }) {
        val rows = items
        when {
            entityId == null -> EmptyRow(text = "No to-do list configured")
            repo == null -> EmptyRow(text = "To-do list unavailable")
            rows == null -> EmptyRow(text = "Loading…")
            else -> {
                val today = LocalDate.now()
                val filtered = filterTodoByDuePeriod(rows, duePeriod, dueOffset, today)
                val sorted = sortTodoItems(filtered, sortMode)
                val sections = groupTodoSections(sorted)
                val anyShown = sections.active.isNotEmpty() ||
                    (!hideCompleted && sections.completed.isNotEmpty())
                if (!anyShown) {
                    EmptyRow(text = if (rows.isEmpty()) "No items" else "All done")
                } else {
                    var rowsDrawn = 0
                    // Active (unchecked) section.
                    sections.active.take(MAX_TODO_ROWS).forEachIndexed { idx, item ->
                        if (rowsDrawn > 0) TodoDivider()
                        TodoRow(
                            item = item,
                            today = today,
                            onToggle = if (item.uid != null && canUpdate) {
                                { scope.launch { repo.updateTodoItem(entityId, item.uid, true); refetch() } }
                            } else null,
                            onTap = if (item.uid != null && canUpdate) { { editing = item } } else null,
                            onDelete = if (item.uid != null && canDelete) {
                                { scope.launch { repo.removeTodoItem(entityId, item.uid); refetch() } }
                            } else null,
                            onMoveUp = if (item.uid != null && canMove && idx > 0) {
                                {
                                    // Move above the previous item: land after the item before that.
                                    val prevPrev = sections.active.getOrNull(idx - 2)?.uid
                                    scope.launch { repo.moveTodoItem(entityId, item.uid, prevPrev); refetch() }
                                }
                            } else null,
                            onMoveDown = if (item.uid != null && canMove && idx < sections.active.lastIndex) {
                                {
                                    val next = sections.active.getOrNull(idx + 1)?.uid
                                    scope.launch { repo.moveTodoItem(entityId, item.uid, next); refetch() }
                                }
                            } else null,
                        )
                        rowsDrawn++
                    }
                    // Completed section (with optional header), unless hidden.
                    if (!hideCompleted && sections.completed.isNotEmpty()) {
                        if (!hideSectionHeaders) TodoSectionHeader("Completed")
                        sections.completed.take(MAX_TODO_ROWS).forEach { item ->
                            if (rowsDrawn > 0) TodoDivider()
                            TodoRow(
                                item = item,
                                today = today,
                                onToggle = if (item.uid != null && canUpdate) {
                                    { scope.launch { repo.updateTodoItem(entityId, item.uid, false); refetch() } }
                                } else null,
                                onTap = if (item.uid != null && canUpdate) { { editing = item } } else null,
                                onDelete = if (item.uid != null && canDelete) {
                                    { scope.launch { repo.removeTodoItem(entityId, item.uid); refetch() } }
                                } else null,
                                onMoveUp = null,
                                onMoveDown = null,
                            )
                            rowsDrawn++
                        }
                    }
                }
                // "Clear completed" action when the provider supports delete and
                // there is at least one completed item to clear.
                if (canDelete && rows.any { it.completed }) {
                    TodoDivider()
                    TodoActionRow(label = "CLEAR COMPLETED") {
                        scope.launch { repo.clearCompletedTodoItems(entityId); refetch() }
                    }
                }
            }
        }
        // Add-item input: shown by default, hidden when hide_create=true, the
        // provider doesn't support CREATE, or the entity/repo is unavailable.
        if (!hideCreate && canCreate && entityId != null && repo != null) {
            TodoDivider()
            TodoAddRow(
                value = newItemText,
                onValueChange = { newItemText = it },
                onAdd = {
                    val summary = newItemText.trim()
                    if (summary.isNotBlank()) {
                        newItemText = ""
                        scope.launch { repo.addTodoItem(entityId, summary); refetch() }
                    }
                },
            )
        }
    }

    val edit = editing
    if (edit != null && edit.uid != null && entityId != null && repo != null) {
        TodoEditDialog(
            item = edit,
            onDismiss = { editing = null },
            onSave = { summary, description, due ->
                editing = null
                scope.launch {
                    repo.editTodoItem(entityId, edit.uid, summary = summary, description = description, due = due)
                    refetch()
                }
            },
        )
    }
}

private fun kotlinx.serialization.json.JsonObject.boolOption(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBoolean() ?: false

@Composable
private fun TodoRow(
    item: ToDoItem,
    today: LocalDate,
    onToggle: (() -> Unit)?,
    onTap: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
) {
    val accent = if (item.completed) R1.AccentGreen else R1.InkSoft
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox (flips completion).
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(R1.ShapeS)
                .background(if (item.completed) accent.copy(alpha = 0.18f) else R1.SurfaceMuted)
                .border(1.dp, if (item.completed) accent else R1.Hairline, R1.ShapeS)
                .let { if (onToggle != null) it.r1Pressable(onClick = onToggle, contentDescription = "Toggle item") else it },
            contentAlignment = Alignment.Center,
        ) {
            if (item.completed) Text(text = "✓", style = R1.labelMicro, color = accent)
        }
        Spacer(Modifier.width(10.dp))
        // Summary + due + description, tappable to open the edit dialog.
        Column(
            modifier = Modifier
                .weight(1f)
                .let { if (onTap != null) it.r1Pressable(onClick = onTap, contentDescription = "Edit item") else it },
        ) {
            Text(
                text = item.summary,
                style = R1.body,
                color = if (item.completed) R1.InkMuted else R1.Ink,
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            val due = ToDoViewModel.formatDue(item.due, today)
            if (due != null) {
                val dueColor = when {
                    item.completed -> R1.InkMuted
                    due.urgency == ToDoViewModel.DueUrgency.OVERDUE -> R1.StatusRed
                    due.urgency == ToDoViewModel.DueUrgency.TODAY -> R1.AccentWarm
                    else -> R1.InkMuted
                }
                Spacer(Modifier.height(2.dp))
                Text(text = "DUE ${due.label}", style = R1.labelMicro, color = dueColor, maxLines = 1)
            }
            val description = item.description
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(2.dp))
                // Descriptions render as markdown (HA's ha-markdown-element).
                MarkdownView(nodes = remember(description) { parseMarkdown(description) })
            }
        }
        // Reorder affordances (wheel-friendly up/down on the R1, in place of HA's
        // drag handles).
        if (onMoveUp != null || onMoveDown != null) {
            Spacer(Modifier.width(6.dp))
            TodoIconButton(glyph = "▲", enabled = onMoveUp != null, onClick = onMoveUp)
            TodoIconButton(glyph = "▼", enabled = onMoveDown != null, onClick = onMoveDown)
        }
        // Per-item delete.
        if (onDelete != null) {
            Spacer(Modifier.width(4.dp))
            TodoIconButton(glyph = "✕", enabled = true, onClick = onDelete, accent = R1.StatusRed)
        }
    }
}

@Composable
private fun TodoIconButton(
    glyph: String,
    enabled: Boolean,
    onClick: (() -> Unit)?,
    accent: Color = R1.InkSoft,
) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(R1.ShapeS)
            .let { if (enabled && onClick != null) it.r1Pressable(onClick = onClick) else it },
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = R1.labelMicro, color = if (enabled) accent else R1.InkMuted)
    }
}

@Composable
private fun TodoSectionHeader(label: String) {
    Text(
        text = label,
        style = R1.sectionHeader,
        color = R1.InkSoft,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 6.dp),
    )
}

@Composable
private fun TodoActionRow(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = R1.labelMicro,
        color = R1.AccentWarm,
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = onClick, contentDescription = label)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    )
}

/**
 * Inline add-item input row. Pressing the keyboard action or the add button
 * submits [onAdd]; the field is cleared by the caller after submission so it
 * stays empty for the next item.
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
            placeholder = { Text(text = "Add item…", style = R1.body, color = R1.InkMuted) },
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
            modifier = Modifier.r1Pressable(onClick = onAdd).padding(horizontal = 8.dp, vertical = 12.dp),
        )
    }
}

/**
 * The item edit dialog: rename, set a due date via the native date picker, and
 * edit the description. Fires `todo.update_item` with the changed fields. A bare
 * date due value is sent (this card edits date-only due dates; the time-of-day
 * variant is left to the more-info / dedicated To-Do screen).
 */
@Composable
private fun TodoEditDialog(
    item: ToDoItem,
    onDismiss: () -> Unit,
    onSave: (summary: String?, description: String?, due: String?) -> Unit,
) {
    val context = LocalContext.current
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        var summary by remember { mutableStateOf(item.summary) }
        var description by remember { mutableStateOf(item.description.orEmpty()) }
        var due by remember { mutableStateOf(dueLocalDate(item.due)?.toString().orEmpty()) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(16.dp),
        ) {
            Text(text = "EDIT ITEM", style = R1.sectionHeader, color = R1.InkSoft)
            Spacer(Modifier.height(10.dp))
            EditField(label = "Name", value = summary, onValueChange = { summary = it })
            Spacer(Modifier.height(8.dp))
            EditField(label = "Description", value = description, onValueChange = { description = it })
            Spacer(Modifier.height(8.dp))
            // Due date via the native picker.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (due.isBlank()) "Set due date" else "Due $due",
                    style = R1.body,
                    color = R1.Ink,
                    modifier = Modifier
                        .weight(1f)
                        .r1Pressable(onClick = {
                            showDatePicker(context, due.ifBlank { LocalDate.now().toString() }) { picked -> due = picked }
                        }, contentDescription = "Pick due date")
                        .padding(vertical = 10.dp),
                )
                if (due.isNotBlank()) {
                    Text(
                        text = "CLEAR",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        modifier = Modifier.r1Pressable(onClick = { due = "" }).padding(8.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Text(
                    text = "CANCEL",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.r1Pressable(onClick = onDismiss).padding(horizontal = 12.dp, vertical = 10.dp),
                )
                val changed = summary.trim() != item.summary ||
                    description.trim() != item.description.orEmpty() ||
                    due != (dueLocalDate(item.due)?.toString().orEmpty())
                val canSave = summary.isNotBlank() && changed
                Box(
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(if (canSave) R1.AccentWarm else R1.SurfaceMuted)
                        .let {
                            if (canSave) {
                                it.r1Pressable(onClick = {
                                    onSave(
                                        summary.trim().takeIf { s -> s != item.summary },
                                        description.trim().takeIf { d -> d != item.description.orEmpty() },
                                        due.takeIf { it != (dueLocalDate(item.due)?.toString().orEmpty()) },
                                    )
                                }, contentDescription = "Save edit")
                            } else {
                                it
                            }
                        }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "SAVE", style = R1.labelMicro, color = if (canSave) R1.Bg else R1.InkMuted)
                }
            }
        }
    }
}

@Composable
private fun EditField(label: String, value: String, onValueChange: (String) -> Unit) {
    TextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(text = label, style = R1.labelMicro, color = R1.InkMuted) },
        singleLine = label != "Description",
        textStyle = R1.body,
        colors = TextFieldDefaults.colors(
            focusedContainerColor = R1.SurfaceMuted,
            unfocusedContainerColor = R1.SurfaceMuted,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
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
