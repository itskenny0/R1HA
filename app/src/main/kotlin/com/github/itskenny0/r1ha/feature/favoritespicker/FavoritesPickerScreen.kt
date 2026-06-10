package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.Domain
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonList
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics

@Composable
fun FavoritesPickerScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    // Diagnostic: a black-screen-on-favorites bug has been reported. Log on first
    // composition so future occurrences leave a footprint in the crash bundle —
    // if the log is present the picker DID compose (suspect a stuck loading or a
    // child composable crashing later); if it's absent the navigation never
    // mounted the screen and the issue is upstream in NavController.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.github.itskenny0.r1ha.core.util.R1Log.i(
            "FavoritesPicker.compose",
            "entering composition",
        )
    }
    val vm: FavoritesPickerViewModel = viewModel(
        factory = FavoritesPickerViewModel.factory(repo = haRepository, settings = settings)
    )
    val ui by vm.ui.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    // Long-press preview state — local to the screen because it isn't business logic; the
    // VM doesn't care which entity is being previewed.
    val previewing = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.EntityState?>(null)
    }
    // Page (tab-group) editor dialog state — local UI, not business logic. Null = closed.
    val pageDialog = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<PageDialog?>(null)
    }

    // Settings flow lifted so the entity-override map can be supplied to LocalEntityOverrides.
    val appSettingsForOverrides by settings.settings.collectAsStateWithLifecycle(initialValue = com.github.itskenny0.r1ha.core.prefs.AppSettings())
    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalHaRepository provides haRepository,
        com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides provides appSettingsForOverrides.entityOverrides,
        com.github.itskenny0.r1ha.core.theme.LocalThemeAccentOverride provides appSettingsForOverrides.themeAccentArgb
            ?.let { androidx.compose.ui.graphics.Color(it) },
    ) {
    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding(),
        ) {
            R1TopBar(title = "FAVOURITES", onBack = onBack)
            AdaptiveContent(modifier = Modifier.weight(1f)) {

            // Search + filter chips — both pinned above the list so the user can refine
            // results from any scroll position. Hidden during initial load; no point
            // showing them before we know what's available.
            if (!ui.loading && ui.error == null) {
                // Tab-group strip — the picker edits ONE page's favourites at a time;
                // this shows which page that is and lets the user switch, add, rename, or
                // delete a page without backing out to the card stack.
                if (ui.pages.isNotEmpty()) {
                    PageBar(
                        pages = ui.pages,
                        activePageId = ui.activePageId,
                        onSelect = { vm.selectPage(it) },
                        onAdd = { pageDialog.value = PageDialog.Create },
                        onRename = { id, name -> pageDialog.value = PageDialog.Rename(id, name) },
                        onDelete = { id, name -> pageDialog.value = PageDialog.Delete(id, name) },
                        canDelete = ui.pages.size > 1,
                    )
                }
                SearchBar(
                    query = ui.query,
                    onQueryChange = { vm.setQuery(it) },
                )
                FilterChipRow(
                    selected = ui.filter,
                    counts = ui.countsByFilter,
                    onSelect = { vm.setFilter(it) },
                )
                // Per-tab sort cycle. Hidden on the FAVS tab (where sort is
                // locked to user-set order). Tapping rotates A→Z → BY AREA →
                // BY KIND → back to A→Z within the active tab; the choice
                // sticks while the picker is open so back-and-forward chip
                // switches feel sticky.
                if (ui.filter != PickerFilter.FAVS) {
                    val activeSort = ui.sortPerFilter[ui.filter]
                        ?: FavoritesPickerViewModel.SortOrder.ALPHA
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "SORT",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(
                                    onClick = { vm.cycleSortOrder() },
                                    contentDescription = "Cycle sort order",
                                )
                                .padding(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = activeSort.label,
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                            )
                        }
                        // Bulk add. Favouriting fifteen lights one checkbox at a
                        // time is the picker's biggest friction point; when the
                        // visible set is scoped (a search or a domain chip, never
                        // the unfiltered ALL view) offer one tap that favourites
                        // everything still unselected. Gate logic is the pure
                        // [shouldOfferBulkAdd] so the "could this accidentally
                        // dump 300 entities into the deck?" cases stay tested.
                        val addable = ui.rows.count { !it.isFavorite }
                        if (shouldOfferBulkAdd(ui.filter, ui.query, addable)) {
                            Spacer(Modifier.weight(1f))
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable(
                                        onClick = { vm.addAllShown() },
                                        contentDescription = "Add all $addable shown entities to this tab",
                                    )
                                    .padding(horizontal = 10.dp, vertical = 4.dp),
                            ) {
                                Text(
                                    text = "ADD ALL · $addable",
                                    style = R1.labelMicro,
                                    color = R1.AccentWarm,
                                )
                            }
                        }
                    }
                }
            }

            // Pull-to-refresh wrap so the user can re-fetch HA's entity list
            // without backing out. Material3 PullToRefreshBox handles the
            // gesture + indicator; we expose ui.loading as the 'refreshing'
            // state so the spinner stays visible while the VM is doing its
            // thing. Refresh fires through vm.refresh() which is idempotent
            // and de-bounced inside the VM, so an over-enthusiastic user
            // can't spam-fetch.
            androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    // First load: skeleton rows shaped like the list that will
                    // replace them (sprint-standard, replaces the abstract
                    // centred spinner). Placeholder rows are decorative; the
                    // polite live region announces the loading state once.
                    ui.loading && ui.rows.isEmpty() -> Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                                contentDescription = "Loading entities"
                            },
                    ) {
                        SkeletonList()
                    }
                    // Failed first load: canonical error state with a RETRY
                    // chip. The previous bespoke version had no retry and the
                    // non-scrollable error Box can't host pull-to-refresh, so
                    // it stranded the user (the old hint even sent them to
                    // sign out, a much bigger hammer than a refetch).
                    ui.error != null -> R1ErrorState(
                        title = "COULDN'T LOAD ENTITIES",
                        message = ui.error,
                        onRetry = { vm.refresh() },
                    )
                    ui.rows.isEmpty() -> FilteredEmptyState(
                        filter = ui.filter,
                        query = ui.query,
                        onClearSearch = { vm.setQuery("") },
                        onShowAll = { vm.setFilter(PickerFilter.ALL) },
                    )
                    else -> ChannelList(
                        rows = ui.rows,
                        listState = listState,
                        // Drag-reorder is index-based and only valid when the visible row
                        // index equals the favourite's position in the persisted list:
                        // true on the FAVS tab with no active search. A query filters the
                        // FAVS list, so a row's index no longer maps to its stored
                        // position; disable the drag there (arrows still nudge by id) so a
                        // drag can't reorder the wrong entities.
                        isReorderable = ui.filter == PickerFilter.FAVS && ui.query.isBlank(),
                        onToggle = { vm.toggle(it) },
                        onMoveUp = { vm.moveUp(it) },
                        onMoveDown = { vm.moveDown(it) },
                        onReorderTo = { from, to -> vm.reorderFavorite(from, to) },
                        onEdit = { vm.startEditing(it) },
                        onPreview = { previewing.value = it },
                    )
                }
            }
            } // AdaptiveContent
        }

        // ── Customize dialog ────────────────────────────────────────────────────────
        val editingId = ui.editingEntityId
        if (editingId != null) {
            val entity = ui.rows.firstOrNull { it.state.id.value == editingId }?.state
            if (entity != null) {
                // Seed the name field from the RAW name override (empty when none),
                // not the resolved display name. Pre-filling with the friendly_name
                // would make an untouched SAVE persist a redundant override equal to
                // HA's friendly_name; an empty field instead shows friendly_name as a
                // placeholder, and SAVE-without-edit leaves no override behind. Matches
                // the dialog's documented "Clear to revert to HA's friendly_name."
                val currentName = appSettingsForOverrides.nameOverrides[editingId].orEmpty()
                val currentOverride = appSettingsForOverrides.entityOverrides[editingId]
                    ?: com.github.itskenny0.r1ha.core.prefs.EntityOverride.NONE
                RenameDialog(
                    entity = entity,
                    initialName = currentName,
                    initialOverride = currentOverride,
                    onSave = { newName, newOverride ->
                        vm.saveCustomize(editingId, newName, newOverride)
                    },
                    onCancel = { vm.cancelEditing() },
                )
            }
        }

        // ── Hold-to-preview overlay ──────────────────────────────────────────────────
        val previewState = previewing.value
        if (previewState != null) {
            PreviewOverlay(
                entity = previewState,
                onDismiss = { previewing.value = null },
            )
        }

        // ── Page (tab-group) create / rename / delete dialogs ─────────────────────────
        when (val dlg = pageDialog.value) {
            is PageDialog.Create -> PageNameDialog(
                title = "NEW TAB",
                initial = "",
                confirmLabel = "CREATE",
                onConfirm = { name ->
                    vm.addPage(name)
                    pageDialog.value = null
                },
                onCancel = { pageDialog.value = null },
            )
            is PageDialog.Rename -> PageNameDialog(
                title = "RENAME TAB",
                initial = dlg.name,
                confirmLabel = "SAVE",
                onConfirm = { name ->
                    vm.renamePage(dlg.id, name)
                    pageDialog.value = null
                },
                onCancel = { pageDialog.value = null },
            )
            is PageDialog.Delete -> PageDeleteDialog(
                name = dlg.name,
                onConfirm = {
                    vm.deletePage(dlg.id)
                    pageDialog.value = null
                },
                onCancel = { pageDialog.value = null },
            )
            null -> Unit
        }
    }
    }
}

