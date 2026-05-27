package com.github.itskenny0.r1ha.core.iotcamera

import android.content.Context

/**
 * Per-device blocklist of camera IDs that surfaced in enumeration but
 * couldn't actually be opened. The hidden-camera probe in
 * [CameraEnumerator] is necessarily a heuristic — OEMs (Xiaomi
 * specifically) expose placeholder camera IDs that advertise
 * LENS_FACING and JPEG output sizes but reject openCamera() at runtime.
 * There's no reliable static check that catches them; the only reliable
 * signal is "tried, failed".
 *
 * So we learn on the fly: when [IotCameraService]'s capture pipeline
 * fires `onError` with an open / disconnect failure for a specific ID,
 * we [block] that ID. Subsequent picker enumeration filters it out.
 * Users iterate through bad IDs once and the picker self-cleans.
 *
 * Stored in SharedPreferences (not DataStore) so the blocklist survives
 * settings export/import without leaking device-specific HAL quirks
 * across devices; the SharedPreferences file is device-local by design.
 *
 * The [clear] entry point gives the user a way to retry all IDs after a
 * permission grant / system update — surfaced as a button on the IoT
 * camera settings screen.
 */
object CameraOpenBlocklist {

    private const val PREFS = "iot_camera_open_blocklist"
    private const val KEY = "blocked_ids"

    fun blockedIds(context: Context): Set<String> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()
    }

    fun block(context: Context, cameraId: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(KEY, emptySet())?.toMutableSet() ?: mutableSetOf()
        if (current.add(cameraId)) {
            // Replace whole set — SharedPreferences caches the StringSet
            // and re-using the same instance can lead to "I edited it but
            // nothing got written" depending on Android version.
            prefs.edit().putStringSet(KEY, current.toSet()).apply()
        }
    }

    fun clear(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().remove(KEY).apply()
    }
}
