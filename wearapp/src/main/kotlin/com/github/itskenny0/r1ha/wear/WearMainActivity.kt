package com.github.itskenny0.r1ha.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.wear.input.outerRimScroll
import com.github.itskenny0.r1ha.wear.nav.WearNavGraph
import com.github.itskenny0.r1ha.wear.nav.WearRoutes
import com.github.itskenny0.r1ha.wear.theme.WearTheme
import kotlinx.coroutines.flow.first

/**
 * Single Activity for the Wear OS app.
 *
 * ## Scroll / rotary input
 *
 * The R1 phone app routes `KEYCODE_DPAD_UP/DOWN` through `WheelInput` to adjust entity
 * values. On a Galaxy Watch there are two analog scroll inputs:
 *
 * 1. **Digital crown / rotating bezel** (Watch 4 Classic, Watch 6 Classic) → the OS
 *    emits `RotaryScrollEvent` which is intercepted by `Modifier.onRotaryScrollEvent`.
 *    Each event carries `verticalScrollPixels` — positive = clockwise / scroll down,
 *    negative = counter-clockwise / scroll up.
 *
 * 2. **Finger sliding along the outer ring of the touchscreen** — implemented by the
 *    custom `Modifier.outerRimScroll` (see `input/OuterRimScroll.kt`). A drag gesture
 *    that starts in the outer ~24 % of the screen radius is tracked as a polar arc;
 *    every N degrees of rotation emits one `WheelEvent` step. This provides a
 *    scroll-wheel analogue on non-classic Watch models that have no physical bezel.
 *
 * Both inputs feed the same `WheelInput` flow, so all downstream logic (entity value
 * adjustment, list scrolling via `WheelScrollFor`) works without modification.
 *
 * The `Box` at the root of the Compose tree holds the `focusRequester` so the rotary
 * events are delivered even when no individual composable has explicit focus.
 */
class WearMainActivity : ComponentActivity() {

    private lateinit var graph: com.github.itskenny0.r1ha.AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        R1Log.i("WearMainActivity.onCreate", "")
        graph = (application as WearApp).graph

        setContent {
            // Block rendering until first settings load — mirrors the phone app's
            // produceState pattern to avoid an onboarding flash on cold start.
            val initialSettings by produceState<AppSettings?>(initialValue = null) {
                value = graph.settings.settings.first()
            }
            val settings by graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = initialSettings ?: AppSettings(),
            )

            val initial = initialSettings ?: run {
                // Show nothing (system splash is still up) until settings arrive.
                Box(modifier = Modifier.fillMaxSize())
                return@setContent
            }

            val startDestination = remember(initial) {
                if (initial.server == null) WearRoutes.ONBOARDING else WearRoutes.CARD_STACK
            }

            // The FocusRequester feeds rotary events into the root Box so that
            // onRotaryScrollEvent works before the user taps any specific element.
            val focusRequester = remember { FocusRequester() }
            val navController = rememberSwipeDismissableNavController()

            WearTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // ── Input source 1: Digital crown / physical rotating bezel ──
                        // RotaryScrollEvent.verticalScrollPixels is negative for
                        // counter-clockwise (UP) and positive for clockwise (DOWN).
                        .onRotaryScrollEvent { event ->
                            val dir = if (event.verticalScrollPixels < 0f)
                                WheelEvent.Direction.UP
                            else
                                WheelEvent.Direction.DOWN
                            graph.wheelInput.emit(dir)
                            true
                        }
                        // ── Input source 2: Outer-rim finger swipe ──────────────────
                        // Drag gestures starting in the outer 24 % of the screen
                        // radius are tracked as polar arcs. Clockwise arc → DOWN,
                        // counter-clockwise arc → UP.  Same WheelInput target.
                        .outerRimScroll { dir -> graph.wheelInput.emit(dir) }
                        // Focus must be requested for the rotary handler to receive events.
                        .focusRequester(focusRequester)
                        .focusable(),
                ) {
                    WearNavGraph(
                        navController = navController,
                        startDestination = startDestination,
                        haRepository = graph.haRepository,
                        settings = graph.settings,
                        tokens = graph.tokens,
                        wheelInput = graph.wheelInput,
                        currentSettings = settings,
                        http = graph.okHttp,
                    )
                }
            }

            // Request focus on composition so the rotary events are delivered immediately.
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Kick a reconnect if the socket dropped while the watch face was shown.
        val connection = graph.haRepository.connection.value
        val idle = connection is com.github.itskenny0.r1ha.core.ha.ConnectionState.Idle ||
                connection is com.github.itskenny0.r1ha.core.ha.ConnectionState.Disconnected
        if (idle) {
            R1Log.i("WearMainActivity.onResume", "triggering reconnect (state=$connection)")
            graph.haRepository.reconnectNow()
        }
    }
}
