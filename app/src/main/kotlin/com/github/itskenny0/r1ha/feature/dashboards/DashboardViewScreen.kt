package com.github.itskenny0.r1ha.feature.dashboards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideApplier
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrides
import com.github.itskenny0.r1ha.core.lovelace.PICKER_TEMPLATES
import com.github.itskenny0.r1ha.core.lovelace.ViewOverride
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.lovelace.renderWithFlags
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.feature.dashboards.cards.LovelaceCardRenderer
import com.github.itskenny0.r1ha.feature.dashboards.cards.dispatchLovelaceAction
import com.github.itskenny0.r1ha.ui.components.DragReorderColumn
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * Full-screen renderer for one Lovelace view. Reads the parsed config
 * from the shared [DashboardsViewModel] (so the dashboard list and the
 * view-screen share the cache), applies the local override layer, and
 * renders the resulting card list either in reorderable edit mode or
 * the regular scrollable view.
 *
 * Edit mode (top-bar EDIT chip):
 *  - Cards become drag-and-droppable via [DragReorderColumn] (long-press
 *    a card to grab it).
 *  - Each card sprouts a small EDIT / DELETE chip strip below it.
 *  - A floating ADD CARD chip pops up the [CardPickerSheet] for the
 *    user to insert a new card.
 *  - A "show HA layout / show overrides" toggle in the top bar flips
 *    between the imported config and the locally-customised view.
 *
 * Visual idiom is identical to view mode but with a subtle warm-accent
 * tint on the screen background (a thin top bar gradient) so the user
 * always knows they're editing.
 */
@Composable
fun DashboardViewScreen(
    haRepository: HaRepository,
    overrideStore: LovelaceOverrideStore,
    dashboardUrlPath: String?,
    viewPath: String,
    onBack: () -> Unit,
) {
    val vm: DashboardsViewModel = viewModel(
        factory = DashboardsViewModel.factory(haRepository, overrideStore),
    )
    val state by vm.state.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val entities by vm.entities.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(dashboardUrlPath, viewPath) {
        vm.loadConfig(dashboardUrlPath)
        // Wait for the config to land in state, then pick the view.
        // selectView is a synchronous read of `_state` so we re-poll;
        // a tiny suspend yield avoids spinning the dispatcher.
        repeat(30) {
            if (state.configs.containsKey(dashboardUrlPath ?: DashboardsViewModel.DEFAULT_KEY)) {
                vm.selectView(dashboardUrlPath, viewPath); return@LaunchedEffect
            }
            kotlinx.coroutines.delay(100)
        }
    }

    val configKey = dashboardUrlPath ?: DashboardsViewModel.DEFAULT_KEY
    val config = state.configs[configKey]
    val view = config?.views?.firstOrNull { it.path == viewPath }
    val viewOverrideKey = LovelaceOverrides.keyFor(dashboardUrlPath, viewPath)
    val viewOverride = if (state.showOriginal) ViewOverride() else (overrides.views[viewOverrideKey] ?: ViewOverride())
    val renderedCards: List<LovelaceCard> = remember(view, viewOverride) {
        if (view == null) emptyList()
        else LovelaceOverrideApplier.apply(view.cards, viewOverride)
    }
    val renderedWithFlags = remember(view, viewOverride) {
        if (view == null) emptyList()
        else renderWithFlags(view.cards, viewOverride)
    }
    val showPicker = remember { mutableStateOf(false) }
    val editingIndex = remember { mutableStateOf<Int?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = view?.title?.takeUnless { it.isBlank() } ?: viewPath,
            onBack = onBack,
            action = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (viewOverride.operations.isNotEmpty() && !state.editMode) {
                        TopChip(
                            text = if (state.showOriginal) "OVERRIDES" else "HA LAYOUT",
                            accent = R1.InkSoft,
                            onClick = { vm.setShowOriginal(!state.showOriginal) },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
                    TopChip(
                        text = if (state.editMode) "DONE" else "EDIT",
                        accent = if (state.editMode) R1.AccentGreen else R1.AccentWarm,
                        onClick = { vm.setEditMode(!state.editMode) },
                    )
                }
            },
        )
        when {
            state.isLoadingConfig && config == null -> LoadingScrim(text = "Loading dashboard…")
            config == null -> ErrorScrim(text = state.configError ?: "Couldn't load dashboard.")
            view == null -> ErrorScrim(text = "View '$viewPath' not in dashboard.")
            renderedCards.isEmpty() -> ViewEmpty(
                editMode = state.editMode,
                onAddCard = { showPicker.value = true },
            )
            state.editMode -> EditModeBody(
                cards = renderedWithFlags,
                onReorder = { from, to -> vm.reorderCard(from, to) },
                onEdit = { idx -> editingIndex.value = idx },
                onDelete = { idx -> vm.deleteCard(originalIndexFor(view.cards, viewOverride, idx)) },
                onAddCard = { showPicker.value = true },
            )
            else -> ViewModeBody(
                cards = renderedCards,
                stateMap = entities ?: emptyMap(),
                onAction = { action ->
                    scope.launch {
                        dispatchLovelaceAction(
                            action = action,
                            fallbackEntityId = (action as? LovelaceAction.CallService)?.entityId,
                            haRepository = haRepository,
                            onNavigate = { /* navigation is dashboard-internal only today */ },
                            onOpenUrl = { /* swallow; URL launch surfaces wired later */ },
                            onMoreInfo = { /* TODO: hook into card-stack drill-in */ },
                        )
                    }
                },
            )
        }
    }

    if (showPicker.value) {
        CardPickerSheet(
            onDismiss = { showPicker.value = false },
            onPick = { template ->
                showPicker.value = false
                vm.appendCard(template)
            },
        )
    }

    val editingIdx = editingIndex.value
    if (editingIdx != null && view != null) {
        val originalIdx = originalIndexFor(view.cards, viewOverride, editingIdx)
        val current = renderedCards.getOrNull(editingIdx)?.raw ?: kotlinx.serialization.json.JsonObject(emptyMap())
        CardEditSheet(
            initial = current,
            onDismiss = { editingIndex.value = null },
            onSave = { next ->
                editingIndex.value = null
                vm.replaceCard(originalIndex = originalIdx, rawJson = next)
            },
        )
    }
}

