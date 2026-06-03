package com.github.itskenny0.r1ha.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.DisplayMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics

/**
 * Legacy top-level category enum kept for source compatibility with the app's
 * nav graph, which still registers a route per value and passes one in as
 * `currentCategory`. The Settings surface no longer drives its drill-in through
 * these (it owns an internal [SettingsBackStack] of [SettingsNode]s instead),
 * but a non-ROOT value seeds the back-stack at the matching node so any lingering
 * external entry point still lands the user on the right subpage. The standalone
 * feature screens (Sync, IoT Camera / Sensors, MQTT) remain their own nav routes
 * and are still reached via [onOpenCategory].
 */
enum class SettingsCategory {
    ROOT,
    CONNECTION,
    APPEARANCE,
    BEHAVIOUR,
    INTEGRATIONS,
    SYNC,
    IOT_CAMERA,
    IOT_SENSORS,
    MQTT,
    ADVANCED,
    BROWSE,
}

/** Seed the internal back-stack from the legacy [SettingsCategory] the nav graph
 *  hands in. Config categories map onto their [SettingsNode]; the standalone
 *  feature screens (SYNC / IOT_* / MQTT) have no in-tree node and fall back to
 *  ROOT (they're reached as their own nav routes, never rendered inline). */
private fun seedNodeFor(category: SettingsCategory): SettingsNode = when (category) {
    SettingsCategory.ROOT -> SettingsNode.ROOT
    SettingsCategory.CONNECTION -> SettingsNode.CONNECTION
    SettingsCategory.APPEARANCE -> SettingsNode.APPEARANCE
    SettingsCategory.BEHAVIOUR -> SettingsNode.BEHAVIOUR
    SettingsCategory.INTEGRATIONS -> SettingsNode.INTEGRATIONS
    SettingsCategory.ADVANCED -> SettingsNode.ADVANCED
    SettingsCategory.BROWSE -> SettingsNode.BROWSE
    SettingsCategory.SYNC,
    SettingsCategory.IOT_CAMERA,
    SettingsCategory.IOT_SENSORS,
    SettingsCategory.MQTT,
    -> SettingsNode.ROOT
}

/** Build the back-stack path from ROOT down to [node] so a deep seed restores a
 *  sane parent trail (a single Back lands on the parent, not all the way out). */
private fun pathTo(node: SettingsNode): List<SettingsNode> {
    val chain = ArrayDeque<SettingsNode>()
    var n: SettingsNode? = node
    while (n != null) {
        chain.addFirst(n)
        n = n.parent
    }
    return chain.toList()
}

@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    wheelInput: WheelInput,
    /** Legacy seed from the nav graph. ROOT opens the top-level category list;
     *  a non-ROOT value seeds the internal back-stack at that subpage. */
    currentCategory: SettingsCategory = SettingsCategory.ROOT,
    /** Used now only to reach the standalone feature screens that are their own
     *  nav routes: SYNC, IOT_CAMERA, IOT_SENSORS, MQTT. Config-category drill-in
     *  is handled internally and never calls this for those. */
    onOpenCategory: (SettingsCategory) -> Unit = {},
    onOpenThemePicker: () -> Unit,
    onOpenAbout: () -> Unit,
    onOpenDevMenu: () -> Unit = {},
    onOpenAssist: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenLogbook: () -> Unit,
    onOpenTemplate: () -> Unit,
    onOpenServiceCaller: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenCameras: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenPersons: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenLongLivedToken: () -> Unit,
    onOpenSystemHealth: () -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenAreas: () -> Unit,
    onOpenLabels: () -> Unit,
    onOpenFloors: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHelpers: () -> Unit,
    onOpenTodo: () -> Unit,
    onOpenUpdates: () -> Unit,
    onOpenRepairs: () -> Unit,
    onOpenMediaBrowse: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenZhaPairing: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenLovelace: () -> Unit,
    onOpenDevice: () -> Unit,
    onOpenModifiedSettings: () -> Unit,
    onOpenKeyBindings: () -> Unit = {},
    onOpenDevices: () -> Unit = {},
    onOpenIntegrations: () -> Unit = {},
    onOpenLogs: () -> Unit = {},
    onOpenUsers: () -> Unit = {},
    onOpenTags: () -> Unit = {},
    onOpenBlueprints: () -> Unit = {},
    onOpenStatistics: () -> Unit = {},
    onOpenDashboards: () -> Unit = {},
    onSignedOut: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(
        factory = SettingsViewModel.factory(settings = settings, tokens = tokens),
    )
    val s by vm.state.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

    // Internal drill-in back-stack. Seeded from the legacy nav-graph category so
    // an external deep link still lands on the right subpage; ordinarily the nav
    // graph passes ROOT and every deeper level is pushed in-process.
    var backStack by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(
            SettingsBackStack(pathTo(seedNodeFor(currentCategory))),
        )
    }
    val node = backStack.current
    val push: (SettingsNode) -> Unit = { backStack = backStack.push(it) }

    // Settings search against the registry. Non-blank query swaps the current
    // page body for a flat matched-entries list grouped by category; tapping a
    // result jumps the back-stack to that setting's home node. Lives at screen
    // scope so it survives node changes for as long as Settings is open.
    var settingsQuery by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }

    // One Back press: clear an in-progress search first, then pop one level; a
    // pop at ROOT exits Settings. Mirrors the on-screen chevron exactly.
    val popOne: () -> Unit = {
        if (settingsQuery.isNotBlank()) {
            settingsQuery = ""
        } else {
            when (val r = backStack.pop()) {
                is PopResult.Popped -> backStack = r.stack
                PopResult.Exit -> onBack()
            }
        }
    }
    BackHandler(onBack = popOne)

    // One LazyList state shared across nodes; reset to the top whenever the node
    // changes so each freshly-entered subpage reads top-down and the wheel scrolls
    // the page the user is actually looking at.
    val listState = rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(node) {
        runCatching { listState.scrollToItem(0) }
    }
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    val matchedEntries = androidx.compose.runtime.remember(settingsQuery) {
        com.github.itskenny0.r1ha.core.prefs.searchSettings(settingsQuery)
    }
    // Second search index: the standalone sub-screens that are their own nav
    // routes (MQTT, Sync, IoT Camera / Sensors, Security/TLS-mTLS, Key
    // bindings). These have no AppSettings-field registry entry and route via
    // [onOpenCategory] / dedicated openers rather than the in-tree back-stack,
    // so the registry-only search misses them. We match their keywords here
    // and surface them as a "Jump to" group above the field-level results.
    val settingsDestinations = androidx.compose.runtime.remember(onOpenCategory, onOpenKeyBindings, push) {
        listOf(
            SettingsDestination(
                title = "MQTT broker",
                subtitle = "Host, port, auth, TLS for the IoT modes",
                keywords = listOf("mqtt", "broker", "publish", "topic", "iot"),
                open = { onOpenCategory(SettingsCategory.MQTT) },
            ),
            SettingsDestination(
                title = "Sync",
                subtitle = "Mirror settings across devices via Home Assistant",
                keywords = listOf("sync", "mirror", "devices", "backup"),
                open = { onOpenCategory(SettingsCategory.SYNC) },
            ),
            SettingsDestination(
                title = "IoT Camera Mode",
                subtitle = "Stream the device camera to Home Assistant",
                keywords = listOf("iot", "camera", "stream", "mjpeg", "snapshot"),
                open = { onOpenCategory(SettingsCategory.IOT_CAMERA) },
            ),
            SettingsDestination(
                title = "IoT Sensors Mode",
                subtitle = "Expose device sensors and controls to Home Assistant",
                keywords = listOf("iot", "sensors", "sensor", "battery", "flashlight", "vibration"),
                open = { onOpenCategory(SettingsCategory.IOT_SENSORS) },
            ),
            SettingsDestination(
                title = "Security",
                subtitle = "TLS certificate pinning, mTLS client cert",
                keywords = listOf("security", "tls", "pin", "pinning", "mtls", "certificate", "cert", "keystore"),
                open = { push(SettingsNode.CONNECTION_SECURITY) },
            ),
            SettingsDestination(
                title = "Key bindings",
                subtitle = "Map hardware keys to in-app actions",
                keywords = listOf("key", "keys", "bindings", "binding", "hardware", "button", "shortcut"),
                open = onOpenKeyBindings,
            ),
        )
    }
    val matchedDestinations = androidx.compose.runtime.remember(settingsQuery, settingsDestinations) {
        searchSettingsDestinations(settingsQuery, settingsDestinations)
    }
    val modifiedCount = androidx.compose.runtime.remember(s) {
        com.github.itskenny0.r1ha.core.prefs.modifiedSettings(s).size
    }
    // Per-section modified count, keyed by the legacy section name, used to badge
    // the top-level category rows so the user can see where they've changed
    // things without drilling in.
    val sectionModifiedCount: Map<String, Int> =
        androidx.compose.runtime.remember(s) {
            com.github.itskenny0.r1ha.core.prefs.modifiedSettings(s)
                .groupingBy { sectionNameForCategory(it.category) }
                .eachCount()
        }
    fun groupBadge(vararg names: String): Int = names.sumOf { sectionModifiedCount[it] ?: 0 }

    // Drain a pending deep-link focus request from ModifiedSettingsScreen. Jump
    // the back-stack to the requested setting's home node (restoring its parent
    // trail) and clear any in-progress search so the target body is visible.
    androidx.compose.runtime.LaunchedEffect(Unit) {
        val pending = com.github.itskenny0.r1ha.core.util.SettingsFocusBus.consume()
        if (pending != null) {
            val path = focusPathForSection(pending)
            if (path.size > 1) {
                backStack = SettingsBackStack(path)
                settingsQuery = ""
            }
        }
    }

    // Quick-Settings-tile entity picker overlay flag. Lives at screen scope so
    // the picker renders above the page body.
    val tilePickerOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }

    // Featured spotlight catalogue for the ROOT list. Built only from features whose
    // deep-link callback is actually wired, so a tap always lands somewhere.
    val featuredCatalogue = androidx.compose.runtime.remember {
        listOf(
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Generic, "Dashboards", "Browse every native Lovelace dashboard", onOpenDashboards),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Power, "Energy", "Live power flow and consumption totals", onOpenEnergy),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Speaker, "Assist", "Talk to Home Assistant from the wheel", onOpenAssist),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Camera, "Cameras", "Snapshots and live streams at a glance", onOpenCameras),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Automation, "Automations", "Inspect, trigger and trace your rules", onOpenAutomations),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Sensor, "Statistics", "Long-term history for any sensor", onOpenStatistics),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Todo, "Logbook", "Recent activity across the whole home", onOpenLogbook),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Scene, "Scenes", "Fire a saved scene in one tap", onOpenScenes),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.Weather, "Weather", "Forecast and conditions for your zone", onOpenWeather),
            FeaturedItem(com.github.itskenny0.r1ha.ui.icons.R1IconSet.MediaPlayer, "Media", "Browse media sources and libraries", onOpenMediaBrowse),
        )
    }
    // Strict per-launch round-robin: advance the persisted rotation cursor by the
    // featured-group size exactly once per launch (the VM advance is idempotent and
    // outlives Settings recompositions / re-entries), then select that group. The
    // advance reads the cursor straight from the cold prefs flow inside the repo's
    // atomic update, so the shown group always matches the value just persisted.
    val resolvedRotation by vm.featuredRotationIndex.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(Unit) {
        vm.advanceFeaturedRotation(count = 3, catalogueSize = featuredCatalogue.size)
    }
    val featuredTrio = androidx.compose.runtime.remember(resolvedRotation, featuredCatalogue) {
        // Until the cursor resolves, show the first group so the section never flashes
        // empty; once resolved we select the rotated group.
        featuredSlice(featuredCatalogue, resolvedRotation ?: 0, count = 3)
    }

    // SAF launchers for backup export / import (Connection > Backup & restore).
    val pendingBackupBlob = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: android.net.Uri? ->
        val blob = pendingBackupBlob.value
        pendingBackupBlob.value = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open output stream")
            com.github.itskenny0.r1ha.core.util.Toaster.show("Backup saved")
        }.onFailure { t ->
            com.github.itskenny0.r1ha.core.util.R1Log.w("Settings.exportBackup", "write failed: ${t.message}")
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Backup save failed",
                fullText = "Couldn't write the backup file.\n\n${t.message ?: t.toString()}",
            )
        }
    }
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                stream.readBytes().toString(Charsets.UTF_8)
            } ?: error("couldn't open input stream")
        }.fold(
            onSuccess = { raw -> vm.importBackupBlob(raw) },
            onFailure = { t ->
                com.github.itskenny0.r1ha.core.util.R1Log.w("Settings.importBackup", "read failed: ${t.message}")
                com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                    shortText = "Backup read failed",
                    fullText = "Couldn't read the backup file.\n\n${t.message ?: t.toString()}",
                )
            },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        // Per-level top bar. The chevron / hardware Back both pop one level via
        // [popOne]; at ROOT that means leaving Settings.
        R1TopBar(title = node.title, onBack = popOne)

        // AdaptiveContent is a fill-size passthrough, so the centre + width cap is
        // applied here on the inner list: on tablet / desktop tiers the category
        // list, every drill-in body, the search results and the Featured grid stay
        // in a centred island (the tier's maxContentWidth) instead of stretching a
        // single setting row across a 1280 dp+ panel. On R1 / compact the cap is
        // Unspecified, so the list fills full-bleed exactly as before.
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            val listModifier = if (dimens.capsContentWidth) {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = dimens.maxContentWidth)
                    .align(Alignment.CenterHorizontally)
            } else {
                Modifier.fillMaxSize()
            }
            LazyColumn(state = listState, modifier = listModifier) {

                // Search bar sits at the top of every level so the user can jump
                // to any leaf from wherever they are.
                item("__search") {
                    SettingsSearchBar(
                        query = settingsQuery,
                        onQueryChange = { settingsQuery = it },
                    )
                }

                if (settingsQuery.isNotBlank()) {
                    settingsSearchResults(
                        query = settingsQuery,
                        matched = matchedEntries,
                        matchedDestinations = matchedDestinations,
                        current = s,
                        onJump = { entry ->
                            val path = focusPathForSection(sectionNameForCategory(entry.category))
                            settingsQuery = ""
                            if (path.size > 1) backStack = SettingsBackStack(path)
                        },
                        onOpenDestination = { dest ->
                            settingsQuery = ""
                            dest.open()
                        },
                    )
                    return@LazyColumn
                }

                when (node) {
                    SettingsNode.ROOT -> rootCategories(
                        s = s,
                        featured = featuredTrio,
                        groupBadge = ::groupBadge,
                        push = push,
                        onOpenCategory = onOpenCategory,
                        onOpenAbout = onOpenAbout,
                    )

                    // ── Connection & server ───────────────────────────────
                    SettingsNode.CONNECTION -> connectionRoot(
                        s = s,
                        vm = vm,
                        haRepository = haRepository,
                        push = push,
                        groupBadge = ::groupBadge,
                    )
                    SettingsNode.CONNECTION_ACCOUNT -> connectionAccount(
                        s = s,
                        vm = vm,
                        haRepository = haRepository,
                        context = context,
                        onOpenLongLivedToken = onOpenLongLivedToken,
                        onSignedOut = onSignedOut,
                    )
                    SettingsNode.CONNECTION_BACKUP -> connectionBackup(
                        vm = vm,
                        pendingBackupBlob = pendingBackupBlob,
                        exportLauncher = exportLauncher,
                        importLauncher = importLauncher,
                    )
                    SettingsNode.CONNECTION_SECURITY -> item { SecuritySection() }

                    // ── Appearance ────────────────────────────────────────
                    SettingsNode.APPEARANCE -> appearanceRoot(
                        s = s,
                        push = push,
                        groupBadge = ::groupBadge,
                    )
                    SettingsNode.APPEARANCE_THEME -> appearanceTheme(s = s, vm = vm, onOpenThemePicker = onOpenThemePicker)
                    SettingsNode.APPEARANCE_NAVPANEL -> appearanceNavPanel(s = s, vm = vm)
                    SettingsNode.APPEARANCE_CARDS -> appearanceCards(s = s, vm = vm, push = push)
                    SettingsNode.APPEARANCE_CARDS_VALUEBAR -> appearanceValueBar(s = s, vm = vm)
                    SettingsNode.APPEARANCE_CARDS_CHROME -> appearanceChrome(s = s, vm = vm)

                    // ── Input ─────────────────────────────────────────────
                    SettingsNode.INPUT -> inputRoot(s = s, push = push, onOpenKeyBindings = onOpenKeyBindings)
                    SettingsNode.INPUT_WHEEL -> inputWheel(s = s, vm = vm)

                    // ── Behaviour ─────────────────────────────────────────
                    SettingsNode.BEHAVIOUR -> behaviourRoot(s = s, vm = vm, push = push)
                    SettingsNode.BEHAVIOUR_QUICKTILES -> behaviourQuickTiles(
                        s = s,
                        vm = vm,
                        onPick = { tilePickerOpen.value = true },
                    )

                    // ── Today / Dashboard ─────────────────────────────────
                    SettingsNode.DASHBOARD -> dashboardRoot(
                        s = s,
                        push = push,
                        onOpenDashboard = onOpenDashboard,
                        onOpenSearch = onOpenSearch,
                        onOpenDashboards = onOpenDashboards,
                    )
                    SettingsNode.DASHBOARD_CARDS -> dashboardCards(s = s, vm = vm)
                    SettingsNode.DASHBOARD_THRESHOLDS -> dashboardThresholds(s = s, vm = vm)
                    SettingsNode.DASHBOARD_ORDER -> dashboardOrder(s = s, vm = vm)

                    // ── Integrations ──────────────────────────────────────
                    SettingsNode.INTEGRATIONS -> integrationsRoot(
                        s = s,
                        vm = vm,
                        push = push,
                        groupBadge = ::groupBadge,
                        onOpenCategory = onOpenCategory,
                    )
                    SettingsNode.INTEGRATIONS_REFRESH -> integrationsRefresh(s = s, vm = vm)
                    SettingsNode.INTEGRATIONS_CAMERAS -> integrationsCameras(s = s, vm = vm)
                    SettingsNode.INTEGRATIONS_DEFAULTS -> integrationsDefaults(s = s, vm = vm)

                    // ── Advanced ──────────────────────────────────────────
                    SettingsNode.ADVANCED -> advancedRoot(
                        s = s,
                        vm = vm,
                        modifiedCount = modifiedCount,
                        onOpenDevMenu = onOpenDevMenu,
                        onOpenModifiedSettings = onOpenModifiedSettings,
                        onOpenSystemHealth = onOpenSystemHealth,
                    )

                    // ── Browse ────────────────────────────────────────────
                    SettingsNode.BROWSE -> browseRoot(push = push)
                    SettingsNode.BROWSE_TODAY -> browseToday(onOpenDashboard, onOpenSearch)
                    SettingsNode.BROWSE_TALK -> browseTalk(
                        onOpenAssist, onOpenScenes, onOpenAutomations, onOpenHelpers, onOpenTodo,
                    )
                    SettingsNode.BROWSE_STATUS -> browseStatus(
                        onOpenCameras, onOpenWeather, onOpenPersons, onOpenCalendars, onOpenLogbook,
                        onOpenNotifications, onOpenAreas, onOpenLabels, onOpenFloors, onOpenZones,
                        onOpenEnergy, onOpenDevice,
                    )
                    SettingsNode.BROWSE_POWER -> browsePower(
                        haRepository = haRepository,
                        coroutineScope = coroutineScope,
                        onOpenUpdates = onOpenUpdates,
                        onOpenRepairs = onOpenRepairs,
                        onOpenMediaBrowse = onOpenMediaBrowse,
                        onOpenBackups = onOpenBackups,
                        onOpenZhaPairing = onOpenZhaPairing,
                        onOpenTemplate = onOpenTemplate,
                        onOpenServiceCaller = onOpenServiceCaller,
                        onOpenServices = onOpenServices,
                        onOpenSystemHealth = onOpenSystemHealth,
                        onOpenLovelace = onOpenLovelace,
                        onOpenDevices = onOpenDevices,
                        onOpenIntegrations = onOpenIntegrations,
                        onOpenBlueprints = onOpenBlueprints,
                        onOpenLogs = onOpenLogs,
                        onOpenUsers = onOpenUsers,
                        onOpenTags = onOpenTags,
                        onOpenStatistics = onOpenStatistics,
                    )
                }

                item("__tail_spacer") { Spacer(Modifier.height(48.dp)) }
            }
        }
    }

    if (tilePickerOpen.value) {
        EntityPickerSheet(
            haRepository = haRepository,
            onPick = { entityId ->
                vm.setQuickTileEntityId(entityId)
                tilePickerOpen.value = false
                com.github.itskenny0.r1ha.core.util.Toaster.show("Tile bound to $entityId")
            },
            onDismiss = { tilePickerOpen.value = false },
        )
    }
}

