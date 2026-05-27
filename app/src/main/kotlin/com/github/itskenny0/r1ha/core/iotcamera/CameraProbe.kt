package com.github.itskenny0.r1ha.core.iotcamera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Active-probe scan that walks a range of camera ids and reports which
 * ones successfully **produce a frame** — not just open. Stub-cameras
 * and placeholder slots that Xiaomi's HAL exposes will pass the
 * static-characteristics filter, sometimes even pass `openCamera`,
 * but never deliver an `onImageAvailable` callback. The only reliable
 * test is "open it, capture once, see if a frame arrives within the
 * timeout".
 *
 * Runs sequentially because cameras don't generally parallelise (the
 * HAL serializes opens anyway) and we want to keep memory + power
 * costs predictable. Each id takes [PROBE_TIMEOUT_MS] worst-case;
 * total run on a 20-slot device is ~bounded by 20 * timeout.
 *
 * Caller drives a coroutine that pumps progress via [onProgress] —
 * settings screen renders the current index + total so the user sees
 * the bar move and knows roughly how long it'll take.
 */
object CameraProbe {

    /** Per-id timeout: open + configure + capture + first frame. ~3 s
     *  is comfortable for slow HALs while keeping a 20-slot full scan
     *  under a minute. */
    const val PROBE_TIMEOUT_MS = 3_000L

    /** Upper bound on the id range we walk. Most OEMs keep hidden ids
     *  in the 0-9 range; 20 leaves headroom without making the scan
     *  feel interminable. */
    const val PROBE_MAX_ID = 19

    /** Settle between candidate opens. Xiaomi HAL revisions hold
     *  exclusive locks for up to a second after close(); pushing past
     *  that wedges the camera service entirely until app restart. 2 s
     *  is the empirically-determined floor. */
    const val INTER_PROBE_SETTLE_MS = 2_000L

    /** If this many candidates fail in a row, the HAL is probably
     *  exhausted (Xiaomi devices stop accepting ANY opens after ~3
     *  failures in close succession, including the standard back +
     *  front cameras). Abort the scan instead of continuing to wedge
     *  the camera service. */
    const val CONSECUTIVE_FAILURE_ABORT = 2

    /** Result of probing a single id. Surfaced for both the cache and
     *  the in-UI progress display so the user sees "✓ id 3 worked"
     *  scroll past as the scan runs. [fingerprint] captures the
     *  HAL-stable identity (facing + focal + max size) at probe time
     *  so the cache can detect when the id later refers to a different
     *  sensor. */
    data class Outcome(
        val id: String,
        val producedFrame: Boolean,
        val failureReason: String?,
        val fingerprint: String?,
    )

