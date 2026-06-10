package com.github.itskenny0.r1ha.feature.moreinfo

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.ui.components.CameraHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Saves a one-shot camera snapshot to the device's public Downloads collection.
 *
 * Uses the scoped-storage MediaStore API (API 29+), which needs no runtime
 * permission: the bytes land in `Download/` and are visible to the system
 * Downloads / Files apps. On older Android the call returns
 * [DownloadResult.Unsupported] so the caller can grey the button rather than
 * dragging in a legacy WRITE_EXTERNAL_STORAGE permission for a low-value LOW item.
 *
 * The snapshot bytes come from HA's `/api/camera_proxy/<entity_id>` (the same
 * endpoint the live view polls), fetched through the shared [CameraHttp] client so
 * the auth-throttle breaker applies.
 */
object CameraSnapshotDownload {

    sealed interface DownloadResult {
        data class Saved(val displayName: String) : DownloadResult
        object Unsupported : DownloadResult
        data class Failed(val reason: String) : DownloadResult
    }

    /** True when this Android version supports the no-permission MediaStore
     *  Downloads write. Callers grey the download affordance when false. */
    val isSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

    suspend fun save(
        context: Context,
        serverUrl: String,
        bearerToken: String?,
        entityId: String,
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (!isSupported) return@withContext DownloadResult.Unsupported
        val bytes = runCatching { fetchBytes(serverUrl, bearerToken, entityId) }
            .onFailure { R1Log.w("CameraDownload", "fetch $entityId failed: ${it.message}") }
            .getOrNull()
            ?: return@withContext DownloadResult.Failed("Snapshot fetch failed")

        val stamp = java.time.format.DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss", java.util.Locale.US)
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.now())
        val displayName = "${entityId.substringAfter('.')}-$stamp.jpg"

        runCatching {
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, "image/jpeg")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching DownloadResult.Failed("Could not create download")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: return@runCatching DownloadResult.Failed("Could not open download")
            DownloadResult.Saved(displayName)
        }.getOrElse {
            R1Log.w("CameraDownload", "write $displayName failed: ${it.message}")
            DownloadResult.Failed(it.message ?: "Save failed")
        }
    }

    private fun fetchBytes(serverUrl: String, bearerToken: String?, entityId: String): ByteArray? {
        val url = "${serverUrl.trimEnd('/')}/api/camera_proxy/$entityId?cb=${System.currentTimeMillis()}"
        val builder = Request.Builder().url(url)
        if (!bearerToken.isNullOrBlank()) builder.header("Authorization", "Bearer $bearerToken")
        CameraHttp.client().newCall(builder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            return resp.body?.bytes()
        }
    }
}
