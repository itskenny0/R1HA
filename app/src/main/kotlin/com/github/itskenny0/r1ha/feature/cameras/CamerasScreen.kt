package com.github.itskenny0.r1ha.feature.cameras

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.flow.first
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.CameraSnapshot
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.formatFixed
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.icons.R1IconSet

/**
 * Cameras surface: lists every `camera.*` entity HA reports and lets
 * the user tap one to see a live polling snapshot. The list view
 * shows just text rows + state chip (idle / recording / streaming /
 * unavailable). Tapping a row pushes a fullscreen overlay with the
 * snapshot polling every 4 s; on roomy windows the overlay becomes a
 * detail pane beside the list instead, so the list stays in reach.
 *
 * Why no inline thumbnails on the list: each thumbnail would be its
 * own HTTP poll, and on big installs with 8-10 cameras that's a
 * stampede. The list-as-directory + tap-to-view-one pattern keeps
 * the network usage proportional to user intent.
 */
@Composable
fun CamerasScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    tokens: TokenStore,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: CamerasViewModel = viewModel(factory = CamerasViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    val gridState = rememberLazyGridState()
    // View-mode preference: rememberSaveable so it survives orientation
    // changes and back-then-forward nav. Defaults to LIST (cheap; no
    // background polling) so big installs don't accidentally fire a
    // thumbnail-fetch stampede on first entry.
    // Default view-mode comes from the camerasDefaultGrid pref; user can
    // still flip via the LIST/GRID chips. Local override is stored as a
    // nullable string in rememberSaveable so:
    //   - first paint (no override + setting not yet loaded) → LIST
    //   - first paint (no override + setting loaded GRID) → GRID
    //   - user taps LIST/GRID → override pinned, setting no longer
    //     resets the in-screen choice until they pick "follow default"
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    var viewModeOverride by rememberSaveable { mutableStateOf<String?>(null) }
    val viewMode = viewModeOverride
        ?: if (appSettings.integrations.camerasDefaultGrid) "GRID" else "LIST"
    // Wheel scroll wired to whichever state is currently visible:
    // LIST drives listState, GRID drives gridState. Both go through
    // the WheelScrollFor* family which shares the accel + cancellation
    // profile. Switching mode swaps which composable is in
    // composition, which auto-cancels the inactive listener.
    if (viewMode == "LIST") {
        WheelScrollFor(
            wheelInput = wheelInput,
            listState = listState,
            settings = settings,
        )
    } else {
        com.github.itskenny0.r1ha.ui.components.WheelScrollForGrid(
            wheelInput = wheelInput,
            gridState = gridState,
            settings = settings,
        )
    }
    LaunchedEffect(Unit) { vm.refresh() }
    var viewingEntityId by remember { mutableStateOf<String?>(null) }
    // Server URL + token for the grid-view thumbnails; null in LIST mode
    // so we don't even attempt to fetch.
    val serverUrl by produceState<String?>(null, settings) {
        value = settings.settings.first().server?.url
    }
    val token by produceState<String?>(null, tokens) { value = tokens.load()?.accessToken }
    // Two-pane on roomy windows: the camera list stays on the left and the
    // live view composes beside it, so swapping cameras is a single tap and
    // the list never disappears. The scaffold collapses to today's
    // fullscreen-overlay swap on small tiers; the shared insets live on the
    // scaffold so neither pane double-pads.
    val twoPane = com.github.itskenny0.r1ha.ui.components.isTwoPane()
    val viewing = viewingEntityId
    com.github.itskenny0.r1ha.ui.components.R1ListDetailPane(
        hasSelection = viewing != null,
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
        detail = {
            viewing?.let { entityId ->
                val tuning = com.github.itskenny0.r1ha.core.ha.ConnectionTuning
                    .from(appSettings.connection)
                val camera = ui.cameras.firstOrNull { it.entityId == entityId }
                CameraDetailOverlay(
                    entityId = entityId,
                    displayName = camera?.name ?: entityId,
                    state = camera?.state,
                    settings = settings,
                    tokens = tokens,
                    pollSec = tuning.flooredCameraSeconds(appSettings.integrations.cameraOverlayPollSec),
                    // Strict-mode floor in millis: the overlay's refresh stepper can
                    // walk down to 200 ms, which would otherwise undercut the
                    // configured minimum camera interval. Pass it so the stepper
                    // clamps its faster bound to it.
                    minPollMillis = tuning.minCameraIntervalMillis,
                    standalone = !twoPane,
                    onDismiss = { viewingEntityId = null },
                )
            }
        },
        emptyDetail = { CamerasSummaryPane(ui.cameras) },
        list = {
    Column(modifier = Modifier.fillMaxSize()) {
        R1TopBar(title = "CAMERAS", onBack = onBack)
        // LIST / GRID toggle row. GRID auto-polls every tile (heavier);
        // LIST is text-only. Default to LIST so big installs don't fire
        // a thumbnail stampede on first entry.
        if (ui.cameras.isNotEmpty()) {
            ViewModeRow(current = viewMode, onSelect = { viewModeOverride = it })
        }
        // Single-pane: the scaffold stops composing this list while the
        // detail is up, so grid tiles don't keep polling at the grid cadence
        // behind the overlay's faster poll. Two-pane: the list is visible
        // beside the live view, so it stays composed (and polling) on purpose.
        when {
            ui.loading && ui.cameras.isEmpty() -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                // Skeleton rows give the eye a hint of "list of cameras
                // incoming" instead of a context-free centred spinner. Three
                // rows fit the R1's portrait viewport without scrolling.
                repeat(3) {
                    com.github.itskenny0.r1ha.ui.components.SkeletonRow()
                }
            }
            ui.error != null && ui.cameras.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // The camera registry fetch itself failed (auth, DNS,
                // server down). Distinct from "no cameras in HA".
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    Icon(
                        imageVector = R1IconSet.Camera,
                        contentDescription = null,
                        tint = R1.StatusRed,
                        modifier = Modifier.size(R1.space.xl),
                    )
                    Text(
                        text = "Cameras load failed: ${ui.error}",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                        color = R1.StatusRed,
                    )
                }
            }
            ui.cameras.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    Icon(
                        imageVector = R1IconSet.Camera,
                        contentDescription = null,
                        tint = R1.InkMuted,
                        modifier = Modifier.size(R1.space.xl),
                    )
                    Text(
                        text = "No cameras in HA. Add a camera integration to see them here.",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                }
            }
            viewMode == "GRID" && serverUrl != null -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Centre + width-cap the wall on big tiers so the tiles read
                // as a centred grid rather than stretching into a few giant
                // squares across a 13" panel. No-op on R1 / compact (those
                // fill edge to edge).
                com.github.itskenny0.r1ha.ui.components.R1CenteredContent {
                LazyVerticalGrid(
                    state = gridState,
                    // Column count steps up by window tier so big panels
                    // actually use the extra horizontal space instead of
                    // ballooning a handful of tiles: mini/compact stay at 2
                    // (today's R1 layout), small tablets get 3, big tablets
                    // 4, and desktop-class windows 5. Routed through the
                    // shared responsive tokens so the camera wall tracks the
                    // same breakpoints as the rest of the app.
                    columns = GridCells.Fixed(
                        com.github.itskenny0.r1ha.core.theme.R1Responsive.gridColumns(
                            com.github.itskenny0.r1ha.ui.components.LocalWindowTier.current.tier,
                        ),
                    ),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.s, vertical = R1.space.xs,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                    horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(items = ui.cameras, key = { it.entityId }) { camera ->
                        CameraTile(
                            camera = camera,
                            serverUrl = serverUrl!!,
                            bearerToken = token,
                            pollSec = com.github.itskenny0.r1ha.core.ha.ConnectionTuning
                                .from(appSettings.connection)
                                .flooredCameraSeconds(appSettings.integrations.cameraGridPollSec),
                            selected = twoPane && camera.entityId == viewing,
                            onTap = { viewingEntityId = camera.entityId },
                        )
                    }
                }
                }
            }
            viewMode == "GRID" -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                // GRID is selected but we have no server URL yet (settings
                // still loading, or no server configured). Show a real
                // placeholder instead of silently rendering the LIST view
                // while the chip claims GRID, which read as a glitch.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(R1.space.s),
                ) {
                    Icon(
                        imageVector = R1IconSet.Camera,
                        contentDescription = null,
                        tint = R1.InkMuted,
                        modifier = Modifier.size(R1.space.xl),
                    )
                    Text(
                        text = "Grid view needs a server connection. Loading…",
                        style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                }
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                // Centre + width-cap the list on big tiers so rows read as a
                // centred column instead of one row stretched the full width
                // of a wide panel. No-op on R1 / compact.
                com.github.itskenny0.r1ha.ui.components.R1CenteredContent {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.m, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(items = ui.cameras, key = { it.entityId }) { camera ->
                        CameraRow(
                            camera = camera,
                            selected = twoPane && camera.entityId == viewing,
                            onTap = { viewingEntityId = camera.entityId },
                        )
                    }
                }
                }
            }
        }
    }
        },
    )
}

