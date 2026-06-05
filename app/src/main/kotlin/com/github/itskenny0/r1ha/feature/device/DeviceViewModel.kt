package com.github.itskenny0.r1ha.feature.device

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.brightness255ToPct
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Drives the Device surface — local controls for the device hosting
 * the app (the R1 itself, or whichever phone the user is testing on).
 *
 * Everything here is LOCAL only — nothing is reported back to Home
 * Assistant or persisted as an entity. The user said they wanted
 * 'controls for the R1 itself' that fit alongside the HA-entity
 * surfaces; rather than fake an HA-style entity for each, we render
 * them as bespoke cards on a dedicated Device screen.
 *
 * Permissions chosen to be invasive-free:
 *  - Brightness: writes the activity's own [WindowManager.LayoutParams]
 *    (`screenBrightness`) — affects only when our app is foreground.
 *    No `WRITE_SETTINGS` required.
 *  - Volume: AudioManager.setStreamVolume — works without a permission
 *    grant for media / notification / alarm streams.
 *  - Flashlight: CameraManager.setTorchMode — no permission required
 *    since API 23.
 *  - Battery: ACTION_BATTERY_CHANGED sticky broadcast — read-only.
 *  - WiFi info: ConnectivityManager / WifiManager — no permission for
 *    the read-only SSID-less subset we use here.
 *
 * AndroidViewModel because every value needs the Application context;
 * threading it through every method call would be tedious.
 */
class DeviceViewModel(app: App) : AndroidViewModel(app) {

    @androidx.compose.runtime.Stable
    data class UiState(
        /** Per-window screen brightness in 0..100. -1 means
         *  'follow system brightness' (no override applied). */
        val brightnessPct: Int = -1,
        /** Current SYSTEM-wide screen brightness in 0..100, read from
         *  Settings.System.SCREEN_BRIGHTNESS (a 0..255 value, scaled
         *  here). Used to position the brightness slider when no
         *  per-window override is active, so the slider reflects what
         *  the user is actually looking at instead of snapping to 50%
         *  the moment they touch it. */
        val systemBrightnessPct: Int = 0,
        /** Media volume scaled to 0..100 across the AudioManager
         *  per-stream range. */
        val mediaVolumePct: Int = 0,
        /** Notification volume 0..100. */
        val notificationVolumePct: Int = 0,
        /** Alarm volume 0..100. */
        val alarmVolumePct: Int = 0,
        /** True if the device has a torch (back-camera with flash) we
         *  could control. False on devices without a flashlight. */
        val flashlightAvailable: Boolean = false,
        /** Current torch state. Updated optimistically on toggle. */
        val flashlightOn: Boolean = false,
        /** Battery percent 0..100; -1 if not yet read. */
        val batteryPct: Int = -1,
        /** True if charging (USB / AC / wireless). */
        val isCharging: Boolean = false,
        /** Raw battery status string from the OS for diagnostic
         *  display ("FULL" / "CHARGING" / "DISCHARGING"). */
        val batteryStatus: String = "",
        /** WiFi SSID (if connected to one we can read). The OS hides
         *  the SSID without location permission since A10+, so this
         *  often reads as "<unknown ssid>"; we still surface that
         *  string so the user sees the WiFi connection state. */
        val wifiSsid: String? = null,
        /** Reading + applying-write epoch for diagnostic. Bumped
         *  whenever we refresh from system state. */
        val lastReadAtMillis: Long = 0L,
    )

    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui

    /** Activity-window brightness handle. Set from the screen-side
     *  composable via [bindWindowBrightnessApplier] so the screen can
     *  push the new brightness into its host Activity's
     *  WindowManager.LayoutParams (the VM can't reach into the
     *  Activity directly). */
    private var windowBrightnessApplier: ((Float) -> Unit)? = null

    private val context: Context get() = getApplication<App>().applicationContext

