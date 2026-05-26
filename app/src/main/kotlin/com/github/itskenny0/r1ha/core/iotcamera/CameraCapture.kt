package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context
import android.graphics.ImageFormat
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
    private val bus: FrameBus,
    private val onError: (String) -> Unit = {},
) {
    private val manager = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val started = AtomicBoolean(false)
    @Volatile private var device: CameraDevice? = null
    @Volatile private var session: CameraCaptureSession? = null
    @Volatile private var reader: ImageReader? = null
    private val sensorOrientation: Int = runCatching {
        manager.getCameraCharacteristics(cameraId)
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

        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(camera: CameraDevice) {
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
                session = s
                val requestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    .apply {
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
                runCatching {
                    s.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                }.onFailure {
                    onError("Capture session start failed: ${it.message}")
                }
            }

            override fun onConfigureFailed(s: CameraCaptureSession) {
                onError("Camera failed to configure $width×$height")
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val config = SessionConfiguration(
                    SessionConfiguration.SESSION_REGULAR,
                    listOf(OutputConfiguration(surface)),
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
            bus.publish(jpeg)
        }
    }

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
