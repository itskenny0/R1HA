package com.github.itskenny0.r1ha.feature.favoritespicker

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import androidx.compose.ui.semantics.contentDescription
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
                    ui.loading -> CenteredLoading()
                    ui.error != null -> ErrorState(message = ui.error ?: "Error")
                    ui.rows.isEmpty() -> FilteredEmptyState(filter = ui.filter, query = ui.query)
                    else -> ChannelList(
                        rows = ui.rows,
                        listState = listState,
                        isReorderable = ui.filter == PickerFilter.FAVS,
                        onToggle = { vm.toggle(it) },
                        onMoveUp = { vm.moveUp(it) },
                        onMoveDown = { vm.moveDown(it) },
                        onReorderTo = { entityId, idx -> vm.moveTo(entityId, idx) },
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
                val currentName = ui.rows.first { it.state.id.value == editingId }.displayName
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
    }
    }
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

@Composable
private fun FilteredEmptyState(filter: PickerFilter, query: String) {
    // Four flavours of "nothing here": active search returned no hits, no entities at
    // all, filter pruned them all, or the favourites-only view with no favourites set
    // yet. Each gets a short hint that points the user at the next step.
    val (heading, body) = when {
        query.isNotBlank() -> "NO MATCHES FOR \"${query.uppercase()}\"" to
            "Try a different word. Search looks at both the entity name and the\nentity_id (e.g. \"sensor.\")."
        filter == PickerFilter.ALL -> "NO CONTROLLABLE ENTITIES" to
            "Home Assistant didn't return anything we know how to drive. No lights,\nswitches, scenes, or sensors."
        filter == PickerFilter.FAVS -> "NO FAVOURITES YET" to
            "Pick a chip above to start browsing, then tap an entity to favourite it."
        else -> "NONE IN THIS FILTER" to "Tap ALL above to see every entity."
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(heading, style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(8.dp))
        Text(body, style = R1.body, color = R1.InkMuted)
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
private fun CenteredLoading() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.height(16.dp))
            Text("FETCHING ENTITIES…", style = R1.sectionHeader, color = R1.InkMuted)
        }
    }
}

@Composable
private fun ErrorState(message: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 22.dp),
        horizontalAlignment = Alignment.Start,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("ERROR", style = R1.labelMicro, color = R1.StatusRed)
        Spacer(Modifier.height(8.dp))
        Text(message, style = R1.body, color = R1.Ink)
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Open Settings → Sign out & reconnect to recover.",
            style = R1.body,
            color = R1.InkMuted,
        )
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
    onReorderTo: (entityId: String, toIndex: Int) -> Unit,
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
            onReorder = { from, to ->
                val entityId = rows.getOrNull(from)?.state?.id?.value ?: return@DragReorderColumn
                onReorderTo(entityId, to)
            },
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
                    style = R1.bodyEmph,
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
                style = R1.numeralS,
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
    // Update entities live on the dedicated Updates screen; same niche
    // manual-favorites path applies.
    Domain.UPDATE -> R1.AccentCool
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
    Domain.UPDATE -> "UPDATE"
}
