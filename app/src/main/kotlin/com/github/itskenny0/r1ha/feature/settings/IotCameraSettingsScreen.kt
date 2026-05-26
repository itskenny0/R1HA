package com.github.itskenny0.r1ha.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.iotcamera.IotCameraStatus
import com.github.itskenny0.r1ha.core.iotcamera.discoverLanIpv4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.iotcamera.CameraEnumerator
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Settings subpage for IoT Camera Mode. Lists detected cameras with friendly
 * labels (BACK · 26mm, FRONT · WIDE etc.) so multi-lens devices can pick a
 * specific sensor, then a resolution picker driven by what that sensor
 * actually supports, then fps + JPEG quality steppers, then independent
 * MJPEG + MQTT sink toggles with the per-sink configuration they need.
 *
 * Permission flow: the master toggle requests CAMERA at runtime if it isn't
 * already granted. A user who denies the prompt sees a warning banner with
 * a button that re-launches the system permission dialog so they're not
 * stuck inside the app's settings.
 */
@Composable
fun IotCameraSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val cam = s.iotCamera
    val context = LocalContext.current
    val clipboard: ClipboardManager = LocalClipboardManager.current

    // Detected cameras — recomputed once on entry; the list is stable
    // for a given device + firmware so we don't refresh on recomposition.
    val cameras = remember { CameraEnumerator.list(context) }
    val pickedCamera = cameras.firstOrNull { it.id == cam.cameraId }
        ?: cameras.firstOrNull()
    val supportedSizes = pickedCamera?.supportedJpegSizes ?: emptyList()

    // Live status flow + LAN IP — recomputed once on entry. We don't tick
    // the IP because flipping Wi-Fi network mid-config is rare and the user
    // can pop the screen to refresh; the live status updates from the
    // service's StateFlow without that.
    val statusHolder = remember(context) {
        (context.applicationContext as? App)?.graph?.iotCameraStatus
    }
    val status by (statusHolder?.snapshot?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(IotCameraStatus.Snapshot()) })
    val lanIp = remember { discoverLanIpv4() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED,
        )
    }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) Toaster.error("Camera permission denied — IoT camera can't start")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        R1TopBar(title = "IOT CAMERA MODE", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Turn this device into a Home Assistant camera. " +
                        "Two sinks: MJPEG over HTTP for true low-latency LAN " +
                        "livestream, MQTT auto-discovery for any-network setups. " +
                        "Off by default; per-device, never synced.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }

            if (!hasCameraPermission) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp)
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.StatusRed, R1.ShapeS)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Text(
                            text = "Camera permission not granted",
                            style = R1.bodyEmph,
                            color = R1.StatusRed,
                        )
                        Text(
                            text = "Streaming needs CAMERA. The OS prompt will appear when " +
                                "you tap below.",
                            style = R1.body,
                            color = R1.InkSoft,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable({
                                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                                })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text("GRANT CAMERA", style = R1.labelMicro, color = R1.AccentWarm)
                        }
                    }
                }
            }

            // ── Master toggle ─────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Enable IoT Camera Mode", style = R1.bodyEmph, color = R1.Ink)
                        Text(
                            text = if (cam.enabled) {
                                "ON — foreground service holds the camera"
                            } else {
                                "OFF — camera released, sinks torn down"
                            },
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    R1Switch(
                        checked = cam.enabled,
                        onCheckedChange = { v ->
                            if (v && !hasCameraPermission) {
                                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                            }
                            vm.updateIotCamera { it.copy(enabled = v) }
                        },
                    )
                }
            }

            // ── Live status ────────────────────────────────────────────────
            // Sits high in the screen so the user always sees the health
            // of each sink + the LAN IP they need to point HA at. Hidden
            // entirely when the master toggle is off — there's nothing
            // meaningful to report.
            if (cam.enabled) {
                item { StatusCard(status = status, lanIp = lanIp) }
            }

            // ── Live preview ───────────────────────────────────────────────
            item { LivePreviewTile(enabled = cam.enabled) }

            // ── Camera lens picker ────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("Camera lens", style = R1.bodyEmph, color = R1.Ink)
                    if (cameras.isEmpty()) {
                        Text(
                            "No cameras detected on this device.",
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    } else {
                        Text(
                            text = "Multi-lens devices expose every sensor as its own id " +
                                "(wide, tele, ultrawide). Pick the lens you want HA to see.",
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 1.dp, bottom = 6.dp),
                        )
                        cameras.forEach { c ->
                            val isPicked = (pickedCamera?.id == c.id)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(
                                        1.dp,
                                        if (isPicked) R1.AccentWarm else R1.Hairline,
                                        R1.ShapeS,
                                    )
                                    .r1Pressable({
                                        vm.updateIotCamera { it.copy(cameraId = c.id) }
                                    })
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        c.label,
                                        style = R1.bodyEmph,
                                        color = if (isPicked) R1.AccentWarm else R1.Ink,
                                    )
                                    Text(
                                        c.description,
                                        style = R1.body,
                                        color = R1.InkMuted,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                                if (isPicked) {
                                    Text("●", style = R1.bodyEmph, color = R1.AccentWarm)
                                }
                            }
                        }
                    }
                }
            }

            // ── Resolution picker ─────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("Resolution", style = R1.bodyEmph, color = R1.Ink)
                    Text(
                        "Pulled from the selected lens. Larger = sharper but more bandwidth.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(top = 1.dp, bottom = 6.dp),
                    )
                    if (supportedSizes.isEmpty()) {
                        Text(
                            "Pick a camera first.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    } else {
                        // Cap the offered choices — most lenses expose 20+
                        // sizes, many of which are esoteric (1024×768 4:3
                        // crops etc.). Down-sample to common 16:9 + 4:3
                        // resolutions the user is likely to recognise.
                        val picks = supportedSizes
                            .filter { it.width * 9 == it.height * 16 || it.width * 3 == it.height * 4 }
                            .ifEmpty { supportedSizes }
                            .take(8)
                        picks.forEach { sz ->
                            val isPicked = sz.width == cam.width && sz.height == cam.height
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(
                                        1.dp,
                                        if (isPicked) R1.AccentWarm else R1.Hairline,
                                        R1.ShapeS,
                                    )
                                    .r1Pressable({
                                        vm.updateIotCamera {
                                            it.copy(width = sz.width, height = sz.height)
                                        }
                                    })
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "${sz.width} × ${sz.height}",
                                    style = R1.body,
                                    color = if (isPicked) R1.AccentWarm else R1.Ink,
                                    modifier = Modifier.weight(1f),
                                )
                                if (isPicked) {
                                    Text("●", style = R1.bodyEmph, color = R1.AccentWarm)
                                }
                            }
                        }
                    }
                }
            }

            // ── Frame rate ────────────────────────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("Frame rate", style = R1.bodyEmph, color = R1.Ink)
                    Text(
                        "Higher = smoother but more CPU + bandwidth. No cap — pick what your " +
                            "hardware can sustain.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(top = 1.dp, bottom = 6.dp),
                    )
                    StepperRow(
                        value = "${cam.fps} fps",
                        onDec = {
                            vm.updateIotCamera { it.copy(fps = (it.fps - 1).coerceAtLeast(1)) }
                        },
                        onInc = {
                            vm.updateIotCamera { it.copy(fps = it.fps + 1) }
                        },
                    )
                }
            }

            // ── JPEG quality (bitrate dial) ───────────────────────────────
            item {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
                ) {
                    Text("JPEG quality", style = R1.bodyEmph, color = R1.Ink)
                    Text(
                        "1-100. Combined with resolution + fps this is your bitrate dial; " +
                            "70 is a sensible default for surveillance-style streams.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(top = 1.dp, bottom = 6.dp),
                    )
                    StepperRow(
                        value = "${cam.jpegQuality}",
                        onDec = {
                            vm.updateIotCamera {
                                it.copy(jpegQuality = (it.jpegQuality - 5).coerceAtLeast(10))
                            }
                        },
                        onInc = {
                            vm.updateIotCamera {
                                it.copy(jpegQuality = (it.jpegQuality + 5).coerceAtMost(100))
                            }
                        },
                    )
                }
            }

            // ── MJPEG sink ─────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MJPEG over HTTP", style = R1.bodyEmph, color = R1.Ink)
                        Text(
                            "Live multipart stream on the LAN. Add as 'generic' camera in HA.",
                            style = R1.body,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    R1Switch(
                        checked = cam.mjpegEnabled,
                        onCheckedChange = { v ->
                            vm.updateIotCamera { it.copy(mjpegEnabled = v) }
                        },
                    )
                }
            }
            if (cam.mjpegEnabled) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                        Text("Port", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = cam.mjpegPort.toString(),
                            onValueChange = { v ->
                                val p = v.toIntOrNull() ?: return@R1TextField
                                vm.updateIotCamera { it.copy(mjpegPort = p.coerceIn(1024, 65535)) }
                            },
                            placeholder = "8181",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Require auth", style = R1.bodyEmph, color = R1.Ink)
                                Text(
                                    text = if (cam.mjpegAuthEnabled) {
                                        "Basic auth on every request (recommended)"
                                    } else {
                                        "Open — anyone on the LAN can view the stream"
                                    },
                                    style = R1.body,
                                    color = if (cam.mjpegAuthEnabled) R1.InkMuted else R1.StatusAmber,
                                    modifier = Modifier.padding(top = 1.dp),
                                )
                            }
                            R1Switch(
                                checked = cam.mjpegAuthEnabled,
                                onCheckedChange = { v ->
                                    vm.updateIotCamera { it.copy(mjpegAuthEnabled = v) }
                                },
                            )
                        }
                        if (cam.mjpegAuthEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Text("Username", style = R1.labelMicro, color = R1.InkSoft)
                            R1TextField(
                                value = cam.mjpegUsername,
                                onValueChange = { v ->
                                    vm.updateIotCamera { it.copy(mjpegUsername = v) }
                                },
                                placeholder = "r1ha",
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Text("Password", style = R1.labelMicro, color = R1.InkSoft)
                            R1TextField(
                                value = cam.mjpegPassword,
                                onValueChange = { v ->
                                    vm.updateIotCamera { it.copy(mjpegPassword = v) }
                                },
                                placeholder = "auto-generated on first enable",
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            )
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .clip(R1.ShapeS)
                                        .background(R1.SurfaceMuted)
                                        .border(1.dp, R1.Hairline, R1.ShapeS)
                                        .r1Pressable({
                                            val pw = randomPassword()
                                            vm.updateIotCamera { it.copy(mjpegPassword = pw) }
                                            Toaster.show("New MJPEG password set")
                                        })
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text("REGEN PASSWORD", style = R1.labelMicro, color = R1.AccentWarm)
                                }
                                Box(
                                    modifier = Modifier
                                        .clip(R1.ShapeS)
                                        .background(R1.SurfaceMuted)
                                        .border(1.dp, R1.Hairline, R1.ShapeS)
                                        .r1Pressable({
                                            val url = "http://${cam.mjpegUsername}:" +
                                                "${cam.mjpegPassword}@${lanIp ?: "<device-ip>"}:" +
                                                "${cam.mjpegPort}/stream"
                                            clipboard.setText(AnnotatedString(url))
                                            Toaster.show("Stream URL template copied")
                                        })
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                ) {
                                    Text("COPY URL", style = R1.labelMicro, color = R1.AccentWarm)
                                }
                            }
                        } else {
                            Spacer(Modifier.height(8.dp))
                            Box(
                                modifier = Modifier
                                    .clip(R1.ShapeS)
                                    .background(R1.SurfaceMuted)
                                    .border(1.dp, R1.Hairline, R1.ShapeS)
                                    .r1Pressable({
                                        val url = "http://${lanIp ?: "<device-ip>"}:${cam.mjpegPort}/stream"
                                        clipboard.setText(AnnotatedString(url))
                                        Toaster.show("Stream URL template copied")
                                    })
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                Text("COPY URL", style = R1.labelMicro, color = R1.AccentWarm)
                            }
                        }
                    }
                }
            }

            // ── MQTT sink ─────────────────────────────────────────────────
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("MQTT auto-discovery", style = R1.bodyEmph, color = R1.Ink)
                        Text(
                            text = if (s.advanced.mqttHost.isBlank()) {
                                "Configure broker under Advanced → MQTT first"
                            } else {
                                "Publishes config + frames to ${s.advanced.mqttHost}"
                            },
                            style = R1.body,
                            color = if (s.advanced.mqttHost.isBlank()) R1.StatusAmber else R1.InkMuted,
                            modifier = Modifier.padding(top = 1.dp),
                        )
                    }
                    R1Switch(
                        checked = cam.mqttEnabled,
                        enabled = s.advanced.mqttHost.isNotBlank(),
                        onCheckedChange = { v ->
                            vm.updateIotCamera { it.copy(mqttEnabled = v) }
                        },
                    )
                }
            }
            if (cam.mqttEnabled) {
                item {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 4.dp)) {
                        Text("Discovery prefix", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = cam.mqttDiscoveryPrefix,
                            onValueChange = { v ->
                                vm.updateIotCamera { it.copy(mqttDiscoveryPrefix = v) }
                            },
                            placeholder = "homeassistant",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Node id (per device)", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = cam.mqttNodeId,
                            onValueChange = { v ->
                                vm.updateIotCamera { it.copy(mqttNodeId = v) }
                            },
                            placeholder = "auto-generated on first enable",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("Entity name (HA label)", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = cam.entityName,
                            onValueChange = { v ->
                                vm.updateIotCamera { it.copy(entityName = v) }
                            },
                            placeholder = "R1HA Camera",
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        )
                    }
                }
            }

            // ── How to add in HA ──────────────────────────────────────────
            item { Spacer(Modifier.height(20.dp)) }
            item { HowToAddInHa(cam = cam, lanIp = lanIp) }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/**
 * At-a-glance health of each enabled sink, plus the LAN IPv4 we want the
 * user to point HA at for MJPEG. Updates from [IotCameraStatus]'s flow as
 * the service starts / fails / publishes the discovery payload.
 */