/** Which page-editor dialog is open. Local screen state, not business logic. */
private sealed interface PageDialog {
    data object Create : PageDialog
    data class Rename(val id: String, val name: String) : PageDialog
    data class Delete(val id: String, val name: String) : PageDialog
}

/**
 * Horizontal-scroll row of filter chips. Each chip shows the filter label + a tiny count
 * suffix (e.g. "LIGHTS · 7") so the user can see at a glance which filters have entries.
 * Selected chip is filled with the accent colour; unselected chips are hairline-bordered.
 */
@Composable
private fun FilterChipRow(
    selected: PickerFilter,
    counts: Map<PickerFilter, Int>,
    onSelect: (PickerFilter) -> Unit,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PickerFilter.entries.forEach { filter ->
            val count = counts[filter] ?: 0
            // Hide chips with zero matches (except ALL/FAVS which always show) — keeps
            // the row tight on installs with only a handful of domains.
            if (count == 0 && filter != PickerFilter.ALL && filter != PickerFilter.FAVS) {
                return@forEach
            }
            val isSelected = filter == selected
            Box(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .clip(R1.ShapeS)
                    .background(if (isSelected) R1.AccentWarm else R1.Bg)
                    .then(
                        if (isSelected) Modifier
                        else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                    )
                    .r1Pressable(onClick = { onSelect(filter) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "${filter.label} · $count",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
        }
    }
}

/**
 * Tab-group strip. Horizontal-scroll row of page chips: the active page is filled
 * with the accent colour, the rest are hairline-bordered. Tapping a non-active chip
 * switches the page being edited; tapping the active chip opens its rename dialog (a
 * pencil glyph hints at this), and a small delete control sits on the active chip when
 * more than one page exists. A trailing "+" chip creates a new tab. Mirrors the card
 * stack's page-tab vocabulary so the two surfaces read as the same tab model.
 */
@Composable
private fun PageBar(
    pages: List<com.github.itskenny0.r1ha.core.prefs.FavoritePage>,
    activePageId: String,
    onSelect: (String) -> Unit,
    onAdd: () -> Unit,
    onRename: (id: String, name: String) -> Unit,
    onDelete: (id: String, name: String) -> Unit,
    canDelete: Boolean,
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "TAB",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        pages.forEach { page ->
            val isActive = page.id == activePageId
            val label = page.icon?.let { "$it ${page.name}" } ?: page.name
            Row(
                modifier = Modifier
                    .padding(end = 6.dp)
                    .heightIn(min = 36.dp)
                    .clip(R1.ShapeS)
                    .background(if (isActive) R1.AccentWarm else R1.Bg)
                    .then(
                        if (isActive) Modifier
                        else Modifier.border(1.dp, R1.Hairline, R1.ShapeS),
                    )
                    // Tap an inactive chip to switch; tap the active chip to rename it.
                    .r1Pressable(
                        onClick = {
                            if (isActive) onRename(page.id, page.name) else onSelect(page.id)
                        },
                        contentDescription = if (isActive) {
                            "Active tab ${page.name}, tap to rename"
                        } else {
                            "Switch to tab ${page.name}"
                        },
                    )
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label.ifBlank { "UNNAMED" },
                    style = R1.labelMicro,
                    color = if (isActive) R1.Bg else R1.InkSoft,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                if (isActive) {
                    Spacer(Modifier.width(6.dp))
                    com.github.itskenny0.r1ha.ui.components.EditGlyph(size = 11.dp, tint = R1.Bg)
                    // Delete is only offered while more than one page survives — the
                    // store refuses to delete the last page, and we shouldn't show an
                    // affordance that silently no-ops.
                    if (canDelete) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .r1Pressable(
                                    onClick = { onDelete(page.id, page.name) },
                                    hapticOnClick = false,
                                    contentDescription = "Delete tab ${page.name}",
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(text = "✕", style = R1.labelMicro, color = R1.Bg)
                        }
                    }
                }
            }
        }
        // Trailing add chip.
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .clip(R1.ShapeS)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = onAdd, contentDescription = "Add a new tab")
                .padding(horizontal = 12.dp, vertical = 6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+ NEW", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}

/**
 * Single-field name dialog used for both create and rename of a tab group. Auto-focuses
 * the field on open; CREATE / SAVE is disabled while the field is blank so an empty tab
 * name can't be committed (the VM also guards, but disabling the button is the clearer
 * affordance). Back / tap-outside cancels.
 */
@Composable
private fun PageNameDialog(
    title: String,
    initial: String,
    confirmLabel: String,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    var name by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(initial)
    }
    val focusRequester = androidx.compose.runtime.remember {
        androidx.compose.ui.focus.FocusRequester()
    }
    androidx.compose.runtime.LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onCancel, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                // Absorb taps so a tap inside the panel doesn't dismiss the dialog.
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(text = title, style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(R1.space.m))
            com.github.itskenny0.r1ha.ui.components.R1TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "Tab name",
                monospace = false,
                focusRequester = focusRequester,
            )
            Spacer(Modifier.height(R1.space.l))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                com.github.itskenny0.r1ha.ui.components.R1Button(
                    text = "CANCEL",
                    onClick = onCancel,
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.width(R1.space.s))
                com.github.itskenny0.r1ha.ui.components.R1Button(
                    text = confirmLabel,
                    onClick = { onConfirm(name) },
                    enabled = name.isNotBlank(),
                )
            }
        }
    }
}

