package com.github.itskenny0.r1ha.core.ambient

/**
 * Decides which hardware key events the activity swallows on behalf of the
 * ambient idle face.
 *
 * The waking KEY_DOWN is swallowed while the face is showing. The idle flag
 * clears asynchronously (a state emission plus a recomposition) well before
 * the finger lifts, so without a latch the matching KEY_UP leaked through and
 * fired UP-driven bindings: a GO_BACK side button that woke the device also
 * popped the back stack. The latch remembers the swallowed key and eats its
 * UP regardless of the idle flag.
 */
class WakeKeySwallow {
    private var latchedKeyCode: Int = NONE

    /**
     * @param swallowNow true while the idle face is showing AND the user opted
     *   to consume wake events.
     * @return true when the activity must consume this event.
     */
    fun shouldSwallow(keyCode: Int, isDown: Boolean, swallowNow: Boolean): Boolean {
        if (swallowNow) {
            if (isDown) latchedKeyCode = keyCode else if (keyCode == latchedKeyCode) latchedKeyCode = NONE
            return true
        }
        if (!isDown && keyCode == latchedKeyCode) {
            latchedKeyCode = NONE
            return true
        }
        return false
    }

    private companion object {
        const val NONE = -1
    }
}
