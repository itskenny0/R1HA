package com.github.itskenny0.r1ha.feature.settings

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.iotcamera.CameraEnumerator
import com.github.itskenny0.r1ha.core.iotcamera.IotCameraStatus
import com.github.itskenny0.r1ha.core.iotcamera.discoverLanIpv4
import com.github.itskenny0.r1ha.core.prefs.IotCameraSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.SkeletonBlock
import com.github.itskenny0.r1ha.ui.components.formatFixed
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.withContext

/**
 * Settings subpage for IoT Camera Mode. Sectioned into PERMISSION, MASTER,
 * LIVE STATUS, PREVIEW, SOURCE, SINKS, IN HOME ASSISTANT, and ADVANCED.
 * Advanced is collapsed by default so the page reads as a short ladder of
 * decisions rather than a wall of fields; everything power-users want is
 * one tap away.
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
    /** Navigate to the dedicated MQTT broker config screen. Used by the
     *  "MQTT not configured" warning banner so the user can fix the
     *  prerequisite in one tap without hunting through Settings. */
    onOpenMqttSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val cam = s.iotCamera
    val context = LocalContext.current
    val clipboard: ClipboardManager = LocalClipboardManager.current

    val cameras = remember { CameraEnumerator.list(context) }
    val pickedCamera = cameras.firstOrNull { it.id == cam.cameraId }
        ?: cameras.firstOrNull()
    val supportedSizes = pickedCamera?.supportedJpegSizes ?: emptyList()

    val graph = remember(context) { (context.applicationContext as? App)?.graph }
    val statusHolder = graph?.iotCameraStatus
    val frameBus = graph?.iotCameraFrameBus
    val status by (statusHolder?.snapshot?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(IotCameraStatus.Snapshot()) })
    val subscriberCount by (frameBus?.subscriberCount?.collectAsStateWithLifecycle(initialValue = 0)
        ?: remember { mutableStateOf(0) })
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
        if (!granted) Toaster.error("Camera permission denied; sinks can't start")
    }

    var advancedExpanded by remember { mutableStateOf(false) }
    var disableArmed by remember { mutableStateOf(false) }
    LaunchedEffect(disableArmed) {
        if (disableArmed) {
            delay(3_000)
            disableArmed = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "IOT CAMERA MODE", onBack = onBack)
        // Centre + width-cap the form on tablet / desktop tiers. R1 / compact
        // stay Unspecified = fill full-bleed.
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        val listModifier = if (dimens.capsContentWidth) {
            Modifier
                .fillMaxSize()
                .widthIn(max = dimens.maxContentWidth)
                .align(Alignment.CenterHorizontally)
        } else {
            Modifier.fillMaxSize()
        }
        LazyColumn(modifier = listModifier) {
            item {
                Text(
                    text = "Turn this device into a Home Assistant camera. " +
                        "Two sinks: MJPEG over HTTP for low-latency LAN viewing, " +
                        "MQTT auto-discovery for any-network setups. Per-device, " +
                        "never synced.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.m),
                )
            }

            if (!hasCameraPermission) {
                item {
                    PermissionBanner(
                        title = "CAMERA PERMISSION MISSING",
                        body = "Streaming needs CAMERA. Tap below to show the system prompt.",
                        cta = "GRANT CAMERA",
                        accent = R1.StatusRed,
                        onClick = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    )
                }
            }

            // ── Master toggle ─────────────────────────────────────────────
            item {
                MasterToggleRow(
                    cam = cam,
                    armed = disableArmed,
                    onArm = { disableArmed = true },
                    onCommit = {
                        disableArmed = false
                        vm.updateIotCamera { it.copy(enabled = false) }
                    },
                    onEnable = {
                        if (!hasCameraPermission) {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                        vm.updateIotCamera { it.copy(enabled = true) }
                    },
                    subscriberCount = subscriberCount,
                )
            }

            // ── Live status ────────────────────────────────────────────────
            if (cam.enabled) {
                item { SectionHeader("LIVE STATUS") }
                item {
                    StatusCard(
                        status = status,
                        lanIp = lanIp,
                        subscriberCount = subscriberCount,
                    )
                }
            }

            // ── Live preview ───────────────────────────────────────────────
            item { SectionHeader("PREVIEW") }
            item { LivePreviewTile(enabled = cam.enabled) }

            // ── Source ─────────────────────────────────────────────────────
            item { SectionHeader("SOURCE") }
            item {
                LensPickerBlock(
                    cameras = cameras,
                    pickedCameraId = pickedCamera?.id,
                    onPick = { id -> vm.updateIotCamera { it.copy(cameraId = id) } },
                )
            }
            item {
                ResolutionBlock(
                    supportedSizes = supportedSizes,
                    currentWidth = cam.width,
                    currentHeight = cam.height,
                    onPick = { w, h -> vm.updateIotCamera { it.copy(width = w, height = h) } },
                )
            }
            item {
                FrameRateBlock(
                    fps = cam.fps,
                    onDec = { vm.updateIotCamera { it.copy(fps = (it.fps - 1).coerceAtLeast(1)) } },
                    onInc = { vm.updateIotCamera { it.copy(fps = it.fps + 1) } },
                )
            }
            item {
                QualityBlock(
                    quality = cam.jpegQuality,
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

            // ── Sinks ──────────────────────────────────────────────────────
            item { SectionHeader("SINKS") }
            item {
                SinkToggleRow(
                    title = "MJPEG over HTTP",
                    subtitle = "Live multipart stream on the LAN. Add as Generic Camera in HA.",
                    checked = cam.mjpegEnabled,
                    onToggle = { vm.updateIotCamera { it.copy(mjpegEnabled = !it.mjpegEnabled) } },
                )
            }
            if (cam.mjpegEnabled) {
                item {
                    MjpegConfigBlock(
                        cam = cam,
                        lanIp = lanIp,
                        clipboard = clipboard,
                        onUpdate = { tr -> vm.updateIotCamera(tr) },
                    )
                }
            }
            item {
                SinkToggleRow(
                    title = "MQTT auto-discovery",
                    subtitle = "Publishes a camera entity to HA via the shared MQTT broker.",
                    checked = cam.mqttEnabled,
                    onToggle = { vm.updateIotCamera { it.copy(mqttEnabled = !it.mqttEnabled) } },
                )
            }
            if (cam.mqttEnabled && s.advanced.mqttHost.isBlank()) {
                item {
                    PermissionBanner(
                        title = "MQTT BROKER NOT CONFIGURED",
                        body = "Configure the broker R1HA should publish to. R1HA does " +
                            "not auto-discover brokers; point it at the same one your " +
                            "Home Assistant uses.",
                        cta = "CONFIGURE BROKER",
                        accent = R1.StatusAmber,
                        onClick = onOpenMqttSettings,
                    )
                }
            }
            if (cam.mqttEnabled && s.advanced.mqttHost.isNotBlank()) {
                item {
                    BrokerSummaryRow(
                        host = s.advanced.mqttHost,
                        port = s.advanced.mqttPort,
                        tls = s.advanced.mqttUseTls,
                        onEdit = onOpenMqttSettings,
                    )
                }
            }
            if (cam.mqttEnabled) {
                item {
                    MqttConfigBlock(
                        cam = cam,
                        onUpdate = { tr -> vm.updateIotCamera(tr) },
                    )
                }
            }

            // ── In Home Assistant ─────────────────────────────────────────
            item { SectionHeader("IN HOME ASSISTANT") }
            item { HowToAddInHa(cam = cam, lanIp = lanIp, clipboard = clipboard) }

            // ── Advanced ───────────────────────────────────────────────────
            item {
                SectionHeaderToggle(
                    text = "Advanced",
                    expanded = advancedExpanded,
                    onToggle = { advancedExpanded = !advancedExpanded },
                )
            }
            if (advancedExpanded) {
                item {
                    RotationBlock(
                        rotation = cam.rotationDegrees,
                        onLeft = {
                            vm.updateIotCamera { it.copy(rotationDegrees = (it.rotationDegrees + 270) % 360) }
                        },
                        onRight = {
                            vm.updateIotCamera { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) }
                        },
                    )
                }
                item {
                    InfoCallout(
                        label = "ROTATION COST",
                        body = "Every non-zero rotation re-encodes each frame. Leave at " +
                            "0° for peak fps and let the HA viewer rotate instead.",
                    )
                }
            }

            item { Spacer(Modifier.height(R1.MinTarget)) }
        }
    }
}

// ── Section primitives ───────────────────────────────────────────────────

@Composable
private fun SectionHeader(text: String) {
    // Canonical group-header treatment (matches R1Section's title line):
    // uppercase section-header type in the accent colour with a hairline rule
    // filling the remaining width.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = R1.space.l, end = R1.space.l, top = R1.space.xl, bottom = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(R1.space.m))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionHeaderToggle(
    text: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = R1.space.l, end = R1.space.l, top = R1.space.xl, bottom = R1.space.s)
            .r1Pressable(onToggle)
            .defaultMinSize(minHeight = R1.MinTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text.uppercase(), style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(R1.space.m))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        Spacer(Modifier.width(R1.space.s))
        R1Chip(
            text = if (expanded) "HIDE" else "SHOW",
            variant = R1ChipVariant.Action,
            tone = R1.AccentWarm,
            selected = true,
            onClick = onToggle,
        )
    }
}

