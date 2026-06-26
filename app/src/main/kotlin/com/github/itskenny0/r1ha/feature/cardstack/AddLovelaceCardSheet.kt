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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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
                type == "iframe" || type == "markdown" || type == "heading" ||
                // Any type with a field schema is form-editable, even with no
                // entity (clock, picture): it renders its generic fields section.
                cardFieldsFor(type).isNotEmpty()
            )
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
    val rows = remember(initialObj) {
        androidx.compose.runtime.mutableStateListOf<CardEntityRow>().apply {
            addAll(parseEntityRows(initialObj ?: JsonObject(emptyMap())))
        }
    }
    // Generic field-schema values (cardFieldsFor): real config-key -> raw value.
    // Seeded from the config so every present key round-trips; the editor adds a
    // key here the moment a control is touched, which switches buildStructuredCard
    // from passthrough to form-owned for that key (see its field loop).
    val fieldValues = remember(initialObj, type) {
        androidx.compose.runtime.mutableStateMapOf<String, kotlinx.serialization.json.JsonElement>().apply {
            putAll(seedFieldValues(initialObj, type))
        }
    }
    // Which action field (tap_action/…) the bespoke action editor is open for.
    var actionEditorFor by remember { mutableStateOf<String?>(null) }
    // Which complex field (features/severity/segments) the bespoke editor is open for.
    var bespokeEditorFor by remember { mutableStateOf<BespokeFieldSpec?>(null) }
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
                rows = rows.toList(),
                name = name,
                icon = icon,
                toggles = toggleState.toMap(),
                values = fieldValues.toMap(),
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
                                    rows.clear()
                                    rows.addAll(parseEntityRows(parsed))
                                    fieldValues.clear()
                                    fieldValues.putAll(seedFieldValues(parsed, type))
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
                when {
                    // Headings label via `heading:`, buttons via `name:` (the
                    // button card has no `title:` key, so offering TITLE there
                    // edited a key the renderer never reads).
                    type == "heading" -> EditorField(label = "HEADING", value = heading, onChange = { heading = it })
                    type == "button" -> EditorField(label = "NAME", value = name, onChange = { name = it })
                    // Name-primary cards (tile, light, gauge…) label via the engine
                    // NAME field below; label-less cards (picture) show no label
                    // field at all. Only title-using cards render TITLE here.
                    !typeUsesTitle(type) -> Unit
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
                    var expandedRow by remember { mutableStateOf(-1) }
                    rows.forEachIndexed { idx, row ->
                        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = if (row.special == null) {
                                        Modifier.weight(1f).clip(R1.ShapeS).r1Pressable(
                                            onClick = { expandedRow = if (expandedRow == idx) -1 else idx },
                                        )
                                    } else {
                                        Modifier.weight(1f)
                                    },
                                ) {
                                    EntityChip(
                                        entityId = if (row.special != null) "ADVANCED ROW (KEPT AS-IS)" else row.entityId,
                                        onClick = null,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(R1.ShapeS)
                                        .r1Pressable(
                                            onClick = {
                                                rows.removeAt(idx)
                                                // Keep the expansion pointing at the same row when an
                                                // earlier row is removed; collapse if it was this one.
                                                expandedRow = when {
                                                    expandedRow == idx -> -1
                                                    expandedRow > idx -> expandedRow - 1
                                                    else -> expandedRow
                                                }
                                            },
                                        )
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                ) { Text(text = "✕", style = R1.labelMicro, color = R1.StatusRed) }
                            }
                            if (expandedRow == idx && row.special == null) {
                                RowOptionsEditor(
                                    type = type,
                                    row = row,
                                    onChange = { rows[idx] = it },
                                )
                            }
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
                CardFieldsSection(
                    type = type,
                    values = fieldValues,
                    onPickEntity = { f -> entityPickerFor = EntityPickTarget.Field(f.key, f.domains) },
                    onEditAction = { key -> actionEditorFor = key },
                    onEditBespoke = { field -> bespokeEditorFor = field },
                )
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
                    EntityPickTarget.Multi -> rows.add(CardEntityRow(entityId = id))
                    is EntityPickTarget.Field -> fieldValues[pickTarget.key] = JsonPrimitive(id)
                }
                entityPickerFor = null
            },
            domains = (pickTarget as? EntityPickTarget.Field)?.domains.orEmpty(),
            onDismiss = { entityPickerFor = null },
        )
    }

    val actionKey = actionEditorFor
    if (actionKey != null) {
        CardActionEditor(
            label = cardFieldsFor(type).firstOrNull { it.key == actionKey }?.label ?: "ACTION",
            initial = actionFieldObject(fieldValues[actionKey]),
            onSave = { obj ->
                // JsonNull (not remove) so the build loop sees the key as LOADED
                // and drops it, instead of falling back to the base value (which
                // would silently undo a clear of a stored action).
                fieldValues[actionKey] = obj ?: JsonNull
                actionEditorFor = null
            },
            onDismiss = { actionEditorFor = null },
        )
    }

    val bespoke = bespokeEditorFor
    if (bespoke != null) {
        val onSaveValue: (JsonElement?) -> Unit = { value ->
            // JsonNull clears (same loaded-but-unset contract as the action editor).
            fieldValues[bespoke.key] = value ?: JsonNull
            bespokeEditorFor = null
        }
        val onDismiss = { bespokeEditorFor = null }
        when (bespoke.kind) {
            BespokeKind.FEATURES -> CardFeaturesEditor(
                initial = fieldValues[bespoke.key],
                onSave = onSaveValue,
                onDismiss = onDismiss,
            )
            BespokeKind.SEVERITY -> GaugeSeverityEditor(
                initial = fieldValues[bespoke.key],
                onSave = onSaveValue,
                onDismiss = onDismiss,
            )
            BespokeKind.SEGMENTS -> GaugeSegmentsEditor(
                initial = fieldValues[bespoke.key],
                onSave = onSaveValue,
                onDismiss = onDismiss,
            )
        }
    }
}

