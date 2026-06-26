package com.github.itskenny0.r1ha.core.input

/**
 * Pure short-vs-long-press state machine for a single hardware key at a time.
 *
 * Hardware keys normally fire their action on key-down, which makes "short press
 * vs long press" impossible (the short action has already fired by the time a
 * hold is detected). When the user opts into a hardware long-press shortcut, the
 * dispatcher routes the key through this tracker instead: the key's normal action
 * is DEFERRED to release, so a hold can fire the long-press shortcut and suppress
 * the short action, while a quick tap still fires the short action on release.
 *
 * Kept free of Android types so the decision is unit-testable; [MainActivity]
 * feeds it raw `KeyEvent` fields and maps the [Outcome] onto real dispatch.
 *
 * Single-key by design (the R1 has one wheel button / side button, not a chord):
 * a new key-down replaces the tracked key, and a stale UP for a no-longer-tracked
 * key is consumed without firing.
 */
class HardwareLongPressTracker(private val thresholdMs: Long = DEFAULT_THRESHOLD_MS) {

    /** What the dispatcher should do with the event the tracker just observed. */
    enum class Outcome {
        /** Swallow the event; take no action. */
        CONSUME,

        /** The key was tapped: fire its normal (short-press) action. */
        FIRE_SHORT,

        /** The key was held past the threshold: fire the long-press shortcut. */
        FIRE_LONG,
    }

    private var keyCode: Int = NO_KEY
    private var downTime: Long = 0L
    private var firedLong: Boolean = false

    /**
     * Observe a key-down. [repeat] is the event's repeat count (0 = the initial
     * press, >0 = a framework auto-repeat while held). Returns [Outcome.FIRE_LONG]
     * on the first repeat that crosses the threshold; otherwise [Outcome.CONSUME].
     */
    fun onDown(keyCode: Int, eventTime: Long, repeat: Int): Outcome {
        if (repeat == 0) {
            this.keyCode = keyCode
            downTime = eventTime
            firedLong = false
            return Outcome.CONSUME
        }
        if (keyCode == this.keyCode && !firedLong && eventTime - downTime >= thresholdMs) {
            firedLong = true
            return Outcome.FIRE_LONG
        }
        return Outcome.CONSUME
    }

    /**
     * Observe a key-up. Returns [Outcome.CONSUME] if the long action already fired
     * during the hold, [Outcome.FIRE_LONG] if the key was held past the threshold
     * but never auto-repeated (so the hold is only visible now), or
     * [Outcome.FIRE_SHORT] for a quick tap. A stale UP for an untracked key is
     * consumed.
     */
    fun onUp(keyCode: Int, eventTime: Long): Outcome {
        if (keyCode != this.keyCode) return Outcome.CONSUME
        val wasLong = firedLong
        val heldLong = eventTime - downTime >= thresholdMs
        reset()
        return when {
            wasLong -> Outcome.CONSUME
            heldLong -> Outcome.FIRE_LONG
            else -> Outcome.FIRE_SHORT
        }
    }

    private fun reset() {
        keyCode = NO_KEY
        firedLong = false
    }

    companion object {
        private const val NO_KEY = -1

        /** Long-press threshold. Matches Android's default ViewConfiguration feel. */
        const val DEFAULT_THRESHOLD_MS = 450L
    }
}
