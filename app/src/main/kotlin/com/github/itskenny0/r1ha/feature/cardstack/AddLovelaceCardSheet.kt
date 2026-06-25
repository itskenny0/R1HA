package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LOVELACE_EDIT_JSON
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConfig
import com.github.itskenny0.r1ha.core.lovelace.LovelaceDashboard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.lovelace.PICKER_TEMPLATES
import com.github.itskenny0.r1ha.core.lovelace.encodeCardJson
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.github.itskenny0.r1ha.core.lovelace.strategies.StrategyDataLoader
import com.github.itskenny0.r1ha.core.lovelace.strategies.StrategyEngine
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The add-cards surface: three paths onto a page's deck.
 *
 *  1. FROM DASHBOARD (primary): browse the server's Lovelace dashboards,
 *     drill dashboard -> view -> cards, tap rows to select, add one or many.
 *  2. IMPORT DASHBOARD: pick a dashboard, choose which views become pages
 *     (one page per view, named after the view, cards in order), confirm.
 *  3. NEW CARD: the type-template grid landing in the structured editor
 *     ([CardMiniEditor]), JSON only as the escape hatch.
 *
 * Visual language matches the rest of the kiosk chrome: dim full-screen
 * backdrop, hairline-bordered near-black panel, warm-accent section header,
 * all-caps micro labels.
 */
@Composable
internal fun AddLovelaceCardSheet(
    haRepository: HaRepository,
    pageName: String,
    /** Commit picked / authored cards (raw config JSON strings) to the page. */
    onAddCards: (List<String>) -> Unit,
    /** Commit a dashboard import: one page spec per selected view. */
    onImportPages: (List<ImportablePage>) -> Unit,
    onDismiss: () -> Unit,
) {
    // Step machine. Back unwinds one step at a time so the flow feels like a
    // drill-down, not a modal maze.
    var step by remember { mutableStateOf<AddStep>(AddStep.Home) }
    // Lazily fetched server data, shared across steps so re-entering a list
    // doesn't refetch.
    var dashboards by remember { mutableStateOf<List<LovelaceDashboard>?>(null) }
    val configCache = remember { mutableStateOf(mapOf<String, LovelaceConfig>()) }
    var loadError by remember { mutableStateOf<String?>(null) }

    val goBack: () -> Unit = {
        step = when (val s = step) {
            AddStep.Home -> { onDismiss(); AddStep.Home }
            is AddStep.PickDashboard -> AddStep.Home
            is AddStep.PickView -> AddStep.PickDashboard(s.forImport)
            is AddStep.PickCards -> AddStep.PickView(s.dashboard, forImport = false)
            is AddStep.ImportConfirm -> AddStep.PickDashboard(forImport = true)
            is AddStep.NewCardType -> AddStep.Home
            is AddStep.NewCardEdit -> AddStep.NewCardType
        }
    }
    androidx.activity.compose.BackHandler(onBack = goBack)

    // Dashboard list fetch, shared by both browse and import entry points.
    androidx.compose.runtime.LaunchedEffect(step is AddStep.PickDashboard) {
        if (step is AddStep.PickDashboard && dashboards == null) {
            loadError = null
            dashboards = loadDashboardList(haRepository)
                .onFailure { loadError = it.message ?: "Couldn't load dashboards" }
                .getOrNull()
        }
    }
    // Per-dashboard config fetch for the view / card / import steps.
    val configKeyWanted = when (val s = step) {
        is AddStep.PickView -> s.dashboard.cacheKey()
        is AddStep.PickCards -> s.dashboard.cacheKey()
        is AddStep.ImportConfirm -> s.dashboard.cacheKey()
        else -> null
    }
    androidx.compose.runtime.LaunchedEffect(configKeyWanted) {
        val s = step
        val urlPath = when (s) {
            is AddStep.PickView -> s.dashboard.urlPath
            is AddStep.PickCards -> s.dashboard.urlPath
            is AddStep.ImportConfirm -> s.dashboard.urlPath
            else -> return@LaunchedEffect
        }
        val key = urlPath ?: DEFAULT_DASH_KEY
        if (configCache.value.containsKey(key)) return@LaunchedEffect
        loadError = null
        loadParsedDashboardConfig(haRepository, urlPath)
            .onSuccess { configCache.value = configCache.value + (key to it) }
            .onFailure { loadError = it.message ?: "Couldn't load dashboard" }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.94f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(14.dp),
        ) {
            when (val s = step) {
                AddStep.Home -> AddHome(
                    pageName = pageName,
                    onFromDashboard = { step = AddStep.PickDashboard(forImport = false) },
                    onImport = { step = AddStep.PickDashboard(forImport = true) },
                    onNewCard = { step = AddStep.NewCardType },
                    onClose = onDismiss,
                )
                is AddStep.PickDashboard -> DashboardListStep(
                    title = if (s.forImport) "IMPORT DASHBOARD" else "FROM DASHBOARD",
                    dashboards = dashboards,
                    error = loadError,
                    onPick = { dash ->
                        step = if (s.forImport) AddStep.ImportConfirm(dash)
                        else AddStep.PickView(dash, forImport = false)
                    },
                    onBack = goBack,
                )
                is AddStep.PickView -> ViewListStep(
                    dashboard = s.dashboard,
                    config = configCache.value[s.dashboard.cacheKey()],
                    error = loadError,
                    onPick = { view -> step = AddStep.PickCards(s.dashboard, view.path) },
                    onBack = goBack,
                )
                is AddStep.PickCards -> {
                    val config = configCache.value[s.dashboard.cacheKey()]
                    val view = config?.views?.firstOrNull { it.path == s.viewPath }
                    CardPickStep(
                        dashboard = s.dashboard,
                        view = view,
                        error = loadError,
                        onAdd = { cards -> onAddCards(cards.map { cardBlob(it) }) },
                        onBack = goBack,
                    )
                }
                is AddStep.ImportConfirm -> ImportConfirmStep(
                    dashboard = s.dashboard,
                    config = configCache.value[s.dashboard.cacheKey()],
                    error = loadError,
                    onImport = onImportPages,
                    onBack = goBack,
                )
                AddStep.NewCardType -> NewCardTypeStep(
                    onPick = { template -> step = AddStep.NewCardEdit(template.toString()) },
                    onBack = goBack,
                )
                is AddStep.NewCardEdit -> {
                    // The structured editor renders as its own full overlay on
                    // top; keep a thin placeholder behind it so back-unwind
                    // lands somewhere sensible.
                    Text(text = "NEW CARD", style = R1.sectionHeader, color = R1.AccentWarm)
                }
            }
        }
    }

    val editStep = step as? AddStep.NewCardEdit
    if (editStep != null) {
        CardMiniEditor(
            initialRaw = editStep.templateRaw,
            haRepository = haRepository,
            onSave = { raw -> onAddCards(listOf(raw)) },
            onDismiss = goBack,
        )
    }
}