/**
 * Default right-pane content in two-pane mode before any camera is selected:
 * fleet counts at a glance so the pane carries real information rather than
 * a "select something" stub.
 */
@Composable
private fun CamerasSummaryPane(cameras: List<CamerasViewModel.Camera>) {
    val streaming = cameras.count { it.state.lowercase() == "streaming" }
    val recording = cameras.count { it.state.lowercase() == "recording" }
    val offline = cameras.count { it.state.lowercase() in setOf("unavailable", "unknown") }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CAMERAS",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.sectionHeader),
                color = R1.InkSoft,
            )
            Spacer(Modifier.height(R1.space.l))
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.xl)) {
                CameraStat(value = cameras.size, label = "TOTAL")
                CameraStat(value = streaming, label = "STREAMING")
                CameraStat(value = recording, label = "RECORDING")
                CameraStat(value = offline, label = "OFFLINE")
            }
            Spacer(Modifier.height(R1.space.l))
            Text(
                text = "Select a camera for a live view.",
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun CameraStat(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.numeralM),
            color = R1.Ink,
        )
        Text(
            text = label,
            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.labelMicro),
            color = R1.InkMuted,
        )
    }
}

/**
 * Maps an HA camera state string to its display label + accent colour.
 * Shared by the LIST row chip and the GRID tile overlay badge so both
 * read the same semantics: streaming = healthy live feed (green),
 * recording = capturing (red), idle = armed-but-quiet, unavailable /
 * unknown = offline (amber). Unknown states pass through verbatim.
 */
