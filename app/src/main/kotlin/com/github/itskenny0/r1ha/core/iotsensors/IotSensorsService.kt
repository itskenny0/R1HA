package com.github.itskenny0.r1ha.core.iotsensors

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.MainActivity
import com.github.itskenny0.r1ha.R
import com.github.itskenny0.r1ha.core.prefs.IotSensorsSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.math.sqrt

/**
 * Foreground service that exposes the host device to Home Assistant as a
 * collection of MQTT-auto-discovered entities. Read-only sensors (battery,
 * light, vibration, screen state, optional SSID) push state to the broker;
 * controllable entities (flashlight, brightness, volume, lock screen)
 * subscribe to a command topic and apply the change locally.
 *
 * The service is the sibling of [com.github.itskenny0.r1ha.core.iotcamera.IotCameraService]
 * — same MQTT broker config, same node id, same device block in discovery
 * payloads so HA groups everything (camera + sensors + controls) under one
 * physical device. They run as separate services because one can be on
 * without the other and the camera service holds a hardware lock that this
 * one doesn't need.
 *
 * Off by default. Started by the App-level observer when
 * [IotSensorsSettings.enabled] flips true and torn down on flip-off; live
 * setting edits inside the service re-spin only the affected resources.
 */
class IotSensorsService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var mqtt: MqttPubSubSession? = null
    @Volatile private var currentConfig: ServiceConfig? = null
    private var publishJob: Job? = null
    private var sensorListener: SensorEventListener? = null
    /** Discovery topics we've published this session — remembered so
     *  onDestroy can blank them and HA stops showing stale entities. */
    private val publishedDiscoveryTopics = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cfg = currentConfig ?: return
            val (pct, charging) = readBattery(intent ?: return)
            if (cfg.publishBattery) {
                publishState(topicState(cfg, "battery"), pct.toString().toByteArray())
            }
            if (cfg.publishCharging) {
                publishState(topicState(cfg, "charging"), if (charging) "ON".toByteArray() else "OFF".toByteArray())
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val cfg = currentConfig ?: return
            if (!cfg.publishScreenOn) return
            val on = intent?.action == Intent.ACTION_SCREEN_ON
            publishState(topicState(cfg, "screen"), if (on) "ON".toByteArray() else "OFF".toByteArray())
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        startInForeground(buildNotification("Starting…"))
        val graph = (application as App).graph
        serviceScope.launch {
            graph.settings.settings
                .map { ServiceConfig.from(it.iotSensors, it.advanced) }
                .distinctUntilChanged()
                .collect { cfg -> applyConfig(cfg) }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Same teardown pattern as IotCameraService — snapshot everything
        // then hand the blocking close calls to a process-scope executor so
        // Service.onDestroy returns immediately and we don't ANR if the
        // broker socket is sluggish.
        val prevConfig = currentConfig
        val previousMqtt = mqtt
        val previousJob = publishJob
        val previousListener = sensorListener
        val previousTopics = publishedDiscoveryTopics.toList()
        mqtt = null
        publishJob = null
        sensorListener = null
        currentConfig = null
        publishedDiscoveryTopics.clear()
        runCatching { previousJob?.cancel() }
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        previousListener?.let { l ->
            runCatching {
                (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                    ?.unregisterListener(l)
            }
        }
        teardownExecutor.execute {
            // Blank every retained discovery topic so HA drops the entities
            // instead of showing them forever as "last seen X ago".
            previousTopics.forEach { topic ->
                runCatching { previousMqtt?.publish(topic, ByteArray(0), retain = true) }
            }
            runCatching { previousMqtt?.stop() }
            // Also clear retained state topics so HA doesn't show stale
            // values after we shut down; cheap enough to do per-entity.
            prevConfig?.let { cfg ->
                listOf("battery", "charging", "illuminance", "vibration", "screen", "ssid").forEach { id ->
                    runCatching { previousMqtt?.publish(topicState(cfg, id), ByteArray(0), retain = true) }
                }
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private suspend fun applyConfig(cfg: ServiceConfig) {
        val prev = currentConfig
        currentConfig = cfg
        if (prev == cfg) return
        // Tear down per-feature listeners that may need re-spinning with
        // new thresholds. The MQTT session can stay if the broker config
        // is unchanged; checking explicitly avoids dropping the connection
        // and missing in-flight HA commands.
        sensorListener?.let { l ->
            runCatching {
                (getSystemService(Context.SENSOR_SERVICE) as? SensorManager)
                    ?.unregisterListener(l)
            }
            sensorListener = null
        }
        publishJob?.cancel(); publishJob = null

        val brokerChanged = prev?.mqttHost != cfg.mqttHost ||
            prev?.mqttPort != cfg.mqttPort ||
            prev?.mqttUsername != cfg.mqttUsername ||
            prev?.mqttPassword != cfg.mqttPassword ||
            prev?.mqttUseTls != cfg.mqttUseTls ||
            prev?.nodeId != cfg.nodeId
        if (brokerChanged) {
            runCatching { mqtt?.stop() }
            mqtt = null
            publishedDiscoveryTopics.clear()
        }

        if (!cfg.enabled) {
            updateNotification("Idle — IoT Sensors disabled")
            return
        }
        if (cfg.mqttHost.isBlank()) {
            updateNotification("Configure broker under Settings → MQTT broker")
            return
        }

        if (mqtt == null) {
            val session = MqttPubSubSession(
                host = cfg.mqttHost,
                port = cfg.mqttPort,
                clientId = "r1ha-sensors-${cfg.nodeId.ifBlank { "default" }}",
                username = cfg.mqttUsername.ifBlank { null },
                password = cfg.mqttPassword.ifBlank { null },
                useTls = cfg.mqttUseTls,
                onMessage = { topic, payload -> handleCommand(topic, payload) },
            )
            val ok = session.start()
            if (!ok) {
                updateNotification("Broker unreachable: ${cfg.mqttHost}:${cfg.mqttPort}")
                return
            }
            mqtt = session
        }

        // Re-publish all discovery payloads (cheap; idempotent on the broker
        // side since they're retained). Covers fresh start, post-reconnect,
        // and field-edit cases without separate code paths.
        publishedDiscoveryTopics.clear()
        publishAllDiscovery(cfg)

        // Read-only sensors: subscribe to whatever the platform offers.
        registerSensorListeners(cfg)

        // Receivers: battery + screen are sticky / event-driven. Register
        // each only when the user wants its entity, so we don't burn CPU
        // for sensors no-one will see.
        if (cfg.publishBattery || cfg.publishCharging) {
            runCatching {
                registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            }
        }
        if (cfg.publishScreenOn) {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            }
            runCatching { registerReceiver(screenReceiver, filter) }
            // Push the current state once on register so HA isn't stuck on
            // the previous value waiting for the next user-driven event.
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            val on = pm?.isInteractive ?: true
            publishState(topicState(cfg, "screen"), if (on) "ON".toByteArray() else "OFF".toByteArray())
        }

        // Periodic publisher for slow-moving values + a heartbeat so HA can
        // mark the device offline if we go silent for several intervals.
        publishJob = serviceScope.launch {
            while (true) {
                publishPeriodic(cfg)
                delay(cfg.publishIntervalSec.coerceAtLeast(5) * 1000L)
            }
        }

        // Command subscriptions for controllable entities.
        if (cfg.controlFlashlight) mqtt?.subscribe(topicCommand(cfg, "flashlight"))
        if (cfg.controlBrightness) mqtt?.subscribe(topicCommand(cfg, "brightness"))
        if (cfg.controlVolume) mqtt?.subscribe(topicCommand(cfg, "volume"))
        if (cfg.controlLockScreen) mqtt?.subscribe(topicCommand(cfg, "lock_screen"))

        updateNotification("Publishing ${enabledEntityCount(cfg)} entities to ${cfg.mqttHost}")
    }

    private fun publishAllDiscovery(cfg: ServiceConfig) {
        val device = buildDeviceBlock(cfg)
        if (cfg.publishBattery) publishDiscovery(
            cfg, component = "sensor", objectId = "battery",
            extra = buildJsonObject {
                put("device_class", JsonPrimitive("battery"))
                put("unit_of_measurement", JsonPrimitive("%"))
                put("state_class", JsonPrimitive("measurement"))
                put("name", JsonPrimitive("Battery"))
            },
            device = device,
        )
        if (cfg.publishCharging) publishDiscovery(
            cfg, component = "binary_sensor", objectId = "charging",
            extra = buildJsonObject {
                put("device_class", JsonPrimitive("battery_charging"))
                put("name", JsonPrimitive("Charging"))
            },
            device = device,
        )
        if (cfg.publishLightSensor) publishDiscovery(
            cfg, component = "sensor", objectId = "illuminance",
            extra = buildJsonObject {
                put("device_class", JsonPrimitive("illuminance"))
                put("unit_of_measurement", JsonPrimitive("lx"))
                put("state_class", JsonPrimitive("measurement"))
                put("name", JsonPrimitive("Light"))
            },
            device = device,
        )
        if (cfg.publishVibration) publishDiscovery(
            cfg, component = "binary_sensor", objectId = "vibration",
            extra = buildJsonObject {
                put("device_class", JsonPrimitive("vibration"))
                put("name", JsonPrimitive("Vibration"))
                // HA flips this back to OFF after the timeout window;
                // matches the "shake detected once" semantics we publish.
                put("off_delay", JsonPrimitive(10))
            },
            device = device,
        )
        if (cfg.publishScreenOn) publishDiscovery(
            cfg, component = "binary_sensor", objectId = "screen",
            extra = buildJsonObject {
                put("device_class", JsonPrimitive("power"))
                put("name", JsonPrimitive("Screen"))
            },
            device = device,
        )
        if (cfg.publishWifiSsid) publishDiscovery(
            cfg, component = "sensor", objectId = "ssid",
            extra = buildJsonObject {
                put("name", JsonPrimitive("WiFi SSID"))
                put("entity_category", JsonPrimitive("diagnostic"))
            },
            device = device,
        )
        if (cfg.controlFlashlight) publishDiscovery(
            cfg, component = "switch", objectId = "flashlight",
            extra = buildJsonObject {
                put("name", JsonPrimitive("Flashlight"))
                put("command_topic", JsonPrimitive(topicCommand(cfg, "flashlight")))
                put("payload_on", JsonPrimitive("ON"))
                put("payload_off", JsonPrimitive("OFF"))
                put("icon", JsonPrimitive("mdi:flashlight"))
            },
            device = device,
        )
        if (cfg.controlBrightness) publishDiscovery(
            cfg, component = "number", objectId = "brightness",
            extra = buildJsonObject {
                put("name", JsonPrimitive("Screen brightness"))
                put("command_topic", JsonPrimitive(topicCommand(cfg, "brightness")))
                put("min", JsonPrimitive(0))
                put("max", JsonPrimitive(100))
                put("step", JsonPrimitive(1))
                put("unit_of_measurement", JsonPrimitive("%"))
                put("icon", JsonPrimitive("mdi:brightness-6"))
            },
            device = device,
        )
        if (cfg.controlVolume) publishDiscovery(
            cfg, component = "number", objectId = "volume",
            extra = buildJsonObject {
                put("name", JsonPrimitive("Media volume"))
                put("command_topic", JsonPrimitive(topicCommand(cfg, "volume")))
                put("min", JsonPrimitive(0))
                put("max", JsonPrimitive(100))
                put("step", JsonPrimitive(1))
                put("unit_of_measurement", JsonPrimitive("%"))
                put("icon", JsonPrimitive("mdi:volume-high"))
            },
            device = device,
        )
        if (cfg.controlLockScreen) publishDiscovery(
            cfg, component = "button", objectId = "lock_screen",
            // button entity has no state_topic — fired only via command.
            extra = buildJsonObject {
                put("name", JsonPrimitive("Lock screen"))
                put("command_topic", JsonPrimitive(topicCommand(cfg, "lock_screen")))
                put("payload_press", JsonPrimitive("PRESS"))
                put("icon", JsonPrimitive("mdi:lock"))
            },
            device = device,
            includeStateTopic = false,
        )
        // Publish current control-state snapshots so HA picks up the
        // initial value instead of waiting for the next user action.
        if (cfg.controlFlashlight) publishState(topicState(cfg, "flashlight"), if (isFlashlightOn()) "ON".toByteArray() else "OFF".toByteArray())
        if (cfg.controlBrightness) publishState(topicState(cfg, "brightness"), currentBrightnessPct().toString().toByteArray())
        if (cfg.controlVolume) publishState(topicState(cfg, "volume"), currentVolumePct().toString().toByteArray())
    }

    private fun publishDiscovery(
        cfg: ServiceConfig,
        component: String,
        objectId: String,
        extra: kotlinx.serialization.json.JsonObject,
        device: kotlinx.serialization.json.JsonObject,
        includeStateTopic: Boolean = true,
    ) {
        val uniqueId = "r1ha_${cfg.nodeId.ifBlank { "default" }}_$objectId"
        val configTopic = "${cfg.discoveryPrefix}/$component/$uniqueId/config"
        val payload = buildJsonObject {
            // Merge order matters — `extra` carries the per-entity name /
            // device_class / unit / icon and shouldn't get overwritten by
            // the device-id boilerplate, so apply extra last.
            put("unique_id", JsonPrimitive(uniqueId))
            put("object_id", JsonPrimitive(uniqueId))
            if (includeStateTopic) {
                put("state_topic", JsonPrimitive(topicState(cfg, objectId)))
            }
            put("device", device)
            extra.forEach { (k, v) -> put(k, v) }
        }
        val bytes = payload.toString().toByteArray(Charsets.UTF_8)
        if (mqtt?.publish(configTopic, bytes, retain = true) == true) {
            publishedDiscoveryTopics.add(configTopic)
        }
    }

    private fun buildDeviceBlock(cfg: ServiceConfig) = buildJsonObject {
        // Same identifier prefix as the camera service so HA merges them
        // under one device — sensors + camera = one tile in the Devices UI.
        put("identifiers", JsonArray(listOf(JsonPrimitive("r1ha_${cfg.nodeId.ifBlank { "default" }}"))))
        put("name", JsonPrimitive("R1HA ${cfg.nodeId.ifBlank { "default" }}"))
        put("manufacturer", JsonPrimitive("R1HA"))
        put("model", JsonPrimitive(Build.MODEL ?: "Android device"))
        put("sw_version", JsonPrimitive(com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME))
    }

    // ── Sensors ─────────────────────────────────────────────────────────
    private fun registerSensorListeners(cfg: ServiceConfig) {
        val sm = getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
        val light = if (cfg.publishLightSensor) sm.getDefaultSensor(Sensor.TYPE_LIGHT) else null
        val accel = if (cfg.publishVibration) sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) else null
        if (light == null && accel == null) return

        val threshold = cfg.vibrationThresholdG.coerceAtLeast(0.1f) * 9.81f
        var lastVibrationAt = 0L
        var lastLightLx = -1f
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_LIGHT -> {
                        val lx = event.values.firstOrNull() ?: return
                        // Throttle: only publish when the value drifts by
                        // more than ~5 lx OR doubles/halves. Avoids
                        // spamming the broker with sub-lux jitter.
                        if (lastLightLx < 0 || kotlin.math.abs(lx - lastLightLx) > 5f ||
                            (lastLightLx > 0 && (lx / lastLightLx > 1.5f || lx / lastLightLx < 0.66f))) {
                            lastLightLx = lx
                            publishState(
                                topicState(cfg, "illuminance"),
                                ((lx * 10).toInt() / 10f).toString().toByteArray(),
                            )
                        }
                    }
                    Sensor.TYPE_ACCELEROMETER -> {
                        val x = event.values.getOrNull(0) ?: 0f
                        val y = event.values.getOrNull(1) ?: 0f
                        val z = event.values.getOrNull(2) ?: 0f
                        val magnitude = sqrt(x * x + y * y + z * z)
                        // Subtract gravity baseline (~9.81) so a still
                        // phone reads ~0 and only real movement crosses
                        // the threshold. Coerced positive in case of
                        // noisy sub-gravity readings.
                        val delta = kotlin.math.abs(magnitude - 9.81f)
                        if (delta > threshold) {
                            val now = System.currentTimeMillis()
                            // Debounce — one vibration event per 2s max,
                            // otherwise a sustained shake floods HA.
                            if (now - lastVibrationAt > 2_000L) {
                                lastVibrationAt = now
                                publishState(topicState(cfg, "vibration"), "ON".toByteArray())
                            }
                        }
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorListener = listener
        // SENSOR_DELAY_NORMAL = ~200 ms — plenty for ambient light and
        // human-scale vibration; UI rate would burn battery for no benefit.
        light?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
        accel?.let { sm.registerListener(listener, it, SensorManager.SENSOR_DELAY_NORMAL) }
    }

    private fun publishPeriodic(cfg: ServiceConfig) {
        // Battery + charging — re-read on the heartbeat too so HA gets a
        // fresh value even if the device hasn't crossed a battery-changed
        // threshold for a while.
        if (cfg.publishBattery || cfg.publishCharging) {
            val sticky = applicationContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            sticky?.let {
                val (pct, charging) = readBattery(it)
                if (cfg.publishBattery) publishState(topicState(cfg, "battery"), pct.toString().toByteArray())
                if (cfg.publishCharging) publishState(topicState(cfg, "charging"), if (charging) "ON".toByteArray() else "OFF".toByteArray())
            }
        }
        if (cfg.publishWifiSsid) {
            val ssid = readSsid() ?: ""
            publishState(topicState(cfg, "ssid"), ssid.toByteArray())
        }
        if (cfg.controlBrightness) publishState(topicState(cfg, "brightness"), currentBrightnessPct().toString().toByteArray())
        if (cfg.controlVolume) publishState(topicState(cfg, "volume"), currentVolumePct().toString().toByteArray())
        if (cfg.controlFlashlight) publishState(topicState(cfg, "flashlight"), if (isFlashlightOn()) "ON".toByteArray() else "OFF".toByteArray())
    }

    // ── Commands ────────────────────────────────────────────────────────
    private fun handleCommand(topic: String, payload: ByteArray) {
        val cfg = currentConfig ?: return
        val text = String(payload, Charsets.UTF_8).trim()
        when (topic) {
            topicCommand(cfg, "flashlight") -> setFlashlight(text.equals("ON", ignoreCase = true))
            topicCommand(cfg, "brightness") -> text.toIntOrNull()?.let { setBrightness(it) }
            topicCommand(cfg, "volume") -> text.toIntOrNull()?.let { setVolume(it) }
            topicCommand(cfg, "lock_screen") -> lockScreen()
            else -> R1Log.d("IotSensors.cmd", "ignored topic=$topic payload=$text")
        }
    }

    private fun setFlashlight(on: Boolean) {
        val cm = getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return
        val id = runCatching {
            cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull() ?: return
        runCatching { cm.setTorchMode(id, on) }
            .onFailure { R1Log.w("IotSensors.cmd", "torch toggle failed: ${it.message}") }
        // Echo state so HA's switch reflects reality even if the user
        // toggled it locally a moment ago.
        currentConfig?.let { publishState(topicState(it, "flashlight"), if (on) "ON".toByteArray() else "OFF".toByteArray()) }
    }

    private fun setBrightness(pct: Int) {
        val clamped = pct.coerceIn(0, 100)
        if (!Settings.System.canWrite(applicationContext)) {
            R1Log.w("IotSensors.cmd", "WRITE_SETTINGS not granted; brightness command ignored")
            updateNotification("Grant 'Modify system settings' to control brightness")
            return
        }
        val raw = (clamped * 255 / 100).coerceIn(1, 255)
        runCatching {
            Settings.System.putInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS, raw)
        }.onFailure { R1Log.w("IotSensors.cmd", "brightness write failed: ${it.message}") }
        currentConfig?.let { publishState(topicState(it, "brightness"), clamped.toString().toByteArray()) }
    }

    private fun setVolume(pct: Int) {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = ((pct.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
        runCatching { am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
            .onFailure { R1Log.w("IotSensors.cmd", "volume write failed: ${it.message}") }
        currentConfig?.let { publishState(topicState(it, "volume"), pct.coerceIn(0, 100).toString().toByteArray()) }
    }

    private fun lockScreen() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            ?: return
        val admin = ComponentName(this, LockAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            runCatching { dpm.lockNow() }
                .onFailure { R1Log.w("IotSensors.cmd", "lockNow failed: ${it.message}") }
        } else {
            R1Log.w("IotSensors.cmd", "device admin not active; lock_screen ignored")
            updateNotification("Grant Device Admin in Settings → IoT Sensors to allow lock")
        }
    }

    // ── Readers ─────────────────────────────────────────────────────────
    private fun readBattery(intent: Intent): Pair<Int, Boolean> {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        return pct to charging
    }

    @Suppress("DEPRECATION")
    private fun readSsid(): String? {
        return runCatching {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val info = wifi.connectionInfo ?: return null
            val raw = info.ssid?.trim('"').orEmpty()
            raw.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }.getOrNull()
    }

    private fun currentBrightnessPct(): Int {
        return runCatching {
            val raw = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            (raw * 100f / 255f).toInt().coerceIn(0, 100)
        }.getOrDefault(0)
    }

    private fun currentVolumePct(): Int {
        val am = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return 0
        return runCatching {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
            (am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100f / max).toInt().coerceIn(0, 100)
        }.getOrDefault(0)
    }

    private fun isFlashlightOn(): Boolean {
        // No public getter on CameraManager — we don't track it across
        // process restarts. Best-effort: return false; the next user toggle
        // (either local or HA-side) re-syncs the state. Acceptable for an
        // optimistic switch entity.
        return false
    }

    // ── Topic helpers ───────────────────────────────────────────────────
    private fun topicState(cfg: ServiceConfig, objectId: String): String =
        "r1ha/${cfg.nodeId.ifBlank { "default" }}/$objectId/state"

    private fun topicCommand(cfg: ServiceConfig, objectId: String): String =
        "r1ha/${cfg.nodeId.ifBlank { "default" }}/$objectId/set"

    private fun publishState(topic: String, payload: ByteArray) {
        // Retain so HA picks up the last value on reconnect rather than
        // showing "unknown" until the next periodic publish ticks.
        mqtt?.publish(topic, payload, retain = true)
    }

    private fun enabledEntityCount(cfg: ServiceConfig): Int {
        var n = 0
        if (cfg.publishBattery) n++
        if (cfg.publishCharging) n++
        if (cfg.publishLightSensor) n++
        if (cfg.publishVibration) n++
        if (cfg.publishScreenOn) n++
        if (cfg.publishWifiSsid) n++
        if (cfg.controlFlashlight) n++
        if (cfg.controlBrightness) n++
        if (cfg.controlVolume) n++
        if (cfg.controlLockScreen) n++
        return n
    }

    // ── Notification ────────────────────────────────────────────────────
    private fun startInForeground(notification: Notification) {
        // No camera / location / microphone usage, so the "specialUse" type
        // is the right slot for Android 14+. Falls back to no-type on
        // earlier API levels.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.notify(NOTIF_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val launchPending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("R1HA IoT Sensors Mode")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(launchPending)
            .build()
    }

    /** Snapshot of the fields the publisher cares about. Used as the
     *  distinct-key for the settings observer so a theme change (or any
     *  other unrelated edit) doesn't tear down the MQTT connection. */
    private data class ServiceConfig(
        val enabled: Boolean,
        val nodeId: String,
        val discoveryPrefix: String,
        val publishIntervalSec: Int,
        val publishBattery: Boolean,
        val publishCharging: Boolean,
        val publishLightSensor: Boolean,
        val publishVibration: Boolean,
        val publishScreenOn: Boolean,
        val publishWifiSsid: Boolean,
        val controlFlashlight: Boolean,
        val controlBrightness: Boolean,
        val controlVolume: Boolean,
        val controlLockScreen: Boolean,
        val vibrationThresholdG: Float,
        val mqttHost: String,
        val mqttPort: Int,
        val mqttUsername: String,
        val mqttPassword: String,
        val mqttUseTls: Boolean,
    ) {
        companion object {
            fun from(
                s: IotSensorsSettings,
                a: com.github.itskenny0.r1ha.core.prefs.AdvancedSettings,
            ): ServiceConfig = ServiceConfig(
                enabled = s.enabled,
                nodeId = s.nodeId,
                discoveryPrefix = s.discoveryPrefix.ifBlank { "homeassistant" },
                publishIntervalSec = s.publishIntervalSec.coerceAtLeast(5),
                publishBattery = s.publishBattery,
                publishCharging = s.publishCharging,
                publishLightSensor = s.publishLightSensor,
                publishVibration = s.publishVibration,
                publishScreenOn = s.publishScreenOn,
                publishWifiSsid = s.publishWifiSsid,
                controlFlashlight = s.controlFlashlight,
                controlBrightness = s.controlBrightness,
                controlVolume = s.controlVolume,
                controlLockScreen = s.controlLockScreen,
                vibrationThresholdG = s.vibrationThresholdG,
                mqttHost = a.mqttHost,
                mqttPort = a.mqttPort,
                mqttUsername = a.mqttUsername,
                mqttPassword = a.mqttPassword,
                mqttUseTls = a.mqttUseTls,
            )
        }
    }

    companion object {
        private const val CHANNEL_ID = "iot_sensors_mode"
        private const val NOTIF_ID = 0x71BA1703

        /** Daemon executor for blocking teardown calls — same rationale as
         *  IotCameraService.teardownExecutor: keeps Service.onDestroy from
         *  blocking the main thread on MQTT socket close. */
        private val teardownExecutor by lazy {
            java.util.concurrent.Executors.newSingleThreadExecutor { r ->
                Thread(r, "r1ha-iot-sensors-teardown").apply { isDaemon = true }
            }
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "IoT Sensors Mode",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Publishes device sensors + controls to Home Assistant via MQTT"
            }
            manager.createNotificationChannel(channel)
        }

        fun start(context: Context) {
            ensureChannel(context)
            val intent = Intent(context, IotSensorsService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, IotSensorsService::class.java))
        }
    }
}