/** Step machine for [AddLovelaceCardSheet]. */
private sealed interface AddStep {
    data object Home : AddStep
    data class PickDashboard(val forImport: Boolean) : AddStep
    data class PickView(val dashboard: LovelaceDashboard, val forImport: Boolean) : AddStep
    data class PickCards(val dashboard: LovelaceDashboard, val viewPath: String) : AddStep
    data class ImportConfirm(val dashboard: LovelaceDashboard) : AddStep
    data object NewCardType : AddStep
    data class NewCardEdit(val templateRaw: String) : AddStep
}

private const val DEFAULT_DASH_KEY = "_default_"

private fun LovelaceDashboard.cacheKey(): String = urlPath ?: DEFAULT_DASH_KEY

/** Fetch + parse the dashboard list, always including the default dashboard. */
private suspend fun loadDashboardList(repo: HaRepository): Result<List<LovelaceDashboard>> =
    repo.listLovelaceDashboards().map { arr ->
        val parsed = runCatching { LovelaceParser.parseDashboards(arr) }.getOrDefault(emptyList())
        if (parsed.any { it.urlPath == null }) parsed
        else listOf(DEFAULT_DASHBOARD_ENTRY) + parsed
    }

private val DEFAULT_DASHBOARD_ENTRY = LovelaceDashboard(
    id = null,
    urlPath = null,
    title = "Default dashboard",
    icon = "mdi:view-dashboard",
    showInSidebar = true,
    requireAdmin = false,
    mode = "storage",
)

