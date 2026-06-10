package com.github.itskenny0.r1ha.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken
import com.github.itskenny0.r1ha.core.theme.LocalHaServerUrl
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Shared image engine for all picture-card renderers. Mirrors HA's hui-image web
 * component (hui-image.ts), adapted for the R1 native client:
 *
 *  - aspect_ratio box sizing with intrinsic fallback (drops hard-coded heights)
 *  - camera_image + camera_view: auto (10 s poll) and live (1 s poll) via JPEG
 *  - filter / state_filter: CSS-ish string parsed to ColorMatrix
 *  - Default grayscale(100%) for off/unavailable entity states when no filter set
 *  - dark_mode_image / dark_mode_filter applied when [isDarkMode] is true
 *  - image.* domain: URL refreshed when entity state (capture timestamp) changes
 *  - media-source:// URLs: show broken-image placeholder (local resolve not wired)
 *  - Loading placeholder (muted surface) and broken-image placeholder ("?")
 *
 * The four picture-card renderers call this composable for all image rendering
 * and handle only their own overlays and tap actions.
 *
 * @param imageUrl         Final image URL to show (after state_image / dark_mode_image
 *                         selection by the caller); null = no static image.
 * @param cameraEntityId   Camera entity id for JPEG polling (null = no camera).
 * @param cameraView       "live" or "auto"; null defaults to "auto".
 * @param entityState      Raw entity state string (lowercase), drives off-state grayscale.
 * @param entityId         Entity id; drives isImageDomain detection.
 * @param filter           CSS-ish filter string ("grayscale(100%)").
 * @param aspectRatioStr   HA aspect_ratio config string ("16:9", "50%", "1.78").
 * @param fitMode          "cover" / "contain" / "fill" (null = cover).
 * @param isDarkMode       Whether the current app theme is dark.
 * @param darkModeFilter   Additional filter applied only when [isDarkMode] is true.
 * @param contentDescription Accessibility label forwarded to the image.
 * @param modifier         Applied to the outermost sizing Box.
 */
@Composable
fun HuiImage(
    imageUrl: String?,
    cameraEntityId: String? = null,
    cameraView: String? = null,
    entityState: String? = null,
    entityId: String? = null,
    filter: String? = null,
    aspectRatioStr: String? = null,
    fitMode: String? = null,
    isDarkMode: Boolean = true,
    darkModeFilter: String? = null,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val serverUrl = LocalHaServerUrl.current
    val bearerToken = LocalHaBearerToken.current

    val ratio = remember(aspectRatioStr) { ImageEngine.aspectRatioFloat(aspectRatioStr) }

    // Combine base filter + dark-mode filter; fall back to off-state grayscale.
    // ImageEngine.parseFilterString returns a FloatArray (20 values, row-major 4x5);
    // we wrap it in a Compose ColorMatrix here to keep ImageEngine Android-free.
    val colorFilter: ColorFilter? = remember(filter, darkModeFilter, entityState, entityId, isDarkMode) {
        val combined = buildString {
            if (!filter.isNullOrBlank()) append(filter)
            if (isDarkMode && !darkModeFilter.isNullOrBlank()) {
                if (isNotEmpty()) append(" ")
                append(darkModeFilter)
            }
        }
        when {
            combined.isNotBlank() ->
                ImageEngine.parseFilterString(combined)
                    ?.let { floats -> ColorFilter.colorMatrix(ColorMatrix(floats)) }
            entityId != null && ImageEngine.isOffState(entityState) -> {
                // Default grayscale(100%) for off/unavailable states (mirrors hui-image.ts DEFAULT_FILTER)
                val grayscale = ImageEngine.parseFilterString("grayscale(100%)")!!
                ColorFilter.colorMatrix(ColorMatrix(grayscale))
            }
            else -> null
        }
    }

    val camMode = remember(cameraEntityId, cameraView) {
        ImageEngine.cameraMode(cameraEntityId, cameraView)
    }

    // For image.* entities include the entity state as a re-fetch trigger.
    val imageFetchKey = remember(imageUrl, entityId, entityState) {
        if (entityId != null && ImageEngine.isImageDomain(entityId)) "$imageUrl|$entityState"
        else imageUrl
    }
    val resolvedStatic = remember(imageFetchKey, serverUrl) {
        imageFetchKey?.let { resolveHuiUrl(it.substringBefore('|'), serverUrl) }
    }

    val contentScale = fitModeToContentScale(fitMode)

    val boxModifier = if (ratio != null && ratio > 0f) {
        modifier.fillMaxWidth().aspectRatio(ratio)
    } else {
        modifier.fillMaxWidth()
    }

    Box(modifier = boxModifier.background(R1.SurfaceMuted)) {
        when (camMode) {
            ImageEngine.CameraMode.Static ->
                StaticImageLayer(resolvedStatic, bearerToken, contentScale, colorFilter, contentDescription)
            ImageEngine.CameraMode.Auto ->
                CameraImageLayer(serverUrl ?: "", bearerToken, cameraEntityId ?: "",
                    intervalMillis = 10_000L, contentScale, colorFilter, contentDescription)
            ImageEngine.CameraMode.Live ->
                // 1.2 s cadence for "live" view — same JPEG-poll approach as auto.
                // HLS/WebRTC is out of scope; see ImageEngine.cameraMode KDoc.
                CameraImageLayer(serverUrl ?: "", bearerToken, cameraEntityId ?: "",
                    intervalMillis = 1_200L, contentScale, colorFilter, contentDescription)
        }
    }
}

// ── StaticImageLayer ─────────────────────────────────────────────────────────

private enum class LoadState { Loading, Loaded, Error }