@Composable
private fun PermissionBanner(
    title: String,
    body: String,
    cta: String,
    accent: Color,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, accent, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Text(text = title, style = R1.labelMicro, color = accent)
        Spacer(Modifier.height(R1.space.xs))
        Text(text = body, style = R1.body, color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.m))
        R1Chip(
            text = cta,
            variant = R1ChipVariant.Action,
            tone = accent,
            selected = true,
            onClick = onClick,
        )
    }
}

@Composable
private fun InfoCallout(label: String, body: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(R1.space.xs))
        Text(text = body, style = R1.body, color = R1.InkMuted)
    }
}

// ── Master toggle ────────────────────────────────────────────────────────

@Composable
private fun MasterToggleRow(
    cam: IotCameraSettings,
    armed: Boolean,
    onArm: () -> Unit,
    onCommit: () -> Unit,
    onEnable: () -> Unit,
    subscriberCount: Int,
) {
    val hasClients = cam.enabled && subscriberCount > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (cam.enabled) R1.AccentWarm else R1.Hairline,
                R1.ShapeS,
            )
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Enable IoT Camera Mode", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = when {
                        !cam.enabled -> "Off. Camera released, sinks torn down."
                        else -> "On. Foreground service holds the camera."
                    },
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            R1Switch(
                checked = cam.enabled,
                onCheckedChange = { v ->
                    if (v) {
                        onEnable()
                    } else if (hasClients && !armed) {
                        onArm()
                    } else {
                        onCommit()
                    }
                },
            )
        }
        if (armed) {
            Spacer(Modifier.height(R1.space.m))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.StatusAmber, R1.ShapeS)
                    .padding(horizontal = R1.space.m, vertical = R1.space.m),
            ) {
                Text(
                    text = "$subscriberCount " +
                        (if (subscriberCount == 1) "subscriber" else "subscribers") +
                        " connected",
                    style = R1.labelMicro,
                    color = R1.StatusAmber,
                )
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = "Disabling stops every active stream. Tap again to confirm.",
                    style = R1.body,
                    color = R1.InkSoft,
                )
                Spacer(Modifier.height(R1.space.s))
                Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                    R1Chip(
                        text = "CONFIRM DISABLE",
                        variant = R1ChipVariant.Action,
                        tone = R1.StatusAmber,
                        selected = true,
                        onClick = onCommit,
                    )
                }
            }
        }
    }
}