@Composable
private fun TopChip(text: String, accent: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeRound)
            .background(R1.SurfaceMuted)
            .border(1.dp, accent.copy(alpha = 0.6f), R1.ShapeRound)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = R1.labelMicro, color = accent)
    }
}

@Composable
private fun ViewModeBody(
    cards: List<LovelaceCard>,
    stateMap: Map<com.github.itskenny0.r1ha.core.ha.EntityId, com.github.itskenny0.r1ha.core.ha.EntityState>,
    onAction: (LovelaceAction) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        cards.forEach { card ->
            LovelaceCardRenderer(card = card, stateMap = stateMap, onAction = onAction)
        }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun EditModeBody(
    cards: List<com.github.itskenny0.r1ha.core.lovelace.RenderedCard>,
    onReorder: (Int, Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDelete: (Int) -> Unit,
    onAddCard: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        DragReorderColumn(
            items = cards,
            keyOf = { it.card.raw.hashCode() to it.hashCode() },
            onReorder = onReorder,
            modifier = Modifier.fillMaxSize(),
        ) { item, handle, isDragging ->
            val idx = cards.indexOf(item)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                EditCardWrapper(
                    rendered = item,
                    isDragging = isDragging,
                    dragHandle = handle,
                    onEdit = { onEdit(idx) },
                    onDelete = { onDelete(idx) },
                )
            }
        }
        // Floating add-card chip in the lower-right corner.
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 22.dp, bottom = 28.dp),
        ) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeRound)
                    .background(R1.AccentWarm)
                    .r1Pressable(onClick = onAddCard)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            ) {
                Text(text = "+ ADD CARD", style = R1.labelMicro, color = R1.Bg)
            }
        }
    }
}

@Composable
private fun EditCardWrapper(
    rendered: com.github.itskenny0.r1ha.core.lovelace.RenderedCard,
    isDragging: Boolean,
    dragHandle: Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val accent = if (rendered.isOverridden) R1.AccentWarm else R1.InkSoft
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .border(2.dp, if (isDragging) R1.AccentWarm else accent.copy(alpha = 0.5f), R1.ShapeM)
            .background(R1.Bg)
            .padding(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().then(dragHandle)) {
            Text(
                text = rendered.card.type.uppercase(),
                style = R1.labelMicro,
                color = if (rendered.isOverridden) R1.AccentWarm else R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(text = "≡ DRAG", style = R1.labelMicro, color = R1.InkMuted)
        }
        Spacer(Modifier.height(6.dp))
        LovelaceCardRenderer(
            card = rendered.card,
            stateMap = emptyMap(),
            onAction = { /* inert in edit mode */ },
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = onEdit)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("EDIT JSON", style = R1.labelMicro, color = R1.AccentCool) }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = onDelete)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) { Text("DELETE", style = R1.labelMicro, color = R1.StatusRed) }
        }
    }
}

@Composable
private fun ViewEmpty(editMode: Boolean, onAddCard: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Empty view", style = R1.screenTitle, color = R1.Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (editMode) "Tap ADD CARD below to insert one." else "This view has no cards. Toggle EDIT to add one.",
                style = R1.body,
                color = R1.InkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            if (editMode) {
                Spacer(Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeRound)
                        .background(R1.AccentWarm)
                        .r1Pressable(onClick = onAddCard)
                        .padding(horizontal = 18.dp, vertical = 12.dp),
                ) {
                    Text(text = "+ ADD CARD", style = R1.labelMicro, color = R1.Bg)
                }
            }
        }
    }
}

@Composable
private fun LoadingScrim(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = R1.AccentWarm, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(10.dp))
            Text(text, style = R1.body, color = R1.InkSoft)
        }
    }
}

@Composable
private fun ErrorScrim(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Couldn't load", style = R1.screenTitle, color = R1.StatusAmber)
            Spacer(Modifier.height(8.dp))
            Text(text, style = R1.body, color = R1.InkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

/**
 * Resolve a rendered-list index back to the original HA-config card
 * index, so the override op (Replace / Delete) targets the right slot.
 * Counts deletes (which shift original indices upward in the rendered
 * list) and ignores reorders / appends (the latter live in negative
 * sentinel slots and the editor regenerates the op blob for them).
 */
private fun originalIndexFor(
    originalCards: List<LovelaceCard>,
    overrideBlob: ViewOverride,
    renderedIndex: Int,
): Int {
    val deletedIndices = overrideBlob.operations
        .filterIsInstance<com.github.itskenny0.r1ha.core.lovelace.OverrideOp.Delete>()
        .map { it.index }
        .toSet()
    val surviving = originalCards.indices.filter { it !in deletedIndices }
    return surviving.getOrNull(renderedIndex) ?: renderedIndex
}
