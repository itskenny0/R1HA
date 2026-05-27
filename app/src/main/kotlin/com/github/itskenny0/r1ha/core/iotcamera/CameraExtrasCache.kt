package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context

/**
 * Cache of "extra" camera IDs the user discovered via the explicit
 * SCAN FOR EXTRA CAMERAS probe. These are IDs that don't appear in
 * `CameraManager.cameraIdList` or `INFO_PHYSICAL_CAMERA_IDS` but DO
 * accept `openCamera()` — Xiaomi devices specifically hide their
 * wide / tele / ultrawide sub-sensors here, and the only reliable
 * way to find them is to actually try opening each id.
 *
 * Stored per-device in SharedPreferences (not DataStore) so the cache
 * survives across reboots but doesn't sync via the settings backup
 * path — these IDs are HAL-specific and meaningless on another device.
 *
 * Why separate from settings: the [add] entry is hit from the probe
 * coroutine inside [CameraEnumerator] which is a singleton with no
 * dependency-injected settings handle, and the read path is hit by
 * the synchronous enumeration on every settings-screen entry where we
 * don't want to suspend on DataStore.
 */
object CameraExtrasCache {

    private const val PREFS = "iot_camera_extras"
    private const val KEY = "verified_extra_ids"

    fun get(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun replace(context: Context, ids: Set<String>) {
        // Whole-set replacement so the probe path can publish its final
        // result in one shot rather than incrementally — keeps the
        // cache consistent if the user interrupts mid-scan.
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putStringSet(KEY, ids.toSet()).apply()
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
    }
}
