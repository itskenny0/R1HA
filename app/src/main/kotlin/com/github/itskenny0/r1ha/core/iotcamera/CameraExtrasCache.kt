package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager

/**
 * Cache of "extra" cameras the user discovered via the explicit
 * SCAN FOR EXTRA CAMERAS probe — these are IDs that don't appear in
 * `CameraManager.cameraIdList` or `INFO_PHYSICAL_CAMERA_IDS` but DO
 * produce frames. Xiaomi devices specifically hide their wide / tele /
 * ultrawide sub-sensors here.
 *
 * Storage format: `id|fingerprint` pairs in a SharedPreferences
 * StringSet. The fingerprint is a stable hash of the camera's facing,
 * focal length, and maximum JPEG output size — the bits that don't
 * change across reboots even when the raw id does. On every
 * enumeration we re-derive the fingerprint for each cached id; if it
 * still matches, the id is valid. If the fingerprint shifted (HAL
 * re-shuffled ids between boots, which Xiaomi has been observed to
 * do), the cache entry is dropped and the user can rescan.
 *
 * Why per-device SharedPreferences instead of DataStore + settings
 * sync: these IDs are HAL-specific and meaningless on another device.
 * A user with sync on would only confuse themselves if their Pixel
 * picked up extras the Xiaomi cached.
 */
object CameraExtrasCache {

    private const val PREFS = "iot_camera_extras"
    private const val KEY = "verified_extras_v2"

    data class Entry(val id: String, val fingerprint: String)

    /**
     * Compute a fingerprint that survives id-shuffling. The chosen
     * inputs are:
     *
     *  - LENS_FACING — never changes per sensor;
     *  - LENS_INFO_AVAILABLE_FOCAL_LENGTHS — physical lens property,
     *    locked to the hardware;
     *  - max JPEG output dimensions — set by the sensor + HAL config.
     *
     * Sufficient to disambiguate the lenses on every multi-camera
     * device we've looked at; two lenses with identical facing +
     * focal + max size would be functionally interchangeable from our
     * perspective so collision wouldn't be a regression.
     *
     * Returns null when characteristics are missing (e.g., the id no
     * longer exists at all) — caller treats null as "drop this entry".
     */
    fun fingerprintOf(manager: CameraManager, id: String): String? = runCatching {
        val chars = manager.getCameraCharacteristics(id)
        val facing = chars.get(CameraCharacteristics.LENS_FACING) ?: return@runCatching null
        val focals = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?: floatArrayOf()
        val streamMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return@runCatching null
        val maxSize = streamMap.getOutputSizes(ImageFormat.JPEG)
            ?.maxByOrNull { it.width.toLong() * it.height.toLong() }
            ?: return@runCatching null
        val focalLabel = focals.joinToString(",") { "%.2f".format(it) }
        "f$facing|l$focalLabel|s${maxSize.width}x${maxSize.height}"
    }.getOrNull()

    /** Read all cached extras as `id → fingerprint`. */
    fun get(context: Context): Map<String, String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getStringSet(KEY, emptySet()) ?: emptySet()
        return raw.mapNotNull { entry ->
            val parts = entry.split('|', limit = 2)
            if (parts.size == 2) parts[0] to parts[1] else null
        }.toMap()
    }

    /**
     * Read cached extras, drop any whose fingerprint no longer matches
     * the live characteristics (id was reused for a different camera
     * after a reboot, or the HAL revoked the id). Returns only the
     * still-valid IDs; the cache file is rewritten as a side effect
     * so the next read short-circuits.
     */
    fun validatedIds(context: Context): Set<String> {
        val manager = context.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
            ?: return emptySet()
        val cached = get(context)
        if (cached.isEmpty()) return emptySet()
        val stillValid = mutableMapOf<String, String>()
        for ((id, expectedFp) in cached) {
            val currentFp = fingerprintOf(manager, id) ?: continue
            if (currentFp == expectedFp) {
                stillValid[id] = expectedFp
            }
        }
        if (stillValid.size != cached.size) {
            // Persist the trimmed set so we don't repeat the validation
            // work on every enumeration pass.
            writeEntries(context, stillValid)
        }
        return stillValid.keys
    }

    /**
     * Whole-set replacement after a scan. Callers pass `id →
     * fingerprint` pairs captured at probe time so we don't re-derive
     * them; the probe already had the characteristics in hand.
     */
    fun replace(context: Context, entries: Map<String, String>) {
        writeEntries(context, entries)
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
    }

    private fun writeEntries(context: Context, entries: Map<String, String>) {
        val raw = entries.map { (id, fp) -> "$id|$fp" }.toSet()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY, raw).apply()
    }
}