/** Destructive-confirm dialog for deleting a tab group. Spells out that the page's
 *  favourites go with it so the user can't nuke a populated tab by reflex. */
@Composable
private fun PageDeleteDialog(
    name: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    BackHandler(onBack = onCancel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onClick = onCancel, hapticOnClick = false)
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .padding(horizontal = R1.space.l)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(16.dp),
        ) {
            Text(text = "DELETE TAB", style = R1.sectionHeader, color = R1.StatusRed)
            Spacer(Modifier.height(R1.space.s))
            Text(
                text = "Remove \"${name.ifBlank { "UNNAMED" }}\" and its favourites from the deck? Entities themselves stay in Home Assistant.",
                style = R1.body,
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(R1.space.l))
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                com.github.itskenny0.r1ha.ui.components.R1Button(
                    text = "CANCEL",
                    onClick = onCancel,
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
                Spacer(Modifier.width(R1.space.s))
                com.github.itskenny0.r1ha.ui.components.R1Button(
                    text = "DELETE",
                    onClick = onConfirm,
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    accent = R1.StatusRed,
                )
            }
        }
    }
}

@Composable
private fun FilteredEmptyState(
    filter: PickerFilter,
    query: String,
    onClearSearch: () -> Unit,
    onShowAll: () -> Unit,
) {
    // Four flavours of "nothing here": active search returned no hits, no entities at
    // all, filter pruned them all, or the favourites-only view with no favourites set
    // yet. Each routes through the canonical R1EmptyState and, where one exists,
    // carries the action that un-empties the view (clear the search, jump to ALL) so
    // recovery is one tap instead of re-deriving the chip/field state by hand.
    when {
        query.isNotBlank() -> R1EmptyState(
            title = "NO MATCHES FOR \"${query.uppercase()}\"",
            body = "Try a different word. Search looks at both the entity name " +
                "and the entity_id (e.g. \"sensor.\").",
            actionText = "CLEAR SEARCH",
            onAction = onClearSearch,
        )
        filter == PickerFilter.ALL -> R1EmptyState(
            title = "NO CONTROLLABLE ENTITIES",
            body = "Home Assistant didn't return anything we know how to drive. " +
                "No lights, switches, scenes, or sensors.",
        )
        filter == PickerFilter.FAVS -> R1EmptyState(
            title = "NO FAVOURITES YET",
            body = "Browse the chips above, then tap an entity to favourite it.",
            actionText = "BROWSE ALL",
            onAction = onShowAll,
        )
        else -> R1EmptyState(
            title = "NONE IN THIS FILTER",
            body = "Nothing matches this chip on your server.",
            actionText = "SHOW ALL",
            onAction = onShowAll,
        )
    }
}