// ── Page bodies: one LazyListScope extension per node ──────────────────────────────────────
// Each renders the controls for one [SettingsNode]. Value-bearing category rows
// pass an Android-style secondary `value` so the current state shows without
// drilling in. Every control reuses the building-block composables further down
// the file; persistence routes through the exact same SettingsViewModel / repo
// fields as before, so this is purely a re-information-architecture.

private fun LazyListScope.rootCategories(
    s: AppSettings,
    featured: List<FeaturedItem>,
    groupBadge: (Array<out String>) -> Int,
    push: (SettingsNode) -> Unit,
    onOpenCategory: (SettingsCategory) -> Unit,
    onOpenAbout: () -> Unit,
) {
    featuredSection(featured)
    item { CategoryRow(node = SettingsNode.CONNECTION, summary = s.server?.url ?: "Not connected", badge = groupBadge(arrayOf("SERVER")), onClick = { push(SettingsNode.CONNECTION) }) }
    item { CategoryRow(node = SettingsNode.APPEARANCE, summary = "Theme: ${prettyEnumName(s.theme.name)}", badge = groupBadge(arrayOf("APPEARANCE", "CARD UI")), onClick = { push(SettingsNode.APPEARANCE) }) }
    item { CategoryRow(node = SettingsNode.INPUT, summary = "Wheel step: ${s.wheel.stepPercent}%", badge = groupBadge(arrayOf("SCROLL WHEEL")), onClick = { push(SettingsNode.INPUT) }) }
    item { CategoryRow(node = SettingsNode.BEHAVIOUR, summary = "Haptics, screen, tiles", badge = groupBadge(arrayOf("BEHAVIOUR")), onClick = { push(SettingsNode.BEHAVIOUR) }) }
    item { CategoryRow(node = SettingsNode.DASHBOARD, summary = "Cards, thresholds, tile order", badge = groupBadge(arrayOf("DASHBOARD")), onClick = { push(SettingsNode.DASHBOARD) }) }
    item { CategoryRow(node = SettingsNode.INTEGRATIONS, summary = "Refresh, cameras, IoT, sync", badge = groupBadge(arrayOf("INTEGRATIONS")), onClick = { push(SettingsNode.INTEGRATIONS) }) }
    item { CategoryRow(node = SettingsNode.ADVANCED, summary = "Dev menu, modified, reset", badge = 0, onClick = { push(SettingsNode.ADVANCED) }) }
    item { CategoryRow(node = SettingsNode.BROWSE, summary = "Dashboard, Assist, Scenes, tools", badge = 0, onClick = { push(SettingsNode.BROWSE) }) }
    item {
        R1Row(
            label = "About",
            description = "Version, source, file a bug",
            onClick = onOpenAbout,
            showChevron = true,
            contentDescription = "Open About",
            leadingContent = {
                androidx.compose.material3.Icon(
                    imageVector = com.github.itskenny0.r1ha.ui.icons.R1IconSet.Generic,
                    contentDescription = null,
                    tint = R1.InkSoft,
                    modifier = Modifier.size(22.dp),
                )
            },
        )
    }
}

// ── Connection & server ────────────────────────────────────────────────────────────────────

private fun LazyListScope.connectionRoot(
    s: AppSettings,
    vm: SettingsViewModel,
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    push: (SettingsNode) -> Unit,
    groupBadge: (Array<out String>) -> Int,
) {
    item { InfoRow(label = "URL", value = s.server?.url ?: "(not connected)", mono = true) }
    item { s.server?.haVersion?.let { InfoRow(label = "HA version", value = it, mono = true) } }
    item {
        val conn by haRepository.connection.collectAsStateWithLifecycle()
        val label = when (val c = conn) {
            is com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected -> "Connected"
            com.github.itskenny0.r1ha.core.ha.ConnectionState.Idle -> "Idle"
            com.github.itskenny0.r1ha.core.ha.ConnectionState.Connecting -> "Connecting…"
            com.github.itskenny0.r1ha.core.ha.ConnectionState.Authenticating -> "Authenticating…"
            is com.github.itskenny0.r1ha.core.ha.ConnectionState.Disconnected -> "Disconnected (attempt ${c.attempt})"
            is com.github.itskenny0.r1ha.core.ha.ConnectionState.AuthLost -> "Auth lost · sign in again"
        }
        InfoRow(label = "Status", value = label)
    }
    item {
        InfoRow(
            label = "App version",
            value = "${com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME} (${com.github.itskenny0.r1ha.BuildConfig.VERSION_CODE})",
            mono = true,
        )
    }
    item { SubGroupLabel("REQUEST LIMITING") }
    item {
        SwitchRow(
            label = "Strict connection mode",
            subtitle = "Limits how many requests the app sends to Home Assistant and how hard it " +
                "retries after an error. Turn this on if your Home Assistant bans devices after a " +
                "few failed logins. Some surfaces (camera snapshots, dashboards, live data) update " +
                "more slowly while this is on.",
            checked = s.connection.strictMode,
            onCheckedChange = { on -> vm.updateConnection { it.copy(strictMode = on) } },
        )
    }
    if (s.connection.strictMode) {
        item {
            NumberStepperRow(
                label = "Max simultaneous requests",
                subtitle = "Lower means fewer failed logins can reach HA in a single burst. 1 is safest.",
                value = s.connection.maxConcurrentRequests,
                min = 1, max = 4, step = 1,
                onChange = { v -> vm.updateConnection { it.copy(maxConcurrentRequests = v) } },
            )
        }
        item {
            NumberStepperRow(
                label = "Trip after failed requests",
                subtitle = "How many auth failures before the app stops sending requests and backs off.",
                value = s.connection.breakerFailureThreshold,
                min = 1, max = 5, step = 1,
                onChange = { v -> vm.updateConnection { it.copy(breakerFailureThreshold = v) } },
            )
        }
        item {
            NumberStepperRow(
                label = "Cooldown after tripping",
                subtitle = "How long the app waits before testing the connection again. Grows on repeat failures.",
                value = s.connection.breakerCooldownSec,
                min = 5, max = 300, step = 5, suffix = " s",
                onChange = { v -> vm.updateConnection { it.copy(breakerCooldownSec = v) } },
            )
        }
        item {
            NumberStepperRow(
                label = "Max retries before pausing",
                subtitle = "Sign-in recovery attempts before the app waits for a manual retry.",
                value = s.connection.maxAuthRetries,
                min = 1, max = 5, step = 1,
                onChange = { v -> vm.updateConnection { it.copy(maxAuthRetries = v) } },
            )
        }
        item {
            NumberStepperRow(
                label = "Minimum camera refresh",
                subtitle = "Floor on camera snapshot polling. Higher means fewer camera requests. 0 keeps each camera's own setting.",
                value = s.connection.minCameraRefreshSec,
                min = 0, max = 120, step = 5, suffix = " s",
                onChange = { v -> vm.updateConnection { it.copy(minCameraRefreshSec = v) } },
            )
        }
        item {
            NumberStepperRow(
                label = "Slow background refresh",
                subtitle = "Multiplies the auto-refresh interval of background surfaces. 2 polls half as often.",
                value = s.connection.backgroundRefreshMultiplier,
                min = 1, max = 6, step = 1, suffix = "×",
                onChange = { v -> vm.updateConnection { it.copy(backgroundRefreshMultiplier = v) } },
            )
        }
    }
    item { SubGroupLabel("MANAGE") }
    item {
        R1Row(
            label = "Account & sign-in",
            description = "Long-lived token, reconnect, sign out",
            onClick = { push(SettingsNode.CONNECTION_ACCOUNT) },
            showChevron = true,
            contentDescription = "Open Account & sign-in",
        )
    }
    item {
        R1Row(
            label = SettingsNode.CONNECTION_BACKUP.title,
            description = "Export / import settings, reset to defaults",
            onClick = { push(SettingsNode.CONNECTION_BACKUP) },
            showChevron = true,
            contentDescription = "Open Backup & restore",
        )
    }
    item {
        R1Row(
            label = SettingsNode.CONNECTION_SECURITY.title,
            description = "TLS certificate pinning, mTLS client cert",
            onClick = { push(SettingsNode.CONNECTION_SECURITY) },
            showChevron = true,
            contentDescription = "Open Security",
        )
    }
}

private fun LazyListScope.connectionAccount(
    s: AppSettings,
    vm: SettingsViewModel,
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    context: android.content.Context,
    onOpenLongLivedToken: () -> Unit,
    onSignedOut: () -> Unit,
) {
    item {
        NavRow(label = "Use long-lived token", value = "Paste instead of OAuth", onClick = onOpenLongLivedToken)
    }
    item {
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "RECONNECT NOW",
                onClick = {
                    haRepository.reconnectNow()
                    com.github.itskenny0.r1ha.core.util.Toaster.show("Reconnecting…")
                },
                modifier = Modifier.fillMaxWidth(),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        }
    }
    item {
        val url = s.server?.url
        if (url != null) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
                com.github.itskenny0.r1ha.ui.components.R1Button(
                    text = "OPEN HA WEB UI",
                    onClick = {
                        runCatching {
                            context.startActivity(
                                android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse(url),
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }.onFailure {
                            com.github.itskenny0.r1ha.core.util.Toaster.error("No browser to open $url")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                )
            }
        }
    }
    item {
        val armed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(armed.value) {
            if (armed.value) {
                kotlinx.coroutines.delay(3_000)
                armed.value = false
            }
        }
        Box(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.m)) {
            DangerButton(
                text = if (armed.value) "CONFIRM · SIGN OUT" else "SIGN OUT & RECONNECT",
                onClick = { if (armed.value) vm.signOut(onSignedOut) else armed.value = true },
            )
        }
    }
}

