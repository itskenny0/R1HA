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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
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
import com.github.itskenny0.r1ha.feature.dashboards.cards.DashboardNameResolver
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
import com.github.itskenny0.r1ha.ui.components.LocalWindowTier
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
    /** HA server base URL (e.g. `http://homeassistant.local:8123`), sourced
     *  from settings the same way the cameras / history screens do. Provided
     *  to the card renderers via [LocalHaServerUrl] so picture / area / camera
     *  cards can resolve relative `entity_picture` / `image` paths and fetch
     *  them authenticated. Null when no server is configured; the image cards
     *  then fall back to their muted placeholder. */
    serverUrl: String? = null,
    /** Routes to the Lovelace WebView (Routes.LOVELACE). Used by the
     *  strategy-dashboard fallback when R1HA can't resolve the layout
     *  natively. No-op default keeps the screen renderable in isolation. */
    onOpenLovelace: () -> Unit = {},
    /** Navigate to another view in the same dashboard (a `navigate`
     *  tap_action's `navigation_path`). Fired by cards and badges. No-op
     *  default keeps the screen renderable in isolation. */
    onOpenView: (String) -> Unit = {},
    /** Open the more-info drill-in for an entity. Badge / card more-info taps
     *  route through here. When [settings] is provided and the effective
     *  per-entity `moreInfoEnabled` is true, the screen shows the ultra-detail
     *  [com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoSheet]; the caller's
     *  callback still fires so external navigation keeps working. */
    onMoreInfo: (String) -> Unit = {},
    /** Settings repository, used to resolve the effective per-entity
     *  ultra-detail `moreInfoEnabled` flag and to feed the more-info sheet.
     *  Null (the isolation-render default) suppresses the in-screen sheet and
     *  falls back to just [onMoreInfo]. */
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository? = null,
) {
    val vm: DashboardsViewModel = viewModel(
        factory = DashboardsViewModel.factory(haRepository, overrideStore),
    )
    val state by vm.state.collectAsState()
    val overrides by vm.overrides.collectAsState()
    val entities by vm.entities.collectAsState()
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
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

    // Live settings (global ultra-detail default + per-entity overrides) so a
    // more-info tap can decide whether the in-screen sheet is offered. Null
    // settings (isolation render) leaves appSettings null and the sheet stays
    // suppressed; the onMoreInfo callback still fires.
    val appSettings = settings?.settings?.collectAsState(initial = null)?.value
    // Pin-to-side-panel affordance state. The pinnable route is the concrete
    // dashboards-view route for the view the user is currently looking at; its
    // pinned state is read from the live nav-panel pin list. Both resolve only
    // when settings is provided (real app, not isolation render).
    val pinRoute = remember(dashboardUrlPath, viewPath) {
        com.github.itskenny0.r1ha.nav.Routes.dashboardsViewRoute(dashboardUrlPath, viewPath)
    }
    val isPinned = appSettings?.navPanel?.pinnedDashboards?.any { it.route == pinRoute } == true
    val pinScope = rememberCoroutineScope()
    // Entity whose ultra-detail sheet is currently open; null = none.
    var moreInfoEntityId by remember { mutableStateOf<String?>(null) }
    // Wrap the caller's more-info handler: always fire it (external nav still
    // works), and when settings resolve the effective moreInfoEnabled to true,
    // also open the ultra-detail sheet for that entity.
    val handleMoreInfo: (String) -> Unit = remember(appSettings, onMoreInfo, settings) {
        { entityId ->
            onMoreInfo(entityId)
            val s = appSettings
            if (s != null && settings != null) {
                val effective = s.entityOverrides[entityId]?.moreInfoEnabled
                    ?: s.ui.moreInfoEnabledDefault
                if (effective) moreInfoEntityId = entityId
            }
        }
    }

    // A dashboard whose layout is server-generated by an HA strategy returns
    // no concrete cards we can render; detect it (whole config, or just this
    // view) so we can show an "open in Lovelace" affordance instead of an
    // empty scroll. Only branch when there genuinely are no rendered cards.
    val isStrategy = renderedCards.isEmpty() &&
        ((config?.isStrategyGenerated == true) || (view?.isStrategyGenerated == true))

    // Registry fetch for name_type resolution (HA 2025.11). Fetched once per
    // screen entry, best-effort in parallel. Failures yield empty lists so the
    // resolver falls back to friendly_name; the first paint is not blocked because
    // nameResolver starts as EMPTY and is updated via a state variable once loaded.
    var nameResolver by remember { mutableStateOf(DashboardNameResolver.EMPTY) }
    LaunchedEffect(haRepository) {
        coroutineScope {
            val entityResult = async(Dispatchers.IO) {
                haRepository.listEntityRegistry().getOrDefault(emptyList())
            }
            val deviceResult = async(Dispatchers.IO) {
                haRepository.listDevices().getOrDefault(emptyList())
            }
            val areaResult = async(Dispatchers.IO) {
                haRepository.listAreas().getOrDefault(emptyList())
            }
            nameResolver = DashboardNameResolver.from(
                entityRegistry = entityResult.await(),
                devices = deviceResult.await(),
                areas = areaResult.await(),
            )
        }
    }

    androidx.compose.runtime.CompositionLocalProvider(
        com.github.itskenny0.r1ha.core.theme.LocalHaRepository provides haRepository,
        com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl provides serverUrl,
        com.github.itskenny0.r1ha.core.theme.LocalNameResolver provides nameResolver,
    ) {
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
                    // Pin / unpin THIS view to the side panel + phone drawer. Shown only
                    // when settings is wired (real app) and not while editing the layout,
                    // so the edit chips keep the bar uncluttered. Filled star = pinned.
                    if (settings != null && !state.editMode) {
                        com.github.itskenny0.r1ha.ui.components.PinToggle(
                            pinned = isPinned,
                            onClick = {
                                val title = view?.title?.takeUnless { it.isBlank() } ?: viewPath
                                pinScope.launch {
                                    if (isPinned) settings.unpinDashboard(pinRoute)
                                    else settings.pinDashboard(pinRoute, title, view?.icon)
                                }
                            },
                        )
                        Spacer(Modifier.width(6.dp))
                    }
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
            view == null && config.isStrategyGenerated -> StrategyFallback(onOpenLovelace = onOpenLovelace)
            view == null -> ErrorScrim(text = "View '$viewPath' not in dashboard.")
            isStrategy -> StrategyFallback(onOpenLovelace = onOpenLovelace)
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
                badges = view.badges,
                stateMap = entities,
                // The view model carries no masonry-vs-sections distinction, so
                // express no column preference and let dashboardColumnCount()
                // pick the tier's natural count (clamped per screen width). This
                // keeps narrow screens single-column and only widens on tablets.
                requestedColumns = null,
                onAction = { action ->
                    scope.launch {
                        dispatchLovelaceAction(
                            action = action,
                            fallbackEntityId = when (action) {
                                is LovelaceAction.CallService -> action.entityId
                                is LovelaceAction.Builtin -> action.entityId
                                else -> null
                            },
                            haRepository = haRepository,
                            // A `navigate` tap (cards or badges) targets another view
                            // in the same dashboard; route it to the view opener.
                            onNavigate = { path -> onOpenView(path) },
                            onOpenUrl = { url -> launchUrl(context, url) },
                            onMoreInfo = handleMoreInfo,
                            // Live state lookup by raw id so a toggle flips the right
                            // direction (the dispatcher reads isOn to pick turn_on vs
                            // turn_off / open vs close).
                            stateLookup = { rawId -> entities?.get(rawId) },
                        )
                    }
                },
            )
        }
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

    // Ultra-detail more-info sheet. Only reachable when settings is non-null
    // (real app, not isolation render) and the effective per-entity flag
    // resolved to true in handleMoreInfo, which is the only writer of
    // moreInfoEntityId.
    val moreInfoId = moreInfoEntityId
    if (moreInfoId != null && settings != null) {
        com.github.itskenny0.r1ha.feature.moreinfo.MoreInfoSheet(
            haRepository = haRepository,
            settings = settings,
            entityId = moreInfoId,
            onDismiss = { moreInfoEntityId = null },
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
    badges: List<com.github.itskenny0.r1ha.core.lovelace.LovelaceBadge>,
    stateMap: Map<String, com.github.itskenny0.r1ha.core.ha.EntityState>?,
    onAction: (LovelaceAction) -> Unit,
    /** Column count the view config asks for (legacy masonry `columns`, or a
     *  sections view's `max_columns`). Null means "no preference, use the
     *  tier default". Always clamped to the tier ceiling so a wide-config
     *  view never crams several cards into one row on a phone. */
    requestedColumns: Int? = null,
) {
    // Wrap the live map in a stable, value-equal holder once per emission.
    // A bare Map is an unstable Compose parameter, so without this every
    // card recomposes on every websocket state event; the holder + per-card
    // slicing below lets a card skip when its own entities didn't change.
    val states = remember(stateMap) {
        com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates.ofRaw(stateMap ?: emptyMap())
    }
    val tier = LocalWindowTier.current.tier
    val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
    val columns = dashboardColumnCount(tier, requestedColumns)
    val scroll = rememberScrollState()
    // The scroll viewport fills the window so the scrollbar tracks the full
    // height; the inner content column carries the responsive gutter and, on
    // roomy tiers, a centred max-width cap so the badge row and card grid read
    // as a centred column instead of one wall-wide line on a 13in panel. On
    // R1 / compact maxContentWidth is Unspecified, so widthIn is a no-op and the
    // content fills the narrow panel exactly as before.
    val contentWidth = if (dimens.capsContentWidth) {
        Modifier.widthIn(max = dimens.maxContentWidth)
    } else {
        Modifier
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scroll),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(contentWidth)
            .padding(horizontal = dimens.screenGutter, vertical = dimens.sectionGap),
    ) {
        // View badges ("chips on top"): a horizontal, scrollable row above the
        // cards. Renders nothing (and adds no gap) when the view has none. The
        // row itself scrolls horizontally so a 10+ badge view never clips on R1.
        if (badges.isNotEmpty()) {
            LovelaceBadgeRow(
                badges = badges,
                states = states,
                onAction = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
        }
        if (columns <= 1) {
            // Single column: render cards in order, one per row. This is the
            // R1 / compact-phone path and must stay a plain vertical list so
            // the narrow panel never tries to share a row between two cards.
            // Drop cards whose visibility conditions fail first, so a hidden
            // conditional leaves no double inter-card gap (the gap is emitted
            // per-visible-card, not per-original-index). The original index is
            // carried into the key so composition identity stays stable when a
            // conditional toggles visibility (the surviving cards keep their key
            // even though their position in the visible list shifts).
            val visible = cards.withIndex().filter { (_, card) ->
                com.github.itskenny0.r1ha.feature.dashboards.cards.cardWillRender(card, states.sliceFor(card))
            }
            visible.forEachIndexed { position, (originalIndex, card) ->
                if (position > 0) Spacer(Modifier.height(10.dp))
                androidx.compose.runtime.key(originalIndex, card.raw) {
                    LovelaceCardRenderer(
                        card = card,
                        stateMap = states.sliceFor(card),
                        onAction = onAction,
                    )
                }
            }
        } else {
            // Multi-column: distribute cards round-robin into `columns`
            // balanced vertical lanes (the same shape HA's masonry layout
            // uses). Each lane gets an equal fraction of the width via
            // weight(), so inner grids inside a card clamp to the lane and
            // can't overflow the screen.
            // Distribute only the cards that will actually render, so a hidden
            // conditional doesn't leave an empty slot (and the per-lane gaps in
            // spacedBy don't stack around a zero-height child). Original indices
            // are preserved for stable composition keys.
            val visibleIndices = cards.indices.filter {
                com.github.itskenny0.r1ha.feature.dashboards.cards.cardWillRender(cards[it], states.sliceFor(cards[it]))
            }
            val lanes = distributeIndicesIntoLanes(visibleIndices, columns)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                lanes.forEach { laneIndices ->
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .wrapContentHeight(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        laneIndices.forEach { index ->
                            val card = cards[index]
                            androidx.compose.runtime.key(index, card.raw) {
                                LovelaceCardRenderer(
                                    card = card,
                                    stateMap = states.sliceFor(card),
                                    onAction = onAction,
                                )
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(28.dp))
    }
    }
}

/**
 * Decide how many side-by-side card columns the Lovelace view should use.
 *
 * Pure (no Compose / Android deps) so it is unit-testable. Drives the
 * column count off the app-wide [WindowTier] so narrow screens never cram
 * cards into a single row:
 *  - [WindowTier.R1] and [WindowTier.Compact] always collapse to a single
 *    column (the R1 panel and ordinary phones get one card per row).
 *  - [WindowTier.Medium] allows up to two.
 *  - [WindowTier.Expanded] allows up to four.
 *
 * [requestedColumns] is what the view/card config asks for (legacy masonry
 * `columns` or a sections view's `max_columns`). It is honoured when sane
 * but always clamped to the tier ceiling, so a config authored on a desktop
 * can never force four cards onto one phone row. A null or non-positive
 * request falls back to the tier's natural default.
 */
internal fun dashboardColumnCount(tier: WindowTier, requestedColumns: Int?): Int {
    val ceiling = when (tier) {
        WindowTier.R1 -> 1
        WindowTier.COMPACT -> 1
        WindowTier.MEDIUM -> 2
        WindowTier.EXPANDED -> 4
        WindowTier.EXTRA_LARGE -> 4
    }
    val natural = when (tier) {
        WindowTier.R1 -> 1
        WindowTier.COMPACT -> 1
        WindowTier.MEDIUM -> 2
        WindowTier.EXPANDED -> 3
        WindowTier.EXTRA_LARGE -> 4
    }
    val desired = requestedColumns?.takeIf { it > 0 } ?: natural
    return desired.coerceIn(1, ceiling)
}

/**
 * Split [count] card indices into [columns] balanced lanes, round-robin so
 * the first card goes to lane 0, the second to lane 1, and so on. Returns a
 * list with exactly [columns] lanes (some may be empty when there are fewer
 * cards than columns). [columns] is clamped to at least one.
 */
internal fun distributeCardsIntoLanes(count: Int, columns: Int): List<List<Int>> =
    distributeIndicesIntoLanes((0 until count).toList(), columns)

/**
 * Distribute an explicit list of card [indices] round-robin into [columns]
 * lanes, preserving the original index values (so composition keys stay stable
 * even after hidden conditionals are filtered out). The Nth entry of [indices]
 * goes to lane `N % columns`. Returns exactly [columns] lanes (some may be
 * empty). [columns] is clamped to at least one.
 */
internal fun distributeIndicesIntoLanes(indices: List<Int>, columns: Int): List<List<Int>> {
    val lanes = columns.coerceAtLeast(1)
    val result = List(lanes) { mutableListOf<Int>() }
    indices.forEachIndexed { position, originalIndex ->
        result[position % lanes].add(originalIndex)
    }
    return result
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
            // Centre + cap each edit card on roomy tiers so the reorder list
            // doesn't stretch one card wall-wide on a 13in panel. The drag
            // column itself stays full-width so the long-press hit area and
            // drag math are untouched; only the visible card is capped. On
            // R1 / compact maxContentWidth is Unspecified, so widthIn is a
            // no-op and the card fills the narrow panel as before.
            val editDimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
            val editCap = if (editDimens.capsContentWidth) {
                Modifier.widthIn(max = editDimens.maxContentWidth)
            } else {
                Modifier
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
              Column(modifier = Modifier.fillMaxWidth().then(editCap)) {
                EditCardWrapper(
                    rendered = item,
                    isDragging = isDragging,
                    dragHandle = handle,
                    onEdit = { onEdit(idx) },
                    onDelete = { onDelete(idx) },
                )
              }
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
            stateMap = com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates.EMPTY,
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Text(text = "Empty view", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.screenTitle), color = R1.Ink)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (editMode) "Tap ADD CARD below to insert one." else "This view has no cards. Toggle EDIT to add one.",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
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

/**
 * Shown when a dashboard (or the current view) is generated entirely by an
 * HA strategy that R1HA can't expand natively. Explains the situation and
 * offers a single button that hands off to the Lovelace WebView so the user
 * is never left staring at a silently-empty dashboard.
 */
@Composable
private fun StrategyFallback(onOpenLovelace: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxWidth()
                .widthIn(max = 520.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeM)
                .padding(horizontal = 20.dp, vertical = 24.dp),
        ) {
            Text(text = "Generated dashboard", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.screenTitle), color = R1.Ink)
            Spacer(Modifier.height(10.dp))
            Text(
                text = "This dashboard is built by a Home Assistant strategy, so its cards are assembled on the server. R1HA can't recreate that layout natively, but you can open it in the full Lovelace view.",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.InkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.height(18.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeRound)
                    .background(R1.AccentWarm)
                    .r1Pressable(onClick = onOpenLovelace)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(text = "OPEN IN LOVELACE", style = R1.labelMicro, color = R1.Bg)
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
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Text(text = "Couldn't load", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.screenTitle), color = R1.StatusAmber)
            Spacer(Modifier.height(8.dp))
            Text(text, style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body), color = R1.InkSoft, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
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

/**
 * Launch a `url` / `navigate`-to-url / custom-card tap target in the
 * system browser. A blank or unparseable URL is dropped with a toast
 * rather than crashing, and a device with no browser activity surfaces
 * the same friendly message instead of throwing ActivityNotFound.
 */
private fun launchUrl(context: android.content.Context, url: String) {
    val trimmed = url.trim()
    if (trimmed.isEmpty()) {
        com.github.itskenny0.r1ha.core.util.Toaster.error("No link to open")
        return
    }
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(trimmed),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        com.github.itskenny0.r1ha.core.util.Toaster.error("No app to open link")
    }
}
