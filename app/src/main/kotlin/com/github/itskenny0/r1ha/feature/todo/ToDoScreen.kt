package com.github.itskenny0.r1ha.feature.todo

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ToDoList
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Drives every `todo.*` integration HA exposes: shopping list, Local
 * To-do, Google Tasks, CalDAV, etc. List picker chips at the top switch
 * between todo entities; the body shows the items in the active list
 * split into an ACTIVE section and a COMPLETED section (matching the
 * Lovelace card's unchecked / checked grouping), each row carrying a
 * checkbox toggle, an edit (rename) and a remove (X) button, due date /
 * description shown when the provider supplies them, and an input field at
 * the bottom for adding new items. Wheel input scrolls the item list.
 *
 * REST-backed: items come from `todo.get_items?return_response=true`
 * which HA gained in 2024.1. Older HA servers will see an empty list
 * with the integration's error toast.
 */
@Composable
fun ToDoScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: ToDoViewModel = viewModel(factory = ToDoViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    // Today, resolved once per composition pass. The due-date formatter takes
    // this so its relative-day labels ("TODAY", "OVERDUE") stay testable.
    val today = remember { java.time.LocalDate.now() }

    var editing by remember { mutableStateOf<ToDoViewModel.Item?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "TO-DO",
            onBack = onBack,
            action = {
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.xs)) {
                    R1Chip(
                        text = if (ui.loadingItems || ui.loadingLists) "…" else "REFRESH",
                        onClick = { vm.refresh() },
                        contentDescription = "Refresh",
                    )
                }
            },
        )

        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                ListPickerChips(
                    lists = ui.lists,
                    activeEntityId = ui.activeEntityId,
                    onPick = vm::selectList,
                )
                Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    when {
                        ui.loadingLists && ui.lists.isEmpty() -> Box(
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Loading to-do lists"
                            },
                        ) {
                            SkeletonList()
                        }
                        ui.error != null && ui.lists.isEmpty() ->
                            EmptyText("Could not load to-do lists.\n\n${ui.error}")
                        ui.lists.isEmpty() ->
                            EmptyText(
                                "No to-do lists found.\n\n" +
                                "Add one in Home Assistant: Settings, Devices & services, " +
                                "Add integration, Local To-do. Or install one of the " +
                                "Google Tasks / CalDAV / Shopping List integrations.",
                            )
                        ui.activeEntityId == null ->
                            EmptyText("Pick a list to view items.")
                        ui.loadingItems && ui.items.isEmpty() -> Box(
                            modifier = Modifier.semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Loading to-do items"
                            },
                        ) {
                            SkeletonList()
                        }
                        ui.items.isEmpty() ->
                            EmptyText("List is empty.\n\nType below to add the first item.")
                        else ->
                            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                                isRefreshing = ui.loadingItems,
                                onRefresh = { ui.activeEntityId?.let { vm.refresh() } },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                ItemList(
                                    activeItems = ui.activeItems,
                                    completedItems = ui.completedItems,
                                    showCompleted = ui.showCompleted,
                                    pendingItems = ui.pendingItems,
                                    today = today,
                                    listState = listState,
                                    onToggle = vm::toggleCompleted,
                                    onEdit = { editing = it },
                                    onRemove = vm::remove,
                                    onToggleShowCompleted = vm::toggleShowCompleted,
                                    onClearCompleted = vm::clearCompleted,
                                )
                            }
                    }
                }
                if (ui.error != null && ui.items.isEmpty() && ui.lists.isNotEmpty()) {
                    Text(
                        text = "Error: ${ui.error}",
                        style = responsiveType(R1.body),
                        color = R1.StatusRed,
                        modifier = Modifier.padding(horizontal = R1.space.m, vertical = R1.space.s),
                    )
                }
                AddItemRow(
                    draft = ui.draft,
                    enabled = ui.activeEntityId != null,
                    onDraftChange = vm::setDraft,
                    onSubmit = vm::addDraftItem,
                )
            }
        }
    }

    editing?.let { item ->
        EditItemDialog(
            item = item,
            onConfirm = { newSummary ->
                vm.rename(item, newSummary)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun ListPickerChips(
    lists: List<ToDoList>,
    activeEntityId: String?,
    onPick: (String) -> Unit,
) {
    if (lists.isEmpty()) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        lists.forEach { l ->
            R1Chip(
                text = l.friendlyName.uppercase(),
                variant = R1ChipVariant.Filter,
                selected = l.entityId == activeEntityId,
                onClick = { onPick(l.entityId) },
                contentDescription = l.friendlyName,
            )
        }
    }
}

/**
 * The item body, split into an ACTIVE section (needs_action) and a
 * COMPLETED section (completed), mirroring the Lovelace card's unchecked /
 * checked grouping. The completed section header carries a SHOW / HIDE
 * toggle and a CLEAR affordance; collapsing it keeps the focus on what's
 * still outstanding without losing the count.
 */
@Composable
private fun ItemList(
    activeItems: List<ToDoViewModel.Item>,
    completedItems: List<ToDoViewModel.Item>,
    showCompleted: Boolean,
    pendingItems: Set<String>,
    today: java.time.LocalDate,
    listState: LazyListState,
    onToggle: (ToDoViewModel.Item) -> Unit,
    onEdit: (ToDoViewModel.Item) -> Unit,
    onRemove: (ToDoViewModel.Item) -> Unit,
    onToggleShowCompleted: () -> Unit,
    onClearCompleted: () -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        if (activeItems.isNotEmpty()) {
            item(key = "header-active") {
                SectionHeader(text = "ACTIVE (${activeItems.size})")
            }
            items(activeItems, key = { it.key }) { item ->
                ItemRow(
                    item = item,
                    pending = item.key in pendingItems,
                    today = today,
                    onToggle = onToggle,
                    onEdit = onEdit,
                    onRemove = onRemove,
                )
            }
        }

        if (completedItems.isNotEmpty()) {
            item(key = "header-completed") {
                CompletedHeader(
                    count = completedItems.size,
                    showCompleted = showCompleted,
                    onToggleShowCompleted = onToggleShowCompleted,
                    onClearCompleted = onClearCompleted,
                )
            }
            if (showCompleted) {
                items(completedItems, key = { it.key }) { item ->
                    ItemRow(
                        item = item,
                        pending = item.key in pendingItems,
                        today = today,
                        onToggle = onToggle,
                        onEdit = onEdit,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = responsiveType(R1.sectionHeader),
        color = R1.InkSoft,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    )
}

/**
 * Completed-section header: the count plus a SHOW / HIDE chip and a CLEAR
 * chip. CLEAR fires `remove_completed_items`; SHOW / HIDE only collapses the
 * rows locally so the user can declutter without deleting their history.
 */
@Composable
private fun CompletedHeader(
    count: Int,
    showCompleted: Boolean,
    onToggleShowCompleted: () -> Unit,
    onClearCompleted: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Text(
            text = "COMPLETED ($count)",
            style = responsiveType(R1.sectionHeader),
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
        )
        R1Chip(
            text = if (showCompleted) "HIDE" else "SHOW",
            onClick = onToggleShowCompleted,
            contentDescription = if (showCompleted) "Hide completed items" else "Show completed items",
        )
        R1Chip(
            text = "CLEAR",
            tone = R1.AccentWarm,
            onClick = onClearCompleted,
            contentDescription = "Clear completed items",
        )
    }
}

@Composable
private fun ItemRow(
    item: ToDoViewModel.Item,
    pending: Boolean,
    today: java.time.LocalDate,
    onToggle: (ToDoViewModel.Item) -> Unit,
    onEdit: (ToDoViewModel.Item) -> Unit,
    onRemove: (ToDoViewModel.Item) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Checkbox: a 20dp glyph centred inside a 48dp tap target so the
        // touchable area meets R1.MinTarget even though the visual is small.
        Box(
            modifier = Modifier
                .sizeIn(minWidth = R1.MinTarget, minHeight = R1.MinTarget)
                .r1Pressable(
                    onClick = { onToggle(item) },
                    contentDescription = if (item.completed) {
                        "Mark ${item.summary} not done"
                    } else {
                        "Mark ${item.summary} done"
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(R1.space.xl)
                    .clip(R1.ShapeS)
                    .background(if (item.completed) R1.AccentGreen else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (item.completed) R1.AccentGreen else R1.InkMuted,
                        R1.ShapeS,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                if (item.completed) {
                    Text(text = "✓", style = R1.labelMicro, color = R1.Bg)
                }
            }
        }
        Spacer(Modifier.width(R1.space.xs))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.summary,
                style = responsiveType(R1.body),
                color = if (item.completed) R1.InkMuted else R1.Ink,
                textDecoration = if (item.completed) TextDecoration.LineThrough else null,
            )
            val due = ToDoViewModel.formatDue(item.due, today)
            if (due != null) {
                // Overdue items redden; due-today nudges with the warm accent;
                // everything further out reads in the muted ink so it doesn't
                // shout. Completed rows mute the due colour regardless.
                val dueColor = when {
                    item.completed -> R1.InkMuted
                    due.urgency == ToDoViewModel.DueUrgency.OVERDUE -> R1.StatusRed
                    due.urgency == ToDoViewModel.DueUrgency.TODAY -> R1.AccentWarm
                    else -> R1.InkSoft
                }
                Text(
                    text = "DUE ${due.label}",
                    style = responsiveType(R1.labelMicro),
                    color = dueColor,
                )
            }
            val description = item.description
            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = responsiveType(R1.body),
                    color = R1.InkMuted,
                )
            }
        }
        Spacer(Modifier.width(R1.space.xs))
        // Edit (rename): a glyph in a 48dp tap target.
        IconGlyph(
            glyph = "✎",
            contentDescription = "Edit ${item.summary}",
            onClick = { onEdit(item) },
        )
        // Remove "✕": a glyph in a 48dp tap target; shows "…" while a remove
        // (or any other mutation on this row) is in flight.
        IconGlyph(
            glyph = if (pending) "…" else "✕",
            contentDescription = "Remove ${item.summary}",
            onClick = { onRemove(item) },
        )
    }
}

@Composable
private fun IconGlyph(
    glyph: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = R1.MinTarget, minHeight = R1.MinTarget)
            .clip(R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = contentDescription),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = glyph, style = responsiveType(R1.label), color = R1.InkMuted)
    }
}