/**
 * Fetch + parse one dashboard config, expanding strategies the same way the
 * dashboards screen does (so an auto-generated dashboard imports as concrete
 * cards rather than an empty husk). Reuses the fetch machinery the dashboards
 * feature already runs on; only the in-memory caching lives in the sheet.
 */
internal suspend fun loadParsedDashboardConfig(
    repo: HaRepository,
    urlPath: String?,
): Result<LovelaceConfig> =
    repo.fetchLovelaceConfig(urlPath, forceRefresh = false).map { raw ->
        val effectiveRaw = if (StrategyEngine.hasAnyStrategy(raw)) {
            runCatching {
                val data = StrategyDataLoader(repo)
                    .load(needsUsagePrediction = StrategyEngine.referencesUsagePrediction(raw))
                StrategyEngine.expand(raw, data)
            }.onFailure {
                R1Log.w("AddCards", "strategy expand failed: ${it.message}")
            }.getOrDefault(raw)
        } else {
            raw
        }
        runCatching { LovelaceParser.parseConfig(effectiveRaw) }
            .onFailure { R1Log.w("AddCards", "parse failed: ${it.message}") }
            .getOrElse { LovelaceConfig(title = null, views = emptyList()) }
    }

// ── Steps ────────────────────────────────────────────────────────────────────

@Composable
private fun AddHome(
    pageName: String,
    onFromDashboard: () -> Unit,
    onImport: () -> Unit,
    onNewCard: () -> Unit,
    onClose: () -> Unit,
) {
    Text(text = "ADD CARDS", style = R1.sectionHeader, color = R1.AccentWarm)
    if (pageName.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(text = pageName.uppercase(), style = R1.labelMicro, color = R1.InkSoft)
    }
    Spacer(Modifier.height(12.dp))
    PathRow(
        title = "FROM DASHBOARD",
        body = "Pick cards from an existing Lovelace dashboard.",
        accent = true,
        onClick = onFromDashboard,
    )
    Spacer(Modifier.height(8.dp))
    PathRow(
        title = "IMPORT DASHBOARD",
        body = "Turn a whole dashboard into pages, one per view.",
        accent = false,
        onClick = onImport,
    )
    Spacer(Modifier.height(8.dp))
    PathRow(
        title = "NEW CARD",
        body = "Build a card from a template: entity, iframe, markdown...",
        accent = false,
        onClick = onNewCard,
    )
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SheetButton(label = "CLOSE", accent = false, onClick = onClose)
    }
}

@Composable
private fun PathRow(title: String, body: String, accent: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (accent) R1.AccentWarm.copy(alpha = 0.6f) else R1.Hairline,
                R1.ShapeM,
            )
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = title,
            style = R1.bodyEmph,
            color = if (accent) R1.AccentWarm else R1.Ink,
        )
        Spacer(Modifier.height(2.dp))
        Text(text = body, style = R1.labelMicro, color = R1.InkSoft)
    }
}

@Composable
private fun StepHeader(title: String, subtitle: String?, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .r1Pressable(onClick = onBack, contentDescription = "Back")
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) { Text(text = "◀", style = R1.labelMicro, color = R1.InkSoft) }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(text = title, style = R1.sectionHeader, color = R1.AccentWarm)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle.uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun LoadingOrError(error: String?) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(vertical = 26.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (error != null) {
            Text(text = error, style = R1.body, color = R1.StatusRed)
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = R1.AccentWarm,
            )
        }
    }
}

