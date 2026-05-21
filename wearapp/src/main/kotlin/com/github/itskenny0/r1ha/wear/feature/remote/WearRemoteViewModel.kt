package com.github.itskenny0.r1ha.wear.feature.remote

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.github.itskenny0.r1ha.core.ha.HaRepository

/**
 * ViewModel for the Wear OS Remote Control screen.
 *
 * All methods are fire-and-forget: they enqueue a `unified_remote/command`
 * message onto the HA WebSocket and return immediately. The HA integration
 * (`custom_components/unified_remote`) forwards each command to the Unified
 * Remote server running locally on the user's PC (UDP/TCP port 9512).
 *
 * Sensitivity multiplier scales raw Compose drag pixels → UR mouse units.
 * The default (2.5×) is a comfortable desk-use value; the watch touchpad
 * area is small so a modest multiplier avoids needing tiny swipes.
 */
class WearRemoteViewModel(
    private val haRepository: HaRepository,
) : ViewModel() {

    companion object {
        /** Pixels-per-point drag → UR mouse unit scale factor. */
        private const val SENSITIVITY = 2.5

        fun factory(haRepository: HaRepository) = viewModelFactory {
            initializer { WearRemoteViewModel(haRepository) }
        }
    }

    // ── Mouse ────────────────────────────────────────────────────────────────

    fun sendMove(dx: Float, dy: Float) {
        val scaledX = dx * SENSITIVITY
        val scaledY = dy * SENSITIVITY
        // Skip zero-deltas — they just waste a WS message
        if (scaledX == 0.0 && scaledY == 0.0) return
        haRepository.unifiedRemoteCommand("move", dx = scaledX, dy = scaledY)
    }

    fun sendScroll(dy: Float) {
        // Positive dy = finger moving down = scroll down
        haRepository.unifiedRemoteCommand("scroll", dy = dy.toDouble())
    }

    fun sendClick() = haRepository.unifiedRemoteCommand("click")

    fun sendRightClick() = haRepository.unifiedRemoteCommand("right_click")

    fun sendDoubleClick() = haRepository.unifiedRemoteCommand("double_click")

    // ── Volume ───────────────────────────────────────────────────────────────

    fun sendVolumeUp()   = haRepository.unifiedRemoteCommand("volume", action = "up")
    fun sendVolumeDown() = haRepository.unifiedRemoteCommand("volume", action = "down")
    fun sendMute()       = haRepository.unifiedRemoteCommand("volume", action = "mute")

    // ── Media transport ──────────────────────────────────────────────────────

    fun sendPlayPause() = haRepository.unifiedRemoteCommand("media", action = "play_pause")
    fun sendPrevious()  = haRepository.unifiedRemoteCommand("media", action = "previous")
    fun sendNext()      = haRepository.unifiedRemoteCommand("media", action = "next")
    fun sendStop()      = haRepository.unifiedRemoteCommand("media", action = "stop")

    // ── Keyboard shortcuts ───────────────────────────────────────────────────

    fun sendKey(key: String) = haRepository.unifiedRemoteCommand("key", key = key)
}