/**
 * Free-text search above the filter chips. Tiny R1TextField with a magnifier-glyph
 * prefix and a clear-X suffix when there's text. Filters by friendly_name + entity_id —
 * see [FavoritesPickerViewModel.buildRows].
 */
@Composable
private fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Small "Q" prefix label — the canvas magnifier glyph would be lovely but adds
        // a Canvas to every recomposition; a single character "⌕" or "Q" is cheaper and
        // reads as "this is a search field" especially next to the placeholder copy.
        Text(
            text = "FIND",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(end = 8.dp),
        )
        Box(modifier = Modifier.weight(1f)) {
            com.github.itskenny0.r1ha.ui.components.R1TextField(
                value = query,
                onValueChange = onQueryChange,
                placeholder = "kitchen, .door, scene, ...",
                monospace = false,
            )
        }
        // Clear-X appears only when there's something to clear. Smaller-than-pencil so
        // it doesn't visually fight the field for attention.
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .r1Pressable({ onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.numeralM, color = R1.InkSoft)
            }
        }
    }
}

/**
 * Long-press preview — pops a centred mini-card of the entity the user is holding,
 * dismisses on any tap or back-press. Uses the same [com.github.itskenny0.r1ha.ui.components.EntityCard]
 * the main stack uses, scaled to fit the overlay; that way the preview is pixel-faithful
 * to what the user will actually see after they favourite the entity.
 */