@Composable
private fun DashboardListStep(
    title: String,
    dashboards: List<LovelaceDashboard>?,
    error: String?,
    onPick: (LovelaceDashboard) -> Unit,
    onBack: () -> Unit,
) {
    StepHeader(title = title, subtitle = "PICK A DASHBOARD", onBack = onBack)
    if (dashboards == null) {
        LoadingOrError(error)
        return
    }
    LazyColumn(
        modifier = Modifier.heightIn(max = 300.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(dashboards, key = { it.cacheKey() }) { dash ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeM)
                    .r1Pressable(onClick = { onPick(dash) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = dash.title.ifBlank { dash.urlPath ?: "Dashboard" },
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    dash.urlPath?.let {
                        Text(
                            text = it,
                            style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                            color = R1.InkMuted,
                            maxLines = 1,
                        )
                    }
                }
                Text(text = "▶", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun ViewListStep(
    dashboard: LovelaceDashboard,
    config: LovelaceConfig?,
    error: String?,
    onPick: (LovelaceView) -> Unit,
    onBack: () -> Unit,
) {
    StepHeader(
        title = "FROM DASHBOARD",
        subtitle = dashboard.title.ifBlank { dashboard.urlPath ?: "dashboard" },
        onBack = onBack,
    )
    if (config == null) {
        LoadingOrError(error)
        return
    }
    val views = config.views.filter { !it.subview }
    if (views.isEmpty()) {
        Text(text = "No views in this dashboard.", style = R1.body, color = R1.InkMuted)
        return
    }
    LazyColumn(
        modifier = Modifier.heightIn(max = 300.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(views, key = { _, v -> v.path }) { index, view ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeM)
                    .r1Pressable(onClick = { onPick(view) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = importPageName(view, index),
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${view.cards.size} CARD${if (view.cards.size == 1) "" else "S"}",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Text(text = "▶", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun CardPickStep(
    dashboard: LovelaceDashboard,
    view: LovelaceView?,
    error: String?,
    onAdd: (List<LovelaceCard>) -> Unit,
    onBack: () -> Unit,
) {
    StepHeader(
        title = "PICK CARDS",
        subtitle = view?.let { it.title ?: it.path } ?: dashboard.title,
        onBack = onBack,
    )
    if (view == null) {
        LoadingOrError(error)
        return
    }
    if (view.cards.isEmpty()) {
        Text(text = "No cards in this view.", style = R1.body, color = R1.InkMuted)
        return
    }
    // Selection by card index within the view: the same config can contain
    // two identical cards and both must be individually pickable.
    val selected = remember(view) { androidx.compose.runtime.mutableStateListOf<Int>() }
    Text(
        text = "TAP TO SELECT · ADD INSTALLS THEM IN ORDER",
        style = R1.labelMicro,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(6.dp))
    LazyColumn(
        modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(view.cards) { idx, card ->
            val isSelected = idx in selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(if (isSelected) R1.AccentWarm.copy(alpha = 0.16f) else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (isSelected) R1.AccentWarm.copy(alpha = 0.7f) else R1.Hairline,
                        R1.ShapeM,
                    )
                    .r1Pressable(onClick = {
                        if (isSelected) selected.remove(idx) else selected.add(idx)
                    })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = deckCardTitle(card),
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = card.type.uppercase().replace('-', ' '),
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Text(
                    text = if (isSelected) "●" else "○",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.AccentWarm else R1.InkMuted,
                )
            }
        }
    }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SheetButton(
            label = if (selected.isEmpty()) "SELECT CARDS" else "ADD ${selected.size}",
            accent = selected.isNotEmpty(),
            onClick = {
                if (selected.isNotEmpty()) {
                    // Deck order follows the view's order, not tap order, so
                    // the imported slice reads like the source dashboard.
                    onAdd(selected.sorted().mapNotNull { view.cards.getOrNull(it) })
                }
            },
        )
    }
}

@Composable
private fun ImportConfirmStep(
    dashboard: LovelaceDashboard,
    config: LovelaceConfig?,
    error: String?,
    onImport: (List<ImportablePage>) -> Unit,
    onBack: () -> Unit,
) {
    StepHeader(
        title = "IMPORT DASHBOARD",
        subtitle = dashboard.title.ifBlank { dashboard.urlPath ?: "dashboard" },
        onBack = onBack,
    )
    if (config == null) {
        LoadingOrError(error)
        return
    }
    val views = remember(config) { config.views.filter { !it.subview && it.cards.isNotEmpty() } }
    if (views.isEmpty()) {
        Text(
            text = "Nothing importable: no views with cards.",
            style = R1.body,
            color = R1.InkMuted,
        )
        return
    }
    // All views selected by default; deselect to import a subset (down to a
    // single view). Tracked by INDEX, not view.path: the parser defaults a
    // missing path to the view's index, so two views can legally share a path
    // (one explicit "1", one defaulted) and path-keyed selection would toggle
    // both together and crash the LazyColumn on duplicate keys.
    val deselected = remember(config) { androidx.compose.runtime.mutableStateListOf<Int>() }
    Text(
        text = "ONE PAGE PER VIEW · NAMED AFTER THE VIEW",
        style = R1.labelMicro,
        color = R1.InkMuted,
    )
    Spacer(Modifier.height(6.dp))
    LazyColumn(
        modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(views, key = { index, _ -> index }) { index, view ->
            val isSelected = index !in deselected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(if (isSelected) R1.AccentWarm.copy(alpha = 0.16f) else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (isSelected) R1.AccentWarm.copy(alpha = 0.7f) else R1.Hairline,
                        R1.ShapeM,
                    )
                    .r1Pressable(onClick = {
                        if (isSelected) deselected.add(index) else deselected.remove(index)
                    })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = importPageName(view, index),
                        style = R1.body,
                        color = R1.Ink,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = "${view.cards.size} CARD${if (view.cards.size == 1) "" else "S"}",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Text(
                    text = if (isSelected) "●" else "○",
                    style = R1.labelMicro,
                    color = if (isSelected) R1.AccentWarm else R1.InkMuted,
                )
            }
        }
    }
    val selectedViews = views.filterIndexed { index, _ -> index !in deselected }
    Spacer(Modifier.height(12.dp))
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        SheetButton(
            label = if (selectedViews.isEmpty()) "SELECT VIEWS"
            else "IMPORT ${selectedViews.size} PAGE${if (selectedViews.size == 1) "" else "S"}",
            accent = selectedViews.isNotEmpty(),
            onClick = {
                if (selectedViews.isNotEmpty()) {
                    onImport(viewsToImportablePages(selectedViews))
                }
            },
        )
    }
}

@Composable
private fun NewCardTypeStep(
    onPick: (JsonObject) -> Unit,
    onBack: () -> Unit,
) {
    StepHeader(title = "NEW CARD", subtitle = "PICK A TYPE", onBack = onBack)
    LazyColumn(
        modifier = Modifier.heightIn(max = 320.dp).fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(PICKER_TEMPLATES, key = { it.first }) { (type, template) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeM)
                    .r1Pressable(onClick = { onPick(template) })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = type.uppercase(), style = R1.bodyEmph, color = R1.Ink)
                    Text(
                        text = describeCardType(type),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
                Text(text = "▶", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

private fun describeCardType(type: String): String = when (type) {
    "entities" -> "Vertical entity list"
    "glance" -> "Compact tile grid"
    "tile" -> "Modern one-entity tile"
    "button" -> "Single action button"
    "light" -> "Brightness orb"
    "gauge" -> "Numeric arc"
    "markdown" -> "Markdown body"
    "iframe" -> "Embedded web page"
    "heading" -> "Section heading"
    "weather-forecast" -> "Weather + forecast"
    "vertical-stack" -> "Column of cards"
    "horizontal-stack" -> "Row of cards"
    "grid" -> "Cards in N columns"
    "conditional" -> "Show only when conditions match"
    else -> type
}

// ── Structured editor ────────────────────────────────────────────────────────
// (The per-type key ownership + write-back logic lives in CardStructuredEdit.kt
// so the round-trip is unit-testable; this file owns only the form UI.)

/**
 * Structured per-type card editor: the tight integration replacing the
 * JSON-first flow. Entity-bearing cards get an inline entity picker with
 * search, iframes get url + title + aspect chips, markdown gets a body field,
 * buttons get name + icon + show toggles (so a pinned Broadlink button is as
 * customisable as any other card); everything else (stacks, conditionals,
 * custom types) lands in the raw JSON mode, which also stays one tap away as
 * the advanced escape hatch. Unknown keys in the config are preserved
 * verbatim, so structured edits never destroy options the form doesn't model
 * (a Broadlink button's call-service tap_action above all).
 */
@Composable
internal fun CardMiniEditor(
    initialRaw: String,
    haRepository: HaRepository,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialObj = remember(initialRaw) { parseCardJsonBlob(initialRaw) }
    val type = remember(initialObj) {
        (initialObj?.get("type") as? JsonPrimitive)?.content.orEmpty()
    }
    val structuredCapable = remember(initialObj, type) {
        initialObj != null && (
            type in SINGLE_ENTITY_TYPES || type in MULTI_ENTITY_TYPES ||
                type == "iframe" || type == "markdown" || type == "heading"
            ) &&
            // Multi-entity editing only handles plain string entries; rows
            // with per-entity options would be silently flattened, so those
            // configs go straight to JSON.
            (type !in MULTI_ENTITY_TYPES || entitiesAllPrimitive(initialObj))
    }
    // Invalid blobs (repair path) and structural types start in JSON mode.
    var jsonMode by remember { mutableStateOf(!structuredCapable) }

    // Field state, seeded from the parsed config.
    var title by remember { mutableStateOf(initialObj.str("title")) }
    var heading by remember { mutableStateOf(initialObj.str("heading")) }
    var entity by remember { mutableStateOf(initialObj.str("entity")) }
    var url by remember { mutableStateOf(initialObj.str("url")) }
    var aspect by remember { mutableStateOf(initialObj.str("aspect_ratio")) }
    var content by remember { mutableStateOf(initialObj.str("content")) }
    // Button-card fields. The toggle defaults mirror HA's button card so the
    // chips show how an omitting config actually renders.
    var name by remember { mutableStateOf(initialObj.str("name")) }
    var icon by remember { mutableStateOf(initialObj.str("icon")) }
    // Per-key show/hide state seeded from the config, keyed by the real JSON key
    // and holding the real value (HIDE-sense resolved only on display).
    val toggleState = remember(initialObj, type) {
        androidx.compose.runtime.mutableStateMapOf<String, Boolean>().apply {
            cardTogglesFor(type).forEach { t -> put(t.key, initialObj.boolOr(t.key, t.default)) }
        }
    }
    val multiEntities = remember(initialObj) {
        androidx.compose.runtime.mutableStateListOf<String>().apply {
            addAll(primitiveEntities(initialObj))
        }
    }
    // Raw JSON text (the escape hatch); kept in sync from the structured side
    // only when the user switches modes, so typing in one mode never fights
    // the other.
    var jsonText by remember {
        mutableStateOf(
            initialObj?.let { LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), it) }
                ?: initialRaw,
        )
    }
    var entityPickerFor by remember { mutableStateOf<EntityPickTarget?>(null) }

    fun buildStructured(): JsonObject? {
        val base = initialObj ?: return null
        // Per-type key ownership + verbatim passthrough live in
        // buildStructuredCard (CardStructuredEdit.kt), unit-tested there.
        return buildStructuredCard(
            base,
            CardEditorForm(
                type = type,
                title = title,
                heading = heading,
                entity = entity,
                url = url,
                aspect = aspect,
                content = content,
                entities = multiEntities.toList(),
                name = name,
                icon = icon,
                toggles = toggleState.toMap(),
            ),
        )
    }

    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.94f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "EDIT CARD", style = R1.sectionHeader, color = R1.AccentWarm)
                    Text(
                        text = (type.ifBlank { "card" }).uppercase().replace('-', ' '),
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
                if (structuredCapable) {
                    SheetButton(
                        label = if (jsonMode) "FORM" else "EDIT JSON",
                        accent = false,
                        onClick = {
                            if (!jsonMode) {
                                // Entering JSON mode: materialise the current
                                // form into text so the user edits what they
                                // see.
                                buildStructured()?.let {
                                    jsonText = LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), it)
                                }
                                jsonMode = true
                            } else {
                                // Leaving JSON mode: re-seed the form only on
                                // parseable text; otherwise stay (no data loss).
                                val parsed = parseCardJsonBlob(jsonText)
                                if (parsed != null) {
                                    title = parsed.str("title")
                                    heading = parsed.str("heading")
                                    entity = parsed.str("entity")
                                    url = parsed.str("url")
                                    aspect = parsed.str("aspect_ratio")
                                    content = parsed.str("content")
                                    name = parsed.str("name")
                                    icon = parsed.str("icon")
                                    toggleState.clear()
                                    cardTogglesFor(type).forEach { t ->
                                        toggleState[t.key] = parsed.boolOr(t.key, t.default)
                                    }
                                    multiEntities.clear()
                                    multiEntities.addAll(primitiveEntities(parsed))
                                    jsonMode = false
                                }
                            }
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (jsonMode) {
                val parsed = parseCardJsonBlob(jsonText)
                TextField(
                    value = jsonText,
                    onValueChange = { jsonText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp)
                        .border(1.dp, R1.Hairline, R1.ShapeM),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = R1.SurfaceMuted,
                        unfocusedContainerColor = R1.SurfaceMuted,
                        focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                        focusedTextColor = R1.Ink,
                        unfocusedTextColor = R1.Ink,
                    ),
                    textStyle = androidx.compose.ui.text.TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (parsed != null) {
                        "JSON ok · type = ${(parsed["type"] as? JsonPrimitive)?.content ?: "?"}"
                    } else {
                        "invalid JSON"
                    },
                    style = R1.labelMicro,
                    color = if (parsed != null) R1.AccentGreen else R1.StatusRed,
                )
            } else {
                when (type) {
                    // Headings label via `heading:`, buttons via `name:` (the
                    // button card has no `title:` key, so offering TITLE there
                    // edited a key the renderer never reads).
                    "heading" -> EditorField(label = "HEADING", value = heading, onChange = { heading = it })
                    "button" -> EditorField(label = "NAME", value = name, onChange = { name = it })
                    else -> EditorField(label = "TITLE", value = title, onChange = { title = it })
                }
                if (type == "button") {
                    Spacer(Modifier.height(8.dp))
                    EditorField(
                        label = "ICON (MDI:...)",
                        value = icon,
                        onChange = { icon = it },
                        monospace = true,
                    )
                }
                if (type in SINGLE_ENTITY_TYPES) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "ENTITY", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    EntityChip(
                        entityId = entity,
                        onClick = { entityPickerFor = EntityPickTarget.Single },
                    )
                }
                if (type in MULTI_ENTITY_TYPES) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "ENTITIES", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    multiEntities.forEachIndexed { idx, id ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(modifier = Modifier.weight(1f)) { EntityChip(entityId = id, onClick = null) }
                            Spacer(Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .r1Pressable(onClick = { multiEntities.removeAt(idx) })
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) { Text(text = "✕", style = R1.labelMicro, color = R1.StatusRed) }
                        }
                    }
                    SheetButton(
                        label = "+ ADD ENTITY",
                        accent = true,
                        onClick = { entityPickerFor = EntityPickTarget.Multi },
                    )
                }
                if (type == "iframe") {
                    Spacer(Modifier.height(8.dp))
                    EditorField(label = "URL", value = url, onChange = { url = it }, monospace = true)
                    Spacer(Modifier.height(8.dp))
                    Text(text = "ASPECT", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (preset in listOf("16:9", "4:3", "1:1", "2:1")) {
                            val selected = aspect == preset
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .background(if (selected) R1.AccentWarm.copy(alpha = 0.18f) else R1.SurfaceMuted)
                                    .border(
                                        1.dp,
                                        if (selected) R1.AccentWarm.copy(alpha = 0.7f) else R1.Hairline,
                                        R1.ShapeS,
                                    )
                                    .r1Pressable(onClick = { aspect = if (selected) "" else preset })
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text(
                                    text = preset,
                                    style = R1.labelMicro,
                                    color = if (selected) R1.AccentWarm else R1.InkSoft,
                                )
                            }
                        }
                    }
                }
                if (type == "markdown") {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "CONTENT", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    TextField(
                        value = content,
                        onValueChange = { content = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .border(1.dp, R1.Hairline, R1.ShapeM),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = R1.SurfaceMuted,
                            unfocusedContainerColor = R1.SurfaceMuted,
                            focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                            focusedTextColor = R1.Ink,
                            unfocusedTextColor = R1.Ink,
                        ),
                        textStyle = androidx.compose.ui.text.TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                        ),
                    )
                }
                val showToggles = cardTogglesFor(type)
                if (showToggles.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "SHOW", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        showToggles.forEach { t ->
                            val raw = toggleState[t.key] ?: t.default
                            val shown = toggleChipShown(raw, t.sense)
                            EditorToggleChip(
                                label = t.label,
                                selected = shown,
                                onClick = { toggleState[t.key] = toggleStoredValue(!shown, t.sense) },
                            )
                        }
                    }
                }
                if (!structuredCapable) {
                    Text(
                        text = "This card type edits as raw JSON.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton(label = "CANCEL", accent = false, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                val canSave = if (jsonMode) parseCardJsonBlob(jsonText) != null else initialObj != null
                SheetButton(
                    label = "SAVE",
                    accent = canSave,
                    onClick = {
                        val result = if (jsonMode) parseCardJsonBlob(jsonText) else buildStructured()
                        if (result != null) onSave(encodeCardJson(result))
                    },
                )
            }
        }
    }

    val pickTarget = entityPickerFor
    if (pickTarget != null) {
        EntityPickerOverlay(
            haRepository = haRepository,
            onPick = { id ->
                when (pickTarget) {
                    EntityPickTarget.Single -> entity = id
                    EntityPickTarget.Multi -> multiEntities.add(id)
                }
                entityPickerFor = null
            },
            onDismiss = { entityPickerFor = null },
        )
    }
}