// ── Status card ──────────────────────────────────────────────────────────

@Composable
private fun StatusCard(
    status: IotCameraStatus.Snapshot,
    lanIp: String?,
    subscriberCount: Int,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        StatusRow(
            label = "DEVICE IP",
            value = lanIp ?: "no network",
            tint = if (lanIp == null) R1.StatusAmber else R1.Ink,
            mono = true,
        )
        val mjpegText = when (status.mjpeg) {
            IotCameraStatus.SinkState.OFF -> "Off"
            IotCameraStatus.SinkState.STARTING -> "Starting"
            IotCameraStatus.SinkState.ACTIVE -> "Listening"
            IotCameraStatus.SinkState.FAILED -> status.mjpegError ?: "Failed"
        }
        StatusRow(label = "MJPEG", value = mjpegText, tint = sinkTint(status.mjpeg))
        val mqttText = when (status.mqtt) {
            IotCameraStatus.SinkState.OFF -> "Off"
            IotCameraStatus.SinkState.STARTING -> "Connecting"
            IotCameraStatus.SinkState.ACTIVE ->
                if (status.mqttDiscoveryPublished) "Discovery sent" else "Connected"
            IotCameraStatus.SinkState.FAILED -> status.mqttError ?: "Failed"
        }
        StatusRow(label = "MQTT", value = mqttText, tint = sinkTint(status.mqtt))
        StatusRow(
            label = "SUBSCRIBERS",
            value = subscriberCount.toString(),
            tint = if (subscriberCount > 0) R1.AccentGreen else R1.InkSoft,
            mono = true,
        )
        StatusRow(
            label = "BITRATE",
            value = formatBitrate(status.bitrateBps),
            tint = if (status.bitrateBps > 0L) R1.AccentGreen else R1.InkSoft,
            mono = true,
        )
        StatusRow(
            label = "TOTAL SENT",
            value = formatByteCount(status.bytesUploadedTotal),
            tint = R1.Ink,
            mono = true,
        )
    }
}

