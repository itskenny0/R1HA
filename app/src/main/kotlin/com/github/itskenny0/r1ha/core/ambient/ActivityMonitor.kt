package com.github.itskenny0.r1ha.core.ambient

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Process-scoped record of the last user interaction (touch / key / wheel),
 * the single source of truth for ambient idle detection. Fed from
 * [com.github.itskenny0.r1ha.MainActivity.onUserInteraction] (touch) and
 * [com.github.itskenny0.r1ha.MainActivity.dispatchKeyEvent] (keys / wheel).
 * The timestamp is a caller-supplied monotonic value (SystemClock.uptimeMillis)
 * so this object stays free of Android imports and unit-testable.
 */
object ActivityMonitor {
    private val _lastInteractionAt = MutableStateFlow(0L)
    val lastInteractionAt: StateFlow<Long> = _lastInteractionAt

    fun markInteraction(nowMs: Long) {
        _lastInteractionAt.value = nowMs
    }
}