private fun LazyListScope.connectionBackup(
    vm: SettingsViewModel,
    pendingBackupBlob: androidx.compose.runtime.MutableState<String?>,
    exportLauncher: androidx.activity.result.ActivityResultLauncher<String>,
    importLauncher: androidx.activity.result.ActivityResultLauncher<Array<String>>,
) {
    item { InfoRow(label = "What's included", value = "Server URL · pages · favourites · all settings (no tokens)") }
    item {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.m),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "EXPORT",
                onClick = {
                    vm.exportBackupBlob { blob ->
                        pendingBackupBlob.value = blob
                        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date())
                        exportLauncher.launch("r1ha-backup-$stamp.json")
                    }
                },
                modifier = Modifier.weight(1f),
            )
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "IMPORT",
                onClick = { importLauncher.launch(arrayOf("application/json", "*/*")) },
                modifier = Modifier.weight(1f),
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        }
    }
    item {
        val armed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(armed.value) {
            if (armed.value) {
                kotlinx.coroutines.delay(3_000)
                armed.value = false
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = if (armed.value) "CONFIRM RESET · TAP AGAIN" else "RESET TO DEFAULTS",
                onClick = {
                    if (armed.value) {
                        vm.resetToDefaults()
                        armed.value = false
                    } else {
                        armed.value = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                accent = R1.StatusAmber,
            )
            if (armed.value) {
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = "Drops every override, theme, wheel + UI + behaviour preference. Keeps your account, favourites, and pages.",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}

// ── Appearance ─────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.appearanceRoot(
    s: AppSettings,
    push: (SettingsNode) -> Unit,
    groupBadge: (Array<out String>) -> Int,
) {
    item {
        CategorySubRow(
            node = SettingsNode.APPEARANCE_THEME,
            summary = if (s.autoThemeEnabled) "${prettyEnumName(s.theme.name)} · auto night" else prettyEnumName(s.theme.name),
            badge = groupBadge(arrayOf("APPEARANCE")),
            onClick = { push(SettingsNode.APPEARANCE_THEME) },
        )
    }
    item {
        CategorySubRow(
            node = SettingsNode.APPEARANCE_NAVPANEL,
            summary = navPanelSummary(s.navPanel),
            badge = groupBadge(arrayOf("APPEARANCE")),
            onClick = { push(SettingsNode.APPEARANCE_NAVPANEL) },
        )
    }
    item {
        CategorySubRow(
            node = SettingsNode.APPEARANCE_CARDS,
            summary = "Display mode: ${prettyEnumName(s.ui.displayMode.name)}",
            badge = groupBadge(arrayOf("CARD UI")),
            onClick = { push(SettingsNode.APPEARANCE_CARDS) },
        )
    }
}

/** One-line summary of the nav-panel settings for the Appearance sub-row. */
private fun navPanelSummary(navPanel: com.github.itskenny0.r1ha.core.prefs.NavPanelSettings): String =
    if (!navPanel.sidePanelEnabled) {
        "Off"
    } else {
        val hidden = navPanel.hiddenNavItems.count { it in com.github.itskenny0.r1ha.core.prefs.NavItemId.HIDEABLE }
        if (hidden == 0) "On" else "On · $hidden hidden"
    }

private fun LazyListScope.appearanceNavPanel(
    s: AppSettings,
    vm: SettingsViewModel,
) {
    val nav = s.navPanel
    item {
        SwitchRow(
            label = "Show side navigation panel",
            subtitle = "The rail / drawer on tablets and large screens. Off reverts large " +
                "screens to the card-stack layout (Settings stays on the chrome gear).",
            checked = nav.sidePanelEnabled,
            onCheckedChange = { v -> vm.updateNavPanel { it.copy(sidePanelEnabled = v) } },
        )
    }
    item { SubGroupLabel("VISIBLE ITEMS") }
    item {
        SwitchRow(
            label = "Show Today",
            subtitle = "The Today dashboard. Off removes it from the panel and stops it loading " +
                "(no background polling). Available even when the panel is off.",
            checked = com.github.itskenny0.r1ha.core.prefs.NavItemId.TODAY !in nav.hiddenNavItems,
            onCheckedChange = { v ->
                vm.updateNavPanel {
                    it.copy(hiddenNavItems = it.hiddenNavItems.toggleHidden(com.github.itskenny0.r1ha.core.prefs.NavItemId.TODAY, hidden = !v))
                }
            },
        )
    }
    item {
        SwitchRow(
            label = "Show Search",
            checked = com.github.itskenny0.r1ha.core.prefs.NavItemId.SEARCH !in nav.hiddenNavItems,
            enabled = nav.sidePanelEnabled,
            onCheckedChange = { v ->
                vm.updateNavPanel {
                    it.copy(hiddenNavItems = it.hiddenNavItems.toggleHidden(com.github.itskenny0.r1ha.core.prefs.NavItemId.SEARCH, hidden = !v))
                }
            },
        )
    }
    item {
        SwitchRow(
            label = "Show Assist",
            checked = com.github.itskenny0.r1ha.core.prefs.NavItemId.ASSIST !in nav.hiddenNavItems,
            enabled = nav.sidePanelEnabled,
            onCheckedChange = { v ->
                vm.updateNavPanel {
                    it.copy(hiddenNavItems = it.hiddenNavItems.toggleHidden(com.github.itskenny0.r1ha.core.prefs.NavItemId.ASSIST, hidden = !v))
                }
            },
        )
    }
}

/** Returns a copy of the set with [id] added when [hidden], removed otherwise. */
private fun Set<String>.toggleHidden(id: String, hidden: Boolean): Set<String> =
    if (hidden) this + id else this - id

private fun LazyListScope.appearanceTheme(
    s: AppSettings,
    vm: SettingsViewModel,
    onOpenThemePicker: () -> Unit,
) {
    item { NavRow(label = "Theme", value = prettyEnumName(s.theme.name), onClick = onOpenThemePicker) }
    item {
        SwitchRow(
            label = "Auto night theme",
            subtitle = "Swap themes inside the configured night window",
            checked = s.autoThemeEnabled,
            onCheckedChange = { vm.setAutoThemeEnabled(it) },
        )
    }
    if (s.autoThemeEnabled) {
        item {
            val nightThemeDialog = remember { mutableStateOf(false) }
            NavRow(label = "Night theme", value = prettyEnumName(s.nightTheme.name), onClick = { nightThemeDialog.value = true })
            if (nightThemeDialog.value) {
                NightThemePickerDialog(
                    current = s.nightTheme,
                    onPick = { vm.setNightTheme(it); nightThemeDialog.value = false },
                    onDismiss = { nightThemeDialog.value = false },
                )
            }
        }
        item {
            val hoursDialog = remember { mutableStateOf(false) }
            NavRow(label = "Night window", value = nightWindowSummary(s.nightStartHour, s.nightEndHour), onClick = { hoursDialog.value = true })
            if (hoursDialog.value) {
                NightHoursDialog(
                    startHour = s.nightStartHour,
                    endHour = s.nightEndHour,
                    onApply = { start, end -> vm.setNightWindow(start, end); hoursDialog.value = false },
                    onDismiss = { hoursDialog.value = false },
                )
            }
        }
    }
    item { CategoryResetRow(label = "RESET THEME", category = com.github.itskenny0.r1ha.core.prefs.SettingCategory.APPEARANCE, vm = vm) }
}

private fun LazyListScope.appearanceCards(
    s: AppSettings,
    vm: SettingsViewModel,
    push: (SettingsNode) -> Unit,
) {
    item {
        LabeledControl(label = "Display mode") {
            SegmentedEnumPicker(
                options = DisplayMode.entries,
                selected = s.ui.displayMode,
                label = { when (it) { DisplayMode.PERCENT -> "PERCENT"; DisplayMode.RAW -> "RAW" } },
                onSelect = { vm.setDisplayMode(it) },
            )
        }
    }
    item { SwitchRow(label = "Show on/off pill", checked = s.ui.showOnOffPill, onCheckedChange = { vm.setShowOnOffPill(it) }) }
    item { SwitchRow(label = "Show area label", checked = s.ui.showAreaLabel, onCheckedChange = { vm.setShowAreaLabel(it) }) }
    item {
        SwitchRow(
            label = "Hide card hint above current",
            subtitle = "Solid chrome backdrop covers the previous card's tail",
            checked = s.ui.hideCardTailAbove,
            onCheckedChange = { vm.setHideCardTailAbove(it) },
        )
    }
    item {
        SwitchRow(
            label = "Infinite scroll",
            subtitle = "Wheel past the last card wraps to the first",
            checked = s.ui.infiniteScroll,
            onCheckedChange = { vm.setInfiniteScroll(it) },
        )
    }
    item {
        SwitchRow(
            label = "Ultra-detail view",
            subtitle = "Offer the detailed more-info sheet on cards and tiles " +
                "(per-card override available in Customize)",
            checked = s.ui.moreInfoEnabledDefault,
            onCheckedChange = { vm.setMoreInfoEnabledDefault(it) },
        )
    }
    item {
        SwitchRow(
            label = "Show 0% arc when entity is off",
            subtitle = "Off (default): arc shows whatever brightness HA reported, " +
                "even if the entity is currently off. On: arc is always blank (0%) " +
                "for off entities. Useful for bulbs that store pre-off brightness " +
                "in HA so a dark bulb doesn't show 75% on its card.",
            checked = s.ui.showZeroPercentWhenOff,
            onCheckedChange = { vm.setShowZeroPercentWhenOff(it) },
        )
    }
    item {
        LabeledControl(label = "Peek deck") {
            SegmentedEnumPicker(
                options = com.github.itskenny0.r1ha.core.prefs.CardPeekMode.entries,
                selected = s.ui.cardPeekMode,
                label = { com.github.itskenny0.r1ha.core.prefs.cardPeekModeLabel(it) },
                onSelect = { vm.setCardPeekMode(it) },
            )
            Spacer(Modifier.height(R1.space.s))
            Text(
                text = "Half-height cards with the previous and next card peeking. " +
                    "Auto: phone-portrait only. Always: every device (turn on for R1 " +
                    "or small phones). Never: full-screen cards.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
    item {
        SliderRow(
            label = "Card stack scroll sensitivity",
            subtitle = "How far a flick coasts when scrolling the card stack. Higher = more " +
                "momentum, the deck glides further and faster. Lower = the deck brakes " +
                "sooner. The default (80%) matches the standard feel.",
            value = s.ui.cardScrollSensitivity,
            valueLabel = "${s.ui.cardScrollSensitivity}%",
            onChange = { vm.setCardScrollSensitivity(it) },
        )
    }
    item {
        LabeledControl(label = "Sensor decimals") {
            SegmentedIntPicker(
                options = listOf(0, 1, 2, 3, 4),
                selected = s.ui.maxDecimalPlaces,
                label = { if (it == 0) "INT" else "$it" },
                onSelect = { vm.setMaxDecimalPlaces(it) },
            )
        }
    }
    item {
        LabeledControl(label = "Temperature unit") {
            SegmentedEnumPicker(
                options = com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.entries,
                selected = s.ui.tempUnit,
                label = {
                    when (it) {
                        com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.AUTO -> "AUTO"
                        com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.CELSIUS -> "°C"
                        com.github.itskenny0.r1ha.core.prefs.TemperatureUnit.FAHRENHEIT -> "°F"
                    }
                },
                onSelect = { vm.setTempUnit(it) },
            )
        }
    }
    item { SubGroupLabel("LAYOUT") }
    item {
        R1Row(
            label = SettingsNode.APPEARANCE_CARDS_VALUEBAR.title,
            description = "Value bar location, position pip",
            value = "${com.github.itskenny0.r1ha.core.prefs.valueBarLocationLabel(s.ui.valueBarLocation)} · ${com.github.itskenny0.r1ha.core.prefs.positionDotLocationLabel(s.ui.positionDotLocation)}",
            onClick = { push(SettingsNode.APPEARANCE_CARDS_VALUEBAR) },
            showChevron = true,
            contentDescription = "Open Value bar & pip",
        )
    }
    item {
        R1Row(
            label = SettingsNode.APPEARANCE_CARDS_CHROME.title,
            description = "Reorder + toggle the card-stack chrome buttons",
            onClick = { push(SettingsNode.APPEARANCE_CARDS_CHROME) },
            showChevron = true,
            contentDescription = "Open Chrome buttons",
        )
    }
    item { CategoryResetRow(label = "RESET CARD UI", category = com.github.itskenny0.r1ha.core.prefs.SettingCategory.CARD_UI, vm = vm) }
}

private fun LazyListScope.appearanceValueBar(s: AppSettings, vm: SettingsViewModel) {
    item {
        LabeledControl(label = "Position pip location") {
            PositionDotLocationPicker(selected = s.ui.positionDotLocation, onSelect = { vm.setPositionDotLocation(it) })
        }
    }
    item {
        LabeledControl(label = "Value bar location") {
            ValueBarLocationPicker(selected = s.ui.valueBarLocation, onSelect = { vm.setValueBarLocation(it) })
        }
    }
}

private fun LazyListScope.appearanceChrome(s: AppSettings, vm: SettingsViewModel) {
    item {
        Text(
            text = "Chrome buttons",
            style = R1.bodyEmph,
            color = R1.Ink,
            modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 10.dp),
        )
        Text(
            text = "Reorder with ↑ / ↓ chips and toggle visibility per button. Right cluster of the card-stack chrome.",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = R1.space.xl),
        )
        ChromeButtonsPreview(buttons = s.ui.chromeButtons)
    }
    itemsIndexed(s.ui.chromeButtons, key = { _, c -> c.ref.name }) { idx, cfg ->
        ChromeButtonRow(
            position = idx + 1,
            config = cfg,
            isFirst = idx == 0,
            isLast = idx == s.ui.chromeButtons.lastIndex,
            onMoveUp = { vm.moveChromeButton(idx, idx - 1) },
            onMoveDown = { vm.moveChromeButton(idx, idx + 1) },
            onToggle = { vm.setChromeButtonEnabled(cfg.ref, it) },
        )
    }
}

// ── Input ──────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.inputRoot(
    s: AppSettings,
    push: (SettingsNode) -> Unit,
    onOpenKeyBindings: () -> Unit,
) {
    item {
        R1Row(
            label = SettingsNode.INPUT_WHEEL.title,
            description = "Step size, acceleration, invert",
            value = "${s.wheel.stepPercent}%",
            onClick = { push(SettingsNode.INPUT_WHEEL) },
            showChevron = true,
            contentDescription = "Open Scroll wheel",
        )
    }
    item {
        val customCount = s.keyBindings.count { (name, list) ->
            val action = runCatching { com.github.itskenny0.r1ha.core.input.KeyAction.valueOf(name) }.getOrNull()
            val default = com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS[action].orEmpty()
            list != default
        }
        NavRow(
            label = "Key bindings",
            value = if (customCount == 0) "Default mapping" else "$customCount custom",
            onClick = onOpenKeyBindings,
        )
    }
}

private fun LazyListScope.inputWheel(s: AppSettings, vm: SettingsViewModel) {
    item {
        LabeledControl(label = "Step size") {
            SegmentedIntPicker(
                options = listOf(1, 2, 5, 10),
                selected = s.wheel.stepPercent,
                label = { "$it%" },
                onSelect = { vm.setWheelStep(it) },
            )
        }
    }
    item {
        SwitchRow(
            label = "Acceleration",
            subtitle = "Spin faster to jump further",
            checked = s.wheel.acceleration,
            onCheckedChange = { vm.setWheelAcceleration(it) },
        )
    }
    if (s.wheel.acceleration) {
        item {
            LabeledControl(label = "Acceleration curve") {
                SegmentedEnumPicker(
                    options = com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.entries,
                    selected = s.wheel.accelerationCurve,
                    label = {
                        when (it) {
                            com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.SUBTLE -> "SUBTLE"
                            com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.MEDIUM -> "MEDIUM"
                            com.github.itskenny0.r1ha.core.prefs.AccelerationCurve.AGGRESSIVE -> "AGGRESSIVE"
                        }
                    },
                    onSelect = { vm.setAccelerationCurve(it) },
                )
            }
        }
    }
    item {
        SwitchRow(
            label = "Invert direction",
            checked = s.wheel.invertDirection,
            onCheckedChange = { vm.setWheelInvert(it) },
        )
    }
    item { CategoryResetRow(label = "RESET WHEEL", category = com.github.itskenny0.r1ha.core.prefs.SettingCategory.INPUT, vm = vm) }
}

// ── Behaviour ──────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.behaviourRoot(
    s: AppSettings,
    vm: SettingsViewModel,
    push: (SettingsNode) -> Unit,
) {
    item { SwitchRow(label = "Haptic feedback", checked = s.behavior.haptics, onCheckedChange = { vm.setHaptics(it) }) }
    item { SwitchRow(label = "Keep screen on", checked = s.behavior.keepScreenOn, onCheckedChange = { vm.setKeepScreenOn(it) }) }
    item {
        SwitchRow(
            label = "Tap to toggle",
            subtitle = "Off (default): the whole-card tap is inert so a miss " +
                "while aiming for the chrome buttons doesn't accidentally turn " +
                "the entity on. On: tap anywhere on the card to flip it.",
            checked = s.behavior.tapToToggle,
            onCheckedChange = { vm.setTapToToggle(it) },
        )
    }
    item {
        SwitchRow(
            label = "Hide status bar",
            subtitle = "Swipe down to peek the bar; auto-hides after release",
            checked = s.behavior.hideStatusBar,
            onCheckedChange = { vm.setHideStatusBar(it) },
        )
    }
    if (s.behavior.hideStatusBar) {
        item {
            SwitchRow(
                label = "↳ Show battery indicator",
                subtitle = "Tiny percent pill on the right of the chrome row " +
                    "(polled every 30 s). Useful so a low R1 battery doesn't catch you off-guard.",
                checked = s.behavior.showBatteryWhenStatusBarHidden,
                onCheckedChange = { vm.setShowBatteryWhenStatusBarHidden(it) },
            )
        }
    }
    item {
        SwitchRow(
            label = "Start on Dashboard",
            subtitle = "Open the app on the TODAY dashboard instead of the card stack. " +
                "Useful for wall-mounted / kiosk R1s. Takes effect on next app launch.",
            checked = s.behavior.startOnDashboard,
            onCheckedChange = { vm.setStartOnDashboard(it) },
        )
    }
    item {
        SwitchRow(
            label = "Wheel toggles switches",
            subtitle = "On (default): wheel-up turns locks, covers, vacuums, plain " +
                "switches on; wheel-down turns them off. Off: wheel does nothing on " +
                "those cards; useful if a casual brush is accidentally relocking your door.",
            checked = s.behavior.wheelTogglesSwitches,
            onCheckedChange = { vm.setWheelTogglesSwitches(it) },
        )
    }
    item {
        SwitchRow(
            label = "Assist · open keyboard on entry",
            subtitle = "Off (default): tapping into Assist shows the screen but " +
                "leaves the keyboard closed; useful on phones where the IME " +
                "popping up otherwise re-centers the empty state jarringly. " +
                "On: opening Assist focuses the input field immediately. " +
                "Voice input (🎤) works regardless of this setting.",
            checked = s.behavior.assistAutoOpenKeyboard,
            onCheckedChange = { vm.setAssistAutoOpenKeyboard(it) },
        )
    }
    item { ToastLogLevelRow(current = s.behavior.toastLogLevel, onSelect = { vm.setToastLogLevel(it) }) }
    item { OrientationModeRow(current = s.behavior.orientationMode, onSelect = { vm.setOrientationMode(it) }) }
    item {
        SwitchRow(
            label = "Guest mode (read-only)",
            subtitle = "When on, the app refuses every outbound service call. " +
                "Lights, locks, scripts: everything is blocked until you turn this off. " +
                "Hand the device to a guest without worrying they'll toggle something.",
            checked = s.guestModeEnabled,
            onCheckedChange = { vm.setGuestModeEnabled(it) },
        )
    }
    item { SubGroupLabel("MORE") }
    item {
        val bound = listOfNotNull(
            s.behavior.quickTileEntityId, s.behavior.quickTileEntityIdB,
            s.behavior.quickTileEntityIdC, s.behavior.quickTileEntityIdD,
        ).count { it.isNotBlank() }
        R1Row(
            label = SettingsNode.BEHAVIOUR_QUICKTILES.title,
            description = "Bind HA entities to notification-shade tiles",
            value = if (bound == 0) "None bound" else "$bound bound",
            onClick = { push(SettingsNode.BEHAVIOUR_QUICKTILES) },
            showChevron = true,
            contentDescription = "Open Quick Settings tiles",
        )
    }
    item { CategoryResetRow(label = "RESET BEHAVIOUR", category = com.github.itskenny0.r1ha.core.prefs.SettingCategory.BEHAVIOUR, vm = vm) }
}

private fun LazyListScope.behaviourQuickTiles(
    s: AppSettings,
    vm: SettingsViewModel,
    onPick: () -> Unit,
) {
    item {
        LabeledControl(label = "Quick Settings tile") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = s.behavior.quickTileEntityId ?: "",
                        onValueChange = { vm.setQuickTileEntityId(it) },
                        placeholder = "light.kitchen",
                        monospace = true,
                    )
                }
                Spacer(Modifier.width(R1.space.s))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = onPick)
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                ) {
                    Text(text = "PICK", style = R1.labelMicro, color = R1.AccentWarm)
                }
            }
            Spacer(Modifier.height(R1.space.s))
            Text(
                text = "After binding, pull down the notification shade twice → " +
                    "tap the pencil-edit icon → drag the 'HA Toggle' tile from " +
                    "the bottom row up to your active set.",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
    item {
        LabeledControl(label = "Quick Settings tile · slot B (HA Toggle 2)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = s.behavior.quickTileEntityIdB ?: "",
                        onValueChange = { vm.setQuickTileEntityIdB(it) },
                        placeholder = "switch.coffee_machine",
                        monospace = true,
                    )
                }
            }
        }
    }
    item {
        LabeledControl(label = "Quick Settings tile · slot C (HA Toggle 3)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = s.behavior.quickTileEntityIdC ?: "",
                        onValueChange = { vm.setQuickTileEntityIdC(it) },
                        placeholder = "script.goodnight",
                        monospace = true,
                    )
                }
            }
        }
    }
    item {
        LabeledControl(label = "Quick Settings tile · slot D (HA Toggle 4)") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = s.behavior.quickTileEntityIdD ?: "",
                        onValueChange = { vm.setQuickTileEntityIdD(it) },
                        placeholder = "scene.away",
                        monospace = true,
                    )
                }
            }
        }
    }
}