@Composable
private fun StatusRow(
    label: String,
    value: String,
    tint: Color,
    mono: Boolean = false,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = R1.labelMicro,
            color = R1.InkMuted,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = value,
            style = if (mono) R1.numeralS.copy(color = tint) else R1.labelMicro,
            color = tint,
        )
    }
}

private fun formatBitrate(bps: Long): String = when {
    bps <= 0L -> "0 bps"
    bps < 1_000L -> "$bps bps"
    bps < 1_000_000L -> "${bps / 1_000L} kbps"
    else -> "${formatFixed(bps / 1_000_000.0, 2)} Mbps"
}

private fun formatByteCount(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${formatFixed(bytes / 1024.0, 1)} KiB"
    bytes < 1024L * 1024L * 1024L -> "${formatFixed(bytes / (1024.0 * 1024.0), 2)} MiB"
    else -> "${formatFixed(bytes / (1024.0 * 1024.0 * 1024.0), 2)} GiB"
}

private fun sinkTint(state: IotCameraStatus.SinkState): Color = when (state) {
    IotCameraStatus.SinkState.OFF -> R1.InkSoft
    IotCameraStatus.SinkState.STARTING -> R1.AccentWarm
    IotCameraStatus.SinkState.ACTIVE -> R1.AccentGreen
    IotCameraStatus.SinkState.FAILED -> R1.StatusRed
}

// ── Lens picker ──────────────────────────────────────────────────────────

@Composable
private fun LensPickerBlock(
    cameras: List<CameraEnumerator.CameraDescriptor>,
    pickedCameraId: String?,
    onPick: (String) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        Text("Lens", style = R1.bodyEmph, color = R1.Ink)
        if (cameras.isEmpty()) {
            Text(
                "No cameras detected on this device.",
                style = R1.body,
                color = R1.StatusAmber,
                modifier = Modifier.padding(top = R1.space.xs),
            )
            return@Column
        }
        Text(
            text = "Pick the lens HA should see.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = R1.space.xxs, bottom = R1.space.s),
        )
        cameras.forEach { c ->
            val isPicked = (pickedCameraId == c.id)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = R1.space.xxs)
                    .clip(R1.ShapeS)
                    .background(if (isPicked) R1.Bg else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (isPicked) R1.AccentWarm else R1.Hairline,
                        R1.ShapeS,
                    )
                    .r1Pressable({ onPick(c.id) })
                    .defaultMinSize(minHeight = R1.MinTarget)
                    .padding(horizontal = R1.space.l, vertical = R1.space.m),
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
                        style = R1.numeralS,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(top = R1.space.xxs),
                    )
                }
                if (isPicked) {
                    R1Chip(text = "ACTIVE", variant = R1ChipVariant.Pill, tone = R1.AccentWarm)
                }
            }
        }
    }
}

// ── Resolution ───────────────────────────────────────────────────────────

