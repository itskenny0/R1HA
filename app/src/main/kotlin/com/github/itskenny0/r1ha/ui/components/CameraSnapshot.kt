package com.github.itskenny0.r1ha.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
     *  feeling much smoother given HA's typical camera-fetch latency. */
    intervalMillis: Long = 4_000L,
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
                while (true) {
                    val cb = System.currentTimeMillis()
                    val url = "${serverUrl.trimEnd('/')}/api/camera_proxy/$entityId?cb=$cb"
                    val image = runCatching { fetchSnapshot(url, bearerToken, targetWidthPx, targetHeightPx) }
                        .onFailure { R1Log.d("Camera", "fetch $entityId failed: ${it.message}") }
                        .getOrNull()
                    if (image != null) {
                        bitmap = image
                        failed = false
                    } else if (bitmap == null) {
                        // Only flip into the failed-with-no-last-frame state if we never
                        // got anything. A transient failure mid-stream just keeps the
                        // previous frame visible until the next poll lands.
                        failed = true
                    }
                    delay(intervalMillis)
                }
            }
        }
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = entityId,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
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

/** Module-scoped OkHttp client for camera fetches. Separate from
 *  AsyncBitmapCache's client so a slow camera doesn't park the
 *  album-art request queue. */
private val cameraHttp: OkHttpClient by lazy {
    OkHttpClient.Builder()
        // Long-ish read timeout — some integrations (Reolink / Doorbird)
        // generate the snapshot on demand and a sub-5 s read can clip
        // them. The polling loop keeps things lively in spite of this.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
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
        cameraHttp.newCall(builder.build()).execute().use { resp ->
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