@Composable
private fun StatusCard(status: IotCameraStatus.Snapshot, lanIp: String?) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("STATUS", style = R1.labelMicro, color = R1.InkSoft)
        StatusRow(
            label = "DEVICE IP",
            value = lanIp ?: "no network",
            tint = if (lanIp == null) R1.StatusAmber else R1.Ink,
        )
        val mjpegText = when (status.mjpeg) {
            IotCameraStatus.SinkState.OFF -> "Off"
            IotCameraStatus.SinkState.STARTING -> "Starting…"
            IotCameraStatus.SinkState.ACTIVE -> "Listening"
            IotCameraStatus.SinkState.FAILED -> status.mjpegError ?: "Failed"
        }
        StatusRow(
            label = "MJPEG",
            value = mjpegText,
            tint = sinkTint(status.mjpeg),
        )
        val mqttText = when (status.mqtt) {
            IotCameraStatus.SinkState.OFF -> "Off"
            IotCameraStatus.SinkState.STARTING -> "Connecting…"
            IotCameraStatus.SinkState.ACTIVE ->
                if (status.mqttDiscoveryPublished) "Connected · discovery sent" else "Connected"
            IotCameraStatus.SinkState.FAILED -> status.mqttError ?: "Failed"
        }
        StatusRow(
            label = "MQTT",
            value = mqttText,
            tint = sinkTint(status.mqtt),
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    tint: androidx.compose.ui.graphics.Color,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(text = value, style = R1.labelMicro, color = tint)
    }
}