// ── Today / Dashboard ────────────────────────────────────────────────────────────────────

private fun LazyListScope.dashboardRoot(
    s: AppSettings,
    push: (SettingsNode) -> Unit,
    onOpenDashboard: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenDashboards: () -> Unit,
) {
    item { NavRow(label = "Open Dashboard", value = "Weather · People · Next event", onClick = onOpenDashboard) }
    item { NavRow(label = "Quick Search", value = "Find any entity", onClick = onOpenSearch) }
    // Native Lovelace dashboards renderer. Always shown so it stays reachable on
    // every device; on an R1-sized display the full-screen card grid is tight, so
    // tapping first asks for confirmation rather than hiding the entry (which also
    // wrongly hid it on phones reporting exactly 360 dp).
    item {
        val isR1Width = com.github.itskenny0.r1ha.ui.components.LocalWindowTier.current.widthDp <= 360
        val showDashboardsPrompt = androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(false)
        }
        NavRow(
            label = "Dashboards",
            value = "Native Lovelace renderer",
            onClick = { if (isR1Width) showDashboardsPrompt.value = true else onOpenDashboards() },
        )
        if (showDashboardsPrompt.value) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDashboardsPrompt.value = false },
                containerColor = R1.Bg,
                title = { Text(text = "DASHBOARDS", style = R1.sectionHeader, color = R1.Ink) },
                text = {
                    Text(
                        text = "This feature works better on larger screens. Do you want to continue?",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                },
                confirmButton = {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "CONTINUE",
                        onClick = {
                            showDashboardsPrompt.value = false
                            onOpenDashboards()
                        },
                    )
                },
                dismissButton = {
                    com.github.itskenny0.r1ha.ui.components.R1Button(
                        text = "CANCEL",
                        onClick = { showDashboardsPrompt.value = false },
                        variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
                    )
                },
            )
        }
    }
    item { SubGroupLabel("LAYOUT") }
    item {
        R1Row(
            label = SettingsNode.DASHBOARD_CARDS.title,
            description = "Toggle which dashboard cards appear",
            onClick = { push(SettingsNode.DASHBOARD_CARDS) },
            showChevron = true,
            contentDescription = "Open Visible cards",
        )
    }
    item {
        R1Row(
            label = SettingsNode.DASHBOARD_THRESHOLDS.title,
            description = "Refresh cadence, battery, power thresholds",
            value = if (s.dashboard.refreshIntervalSec == 0) "Pull-down" else "${s.dashboard.refreshIntervalSec}s",
            onClick = { push(SettingsNode.DASHBOARD_THRESHOLDS) },
            showChevron = true,
            contentDescription = "Open Thresholds & intervals",
        )
    }
    item {
        R1Row(
            label = SettingsNode.DASHBOARD_ORDER.title,
            description = "Reorder the dashboard tiles",
            onClick = { push(SettingsNode.DASHBOARD_ORDER) },
            showChevron = true,
            contentDescription = "Open Tile order",
        )
    }
}

private fun LazyListScope.dashboardCards(s: AppSettings, vm: SettingsViewModel) {
    item { SwitchRow(label = "Greeting", subtitle = "GOOD MORNING / AFTERNOON / EVENING / NIGHT row", checked = s.dashboard.showGreeting, onCheckedChange = { v -> vm.updateDashboard { it.copy(showGreeting = v) } }) }
    item { SwitchRow(label = "Weather card", subtitle = "Current condition + temperature from your first weather.* entity", checked = s.dashboard.showWeather, onCheckedChange = { v -> vm.updateDashboard { it.copy(showWeather = v) } }) }
    item { SwitchRow(label = "Sun card", subtitle = "Above/below horizon, elevation, next rise/set", checked = s.dashboard.showSun, onCheckedChange = { v -> vm.updateDashboard { it.copy(showSun = v) } }) }
    item { SwitchRow(label = "Timers", subtitle = "Active timer.* entities with remaining time", checked = s.dashboard.showTimers, onCheckedChange = { v -> vm.updateDashboard { it.copy(showTimers = v) } }) }
    item { SwitchRow(label = "Now Playing", subtitle = "Currently-playing media_player entities with prev / play / next", checked = s.dashboard.showMedia, onCheckedChange = { v -> vm.updateDashboard { it.copy(showMedia = v) } }) }
    item { SwitchRow(label = "People", subtitle = "Home/away count + per-person state", checked = s.dashboard.showPersons, onCheckedChange = { v -> vm.updateDashboard { it.copy(showPersons = v) } }) }
    item { SwitchRow(label = "Next event", subtitle = "Earliest upcoming calendar event with NOW pill", checked = s.dashboard.showNextEvent, onCheckedChange = { v -> vm.updateDashboard { it.copy(showNextEvent = v) } }) }
    item { SwitchRow(label = "DRAW (power)", subtitle = "Sum of device_class=power sensors in watts", checked = s.dashboard.showPower, onCheckedChange = { v -> vm.updateDashboard { it.copy(showPower = v) } }) }
    item { SwitchRow(label = "Metrics row", subtitle = "LIGHTS ON / CAMERAS / ALERTS tiles", checked = s.dashboard.showMetrics, onCheckedChange = { v -> vm.updateDashboard { it.copy(showMetrics = v) } }) }
    item { SwitchRow(label = "Low-battery alerts", subtitle = "Surface battery sensors under the threshold", checked = s.dashboard.showLowBattery, onCheckedChange = { v -> vm.updateDashboard { it.copy(showLowBattery = v) } }) }
    item { SwitchRow(label = "Inline alert previews", subtitle = "Preview the first N HA persistent alerts on the dashboard", checked = s.dashboard.showInlineAlerts, onCheckedChange = { v -> vm.updateDashboard { it.copy(showInlineAlerts = v) } }) }
}

private fun LazyListScope.dashboardThresholds(s: AppSettings, vm: SettingsViewModel) {
    item { NumberStepperRow(label = "Dashboard refresh", subtitle = "Auto-refresh cadence (0 = pull-down only · long-press −/+ for ×10)", value = s.dashboard.refreshIntervalSec, min = 0, max = 600, step = 15, suffix = " s", onChange = { v -> vm.updateDashboard { it.copy(refreshIntervalSec = v) } }) }
    item { NumberStepperRow(label = "Low-battery threshold", subtitle = "Surface batteries below this percentage", value = s.dashboard.lowBatteryThresholdPct, min = 1, max = 100, step = 5, suffix = " %", onChange = { v -> vm.updateDashboard { it.copy(lowBatteryThresholdPct = v) } }) }
    item { NumberStepperRow(label = "DRAW amber above", subtitle = "Power threshold where the DRAW tile turns amber", value = s.dashboard.powerAmberThresholdW, min = 50, max = 10_000, step = 50, suffix = " W", onChange = { v -> vm.updateDashboard { it.copy(powerAmberThresholdW = v) } }) }
    item { NumberStepperRow(label = "DRAW red above", subtitle = "Power threshold where the DRAW tile turns red", value = s.dashboard.powerRedThresholdW, min = 200, max = 30_000, step = 100, suffix = " W", onChange = { v -> vm.updateDashboard { it.copy(powerRedThresholdW = v) } }) }
    item { NumberStepperRow(label = "Inline alerts shown", subtitle = "Max HA persistent-alert previews under METRICS", value = s.dashboard.inlineAlertsCount, min = 0, max = 10, step = 1, onChange = { v -> vm.updateDashboard { it.copy(inlineAlertsCount = v) } }) }
    item { NumberStepperRow(label = "Media rows shown", subtitle = "Max simultaneous media-player cards on the dashboard", value = s.dashboard.mediaSummaryCount, min = 1, max = 10, step = 1, onChange = { v -> vm.updateDashboard { it.copy(mediaSummaryCount = v) } }) }
}

private fun LazyListScope.dashboardOrder(s: AppSettings, vm: SettingsViewModel) {
    item {
        Text(
            text = "Drag-style reorder isn't on the R1's small screen yet. Use the arrows to nudge each tile up or down.",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.xs),
        )
    }
    item {
        TileOrderEditor(
            order = s.dashboard.tileOrder,
            onMove = { from, to ->
                vm.updateDashboard {
                    val list = it.tileOrder.toMutableList()
                    if (from in list.indices && to in list.indices) {
                        val moved = list.removeAt(from)
                        list.add(to, moved)
                    }
                    it.copy(tileOrder = list)
                }
            },
            onReset = {
                vm.updateDashboard { it.copy(tileOrder = com.github.itskenny0.r1ha.core.prefs.DashboardSettings.DEFAULT_TILE_ORDER) }
            },
        )
    }
}

// ── Integrations ─────────────────────────────────────────────────────────────────────────