private sealed interface EntityPickTarget {
    object Single : EntityPickTarget
    object Multi : EntityPickTarget

    /** Bind the picked entity into a generic schema field modelled as an
     *  [EntityFieldSpec]; [domains] scopes the picker (e.g. camera for a
     *  camera_image field). */
    data class Field(val key: String, val domains: List<String> = emptyList()) : EntityPickTarget
}

private fun JsonObject?.str(key: String): String =
    (this?.get(key) as? JsonPrimitive)?.content.orEmpty()

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
    /** When non-empty, only entities whose domain is in this set are listed (e.g.
     *  ["camera"] for a camera_image field). Empty = all entities. */
    domains: List<String> = emptyList(),
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
                val domainScoped = if (domains.isEmpty()) entities else entities.filter {
                    it.id.value.substringBefore('.') in domains
                }
                val filtered = if (q.isBlank()) domainScoped else domainScoped.filter {
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

/** secondary_info values the entities renderer understands (plus NONE). */
private val SECONDARY_INFO_OPTIONS = listOf(
    null, "entity-id", "area", "state", "last-changed", "last-updated",
    "last-triggered", "position", "tilt-position", "brightness",
)

/** Per-row native sub-element editor for entities/glance rows: a secondary_info
 *  selector (entities only) plus tri-state (AUTO / ON / OFF) chips driven by
 *  rowTogglesFor. */
@Composable
private fun RowOptionsEditor(
    type: String,
    row: CardEntityRow,
    onChange: (CardEntityRow) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp, bottom = 6.dp)) {
        if (type == "entities") {
            Text(text = "SECONDARY INFO", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                SECONDARY_INFO_OPTIONS.forEach { opt ->
                    EditorToggleChip(
                        label = (opt ?: "none").uppercase().replace('-', ' '),
                        selected = row.secondaryInfo == opt,
                        onClick = { onChange(row.copy(secondaryInfo = opt)) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Text(text = "ROW SHOW", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(4.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            rowTogglesFor(type).forEach { t ->
                val current: Boolean? = when (t.key) {
                    "show_state" -> row.showState
                    "state_color" -> row.stateColor
                    "show_last_changed" -> row.showLastChanged
                    else -> null
                }
                val suffix = when (current) {
                    null -> "AUTO"
                    true -> "ON"
                    false -> "OFF"
                }
                EditorToggleChip(
                    label = "${t.label}: $suffix",
                    selected = current != null,
                    onClick = {
                        val next = triStateNext(current)
                        onChange(
                            when (t.key) {
                                "show_state" -> row.copy(showState = next)
                                "state_color" -> row.copy(stateColor = next)
                                "show_last_changed" -> row.copy(showLastChanged = next)
                                else -> row
                            },
                        )
                    },
                )
            }
        }
    }
}

/**
 * Generic schema-field section of the structured editor: renders every field of
 * [cardFieldsFor] grouped under its [FieldSection], reading/writing the live
 * [values] map (real config key -> raw value). Bool/enum/colour/action each get a
 * fit-for-purpose control; together with the hand-rendered primaries and the SHOW
 * toggles this is the full visual configurator for the card type.
 */
@Composable
private fun CardFieldsSection(
    type: String,
    values: androidx.compose.runtime.snapshots.SnapshotStateMap<String, JsonElement>,
    onPickEntity: (EntityFieldSpec) -> Unit,
    onEditAction: (String) -> Unit,
    onEditBespoke: (BespokeFieldSpec) -> Unit,
) {
    val fields = cardFieldsFor(type)
    if (fields.isEmpty()) return
    for (section in FIELD_SECTION_ORDER) {
        val inSection = fields.filter { it.section == section }
        if (inSection.isEmpty()) continue
        Spacer(Modifier.height(12.dp))
        Text(text = section, style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(6.dp))
        inSection.forEach { field ->
            CardFieldControl(field, values, onPickEntity, onEditAction, onEditBespoke)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun CardFieldControl(
    field: CardField,
    values: androidx.compose.runtime.snapshots.SnapshotStateMap<String, JsonElement>,
    onPickEntity: (EntityFieldSpec) -> Unit,
    onEditAction: (String) -> Unit,
    onEditBespoke: (BespokeFieldSpec) -> Unit,
) {
    val raw = values[field.key]
    when (field) {
        is TextFieldSpec -> EditorField(
            label = field.label,
            value = stringFieldText(raw),
            onChange = { values[field.key] = JsonPrimitive(it) },
            monospace = field.monospace,
        )
        is IconFieldSpec -> EditorField(
            label = field.label,
            value = stringFieldText(raw),
            onChange = { values[field.key] = JsonPrimitive(it) },
            monospace = true,
        )
        is ListFieldSpec -> EditorField(
            label = field.label,
            value = listFieldText(raw),
            onChange = { values[field.key] = JsonPrimitive(it) },
            monospace = true,
        )
        is NumberFieldSpec -> EditorField(
            label = field.label,
            value = numberFieldText(raw),
            onChange = { values[field.key] = JsonPrimitive(it) },
            monospace = true,
        )
        is BoolFieldSpec -> {
            val cur = boolFieldValue(raw, field.default)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = field.label,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                EditorToggleChip(
                    label = if (cur) "ON" else "OFF",
                    selected = cur,
                    onClick = { values[field.key] = JsonPrimitive(!cur) },
                )
            }
        }
        is EnumFieldSpec -> {
            val selected = enumFieldValue(raw, field.default)
            Text(text = field.label, style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(4.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                field.options.forEach { opt ->
                    val isSel = selected == opt.value
                    EditorToggleChip(
                        label = opt.label,
                        selected = isSel,
                        // Re-tapping the selected chip clears the key (JsonNull so
                        // the build loop drops it) when the field allows unset, so
                        // an enum can return to the card's own default in-form.
                        onClick = {
                            values[field.key] =
                                if (isSel && field.allowUnset) JsonNull else JsonPrimitive(opt.value)
                        },
                    )
                }
            }
        }
        is ColorFieldSpec -> ColorFieldControl(
            field = field,
            value = stringFieldText(raw),
            onChange = { values[field.key] = JsonPrimitive(it) },
        )
        is EntityFieldSpec -> {
            Text(text = field.label, style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(4.dp))
            EntityChip(entityId = stringFieldText(raw), onClick = { onPickEntity(field) })
        }
        is ActionFieldSpec -> EditSummaryRow(
            label = field.label,
            summary = actionSummary(actionFieldObject(raw)),
            onClick = { onEditAction(field.key) },
        )
        is BespokeFieldSpec -> EditSummaryRow(
            label = field.label,
            summary = bespokeSummary(field.kind, raw),
            onClick = { onEditBespoke(field) },
        )
    }
}

/** A tappable "label / current-value / EDIT ›" row, shared by the action and
 *  bespoke (features / severity / segments) sub-editors. */
@Composable
private fun EditSummaryRow(label: String, summary: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
                Text(
                    text = summary,
                    style = R1.body,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(text = "EDIT ›", style = R1.labelMicro, color = R1.AccentWarm)
        }
    }
}

/** One-line summary of a bespoke field's current value for its [EditSummaryRow]. */
private fun bespokeSummary(kind: BespokeKind, raw: JsonElement?): String = when (kind) {
    BespokeKind.FEATURES -> {
        val n = parseFeatureObjects(raw).size
        if (n == 0) "NONE" else "$n FEATURE${if (n == 1) "" else "S"}"
    }
    BespokeKind.SEVERITY -> {
        val (g, y, r) = parseSeverityText(raw)
        if (g.isBlank() && y.isBlank() && r.isBlank()) "NONE" else "G $g · Y $y · R $r"
    }
    BespokeKind.SEGMENTS -> {
        val n = parseSegmentRows(raw).size
        if (n == 0) "NONE" else "$n BAND${if (n == 1) "" else "S"}"
    }
}

/** Colour field: HA named-colour swatch quick-picks plus a free hex/name field. */
@Composable
private fun ColorFieldControl(
    field: ColorFieldSpec,
    value: String,
    onChange: (String) -> Unit,
) {
    Text(text = field.label, style = R1.labelMicro, color = R1.InkSoft)
    Spacer(Modifier.height(4.dp))
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        HA_NAMED_COLORS.forEach { (name, argb) ->
            val selected = value == name
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(androidx.compose.ui.graphics.Color(argb))
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) R1.AccentWarm else R1.Hairline,
                        androidx.compose.foundation.shape.CircleShape,
                    )
                    .r1Pressable(
                        onClick = { onChange(if (selected) "" else name) },
                        contentDescription = "Colour $name",
                    ),
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    com.github.itskenny0.r1ha.ui.components.R1TextField(
        value = value,
        onValueChange = onChange,
        placeholder = "name or #rrggbb",
        monospace = true,
    )
}

/** One-line summary of an action config for the [ActionFieldSpec] row. */
private fun actionSummary(obj: JsonObject?): String {
    if (obj == null) return "DEFAULT"
    val action = (obj["action"] as? JsonPrimitive)?.content ?: return "CUSTOM"
    fun s(key: String) = (obj[key] as? JsonPrimitive)?.content.orEmpty()
    return when (action) {
        "more-info" -> "MORE INFO"
        "toggle" -> "TOGGLE"
        "none" -> "NONE"
        "assist" -> "ASSIST"
        "navigate" -> "NAVIGATE → ${s("navigation_path")}"
        "url" -> "URL → ${s("url_path")}"
        "perform-action", "call-service" -> {
            val svc = (obj["perform_action"] ?: obj["service"]) as? JsonPrimitive
            "PERFORM ${svc?.content.orEmpty()}"
        }
        else -> action.uppercase()
    }
}

/** Action types offered by [CardActionEditor], in display order. The value is the
 *  HA `action:` key; "default" is the synthetic "no override" choice (removes the
 *  key so the card's domain-default gesture applies). */
private val ACTION_TYPE_OPTIONS = listOf(
    "default" to "DEFAULT",
    "more-info" to "MORE INFO",
    "toggle" to "TOGGLE",
    "navigate" to "NAVIGATE",
    "url" to "URL",
    "perform-action" to "PERFORM",
    "assist" to "ASSIST",
    "none" to "NONE",
)

/**
 * Bespoke editor for an HA action object (tap_action / hold_action / …). Mirrors
 * HA's action editor: an action-type selector plus the per-action parameters
 * (navigation path, url, perform-action + target + data, confirmation). Saves a
 * JSON object, or null to clear the key back to the card's default gesture.
 */
@Composable
private fun CardActionEditor(
    label: String,
    initial: JsonObject?,
    onSave: (JsonObject?) -> Unit,
    onDismiss: () -> Unit,
) {
    fun str(key: String) = (initial?.get(key) as? JsonPrimitive)?.content.orEmpty()
    val initialType = (initial?.get("action") as? JsonPrimitive)?.content
        ?.let { if (it == "call-service") "perform-action" else it }
        ?: "default"
    var actionType by remember { mutableStateOf(initialType) }
    var navPath by remember { mutableStateOf(str("navigation_path")) }
    var urlPath by remember { mutableStateOf(str("url_path")) }
    var perform by remember {
        mutableStateOf(
            ((initial?.get("perform_action") ?: initial?.get("service")) as? JsonPrimitive)?.content.orEmpty(),
        )
    }
    var targetEntity by remember {
        mutableStateOf(
            ((initial?.get("target") as? JsonObject)?.get("entity_id") as? JsonPrimitive)?.content.orEmpty(),
        )
    }
    var dataJson by remember {
        mutableStateOf(
            (initial?.get("data") as? JsonObject)
                ?.let { LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), it) }
                .orEmpty(),
        )
    }
    var confirm by remember { mutableStateOf(initial?.containsKey("confirmation") == true) }

    fun build(): JsonObject? {
        if (actionType == "default") return null
        val m = linkedMapOf<String, JsonElement>("action" to JsonPrimitive(actionType))
        when (actionType) {
            "navigate" -> if (navPath.isNotBlank()) m["navigation_path"] = JsonPrimitive(navPath)
            "url" -> if (urlPath.isNotBlank()) m["url_path"] = JsonPrimitive(urlPath)
            "perform-action" -> {
                if (perform.isNotBlank()) m["perform_action"] = JsonPrimitive(perform)
                if (targetEntity.isNotBlank()) {
                    m["target"] = JsonObject(mapOf("entity_id" to JsonPrimitive(targetEntity)))
                }
                val parsedData = runCatching {
                    if (dataJson.isBlank()) null else LOVELACE_EDIT_JSON.parseToJsonElement(dataJson) as? JsonObject
                }.getOrNull()
                parsedData?.let { m["data"] = it }
            }
        }
        if (confirm) m["confirmation"] = JsonPrimitive(true)
        return JsonObject(m)
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
                .padding(14.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(text = "ACTION · $label", style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(10.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ACTION_TYPE_OPTIONS.forEach { (value, lbl) ->
                    EditorToggleChip(
                        label = lbl,
                        selected = actionType == value,
                        onClick = { actionType = value },
                    )
                }
            }
            when (actionType) {
                "navigate" -> {
                    Spacer(Modifier.height(10.dp))
                    EditorField(label = "NAVIGATION PATH", value = navPath, onChange = { navPath = it }, monospace = true)
                }
                "url" -> {
                    Spacer(Modifier.height(10.dp))
                    EditorField(label = "URL", value = urlPath, onChange = { urlPath = it }, monospace = true)
                }
                "perform-action" -> {
                    Spacer(Modifier.height(10.dp))
                    EditorField(label = "ACTION (domain.service)", value = perform, onChange = { perform = it }, monospace = true)
                    Spacer(Modifier.height(8.dp))
                    EditorField(label = "TARGET ENTITY", value = targetEntity, onChange = { targetEntity = it }, monospace = true)
                    Spacer(Modifier.height(8.dp))
                    Text(text = "DATA (JSON)", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    com.github.itskenny0.r1ha.ui.components.R1TextField(
                        value = dataJson,
                        onValueChange = { dataJson = it },
                        placeholder = "{ \"brightness\": 128 }",
                        monospace = true,
                    )
                }
            }
            if (actionType != "default") {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "CONFIRMATION", style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.weight(1f))
                    EditorToggleChip(
                        label = if (confirm) "ON" else "OFF",
                        selected = confirm,
                        onClick = { confirm = !confirm },
                    )
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton(label = "CANCEL", accent = false, onClick = onDismiss)
                Spacer(Modifier.width(8.dp))
                SheetButton(label = "SAVE", accent = true, onClick = { onSave(build()) })
            }
        }
    }
}

/** Shared modal scaffold for the bespoke sub-editors (features / severity /
 *  segments): the dim backdrop, centred surface, title and CANCEL/SAVE row. */
@Composable
private fun BespokeEditorScaffold(
    title: String,
    onCancel: () -> Unit,
    onSave: () -> Unit,
    body: @Composable () -> Unit,
) {
    androidx.activity.compose.BackHandler(onBack = onCancel)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg.copy(alpha = 0.96f))
            .r1Pressable(onClick = onCancel, hapticOnClick = false)
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
            Text(text = title, style = R1.sectionHeader, color = R1.AccentWarm)
            Spacer(Modifier.height(10.dp))
            body()
            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                SheetButton(label = "CANCEL", accent = false, onClick = onCancel)
                Spacer(Modifier.width(8.dp))
                SheetButton(label = "SAVE", accent = true, onClick = onSave)
            }
        }
    }
}

/**
 * Editor for a card's `features:` array. Lists the configured features, lets the
 * user add (from the catalogue), remove, reorder, and edit each feature's full
 * options as JSON (so every HA feature option is reachable), then writes the
 * array back. Saving an empty list clears the key.
 */
@Composable
private fun CardFeaturesEditor(
    initial: JsonElement?,
    onSave: (JsonElement?) -> Unit,
    onDismiss: () -> Unit,
) {
    val features = remember {
        androidx.compose.runtime.mutableStateListOf<JsonObject>().apply {
            addAll(parseFeatureObjects(initial))
        }
    }
    var addOpen by remember { mutableStateOf(false) }
    var expanded by remember { mutableStateOf(-1) }

    BespokeEditorScaffold(
        title = "FEATURES",
        onCancel = onDismiss,
        onSave = { onSave(buildFeaturesArray(features.toList())) },
    ) {
        features.forEachIndexed { idx, feat ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(R1.ShapeS)
                            .r1Pressable(onClick = { expanded = if (expanded == idx) -1 else idx })
                            .padding(vertical = 4.dp),
                    ) {
                        Text(text = featureRowLabel(feat), style = R1.body, color = R1.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    // Reorder up/down + remove.
                    if (idx > 0) {
                        Box(
                            modifier = Modifier.clip(R1.ShapeS)
                                .r1Pressable(onClick = {
                                    val tmp = features[idx - 1]; features[idx - 1] = features[idx]; features[idx] = tmp
                                    expanded = -1
                                })
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) { Text("↑", style = R1.body, color = R1.InkSoft) }
                    }
                    if (idx < features.lastIndex) {
                        Box(
                            modifier = Modifier.clip(R1.ShapeS)
                                .r1Pressable(onClick = {
                                    val tmp = features[idx + 1]; features[idx + 1] = features[idx]; features[idx] = tmp
                                    expanded = -1
                                })
                                .padding(horizontal = 6.dp, vertical = 6.dp),
                        ) { Text("↓", style = R1.body, color = R1.InkSoft) }
                    }
                    Box(
                        modifier = Modifier.clip(R1.ShapeS)
                            .r1Pressable(onClick = {
                                features.removeAt(idx)
                                expanded = -1
                            })
                            .padding(horizontal = 8.dp, vertical = 6.dp),
                    ) { Text("✕", style = R1.labelMicro, color = R1.StatusRed) }
                }
                if (expanded == idx) {
                    // One text representation of this feature, keyed on idx (NOT on
                    // the feature object) so it never resets mid-keystroke. Both the
                    // friendly list field and the raw editor read/write through it;
                    // features[idx] mirrors the last valid parse. This keeps the two
                    // controls in sync and the cursor stable while typing.
                    var text by remember(idx) {
                        mutableStateOf(LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), feat))
                    }
                    val parsedFeat = runCatching { LOVELACE_EDIT_JSON.parseToJsonElement(text) as? JsonObject }.getOrNull()
                    val featType = (parsedFeat?.get("type") as? JsonPrimitive)?.content
                        ?: (feat["type"] as? JsonPrimitive)?.content.orEmpty()
                    val listKey = featureListKey(featType)
                    // Friendly comma field for the common list-option features
                    // (mode pickers, command rows, select options, media controls);
                    // the raw-JSON field below still exposes every other option.
                    if (listKey != null && parsedFeat != null) {
                        Spacer(Modifier.height(4.dp))
                        EditorField(
                            label = listKey.uppercase().replace('_', ' '),
                            value = featureListText(parsedFeat, listKey),
                            onChange = {
                                val newFeat = setFeatureList(parsedFeat, listKey, it)
                                text = LOVELACE_EDIT_JSON.encodeToString(JsonObject.serializer(), newFeat)
                                features[idx] = newFeat
                            },
                            monospace = true,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(text = "OPTIONS (JSON)", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    com.github.itskenny0.r1ha.ui.components.R1TextField(
                        value = text,
                        onValueChange = {
                            text = it
                            runCatching { LOVELACE_EDIT_JSON.parseToJsonElement(it) as? JsonObject }
                                .getOrNull()?.let { obj -> features[idx] = obj }
                        },
                        placeholder = "{ \"type\": \"...\" }",
                        monospace = true,
                        isError = parsedFeat == null,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        SheetButton(label = if (addOpen) "CLOSE CATALOGUE" else "+ ADD FEATURE", accent = true, onClick = { addOpen = !addOpen })
        if (addOpen) {
            Spacer(Modifier.height(6.dp))
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                FEATURE_CATALOG.forEach { (type, lbl) ->
                    EditorToggleChip(
                        label = lbl.uppercase(),
                        selected = false,
                        onClick = {
                            features.add(newFeatureObject(type))
                            addOpen = false
                            expanded = features.lastIndex
                        },
                    )
                }
            }
        }
    }
}

/** Editor for a gauge `severity:` object: green/yellow/red thresholds. Saving with
 *  every field blank clears the key (HA falls back to a single fill / segments). */
@Composable
private fun GaugeSeverityEditor(
    initial: JsonElement?,
    onSave: (JsonElement?) -> Unit,
    onDismiss: () -> Unit,
) {
    val (g0, y0, r0) = remember(initial) { parseSeverityText(initial) }
    var green by remember { mutableStateOf(g0) }
    var yellow by remember { mutableStateOf(y0) }
    var red by remember { mutableStateOf(r0) }
    BespokeEditorScaffold(
        title = "SEVERITY BANDS",
        onCancel = onDismiss,
        onSave = { onSave(buildSeverity(green, yellow, red)) },
    ) {
        Text(
            text = "Threshold where each colour starts. Leave all blank to clear.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(8.dp))
        EditorField(label = "GREEN FROM", value = green, onChange = { green = it }, monospace = true)
        Spacer(Modifier.height(8.dp))
        EditorField(label = "YELLOW FROM", value = yellow, onChange = { yellow = it }, monospace = true)
        Spacer(Modifier.height(8.dp))
        EditorField(label = "RED FROM", value = red, onChange = { red = it }, monospace = true)
    }
}

/** Editor for a gauge `segments:` array: ordered colour bands (from + colour +
 *  optional label). Saving with no valid rows clears the key. */
@Composable
private fun GaugeSegmentsEditor(
    initial: JsonElement?,
    onSave: (JsonElement?) -> Unit,
    onDismiss: () -> Unit,
) {
    val rows = remember {
        androidx.compose.runtime.mutableStateListOf<SegmentRow>().apply { addAll(parseSegmentRows(initial)) }
    }
    BespokeEditorScaffold(
        title = "SEGMENTS",
        onCancel = onDismiss,
        onSave = { onSave(buildSegments(rows.toList())) },
    ) {
        rows.forEachIndexed { idx, row ->
            Column(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "BAND ${idx + 1}", style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.weight(1f))
                    Box(
                        modifier = Modifier.clip(R1.ShapeS)
                            .r1Pressable(onClick = { rows.removeAt(idx) })
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) { Text("✕", style = R1.labelMicro, color = R1.StatusRed) }
                }
                Spacer(Modifier.height(4.dp))
                EditorField(label = "FROM", value = row.from, onChange = { rows[idx] = row.copy(from = it) }, monospace = true)
                Spacer(Modifier.height(4.dp))
                EditorField(label = "COLOUR", value = row.color, onChange = { rows[idx] = row.copy(color = it) }, monospace = true)
                Spacer(Modifier.height(4.dp))
                EditorField(label = "LABEL (OPTIONAL)", value = row.label, onChange = { rows[idx] = row.copy(label = it) })
            }
        }
        SheetButton(label = "+ ADD BAND", accent = true, onClick = { rows.add(SegmentRow(from = "", color = "")) })
    }
}