private fun sinkTint(state: IotCameraStatus.SinkState): androidx.compose.ui.graphics.Color =
    when (state) {
        IotCameraStatus.SinkState.OFF -> R1.InkSoft
        IotCameraStatus.SinkState.STARTING -> R1.AccentWarm
        IotCameraStatus.SinkState.ACTIVE -> R1.AccentGreen
        IotCameraStatus.SinkState.FAILED -> R1.StatusRed
    }

/**
 * Expandable walk-through of how to surface the camera inside Home
 * Assistant. Two paths because the two sinks reach HA very differently:
 *
 *   - MQTT discovery is HA's first-class auto-discover path; if the MQTT
 *     integration is installed and pointed at the same broker R1HA is
 *     publishing to, the camera entity registers itself.
 *
 *   - MJPEG has no native auto-discovery in HA; you add it manually as
 *     a Generic Camera with the URL we built for you.
 *
 * Body is rendered as plain numbered steps; the URL row is tappable to
 * copy. Kept inline rather than a separate route so the user doesn't
 * lose the context of which toggles they set.
 */
@Composable
private fun HowToAddInHa(
    cam: com.github.itskenny0.r1ha.core.prefs.IotCameraSettings,
    lanIp: String?,
) {
    var expanded by remember { mutableStateOf(false) }
    val clipboard: ClipboardManager = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable({ expanded = !expanded })
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "WHERE TO FIND THIS IN HA",
                style = R1.labelMicro,
                color = R1.AccentWarm,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (expanded) "−" else "+",
                style = R1.bodyEmph,
                color = R1.AccentWarm,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(8.dp))
            if (cam.mqttEnabled) {
                Text("MQTT AUTO-DISCOVERY", style = R1.labelMicro, color = R1.AccentGreen)
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. In HA: Settings → Devices & Services. Confirm the MQTT " +
                        "integration is installed and shows the same broker " +
                        "R1HA is publishing to (Advanced → MQTT).",
                    style = R1.body,
                    color = R1.Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "2. The entity registers automatically as " +
                        "camera.r1ha_${cam.mqttNodeId.ifBlank { "<nodeid>" }}_${cam.mqttObjectId} " +
                        "under MQTT → Devices → \"R1HA " +
                        "${cam.mqttNodeId.ifBlank { "<nodeid>" }}\".",
                    style = R1.body,
                    color = R1.Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "If the STATUS card above doesn't say \"discovery sent\", " +
                        "the broker isn't reachable yet. Check Advanced → MQTT.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
                Spacer(Modifier.height(12.dp))
            }
            if (cam.mjpegEnabled) {
                Text(
                    "MJPEG (GENERIC CAMERA)",
                    style = R1.labelMicro,
                    color = R1.AccentGreen,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "HA can't auto-discover arbitrary MJPEG streams; add it " +
                        "manually:",
                    style = R1.body,
                    color = R1.Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "1. HA → Settings → Devices & Services → + ADD INTEGRATION → " +
                        "search \"Generic Camera\".",
                    style = R1.body,
                    color = R1.Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "2. Still Image URL:",
                    style = R1.body,
                    color = R1.Ink,
                )
                CopyableUrlRow(
                    url = mjpegUrlFor(cam, lanIp, path = "snapshot"),
                    clipboard = clipboard,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "3. Stream Source URL:",
                    style = R1.body,
                    color = R1.Ink,
                )
                CopyableUrlRow(
                    url = mjpegUrlFor(cam, lanIp, path = "stream"),
                    clipboard = clipboard,
                )
                if (cam.mjpegAuthEnabled) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "4. Username: ${cam.mjpegUsername} · Password: " +
                            (if (cam.mjpegPassword.isBlank()) "(not set)" else "the password above"),
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                if (lanIp == null) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Connect this device to Wi-Fi or Ethernet first — we " +
                            "couldn't detect a LAN IP to put in the URL.",
                        style = R1.body,
                        color = R1.StatusAmber,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }
            if (!cam.mjpegEnabled && !cam.mqttEnabled) {
                Text(
                    "No sinks enabled yet. Turn on MJPEG and/or MQTT above first.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            Text(
                "The camera also shows up under R1HA → Cameras once HA has " +
                    "the entity, since that screen lists HA camera entities.",
                style = R1.body,
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun CopyableUrlRow(url: String, clipboard: ClipboardManager) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable({
                clipboard.setText(AnnotatedString(url))
                Toaster.show("Copied")
            })
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = url,
            style = R1.body,
            color = R1.Ink,
            modifier = Modifier.weight(1f),
        )
        Text("COPY", style = R1.labelMicro, color = R1.AccentWarm)
    }
}