@Composable
private fun ResolutionBlock(
    supportedSizes: List<android.util.Size>,
    currentWidth: Int,
    currentHeight: Int,
    onPick: (Int, Int) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        Text("Resolution", style = R1.bodyEmph, color = R1.Ink)
        Text(
            "Larger means sharper but more bandwidth.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = R1.space.xxs, bottom = R1.space.s),
        )
        if (supportedSizes.isEmpty()) {
            Text("Pick a lens first.", style = R1.body, color = R1.InkMuted)
            return@Column
        }
        val picks = supportedSizes
            .filter { it.width * 9 == it.height * 16 || it.width * 3 == it.height * 4 }
            .ifEmpty { supportedSizes }
            .take(8)
        picks.forEach { sz ->
            val isPicked = sz.width == currentWidth && sz.height == currentHeight
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = R1.space.xxs)
                    .clip(R1.ShapeS)
                    .background(if (isPicked) R1.Bg else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (isPicked) R1.AccentWarm else R1.Hairline,
                        R1.ShapeS,
                    )
                    .r1Pressable({ onPick(sz.width, sz.height) })
                    .defaultMinSize(minHeight = R1.MinTarget)
                    .padding(horizontal = R1.space.l, vertical = R1.space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${sz.width} × ${sz.height}",
                    style = R1.numeralS.copy(
                        color = if (isPicked) R1.AccentWarm else R1.Ink,
                    ),
                    color = if (isPicked) R1.AccentWarm else R1.Ink,
                    modifier = Modifier.weight(1f),
                )
                val ratio = if (sz.width * 9 == sz.height * 16) "16:9" else
                    if (sz.width * 3 == sz.height * 4) "4:3" else ""
                if (ratio.isNotEmpty()) {
                    Text(ratio, style = R1.labelMicro, color = R1.InkMuted)
                }
            }
        }
    }
}

// ── Frame rate / Quality ─────────────────────────────────────────────────

@Composable
private fun FrameRateBlock(fps: Int, onDec: () -> Unit, onInc: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Frame rate", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    "Higher is smoother; costs CPU and bandwidth.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            StepperButtons(
                valueLabel = "${fps} fps",
                onDec = onDec,
                onInc = onInc,
            )
        }
    }
}

@Composable
private fun QualityBlock(quality: Int, onDec: () -> Unit, onInc: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("JPEG quality", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    "10 to 100. About 70 is fine for surveillance use.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            StepperButtons(
                valueLabel = "$quality",
                onDec = onDec,
                onInc = onInc,
            )
        }
    }
}

@Composable
private fun RotationBlock(rotation: Int, onLeft: () -> Unit, onRight: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.s)) {
        Text("Rotation", style = R1.bodyEmph, color = R1.Ink)
        Text(
            "Burns rotation into every frame before fan-out.",
            style = R1.body,
            color = R1.InkMuted,
            modifier = Modifier.padding(top = R1.space.xxs, bottom = R1.space.s),
        )
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            GlyphButton(glyph = "↺", onClick = onLeft, description = "rotate counter-clockwise")
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = "${rotation}°",
                style = R1.numeralM.copy(color = R1.AccentWarm),
                color = R1.AccentWarm,
                modifier = Modifier.weight(1f),
            )
            GlyphButton(glyph = "↻", onClick = onRight, description = "rotate clockwise")
        }
    }
}

@Composable
private fun StepperButtons(valueLabel: String, onDec: () -> Unit, onInc: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        GlyphButton(glyph = "−", onClick = onDec, description = "decrement")
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = valueLabel,
            style = R1.numeralM.copy(color = R1.AccentWarm),
            color = R1.AccentWarm,
            modifier = Modifier
                .defaultMinSize(minWidth = 72.dp)
                .padding(horizontal = R1.space.s),
        )
        Spacer(Modifier.width(R1.space.s))
        GlyphButton(glyph = "+", onClick = onInc, description = "increment")
    }
}

@Composable
private fun GlyphButton(glyph: String, onClick: () -> Unit, description: String) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = description)
            .defaultMinSize(minWidth = R1.MinTarget, minHeight = R1.MinTarget),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, style = R1.bodyEmph, color = R1.InkSoft)
    }
}

// ── Sinks ────────────────────────────────────────────────────────────────

@Composable
private fun SinkToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (checked) R1.AccentWarm else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(
                onClick = onToggle,
                contentDescription = SettingsA11y.switchRowDescription(title, subtitle, checked),
            )
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = R1.bodyEmph, color = if (checked) R1.AccentWarm else R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted, modifier = Modifier.padding(top = R1.space.xxs))
        }
        R1Switch(checked = checked, onCheckedChange = { onToggle() })
    }
}

@Composable
private fun BrokerSummaryRow(
    host: String,
    port: Int,
    tls: Boolean,
    onEdit: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("BROKER", style = R1.labelMicro, color = R1.InkSoft)
            Text(
                text = "$host:$port" + if (tls) " · TLS" else "",
                style = R1.numeralS,
                color = R1.Ink,
                modifier = Modifier.padding(top = R1.space.xxs),
            )
        }
        R1Chip(
            text = "EDIT",
            variant = R1ChipVariant.Action,
            tone = R1.AccentWarm,
            selected = true,
            onClick = onEdit,
        )
    }
}

