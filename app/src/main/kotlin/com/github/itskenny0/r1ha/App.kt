package com.github.itskenny0.r1ha

import android.app.Application
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class App : Application() {

    val graph: AppGraph by lazy { AppGraph(this) }

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Debug-only main-thread health probe. Every [PROBE_INTERVAL_MS] we post a
     * sentinel Runnable that toggles a flag; from a background thread we sleep
     * for the same interval and check whether the flag flipped. If it didn't,
     * the main thread is stuck and we dump its stack. False positives are
     * possible during GC pauses but the threshold is generous enough that
     * routine work rarely trips it.
     */
    private fun startDebugAnrWatchdog() {
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        val tick = java.util.concurrent.atomic.AtomicLong(0L)
        val probeIntervalMs = 5_000L
        val anrThresholdMs = 4_000L
        Thread({
            var lastSeen = -1L
            while (true) {
                main.post { tick.incrementAndGet() }
                try {
                    Thread.sleep(probeIntervalMs)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val now = tick.get()
                if (now == lastSeen) {
                    val trace = android.os.Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "  at $it" }
                    R1Log.w(
                        "App.anr",
                        "main thread didn't tick in ${anrThresholdMs / 1000}s\n$trace",
                    )
                }
                lastSeen = now
            }
        }, "r1ha-anr-watchdog").apply { isDaemon = true }.start()
    }

    override fun onCreate() {
        androidx.tracing.Trace.beginSection("R1HA.App.onCreate")
        super.onCreate()
        // Debug-only StrictMode: catches main-thread disk + network I/O and common
        // VM-policy leaks (closeable not closed, untagged sockets, etc.). Release
        // builds never install StrictMode so end-users don't pay the runtime cost
        // or eat false-positive crashes if a third-party process throws.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder()
                    .detectDiskReads()
                    .detectDiskWrites()
                    .detectNetwork()
                    .penaltyLog()
                    .build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects()
                    .detectLeakedRegistrationObjects()
                    .detectActivityLeaks()
                    .penaltyLog()
                    .build(),
            )
        }
        Toaster.init(this)
        // Wire the album-art cache to the app's cache dir so HA media_player
        // entity_pictures persist across launches. Disk hit ≈ 0 ms vs the ~300
        // ms LAN round-trip every fresh fetch costs on the R1's slow stack.
        com.github.itskenny0.r1ha.ui.components.AsyncBitmapCache.init(this)
        // Debug-only ANR watchdog: posts a sentinel to the main looper every 5 s
        // and a paired check-completion ping; if the ping doesn't fire within the
        // ANR threshold the main thread's current stack trace is logged. Cheap
        // (~one Runnable per 5 s) and silent unless something's actually stuck.
        // Release builds skip this so end-users don't pay even that cost.
        if (BuildConfig.DEBUG) {
            startDebugAnrWatchdog()
        }
        // Install a JVM-wide uncaught-exception handler that copies the
        // stack trace into R1LogBuffer BEFORE chaining to the previous
        // handler (which lets Android's default behaviour — crash dialog +
        // process death — proceed). Means the dev menu's log viewer can
        // surface the crash that just happened on next launch, and the
        // user can EXPORT the logs to a file via SAF for diagnostics.
        // Without this, every crash on the R1 was a black box; logcat isn't
        // accessible to most users.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, ex ->
            runCatching {
                R1Log.e(
                    "App.uncaught",
                    "thread=${thread.name} ex=${ex::class.java.name}: ${ex.message}",
                    ex,
                )
                // Write the crash trace + recent log buffer to a file in
                // filesDir so the dev menu's 'LAST CRASH' button can surface
                // it after restart. Without this every crash is a black box
                // on the R1: logcat isn't accessible to most users.
                //
                // Two-phase write so an OOM at the buildString step doesn't lose
                // the actual stack trace. Phase 1 writes the bare minimum
                // (exception + stack) using only pre-allocated capacity; phase 2
                // appends the recent-log tail and runs inside an inner runCatching
                // so a failure on the second buffer doesn't lose phase 1.
                val crashFile = java.io.File(filesDir, "last_crash.txt")
                runCatching {
                    val essential = StringBuilder(4096).apply {
                        append("R1HA crash · ").append(java.time.Instant.now().toString()).append('\n')
                        append("App ").append(BuildConfig.VERSION_NAME).append(" (")
                            .append(BuildConfig.VERSION_CODE).append(")\n")
                        append("Thread: ").append(thread.name).append('\n')
                        append("Exception: ").append(ex::class.java.name)
                        ex.message?.let { append(": ").append(it) }
                        append("\n\nSTACK TRACE:\n")
                        append(ex.stackTraceToString())
                    }
                    crashFile.writeText(essential.toString(), Charsets.UTF_8)
                }
                runCatching {
                    val logs = com.github.itskenny0.r1ha.core.util.R1LogBuffer.snapshot().reversed()
                    val tail = StringBuilder(8192).apply {
                        append("\n\nRECENT LOGS (newest first):\n")
                        for (e in logs.take(100)) {
                            val ts = java.time.Instant.ofEpochMilli(e.timestampMillis).toString()
                            append("[$ts] ").append(e.level).append(' ').append(e.tag)
                                .append(" — ").append(e.message).append('\n')
                        }
                    }
                    crashFile.appendText(tail.toString(), Charsets.UTF_8)
                }
            }
            previousHandler?.uncaughtException(thread, ex)
        }
        R1Log.i("App.onCreate", "application starting")
        appScope.launch {
            androidx.tracing.Trace.beginSection("R1HA.haRepository.start")
            try {
                graph.haRepository.start()
            } finally {
                androidx.tracing.Trace.endSection()
            }
            R1Log.i("App.onCreate", "haRepository.start() returned")
        }
        androidx.tracing.Trace.endSection()
        // Kick off the HA-backed settings sync manager. Idle until the user
        // opts in via Settings → Integrations → "Sync with Home Assistant";
        // once enabled, the manager pulls a baseline from HA, pushes local
        // edits (debounced) and periodically polls for remote changes.
        graph.haSettingsSync.start()

        // Mirror the user's hardware key bindings into a volatile field so MainActivity's
        // dispatchKeyEvent (which runs on the UI thread and can't suspend) can resolve a
        // keycode → action synchronously. Missing entries fall back to the built-in
        // DEFAULT_KEY_BINDINGS; an empty list under a known action explicitly clears that
        // binding so users can unbind a default they don't want without affecting the rest.
        appScope.launch {
            graph.settings.settings
                .map { it.keyBindings }
                .distinctUntilChanged()
                .collect { stored ->
                    val merged = com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS
                        .mapKeys { it.key.name }
                        .toMutableMap()
                    // Stored entries (even empty lists) authoritatively override the
                    // default for that action.
                    for ((name, codes) in stored) merged[name] = codes
                    val resolved = buildMap {
                        for ((name, codes) in merged) {
                            val action = runCatching {
                                com.github.itskenny0.r1ha.core.input.KeyAction.valueOf(name)
                            }.getOrNull() ?: continue
                            put(action, codes)
                        }
                    }
                    graph.latestBindings =
                        com.github.itskenny0.r1ha.core.input.KeyBindings(resolved)
                }
        }
        // Honour the background-refresh advanced toggle: schedule or cancel the periodic
        // JobService on every emission so a flip-flop at runtime takes effect on the
        // next setting tick rather than waiting for an app restart. JobScheduler is
        // idempotent on schedule with the same JOB_ID.
        appScope.launch {
            graph.settings.settings
                .map { it.advanced.backgroundRefreshEnabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        com.github.itskenny0.r1ha.core.background.BackgroundRefreshJob.schedule(this@App)
                    } else {
                        com.github.itskenny0.r1ha.core.background.BackgroundRefreshJob.cancel(this@App)
                    }
                }
        }
        // HA notification mirror — same observe-and-react pattern as the background job,
        // so a toggle flip immediately starts or stops the mirror without an app restart.
        appScope.launch {
            graph.settings.settings
                .map { it.advanced.mirrorHaNotifications }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        com.github.itskenny0.r1ha.core.notifications.HaNotificationMirror.start(this@App)
                    } else {
                        com.github.itskenny0.r1ha.core.notifications.HaNotificationMirror.stop()
                    }
                }
        }
        // Webhook listener — same observe-and-react pattern. The service holds
        // a foreground notification; the start/stop calls are cheap so flipping
        // port or webhook_id without toggling off-then-on is also safe because
        // the service restarts with the new extras.
        appScope.launch {
            graph.settings.settings
                .map {
                    Triple(it.advanced.webhookEnabled, it.advanced.webhookPort, it.advanced.webhookId)
                }
                .distinctUntilChanged()
                .collect { (enabled, port, id) ->
                    if (enabled) {
                        com.github.itskenny0.r1ha.core.webhook.WebhookListenerService.start(
                            this@App, port, id,
                        )
                    } else {
                        com.github.itskenny0.r1ha.core.webhook.WebhookListenerService.stop(this@App)
                    }
                }
        }

        // IoT Camera Mode — observe the master toggle. The service itself
        // re-reads structural settings (camera id, resolution, fps, sink
        // toggles) from the same flow, so flipping any sub-toggle just
        // restarts the pipeline; the App-level observer only decides
        // service alive vs dead. Before starting, seed the per-device
        // secrets (random MJPEG password + node id) if the user hasn't
        // set them — never broadcast an open MJPEG stream.
        appScope.launch {
            graph.settings.settings
                .map { it.iotCamera.enabled }
                .distinctUntilChanged()
                .collect { enabled ->
                    if (enabled) {
                        // Seed missing per-device fields under the settings
                        // mutex so a concurrent edit can't clobber what we
                        // just wrote. No-op when the user already set them.
                        graph.settings.update { current ->
                            val c = current.iotCamera
                            val patched = c.copy(
                                mjpegPassword = c.mjpegPassword.ifBlank { randomToken(16) },
                                mqttNodeId = c.mqttNodeId.ifBlank { randomToken(8).lowercase() },
                            )
                            if (patched == c) current else current.copy(iotCamera = patched)
                        }
                        com.github.itskenny0.r1ha.core.iotcamera.IotCameraService.start(this@App)
                    } else {
                        com.github.itskenny0.r1ha.core.iotcamera.IotCameraService.stop(this@App)
                    }
                }
        }

        // iBeacon — observe the four backing fields (enabled + UUID + major +
        // minor) together so a UUID change while the toggle is on tears down
        // and re-starts with the new payload. Distinct on the full tuple so
        // unrelated settings emissions don't re-arm the advertiser.
        appScope.launch {
            graph.settings.settings
                .map { s ->
                    Quad(
                        s.advanced.iBeaconEnabled,
                        s.advanced.iBeaconUuid,
                        s.advanced.iBeaconMajor,
                        s.advanced.iBeaconMinor,
                    )
                }
                .distinctUntilChanged()
                .collect { (enabled, uuid, major, minor) ->
                    if (enabled) {
                        com.github.itskenny0.r1ha.core.ibeacon.IBeaconAdvertiser.start(
                            this@App, uuid, major, minor,
                        )
                    } else {
                        com.github.itskenny0.r1ha.core.ibeacon.IBeaconAdvertiser.stop()
                    }
                }
        }
    }

    /** Local 4-tuple. Kotlin's stdlib only has Pair / Triple; this carries
     *  the iBeacon enabled flag + the three identity fields so the settings
     *  observer can react to any combination changing. */
    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Random alphanumeric token. Used to seed the IoT Camera Mode MJPEG
     *  password + MQTT node id on first enable so the user never broadcasts
     *  an auth-less stream onto their LAN. */
    private fun randomToken(length: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return (1..length).map { chars.random() }.joinToString("")
    }
}