    private val audioManager: AudioManager by lazy {
        context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val cameraManager: CameraManager? by lazy {
        runCatching {
            context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        }.getOrNull()
    }

    /** Find the first back-camera with flash so torchOn / torchOff
     *  targets the right physical camera. Null when the device has
     *  no flashlight-capable camera (which renders the flashlight
     *  card as unavailable rather than letting the toggle throw). */
    private val torchCameraId: String? by lazy {
        runCatching {
            val mgr = cameraManager ?: return@lazy null
            mgr.cameraIdList.firstOrNull { id ->
                val ch = mgr.getCameraCharacteristics(id)
                ch.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
    }

    // Declared BEFORE [init] so the property initializer runs first — Kotlin
    // executes property initializers and init blocks in textual order, so a
    // torchCallback declared after `init { registerTorchCallback(...) }` would
    // be null at registration time. We previously passed that null straight
    // into CameraManager; some Android builds validate and throw (caught here),
    // others silently accept it and later crash on a background thread when
    // the torch state changes and the framework tries to invoke a method on
    // the null callback — that uncaught NPE took the whole app down whenever
    // the user opened the Device screen.
    private val torchCallback = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCameraId) {
                _ui.value = _ui.value.copy(flashlightOn = enabled)
            }
        }
    }

    init {
        refresh()
        // Listen for torch state changes initiated by other apps so
        // our card reflects external flips. Skipped on devices that don't
        // expose a torch-capable camera at all — registerTorchCallback would
        // either fail or fire zero events, and getting torchCameraId here
        // forces the lazy to resolve before refresh()'s async fire reaches
        // the same property (avoiding a benign double-evaluation).
        runCatching {
            if (torchCameraId != null) {
                cameraManager?.registerTorchCallback(torchCallback, null)
            }
        }
    }

    /** Refresh all readable values from the system. Called on entry +
     *  after every write so the UI stays consistent with reality. */
    fun refresh() {
        viewModelScope.launch {
            val cur = _ui.value
            val brightness = readWindowBrightnessPct()
            val media = readVolumePct(AudioManager.STREAM_MUSIC)
            val notif = readVolumePct(AudioManager.STREAM_NOTIFICATION)
            val alarm = readVolumePct(AudioManager.STREAM_ALARM)
            val torchAvail = torchCameraId != null
            val battery = readBatteryStatus()
            val ssid = readWifiSsid()
            _ui.value = cur.copy(
                brightnessPct = brightness,
                systemBrightnessPct = readSystemBrightnessPct(),
                mediaVolumePct = media,
                notificationVolumePct = notif,
                alarmVolumePct = alarm,
                flashlightAvailable = torchAvail,
                batteryPct = battery.first,
                isCharging = battery.second,
                batteryStatus = battery.third,
                wifiSsid = ssid,
                lastReadAtMillis = System.currentTimeMillis(),
            )
        }
    }

    /** Screen-side composable plumbs the activity's WindowManager
     *  brightness setter through this hook. The VM never holds an
     *  Activity reference (would leak); the lambda captures it for
     *  the duration of the composition only. */
    fun bindWindowBrightnessApplier(apply: (Float) -> Unit) {
        windowBrightnessApplier = apply
        // Read the current value back from the activity, since the
        // screen lifecycle may have re-attached with a fresh window
        // (after rotation, etc.).
        viewModelScope.launch { _ui.value = _ui.value.copy(brightnessPct = readWindowBrightnessPct()) }
    }

    fun unbindWindowBrightness() {
        windowBrightnessApplier = null
    }

    /** Set the per-window brightness in 0..100. -1 (or below 0)
     *  releases the override so the system brightness takes over. */
    fun setBrightness(pct: Int) {
        val clamped = pct.coerceIn(-1, 100)
        val asFloat = if (clamped < 0) android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        else (clamped / 100f).coerceIn(0.01f, 1f) // 0.01 floor so the screen stays barely-on
        windowBrightnessApplier?.invoke(asFloat)
        _ui.value = _ui.value.copy(brightnessPct = clamped)
    }

    /** AudioManager.setStreamVolume in 0..100. Translates to the
     *  per-stream max via getStreamMaxVolume. Doesn't show the system
     *  volume UI (third arg is 0, not SHOW_UI) so the slider feels
     *  like a direct control, not a separate panel. */
    fun setMediaVolume(pct: Int) = setStreamVolume(AudioManager.STREAM_MUSIC, pct)
    fun setNotificationVolume(pct: Int) = setStreamVolume(AudioManager.STREAM_NOTIFICATION, pct)
    fun setAlarmVolume(pct: Int) = setStreamVolume(AudioManager.STREAM_ALARM, pct)