private fun LazyListScope.integrationsRoot(
    s: AppSettings,
    vm: SettingsViewModel,
    push: (SettingsNode) -> Unit,
    groupBadge: (Array<out String>) -> Int,
    onOpenCategory: (SettingsCategory) -> Unit,
) {
    item { SubGroupLabel("TUNING") }
    item {
        val badge = groupBadge(arrayOf("INTEGRATIONS"))
        R1Row(
            label = SettingsNode.INTEGRATIONS_REFRESH.title,
            description = "Per-surface auto-refresh cadence",
            onClick = { push(SettingsNode.INTEGRATIONS_REFRESH) },
            showChevron = true,
            contentDescription = "Open Auto-refresh intervals",
            trailing = if (badge > 0) { { R1Chip(text = badge.toString(), variant = R1ChipVariant.Pill) } } else null,
        )
    }
    item {
        R1Row(
            label = SettingsNode.INTEGRATIONS_CAMERAS.title,
            description = "Snapshot polling, default grid view",
            value = if (s.integrations.camerasDefaultGrid) "GRID" else "LIST",
            onClick = { push(SettingsNode.INTEGRATIONS_CAMERAS) },
            showChevron = true,
            contentDescription = "Open Cameras",
        )
    }
    item {
        R1Row(
            label = SettingsNode.INTEGRATIONS_DEFAULTS.title,
            description = "Window sizes, result caps, history depth",
            onClick = { push(SettingsNode.INTEGRATIONS_DEFAULTS) },
            showChevron = true,
            contentDescription = "Open Defaults & limits",
        )
    }
    item { SubGroupLabel("DEVICE AS A SERVICE") }
    item {
        R1Row(
            label = "Sync",
            description = "Mirror settings across devices via Home Assistant",
            value = if (s.integrations.haSyncEnabled) "ON · ${s.integrations.haSyncIntervalSec}s" else "Off",
            onClick = { onOpenCategory(SettingsCategory.SYNC) },
            showChevron = true,
            contentDescription = "Open Sync",
        )
    }
    item {
        R1Row(
            label = "IoT Camera Mode",
            description = "Stream the device camera to Home Assistant",
            value = if (s.iotCamera.enabled) {
                val sinks = buildList {
                    if (s.iotCamera.mjpegEnabled) add("MJPEG")
                    if (s.iotCamera.mqttEnabled) add("MQTT")
                }
                "ON · ${if (sinks.isEmpty()) "no sinks" else sinks.joinToString(" + ")}"
            } else "Off",
            onClick = { onOpenCategory(SettingsCategory.IOT_CAMERA) },
            showChevron = true,
            contentDescription = "Open IoT Camera Mode",
        )
    }
    item {
        R1Row(
            label = "IoT Sensors Mode",
            description = "Expose device sensors + controls to Home Assistant",
            value = if (s.iotSensors.enabled) "On" else "Off",
            onClick = { onOpenCategory(SettingsCategory.IOT_SENSORS) },
            showChevron = true,
            contentDescription = "Open IoT Sensors Mode",
        )
    }
    item {
        R1Row(
            label = "MQTT broker",
            description = "Host / port / auth / TLS · required by IoT modes",
            value = if (s.advanced.mqttHost.isNotBlank()) {
                "${s.advanced.mqttHost}:${s.advanced.mqttPort}" + (if (s.advanced.mqttUseTls) " · TLS" else "")
            } else "Not configured",
            onClick = { onOpenCategory(SettingsCategory.MQTT) },
            showChevron = true,
            contentDescription = "Open MQTT broker",
        )
    }
    item { CategoryResetRow(label = "RESET INTEGRATIONS", category = com.github.itskenny0.r1ha.core.prefs.SettingCategory.INTEGRATIONS, vm = vm) }
}

private fun LazyListScope.integrationsRefresh(s: AppSettings, vm: SettingsViewModel) {
    item { NumberStepperRow(label = "Notifications refresh", subtitle = "Auto-refresh the Notifications surface every…", value = s.integrations.notificationsRefreshSec, min = 0, max = 600, step = 15, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(notificationsRefreshSec = v) } }) }
    item { NumberStepperRow(label = "Logbook refresh", subtitle = "Auto-refresh the Recent Activity feed every…", value = s.integrations.logbookRefreshSec, min = 0, max = 900, step = 30, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(logbookRefreshSec = v) } }) }
    item { NumberStepperRow(label = "Who's-home refresh", subtitle = "Auto-refresh the Persons surface every…", value = s.integrations.personsRefreshSec, min = 0, max = 900, step = 30, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(personsRefreshSec = v) } }) }
    item { NumberStepperRow(label = "Weather refresh", subtitle = "Auto-refresh the Weather surface every…", value = s.integrations.weatherRefreshSec, min = 0, max = 3600, step = 60, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(weatherRefreshSec = v) } }) }
    item { NumberStepperRow(label = "Calendars refresh", subtitle = "Auto-refresh the Calendars surface every…", value = s.integrations.calendarsRefreshSec, min = 0, max = 3600, step = 60, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(calendarsRefreshSec = v) } }) }
}

private fun LazyListScope.integrationsCameras(s: AppSettings, vm: SettingsViewModel) {
    item { NumberStepperRow(label = "Camera overlay polling", subtitle = "Snapshot fetch interval when viewing a camera fullscreen", value = s.integrations.cameraOverlayPollSec, min = 1, max = 60, step = 1, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(cameraOverlayPollSec = v) } }) }
    item { NumberStepperRow(label = "Camera grid polling", subtitle = "Snapshot fetch interval per tile in GRID view", value = s.integrations.cameraGridPollSec, min = 2, max = 120, step = 2, suffix = " s", onChange = { v -> vm.updateIntegrations { it.copy(cameraGridPollSec = v) } }) }
    item { SwitchRow(label = "Cameras open in GRID", subtitle = "Default to the polling-tiles view rather than the text list", checked = s.integrations.camerasDefaultGrid, onCheckedChange = { v -> vm.updateIntegrations { it.copy(camerasDefaultGrid = v) } }) }
}

private fun LazyListScope.integrationsDefaults(s: AppSettings, vm: SettingsViewModel) {
    item { NumberStepperRow(label = "Logbook default window", subtitle = "Time window applied on Recent Activity entry", value = s.integrations.logbookDefaultWindowHours, min = 1, max = 168, step = 1, suffix = " h", onChange = { v -> vm.updateIntegrations { it.copy(logbookDefaultWindowHours = v) } }) }
    item { NumberStepperRow(label = "Calendar look-ahead", subtitle = "Days of events fetched when drilling into a calendar", value = s.integrations.calendarLookaheadDays, min = 1, max = 90, step = 1, suffix = " d", onChange = { v -> vm.updateIntegrations { it.copy(calendarLookaheadDays = v) } }) }
    item { NumberStepperRow(label = "Quick Search result cap", subtitle = "Maximum entities shown for a search", value = s.integrations.searchResultCap, min = 10, max = 500, step = 10, onChange = { v -> vm.updateIntegrations { it.copy(searchResultCap = v) } }) }
    item { NumberStepperRow(label = "RECENT history depth", subtitle = "Items kept in Templates / Service Caller RECENT lists", value = s.integrations.recentHistoryDepth, min = 0, max = 30, step = 1, onChange = { v -> vm.updateIntegrations { it.copy(recentHistoryDepth = v) } }) }
}

// ── Advanced ───────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.advancedRoot(
    s: AppSettings,
    vm: SettingsViewModel,
    modifiedCount: Int,
    onOpenDevMenu: () -> Unit,
    onOpenModifiedSettings: () -> Unit,
    onOpenSystemHealth: () -> Unit,
) {
    item { NavRow(label = "Dev menu", value = "Live logs, fire-event, integrations panel", onClick = onOpenDevMenu) }
    item { NavRow(label = "Modified settings", value = if (modifiedCount > 0) "$modifiedCount changed" else "All at defaults", onClick = onOpenModifiedSettings) }
    item { NavRow(label = "System health", value = "Server config, ping, error log", onClick = onOpenSystemHealth) }
}

// ── Browse ─────────────────────────────────────────────────────────────────────────────────

private fun LazyListScope.browseRoot(push: (SettingsNode) -> Unit) {
    item { R1Row(label = SettingsNode.BROWSE_TODAY.title, description = "Dashboard, Quick Search", onClick = { push(SettingsNode.BROWSE_TODAY) }, showChevron = true, contentDescription = "Open Today") }
    item { R1Row(label = SettingsNode.BROWSE_TALK.title, description = "Assist, Scenes, Automations, Helpers", onClick = { push(SettingsNode.BROWSE_TALK) }, showChevron = true, contentDescription = "Open Talk & fire") }
    item { R1Row(label = SettingsNode.BROWSE_STATUS.title, description = "Cameras, Weather, People, registries", onClick = { push(SettingsNode.BROWSE_STATUS) }, showChevron = true, contentDescription = "Open Status views") }
    item { R1Row(label = SettingsNode.BROWSE_POWER.title, description = "Updates, Repairs, diagnostics, backups", onClick = { push(SettingsNode.BROWSE_POWER) }, showChevron = true, contentDescription = "Open Power tools") }
}

private fun LazyListScope.browseToday(onOpenDashboard: () -> Unit, onOpenSearch: () -> Unit) {
    item { NavRow(label = "Dashboard", value = "Weather · People · Next event", onClick = onOpenDashboard) }
    item { NavRow(label = "Quick Search", value = "Find any entity", onClick = onOpenSearch) }
}

private fun LazyListScope.browseTalk(
    onOpenAssist: () -> Unit,
    onOpenScenes: () -> Unit,
    onOpenAutomations: () -> Unit,
    onOpenHelpers: () -> Unit,
    onOpenTodo: () -> Unit,
) {
    item { NavRow(label = "Assist", value = "Talk to HA", onClick = onOpenAssist) }
    item { NavRow(label = "Scenes & Scripts", value = "Fire instantly", onClick = onOpenScenes) }
    item { NavRow(label = "Automations", value = "List, trigger, enable / disable", onClick = onOpenAutomations) }
    item { NavRow(label = "Helpers", value = "input_*, counter, timer", onClick = onOpenHelpers) }
    item { NavRow(label = "To-do lists", value = "Shopping lists, tasks", onClick = onOpenTodo) }
}

private fun LazyListScope.browseStatus(
    onOpenCameras: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenPersons: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenLogbook: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenAreas: () -> Unit,
    onOpenLabels: () -> Unit,
    onOpenFloors: () -> Unit,
    onOpenZones: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenDevice: () -> Unit,
) {
    item { NavRow(label = "Cameras", value = "Live snapshots", onClick = onOpenCameras) }
    item { NavRow(label = "Weather", value = "Conditions readout", onClick = onOpenWeather) }
    item { NavRow(label = "Who's home", value = "People + device trackers", onClick = onOpenPersons) }
    item { NavRow(label = "Calendars", value = "Next event preview", onClick = onOpenCalendars) }
    item { NavRow(label = "Recent Activity", value = "Logbook feed", onClick = onOpenLogbook) }
    item { NavRow(label = "Notifications", value = "HA persistent alerts", onClick = onOpenNotifications) }
    item { NavRow(label = "Areas", value = "HA area registry", onClick = onOpenAreas) }
    item { NavRow(label = "Labels", value = "HA label registry (tags)", onClick = onOpenLabels) }
    item { NavRow(label = "Floors", value = "Floor → areas hierarchy", onClick = onOpenFloors) }
    item { NavRow(label = "Zones", value = "Geographic zones + who's there", onClick = onOpenZones) }
    item { NavRow(label = "Energy", value = "Draw, production, today's kWh", onClick = onOpenEnergy) }
    item { NavRow(label = "Device", value = "Local: brightness, volume, flashlight", onClick = onOpenDevice) }
}

