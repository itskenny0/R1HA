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
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Cameras surface — lists every `camera.*` entity HA reports and lets
 * the user tap one to see a live polling snapshot. The list view
 * shows just text rows + state chip (idle / recording / streaming /
 * unavailable). Tapping a row pushes a fullscreen overlay with the
 * snapshot polling every 4 s.
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
    // View-mode preference — rememberSaveable so it survives orientation
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
    // Wheel scroll wired to whichever state is currently visible —
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
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "CAMERAS", onBack = onBack)
        // LIST / GRID toggle row. GRID auto-polls every tile (heavier);
        // LIST is text-only. Default to LIST so big installs don't fire
        // a thumbnail stampede on first entry.
        if (ui.cameras.isNotEmpty()) {
            ViewModeRow(current = viewMode, onSelect = { viewModeOverride = it })
        }
        when {
            ui.loading -> Column(
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
                Text(
                    text = "Cameras load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.cameras.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(R1.space.xl),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No cameras in HA. Add a camera integration to see them here.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            viewMode == "GRID" && serverUrl != null -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyVerticalGrid(
                    state = gridState,
                    // Column count adapts to the host width so tablets
                    // actually use the extra horizontal space — R1 stays
                    // at 2 columns (today's layout), phones stay at 2,
                    // tablets jump to 3 inside the responsive column.
                    columns = GridCells.Fixed(
                        com.github.itskenny0.r1ha.ui.layout.gridColumnsFor(),
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
                            onTap = { viewingEntityId = camera.entityId },
                        )
                    }
                }
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = R1.space.m, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(items = ui.cameras, key = { it.entityId }) { camera ->
                        CameraRow(camera, onTap = { viewingEntityId = camera.entityId })
                    }
                }
            }
        }
    }
    // Detail overlay — fullscreen snapshot polling. Back-press dismisses.
    val viewing = viewingEntityId
    if (viewing != null) {
        CameraDetailOverlay(
            entityId = viewing,
            displayName = ui.cameras.firstOrNull { it.entityId == viewing }?.name ?: viewing,
            settings = settings,
            tokens = tokens,
            pollSec = com.github.itskenny0.r1ha.core.ha.ConnectionTuning
                .from(appSettings.connection)
                .flooredCameraSeconds(appSettings.integrations.cameraOverlayPollSec),
            onDismiss = { viewingEntityId = null },
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
    onTap: () -> Unit,
) {
    val (statusLabel, statusColor) = cameraStatusChip(camera.state)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
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
        }
        Text(
            text = camera.name,
            style = R1.body,
            color = R1.Ink,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = R1.space.s, vertical = R1.space.xs),
        )
    }
}

@Composable
private fun CameraRow(camera: CamerasViewModel.Camera, onTap: () -> Unit) {
    val (label, color) = cameraStatusChip(camera.state)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(
                onClick = onTap,
                contentDescription = "${camera.name}, $label. Open live view.",
            )
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(end = R1.space.s)) {
            Text(text = camera.name, style = R1.body, color = R1.Ink, maxLines = 2)
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
                    Text(
                        text = "MOTION",
                        style = R1.labelMicro,
                        color = R1.AccentCool,
                        modifier = Modifier.padding(end = R1.space.xs),
                    )
                }
                // State chip, coloured by HA state. "streaming" is the
                // healthy live-feed state.
                Text(text = label, style = R1.labelMicro, color = color)
            }
        }
    }
}

@Composable
private fun CameraDetailOverlay(
    entityId: String,
    displayName: String,
    settings: SettingsRepository,
    tokens: TokenStore,
    pollSec: Int,
    onDismiss: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    // Pull the server URL + bearer token through produceState so the
    // overlay can fetch lazily without making them mandatory params.
    val serverUrl by produceState<String?>(null, settings) {
        value = settings.settings.first().server?.url
    }
    val token by produceState<String?>(null, tokens) {
        value = tokens.load()?.accessToken
    }
    // Per-overlay live controls — refresh cadence + display rotation.
    // Seeded from the global Integrations setting but mutable here so the
    // user can crank pseudo-realtime (~200 ms) when they're actively
    // watching the feed, and rotate via the on-overlay button for cameras
    // mounted at non-zero degrees without editing the source.
    var pollMillisLive by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf((pollSec * 1000L).coerceAtLeast(200L))
    }
    var rotationDegrees by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableFloatStateOf(0f)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Custom top bar — title + close X. R1TopBar uses NavController
            // patterns; an inline one fits the overlay model better.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(R1.MinTarget)
                    .padding(horizontal = R1.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
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
                Text(
                    text = displayName.uppercase(),
                    style = R1.sectionHeader,
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                )
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
            if (s == null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "Loading…", style = R1.body, color = R1.InkMuted)
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
                                    pollMillisLive = nextRefreshStep(pollMillisLive, faster = true)
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
                                    pollMillisLive = nextRefreshStep(pollMillisLive, faster = false)
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
                    text = "Rotate ↻ · tap ✕ to close",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = R1.space.m),
                )
            }
        }
    }
}

/** Non-linear step ladder for the refresh-rate picker — denser near the
 *  realtime end where small changes matter, coarser at the slow end where
 *  they don't. 200 ms is the practical floor: HA camera_proxy round-trips
 *  on LAN cluster ~80-150 ms, so polling faster than that just stacks
 *  in-flight requests. */
private val REFRESH_STEPS_MILLIS: LongArray = longArrayOf(
    200L, 333L, 500L, 1_000L, 2_000L, 4_000L, 8_000L, 15_000L, 30_000L,
)

private fun nextRefreshStep(current: Long, faster: Boolean): Long {
    val idx = REFRESH_STEPS_MILLIS.indexOfFirst { it >= current }
        .let { if (it < 0) REFRESH_STEPS_MILLIS.lastIndex else it }
    val next = if (faster) (idx - 1).coerceAtLeast(0) else (idx + 1).coerceAtMost(REFRESH_STEPS_MILLIS.lastIndex)
    return REFRESH_STEPS_MILLIS[next]
}

private fun formatPollInterval(millis: Long): String = when {
    millis < 1000 -> "${millis} ms"
    millis < 10_000 -> "${"%.1f".format(millis / 1000f)} s"
    else -> "${millis / 1000} s"
}