@Composable
private fun StaticImageLayer(
    url: String?,
    bearerToken: String?,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    contentDescription: String?,
) {
    if (url.isNullOrBlank()) return

    var bitmap by remember(url) { mutableStateOf(AsyncBitmapCache.peek(url)) }
    var state by remember(url) {
        mutableStateOf(if (bitmap != null) LoadState.Loaded else LoadState.Loading)
    }

    LaunchedEffect(url, bearerToken) {
        if (AsyncBitmapCache.peek(url) != null) { state = LoadState.Loaded; return@LaunchedEffect }
        state = LoadState.Loading
        val img = runCatching { fetchHuiStatic(url, bearerToken) }
            .onFailure { R1Log.d("HuiImage", "fetch failed $url: ${it.message}") }
            .getOrNull()
        if (img != null) {
            AsyncBitmapCache.put(url, img)
            bitmap = img
            state = LoadState.Loaded
        } else {
            state = LoadState.Error
        }
    }

    when (state) {
        LoadState.Error -> BrokenImagePlaceholder(contentDescription)
        LoadState.Loaded -> bitmap?.let { bmp ->
            Image(
                bitmap = bmp,
                contentDescription = contentDescription,
                contentScale = contentScale,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize(),
            )
        }
        LoadState.Loading -> { /* outer Box shows muted surface while loading */ }
    }
}

// ── CameraImageLayer ──────────────────────────────────────────────────────────

@Composable
private fun CameraImageLayer(
    serverUrl: String,
    bearerToken: String?,
    entityId: String,
    intervalMillis: Long,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
    contentDescription: String?,
) {
    var bitmap by remember(entityId) { mutableStateOf<ImageBitmap?>(null) }
    var failed by remember(entityId) { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val targetW = with(density) { maxWidth.toPx().toInt().coerceAtLeast(1) }
        val targetH = with(density) { maxHeight.toPx().toInt().coerceAtLeast(1) }

        LaunchedEffect(entityId, serverUrl, bearerToken, intervalMillis, lifecycleOwner, targetW, targetH) {
            lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                var failureCount = 0
                while (true) {
                    val url = "${serverUrl.trimEnd('/')}/api/camera_proxy/$entityId?cb=${System.currentTimeMillis()}"
                    val img = runCatching { fetchHuiCamera(url, bearerToken, targetW, targetH) }
                        .onFailure { R1Log.d("HuiImage", "camera $entityId: ${it.message}") }
                        .getOrNull()
                    if (img != null) {
                        bitmap = img; failed = false; failureCount = 0
                    } else if (bitmap == null) {
                        failed = true
                    }
                    // Exponential backoff on consecutive failures (same logic as CameraSnapshot.kt)
                    val nextDelay = if (img == null) {
                        failureCount = (failureCount + 1).coerceAtMost(4)
                        minOf(intervalMillis shl failureCount, 60_000L)
                    } else intervalMillis
                    delay(nextDelay)
                }
            }
        }

        val bmp = bitmap
        when {
            bmp != null -> Image(
                bitmap = bmp,
                contentDescription = contentDescription,
                contentScale = contentScale,
                colorFilter = colorFilter,
                modifier = Modifier.fillMaxSize(),
            )
            failed -> BrokenImagePlaceholder(contentDescription)
        }
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

@Composable
private fun BrokenImagePlaceholder(description: String?) {
    Box(
        modifier = Modifier.fillMaxSize().background(R1.SurfaceMuted),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "?", style = R1.numeralM, color = R1.InkMuted)
    }
}

/** Map HA fit_mode string to Compose ContentScale. Defaults to Crop (HA default). */
internal fun fitModeToContentScale(fitMode: String?): ContentScale = when (fitMode?.lowercase()) {
    "contain" -> ContentScale.Fit
    "fill" -> ContentScale.FillBounds
    else -> ContentScale.Crop
}

/**
 * Resolve a raw HA image URL into something OkHttp can load.
 *  - media-source:// is not resolvable locally -- caller shows broken-image placeholder.
 *  - Relative paths anchored on [serverUrl].
 *  - Absolute http(s) and data: URIs pass through.
 */
internal fun resolveHuiUrl(raw: String?, serverUrl: String?): String? {
    if (raw.isNullOrBlank()) return null
    return when {
        raw.startsWith("media-source://") -> null
        raw.startsWith("http://") || raw.startsWith("https://") -> raw
        raw.startsWith("data:") -> raw
        raw.startsWith("/") && !serverUrl.isNullOrBlank() -> serverUrl.trimEnd('/') + raw
        else -> null
    }
}

private suspend fun fetchHuiStatic(url: String, bearerToken: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (url.startsWith("data:")) {
            val idx = url.indexOf(',')
            if (idx < 0) return@withContext null
            val bytes = runCatching {
                android.util.Base64.decode(url.substring(idx + 1), android.util.Base64.DEFAULT)
            }.getOrNull() ?: return@withContext null
            return@withContext decodeSubsampledHui(bytes, 512, 512)
        }
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer $bearerToken")
        AsyncBitmapCache.httpClient().newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            decodeSubsampledHui(bytes, 512, 512)
        }
    }

private suspend fun fetchHuiCamera(url: String, bearerToken: String?, targetW: Int, targetH: Int): ImageBitmap? =
    withContext(Dispatchers.IO) {
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer $bearerToken")
        CameraHttp.client().newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            val bytes = resp.body?.bytes() ?: return@use null
            decodeSubsampledHui(bytes, targetW, targetH)
        }
    }

private fun decodeSubsampledHui(bytes: ByteArray, targetW: Int, targetH: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= targetW && bounds.outHeight / (sample * 2) >= targetH) sample *= 2
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}
