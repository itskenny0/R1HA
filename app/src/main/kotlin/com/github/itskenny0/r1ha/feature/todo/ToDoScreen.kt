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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.ToDoItem
import com.github.itskenny0.r1ha.core.ha.ToDoList
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Drives every `todo.*` integration HA exposes: shopping list, Local
 * To-do, Google Tasks, CalDAV, etc. List picker chips at the top switch
 * between todo entities; the body shows the items in the active list
 * with a checkbox toggle, a remove (X) button per row, and an input
 * field at the bottom for adding new items. Wheel input scrolls the
 * item list.
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
                val hasCompleted = ui.items.any { it.completed }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (hasCompleted) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeS)
                                .r1Pressable(onClick = { vm.clearCompleted() })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "CLEAR DONE",
                                style = R1.labelMicro,
                                color = R1.AccentWarm,
                            )
                        }
                    }
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = { vm.refresh() })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(
                            text = if (ui.loadingItems || ui.loadingLists) "…" else "REFRESH",
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
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
                        ui.loadingLists && ui.lists.isEmpty() ->
                            CenteredSpinner()
                        ui.lists.isEmpty() ->
                            EmptyText(
                                "No to-do lists found.\n\n" +
                                "Add one in Home Assistant: Settings → Devices & services " +
                                "→ Add integration → Local To-do. Or install one of the " +
                                "Google Tasks / CalDAV / Shopping List integrations.",
                            )
                        ui.activeEntityId == null ->
                            EmptyText("Pick a list to view items.")
                        ui.loadingItems && ui.items.isEmpty() ->
                            CenteredSpinner()
                        ui.items.isEmpty() ->
                            EmptyText("List is empty.")
                        else ->
                            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                                isRefreshing = ui.loadingItems,
                                onRefresh = { ui.activeEntityId?.let { vm.refresh() } },
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                ItemList(
                                    items = ui.items,
                                    pendingItems = ui.pendingItems,
                                    listState = listState,
                                    onToggle = vm::toggleCompleted,
                                    onRemove = vm::remove,
                                )
                            }
                    }
                }
                if (ui.error != null && ui.items.isEmpty()) {
                    Text(
                        text = "Error: ${ui.error}",
                        style = R1.body,
                        color = R1.StatusRed,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        lists.forEach { l ->
            val selected = l.entityId == activeEntityId
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (selected) R1.AccentWarm else R1.SurfaceMuted)
                    .border(1.dp, if (selected) R1.AccentWarm else R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { onPick(l.entityId) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = l.friendlyName.uppercase(),
                    style = R1.labelMicro,
                    color = if (selected) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

@Composable
private fun ItemList(
    items: List<ToDoItem>,
    pendingItems: Set<String>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onToggle: (ToDoItem) -> Unit,
    onRemove: (ToDoItem) -> Unit,
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.uid }) { item ->
            val pending = item.uid in pendingItems
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Checkbox-style tap target — accent-fill when completed,
                // outlined empty when pending action.
                Box(
                    modifier = Modifier
                        .size(20.dp)
                        .clip(R1.ShapeS)
                        .background(if (item.completed) R1.AccentGreen else R1.SurfaceMuted)
                        .border(1.dp, if (item.completed) R1.AccentGreen else R1.InkMuted, R1.ShapeS)
                        .r1Pressable(onClick = { onToggle(item) }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (item.completed) {
                        Text(text = "✓", style = R1.labelMicro, color = R1.Bg)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item.summary,
                    style = R1.body,
                    color = if (item.completed) R1.InkMuted else R1.Ink,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // Remove "✕" — 32-by-32 hit area with R1's standard
                // accessibility expansion via r1Pressable.
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(R1.ShapeS)
                        .r1Pressable(onClick = { onRemove(item) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (pending) "…" else "✕",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
        }
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
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
        Spacer(Modifier.width(8.dp))
        val canSubmit = enabled && draft.isNotBlank()
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (canSubmit) R1.AccentWarm else R1.SurfaceMuted)
                .let { if (canSubmit) it.r1Pressable(onClick = onSubmit) else it }
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text = "ADD",
                style = R1.labelMicro,
                color = if (canSubmit) R1.Bg else R1.InkMuted,
            )
        }
    }
}

@Composable
private fun CenteredSpinner() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(22.dp),
            strokeWidth = 2.dp,
            color = R1.AccentWarm,
        )
    }
}

@Composable
private fun EmptyText(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = message,
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}