private fun LazyListScope.browsePower(
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onOpenUpdates: () -> Unit,
    onOpenRepairs: () -> Unit,
    onOpenMediaBrowse: () -> Unit,
    onOpenBackups: () -> Unit,
    onOpenZhaPairing: () -> Unit,
    onOpenTemplate: () -> Unit,
    onOpenServiceCaller: () -> Unit,
    onOpenServices: () -> Unit,
    onOpenSystemHealth: () -> Unit,
    onOpenLovelace: () -> Unit,
    onOpenDevices: () -> Unit,
    onOpenIntegrations: () -> Unit,
    onOpenBlueprints: () -> Unit,
    onOpenLogs: () -> Unit,
    onOpenUsers: () -> Unit,
    onOpenTags: () -> Unit,
    onOpenStatistics: () -> Unit,
) {
    item { NavRow(label = "Updates", value = "HA Core, add-ons, integrations", onClick = onOpenUpdates) }
    item { NavRow(label = "Repairs", value = "HA issues + ignore", onClick = onOpenRepairs) }
    item { NavRow(label = "Media Browse", value = "Browse + play any media_player library", onClick = onOpenMediaBrowse) }
    item { NavRow(label = "Backups", value = "View + create HA backups", onClick = onOpenBackups) }
    item { NavRow(label = "Zigbee pair", value = "Open the network to enrol new devices", onClick = onOpenZhaPairing) }
    item { NavRow(label = "Templates", value = "Jinja2 evaluator", onClick = onOpenTemplate) }
    item { NavRow(label = "Service Caller", value = "Fire any service", onClick = onOpenServiceCaller) }
    item { NavRow(label = "Services Browser", value = "Discover available services", onClick = onOpenServices) }
    item { NavRow(label = "System Health", value = "HA version + error log", onClick = onOpenSystemHealth) }
    item { NavRow(label = "Lovelace (WebView)", value = "Open HA's frontend in-app", onClick = onOpenLovelace) }
    item { NavRow(label = "Devices", value = "Browse HA's device registry", onClick = onOpenDevices) }
    item { NavRow(label = "Integrations", value = "Configured integrations + reload", onClick = onOpenIntegrations) }
    item { NavRow(label = "Blueprints", value = "Installed automations + scripts, import from URL", onClick = onOpenBlueprints) }
    item { NavRow(label = "Logs", value = "Full /api/error_log with level + search", onClick = onOpenLogs) }
    item { NavRow(label = "Users", value = "Read-only HA user list (admin)", onClick = onOpenUsers) }
    item { NavRow(label = "Tags", value = "NFC / QR tag registry", onClick = onOpenTags) }
    item { NavRow(label = "Statistics", value = "Long-term sensor stats", onClick = onOpenStatistics) }
    item {
        val backupArmed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(backupArmed.value) {
            if (backupArmed.value) {
                kotlinx.coroutines.delay(3_000)
                backupArmed.value = false
            }
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
            Text(text = "BACKUP", style = R1.labelMicro, color = R1.AccentWarm)
            Spacer(Modifier.height(R1.space.xs))
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = if (backupArmed.value) "CONFIRM · CREATE BACKUP NOW" else "CREATE BACKUP NOW",
                onClick = {
                    if (backupArmed.value) {
                        backupArmed.value = false
                        coroutineScope.launch {
                            haRepository.callRawService(
                                domain = "backup",
                                service = "create",
                                data = kotlinx.serialization.json.JsonObject(emptyMap()),
                            ).fold(
                                onSuccess = { com.github.itskenny0.r1ha.core.util.Toaster.show("Backup creation started") },
                                onFailure = { t ->
                                    com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                                        shortText = "Backup failed to start",
                                        fullText = t.message ?: t.toString(),
                                    )
                                },
                            )
                        }
                    } else {
                        backupArmed.value = true
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = if (backupArmed.value)
                    "Triggers HA's backup.create service. Honors your supervisor's backup destination + retention config."
                else
                    "Fires backup.create on your HA server (HA Core 2024.4+).",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

// ── Search ─────────────────────────────────────────────────────────────────────────────────

/** Search field rendered at the top of every level. Filters the registry; a
 *  non-blank query swaps the page body for the flat matched-entries list. */
@Composable
private fun SettingsSearchBar(query: String, onQueryChange: (String) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.l, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            R1TextField(value = query, onValueChange = onQueryChange, placeholder = "Search settings…", monospace = false)
        }
        if (query.isNotEmpty()) {
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier.size(48.dp).r1Pressable(onClick = { onQueryChange("") }),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "✕", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

/** Flat matched-entries list grouped by registry category. Tapping a result
 *  jumps the back-stack to that setting's home subpage. */
private fun LazyListScope.settingsSearchResults(
    query: String,
    matched: List<com.github.itskenny0.r1ha.core.prefs.SettingEntry>,
    matchedDestinations: List<SettingsDestination>,
    current: AppSettings,
    onJump: (com.github.itskenny0.r1ha.core.prefs.SettingEntry) -> Unit,
    onOpenDestination: (SettingsDestination) -> Unit,
) {
    if (matched.isEmpty() && matchedDestinations.isEmpty()) {
        item {
            Text(
                text = "No settings match \"$query\".",
                style = R1.body,
                color = R1.InkMuted,
                modifier = Modifier.padding(22.dp),
            )
        }
        return
    }
    // Standalone sub-screens first, under a "JUMP TO" header, so the
    // screen-level destinations the registry can't index are the most
    // prominent results.
    if (matchedDestinations.isNotEmpty()) {
        item("__search_dest_header") {
            Text(
                text = "JUMP TO",
                style = R1.labelMicro,
                color = R1.AccentWarm,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
            )
        }
        items(matchedDestinations, key = { "__dest_${it.title}" }) { dest ->
            R1Row(
                label = dest.title,
                description = dest.subtitle,
                onClick = { onOpenDestination(dest) },
                boxed = true,
                showChevron = true,
                contentDescription = "Open ${dest.title}",
                modifier = Modifier.padding(horizontal = R1.space.m, vertical = R1.space.xs),
            )
        }
    }
    val grouped = matched
        .groupBy { it.category }
        .toList()
        .sortedBy { (cat, _) -> com.github.itskenny0.r1ha.core.prefs.SettingCategory.entries.indexOf(cat) }
    grouped.forEach { (category, entries) ->
        item("__search_cat_${category.name}") {
            Text(
                text = category.label.uppercase(),
                style = R1.labelMicro,
                color = R1.AccentWarm,
                modifier = Modifier.padding(start = 18.dp, top = 8.dp, bottom = 2.dp),
            )
        }
        itemsIndexed(entries, key = { _, it -> it.id }) { _, entry ->
            SearchResultRow(entry = entry, current = current, onClick = { onJump(entry) })
        }
    }
}

// ── Category rows ───────────────────────────────────────────────────────────────────────────

/**
 * Map a [SettingsNode] to an in-house [com.github.itskenny0.r1ha.ui.icons.R1IconSet]
 * glyph for the row's leading slot, so the category list scans by shape as well as
 * by text. The icon set is HA-domain focused, so a few categories borrow the closest
 * semantic glyph (Generic for the abstract ones); the point is a stable, distinct
 * emblem per row rather than a literal depiction.
 */
private fun nodeLeadingIcon(node: SettingsNode): androidx.compose.ui.graphics.vector.ImageVector {
    val set = com.github.itskenny0.r1ha.ui.icons.R1IconSet
    return when (node) {
        SettingsNode.CONNECTION, SettingsNode.CONNECTION_ACCOUNT -> set.Generic
        SettingsNode.CONNECTION_BACKUP -> set.Update
        SettingsNode.CONNECTION_SECURITY -> set.Lock
        SettingsNode.APPEARANCE, SettingsNode.APPEARANCE_THEME -> set.Light
        SettingsNode.APPEARANCE_NAVPANEL -> set.Select
        SettingsNode.APPEARANCE_CARDS, SettingsNode.APPEARANCE_CARDS_VALUEBAR,
        SettingsNode.APPEARANCE_CARDS_CHROME,
        -> set.Button
        SettingsNode.INPUT, SettingsNode.INPUT_WHEEL -> set.Counter
        SettingsNode.BEHAVIOUR -> set.Automation
        SettingsNode.BEHAVIOUR_QUICKTILES -> set.Switch
        SettingsNode.DASHBOARD, SettingsNode.DASHBOARD_CARDS,
        SettingsNode.DASHBOARD_THRESHOLDS, SettingsNode.DASHBOARD_ORDER,
        -> set.Sensor
        SettingsNode.INTEGRATIONS, SettingsNode.INTEGRATIONS_REFRESH,
        SettingsNode.INTEGRATIONS_DEFAULTS,
        -> set.Remote
        SettingsNode.INTEGRATIONS_CAMERAS -> set.Camera
        SettingsNode.ADVANCED -> set.Script
        SettingsNode.BROWSE, SettingsNode.BROWSE_TODAY, SettingsNode.BROWSE_TALK,
        SettingsNode.BROWSE_STATUS, SettingsNode.BROWSE_POWER,
        -> set.Todo
        SettingsNode.ROOT -> set.Generic
    }
}

/** Standard leading emblem for a Settings row: a muted-tinted [node] glyph. */
@Composable
private fun RowLeadingIcon(node: SettingsNode) {
    androidx.compose.material3.Icon(
        imageVector = nodeLeadingIcon(node),
        contentDescription = null,
        tint = R1.InkSoft,
        modifier = Modifier.size(22.dp),
    )
}

/** Top-level Settings list row: title + Android-style summary secondary text +
 *  optional modified-count badge, drills into [node]. */
@Composable
private fun CategoryRow(node: SettingsNode, summary: String, badge: Int, onClick: () -> Unit) {
    R1Row(
        label = node.title,
        description = summary,
        onClick = onClick,
        showChevron = true,
        contentDescription = "Open ${node.title}",
        leadingContent = { RowLeadingIcon(node) },
        trailing = if (badge > 0) { { R1Chip(text = badge.toString(), variant = R1ChipVariant.Pill) } } else null,
    )
}

/** Like [CategoryRow] but shows the current value on the right edge (accent)
 *  the way a leaf value row would, for mid-tree category rows. */
@Composable
private fun CategorySubRow(node: SettingsNode, summary: String, badge: Int, onClick: () -> Unit) {
    R1Row(
        label = node.title,
        value = summary,
        onClick = onClick,
        showChevron = true,
        contentDescription = "Open ${node.title}",
        leadingContent = { RowLeadingIcon(node) },
        trailing = if (badge > 0) { { R1Chip(text = badge.toString(), variant = R1ChipVariant.Pill) } } else null,
    )
}

/**
 * Map a registry [SettingCategory] to the parent SettingsScreen's section-header
 * string. Both surfaces (the section grid above and the search-result drilldown)
 * route through this so the strings live in exactly one place. Section labels
 * are the SettingsScreen's contract: not part of the registry's public API.
 * So the mapping is kept in this file rather than next to the enum.
 */
internal fun sectionNameForCategory(
    category: com.github.itskenny0.r1ha.core.prefs.SettingCategory,
): String = when (category) {
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.SERVER -> "SERVER"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.INPUT -> "SCROLL WHEEL"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.CARD_UI -> "CARD UI"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.BEHAVIOUR -> "BEHAVIOUR"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.APPEARANCE -> "APPEARANCE"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.INTEGRATIONS -> "INTEGRATIONS"
    com.github.itskenny0.r1ha.core.prefs.SettingCategory.DASHBOARD -> "DASHBOARD"
}

// ── Building blocks ──────────────────────────────────────────────────────────────────────

/**
 * Smaller heading rendered inside a page body to split it into visual
 * groups (e.g. "VISIBLE CARDS" vs "THRESHOLDS & INTERVALS" under
 * DASHBOARD) without escalating to a full Section divider. Pairs
 * nicely with long lists of related rows that would otherwise blur
 * together at a glance.
 */
@Composable
private fun SubGroupLabel(text: String) {
    Spacer(Modifier.height(R1.space.s))
    androidx.compose.material3.Text(
        text = text,
        style = R1.labelMicro,
        color = R1.InkMuted,
        modifier = Modifier.padding(
            start = R1.space.xl,
            end = R1.space.xl,
            top = R1.space.xxs,
            bottom = R1.space.xs,
        ),
    )
}

/**
 * Per-category "reset to defaults" affordance shown at the foot of a category
 * page. Two-stage arm-confirm (first tap arms, second within 3 s commits, then
 * auto-disarms) because single-tap reset is too easy to fire by accident on the
 * wheel-only R1. Routes to [SettingsViewModel.resetCategory] which only touches
 * the matching slice of [AppSettings]: server, favourites and pages are never
 * affected.
 */
@Composable
private fun CategoryResetRow(
    label: String,
    category: com.github.itskenny0.r1ha.core.prefs.SettingCategory,
    vm: SettingsViewModel,
) {
    val armed = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(armed.value) {
        if (armed.value) {
            kotlinx.coroutines.delay(3_000)
            armed.value = false
        }
    }
    Box(
        modifier = Modifier
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
    ) {
        R1Chip(
            text = if (armed.value) "CONFIRM RESET" else label,
            variant = R1ChipVariant.Action,
            tone = R1.StatusAmber,
            selected = armed.value,
            onClick = {
                if (armed.value) {
                    armed.value = false
                    vm.resetCategory(category)
                } else {
                    armed.value = true
                }
            },
            contentDescription = "Reset $label to defaults",
        )
    }
}

/**
 * Horizontal-scroll chip row selecting the in-app toast log threshold. OFF is the
 * default (no diagnostic toasts); WARN is the friendly diagnostic level (failures
 * + decoder drops). Tap a chip to switch.
 */
@Composable
private fun ToastLogLevelRow(
    current: com.github.itskenny0.r1ha.core.prefs.ToastLogLevel,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.ToastLogLevel) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
    ) {
        Text("Toast log level", style = R1.bodyEmph, color = R1.Ink)
        Text(
            text = "Off (default): no diagnostic toasts. Warn: surface failures and " +
                "decoder drops as tappable expanding toasts. Useful for 'where's my " +
                "entity?' on devices without adb. Debug: everything R1Log emits.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = R1.space.xxs, bottom = R1.space.s),
        )
        val scroll = rememberScrollState()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(scroll),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.entries.forEach { level ->
                R1Chip(
                    text = level.name,
                    variant = R1ChipVariant.Filter,
                    selected = level == current,
                    onClick = { onSelect(level) },
                )
            }
        }
    }
}

@Composable
private fun OrientationModeRow(
    current: com.github.itskenny0.r1ha.core.prefs.OrientationMode,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.OrientationMode) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
    ) {
        Text("Screen orientation", style = R1.bodyEmph, color = R1.Ink)
        Text(
            text = "Follow device (default): rotates with the sensor. " +
                "Portrait only: locks to portrait regardless of rotation. " +
                "Right choice for R1 and one-handed phone use.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = R1.space.xxs, bottom = R1.space.s),
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
        ) {
            com.github.itskenny0.r1ha.core.prefs.OrientationMode.entries.forEach { mode ->
                val label = when (mode) {
                    com.github.itskenny0.r1ha.core.prefs.OrientationMode.FOLLOW_DEVICE -> "Follow device"
                    com.github.itskenny0.r1ha.core.prefs.OrientationMode.PORTRAIT_ONLY -> "Portrait only"
                }
                R1Chip(
                    text = label,
                    variant = R1ChipVariant.Filter,
                    selected = mode == current,
                    onClick = { onSelect(mode) },
                )
            }
        }
    }
}


/**
 * Single matched-entry row in the search-results view. Tells the user which
 * category the setting lives in, the label / description, and its current
 * value, and on tap jumps the back-stack to that setting's home subpage.
 */
@Composable
private fun SearchResultRow(
    entry: com.github.itskenny0.r1ha.core.prefs.SettingEntry,
    current: com.github.itskenny0.r1ha.core.prefs.AppSettings,
    onClick: () -> Unit,
) {
    // Category tag lives on the group header now (parent LazyColumn), so the row
    // body only needs label + description + the current value. The value tints
    // accent-warm when non-default via R1Row's built-in value treatment; default
    // values read muted so they don't compete with actually-modified ones.
    val isModified = !entry.isDefault(current)
    R1Row(
        label = entry.label,
        description = entry.description,
        value = if (isModified) entry.currentDisplay(current) else null,
        onClick = onClick,
        boxed = true,
        modifier = Modifier.padding(horizontal = R1.space.m, vertical = R1.space.xs),
        trailing = if (!isModified) {
            {
                androidx.compose.material3.Text(
                    text = entry.currentDisplay(current),
                    style = R1.bodyEmph,
                    color = R1.InkSoft,
                )
            }
        } else {
            null
        },
    )
}

/**
 * Per-button row in the Chrome buttons reorder list. Renders:
 *   - Up / Down chips on the left to move the row one slot in either direction
 *     (chips are disabled at the list extremes so a press becomes a no-op rather
 *     than a layout-mutating wrap-around);
 *   - The button name in the middle;
 *   - A small switch on the right that toggles visibility. GEAR's switch is
 *     forced-on and the tap is swallowed so the user can't disable it.
 *
 * Lives inside the parent LazyColumn so it doesn't introduce a nested scroll;
 * the small fixed-size list (4 items today) is enumerated via [itemsIndexed].
 */
@Composable
private fun ChromeButtonRow(
    position: Int,
    config: com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit,
) {
    val gear = config.ref == com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR
    val label = when (config.ref) {
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY -> "Battery indicator"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.ASSIST_MIC -> "Assist mic"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.EDIT -> "Edit pencil"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR -> "Settings gear"
    }
    val subtitle = when (config.ref) {
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY ->
            "Also requires the system status bar hidden + battery-on-chrome opt-in"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.ASSIST_MIC ->
            "Opens HA Assist from anywhere on the card stack"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.EDIT ->
            "Opens the customize dialog for the active card"
        com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR ->
            "Tap: Settings. Long-press: Quick Search. Always shown."
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Position number; disambiguates "slot 1" (leftmost in the cluster) from
        // "slot N" (closest to GEAR) when the user is scanning the rows. Fixed-
        // width so the labels below line up.
        Text(
            text = "$position.",
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.width(18.dp),
        )
        // Up / Down chips. Both are r1Pressable-only when the move is legal; we
        // still render the disabled state so the row's left edge stays aligned
        // even at the list extremes.
        ReorderChip(label = "↑", enabled = !isFirst, onClick = onMoveUp)
        Spacer(Modifier.width(R1.space.xs))
        ReorderChip(label = "↓", enabled = !isLast, onClick = onMoveDown)
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = R1.bodyEmph, color = R1.Ink)
            Text(text = subtitle, style = R1.labelMicro, color = R1.InkMuted)
        }
        com.github.itskenny0.r1ha.ui.components.R1Switch(
            checked = config.enabled || gear,
            onCheckedChange = { if (!gear) onToggle(it) },
            enabled = !gear,
        )
    }
}

/**
 * Live preview of the chrome-row's right cluster as the user reorders / toggles
 * buttons. Renders compact pills in the same left-to-right order they'll appear
 * on the card stack. Hidden buttons are dimmed and struck through so the user
 * sees the position survives a visibility toggle (the disabled slot just won't
 * render at runtime). Keeps the preview decoupled from the heavy actual glyphs
 * (BatteryIndicator polls BatteryManager, AssistMicGlyph draws a path); those
 * would be wasteful inside a settings row that just needs to communicate order.
 */
@Composable
private fun ChromeButtonsPreview(
    buttons: List<com.github.itskenny0.r1ha.core.prefs.ChromeButtonConfig>,
) {
    // horizontalScroll so 'BAT · off' (the disabled-state badge) doesn't push
    // the cluster past the right edge on a 240-wide R1 portrait. With all
    // buttons enabled and short labels the row already fits; the scroll is a
    // graceful-overflow safety net for the disabled badge widths.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = R1.space.xl, end = R1.space.xl, top = R1.space.s, bottom = R1.space.xs)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "PREVIEW",
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        Spacer(Modifier.width(R1.space.m))
        buttons.forEachIndexed { idx, cfg ->
            val gear = cfg.ref == com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR
            val visible = cfg.enabled || gear
            val shortLabel = when (cfg.ref) {
                com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.BATTERY -> "BAT"
                com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.ASSIST_MIC -> "MIC"
                com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.EDIT -> "EDIT"
                com.github.itskenny0.r1ha.core.prefs.ChromeButtonRef.GEAR -> "GEAR"
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (visible) R1.SurfaceMuted else R1.SurfaceMuted.copy(alpha = 0.35f))
                    .border(
                        width = 1.dp,
                        color = if (visible) R1.AccentWarm.copy(alpha = 0.6f) else R1.Hairline,
                        shape = R1.ShapeS,
                    )
                    .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (visible) shortLabel else "$shortLabel · off",
                    style = R1.labelMicro,
                    color = if (visible) R1.Ink else R1.InkMuted,
                )
            }
            if (idx != buttons.lastIndex) {
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = "›",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.width(R1.space.s))
            }
        }
    }
}

@Composable
private fun ReorderChip(label: String, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (enabled) R1.SurfaceMuted else R1.SurfaceMuted.copy(alpha = 0.4f)
    val fg = if (enabled) R1.Ink else R1.InkMuted
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(R1.ShapeS)
            .background(bg)
            .then(if (enabled) Modifier.r1Pressable(onClick = onClick) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.body, color = fg)
    }
}