@Composable
private fun MjpegConfigBlock(
    cam: IotCameraSettings,
    lanIp: String?,
    clipboard: ClipboardManager,
    onUpdate: ((IotCameraSettings) -> IotCameraSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Text("PORT", style = R1.labelMicro, color = R1.InkSoft)
        R1TextField(
            value = cam.mjpegPort.toString(),
            onValueChange = { v ->
                val p = v.toIntOrNull() ?: return@R1TextField
                onUpdate { it.copy(mjpegPort = p.coerceIn(1024, 65535)) }
            },
            placeholder = "8181",
            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
        )
        Spacer(Modifier.height(R1.space.m))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Require auth", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = if (cam.mjpegAuthEnabled) {
                        "Basic auth on every request."
                    } else {
                        "Open. Anyone on the LAN can view."
                    },
                    style = R1.body,
                    color = if (cam.mjpegAuthEnabled) R1.InkMuted else R1.StatusAmber,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            R1Switch(
                checked = cam.mjpegAuthEnabled,
                onCheckedChange = { v -> onUpdate { it.copy(mjpegAuthEnabled = v) } },
            )
        }
        if (cam.mjpegAuthEnabled) {
            Spacer(Modifier.height(R1.space.m))
            Text("USERNAME", style = R1.labelMicro, color = R1.InkSoft)
            R1TextField(
                value = cam.mjpegUsername,
                onValueChange = { v -> onUpdate { it.copy(mjpegUsername = v) } },
                placeholder = "r1ha",
                modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
            )
            Spacer(Modifier.height(R1.space.s))
            Text("PASSWORD", style = R1.labelMicro, color = R1.InkSoft)
            R1TextField(
                value = cam.mjpegPassword,
                onValueChange = { v -> onUpdate { it.copy(mjpegPassword = v) } },
                placeholder = "auto-generated on first enable",
                modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
            )
            Spacer(Modifier.height(R1.space.s))
            Row(horizontalArrangement = Arrangement.spacedBy(R1.space.s)) {
                R1Chip(
                    text = "REGENERATE",
                    variant = R1ChipVariant.Action,
                    tone = R1.AccentWarm,
                    selected = true,
                    onClick = {
                        val pw = randomPassword()
                        onUpdate { it.copy(mjpegPassword = pw) }
                        Toaster.show("New MJPEG password set")
                    },
                )
                R1Chip(
                    text = "COPY URL",
                    variant = R1ChipVariant.Action,
                    tone = R1.AccentWarm,
                    selected = true,
                    onClick = {
                        val url = "http://${cam.mjpegUsername}:" +
                            "${cam.mjpegPassword.ifBlank { "<password>" }}@${lanIp ?: "<device-ip>"}:" +
                            "${cam.mjpegPort}/stream"
                        clipboard.setText(AnnotatedString(url))
                        Toaster.show("Stream URL copied")
                    },
                )
            }
        } else {
            Spacer(Modifier.height(R1.space.m))
            R1Chip(
                text = "COPY URL",
                variant = R1ChipVariant.Action,
                tone = R1.AccentWarm,
                selected = true,
                onClick = {
                    val url = "http://${lanIp ?: "<device-ip>"}:${cam.mjpegPort}/stream"
                    clipboard.setText(AnnotatedString(url))
                    Toaster.show("Stream URL copied")
                },
            )
        }
    }
}

@Composable
private fun MqttConfigBlock(
    cam: IotCameraSettings,
    onUpdate: ((IotCameraSettings) -> IotCameraSettings) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.xs)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Text("DISCOVERY PREFIX", style = R1.labelMicro, color = R1.InkSoft)
        R1TextField(
            value = cam.mqttDiscoveryPrefix,
            onValueChange = { v -> onUpdate { it.copy(mqttDiscoveryPrefix = v) } },
            placeholder = "homeassistant",
            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
        )
        Spacer(Modifier.height(R1.space.m))
        Text("NODE ID", style = R1.labelMicro, color = R1.InkSoft)
        R1TextField(
            value = cam.mqttNodeId,
            onValueChange = { v -> onUpdate { it.copy(mqttNodeId = v) } },
            placeholder = "auto-generated on first enable",
            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
        )
        Spacer(Modifier.height(R1.space.m))
        Text("ENTITY NAME", style = R1.labelMicro, color = R1.InkSoft)
        R1TextField(
            value = cam.entityName,
            onValueChange = { v -> onUpdate { it.copy(entityName = v) } },
            placeholder = "R1HA Camera",
            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
        )
    }
}