@Composable
private fun PreviewOverlay(
    entity: com.github.itskenny0.r1ha.core.ha.EntityState,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.92f))
            .r1Pressable(onDismiss, hapticOnClick = false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Header — tells the user this is a preview, not the live card.
            Text("PREVIEW · HOLD", style = R1.labelMicro, color = R1.AccentWarm)
            Spacer(Modifier.height(6.dp))
            // The card itself — same EntityCard the live stack uses, framed in a hairline
            // border so it reads as a "card surface" lifted off the overlay. The whole
            // box is given a fixed height that matches the card-stack proportions so it
            // doesn't visually morph between preview and live.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
                    .clip(R1.ShapeM)
                    .border(1.dp, R1.Hairline, R1.ShapeM),
            ) {
                com.github.itskenny0.r1ha.ui.components.EntityCard(
                    state = entity,
                    onTapToggle = { /* preview is non-interactive */ },
                    tapToToggleEnabled = false,
                    modifier = Modifier.fillMaxSize(),
                )
            }
            Spacer(Modifier.height(6.dp))
            Text("Tap anywhere to dismiss", style = R1.body, color = R1.InkMuted)
        }
    }
}

@Composable
private fun ChannelList(
    rows: List<FavoritesPickerViewModel.Row>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    /** True when the active filter is FAVS — turns on long-press-drag reordering of
     *  rows. The whole-list drag treatment doesn't make sense on category filters
     *  where most rows aren't favourites and there's no order to preserve. */
    isReorderable: Boolean,
    onToggle: (String) -> Unit,
    onMoveUp: (String) -> Unit,
    onMoveDown: (String) -> Unit,
    onReorderTo: (fromIndex: Int, toIndex: Int) -> Unit,
    onEdit: (String) -> Unit,
    onPreview: (com.github.itskenny0.r1ha.core.ha.EntityState) -> Unit,
) {
    // favCount used to drive move-arrow enable logic. Pre-computed once per list rather
    // than once per row composition.
    val favCount = rows.count { it.isFavorite }
    if (isReorderable) {
        // FAVS view — long-press a row to grab, drag to reorder, release to drop.
        // [DragReorderColumn] manages the LazyColumn internally and emits absolute
        // (from, to) indices on each swap. We map the swap back to the underlying
        // entityId so the VM persists into the favourites list.
        com.github.itskenny0.r1ha.ui.components.DragReorderColumn(
            items = rows,
            keyOf = { it.state.id.value },
            // Index-based: pass the swap straight through to the VM, which reorders the
            // favourites list by index. The previous code re-resolved an entity_id from
            // `rows` per swap, but `rows` is async-stale across the multiple swaps a fast
            // drag fires in one frame, so the wrong entity moved. On the FAVS tab the row
            // index equals the favourite's position (rows are sorted by orderIndex), so
            // (from, to) map directly onto the persisted list.
            onReorder = { from, to -> onReorderTo(from, to) },
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 4.dp),
        ) { row, dragHandle, isDragging ->
            ChannelRow(
                row = row,
                favCount = favCount,
                onToggle = { onToggle(row.state.id.value) },
                onMoveUp = { onMoveUp(row.state.id.value) },
                onMoveDown = { onMoveDown(row.state.id.value) },
                onEdit = { onEdit(row.state.id.value) },
                onLongPress = { onPreview(row.state) },
                modifier = dragHandle,
                isDragging = isDragging,
            )
        }
    } else {
        // On roomy tiers the picker is a long flat entity list, so flow it into the
        // tier's grid-column count (2/3/4/5) to use the extra width instead of one
        // tall single column. Mini / compact (no width cap) stay a single column and
        // keep the wheel-scroll-bound LazyColumn so the hardware wheel still drives
        // the list exactly as before.
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        val gridColumns = if (dimens.capsContentWidth) dimens.gridColumns else 1
        if (gridColumns > 1) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(gridColumns),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
            ) {
                gridItems(
                    items = rows,
                    key = { it.state.id.value },
                    contentType = { if (it.isFavorite) "fav" else "non-fav" },
                ) { row ->
                    ChannelRow(
                        row = row,
                        favCount = favCount,
                        onToggle = { onToggle(row.state.id.value) },
                        onMoveUp = { onMoveUp(row.state.id.value) },
                        onMoveDown = { onMoveDown(row.state.id.value) },
                        onEdit = { onEdit(row.state.id.value) },
                        onLongPress = { onPreview(row.state) },
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 4.dp),
                // contentType lets Compose recycle row composables across items rather than
                // throwing away the layout tree for every scroll step. Two contentTypes: one
                // for favourite rows (have move-arrows) and one for non-favourites. Without
                // this hint, every row re-composes from scratch on swap.
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 48.dp),
            ) {
                items(
                    items = rows,
                    key = { it.state.id.value },
                    contentType = { if (it.isFavorite) "fav" else "non-fav" },
                ) { row ->
                    ChannelRow(
                        row = row,
                        favCount = favCount,
                        onToggle = { onToggle(row.state.id.value) },
                        onMoveUp = { onMoveUp(row.state.id.value) },
                        onMoveDown = { onMoveDown(row.state.id.value) },
                        onEdit = { onEdit(row.state.id.value) },
                        onLongPress = { onPreview(row.state) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ChannelRow(
    row: FavoritesPickerViewModel.Row,
    favCount: Int,
    onToggle: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onEdit: () -> Unit,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
    /** True when this row is currently being long-press-dragged in the reorderable
     *  FAVS view. Renders with a soft accent fill so the user can see which row is
     *  in flight, distinct from the row's normal favourite-vs-non-favourite styling. */
    isDragging: Boolean = false,
) {
    val domain = row.state.id.domain
    val domainAccent = domainAccentFor(domain)
    val domainCode = domainLabel(domain)
    // In the FAVS reorderable view the [DragReorderColumn] owns the long-press
    // gesture (promoting to a drag); we keep tap-to-toggle but skip our own
    // long-press detector so the two gesture pipelines don't fight over which one
    // wins. The drag-handle modifier is composed in via [modifier].
    val gestureModifier = if (isDragging) {
        // Dragging — no tap fires until release, the drag-column owns this row.
        Modifier
    } else {
        Modifier.r1RowPressable(onTap = onToggle, onLongPress = onLongPress)
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .background(if (isDragging) R1.AccentWarm.copy(alpha = 0.18f) else androidx.compose.ui.graphics.Color.Transparent)
            .then(gestureModifier)
            .padding(horizontal = 22.dp, vertical = 12.dp),
    ) {
        // ── Left: domain block (coloured tab) + identity ────────────────────────────
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(32.dp)
                .background(if (row.isFavorite) domainAccent else R1.Hairline),
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(domainCode, style = R1.labelMicro, color = domainAccent)
                // Tag the row so the user knows what kind of control they'll get when they
                // favourite this entity. ACTION for fire-and-forget (scenes/scripts/buttons),
                // SENSOR for read-only sensors, ON/OFF for on-off-only switches, and silent
                // for scalar entities (the percent control is implicit from the domain).
                val tag = when {
                    row.state.id.domain.isAction -> "TRIGGER"
                    row.state.id.domain == Domain.SENSOR -> "READING"
                    !row.state.supportsScalar -> "ON/OFF"
                    else -> null
                }
                if (tag != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(text = "· $tag", style = R1.labelMicro, color = R1.InkMuted)
                }
                if (row.isFavorite && row.orderIndex != null) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "·",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "POS ${row.orderIndex + 1}",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
            // Friendly name — up to 2 lines so similarly-named entities
            // ("Office lamp 1" vs "Office lamp 2") are distinguishable without truncating
            // the suffix. The pencil edit button sits inline on the right of the name row
            // so the rename affordance is close to the thing it acts on.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.displayName,
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.bodyEmph),
                    color = R1.Ink,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .size(24.dp)
                        .r1Pressable(onEdit, hapticOnClick = false),
                    contentAlignment = Alignment.Center,
                ) {
                    com.github.itskenny0.r1ha.ui.components.EditGlyph(
                        size = 12.dp,
                        tint = R1.InkMuted,
                    )
                }
            }
            Text(
                text = row.state.id.value,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.numeralS),
                color = R1.InkMuted,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }

        // ── Right: move arrows (only for favourites) + selection state ──────────────
        // Plain if/else instead of AnimatedVisibility — the fade-in/out costs a measure
        // pass per row per state change, and with 50+ entities scrolling that snowballs.
        // The arrows appearing/disappearing on favourite toggle is fine without animation;
        // the SelectBox itself is the focal point of the state change anyway.
        if (row.isFavorite) {
            val canMoveUp = row.orderIndex != null && row.orderIndex > 0
            val canMoveDown = row.orderIndex != null && row.orderIndex < favCount - 1
            MoveChevron(
                onClick = onMoveUp,
                enabled = canMoveUp,
                direction = ChevronDirection.Up,
                description = "Move up",
            )
            MoveChevron(
                onClick = onMoveDown,
                enabled = canMoveDown,
                direction = ChevronDirection.Down,
                description = "Move down",
            )
        }
        Spacer(Modifier.width(10.dp))
        SelectBox(selected = row.isFavorite, onClick = onToggle, accent = domainAccent)
    }
}

