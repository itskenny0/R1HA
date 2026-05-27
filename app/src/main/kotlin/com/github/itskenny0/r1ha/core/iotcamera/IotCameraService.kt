package com.github.itskenny0.r1ha.core.iotcamera

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.prefs.IotCameraSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/**
 * Foreground service that owns the camera capture session + both sinks
 * (MJPEG HTTP server, MQTT publisher) for IoT Camera Mode. Started by
 * App.onCreate's observer when [IotCameraSettings.enabled] flips true,
 * stopped when it flips false. While running, posts a persistent
 * notification — required on Android 8+ for any indefinitely-running
 * service, and explicitly required at startForeground time for the
 * "camera" foreground-service type on Android 14+.
 *
 * Settings observer: the service watches the live [IotCameraSettings]
 * flow and tears down + rebuilds the pipeline when any of the structural
 * fields change (camera id, resolution, fps, sink toggles, MQTT broker
 * config). The notification updates in-place so the user sees the
 * current state without an extra tap.
 *
 * Permission check: CAMERA permission is the user's responsibility to
 * grant before flipping the master toggle. If it's missing at start
 * time we surface a notification + toast and stop ourselves; the
 * settings UI prompts the user to grant the permission and re-enable.
 */
class IotCameraService : Service() {

    private val bus: FrameBus by lazy { (application as App).graph.iotCameraFrameBus }
    private val status: IotCameraStatus by lazy { (application as App).graph.iotCameraStatus }
    private var capture: CameraCapture? = null
    private var mjpeg: MjpegServer? = null
    private var mqtt: MqttStreamSession? = null
    private var mqttJob: Job? = null
    private var bitrateJob: Job? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    @Volatile private var currentConfig: ServiceConfig? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification(initial = true))
        // Observe the live settings flow ourselves so the user can edit
        // resolution / fps / sink toggles from the settings screen and
        // see the stream re-spin without toggling the master switch off
        // and back on.
        val graph = (application as App).graph
        serviceScope.launch {
            graph.settings.settings
                .map { ServiceConfig.from(it.iotCamera, it.advanced) }
                .distinctUntilChanged()
                .collect { cfg -> applyConfig(cfg) }
        }
        // Rolling bitrate ticker. Samples the cumulative byte counter
        // every BITRATE_WINDOW_MS and reports the delta as bits/sec, so
        // the settings status card has a glanceable readout that
        // reflects the user's chosen fps / quality / sink combo. Lives
        // on serviceScope so it dies cleanly with the service.
        bitrateJob = serviceScope.launch {
            var lastBytes = status.snapshot.value.bytesUploadedTotal
            var lastAtNanos = System.nanoTime()
            while (true) {
                kotlinx.coroutines.delay(BITRATE_WINDOW_MS)
                val nowBytes = status.snapshot.value.bytesUploadedTotal
                val nowNanos = System.nanoTime()
                val deltaBytes = (nowBytes - lastBytes).coerceAtLeast(0L)
                val deltaSec = ((nowNanos - lastAtNanos) / 1_000_000_000.0).coerceAtLeast(0.001)
                val bps = (deltaBytes * 8.0 / deltaSec).toLong()
                status.setBitrate(bps)
                lastBytes = nowBytes
                lastAtNanos = nowNanos
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // We don't reinitialise on every startCommand — the settings flow
        // is the source of truth, and reissuing the same start intent
        // shouldn't tear anything down. Just keep us alive.
        return START_STICKY
    }

    override fun onDestroy() {
        teardownPipeline(currentConfig)
        status.reset()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /** Convert the live [IotCameraSettings] + broker config into the bag
     *  the pipeline cares about, then teardown + rebuild only when something
     *  actually changed. Suspend so the inter-camera HAL-settle delay
     *  can run without blocking the upstream settings collector. */
    private suspend fun applyConfig(cfg: ServiceConfig) {
        val prev = currentConfig
        currentConfig = cfg
        if (prev == cfg) return
        // Pass the previous config to teardown so MQTT can clear its
        // retained discovery payload from the broker — without that, HA
        // keeps showing the camera as if it were still publishing because
        // the broker replays the retained config to every subscriber and
        // the camera entity sticks around with the last cached frame.
        teardownPipeline(prev)
        status.reset()
        if (!cfg.enabled) {
            updateNotification("Idle — no sinks enabled")
            return
        }
        // Inter-camera settle. Some Xiaomi HAL revisions hold exclusive
        // locks on adjacent camera ids for a moment after the previous
        // session closes; jumping straight from id 2 to id 3 in those
        // configurations yields "ERROR_CAMERA_IN_USE" on the new open,
        // and the user perceives it as "id 2 broke all the other
        // lenses". A short pause when switching cameras lets the HAL
        // finish releasing before we ask for the next one. Skipped on
        // first start (no prev) and when the id didn't change (any
        // other field change like resolution / fps already torn down
        // the same camera id, no cross-camera contention to worry
        // about).
        if (prev != null && prev.enabled && prev.cameraId != cfg.cameraId) {
            kotlinx.coroutines.delay(HAL_SETTLE_MS)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            R1Log.w("IotCamera.service", "CAMERA permission not granted — aborting start")
            updateNotification("CAMERA permission required — open settings to grant")
            return
        }
        val cameraId = cfg.cameraId.ifBlank {
            CameraEnumerator.pickDefault(this)
        } ?: run {
            updateNotification("No camera available on this device")
            return
        }
        // Capture is shared by both sinks; spin it up regardless of which
        // sinks are active. The encode-when-subscribed gate inside the
        // capture pipeline means a config with master=on and both sinks=off
        // is cheap (camera open, no encode work, no broadcast).
        capture = CameraCapture(
            // applicationContext to dodge the Camera2-mContext leak path
            // (CameraDeviceImpl pins its construction context for the
            // lifetime of the native session, which on Xiaomi keeps
            // leaking the Service even after we close()).
            context = applicationContext,
            cameraId = cameraId,
            width = cfg.width,
            height = cfg.height,
            targetFps = cfg.fps,
            jpegQuality = cfg.jpegQuality,
            rotationDegrees = cfg.rotationDegrees,
            bus = bus,
            onError = { msg ->
                R1Log.w("IotCamera.service", "capture error: $msg")
                updateNotification("Camera error: $msg")
            },
        ).also { it.start() }
        // Two ways the MJPEG server can run: with auth (requires a
        // password) or without (anyone on the LAN can hit it). The
        // pwd-blank check only gates the auth path so a user who
        // explicitly opts out of auth doesn't get blocked by an empty
        // password field they'll never use.
        if (cfg.mjpegEnabled && (!cfg.mjpegAuthEnabled || cfg.mjpegPassword.isNotBlank())) {
            status.setMjpeg(IotCameraStatus.SinkState.STARTING)
            mjpeg = MjpegServer(
                port = cfg.mjpegPort,
                authRequired = cfg.mjpegAuthEnabled,
                username = cfg.mjpegUsername,
                password = cfg.mjpegPassword,
                frames = bus.frames,
                latestFrame = { bus.latest() },
                onBytesEgressed = { delta -> status.addBytesUploaded(delta) },
            ).also { it.start() }
            // Bind-success isn't a callback we can observe directly from
            // the server, but the ServerSocket constructor inside start()
            // would have thrown if the port was busy and that would have
            // surfaced via the catch in the accept thread. For the status
            // we report ACTIVE as soon as we've spun the server up — the
            // user-facing "is it reachable" check is in the settings copy
            // alongside the URL ("Test in a browser").
            status.setMjpeg(IotCameraStatus.SinkState.ACTIVE)
        } else if (cfg.mjpegEnabled) {
            status.setMjpeg(
                IotCameraStatus.SinkState.FAILED,
                "Auth on but password is blank",
            )
        }
        if (cfg.mqttEnabled && cfg.mqttHost.isNotBlank()) {
            status.setMqtt(IotCameraStatus.SinkState.STARTING)
            mqtt = MqttStreamSession(
                host = cfg.mqttHost,
                port = cfg.mqttPort,
                clientId = "r1ha-cam-${cfg.mqttNodeId.ifBlank { "default" }}",
                username = cfg.mqttUsername.ifBlank { null },
                password = cfg.mqttPassword.ifBlank { null },
                useTls = cfg.mqttUseTls,
            )
            // start() returns false on initial connect failure (broker
            // unreachable, bad creds). Surface that synchronously so the
            // settings screen can show "Broker unreachable" instead of a
            // perpetual STARTING.
            val mqttUp = mqtt?.start() ?: false
            if (!mqttUp) {
                status.setMqtt(
                    IotCameraStatus.SinkState.FAILED,
                    "Couldn't connect to ${cfg.mqttHost}:${cfg.mqttPort}",
                )
            } else {
                status.setMqtt(IotCameraStatus.SinkState.ACTIVE)
                publishMqttDiscovery(cfg)
            }
            mqttJob = serviceScope.launch {
                bus.frames.collect { jpeg ->
                    val ok = mqtt?.publish(mqttImageTopic(cfg), jpeg, retain = false) ?: false
                    if (ok) status.addBytesUploaded(jpeg.size.toLong())
                }
            }
        } else if (cfg.mqttEnabled) {
            status.setMqtt(
                IotCameraStatus.SinkState.FAILED,
                "Configure broker under Advanced → MQTT first",
            )
        }
        updateNotification(stateSummary(cfg))
    }

    private fun teardownPipeline(prev: ServiceConfig? = null) {
        mqttJob?.cancel()
        mqttJob = null
        // If we had MQTT auto-discovery published, retract the retained
        // config payload first by publishing an empty retained message to
        // the same topic. HA convention: an empty payload to a discovery
        // topic removes the auto-discovered entity. Without this, the
        // entity sticks around in HA with the last cached frame even
        // though we've stopped publishing — exactly the "still streaming"
        // bug users hit when toggling off the master switch.
        prev?.let { unpublishMqttDiscovery(it) }
        runCatching { mqtt?.stop() }
        mqtt = null
        runCatching { mjpeg?.stop() }
        mjpeg = null
        runCatching { capture?.shutdown() }
        // shutdown() also kills the HandlerThread, so the old instance
        // can't keep a background looper alive after we replace it. Null
        // out so applyConfig's next assignment is the only live capture.
        capture = null
    }

    private fun mqttImageTopic(cfg: ServiceConfig): String =
        "r1ha/${cfg.mqttNodeId.ifBlank { "default" }}/${cfg.mqttObjectId}/image"

    /**
     * Inverse of [publishMqttDiscovery]: publish an empty retained payload
     * to the discovery topic, which HA interprets as "remove this auto-
     * discovered entity". Runs synchronously here (not via serviceScope)
     * because by the time this is called, mqtt.stop() is about to fire
     * and any pending coroutine publishes would race the socket close —
     * the publish call itself blocks on a tiny socket write so doing it
     * inline is cheap and reliable. Best-effort: a failure here just
     * leaves the entity stranded in HA, which the user can clean up via
     * the Devices UI.
     */
    private fun unpublishMqttDiscovery(cfg: ServiceConfig) {
        if (!cfg.mqttEnabled || cfg.mqttHost.isBlank()) return
        val node = cfg.mqttNodeId.ifBlank { "default" }
        val uniqueId = "r1ha_${node}_${cfg.mqttObjectId}"
        val configTopic = "${cfg.mqttDiscoveryPrefix}/camera/$uniqueId/config"
        runCatching {
            mqtt?.publish(configTopic, ByteArray(0), retain = true)
        }
    }

    private fun publishMqttDiscovery(cfg: ServiceConfig) {
        // HA's MQTT discovery for the camera platform — published with
        // retain=true so the broker hands it to HA at every reconnect,
        // even if the device wasn't online during HA's last restart.
        val node = cfg.mqttNodeId.ifBlank { "default" }
        val uniqueId = "r1ha_${node}_${cfg.mqttObjectId}"
        val configTopic = "${cfg.mqttDiscoveryPrefix}/camera/$uniqueId/config"
        val payload = buildJsonObject {
            put("name", JsonPrimitive(cfg.entityName.ifBlank { "R1HA Camera ($node)" }))
            put("unique_id", JsonPrimitive(uniqueId))
            put("topic", JsonPrimitive(mqttImageTopic(cfg)))
            put("encoding", JsonPrimitive("b64")) // ignored for JPEG bytes but harmless
            // Device block so HA groups the entity under a single device
            // alongside any future companion entities (battery, RSSI).
            put(
                "device",
                buildJsonObject {
                    put(
                        "identifiers",
                        kotlinx.serialization.json.JsonArray(
                            listOf(JsonPrimitive("r1ha_$node")),
                        ),
                    )
                    put("name", JsonPrimitive("R1HA $node"))
                    put("manufacturer", JsonPrimitive("R1HA"))
                    put("model", JsonPrimitive(Build.MODEL ?: "Android device"))
                    put(
                        "sw_version",
                        JsonPrimitive(com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME),
                    )
                },
            )
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        // Discovery is retained so the broker replays it for HA on reconnect.
        serviceScope.launch {
            val ok = mqtt?.publish(configTopic, bytes, retain = true) ?: false
            status.setMqttDiscoveryPublished(ok)
            if (!ok) {
                status.setMqtt(
                    IotCameraStatus.SinkState.FAILED,
                    "Discovery publish failed",
                )
            }
        }
    }

    private fun startInForeground(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(state: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(initial = false, state = state))
    }

    private fun buildNotification(initial: Boolean, state: String? = null): Notification {
        val launchPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = state ?: if (initial) "Starting…" else "Streaming"
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("R1HA IoT Camera Mode")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchPending)
            .build()
    }

    private fun stateSummary(cfg: ServiceConfig): String {
        val parts = buildList {
            if (cfg.mjpegEnabled) add("MJPEG :${cfg.mjpegPort}")
            if (cfg.mqttEnabled) add("MQTT ${cfg.mqttHost}")
            if (isEmpty()) add("idle (no sinks)")
        }
        return "${cfg.width}×${cfg.height} @ ${cfg.fps}fps · " + parts.joinToString(" + ")
    }

    /**
     * Snapshot of the fields the pipeline cares about. Lets the settings
     * observer use a clean `distinctUntilChanged` instead of comparing the
     * whole AppSettings, and decouples the service from incidental settings
     * (a theme change shouldn't tear down the camera).
     */
    private data class ServiceConfig(
        val enabled: Boolean,
        val cameraId: String,
        val width: Int,
        val height: Int,
        val fps: Int,
        val jpegQuality: Int,
        val rotationDegrees: Int,
        val mjpegEnabled: Boolean,
        val mjpegPort: Int,
        val mjpegAuthEnabled: Boolean,
        val mjpegUsername: String,
        val mjpegPassword: String,
        val mqttEnabled: Boolean,
        val mqttDiscoveryPrefix: String,
        val mqttNodeId: String,
        val mqttObjectId: String,
        val entityName: String,
        val mqttHost: String,
        val mqttPort: Int,
        val mqttUsername: String,
        val mqttPassword: String,
        val mqttUseTls: Boolean,
    ) {
        companion object {
            fun from(
                c: IotCameraSettings,
                a: com.github.itskenny0.r1ha.core.prefs.AdvancedSettings,
            ): ServiceConfig = ServiceConfig(
                enabled = c.enabled,
                cameraId = c.cameraId,
                width = c.width.coerceAtLeast(160),
                height = c.height.coerceAtLeast(120),
                fps = c.fps.coerceAtLeast(1),
                jpegQuality = c.jpegQuality.coerceIn(1, 100),
                rotationDegrees = ((c.rotationDegrees % 360) + 360) % 360,
                mjpegEnabled = c.mjpegEnabled,
                mjpegPort = c.mjpegPort.coerceIn(1024, 65535),
                mjpegAuthEnabled = c.mjpegAuthEnabled,
                mjpegUsername = c.mjpegUsername,
                mjpegPassword = c.mjpegPassword,
                mqttEnabled = c.mqttEnabled,
                mqttDiscoveryPrefix = c.mqttDiscoveryPrefix.ifBlank { "homeassistant" },
                mqttNodeId = c.mqttNodeId,
                mqttObjectId = c.mqttObjectId.ifBlank { "camera" },
                entityName = c.entityName,
                mqttHost = a.mqttHost,
                mqttPort = a.mqttPort,
                mqttUsername = a.mqttUsername,
                mqttPassword = a.mqttPassword,
                mqttUseTls = a.mqttUseTls,
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "iot_camera_mode"
        private const val NOTIF_ID = 0x71BA1702

        /** Bitrate sampling window. 1 s gives a responsive readout without
         *  jitter from short bursts; longer would smooth too aggressively
         *  for a "did my throttle change land?" debug check. */
        private const val BITRATE_WINDOW_MS = 1_000L

        /** Delay between releasing one camera id and opening another
         *  to give the HAL time to drop its exclusive locks. 500 ms is
         *  short enough to feel snappy but long enough for the broken
         *  Xiaomi HAL revisions we've seen; if it turns out not to be
         *  enough on some device, surface as a setting. */
        private const val HAL_SETTLE_MS = 500L

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IoT Camera Mode",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Streams the device camera to Home Assistant via MJPEG and/or MQTT"
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, IotCameraService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IotCameraService::class.java))
        }
    }
}