// ── How to add in HA ─────────────────────────────────────────────────────

@Composable
private fun HowToAddInHa(
    cam: IotCameraSettings,
    lanIp: String?,
    clipboard: ClipboardManager,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl, vertical = R1.space.xs)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable({ expanded = !expanded })
                .defaultMinSize(minHeight = R1.MinTarget)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("How to surface this in HA", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = "Step-by-step for each enabled sink.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            R1Chip(
                text = if (expanded) "HIDE" else "SHOW",
                variant = R1ChipVariant.Pill,
                tone = R1.AccentWarm,
            )
        }
        if (!expanded) return@Column

        Spacer(Modifier.height(R1.space.s))
        if (!cam.mjpegEnabled && !cam.mqttEnabled) {
            InfoCallout(
                label = "NO SINKS ENABLED",
                body = "Turn on MJPEG or MQTT under SINKS first.",
            )
            return@Column
        }
        if (cam.mqttEnabled) {
            HaPathCard(
                heading = "MQTT auto-discovery",
                steps = listOf(
                    "Open Settings; Devices & Services. The MQTT integration should be installed and pointed at the broker shown above.",
                    "The camera entity registers automatically. Look under MQTT; Devices for the R1HA device, then the camera entity inside it.",
                ),
                footnoteText = if (cam.mqttNodeId.isBlank()) {
                    "The entity id materialises on first successful publish. Wait for STATUS; MQTT to show \"Discovery sent\"."
                } else {
                    "Entity id: camera.r1ha_${cam.mqttNodeId}_${cam.mqttObjectId}."
                },
            )
        }
        if (cam.mjpegEnabled) {
            if (cam.mqttEnabled) Spacer(Modifier.height(R1.space.m))
            HaPathCard(
                heading = "MJPEG (Generic Camera)",
                steps = listOf(
                    "Open Settings; Devices & Services; ADD INTEGRATION; search \"Generic Camera\".",
                    "Still image URL:",
                ),
                trailingContent = {
                    CopyableUrl(
                        url = mjpegUrlFor(cam, lanIp, path = "snapshot"),
                        clipboard = clipboard,
                    )
                    Spacer(Modifier.height(R1.space.xs))
                    Text("Stream source URL:", style = R1.body, color = R1.Ink)
                    CopyableUrl(
                        url = mjpegUrlFor(cam, lanIp, path = "stream"),
                        clipboard = clipboard,
                    )
                    if (cam.mjpegAuthEnabled) {
                        Spacer(Modifier.height(R1.space.s))
                        Text(
                            text = "Username: ${cam.mjpegUsername}. Password: " +
                                if (cam.mjpegPassword.isBlank()) "(not set)" else "the password above.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    }
                    if (lanIp == null) {
                        Spacer(Modifier.height(R1.space.s))
                        Text(
                            text = "Connect to Wi-Fi or Ethernet first; no LAN IP yet.",
                            style = R1.body,
                            color = R1.StatusAmber,
                        )
                    }
                },
                footnoteText = null,
            )
        }
    }
}

@Composable
private fun HaPathCard(
    heading: String,
    steps: List<String>,
    footnoteText: String?,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Text(text = heading, style = R1.labelMicro, color = R1.AccentGreen)
        steps.forEachIndexed { i, step ->
            Row {
                Text(
                    text = "${i + 1}.",
                    style = R1.numeralS,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(end = R1.space.s),
                )
                Text(text = step, style = R1.body, color = R1.Ink)
            }
        }
        trailingContent?.invoke()
        if (footnoteText != null) {
            Text(text = footnoteText, style = R1.body, color = R1.InkMuted)
        }
    }
}

@Composable
private fun CopyableUrl(url: String, clipboard: ClipboardManager) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable({
                clipboard.setText(AnnotatedString(url))
                Toaster.show("Copied")
            })
            .defaultMinSize(minHeight = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = buildAnnotatedString {
                withStyle(R1.monoSpan.copy(color = R1.Ink)) { append(url) }
            },
            style = R1.numeralS,
            modifier = Modifier.weight(1f),
        )
        Text("COPY", style = R1.labelMicro, color = R1.AccentWarm)
    }
}