private fun mjpegUrlFor(
    cam: com.github.itskenny0.r1ha.core.prefs.IotCameraSettings,
    lanIp: String?,
    path: String,
): String {
    val host = lanIp ?: "<device-ip>"
    return if (cam.mjpegAuthEnabled) {
        "http://${cam.mjpegUsername}:${cam.mjpegPassword.ifBlank { "<password>" }}@$host:${cam.mjpegPort}/$path"
    } else {
        "http://$host:${cam.mjpegPort}/$path"
    }
}

/**
 * Live preview of whatever the capture pipeline is currently emitting.
 * Subscribes to the shared [com.github.itskenny0.r1ha.core.iotcamera.FrameBus]
 * on the [com.github.itskenny0.r1ha.AppGraph], samples down to ~3 fps so we
 * don't burn the user's battery decoding 30 JPEGs per second just to feed
 * a thumbnail, and renders the decoded bitmap inside a 16:9 tile.
 *
 * SHOW PREVIEW toggle: per-session switch (default ON). When off, the tile
 * still renders so the section doesn't pop in/out as the user fiddles, but
 * the JPEG-decode collector dies and the FrameBus loses a subscriber. That
 * matters when *no* other sink is connected — the capture pipeline's
 * subscriberCount-zero gate skips encoding entirely, so flipping preview
 * off mid-config drops the encode workload to nothing on a settings-screen-
 * only enable. Not persisted: this is UI affordance, not config.
 *
 * Tile states (when preview-on):
 *   - master OFF → grey placeholder with "ENABLE TO PREVIEW" hint
 *   - master ON but no frame yet → "STARTING…" while the camera warms up
 *   - frame available → live JPEG, repainting at ~3 fps
 *
 * The collector also dies if the composable leaves composition (back-stack
 * pop, screen rotate) thanks to LaunchedEffect's structured cancel.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun LivePreviewTile(enabled: Boolean) {
    val context = LocalContext.current
    val frameBus = remember(context) {
        (context.applicationContext as? App)?.graph?.iotCameraFrameBus
    }
    var previewOn by remember { mutableStateOf(true) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(enabled, previewOn, frameBus) {
        if (!enabled || !previewOn || frameBus == null) {
            bitmap = null
            return@LaunchedEffect
        }
        // sample() takes the latest emission per window so we sip from the
        // stream at preview cadence rather than draining every frame. 333
        // ms = ~3 fps, plenty for "is the camera pointed at the right
        // thing" verification without churning the GC.
        frameBus.frames
            .sample(PREVIEW_SAMPLE_MILLIS)
            .collect { jpeg ->
                val decoded = withContext(Dispatchers.Default) {
                    // Subsample to ~480 px wide max — preview tile is
                    // capped at ~360 dp on a phone, so any larger source
                    // wastes memory on pixels we'll never paint. The
                    // simple inSampleSize heuristic avoids parsing the
                    // header twice on every frame.
                    val opts = BitmapFactory.Options().apply {
                        inSampleSize = 2
                        inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
                    }
                    runCatching {
                        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, opts)?.asImageBitmap()
                    }.getOrNull()
                }
                if (decoded != null) {
                    bitmap = decoded
                }
            }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "PREVIEW",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (previewOn) "SHOW" else "HIDE",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(end = 8.dp),
            )
            R1Switch(
                checked = previewOn,
                onCheckedChange = { previewOn = it },
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS),
            contentAlignment = Alignment.Center,
        ) {
            val img = bitmap
            when {
                !previewOn -> {
                    Text(
                        "PREVIEW HIDDEN",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                img != null -> {
                    Image(
                        bitmap = img,
                        contentDescription = "Live camera preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                !enabled -> {
                    Text(
                        "ENABLE TO PREVIEW",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                else -> {
                    Text(
                        "STARTING…",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
            }
        }
    }
}

private const val PREVIEW_SAMPLE_MILLIS = 333L

@Composable
private fun StepperRow(value: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onDec)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("−", style = R1.bodyEmph, color = R1.InkSoft) }
        Spacer(Modifier.width(8.dp))
        Text(
            text = value,
            style = R1.bodyEmph,
            color = R1.AccentWarm,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onInc)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) { Text("+", style = R1.bodyEmph, color = R1.InkSoft) }
    }
}

private fun randomPassword(): String {
    // 16-char alphanumeric. Sufficient entropy against LAN brute-force on
    // an HTTP server that's auth-throttled by accept latency anyway.
    val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..16).map { chars.random() }.joinToString("")
}