private fun cameraStatusChip(state: String): Pair<String, Color> =
    when (state.lowercase()) {
        "streaming" -> "STREAMING" to R1.AccentGreen
        "recording" -> "RECORDING" to R1.StatusRed
        "idle" -> "IDLE" to R1.InkSoft
        "unavailable", "unknown" -> "OFFLINE" to R1.StatusAmber
        else -> state.uppercase() to R1.InkSoft
    }

@Composable
private fun ViewModeRow(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        for (mode in listOf("LIST", "GRID")) {
            val active = mode == current
            R1Chip(
                text = mode,
                variant = R1ChipVariant.Filter,
                selected = active,
                onClick = { onSelect(mode) },
                contentDescription = if (mode == "LIST") {
                    "Show cameras as a list" + if (active) ", selected" else ""
                } else {
                    "Show cameras as a polling grid" + if (active) ", selected" else ""
                },
            )
        }
    }
}

@Composable
private fun CameraTile(
    camera: CamerasViewModel.Camera,
    serverUrl: String,
    bearerToken: String?,
    pollSec: Int,
    selected: Boolean = false,
    onTap: () -> Unit,
) {
    val (statusLabel, statusColor) = cameraStatusChip(camera.state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Accent ring marks the tile whose feed is open in the two-pane
            // detail; single-pane never passes selected (the list is hidden
            // while the overlay is up).
            .then(if (selected) Modifier.border(1.dp, R1.AccentWarm, R1.ShapeS) else Modifier)
            .r1Pressable(
                onClick = onTap,
                // Reads the whole tile as a single actionable item to a
                // screen reader: name + state, rather than announcing the
                // raw bitmap contentDescription from CameraSnapshot.
                contentDescription = "${camera.name}, $statusLabel. Open live view.",
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            CameraSnapshot(
                serverUrl = serverUrl,
                bearerToken = bearerToken,
                entityId = camera.entityId,
                // Polling cadence comes from the Camera grid polling
                // setting: N tiles times this interval keeps total fetch
                // rate predictable on big installs.
                intervalMillis = pollSec * 1000L,
                modifier = Modifier.fillMaxSize(),
            )
            // State badge floats over the snapshot so the grid carries the
            // same idle / recording / streaming / offline semantics the
            // LIST chips show. Tinted so a glance separates a healthy feed
            // from an offline one without reading the text.
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(R1.space.xs)
                    .clip(R1.ShapeS)
                    .background(R1.Bg.copy(alpha = 0.7f))
                    .padding(horizontal = R1.space.xs, vertical = R1.space.xxs),
            ) {
                Text(text = statusLabel, style = R1.labelMicro, color = statusColor)
            }
            // Motion badge, matching the LIST row's MOTION indicator. Shown
            // as an icon to stay legible at tile scale; the text label rides
            // along as the a11y contentDescription. Null = the integration
            // doesn't model motion, so nothing is drawn.
            if (camera.motionDetection == true) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(R1.space.xs)
                        .clip(R1.ShapeS)
                        .background(R1.Bg.copy(alpha = 0.7f))
                        .padding(R1.space.xxs),
                ) {
                    Icon(
                        imageVector = R1IconSet.Motion,
                        contentDescription = "Motion detection armed",
                        tint = R1.AccentCool,
                        modifier = Modifier.size(R1.space.l),
                    )
                }
            }
        }
        Row(
            modifier = Modifier.padding(horizontal = R1.space.s, vertical = R1.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = camera.name,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Relative "since X" staleness, matching the LIST row, so an
            // offline/stuck tile reads as stale without opening it. Empty
            // (and so invisible) when HA gave no last_changed.
            RelativeTimeLabel(
                at = camera.lastChanged,
                color = R1.InkMuted,
                style = R1.labelMicro,
                modifier = Modifier.padding(start = R1.space.xs),
            )
        }
    }
}

