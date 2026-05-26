package com.github.itskenny0.r1ha.core.input

import android.view.KeyEvent

/**
 * Logical input actions the app can react to from a hardware key press. The
 * physical key→action map is user-configurable via Settings → Behaviour → Key
 * bindings; the same logical action can be bound to multiple keys (or to none,
 * in which case the action is unreachable from the keyboard).
 *
 * Wheel actions [WHEEL_UP] / [WHEEL_DOWN] funnel into [WheelInput.emit]; every
 * other action goes through [KeyActionBus] so screens (or the activity itself)
 * can navigate / refresh / etc. in response.
 */
enum class KeyAction(val displayLabel: String, val description: String) {
    WHEEL_UP("Wheel up", "Scroll up / increase value"),
    WHEEL_DOWN("Wheel down", "Scroll down / decrease value"),
    CARD_UP("Card up", "Jump to the previous card on the stack"),
    CARD_DOWN("Card down", "Jump to the next card on the stack"),
    PAGE_LEFT("Page left", "Swipe to the previous card-stack tab"),
    PAGE_RIGHT("Page right", "Swipe to the next card-stack tab"),
    ACTIVATE("Activate", "Press / tap the focused card"),
    GO_BACK("Go back", "Equivalent to system Back"),
    OPEN_SETTINGS("Open Settings", "Jump to the Settings screen"),
    OPEN_ASSIST("Open Assist", "Open the voice/chat Assist screen"),
    OPEN_SEARCH("Open Search", "Open Universal Search"),
    OPEN_DASHBOARD("Open Dashboard", "Open the Today dashboard"),
    RECONNECT("Force reconnect", "Cancel backoff and reconnect to Home Assistant"),
    REFRESH("Refresh", "Pull-to-refresh on the current screen, where supported"),
}

/**
 * Built-in default key map. Preserves the historical wheel behaviour
 * (DPAD_UP + VOLUME_UP for [KeyAction.WHEEL_UP], etc.) and adds the most
 * obvious bindings for the new navigation actions. Anything not present
 * here is intentionally unbound so users can pick a key that doesn't
 * collide with their setup.
 */
val DEFAULT_KEY_BINDINGS: Map<KeyAction, List<Int>> = mapOf(
    KeyAction.WHEEL_UP to listOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_VOLUME_UP),
    KeyAction.WHEEL_DOWN to listOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN),
    KeyAction.PAGE_LEFT to listOf(KeyEvent.KEYCODE_DPAD_LEFT),
    KeyAction.PAGE_RIGHT to listOf(KeyEvent.KEYCODE_DPAD_RIGHT),
    KeyAction.ACTIVATE to listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER),
    KeyAction.GO_BACK to listOf(KeyEvent.KEYCODE_BACK),
)

/**
 * Resolved key→action map used by [com.github.itskenny0.r1ha.MainActivity.dispatchKeyEvent].
 * Wraps a raw [Map] so the inverse lookup (keycode → action) is precomputed
 * once at construction time rather than on every key press.
 *
 * The wrapped map's keys are normalised to [KeyAction] enum entries; persisted
 * storage uses string names so an unknown / removed action decodes to no-op
 * rather than crashing.
 */
class KeyBindings(val map: Map<KeyAction, List<Int>>) {
    private val inverse: Map<Int, KeyAction> = buildMap {
        for ((action, codes) in map) {
            for (code in codes) {
                // First binding wins on collision (an explicit user choice should
                // not silently overwrite an earlier action they already configured).
                putIfAbsent(code, action)
            }
        }
    }

    fun actionFor(keyCode: Int): KeyAction? = inverse[keyCode]

    fun keysFor(action: KeyAction): List<Int> = map[action].orEmpty()

    companion object {
        val DEFAULT: KeyBindings = KeyBindings(DEFAULT_KEY_BINDINGS)
    }
}

/**
 * Human-friendly label for a keycode. Falls back to the raw KeyEvent constant
 * name (KEYCODE_DPAD_UP → "DPAD UP") for codes we haven't curated, and to
 * "KEY $code" for codes Android doesn't know about. Used in the Settings
 * row that lists currently-bound keys.
 */
fun keyCodeLabel(code: Int): String {
    val curated = when (code) {
        KeyEvent.KEYCODE_DPAD_UP -> "DPAD UP"
        KeyEvent.KEYCODE_DPAD_DOWN -> "DPAD DOWN"
        KeyEvent.KEYCODE_DPAD_LEFT -> "DPAD LEFT"
        KeyEvent.KEYCODE_DPAD_RIGHT -> "DPAD RIGHT"
        KeyEvent.KEYCODE_DPAD_CENTER -> "DPAD PRESS"
        KeyEvent.KEYCODE_ENTER -> "ENTER"
        KeyEvent.KEYCODE_VOLUME_UP -> "VOL +"
        KeyEvent.KEYCODE_VOLUME_DOWN -> "VOL −"
        KeyEvent.KEYCODE_VOLUME_MUTE -> "VOL MUTE"
        KeyEvent.KEYCODE_BACK -> "BACK"
        KeyEvent.KEYCODE_HOME -> "HOME"
        KeyEvent.KEYCODE_MENU -> "MENU"
        KeyEvent.KEYCODE_SEARCH -> "SEARCH"
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> "PLAY/PAUSE"
        KeyEvent.KEYCODE_MEDIA_NEXT -> "NEXT TRACK"
        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "PREV TRACK"
        else -> null
    }
    if (curated != null) return curated
    val raw = KeyEvent.keyCodeToString(code)
    return raw.removePrefix("KEYCODE_").replace('_', ' ').takeIf { it.isNotBlank() } ?: "KEY $code"
}
