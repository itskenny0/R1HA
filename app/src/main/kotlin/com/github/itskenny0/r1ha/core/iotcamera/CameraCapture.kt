package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Size
import com.github.itskenny0.r1ha.core.util.R1Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns the Camera2 capture session for IoT Camera Mode. Streams YUV frames
 * from the chosen logical camera through an [ImageReader], encodes each one
 * to JPEG via [YuvImage] at the requested quality, and pushes the bytes
 * into [bus] for the sinks (MJPEG / MQTT) to fan out.
 *
 * Why YUV → manual JPEG instead of `ImageFormat.JPEG`:
 *   - JPEG ImageReader on most devices uses the hardware encoder, which is
 *     fast but commits the device's vendor implementation to deliver a
 *     frame on every capture request. Some R1 / mid-range Android camera
 *     HALs only deliver a JPEG every few requests, capping us at 2-3 fps
 *     when the user asked for 10.
 *   - YUV_420_888 lets us request frames at the user's chosen rate via
 *     `CONTROL_AE_TARGET_FPS_RANGE`, and the per-frame encode is cheap
 *     enough on modern hardware (a 1280×720 YUV→JPEG runs in <20 ms on a
 *     Pixel 4a class device; the R1's Helio G36 manages it in ~40 ms which
 *     still clears 20 fps with headroom).
 *   - Manual encode gives us precise [jpegQuality] control. JPEG ImageReader
 *     respects `JPEG_QUALITY` but vendor drivers clamp it inconsistently.
 *
 * Frame-rate gating: when [FrameBus.subscriberCount] is zero we still keep
 * the camera open + repeating capture running (re-opening is expensive),
 * but we skip the YUV→JPEG encode step. That keeps the master toggle's
 * "ready to stream" state cheap while no sink is connected.
 */
