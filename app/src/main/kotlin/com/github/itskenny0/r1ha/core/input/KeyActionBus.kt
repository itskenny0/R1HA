package com.github.itskenny0.r1ha.core.input

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide channel for non-wheel [KeyAction]s. MainActivity's
 * `dispatchKeyEvent` consults [com.github.itskenny0.r1ha.AppGraph.keyBindings] and
 * emits here on a KEY_DOWN whose keycode resolves to anything other than
 * WHEEL_UP/DOWN (those still go through the dedicated WheelInput). Downstream
 * collectors live in MainActivity's content composable so the navigation
 * controller is in scope when an OPEN_SETTINGS / OPEN_ASSIST action arrives.
 *
 * Small buffer with DROP_OLDEST so a stuck collector can't grow memory; the
 * stream is user-driven so backpressure isn't a real concern.
 */
object KeyActionBus {
    private val _events = MutableSharedFlow<KeyAction>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<KeyAction> = _events.asSharedFlow()
    fun emit(action: KeyAction) {
        _events.tryEmit(action)
    }
}

/**
 * Single-slot rendezvous for the "press the button to assign" flow. The
 * Settings binding dialog installs a callback; the next KEY_DOWN event in
 * MainActivity goes to the callback (which returns true to consume it) and
 * does NOT propagate to the normal binding lookup, so a user pressing
 * VOLUME_UP to bind a new action doesn't simultaneously fire the existing
 * wheel-up binding.
 *
 * Volatile + atomic compare-and-set keeps the install / clear races from
 * either dropping a capture or honouring a stale callback after the dialog
 * was dismissed.
 */
object KeyCaptureBus {
    private val ref = java.util.concurrent.atomic.AtomicReference<((Int) -> Boolean)?>(null)

    fun install(callback: (keyCode: Int) -> Boolean) {
        ref.set(callback)
    }

    fun clear() {
        ref.set(null)
    }

    /** Returns true if a callback was installed and consumed the event. */
    fun tryCapture(keyCode: Int): Boolean {
        val cb = ref.get() ?: return false
        return cb(keyCode)
    }
}
