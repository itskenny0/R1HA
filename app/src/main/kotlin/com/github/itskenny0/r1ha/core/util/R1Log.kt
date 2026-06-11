package com.github.itskenny0.r1ha.core.util

import android.util.Log

/**
 * Single point for runtime logging. All R1HA logs share the same tag so a developer can
 * filter live with `adb logcat | grep R1HA`. Verbose / debug logs are stripped from release
 * builds by the ProGuard rule in `proguard-rules.pro`; `i` / `w` / `e` survive so production
 * crash troubleshooting is possible.
 */
internal object R1Log {
    private const val TAG = "R1HA"

    /**
     * Optional remote log shipper, installed once at Application startup. When
     * set AND enabled, every R1Log call also enqueues a wire entry for the
     * background drain loop. Volatile because it's read on every log call from
     * arbitrary threads but written exactly once. The shipper itself guards
     * against re-entrancy (it never logs its own HTTP activity), so the central
     * hook here can stay unconditional.
     */
    @Volatile
    var shipper: LogShipper? = null

    fun v(where: String, msg: String) {
        Log.v(TAG, "$where: $msg")
        // Verbose maps to DEBUG on the in-app bus — the bus only ships three named
        // levels above NONE so verbose folds in with the noisiest setting.
        R1Toast.push(R1Toast.Level.DEBUG, where, msg)
        R1LogBuffer.append(R1LogBuffer.Level.D, where, msg)
        shipper?.submit(LogEntry.LEVEL_VERBOSE, where, msg)
    }
    fun d(where: String, msg: String) {
        Log.d(TAG, "$where: $msg")
        R1Toast.push(R1Toast.Level.DEBUG, where, msg)
        R1LogBuffer.append(R1LogBuffer.Level.D, where, msg)
        shipper?.submit(LogEntry.LEVEL_DEBUG, where, msg)
    }
    fun i(where: String, msg: String) {
        Log.i(TAG, "$where: $msg")
        R1Toast.push(R1Toast.Level.INFO, where, msg)
        R1LogBuffer.append(R1LogBuffer.Level.I, where, msg)
        shipper?.submit(LogEntry.LEVEL_INFO, where, msg)
    }
    fun w(where: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.w(TAG, "$where: $msg", t) else Log.w(TAG, "$where: $msg")
        val full = if (t != null) "$msg\n\n${t.stackTraceToString()}" else msg
        R1Toast.push(R1Toast.Level.WARN, where, msg, full)
        R1LogBuffer.append(R1LogBuffer.Level.W, where, msg, t)
        shipper?.submit(LogEntry.LEVEL_WARN, where, full)
    }
    fun e(where: String, msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, "$where: $msg", t) else Log.e(TAG, "$where: $msg")
        val full = if (t != null) "$msg\n\n${t.stackTraceToString()}" else msg
        R1Toast.push(R1Toast.Level.ERROR, where, msg, full)
        R1LogBuffer.append(R1LogBuffer.Level.E, where, msg, t)
        shipper?.submit(LogEntry.LEVEL_ERROR, where, full)
    }
}