class CameraCapture(
    private val context: Context,
    private val cameraId: String,
    private val width: Int,
    private val height: Int,
    private val targetFps: Int,
    private val jpegQuality: Int,
    /** Sender-side rotation in degrees (0 / 90 / 180 / 270). Non-zero
     *  values trigger a decode → Matrix.postRotate → re-encode per
     *  frame, which costs ~10-30 ms on modern hardware and caps the
     *  practical fps below what the raw pipeline would do. */
    private val rotationDegrees: Int,
    private val bus: FrameBus,
    private val onError: (String) -> Unit = {},
) {
    // Always go through applicationContext for the camera manager.
    // Camera2's CameraDeviceImpl retains its `mContext` reference for
    // the lifetime of the native session — if we hand it the service
    // context, that reference outlives the service and leaks the whole
    // thing (LeakCanary caught this exact path on Xiaomi). Pinning to
    // Application means the surviving reference is to a singleton that
    // lives forever anyway, so no leak.
    private val manager =
        context.applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    /**
     * Resolve [cameraId] into the logical id we open + the optional
     * physical id we pin the output stream to.
     *
     * IDs prefixed with `phys:` were synthesized by [CameraEnumerator]
     * for physical sub-sensors of a logical camera (used by multi-lens
     * devices like the Xiaomi 9T that bundle wide / tele / ultrawide
     * behind a single logical "back" id). Format: `phys:<logical>:<phys>`.
     */
    private val openId: String
    private val physicalCameraId: String?
    init {
        if (cameraId.startsWith("phys:")) {
            val parts = cameraId.split(":")
            openId = parts.getOrNull(1).orEmpty()
            physicalCameraId = parts.getOrNull(2)
        } else {
            openId = cameraId
            physicalCameraId = null
        }
    }

    private val started = AtomicBoolean(false)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    private val sensorOrientation: Int = runCatching {
        // Pull from the physical when we have one (its sensor orientation
        // can differ from the logical wrapper's), otherwise the logical id.
        val charsId = physicalCameraId ?: openId
        manager.getCameraCharacteristics(charsId)
            .get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
    }.getOrDefault(0)

    /** Dedicated background thread for Camera2 callbacks — the API insists
     *  on a Handler-driven dispatcher and the main thread is a non-starter
     *  (callbacks would race with Compose recomposition). HandlerThread
     *  outlives [start]/[stop] cycles because re-creating it adds ~150 ms
     *  latency on every toggle flip. */
    private val cameraThread = HandlerThread("r1ha-iotcamera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)

    /** Throttles the YUV→JPEG encode to the user's target fps. Camera2's
     *  TARGET_FPS_RANGE is a hint, not a contract, so the HAL frequently
     *  delivers frames faster than requested; we drop the extras here. */
    private val frameIntervalMs: Long
        get() = (1000L / targetFps.coerceAtLeast(1)).coerceAtLeast(1L)
    private val lastEncodeAtMillis = AtomicLong(0L)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        runCatching { openCamera() }.onFailure { t ->
            R1Log.w("IotCamera.capture", "openCamera failed: ${t.message}", t)
            onError("Camera open failed: ${t.message ?: t::class.java.simpleName}")
            started.set(false)
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
    }

    /** Free the background thread. Call once on permanent teardown — not
     *  on every start/stop cycle, because rebuilding the looper is what
     *  makes a quick disable/enable take half a second. */
    fun shutdown() {
        stop()
        cameraThread.quitSafely()
    }

    @Suppress("MissingPermission") // CAMERA permission is checked by the caller.
    private fun openCamera() {
        val r = ImageReader.newInstance(width, height, ImageFormat.YUV_420_888, 2)
        reader = r
        r.setOnImageAvailableListener({ ir -> onYuvFrame(ir) }, cameraHandler)

        manager.openCamera(openId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
                // Race window: between openCamera() being called and onOpened
                // landing, stop() may have run (settings change tearing the
                // pipeline down, master toggle flipped off). Bail without
                // touching the now-stale state — just close the camera the
                // HAL handed us so we don't leak the FD.
                if (!started.get()) {
                    runCatching { camera.close() }
                    return
                }
                device = camera
                buildSession(camera, r)
            }

            override fun onDisconnected(camera: CameraDevice) {
                R1Log.i("IotCamera.capture", "camera $cameraId disconnected")
                runCatching { camera.close() }
                device = null
                onError("Camera was claimed by another app")
            }

            override fun onError(camera: CameraDevice, error: Int) {
                R1Log.w("IotCamera.capture", "camera $cameraId error=$error")
                runCatching { camera.close() }
                device = null
                onError("Camera error ($error)")
            }
        }, cameraHandler)
    }

    private fun buildSession(camera: CameraDevice, r: ImageReader) {
        val surface = r.surface
        val configCallback = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                // Same race as onOpened: stop() may have closed the device
                // between createCaptureSession() and onConfigured() firing.
                // createCaptureRequest() on the closed device throws
                // IllegalStateException, which crashed the iotcamera thread
                // in an early-2026 build. Bail cleanly if we're no longer
                // started or if the device has changed under us.
                if (!started.get() || device !== camera) {
                    runCatching { s.close() }
                    return
                }
                session = s
                // Wrap the whole request build + dispatch in runCatching —
                // the camera can be closed mid-flight even past the started
                // check, since stop() can run on another thread. A late
                // IllegalStateException here would otherwise tear down the
                // service via the uncaught handler.
                runCatching {
                    val requestBuilder = camera.createCaptureRequest(
                        CameraDevice.TEMPLATE_RECORD,
                    ).apply {
                        addTarget(surface)
                        // Hint the HAL toward the user's chosen rate. Real
                        // delivered rate depends on hardware ceilings + the
                        // current exposure (long exposure caps fps).
                        val target = targetFps.coerceAtLeast(1)
                        set(
                            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                            android.util.Range(target, target),
                        )
                        set(CaptureRequest.CONTROL_MODE, CameraCharacteristics.CONTROL_MODE_AUTO)
                    }
                    s.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                }.onFailure { t ->
                    R1Log.d("IotCamera.capture", "session start failed: ${t.message}")
                    onError("Capture session start failed: ${t.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("Camera failed to configure $width×$height")
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val outputConfig = OutputConfiguration(surface).also { oc ->
                    // Pin the output stream to the chosen physical sensor.
                    // This is the API path that lets a multi-camera device
                    // (Xiaomi 9T's wide / tele / ultrawide bundled under
                    // one logical "back" id) capture from a SPECIFIC lens
                    // rather than letting the HAL auto-zoom-fuse across
                    // them. Some OEMs restrict physical-camera output to
                    // privileged apps; if that's the case the session
                    // configures-failed callback fires and we surface the
                    // friendly error instead of crashing.
                    if (physicalCameraId != null) {
                        oc.setPhysicalCameraId(physicalCameraId)
                    }
                }
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(outputConfig),
                    { runnable -> cameraHandler.post(runnable) },
                    configCallback,
                )
                camera.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                camera.createCaptureSession(listOf(surface), configCallback, cameraHandler)
            }
        }.onFailure {
            onError("createCaptureSession threw: ${it.message}")
        }
    }

    private fun onYuvFrame(reader: ImageReader) {
        val image = runCatching { reader.acquireLatestImage() }.getOrNull() ?: return
        image.use { img ->
            // Skip encode work when nobody's listening. We still pulled the
            // frame from the buffer (otherwise the HAL stalls on buffer
            // pressure within ~3 frames) but we don't pay the encode cost
            // when the master toggle is on without any sink connected.
            if (bus.subscriberCount.value == 0) return
            val now = System.currentTimeMillis()
            val last = lastEncodeAtMillis.get()
            if (now - last < frameIntervalMs) return
            lastEncodeAtMillis.set(now)
            val jpeg = encodeYuvToJpeg(img) ?: return
            val rotated = if (rotationDegrees % 360 == 0) jpeg else rotateJpeg(jpeg, rotationDegrees)
            bus.publish(rotated)
        }
    }

    /**
     * Apply [degrees] to a JPEG by decoding → Matrix.postRotate →
     * re-encode at the same quality. Used when the user requests
     * sender-side rotation (settings → rotate button on the camera
     * lens picker). Returns the original bytes on any decode failure
     * so a transient malformed frame doesn't drop the stream.
     *
     * Future optimisation: write the EXIF Orientation tag into the
     * JPEG header instead. That would be near-zero-cost (HA + browsers
     * honour the EXIF tag on render) but the byte-manipulation code is
     * fiddly enough that V1 ships with the simpler re-encode path.
     */
    private fun rotateJpeg(jpeg: ByteArray, degrees: Int): ByteArray = runCatching {
        val source = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
            ?: return@runCatching jpeg
        try {
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                source, 0, 0, source.width, source.height, matrix, /* filter = */ false,
            )
            val out = java.io.ByteArrayOutputStream(source.width * source.height / 2)
            rotated.compress(Bitmap.CompressFormat.JPEG, jpegQuality.coerceIn(1, 100), out)
            if (rotated !== source) rotated.recycle()
            out.toByteArray()
        } finally {
            source.recycle()
        }
    }.getOrElse { jpeg }

    /**
     * Repack an Image's three YUV_420_888 planes into a contiguous NV21 byte
     * array — the input shape [YuvImage] requires — then JPEG-encode at
     * [jpegQuality]. Returns null on rare malformed frames so the publish
     * loop never crashes the service.
     *
     * Performance: the per-frame allocation is ~1.5 MB (640×480 NV21) which
     * the YoungGen GC handles without ceremony at 10-30 fps. A streaming
     * encoder (libjpeg-turbo via JNI) would skip the intermediate but adds a
     * native dependency + ABI sweep we don't want for the V1.
     */
    private fun encodeYuvToJpeg(image: Image): ByteArray? {
        return runCatching {
            val planes = image.planes
            if (planes.size < 3) return@runCatching null
            val y = planes[0].buffer
            val u = planes[1].buffer
            val v = planes[2].buffer
            val ySize = y.remaining()
            val uSize = u.remaining()
            val vSize = v.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            y.get(nv21, 0, ySize)
            // YuvImage expects NV21 (Y then interleaved VU); the second
            // chroma plane in YUV_420_888 is already V on most devices so
            // we copy V then U into the alternating slots. Some HALs swap
            // them; the picture comes out colour-shifted in that case and
            // the user can pick a different camera id.
            v.get(nv21, ySize, vSize)
            u.get(nv21, ySize + vSize, uSize)
            val yuv = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            val out = ByteArrayOutputStream(image.width * image.height / 2)
            val ok = yuv.compressToJpeg(
                Rect(0, 0, image.width, image.height),
                jpegQuality.coerceIn(1, 100),
                out,
            )
            if (!ok) return@runCatching null
            out.toByteArray()
        }.getOrElse { t ->
            R1Log.d("IotCamera.capture", "encode failed: ${t.message}")
            null
        }
    }

    @Suppress("unused") // Documented intent; used by future sink that needs orientation hint.
    val orientationDegrees: Int get() = sensorOrientation
}