@Composable
private fun MoveChevron(
    onClick: () -> Unit,
    enabled: Boolean,
    direction: ChevronDirection,
    description: String,
) {
    // 32dp tap target with the chevron centred. We attach the contentDescription to the
    // outer Box (Chevron itself is a Canvas with no built-in semantic role) so TalkBack
    // still reads "Move up" / "Move down" even though we dropped Material's IconButton.
    Box(
        modifier = Modifier
            .size(32.dp)
            .semantics { contentDescription = description }
            .then(if (enabled) Modifier.r1Pressable(onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Chevron(
            direction = direction,
            size = 14.dp,
            tint = if (enabled) R1.InkSoft else R1.Hairline,
        )
    }
}

/**
 * Bespoke selection box — much more clearly a "patch slot is selected" indicator than
 * Material 3's stock Checkbox. Empty hairline-bordered square when unselected, accent-filled
 * square with a tick when selected. Uses a proper [border] modifier (rather than the previous
 * two-tone background trick) so the unselected state reads as a crisp 1dp outline on the
 * R1's tiny display rather than a near-invisible darker square.
 */
@Composable
private fun SelectBox(selected: Boolean, onClick: () -> Unit, accent: Color) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(R1.ShapeS)
            .background(if (selected) accent else R1.Bg)
            .then(
                if (selected) Modifier
                else Modifier.border(1.dp, R1.InkMuted, R1.ShapeS),
            )
            .r1Pressable(onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text(text = "✓", style = R1.labelMicro, color = R1.Bg)
        }
    }
}