private enum class EntityPickTarget { Single, Multi }

private fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.content.orEmpty()

/** True when the config's `entities:` array contains only plain string ids. */
internal fun entitiesAllPrimitive(obj: JsonObject): Boolean {
    val arr = obj["entities"] as? kotlinx.serialization.json.JsonArray ?: return true
    return arr.all { it is JsonPrimitive && it.isString }
}

private fun primitiveEntities(obj: JsonObject?): List<String> {
    val arr = obj?.get("entities") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return arr.mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
}

@Composable
private fun EditorField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    monospace: Boolean = false,
) {
    Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
    Spacer(Modifier.height(4.dp))
    com.github.itskenny0.r1ha.ui.components.R1TextField(
        value = value,
        onValueChange = onChange,
        placeholder = label,
        monospace = monospace,
    )
}

/** On/off chip for a card's native show/hide toggles (driven by cardTogglesFor);
 *  same visual language as the iframe aspect presets. */
@Composable
private fun EditorToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(if (selected) R1.AccentWarm.copy(alpha = 0.18f) else R1.SurfaceMuted)
            .border(
                1.dp,
                if (selected) R1.AccentWarm.copy(alpha = 0.7f) else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(onClick = onClick, contentDescription = "Toggle show $label")
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (selected) R1.AccentWarm else R1.InkSoft,
        )
    }
}

