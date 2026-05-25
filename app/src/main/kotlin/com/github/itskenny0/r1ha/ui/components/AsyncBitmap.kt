package com.github.itskenny0.r1ha.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Tiny image loader for the now-playing album art and any other one-off
 * Compose-rendered bitmap. No Glide / Coil dependency — the R1 use case is
 * one or two album covers visible at a time, so a per-composable LaunchedEffect
 * that fetches the bytes and decodes via BitmapFactory is plenty.
 *
 * **Caching.** Backed by [AsyncBitmapCache] — an in-memory LRU of decoded
 * [ImageBitmap]s (so swiping back to a previously-seen card paints instantly,
 * no re-decode) plus an OkHttp disk cache shared by every fetch (so re-opening
 * the app pulls the cover from disk instead of refetching over WLAN). On the
 * R1's slow boot path the difference is the album cover appearing in the same
 * frame as the rest of the card rather than ~300 ms later.
 *
 * Handles three URL shapes coming from HA's `entity_picture` attribute:
 *  1. Absolute `http(s)://…` — used verbatim.
 *  2. Relative `/api/media_player_proxy/…` — prepended with the configured HA
 *     server URL.
 *  3. `data:image/…` data URIs — split and decoded as base64 in place. Data
 *     URIs are NOT cached on disk (they're inline already and the LRU is
 *     enough) but DO go through the in-memory LRU.
 *
 * Failures (network down, decode error, 404 on the proxy) render a quiet
 * placeholder instead of throwing; the album cover is a nice-to-have, not a
 * must-have, and a broken image rotating in a corner of the card would be
 * worse than a clean fallback.
 */
@Composable
fun AsyncBitmap(
    url: String?,
    serverUrl: String?,
    bearerToken: String?,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    val resolved = remember(url, serverUrl) { url?.let { resolveUrl(it, serverUrl) } }
    // Seed bitmap from the memory cache so swiping back to a known cover paints
    // in the same frame as the rest of the card — no flash of placeholder
    // while the LaunchedEffect re-fetches. The cache returns null when the URL
    // isn't seeded yet (or has been evicted); the effect below populates it.
    var bitmap by remember(resolved) {
        mutableStateOf(resolved?.let { AsyncBitmapCache.peek(it) })
    }
    var failed by remember(resolved) { mutableStateOf(false) }

    LaunchedEffect(resolved, bearerToken) {
        if (resolved == null) {
            failed = true
            return@LaunchedEffect
        }
        // Memory-hit fast path — keep the existing bitmap (already seeded above)
        // and skip the IO dispatch entirely.
        if (AsyncBitmapCache.peek(resolved) != null) return@LaunchedEffect
        val image = runCatching { fetchAndDecode(resolved, bearerToken) }
            .onFailure { R1Log.d("AsyncBitmap", "fetch failed $resolved: ${it.message}") }
            .getOrNull()
        if (image != null) {
            AsyncBitmapCache.put(resolved, image)
            bitmap = image
        } else {
            failed = true
        }
    }

    Box(modifier = modifier.background(R1.SurfaceMuted)) {
        val img = bitmap
        if (img != null) {
            Image(
                bitmap = img,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (failed) {
            // Tiny "♪" placeholder so an unloadable cover still reads as 'this is
            // where the art would go' rather than an empty rectangle.
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(text = "♪", style = R1.numeralM, color = R1.InkMuted)
            }
        }
        // Loading state intentionally renders as the empty SurfaceMuted box —
        // album art typically loads in <300 ms over LAN, and a spinner during
        // that window would feel busier than the brief blank frame. With the
        // memory cache, subsequent loads are 0 ms anyway.
    }
}

/**
 * Resolve the entity_picture URL into something OkHttp can fetch. Returns null
 * for shapes we can't handle (e.g. malformed data URIs); the caller renders the
 * placeholder in that case.
 */
private fun resolveUrl(raw: String, serverUrl: String?): String? = when {
    raw.startsWith("http://") || raw.startsWith("https://") -> raw
    raw.startsWith("data:") -> raw  // handled specially in fetchAndDecode
    raw.startsWith("/") && !serverUrl.isNullOrBlank() -> serverUrl.trimEnd('/') + raw
    else -> null
}

private suspend fun fetchAndDecode(url: String, bearerToken: String?): ImageBitmap? =
    withContext(Dispatchers.IO) {
        if (url.startsWith("data:")) {
            // data:image/jpeg;base64,<...> split off the base64 segment and
            // decode it. Inline data URIs are rare for media_player but some
            // integrations (Plex, Music Assistant) emit them.
            val commaIdx = url.indexOf(',')
            if (commaIdx < 0) return@withContext null
            val payload = url.substring(commaIdx + 1)
            val bytes = runCatching { android.util.Base64.decode(payload, android.util.Base64.DEFAULT) }
                .getOrNull() ?: return@withContext null
            return@withContext decodeSubsampled(bytes)
        }
        val client = AsyncBitmapCache.httpClient()
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) {
            builder.header("Authorization", "Bearer $bearerToken")
        }
        client.newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null
            val bytes = resp.body?.bytes() ?: return@withContext null
            decodeSubsampled(bytes)
        }
    }

