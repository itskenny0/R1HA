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
import androidx.compose.runtime.produceState
import kotlinx.coroutines.flow.first
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.CameraSnapshot
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
            ui.loading -> androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
            ) {
                // Skeleton rows give the eye a hint of "list of cameras
                // incoming" instead of a context-free centred spinner. Three
                // rows fit the R1's portrait viewport without scrolling.
                repeat(3) {
                    com.github.itskenny0.r1ha.ui.components.SkeletonRow()
                }
            }
            ui.error != null && ui.cameras.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // The camera registry fetch itself failed (auth, DNS,
                // server down) — distinct from "no cameras in HA".
                Text(
                    text = "Cameras load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.cameras.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
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
                        horizontal = 8.dp, vertical = 6.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(items = ui.cameras, key = { it.entityId }) { camera ->
                        CameraTile(
                            camera = camera,
                            serverUrl = serverUrl!!,
                            bearerToken = token,
                            pollSec = appSettings.integrations.cameraGridPollSec,
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
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
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
            pollSec = appSettings.integrations.cameraOverlayPollSec,
            onDismiss = { viewingEntityId = null },
        )
    }
}

@Composable
private fun ViewModeRow(current: String, onSelect: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (mode in listOf("LIST", "GRID")) {
            val active = mode == current
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (active) R1.AccentWarm else R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSelect(mode) })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = mode,
                    style = R1.labelMicro,
                    color = if (active) R1.Bg else R1.InkSoft,
                )
            }
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
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onTap),
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
                // setting — N tiles × this interval keeps total fetch
                // rate predictable on big installs.
                intervalMillis = pollSec * 1000L,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Text(
            text = camera.name,
            style = R1.body,
            color = R1.Ink,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun CameraRow(camera: CamerasViewModel.Camera, onTap: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onTap)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(end = 8.dp)) {
            Text(text = camera.name, style = R1.body, color = R1.Ink, maxLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = camera.entityId,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                // State chip — coloured by HA state. "streaming" is the
                // healthy live-feed state.
                val (label, color) = when (camera.state.lowercase()) {
                    "streaming" -> "STREAMING" to R1.AccentGreen
                    "recording" -> "RECORDING" to R1.StatusRed
                    "idle" -> "IDLE" to R1.InkSoft
                    "unavailable", "unknown" -> "OFFLINE" to R1.StatusAmber
                    else -> camera.state.uppercase() to R1.InkSoft
                }
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
                    .height(48.dp)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(R1.ShapeS)
                        .r1Pressable(onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "✕", style = R1.body, color = R1.InkSoft)
                }
                Spacer(Modifier.width(8.dp))
                Text(text = displayName.uppercase(), style = R1.sectionHeader, color = R1.Ink)
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
                // Use the available vertical space rather than locking to 16:9 — portrait
                // cameras (Reolink doorbells, baby monitors) otherwise waste ~70% of the
                // overlay below the image strip. CameraSnapshot still ContentScale.Fit's the
                // bitmap inside this box so non-16:9 sources letterbox cleanly.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(R1.ShapeS)
                        .border(1.dp, R1.Hairline, R1.ShapeS),
                ) {
                    CameraSnapshot(
                        serverUrl = s,
                        bearerToken = token,
                        entityId = entityId,
                        intervalMillis = pollSec * 1000L,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    text = entityId,
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
                Text(
                    text = "Polling every $pollSec s · tap ✕ to close",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
}
