package com.github.itskenny0.r1ha.feature.ambient

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.github.itskenny0.r1ha.core.ambient.ActivityMonitor
import com.github.itskenny0.r1ha.core.ambient.AmbientLogic
import com.github.itskenny0.r1ha.core.ambient.AmbientSummary
import com.github.itskenny0.r1ha.core.prefs.AmbientSettings

/**
 * Topmost sibling in the app shell. Runs the ambient idle state machine, drives
 * window brightness, forces keep-screen-on when enabled, and renders [IdleFace]
 * over everything when idle. Draws nothing when not idle. All decisions defer to
 * the unit-tested [AmbientLogic].
 */
@Composable
fun AmbientOverlay(
    ambient: AmbientSettings,
    currentRoute: String?,
    nightStartHour: Int,
    nightEndHour: Int,
    powerAmberW: Int,
    powerRedW: Int,
    refreshIntervalSec: Int,
    fetchSummary: suspend () -> AmbientSummary,
    onIdleChanged: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val window = (context as? Activity)?.window

    // Force keep-screen-on at the window level whenever ambient is enabled, so
    // the OS does not sleep the screen out from under the always-on display.
    DisposableEffect(ambient.enabled) {
        if (ambient.enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val suppressed = AmbientLogic.shouldSuppress(
        route = currentRoute,
        scope = ambient.scope,
        imeVisible = imeVisible,
        suppressOverCamera = ambient.suppressOverCamera,
    )

    val lastInteraction by ActivityMonitor.lastInteractionAt.collectAsStateWithLifecycle()
    var idle by remember { mutableStateOf(false) }

    // Idle driver. Re-keyed on each interaction (lastInteraction) so a new event
    // cancels the pending transition and restarts the countdown.
    LaunchedEffect(ambient.enabled, ambient.idleTimeoutSec, suppressed, lastInteraction) {
        if (!ambient.enabled || suppressed || ambient.idleTimeoutSec <= 0) {
            idle = false
            return@LaunchedEffect
        }
        val now = android.os.SystemClock.uptimeMillis()
        val timeoutMs = ambient.idleTimeoutSec * 1000L
        val elapsed = now - lastInteraction
        if (elapsed >= timeoutMs) {
            idle = true
        } else {
            idle = false
            kotlinx.coroutines.delay(timeoutMs - elapsed)
            idle = true
        }
    }

    LaunchedEffect(idle) { onIdleChanged(idle) }

    // Night decision, recomputed at the top of each minute.
    val isNight by produceState(false, ambient.nightDimEnabled, nightStartHour, nightEndHour) {
        while (true) {
            val hour = java.time.LocalTime.now().hour
            value = ambient.nightDimEnabled && AmbientLogic.isNightWindow(hour, nightStartHour, nightEndHour)
            val ms = 60_000L - (System.currentTimeMillis() % 60_000L)
            kotlinx.coroutines.delay(ms.coerceAtLeast(1_000L))
        }
    }

    // Window brightness: fade down to the idle target when entering idle; restore
    // to the system value instantly on wake (never animate through the -1 sentinel).
    val target = AmbientLogic.brightness(idle, isNight, ambient)
    LaunchedEffect(target) {
        val w = window ?: return@LaunchedEffect
        val lp = w.attributes
        if (target < 0f) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            w.attributes = lp
        } else {
            val start = if (lp.screenBrightness in 0f..1f) lp.screenBrightness else 1f
            val frames = 16
            for (i in 1..frames) {
                lp.screenBrightness = start + (target - start) * (i / frames.toFloat())
                w.attributes = lp
                kotlinx.coroutines.delay(50)
            }
        }
    }

    // Refresh the glance data only while idle.
    var summary by remember { mutableStateOf(AmbientSummary()) }
    LaunchedEffect(idle, refreshIntervalSec) {
        if (!idle) return@LaunchedEffect
        val periodMs = (if (refreshIntervalSec > 0) refreshIntervalSec else 60) * 1000L
        while (true) {
            summary = runCatching { fetchSummary() }.getOrDefault(summary)
            kotlinx.coroutines.delay(periodMs)
        }
    }

    if (idle) {
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
        ) {
            IdleFace(
                summary = summary,
                ambient = ambient,
                powerAmberW = powerAmberW,
                powerRedW = powerRedW,
                onWake = {
                    ActivityMonitor.markInteraction(android.os.SystemClock.uptimeMillis())
                },
            )
        }
    }
}