@Composable
private fun SwitchRow(
    label: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    // Merge label + subtitle + on/off state into one spoken phrase so the toggle
    // state reaches a screen reader (the switch graphic alone doesn't convey it).
    R1Row(
        label = label,
        description = subtitle,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        contentDescription = SettingsA11y.switchRowDescription(label, subtitle, checked),
        trailing = { R1Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange) },
    )
}

/**
 * NumberStepperRow: label + subtitle + −/+ pills around the current
 * value. Used for the new dashboard / integrations settings where
 * thresholds (battery low %, power amber/red watts) and intervals
 * (refresh cadence, polling intervals) need granular tuning without
 * a slider's tap-imprecision penalty on the R1's small screen.
 *
 * Tap a pill = ±step. Long-press a pill = ±step×10 (fast-step for
 * wide ranges like power thresholds 50…10 000 W). Pills disable
 * themselves when the value is at the matching boundary so the user
 * doesn't waste taps.
 */
@Composable
private fun NumberStepperRow(
    label: String,
    subtitle: String? = null,
    value: Int,
    min: Int,
    max: Int,
    step: Int = 1,
    suffix: String = "",
    onChange: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            if (subtitle != null) {
                Spacer(Modifier.height(R1.space.xxs))
                Text(subtitle, style = R1.body, color = R1.InkMuted)
            }
        }
        // −/value/+ cluster. Each pill is 28 dp tall, the value cell
        // sits between them as plain text; feels less busy than three
        // border-bordered pills in a row.
        Row(verticalAlignment = Alignment.CenterVertically) {
            val canDec = value > min
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(if (canDec) R1.SurfaceMuted else R1.Bg)
                    .r1RowPressable(
                        onTap = {
                            if (canDec) onChange((value - step).coerceAtLeast(min))
                        },
                        onLongPress = {
                            if (canDec) onChange((value - step * 10).coerceAtLeast(min))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "−", style = R1.body, color = if (canDec) R1.Ink else R1.InkMuted)
            }
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = "$value$suffix",
                style = R1.bodyEmph,
                color = R1.Ink,
                modifier = Modifier.padding(horizontal = R1.space.xs),
            )
            Spacer(Modifier.width(R1.space.s))
            val canInc = value < max
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(if (canInc) R1.SurfaceMuted else R1.Bg)
                    .r1RowPressable(
                        onTap = {
                            if (canInc) onChange((value + step).coerceAtMost(max))
                        },
                        onLongPress = {
                            if (canInc) onChange((value + step * 10).coerceAtMost(max))
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "+", style = R1.body, color = if (canInc) R1.Ink else R1.InkMuted)
            }
        }
    }
}

/**
 * SliderRow: label + current-value pill on one line, a Material3 slider beneath,
 * then an optional subtitle. Used for the continuous 0..100 settings (card-stack
 * scroll sensitivity) where a stepper would be too coarse and a segmented picker
 * has too many stops. The slider quantises to whole integers via toInt().
 */
@Composable
private fun SliderRow(
    label: String,
    subtitle: String? = null,
    value: Int,
    valueLabel: String,
    min: Int = 0,
    max: Int = 100,
    onChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = R1.bodyEmph, color = R1.Ink, modifier = Modifier.weight(1f))
            Text(valueLabel, style = R1.bodyEmph, color = R1.AccentWarm)
        }
        Spacer(Modifier.height(R1.space.s))
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = min.toFloat()..max.toFloat(),
            colors = SliderDefaults.colors(
                thumbColor = R1.AccentWarm,
                activeTrackColor = R1.AccentWarm,
                inactiveTrackColor = R1.Hairline,
            ),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(R1.space.xxs))
            Text(subtitle, style = R1.labelMicro, color = R1.InkMuted)
        }
    }
}

@Composable
private fun LabeledControl(label: String, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(R1.space.s))
        content()
    }
}

@Composable
private fun NavRow(
    label: String,
    value: String? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick)
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Label gets a fixed maxLines = 1 so a long supplementary
        // value can't squeeze it down to one-character-per-line.
        // Without this, e.g. 'Device' next to 'Local: brightness,
        // volume, flashlight' wrapped vertically D / e / v / i / c /
        // e because the value claimed all remaining width and the
        // label's weight(1f) collapsed to whatever was left. The
        // label is the primary identifier; the value is annotation
        // and should ellipsize first.
        Text(
            label,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (value != null) {
            // Value takes the remaining width via weight(1f) and
            // right-aligns with single-line ellipsis. Long values
            // gracefully truncate rather than push the label out.
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = value,
                style = R1.body,
                color = R1.InkSoft,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier
                    .weight(1f)
                    .padding(end = R1.space.s),
            )
        } else {
            Spacer(Modifier.weight(1f))
        }
        com.github.itskenny0.r1ha.ui.components.Chevron(
            direction = com.github.itskenny0.r1ha.ui.components.ChevronDirection.Right,
            size = 10.dp,
            tint = R1.InkMuted,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    // Label and value render as two columns; merge them into one spoken phrase
    // ("HA version, 2025.5.1") so a screen reader doesn't read them as two
    // disconnected fragments.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .semantics(mergeDescendants = true) {
                contentDescription = SettingsA11y.infoRowDescription(label, value)
            }
            .padding(horizontal = R1.space.xl, vertical = R1.space.m),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            label,
            style = R1.bodyEmph,
            color = R1.Ink,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(R1.space.l))
        Text(
            text = value,
            style = if (mono) R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
                else R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
            // Bound a very long value (e.g. a URL or a long status string) to a
            // few lines with an ellipsis rather than letting it grow the row
            // unbounded.
            maxLines = 3,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun DangerButton(text: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Hairline border in StatusRed so the destructive intent reads at a glance; the
            // earlier flat `SurfaceMuted` fill didn't signal "danger" from across the screen.
            .r1Pressable(onClick)
            .padding(vertical = R1.space.m),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = R1.labelMicro, color = R1.StatusRed.copy(alpha = 0.92f))
    }
}

/**
 * Bespoke segmented picker: rectangular cells, hairline borders, selected = orange fill on
 * black text. Reads like a hardware mode selector instead of Material's pill chips.
 */
@Composable
private fun <T> SegmentedIntPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> SegmentedEnumPicker(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) = Segmented(options = options, selected = selected, label = label, onSelect = onSelect)

@Composable
private fun <T> Segmented(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted),
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = option == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(option) })
                    .padding(vertical = R1.space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    style = R1.labelMicro,
                    color = if (isSelected) R1.Bg else R1.InkSoft,
                )
            }
            // Hairline divider between cells (skip after last).
            if (index < options.lastIndex) {
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(36.dp)
                        .background(R1.Bg),
                )
            }
        }
    }
}

/**
 * 3x3 grid picker for the global position-pip slot. The grid maps directly
 * to the nine [com.github.itskenny0.r1ha.core.prefs.PositionDotLocation]
 * values (top row, middle row with centred LEFT / HIDDEN / RIGHT chips, and
 * bottom row), so the user's spatial intuition matches what they see on
 * the deck. HIDDEN sits in the middle of the middle row because that's the
 * "no anchor" position; the centre top/bottom positions still get explicit
 * cells so users can pick "top centre" or "bottom centre" without thinking
 * about HIDDEN as the centre slot.
 *
 * Selected cell paints accent; the rest stay neutral with a hairline border.
 * Cells are uniformly sized so the picker reads as a true grid rather than a
 * row of chips of varying width.
 */
@Composable
private fun PositionDotLocationPicker(
    selected: com.github.itskenny0.r1ha.core.prefs.PositionDotLocation,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.PositionDotLocation) -> Unit,
) {
    // Row order: top / middle / bottom. The middle row uses LEFT / HIDDEN /
    // RIGHT because the centre-middle is conceptually "no anchor" and HIDDEN
    // gets the slot that would otherwise overlap with TOP_CENTER's mental
    // model. Top / bottom centres get their own explicit cells.
    val rows: List<List<Pair<String, com.github.itskenny0.r1ha.core.prefs.PositionDotLocation>>> = remember {
        listOf(
            listOf(
                "↖" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_LEFT,
                "↑" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_CENTER,
                "↗" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.TOP_RIGHT,
            ),
            listOf(
                "←" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.LEFT_CENTER,
                "·" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.HIDDEN,
                "→" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.RIGHT_CENTER,
            ),
            listOf(
                "↙" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_LEFT,
                "↓" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_CENTER,
                "↘" to com.github.itskenny0.r1ha.core.prefs.PositionDotLocation.BOTTOM_RIGHT,
            ),
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEachIndexed { rowIdx, row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEachIndexed { colIdx, (glyph, loc) ->
                    val isSelected = loc == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .padding(2.dp)
                            .clip(R1.ShapeS)
                            .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                            .border(
                                1.dp,
                                if (isSelected) R1.AccentWarm else R1.Hairline,
                                R1.ShapeS,
                            )
                            .r1Pressable(onClick = { onSelect(loc) }),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = glyph,
                            style = R1.bodyEmph,
                            color = if (isSelected) R1.Bg else R1.InkSoft,
                        )
                    }
                    @Suppress("UNUSED_EXPRESSION") colIdx
                }
            }
            @Suppress("UNUSED_EXPRESSION") rowIdx
        }
        Spacer(Modifier.height(R1.space.xs))
        Text(
            text = com.github.itskenny0.r1ha.core.prefs.positionDotLocationLabel(selected),
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = R1.space.xl),
        )
    }
}

/**
 * Five-way picker for the global value-bar location. Laid out as a cross so the
 * spatial intent reads at a glance: TOP on the top row, LEFT / HIDDEN / RIGHT on
 * the middle row, BOTTOM on the bottom row. The centre cell is HIDDEN ("no bar")
 * which mirrors how the position-pip picker uses its centre cell. Same chrome
 * (44 dp tiles, accent fill on selection, hairline border) as
 * [PositionDotLocationPicker].
 */
@Composable
private fun ValueBarLocationPicker(
    selected: com.github.itskenny0.r1ha.core.prefs.ValueBarLocation,
    onSelect: (com.github.itskenny0.r1ha.core.prefs.ValueBarLocation) -> Unit,
) {
    val rows: List<List<Pair<String, com.github.itskenny0.r1ha.core.prefs.ValueBarLocation>?>> = remember {
        listOf(
            listOf(null, "↑" to com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.TOP, null),
            listOf(
                "←" to com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.LEFT,
                "·" to com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.HIDDEN,
                "→" to com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.RIGHT,
            ),
            listOf(null, "↓" to com.github.itskenny0.r1ha.core.prefs.ValueBarLocation.BOTTOM, null),
        )
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        rows.forEach { row ->
            Row(modifier = Modifier.fillMaxWidth()) {
                row.forEach { cell ->
                    if (cell == null) {
                        // Empty corner cell: keeps the cross shape aligned.
                        Spacer(Modifier.weight(1f).height(44.dp).padding(2.dp))
                    } else {
                        val (glyph, loc) = cell
                        val isSelected = loc == selected
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .padding(2.dp)
                                .clip(R1.ShapeS)
                                .background(if (isSelected) R1.AccentWarm else R1.SurfaceMuted)
                                .border(
                                    1.dp,
                                    if (isSelected) R1.AccentWarm else R1.Hairline,
                                    R1.ShapeS,
                                )
                                .r1Pressable(onClick = { onSelect(loc) }),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = glyph,
                                style = R1.bodyEmph,
                                color = if (isSelected) R1.Bg else R1.InkSoft,
                            )
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(R1.space.xs))
        Text(
            text = com.github.itskenny0.r1ha.core.prefs.valueBarLocationLabel(selected),
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.padding(horizontal = R1.space.xl),
        )
    }
}

/**
 * Picker for the night-mode theme. Same three options as the main theme picker
 * but rendered inline as a dialog (no full-screen route) because picking a
 * night theme is a smaller, more transactional choice; the user already
 * decided to enable auto-mode and is just confirming which theme to swap to.
 */
@Composable
private fun NightThemePickerDialog(
    current: com.github.itskenny0.r1ha.core.prefs.ThemeId,
    onPick: (com.github.itskenny0.r1ha.core.prefs.ThemeId) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = { Text(text = "NIGHT THEME", style = R1.sectionHeader, color = R1.Ink) },
        text = {
            Column {
                for (theme in com.github.itskenny0.r1ha.core.prefs.ThemeId.entries) {
                    val selected = theme == current
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .clip(R1.ShapeS)
                            .background(if (selected) R1.AccentWarm.copy(alpha = 0.2f) else R1.Bg)
                            .border(
                                1.dp,
                                if (selected) R1.AccentWarm else R1.Hairline,
                                R1.ShapeS,
                            )
                            .r1Pressable(onClick = { onPick(theme) })
                            .padding(horizontal = R1.space.m, vertical = R1.space.m),
                    ) {
                        Text(
                            text = theme.name.replace('_', ' ').lowercase()
                                .replaceFirstChar { it.uppercase() },
                            style = R1.body,
                            color = if (selected) R1.AccentWarm else R1.Ink,
                        )
                    }
                }
            }
        },
        confirmButton = {
            com.github.itskenny0.r1ha.ui.components.R1Button(text = "CLOSE", onClick = onDismiss)
        },
    )
}

/**
 * Hour-range picker for the night-theme window. Two ±-steppers for start and
 * end hours (0–23, local). Wraparound (start > end) is allowed: e.g. 22 → 6
 * means "night is 22:00–06:00 the next morning." Renders the resulting window
 * as a sentence so the user can sanity-check before applying.
 */
@Composable
private fun NightHoursDialog(
    startHour: Int,
    endHour: Int,
    onApply: (Int, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    var start by remember { mutableStateOf(startHour.coerceIn(0, 23)) }
    var end by remember { mutableStateOf(endHour.coerceIn(0, 23)) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = { Text(text = "NIGHT WINDOW", style = R1.sectionHeader, color = R1.Ink) },
        text = {
            Column {
                HourStepper(label = "Start hour", value = start, onChange = { start = it })
                Spacer(Modifier.height(R1.space.s))
                HourStepper(label = "End hour", value = end, onChange = { end = it })
                Spacer(Modifier.height(R1.space.m))
                val rangeStr = if (start == end) {
                    "Night theme disabled (start == end)"
                } else if (start < end) {
                    "Night theme active from $start:00 to $end:00"
                } else {
                    "Night theme active from $start:00 to $end:00 (overnight)"
                }
                Text(text = rangeStr, style = R1.body, color = R1.InkMuted)
            }
        },
        confirmButton = {
            com.github.itskenny0.r1ha.ui.components.R1Button(text = "APPLY", onClick = { onApply(start, end) })
        },
        dismissButton = {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = "CANCEL",
                onClick = onDismiss,
                variant = com.github.itskenny0.r1ha.ui.components.R1ButtonVariant.Outlined,
            )
        },
    )
}

@Composable
private fun HourStepper(label: String, value: Int, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = label, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .r1Pressable(onClick = { onChange(((value - 1) + 24) % 24) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "−", style = R1.body, color = R1.Ink)
        }
        Box(
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .widthIn(min = 40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "$value:00", style = R1.bodyEmph, color = R1.Ink)
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .r1Pressable(onClick = { onChange((value + 1) % 24) }),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "+", style = R1.body, color = R1.Ink)
        }
    }
}

/**
 * SECURITY section content. Renders the TLS pinning toggle, the list of currently
 * configured SHA-256 pins, and an inline "add pin" form.
 *
 * State is held in [SecurityPolicyStore], which is SharedPreferences-backed and
 * outside the DataStore-flow path used by the rest of Settings. The OkHttpClient
 * builds from the policy at process start and never rebuilds, so every mutation
 * here surfaces a small "restart required" badge: the user gets immediate
 * feedback in the UI (pin appears in the list) but the actual handshake
 * enforcement waits until the next launch.
 */