@Composable
private fun CameraRow(
    camera: CamerasViewModel.Camera,
    selected: Boolean = false,
    onTap: () -> Unit,
) {
    val (label, color) = cameraStatusChip(camera.state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            // Accent ring marks the row whose feed is open in the two-pane
            // detail; single-pane never passes selected.
            .then(if (selected) Modifier.border(1.dp, R1.AccentWarm, R1.ShapeS) else Modifier)
            .r1Pressable(
                onClick = onTap,
                contentDescription = "${camera.name}, $label. Open live view.",
            )
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(end = R1.space.s)) {
            Text(
                text = camera.name,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.Ink,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = camera.entityId,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                // Relative "since X" so a stuck or offline camera reads as
                // stale at a glance. Hidden (empty) when HA gave no
                // last_changed. Decorative next to the chip, so excluded
                // from the row's spoken description above.
                RelativeTimeLabel(
                    at = camera.lastChanged,
                    color = R1.InkMuted,
                    style = R1.labelMicro,
                    modifier = Modifier.padding(end = R1.space.xs),
                )
                // MOTION badge when the integration reports motion
                // detection armed (HA's `motion_detection` attribute,
                // also surfaced by the picture-glance card). Null = the
                // integration doesn't model it, so we show nothing.
                if (camera.motionDetection == true) {
                    Icon(
                        imageVector = R1IconSet.Motion,
                        contentDescription = "Motion detection armed",
                        tint = R1.AccentCool,
                        modifier = Modifier
                            .padding(end = R1.space.xs)
                            .size(R1.space.l),
                    )
                }
                // State chip, coloured by HA state. "streaming" is the
                // healthy live-feed state. Truncates so an unusually long
                // custom state can't shove the row layout off-screen.
                Text(
                    text = label,
                    style = R1.labelMicro,
                    color = color,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CameraDetailOverlay(
    entityId: String,
    displayName: String,
    state: String?,
    settings: SettingsRepository,
    tokens: TokenStore,
    pollSec: Int,
    minPollMillis: Long = 0L,
    /** True when this is the only pane on screen (today's fullscreen
     *  overlay): show the close X and its hint. False in two-pane, where the
     *  list beside us is the way to swap cameras and Back clears the
     *  selection. */
    standalone: Boolean = true,
    onDismiss: () -> Unit,
) {
    // Back clears the selection first in both modes: single-pane that
    // dismisses the overlay, two-pane it empties the detail pane. Only the
    // next Back leaves the screen.
    BackHandler(onBack = onDismiss)
    // Floor every poll cadence at the strict-mode minimum (if set) but never
    // below the 200 ms hard floor. With strict-mode off this stays at 200 ms.
    val pollFloorMillis = maxOf(200L, minPollMillis)
    // Pull the server URL + bearer token through produceState so the
    // overlay can fetch lazily without making them mandatory params.
    val serverUrl by produceState<String?>(null, settings) {
        value = settings.settings.first().server?.url
    }
    val token by produceState<String?>(null, tokens) {
        value = tokens.load()?.accessToken
    }
    // Per-overlay live controls: refresh cadence + display rotation.
    // Seeded from the global Integrations setting but mutable here so the
    // user can crank pseudo-realtime (~200 ms) when they're actively
    // watching the feed, and rotate via the on-overlay button for cameras
    // mounted at non-zero degrees without editing the source.
    // Keyed on the entity so two-pane camera swaps reset cadence + rotation
    // the same way single-pane gets a fresh overlay per open.
    var pollMillisLive by androidx.compose.runtime.remember(entityId) {
        androidx.compose.runtime.mutableStateOf((pollSec * 1000L).coerceAtLeast(pollFloorMillis))
    }
    var rotationDegrees by androidx.compose.runtime.remember(entityId) {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    // No insets here: the list/detail scaffold already applies the screen's
    // system-bar padding around both panes.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom top bar: title + close X. R1TopBar uses NavController
            // patterns; an inline one fits the overlay model better.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(R1.MinTarget)
                    .padding(horizontal = R1.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (standalone) {
                    Box(
                        modifier = Modifier
                            .size(R1.MinTarget)
                            .clip(R1.ShapeS)
                            .r1Pressable(
                                onClick = onDismiss,
                                contentDescription = "Close live view",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "✕", style = R1.body, color = R1.InkSoft)
                    }
                    Spacer(Modifier.width(R1.space.s))
                }
                Text(
                    text = displayName.uppercase(),
                    style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.sectionHeader),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Two-pane carries the list's state chip into the detail so
                // the live view reads name + state at a glance. Single-pane
                // keeps the original chrome (state is visible on the list the
                // user just left).
                if (!standalone && state != null) {
                    val (stateLabel, stateColor) = cameraStatusChip(state)
                    Text(
                        text = stateLabel,
                        style = R1.labelMicro,
                        color = stateColor,
                        modifier = Modifier.padding(end = R1.space.s),
                    )
                }
                // 90-degree increments. Holding modulo-360 in floats stays
                // exact for the four canonical values we care about; the
                // rotate modifier inside CameraSnapshot treats anything in
                // the range as a transform.
                Box(
                    modifier = Modifier
                        .size(R1.MinTarget)
                        .clip(R1.ShapeS)
                        .r1Pressable(
                            onClick = {
                                rotationDegrees = (rotationDegrees + 90f) % 360f
                            },
                            contentDescription = "Rotate view 90 degrees",
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "↻", style = R1.body, color = R1.AccentWarm)
                }
            }
            val s = serverUrl
            // Centre + width-cap the feed and its controls on big tiers so a
            // single 16:9 (or portrait) source fits as a centred panel rather
            // than stretching the bitmap and the stepper edge to edge across a
            // wide window. No-op on R1 / compact, where it fills the panel.
            com.github.itskenny0.r1ha.ui.components.R1CenteredContent(
                modifier = Modifier.weight(1f),
            ) {
            Column(modifier = Modifier.fillMaxSize()) {
            if (s == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Loading…", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body), color = R1.InkMuted)
                }
            } else {
                // Use the available vertical space rather than locking to 16:9. Portrait
                // cameras (Reolink doorbells, baby monitors) otherwise waste ~70% of the
                // overlay below the image strip. CameraSnapshot still ContentScale.Fit's the
                // bitmap inside this box so non-16:9 sources letterbox cleanly.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = R1.space.m, vertical = R1.space.xs)
                        .clip(R1.ShapeS)
                        .border(1.dp, R1.Hairline, R1.ShapeS),
                ) {
                    CameraSnapshot(
                        serverUrl = s,
                        bearerToken = token,
                        entityId = entityId,
                        intervalMillis = pollMillisLive,
                        rotationDegrees = rotationDegrees,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(R1.space.s))
                // Refresh-rate stepper. Steps walk the practical range
                // (~realtime to "background poll") on a non-linear schedule
                // so a single tap moves meaningfully whether the user is
                // at 200 ms or 30 s. Capped at 30 s to avoid users winding
                // it into "feels broken" territory; the global setting
                // covers slower cadences for power-conscious installs.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = R1.space.m, vertical = R1.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(end = R1.space.s),
                    )
                    Box(
                        modifier = Modifier
                            .size(R1.MinTarget)
                            .clip(R1.ShapeS)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(
                                onClick = {
                                    pollMillisLive = nextRefreshStep(
                                        pollMillisLive, faster = true, floorMillis = pollFloorMillis,
                                    )
                                },
                                contentDescription = "Refresh faster",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "◀", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                    Text(
                        text = formatPollInterval(pollMillisLive),
                        style = R1.bodyEmph,
                        color = R1.AccentWarm,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = R1.space.m)
                            .semantics {
                                contentDescription = "Refresh interval ${formatPollInterval(pollMillisLive)}"
                            },
                    )
                    Box(
                        modifier = Modifier
                            .size(R1.MinTarget)
                            .clip(R1.ShapeS)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(
                                onClick = {
                                    pollMillisLive = nextRefreshStep(
                                        pollMillisLive, faster = false, floorMillis = pollFloorMillis,
                                    )
                                },
                                contentDescription = "Refresh slower",
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(text = "▶", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                }
                Text(
                    text = entityId,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = R1.space.m),
                )
                Text(
                    text = if (standalone) {
                        "Rotate ↻ · tap ✕ to close"
                    } else {
                        "Rotate ↻ · tap a camera to swap"
                    },
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = R1.space.m),
                )
            }
            }
            }
        }
    }
}

/** Non-linear step ladder for the refresh-rate picker: denser near the
 *  realtime end where small changes matter, coarser at the slow end where
 *  they don't. 200 ms is the practical floor: HA camera_proxy round-trips
 *  on LAN cluster ~80-150 ms, so polling faster than that just stacks
 *  in-flight requests. */
private val REFRESH_STEPS_MILLIS: LongArray = longArrayOf(
    200L, 333L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L,
)

private fun nextRefreshStep(current: Long, faster: Boolean, floorMillis: Long = 200L): Long {
    val idx = REFRESH_STEPS_MILLIS.indexOfFirst { it >= current }
        .let { if (it < 0) REFRESH_STEPS_MILLIS.lastIndex else it }
    val next = if (faster) (idx - 1).coerceAtLeast(0) else (idx + 1).coerceAtMost(REFRESH_STEPS_MILLIS.lastIndex)
    // Clamp the faster bound to the strict-mode floor so a tap can't poll
    // below the configured minimum camera interval. floorMillis defaults to
    // the 200 ms hard floor when strict mode isn't set.
    return REFRESH_STEPS_MILLIS[next].coerceAtLeast(floorMillis)
}

private fun formatPollInterval(millis: Long): String = when {
    millis < 1000 -> "${millis} ms"
    millis < 10_000 -> "${formatFixed(millis / 1000.0, 1)} s"
    else -> "${millis / 1000} s"
}