private fun mjpegUrlFor(cam: IotCameraSettings, lanIp: String?, path: String): String {
    val host = lanIp ?: "<device-ip>"
    return if (cam.mjpegAuthEnabled) {
        "http://${cam.mjpegUsername}:${cam.mjpegPassword.ifBlank { "<password>" }}@$host:${cam.mjpegPort}/$path"
    } else {
        "http://$host:${cam.mjpegPort}/$path"
    }
}

// ── Live preview ─────────────────────────────────────────────────────────

/**
 * Live preview of whatever the capture pipeline is currently emitting.
 * Subscribes to the shared [com.github.itskenny0.r1ha.core.iotcamera.FrameBus]
 * on the [com.github.itskenny0.r1ha.AppGraph], samples down to ~3 fps so we
 * don't burn the user's battery decoding 30 JPEGs per second just to feed
 * a thumbnail, and renders the decoded bitmap inside a 16:9 tile.
 *
 * SHOW PREVIEW toggle: per-session switch (default OFF). Off-default because
 * users on the settings screen are usually doing config (lens picker, port
 * edits) rather than aiming the camera; the preview burns CPU encoding
 * frames the user doesn't need to see. When off, the JPEG-decode collector
 * dies and the FrameBus loses a subscriber. That matters when no other sink
 * is connected; the capture pipeline's subscriberCount-zero gate skips
 * encoding entirely, so the default-off preview means a settings-screen-only
 * enable drops the encode workload to nothing. Not persisted: this is UI
 * affordance, not config.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
@Composable
private fun LivePreviewTile(enabled: Boolean) {
    val context = LocalContext.current
    val frameBus = remember(context) {
        (context.applicationContext as? App)?.graph?.iotCameraFrameBus
    }
    var previewOn by remember { mutableStateOf(false) }
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    var lastFrameAtMs by remember { mutableLongStateOf(0L) }
    var nowTickMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    LaunchedEffect(enabled, previewOn, frameBus) {
        if (!enabled || !previewOn || frameBus == null) {
            bitmap = null
            lastFrameAtMs = 0L
            return@LaunchedEffect
        }
        frameBus.frames
            .sample(PREVIEW_SAMPLE_MILLIS)
            .collect { jpeg ->
                val decoded = withContext(Dispatchers.Default) {
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
                    lastFrameAtMs = System.currentTimeMillis()
                }
            }
    }

    LaunchedEffect(enabled, previewOn) {
        if (!enabled || !previewOn) return@LaunchedEffect
        while (true) {
            nowTickMs = System.currentTimeMillis()
            delay(500L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Live preview", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = previewStatusText(
                        enabled = enabled,
                        previewOn = previewOn,
                        hasFrame = bitmap != null,
                        ageMs = if (lastFrameAtMs > 0L) nowTickMs - lastFrameAtMs else -1L,
                    ),
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            R1Switch(checked = previewOn, onCheckedChange = { previewOn = it })
        }
        Spacer(Modifier.height(R1.space.s))
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
                    Text("PREVIEW OFF", style = R1.labelMicro, color = R1.InkMuted)
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
                    Text("ENABLE THE STREAM TO PREVIEW", style = R1.labelMicro, color = R1.InkMuted)
                }
                else -> {
                    PreviewSkeleton()
                }
            }
        }
    }
}

@Composable
private fun PreviewSkeleton() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(R1.space.l),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.6f)
                .height(14.dp),
        )
        Spacer(Modifier.size(R1.space.s))
        SkeletonBlock(
            modifier = Modifier
                .fillMaxWidth(fraction = 0.4f)
                .height(10.dp),
        )
        Spacer(Modifier.size(R1.space.m))
        Text("WARMING UP CAMERA", style = R1.labelMicro, color = R1.InkSoft)
    }
}

private fun previewStatusText(
    enabled: Boolean,
    previewOn: Boolean,
    hasFrame: Boolean,
    ageMs: Long,
): String = when {
    !previewOn -> "Off. Toggle on to sample frames at 3 fps."
    !enabled -> "Stream is off; nothing to preview."
    !hasFrame -> "Warming up; waiting for the first frame."
    ageMs in 0..1_500 -> "Live; last frame ${ageMs} ms ago."
    ageMs in 0..30_000 -> "Stalled; last frame ${ageMs / 1000} s ago."
    else -> "No frames in a while; check the service."
}

private const val PREVIEW_SAMPLE_MILLIS = 333L

private fun randomPassword(): String {
    val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
    return (1..16).map { chars.random() }.joinToString("")
}