    /**
     * Probe every id in `0..PROBE_MAX_ID` and report outcomes.
     *
     * [excludeIds] should pass the union of ids already known to be
     * valid via `CameraManager.cameraIdList` + `INFO_PHYSICAL_CAMERA_IDS`
     * — those don't need re-probing, and skipping them saves several
     * timeout windows per scan.
     *
     * [onProgress] fires before each id is probed with `(currentIndex,
     * totalToProbe, lastOutcome)` so the UI can update its bar + a
     * "Testing id X…" label. `lastOutcome` is null for the very first
     * call; subsequent calls pass the previous id's result so the UI
     * can also show "Last: id 2 — no frame".
     */
    suspend fun scan(
        context: Context,
        excludeIds: Set<String>,
        onProgress: (current: Int, total: Int, last: Outcome?) -> Unit,
    ): List<Outcome> {
        // Always operate on the Application context — Camera2's
        // CameraDeviceImpl retains an `mContext` reference internally
        // that survives Java-side close() because the native HAL state
        // outlives our wrapper. Pinning to Application means the
        // surviving reference is to a singleton that lives forever
        // anyway, instead of to a torn-down Service / Activity which
        // would leak (LeakCanary confirmed this exact path on Xiaomi).
        val appCtx = context.applicationContext
        if (ContextCompat.checkSelfPermission(appCtx, Manifest.permission.CAMERA)
            != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the permission, openCamera throws SecurityException
            // immediately — surfacing as a generic "failed" per-id is
            // misleading. Bail with no outcomes so the caller can prompt
            // the user to grant the permission and retry.
            return emptyList()
        }
        val manager = appCtx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptyList()
        // Strict pre-filter: only ids that look like real cameras even
        // get probed. The fuller open-then-capture test wedges some
        // HALs (Xiaomi specifically) after a few rapid opens, so we
        // want to minimize the number of attempted opens. Anything that
        // doesn't pass the static checks gets skipped silently — those
        // are placeholder slots / depth helpers that wouldn't deliver
        // frames anyway, and skipping them doesn't cost the user
        // anything visible.
        val candidates = (0..PROBE_MAX_ID).map { it.toString() }
            .filter { it !in excludeIds }
            .filter { looksLikeRealCamera(manager, it) }
        val outcomes = mutableListOf<Outcome>()
        val thread = HandlerThread("r1ha-cameraprobe").apply { start() }
        val handler = Handler(thread.looper)
        try {
            var lastOutcome: Outcome? = null
            var consecutiveFailures = 0
            for ((index, id) in candidates.withIndex()) {
                onProgress(index + 1, candidates.size, lastOutcome)
                val outcome = probeOne(manager, id, handler)
                outcomes.add(outcome)
                lastOutcome = outcome
                if (outcome.producedFrame) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= CONSECUTIVE_FAILURE_ABORT) {
                        // Bail. Xiaomi HALs stop accepting any new opens
                        // (including the standard back / front cameras)
                        // after ~3 failed opens in close succession, and
                        // the only recovery is an app restart. Aborting
                        // here keeps the user's known-good cameras
                        // working even on a fruitless scan.
                        R1Log.w(
                            "CameraProbe",
                            "aborting scan after $consecutiveFailures consecutive failures",
                        )
                        break
                    }
                }
            }
            // Final progress fire so the UI's "last result" shows the
            // final id rather than being one outcome behind.
            onProgress(candidates.size, candidates.size, lastOutcome)
        } finally {
            thread.quitSafely()
        }
        return outcomes
    }

    /**
     * Cheap static check: does this id look like something we'd ever
     * be able to stream from? Filters out placeholder / depth-helper
     * slots before they cost us a full open attempt. We require:
     *
     *   - Characteristics readable (catches "id doesn't exist");
     *   - INFO_SUPPORTED_HARDWARE_LEVEL set (placeholder slots return null);
     *   - LENS_FACING set;
     *   - At least one focal length > 0.5 mm (real lenses are 1-8 mm sensor-real);
     *   - SENSOR_INFO_PHYSICAL_SIZE non-null;
     *   - At least one JPEG output >= 320x240.
     */
    private fun looksLikeRealCamera(manager: CameraManager, id: String): Boolean = runCatching {
        val chars = manager.getCameraCharacteristics(id)
        chars.get(android.hardware.camera2.CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
            ?: return@runCatching false
        chars.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING)
            ?: return@runCatching false
        val focals = chars.get(
            android.hardware.camera2.CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS,
        ) ?: return@runCatching false
        if (focals.isEmpty() || focals.maxOrNull() ?: 0f <= 0.5f) return@runCatching false
        chars.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            ?: return@runCatching false
        val streamMap = chars.get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        ) ?: return@runCatching false
        val jpegSizes = streamMap.getOutputSizes(ImageFormat.JPEG) ?: return@runCatching false
        jpegSizes.any { it.width >= 320 && it.height >= 240 }
    }.getOrDefault(false)

    private suspend fun probeOne(
        manager: CameraManager,
        id: String,
        handler: Handler,
    ): Outcome {
        // Quick pre-flight: characteristics must exist + report a JPEG
        // size we can read into. Skips ids that wouldn't even survive
        // the bootstrap; without this we'd spend 3 s timing out on
        // every empty slot.
        val size = runCatching {
            val chars = manager.getCameraCharacteristics(id)
            val streamMap = chars.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
            ) ?: return@runCatching null
            streamMap.getOutputSizes(ImageFormat.JPEG)
                ?.minByOrNull { it.width * it.height }
        }.getOrNull() ?: return Outcome(id, false, "no characteristics / no JPEG sizes", null)

        // Capture the stable fingerprint now so the success path can
        // hand it to the cache without re-querying characteristics —
        // matters because the id might already have been reshuffled by
        // the HAL between this read and a later one.
        val fingerprint = CameraExtrasCache.fingerprintOf(manager, id)

        val reader = runCatching {
            ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 2)
        }.getOrNull() ?: return Outcome(id, false, "ImageReader.newInstance failed", fingerprint)
        val frameReceived = CompletableDeferred<Boolean>()
        reader.setOnImageAvailableListener({ r ->
            // Drain whatever's there + signal success on the first
            // delivered frame. We don't care about the contents — the
            // mere fact that the HAL produced an image proves this id
            // can stream.
            runCatching {
                var img = r.acquireLatestImage()
                while (img != null) {
                    img.close()
                    img = r.acquireLatestImage()
                }
            }
            frameReceived.complete(true)
        }, handler)

        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null
        val outcome = try {
            withTimeoutOrNull(PROBE_TIMEOUT_MS) {
                val opened = CompletableDeferred<CameraDevice?>()
                runCatching {
                    @SuppressWarnings("MissingPermission")
                    manager.openCamera(id, object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            opened.complete(camera)
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            runCatching { camera.close() }
                            opened.complete(null)
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            runCatching { camera.close() }
                            opened.complete(null)
                        }
                    }, handler)
                }.onFailure {
                    opened.complete(null)
                }
                val cam = opened.await() ?: return@withTimeoutOrNull Outcome(
                    id, false, "openCamera failed / disconnected", fingerprint,
                )
                device = cam
                val configured = CompletableDeferred<CameraCaptureSession?>()
                val configCallback = object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(s: CameraCaptureSession) {
                        configured.complete(s)
                    }

                    override fun onConfigureFailed(s: CameraCaptureSession) {
                        configured.complete(null)
                    }
                }
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        val config = SessionConfiguration(
                            SessionConfiguration.SESSION_REGULAR,
                            listOf(OutputConfiguration(reader.surface)),
                            { runnable -> handler.post(runnable) },
                            configCallback,
                        )
                        cam.createCaptureSession(config)
                    } else {
                        @Suppress("DEPRECATION")
                        cam.createCaptureSession(
                            listOf(reader.surface), configCallback, handler,
                        )
                    }
                }.onFailure {
                    configured.complete(null)
                }
                val s = configured.await() ?: return@withTimeoutOrNull Outcome(
                    id, false, "createCaptureSession failed", fingerprint,
                )
                session = s
                runCatching {
                    val req = cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(reader.surface)
                    }
                    s.setRepeatingRequest(req.build(), null, handler)
                }.onFailure {
                    return@withTimeoutOrNull Outcome(
                        id, false, "setRepeatingRequest failed: ${it.message}", fingerprint,
                    )
                }
                val got = frameReceived.await()
                Outcome(
                    id,
                    got,
                    if (got) null else "no frame within ${PROBE_TIMEOUT_MS}ms",
                    fingerprint,
                )
            } ?: Outcome(id, false, "timed out after ${PROBE_TIMEOUT_MS}ms", fingerprint)
        } catch (t: Throwable) {
            R1Log.d("CameraProbe", "id $id threw: ${t.message}")
            Outcome(id, false, "exception: ${t.message ?: t::class.java.simpleName}", fingerprint)
        } finally {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            // HAL-settle delay between probes — see [INTER_PROBE_SETTLE_MS]
            // for why this is 2 s and not the more comfortable 250 ms.
            kotlinx.coroutines.delay(INTER_PROBE_SETTLE_MS)
        }
        return outcome
    }
}
