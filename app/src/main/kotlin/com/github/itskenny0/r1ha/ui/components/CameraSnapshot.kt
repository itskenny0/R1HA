package com.github.itskenny0.r1ha.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.repeatOnLifecycle
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Polling JPEG snapshot view backed by HA's `/api/camera_proxy/<entity_id>`
 * endpoint. Each fetch is a one-shot HTTPS GET with the access token in
 * the Authorization header; the result is decoded to an ImageBitmap and
 * painted.
 *
 * **Why not MJPEG.** HA exposes `camera_proxy_stream` for MJPEG too, but
 * MJPEG over HTTP keeps a long-running socket open per stream. On the R1
 * (LineageOS GSI / CipherOS) we've seen long-running HTTP sockets get
 * killed by Doze + power management mid-stream, and reconnecting is
 * choppy. Polling JPEG at 3-5 s gives us a "live enough" feel without
 * needing background-stream resilience.
 *
 * **Caching.** Deliberately bypassed — appending the current epoch
 * millis as a `?cb=` cache-buster guarantees we always see HA's latest
 * snapshot. Otherwise OkHttp would happily serve a 304 short-circuit
 * and the image would freeze.
 *
 * **Cancellation.** The LaunchedEffect cancels the loop when the
 * composable leaves composition (user backs out / pip moves on),
 * cleanly tearing down any in-flight request.
 */
@Composable
fun CameraSnapshot(
    serverUrl: String,
    bearerToken: String?,
    entityId: String,
    /** Polling interval — 4 s is the default. 1 s wastes data without
     *  feeling much smoother given HA's typical camera-fetch latency.
     *  The detail-overlay surfaces a slider that drives this all the
     *  way down to ~200 ms for users who want pseudo-realtime polling
     *  (true MJPEG would need a separate code path; not in scope here). */
    intervalMillis: Long = 4_000L,
    /** On-device display rotation in degrees, applied as a layer
     *  transform after decode so the source bytes / cache stay
     *  untouched. Practical values are 0 / 90 / 180 / 270 — anything
     *  else still renders but with non-axis-aligned bounds inside the
     *  Fit content scale. */
    rotationDegrees: Float = 0f,
    modifier: Modifier = Modifier,
) {
    var bitmap by remember(entityId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(entityId) { mutableStateOf(false) }
    // Suspend the polling loop when the host lifecycle drops below
    // STARTED: saves cellular data + battery on a handheld R1 left in
    // a pocket with the Cameras screen open. Polling resumes
    // automatically on ON_RESUME via repeatOnLifecycle's wiring.
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    // Wrap in BoxWithConstraints so we know the target tile size in pixels at decode
    // time. A 1920x1080 ARGB_8888 bitmap is ~8.3 MB; an 8-tile grid would otherwise
    // hold ~66 MB of camera frames on a 3 GB R1. Sampling down to the rendered size
    // (typically 360 dp wide on the R1) drops that to ~2-3 MB per tile and keeps
    // memory pressure off Doze-induced trim-memory kills.
    BoxWithConstraints(
        modifier = modifier.background(R1.SurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        val density = LocalDensity.current
        val targetWidthPx = with(density) { maxWidth.toPx().toInt().coerceAtLeast(1) }
        val targetHeightPx = with(density) { maxHeight.toPx().toInt().coerceAtLeast(1) }
        LaunchedEffect(entityId, serverUrl, bearerToken, intervalMillis, lifecycleOwner, targetWidthPx, targetHeightPx) {
            lifecycleOwner.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                // Per-loop consecutive-failure counter. Stays inside repeatOnLifecycle so it
                // resets when the lifecycle re-enters STARTED — coming back from background
                // with a fresh token deserves a fast first attempt rather than inheriting
                // backoff state from a previous failure run.
                var failureCount = 0
                while (true) {
                    val cb = System.currentTimeMillis()
                    val url = "${serverUrl.trimEnd('/')}/api/camera_proxy/$entityId?cb=$cb"
                    val image = runCatching { fetchSnapshot(url, bearerToken, targetWidthPx, targetHeightPx) }
                        .onFailure { R1Log.d("Camera", "fetch $entityId failed: ${it.message}") }
                        .getOrNull()
                    if (image != null) {
                        bitmap = image
                        failed = false
                        failureCount = 0
                    } else if (bitmap == null) {
                        // Only flip into the failed-with-no-last-frame state if we never
                        // got anything. A transient failure mid-stream just keeps the
                        // previous frame visible until the next poll lands.
                        failed = true
                    }
                    // Exponential backoff on consecutive failures so a broken camera entity
                    // (offline integration, stale bearer → 401, broken proxy) doesn't pin
                    // /api/camera_proxy at the full poll cadence. Default cadence is 4 s;
                    // unchecked, a stale-token 401 every 4 s hits HA's
                    // login_attempts_threshold (default 5) inside half a minute and IP-bans
                    // the device. After 5 failures we plateau at 64 s ≈ HA-friendly cadence.
                    val nextDelay = if (image == null) {
                        failureCount = (failureCount + 1).coerceAtMost(4)
                        val shifted = intervalMillis shl failureCount
                        minOf(shifted, MAX_BACKOFF_MILLIS)
                    } else {
                        intervalMillis
                    }
                    delay(nextDelay)
                }
            }
        }
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = entityId,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .let { m ->
                        if (rotationDegrees != 0f) m.rotate(rotationDegrees) else m
                    },
            )
        } else if (failed) {
            Text(text = "NO SIGNAL", style = R1.labelMicro, color = R1.InkMuted)
        } else {
            // Initial-load state: empty SurfaceMuted box matches AsyncBitmap's
            // first-frame behaviour rather than a spinner that flickers and
            // disappears within a second.
        }
    }
}