private fun domainAccentFor(domain: Domain): Color = when (domain) {
    Domain.LIGHT, Domain.SWITCH, Domain.INPUT_BOOLEAN, Domain.AUTOMATION,
    Domain.CLIMATE, Domain.WATER_HEATER, Domain.BUTTON, Domain.INPUT_BUTTON,
    Domain.NUMBER, Domain.INPUT_NUMBER -> R1.AccentWarm
    Domain.FAN, Domain.SCENE, Domain.VACUUM, Domain.LAWN_MOWER -> R1.AccentGreen
    Domain.COVER, Domain.LOCK -> R1.AccentNeutral
    Domain.MEDIA_PLAYER, Domain.HUMIDIFIER, Domain.SCRIPT, Domain.SENSOR,
    Domain.VALVE, Domain.SELECT, Domain.INPUT_SELECT -> R1.AccentCool
    Domain.BINARY_SENSOR -> R1.AccentNeutral
    // Helper-only domains — Helpers screen renders these; this picker
    // entry is only reached on the niche path of a user manually
    // adding their entity_id to favorites JSON. Neutral tint.
    Domain.COUNTER, Domain.TIMER,
    Domain.INPUT_TEXT, Domain.INPUT_DATETIME -> R1.AccentNeutral
    // New read-only domains: neutral accent.
    Domain.TEXT, Domain.DATE, Domain.DATETIME, Domain.TIME,
    Domain.IMAGE, Domain.EVENT -> R1.AccentNeutral
    // Siren: warm accent (high-attention safety device).
    Domain.SIREN -> R1.AccentWarm
    // Update entities live on the dedicated Updates screen; same niche
    // manual-favorites path applies.
    Domain.UPDATE -> R1.AccentCool
    // Remote/IR blasters — cool accent matches the cardstack's REMOTE colour.
    Domain.REMOTE -> R1.AccentCool
    // Alarm — warm matches the cardstack's high-attention treatment.
    Domain.ALARM_CONTROL_PANEL -> R1.AccentWarm
    // Person — green reads as "presence / who's home", matching the card-stack
    // person treatment. Weather — cool, consistent with the sensor-style read-only
    // info family. The picker only has the Domain (no live state), so these are the
    // static at-rest accents; the card stack tints person by actual home/away state.
    Domain.PERSON -> R1.AccentGreen
    Domain.WEATHER -> R1.AccentCool
    // Catch-all domains with no archetype: neutral. Only reachable via the niche
    // manual-favorites-JSON path; they have no first-class picker affordance.
    Domain.OTHER -> R1.AccentNeutral
}