    private fun setStreamVolume(stream: Int, pct: Int) {
        runCatching {
            val max = audioManager.getStreamMaxVolume(stream)
            val target = ((pct.coerceIn(0, 100) / 100f) * max).toInt().coerceIn(0, max)
            audioManager.setStreamVolume(stream, target, 0)
        }.onFailure {
            R1Log.w("Device", "setStreamVolume($stream, $pct) failed: ${it.message}")
            Toaster.error("Volume change rejected. Check Do-Not-Disturb / system permissions")
        }
        // Re-read so the slider snaps to the actual rounded value.
        viewModelScope.launch {
            delay(50L)
            refresh()
        }
    }

    /** Flip the torch on/off. Some OEMs throttle rapid toggles; if
     *  the system rejects, we surface a toast so the user understands
     *  it isn't an app bug. */
    fun toggleFlashlight() {
        val id = torchCameraId
        val mgr = cameraManager
        if (id == null || mgr == null) {
            Toaster.error("No flashlight available on this device")
            return
        }
        val target = !_ui.value.flashlightOn
        runCatching {
            mgr.setTorchMode(id, target)
            // Optimistic — the TorchCallback will confirm the real
            // state momentarily.
            _ui.value = _ui.value.copy(flashlightOn = target)
        }.onFailure {
            R1Log.w("Device", "setTorchMode failed: ${it.message}")
            Toaster.error("Flashlight rejected: ${it.message ?: "unknown"}")
        }
    }

    /** Open the system Settings app at the brightness/display page —
     *  useful when the per-window brightness override isn't enough
     *  (e.g. the user wants to change the underlying system value). */
    fun openSystemDisplaySettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            R1Log.w("Device", "open display settings failed: ${it.message}")
            Toaster.error("Couldn't open system display settings")
        }
    }

    /** Open the system WiFi panel for the user to manage SSIDs / connect /
     *  disconnect. We can't manipulate WiFi state directly without
     *  CHANGE_WIFI_STATE / NETWORK_SETTINGS (the latter is system-only). */
    fun openWifiSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            Toaster.error("Couldn't open WiFi settings")
        }
    }

    private fun readWindowBrightnessPct(): Int {
        // Per-window brightness doesn't expose a getter (the Activity
        // owns it). The screen-side bind sets this; absent a bound
        // applier, we report -1 = follow system.
        // We just return the current cached value rather than
        // querying — the screen pushes new values into the VM and
        // bind() re-syncs on mount.
        return _ui.value.brightnessPct
    }

    /** Read the SYSTEM-wide brightness setting and scale 0..255 → 0..100.
     *  No permission required (the value is publicly readable).
     *  Falls back to 0 when the read fails (rare; some manufacturers
     *  scope the setting under a different namespace). */
    private fun readSystemBrightnessPct(): Int {
        return runCatching {
            val raw = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
            )
            brightness255ToPct(raw)
        }.getOrDefault(0)
    }

    private fun readVolumePct(stream: Int): Int {
        return runCatching {
            val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
            val cur = audioManager.getStreamVolume(stream)
            ((cur.toFloat() / max) * 100f).toInt().coerceIn(0, 100)
        }.getOrDefault(0)
    }

    /** Read battery level + charging state via the
     *  ACTION_BATTERY_CHANGED sticky broadcast. Same source the
     *  chrome row's BatteryIndicator uses. */
    private fun readBatteryStatus(): Triple<Int, Boolean, String> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return Triple(-1, false, "")
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val pct = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val statusLabel = when (status) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
            BatteryManager.BATTERY_STATUS_FULL -> "FULL"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT CHARGING"
            else -> "UNKNOWN"
        }
        return Triple(pct, isCharging, statusLabel)
    }

    /** Best-effort SSID read. Returns null when the SSID isn't
     *  readable (location permission missing, no WiFi). The string
     *  "<unknown ssid>" is also surfaced as null because it's the
     *  system's 'we can't tell you' placeholder. */
    @Suppress("DEPRECATION")
    private fun readWifiSsid(): String? {
        return runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                ?: return null
            val info = wifi.connectionInfo ?: return null
            val raw = info.ssid?.trim('"').orEmpty()
            raw.takeIf { it.isNotBlank() && it != "<unknown ssid>" }
        }.getOrNull()
    }

    override fun onCleared() {
        super.onCleared()
        runCatching { cameraManager?.unregisterTorchCallback(torchCallback) }
        windowBrightnessApplier = null
    }

    companion object {
        fun factory(app: App) = viewModelFactory {
            initializer { DeviceViewModel(app) }
        }
    }
}