/** Ceiling on the failure-backoff delay between poll attempts. */
private const val MAX_BACKOFF_MILLIS: Long = 60_000L

/**
 * Compute inSampleSize as the largest power of two that keeps the decoded image at
 * least as large as the target. BitmapFactory rounds down on non-power-of-two values
 * silently, so doing the math here makes the intent explicit and predictable.
 */
private fun computeSampleSize(srcW: Int, srcH: Int, targetW: Int, targetH: Int): Int {
    if (srcW <= 0 || srcH <= 0 || targetW <= 0 || targetH <= 0) return 1
    var sample = 1
    while (srcW / (sample * 2) >= targetW && srcH / (sample * 2) >= targetH) sample *= 2
    return sample
}

/**
 * Holder for the camera-fetch OkHttp client. Kept separate from AsyncBitmapCache's client so a
 * slow camera doesn't park the album-art request queue, but wired to the SAME [AuthThrottle] as
 * the rest of the app (via [init] from App.onCreate) so that:
 *   - a stale-token 401 from `/api/camera_proxy` counts toward opening the shared breaker, and
 *   - once the breaker is open, camera polls short-circuit locally (synthetic 503) instead of
 *     hammering HA's failed-login counter every few seconds.
 *
 * Before [init] runs (or in unit/preview contexts), [client] falls back to a plain client with no
 * breaker, so a screenshot test or a Compose preview still renders.
 */
object CameraHttp {
    @Volatile private var configured: OkHttpClient? = null

    /** Wire the shared breaker. [maxConcurrent] follows the user's connection setting. */
    fun init(
        throttle: com.github.itskenny0.r1ha.core.ha.AuthThrottle,
        maxConcurrent: () -> Int,
    ) {
        if (configured != null) return
        configured = baseBuilder()
            .addInterceptor(
                com.github.itskenny0.r1ha.core.ha.AuthThrottleInterceptor(
                    throttle,
                    dynamicMaxConcurrent = maxConcurrent,
                ),
            )
            .build()
    }

    fun client(): OkHttpClient = configured ?: fallback

    private fun baseBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            // Long-ish read timeout — some integrations (Reolink / Doorbird) generate the
            // snapshot on demand and a sub-5 s read can clip them. The polling loop keeps
            // things lively in spite of this.
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)

    private val fallback: OkHttpClient by lazy { baseBuilder().build() }
}

private suspend fun fetchSnapshot(
    url: String,
    bearerToken: String?,
    targetWidthPx: Int,
    targetHeightPx: Int,
): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        CameraHttp.client().newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.bytes() ?: return@withContext null
            // Two-pass decode: first measure the source dimensions with inJustDecodeBounds,
            // then compute a sample size that brings the decoded bitmap close to the tile
            // size, and finally decode at that sample. RGB_565 halves memory vs ARGB_8888;
            // camera JPEGs don't carry alpha so the colour loss is invisible.
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = computeSampleSize(bounds.outWidth, bounds.outHeight, targetWidthPx, targetHeightPx)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
        }
    }