private fun domainLabel(domain: Domain): String = when (domain) {
    Domain.LIGHT -> "LIGHT"
    Domain.FAN -> "FAN"
    Domain.COVER -> "COVER"
    Domain.MEDIA_PLAYER -> "MEDIA"
    Domain.SWITCH -> "SWITCH"
    Domain.INPUT_BOOLEAN -> "TOGGLE"
    Domain.AUTOMATION -> "AUTOMATION"
    Domain.LOCK -> "LOCK"
    Domain.HUMIDIFIER -> "HUMIDIFIER"
    Domain.CLIMATE -> "CLIMATE"
    Domain.SCENE -> "SCENE"
    Domain.SCRIPT -> "SCRIPT"
    Domain.BUTTON -> "BUTTON"
    Domain.INPUT_BUTTON -> "BUTTON"
    Domain.SENSOR -> "SENSOR"
    Domain.BINARY_SENSOR -> "DETECTOR"
    Domain.NUMBER -> "NUMBER"
    Domain.INPUT_NUMBER -> "NUMBER"
    Domain.VALVE -> "VALVE"
    Domain.VACUUM -> "VACUUM"
    Domain.LAWN_MOWER -> "MOWER"
    Domain.WATER_HEATER -> "HEATER"
    Domain.SELECT -> "SELECT"
    Domain.INPUT_SELECT -> "SELECT"
    Domain.COUNTER -> "COUNTER"
    Domain.TIMER -> "TIMER"
    Domain.INPUT_TEXT -> "TEXT"
    Domain.INPUT_DATETIME -> "DATETIME"
    Domain.TEXT -> "TEXT"
    Domain.DATE -> "DATE"
    Domain.DATETIME -> "DATETIME"
    Domain.TIME -> "TIME"
    Domain.SIREN -> "SIREN"
    Domain.IMAGE -> "IMAGE"
    Domain.EVENT -> "EVENT"
    Domain.UPDATE -> "UPDATE"
    Domain.REMOTE -> "REMOTE"
    Domain.ALARM_CONTROL_PANEL -> "ALARM"
    Domain.PERSON -> "PERSON"
    Domain.WEATHER -> "WEATHER"
    Domain.OTHER -> "OTHER"
}