@Composable
private fun EntityChip(entityId: String, onClick: (() -> Unit)?) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .let { if (onClick != null) it.r1Pressable(onClick = onClick) else it }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = entityId.ifBlank { "TAP TO PICK AN ENTITY" },
            style = R1.body.copy(fontFamily = FontFamily.Monospace),
            color = if (entityId.isBlank()) R1.InkMuted else R1.Ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Full-screen entity picker with search, reusing the repository's
 * all-entities listing (same source the favourites picker searches). Renders
 * above the editor; tap a row to bind it.
 */
@Composable
private fun EntityPickerOverlay(
    haRepository: HaRepository,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val all by androidx.compose.runtime.produceState<List<com.github.itskenny0.r1ha.core.ha.EntityState>?>(
        initialValue = null,
    ) {
        value = haRepository.listAllEntitiesForSearch().getOrNull().orEmpty()
    }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onDismiss, hapticOnClick = false)
            .imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 560.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 18.dp)
                .clip(R1.ShapeM)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeM)
                .r1Pressable(onClick = {}, hapticOnClick = false)
                .padding(14.dp),
        ) {
            Text(text = "PICK ENTITY", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(8.dp))
            com.github.itskenny0.r1ha.ui.components.R1TextField(
                value = query,
                onValueChange = { query = it },
                placeholder = "SEARCH",
                monospace = false,
            )
            Spacer(Modifier.height(8.dp))
            val entities = all
            if (entities == null) {
                LoadingOrError(null)
            } else {
                val q = query.trim().lowercase()
                val filtered = if (q.isBlank()) entities else entities.filter {
                    it.friendlyName.lowercase().contains(q) || it.id.value.lowercase().contains(q)
                }
                LazyColumn(
                    modifier = Modifier.heightIn(max = 280.dp).fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(filtered.take(120), key = { it.id.value }) { ent ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .r1Pressable(onClick = { onPick(ent.id.value) })
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = ent.friendlyName.ifBlank { ent.id.value },
                                style = R1.body,
                                color = R1.Ink,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = ent.id.value,
                                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                                color = R1.InkMuted,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton(label = "CANCEL", accent = false, onClick = onDismiss)
            }
        }
    }
}