@Composable
private fun AddItemRow(
    draft: String,
    enabled: Boolean,
    onDraftChange: (String) -> Unit,
    onSubmit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        R1TextField(
            value = draft,
            onValueChange = onDraftChange,
            placeholder = if (enabled) "ADD AN ITEM" else "NO LIST SELECTED",
            enabled = enabled,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (enabled) onSubmit() }),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(R1.space.s))
        val canSubmit = enabled && draft.isNotBlank()
        Box(
            modifier = Modifier
                .heightIn(min = R1.MinTarget)
                .clip(R1.ShapeS)
                .background(if (canSubmit) R1.AccentWarm else R1.SurfaceMuted)
                .let { if (canSubmit) it.r1Pressable(onClick = onSubmit, contentDescription = "Add item") else it }
                .padding(horizontal = R1.space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "ADD",
                style = responsiveType(R1.labelMicro),
                color = if (canSubmit) R1.Bg else R1.InkMuted,
            )
        }
    }
}

/**
 * Modal rename sheet. A single text field seeded with the current summary,
 * a CANCEL and a SAVE affordance. SAVE is disabled while the field is blank
 * or unchanged so we never fire a no-op `update_item`.
 */
@Composable
private fun EditItemDialog(
    item: ToDoViewModel.Item,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        var text by remember { mutableStateOf(item.summary) }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                // Cap so the rename sheet reads as a centred card on roomy tiers
                // instead of stretching wall-wide; the platform Dialog already
                // keeps it inset from the edges on the mini panel.
                .widthIn(max = 480.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .padding(R1.space.l),
            verticalArrangement = Arrangement.spacedBy(R1.space.m),
        ) {
            Text(text = "EDIT ITEM", style = responsiveType(R1.sectionHeader), color = R1.InkSoft)
            R1TextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "ITEM NAME",
                monospace = false,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    if (text.isNotBlank() && text.trim() != item.summary) onConfirm(text)
                }),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(R1.space.s, Alignment.End),
            ) {
                R1Chip(
                    text = "CANCEL",
                    onClick = onDismiss,
                    contentDescription = "Cancel edit",
                )
                val canSave = text.isNotBlank() && text.trim() != item.summary
                Box(
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(if (canSave) R1.AccentWarm else R1.SurfaceMuted)
                        .let { if (canSave) it.r1Pressable(onClick = { onConfirm(text) }, contentDescription = "Save edit") else it }
                        .padding(horizontal = R1.space.m),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "SAVE",
                        style = responsiveType(R1.labelMicro),
                        color = if (canSave) R1.Bg else R1.InkMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(R1.space.xl),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = responsiveType(R1.body),
            color = R1.InkMuted,
        )
    }
}