@Composable
private fun SecuritySection() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.github.itskenny0.r1ha.App
    val store = app.graph.securityPolicy
    val policyState = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(store.current()) }
    val pendingPin = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    val pendingPinError = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Snapshot the persisted state at first composition. Changes go through
    // [store.update] which we mirror back into the local state; saves a flow
    // collector for what is, in practice, a one-screen-at-a-time surface.
    val policy = policyState.value

    fun applyPolicy(transform: (com.github.itskenny0.r1ha.core.security.SecurityPolicy) -> com.github.itskenny0.r1ha.core.security.SecurityPolicy) {
        store.update(transform)
        policyState.value = store.current()
        com.github.itskenny0.r1ha.core.util.Toaster.show("Saved. Restart app to apply.")
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        SwitchRow(
            label = "TLS certificate pinning",
            subtitle = "Reject any TLS certificate the server presents whose SHA-256 SPKI hash isn't in the list below. Pin at least two values (current key + backup) so a normal cert rotation doesn't lock you out. Takes effect on next app launch.",
            checked = policy.tlsPinningEnabled,
            onCheckedChange = { v -> applyPolicy { it.copy(tlsPinningEnabled = v) } },
        )

        // Live status banner: green when active, amber when armed but with no
        // pins (which means OkHttp won't apply any pinner, so the toggle is
        // a no-op; surface that so the user doesn't think they're protected).
        val active = policy.tlsPinningEnabled && policy.sha256Pins.isNotEmpty()
        val armedNoPins = policy.tlsPinningEnabled && policy.sha256Pins.isEmpty()
        val statusText = when {
            active -> "${policy.sha256Pins.size} PIN${if (policy.sha256Pins.size == 1) "" else "S"} ACTIVE · ENFORCED ON NEXT LAUNCH"
            armedNoPins -> "PINNING ON BUT NO PINS CONFIGURED · ADD AT LEAST ONE PIN BELOW"
            else -> "PINNING OFF · TRUSTS THE SYSTEM CERTIFICATE STORE"
        }
        val statusColor = when {
            active -> R1.AccentCool
            armedNoPins -> R1.StatusAmber
            else -> R1.InkMuted
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
                .clip(R1.ShapeS)
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(text = statusText, style = R1.labelMicro, color = statusColor)
        }

        // Pin list. Each row: monospace hash + REMOVE.
        if (policy.sha256Pins.isNotEmpty()) {
            Spacer(Modifier.height(R1.space.s))
            policy.sha256Pins.forEachIndexed { idx, pin ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = R1.space.xl, vertical = R1.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                    ) {
                        Text(text = "#${idx + 1}", style = R1.labelMicro, color = R1.InkMuted)
                    }
                    Spacer(Modifier.width(R1.space.s))
                    Text(
                        text = pin,
                        style = R1.body.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                        color = R1.Ink,
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                    )
                    Spacer(Modifier.width(R1.space.s))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
                                applyPolicy { it.copy(sha256Pins = it.sha256Pins.toMutableList().also { l -> l.removeAt(idx) }) }
                            })
                            .padding(horizontal = R1.space.m, vertical = R1.space.s),
                    ) {
                        Text(text = "REMOVE", style = R1.labelMicro, color = R1.StatusAmber)
                    }
                }
            }
        }

        // Add-pin form. The user pastes a base64 SHA-256 hash (with or
        // without the `sha256/` prefix). [SecurityPolicyStore.normalisePin]
        // rejects anything that doesn't decode to a 32-byte digest; the
        // typical shape of a copy-paste from a `openssl s_client … |
        // openssl dgst -sha256 -binary | openssl enc -base64` pipeline,
        // which is the standard way to derive a pin from the server cert.
        Spacer(Modifier.height(R1.space.s))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        ) {
            Text(text = "ADD PIN", style = R1.labelMicro, color = R1.AccentWarm)
            Spacer(Modifier.height(R1.space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.weight(1f)) {
                    R1TextField(
                        value = pendingPin.value,
                        onValueChange = {
                            pendingPin.value = it
                            pendingPinError.value = null
                        },
                        placeholder = "base64 SHA-256 (sha256/...)",
                        monospace = true,
                    )
                }
                Spacer(Modifier.width(R1.space.s))
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = {
                            val canonical = com.github.itskenny0.r1ha.core.security.SecurityPolicyStore
                                .normalisePin(pendingPin.value)
                            if (canonical == null) {
                                pendingPinError.value = "Not a SHA-256 base64 hash (expected 32 decoded bytes)"
                            } else if (canonical in policy.sha256Pins) {
                                pendingPinError.value = "Pin already in list"
                            } else {
                                applyPolicy { it.copy(sha256Pins = it.sha256Pins + canonical) }
                                pendingPin.value = ""
                                pendingPinError.value = null
                            }
                        })
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                ) {
                    Text(text = "ADD", style = R1.labelMicro, color = R1.AccentWarm)
                }
            }
            pendingPinError.value?.let { err ->
                Spacer(Modifier.height(R1.space.xs))
                Text(text = err, style = R1.labelMicro, color = R1.StatusAmber)
            }
            Spacer(Modifier.height(R1.space.s))
            Text(
                text = "Derive a pin manually: openssl s_client -connect HOST:443 -servername HOST | openssl x509 -pubkey -noout | openssl pkey -pubin -outform DER | openssl dgst -sha256 -binary | openssl enc -base64",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(R1.space.s))
            // FETCH-FROM-SERVER chip: runs a one-shot HEAD against the user's
            // server URL (using an unpinned ephemeral client), pulls the leaf
            // cert's SPKI hash, and offers ADD chips per certificate in the
            // chain. Much friendlier than the openssl pipeline above for users
            // who just want to pin their current cert. Server URL must be
            // configured first; otherwise we have nothing to probe.
            val fetchedPins = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<List<com.github.itskenny0.r1ha.core.security.PinFetcher.CertPin>?>(null)
            }
            val fetchInFlight = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(false)
            }
            val fetchError = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<String?>(null)
            }
            // Read the server URL from the SharedPreferences shadow store directly —
            // it's the source of truth across DataStore restarts and synchronous to
            // read, which keeps this composable side-effect-free.
            val shadow = androidx.compose.ui.platform.LocalContext.current
                .getSharedPreferences("r1ha_shadow", android.content.Context.MODE_PRIVATE)
            val serverUrl = shadow.getString("server.url", null)
            val coScope = androidx.compose.runtime.rememberCoroutineScope()
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (fetchInFlight.value) return@r1Pressable
                        if (serverUrl.isNullOrBlank()) {
                            fetchError.value = "Configure your HA server URL in SERVER first"
                            return@r1Pressable
                        }
                        fetchInFlight.value = true
                        fetchError.value = null
                        coScope.launch {
                            com.github.itskenny0.r1ha.core.security.PinFetcher.probe(serverUrl).fold(
                                onSuccess = {
                                    fetchedPins.value = it
                                    fetchError.value = if (it.isEmpty()) "Server returned no certificates" else null
                                },
                                onFailure = { t ->
                                    fetchError.value = t.message ?: t.toString()
                                },
                            )
                            fetchInFlight.value = false
                        }
                    })
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
            ) {
                Text(
                    text = if (fetchInFlight.value) "FETCHING…" else "FETCH PINS FROM SERVER",
                    style = R1.labelMicro,
                    color = R1.AccentCool,
                )
            }
            fetchError.value?.let { err ->
                Spacer(Modifier.height(R1.space.xs))
                Text(text = err, style = R1.labelMicro, color = R1.StatusAmber)
            }
            fetchedPins.value?.let { pins ->
                Spacer(Modifier.height(R1.space.s))
                pins.forEach { p ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = R1.space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = p.label, style = R1.body, color = R1.Ink, maxLines = 1)
                            Text(
                                text = p.sha256Base64,
                                style = R1.labelMicro.copy(
                                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                                ),
                                color = R1.InkMuted,
                                maxLines = 1,
                            )
                        }
                        Spacer(Modifier.width(R1.space.s))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(onClick = {
                                    if (p.sha256Base64 in policy.sha256Pins) {
                                        com.github.itskenny0.r1ha.core.util.Toaster.show(
                                            "Pin already in list",
                                        )
                                    } else {
                                        applyPolicy {
                                            it.copy(sha256Pins = it.sha256Pins + p.sha256Base64)
                                        }
                                    }
                                })
                                .padding(horizontal = R1.space.m, vertical = R1.space.s),
                        ) {
                            Text(
                                text = if (p.sha256Base64 in policy.sha256Pins) "ADDED" else "ADD",
                                style = R1.labelMicro,
                                color = if (p.sha256Base64 in policy.sha256Pins) R1.InkMuted else R1.AccentWarm,
                            )
                        }
                    }
                }
            }
        }

        // ── mTLS client certificate (optional) ────────────────────────
        // Some HA deployments behind a reverse proxy (Caddy + client-CA,
        // NGINX with `ssl_verify_client on`) require the client to
        // present a cert during the TLS handshake. This section lets the
        // user pick a .p12 keystore (the typical export format from
        // step-ca / openssl) plus its password. The keystore file is
        // copied to filesDir/mtls/ on import; mTLS is opt-in via the
        // toggle and changes need an app restart to apply.
        Spacer(Modifier.height(R1.space.m))
        MtlsClientCertEditor(store = store, policy = policy, onUpdated = { policyState.value = store.current() })
    }
}

/**
 * mTLS client-certificate editor. Distinct from the pin editor above
 * because the input mechanism (SAF file picker + password) and the
 * surface (import button + remove button + state badge) are different
 * enough to warrant a dedicated composable.
 */
@Composable
private fun MtlsClientCertEditor(
    store: com.github.itskenny0.r1ha.core.security.SecurityPolicyStore,
    policy: com.github.itskenny0.r1ha.core.security.SecurityPolicy,
    onUpdated: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingPassword = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(policy.mtlsKeystorePassword)
    }
    androidx.compose.runtime.LaunchedEffect(policy.mtlsKeystorePassword) {
        // Reflect external updates (RESET, IMPORT) into the local password field
        // so the input doesn't lag behind reality.
        pendingPassword.value = policy.mtlsKeystorePassword
    }
    // SAF launcher for picking the PKCS12 keystore. We copy the bytes into
    // filesDir/mtls/client.p12 immediately so the import doesn't depend on
    // the source URI staying valid (the user may have picked it off a USB
    // stick that gets unmounted, or from a cloud-storage app that may
    // revoke the temp URI after the activity result lands).
    val importLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri: android.net.Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val dir = java.io.File(context.filesDir, "mtls").apply { mkdirs() }
            val dest = java.io.File(dir, "client.p12")
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out) }
            } ?: error("couldn't open input stream")
            store.update { it.copy(mtlsKeystorePath = dest.absolutePath) }
            onUpdated()
            com.github.itskenny0.r1ha.core.util.Toaster.show(
                "Client certificate imported. Set password and toggle mTLS to apply (restart required).",
            )
        }.onFailure { t ->
            com.github.itskenny0.r1ha.core.util.R1Log.w(
                "MtlsEditor", "import failed: ${t.message}",
            )
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Cert import failed",
                fullText = t.message ?: t.toString(),
            )
        }
    }

    Text(
        text = "MTLS CLIENT CERTIFICATE",
        style = R1.labelMicro,
        color = R1.AccentWarm,
        modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.s),
    )
    SwitchRow(
        label = "Present client certificate",
        subtitle = "Use the imported PKCS12 keystore for mutual TLS. Off by default: turning on without a known-good keystore configured will brick every request. Takes effect on next app launch.",
        checked = policy.mtlsEnabled,
        onCheckedChange = { v ->
            store.update { it.copy(mtlsEnabled = v) }
            onUpdated()
        },
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
            .clip(R1.ShapeS)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Text(
            text = if (policy.mtlsKeystorePath.isNullOrBlank()) "NO CERTIFICATE IMPORTED"
            else "KEYSTORE: ${java.io.File(policy.mtlsKeystorePath).name}",
            style = R1.labelMicro,
            color = if (policy.mtlsKeystorePath.isNullOrBlank()) R1.InkMuted else R1.Ink,
        )
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {
                    importLauncher.launch(arrayOf("application/x-pkcs12", "*/*"))
                })
                .padding(vertical = R1.space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (policy.mtlsKeystorePath.isNullOrBlank()) "IMPORT .P12" else "REPLACE .P12",
                style = R1.labelMicro,
                color = R1.AccentWarm,
            )
        }
        if (!policy.mtlsKeystorePath.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        runCatching {
                            policy.mtlsKeystorePath?.let { java.io.File(it).delete() }
                        }
                        store.update {
                            it.copy(
                                mtlsEnabled = false,
                                mtlsKeystorePath = null,
                                mtlsKeystorePassword = "",
                            )
                        }
                        onUpdated()
                        com.github.itskenny0.r1ha.core.util.Toaster.show(
                            "Certificate removed (restart to apply).",
                        )
                    })
                    .padding(horizontal = R1.space.m, vertical = R1.space.m),
            ) {
                Text(text = "REMOVE", style = R1.labelMicro, color = R1.StatusAmber)
            }
        }
    }
    // Password field: stored as plain text alongside the keystore path.
    // Documented as such in the threat-model comment on the data class.
    LabeledControl(label = "Keystore password") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                MaskedSecretField(
                    value = pendingPassword.value,
                    onValueChange = { pendingPassword.value = it },
                    placeholder = "(empty)",
                )
            }
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = {
                        store.update { it.copy(mtlsKeystorePassword = pendingPassword.value) }
                        onUpdated()
                        com.github.itskenny0.r1ha.core.util.Toaster.show(
                            "Password saved (restart to apply).",
                        )
                    })
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
            ) {
                Text(text = "SAVE", style = R1.labelMicro, color = R1.AccentWarm)
            }
        }
    }
}

/**
 * Tile-order editor for the TODAY dashboard. Each row shows the tile's human
 * label plus ↑ / ↓ pills; pressing one swaps the tile with its neighbour and
 * the VM persists the new ordering. Unknown ids (saved on a newer build,
 * decoded by an older one) render with their raw name so the user can at least
 * see them; pressing the arrows still moves them so an out-of-order downgrade
 * doesn't strand a future tile at the bottom.
 *
 * Renders inside the DASHBOARD section so the user sees tile visibility +
 * order in the same place. RESET dumps back to [DEFAULT_TILE_ORDER] without
 * touching show* flags.
 */
@Composable
private fun TileOrderEditor(
    order: List<String>,
    onMove: (Int, Int) -> Unit,
    onReset: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        order.forEachIndexed { idx, id ->
            val label = runCatching {
                com.github.itskenny0.r1ha.core.prefs.DashboardTile.valueOf(id).label
            }.getOrNull() ?: id
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = R1.space.xl, vertical = R1.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                ) {
                    Text(text = "${idx + 1}", style = R1.labelMicro, color = R1.InkMuted)
                }
                Spacer(Modifier.width(R1.space.m))
                Text(text = label, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f))
                val canUp = idx > 0
                val canDown = idx < order.lastIndex
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(R1.ShapeS)
                        .background(if (canUp) R1.SurfaceMuted else R1.Bg)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { if (canUp) onMove(idx, idx - 1) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "↑", style = R1.body, color = if (canUp) R1.Ink else R1.InkMuted)
                }
                Spacer(Modifier.width(R1.space.s))
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(R1.ShapeS)
                        .background(if (canDown) R1.SurfaceMuted else R1.Bg)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { if (canDown) onMove(idx, idx + 1) }),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "↓", style = R1.body, color = if (canDown) R1.Ink else R1.InkMuted)
                }
            }
        }
        Spacer(Modifier.height(R1.space.s))
        Box(
            modifier = Modifier
                .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = onReset)
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(text = "RESET ORDER", style = R1.labelMicro, color = R1.AccentWarm)
        }
    }
}