/**
 * Subsample decode targeted at the typical album-art slot size on R1 (~240 dp).
 * A 1024x1024 ARGB_8888 cover from Music Assistant decodes to ~4 MB; sampled
 * down to 256x256 RGB_565 it's ~128 KB. The LRU's 16-entry cap then bounds
 * memory at ~2 MB instead of ~64 MB for max-sized inputs.
 */
private fun decodeSubsampled(bytes: ByteArray): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val target = TARGET_PX
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target && bounds.outHeight / (sample * 2) >= target) {
        sample *= 2
    }
    val opts = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.RGB_565
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
}

// Slightly larger than the on-screen 240 dp to allow ContentScale.Crop to pick
// from the borders without visible upscaling. RGB_565 + 256-target keeps each
// cached cover well under 200 KB.
private const val TARGET_PX = 256

/**
 * Process-scoped cache for decoded album-art bitmaps + the OkHttp client that
 * backs them. Initialised once from [com.github.itskenny0.r1ha.App.onCreate]
 * via [init] — the application Context is needed for the disk-cache directory
 * but we never hold a longer-lived reference than that. Calls before [init]
 * fall back to an in-memory-only OkHttp instance, so test code that never
 * wires the application context still functions.
 *
 * Memory LRU sized for **16 entries**: each decoded ARGB_8888 album cover at
 * the typical 240×240 R1 size is ~225 KB, so the upper bound is ~3.6 MB — well
 * inside the R1's working set without crowding out the rest of the app.
 *
 * Disk cache **10 MB** in the app's cache directory. Honoured by OkHttp's
 * CacheControl machinery + HA's standard image-proxy ETags so a 304 short-
 * circuits the body transfer entirely on revisits.
 */
internal object AsyncBitmapCache {

    private const val MEMORY_LRU_ENTRIES = 16
    private const val DISK_CACHE_BYTES = 10L * 1024 * 1024

    private val memory = LruCache<String, ImageBitmap>(MEMORY_LRU_ENTRIES)

    @Volatile private var sharedClient: OkHttpClient? = null

    /** Called from App.onCreate. Wires the disk cache under the application's
     *  cache dir; safe to call more than once (subsequent calls no-op). */
    fun init(context: Context) {
        if (sharedClient != null) return
        synchronized(this) {
            if (sharedClient != null) return
            val cacheDir = File(context.cacheDir, "r1ha-images")
            sharedClient = OkHttpClient.Builder()
                .cache(Cache(cacheDir, DISK_CACHE_BYTES))
                .build()
        }
    }

    /** OkHttp instance with the disk cache attached. Falls back to a plain
     *  client when [init] hasn't run yet (e.g. tests) so callers never NPE. */
    fun httpClient(): OkHttpClient = sharedClient ?: fallbackClient

    /** Peek the in-memory LRU without touching the IO path. Used by the
     *  composable's `remember` seed so a previously-decoded cover paints in
     *  the same frame as the surrounding card. */
    fun peek(url: String): ImageBitmap? = memory.get(url)

    /** Insert a decoded bitmap into the LRU. The OkHttp disk cache picks up
     *  the raw bytes side; we only track decoded ImageBitmaps in memory to
     *  amortise the BitmapFactory.decodeByteArray cost on warm hits. */
    fun put(url: String, image: ImageBitmap) {
        memory.put(url, image)
    }

    /** Drop everything. Useful for the dev menu's "clear caches" affordance
     *  if we ever surface one; not wired today. */
    fun clear() {
        memory.evictAll()
        runCatching { sharedClient?.cache?.evictAll() }
    }

    private val fallbackClient: OkHttpClient by lazy { OkHttpClient() }
}
