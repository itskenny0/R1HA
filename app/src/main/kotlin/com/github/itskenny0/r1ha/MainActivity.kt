package com.github.itskenny0.r1ha

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import com.github.itskenny0.r1ha.ui.components.ToastHost
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.first
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.nav.AppNavGraph
import com.github.itskenny0.r1ha.nav.Routes

class MainActivity : ComponentActivity() {

    private lateinit var graph: AppGraph

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        R1Log.i("MainActivity.onCreate", "data=${intent?.data}")

        graph = (application as App).graph

        // Tell the window manager we support all orientations BEFORE setContent / setContentView
        // so the system sizes the window correctly from frame 0. If we wait until a LaunchedEffect
        // fires (after the first frame), AOSP 12+ and derivative ROMs (LineageOS, crDroid) have
        // already applied their large-screen phone-compat letterbox policy and the window is stuck
        // at phone dimensions. FULL_USER means "all 4 orientations, respect the user's rotation
        // lock" — the most permissive option that still honours the system rotation setting.
        // The PORTRAIT_ONLY user preference overrides this in the LaunchedEffect below.
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )

        handleOAuthCallback(intent)

        setContent {
            // Load the FIRST settings value synchronously (suspending) before we render the
            // NavHost. Otherwise we'd mount Onboarding briefly (initialValue.server is null)
            // and then jarringly switch to CardStack once the Flow emitted. produceState
            // returns null until the coroutine assigns the first value.
            val initialSettings by produceState<AppSettings?>(initialValue = null) {
                value = graph.settings.settings.first()
            }
            val settings by graph.settings.settings.collectAsStateWithLifecycle(
                initialValue = initialSettings ?: AppSettings(),
            )

            val initial = initialSettings
            if (initial == null) {
                // Splashscreen API keeps the system-level splash up until the activity is
                // ready to draw; we additionally render a blank surface to avoid any flash
                // until the first settings emission is in hand.
                Box(modifier = Modifier.fillMaxSize())
                return@setContent
            }
            // Cold-start app-shortcut delivery — if the user launched
            // us via a launcher long-press shortcut, the route to push
            // is sitting in the original intent's extras. Forward it
            // to the ShortcutBus so AppNavGraph picks it up on its
            // first compose tick. (onNewIntent handles subsequent
            // shortcut taps while the app is already running.)
            androidx.compose.runtime.LaunchedEffect(Unit) {
                intent.getStringExtra(EXTRA_INITIAL_ROUTE)
                    ?.takeIf { it.isNotBlank() }
                    ?.let { route ->
                        R1Log.i("MainActivity.setContent", "cold-start shortcut route: $route")
                        com.github.itskenny0.r1ha.core.util.ShortcutBus.request(route)
                    }
            }

            // Lock the start destination to the FIRST loaded value so theme changes, server
            // changes, etc. don't re-graph the NavHost mid-session. Two paths:
            //   - server == null         → ONBOARDING
            //   - server + startOnDashboard → DASHBOARD (wall-mounted / kiosk R1 path)
            //   - server + default        → CARD_STACK (handheld R1 path)
            val startDestination = remember(initial) {
                when {
                    initial.server == null -> Routes.ONBOARDING
                    initial.behavior.startOnDashboard -> Routes.DASHBOARD
                    else -> Routes.CARD_STACK
                }
            }
            val navController = rememberNavController()
            R1Log.d("MainActivity.setContent", "startDestination=$startDestination server=${initial.server?.url ?: "null"}")

            // Live setting changes. PORTRAIT_ONLY overrides the FULL_USER set in onCreate;
            // FOLLOW_DEVICE reinstates FULL_USER. Both are immediate — no restart needed.
            androidx.compose.runtime.LaunchedEffect(settings.behavior.orientationMode) {
                requestedOrientation = when (settings.behavior.orientationMode) {
                    com.github.itskenny0.r1ha.core.prefs.OrientationMode.PORTRAIT_ONLY ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                    com.github.itskenny0.r1ha.core.prefs.OrientationMode.FOLLOW_DEVICE ->
                        android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_USER
                }
            }

            // Honour the user's "Hide status bar" toggle live — flipping it in Settings
            // applies immediately without an activity restart. WindowInsetsController is
            // the recommended API since SDK 30; we already require min 30 so no fallback
            // path is needed.
            androidx.compose.runtime.LaunchedEffect(settings.behavior.hideStatusBar) {
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                if (settings.behavior.hideStatusBar) {
                    controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                    // Make the user-swipe-to-show transient (auto-hides after a beat) so
                    // peeking the bar to check the time doesn't permanently break the
                    // hidden state.
                    controller.systemBarsBehavior =
                        androidx.core.view.WindowInsetsControllerCompat
                            .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                } else {
                    controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
                }
            }

            // Apply the toast-log level setting whenever it changes. R1Toast is a
            // process-scope object so we just update its flags; the bus host
            // composable reads them at push time. Off → toast UI is silent;
            // ERROR/WARN/INFO/DEBUG raise the threshold progressively.
            androidx.compose.runtime.LaunchedEffect(settings.behavior.toastLogLevel) {
                val level = settings.behavior.toastLogLevel
                com.github.itskenny0.r1ha.core.util.R1Toast.enabled =
                    level != com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.OFF
                com.github.itskenny0.r1ha.core.util.R1Toast.minLevel = when (level) {
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.OFF,
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.ERROR ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.ERROR
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.WARN ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.WARN
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.INFO ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.INFO
                    com.github.itskenny0.r1ha.core.prefs.ToastLogLevel.DEBUG ->
                        com.github.itskenny0.r1ha.core.util.R1Toast.Level.DEBUG
                }
            }
            // Track the current HA access token so deep image-fetch composables
            // (album art on media_player cards, primarily) can authenticate against
            // HA's media-proxy endpoints. Key produceState on the Connected-state's
            // haVersion so the token re-loads once per successful WS reconnect —
            // which is also when TokenRefresher has just rotated the access token —
            // without thrashing the Keystore on the rapid Connecting / Authenticating
            // / Disconnected bounces that come from a flaky network. haVersion is null
            // outside Connected, so transitions away from Connected and back fire
            // exactly one re-read.
            val connection by graph.haRepository.connection
                .collectAsStateWithLifecycle(initialValue = graph.haRepository.connection.value)
            val connectedHaVersion = (connection as? com.github.itskenny0.r1ha.core.ha.ConnectionState.Connected)?.haVersion
            val bearerToken by produceState<String?>(initialValue = null, connectedHaVersion) {
                value = runCatching { graph.tokens.load()?.accessToken }.getOrNull()
            }
            // Effective theme — auto-mode swaps to the night theme between the
            // configured night hours. produceState ticks every minute so the
            // crossover at 22:00 / 06:00 happens without waiting for the next
            // settings emission. The auto flag short-circuits the tick when off
            // (keepers of constant-theme installs don't pay for a recompose-
            // per-minute they don't need).
            val themeNow by androidx.compose.runtime.produceState(
                initialValue = settings.theme,
                settings.theme, settings.nightTheme, settings.autoThemeEnabled,
                settings.nightStartHour, settings.nightEndHour,
            ) {
                while (true) {
                    val now = java.time.LocalTime.now()
                    val hour = now.hour
                    val night = if (!settings.autoThemeEnabled) false
                    else if (settings.nightStartHour == settings.nightEndHour) false
                    else if (settings.nightStartHour < settings.nightEndHour) {
                        hour in settings.nightStartHour until settings.nightEndHour
                    } else {
                        // Wrap-around window — e.g. 22 → 06 — night is "outside
                        // the day window."
                        hour >= settings.nightStartHour || hour < settings.nightEndHour
                    }
                    value = if (night) settings.nightTheme else settings.theme
                    // Sleep until the top of the next minute so the crossover
                    // happens precisely at the configured boundary instead of
                    // up-to-60-seconds late.
                    val msUntilMinute = 60_000L - (System.currentTimeMillis() % 60_000L)
                    kotlinx.coroutines.delay(msUntilMinute.coerceAtLeast(1_000L))
                }
            }
            R1ThemeHost(themeId = themeNow) {
                CompositionLocalProvider(
                    LocalUiOptions provides settings.ui,
                    com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken provides bearerToken,
                ) {
                    // Wrap the nav graph in a Box so the in-app ToastHost can
                    // overlay every navigated screen. The toast bus is process-
                    // scoped (see R1Toast); the host just renders whatever event
                    // it last received as long as the toast feature is enabled.
                    //
                    // On the R1 (≤ 360 dp wide) the ResponsiveColumn wrapper is
                    // a passthrough — every screen renders bit-for-bit
                    // identical to before. On larger displays (phones,
                    // tablets) the wrapper paints the bezel area with the
                    // theme background and centres each screen inside a
                    // bounded column so the layout stays legible instead of
                    // stretching widgets across a 1200 dp panel. Per-screen
                    // exceptions can opt out by reading currentWidthTier()
                    // directly (Cameras GRID does this to keep its grid
                    // column count adaptive without losing the centering).
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(com.github.itskenny0.r1ha.core.theme.R1.Bg),
                    ) {
                        com.github.itskenny0.r1ha.ui.layout.ResponsiveColumn {
                            AppNavGraph(
                                navController = navController,
                                startDestination = startDestination,
                                haRepository = graph.haRepository,
                                settings = graph.settings,
                                tokens = graph.tokens,
                                wheelInput = graph.wheelInput,
                            )
                        }
                        // Route non-wheel key actions (OPEN_SETTINGS, OPEN_ASSIST,
                        // PAGE_LEFT/RIGHT, RECONNECT, REFRESH, ACTIVATE) into nav /
                        // repository operations from one place. Lives inside the same
                        // composition as navController so the NavController is in
                        // scope. Wheel actions are still handled in dispatchKeyEvent
                        // itself — they need the synchronous fast-path into
                        // WheelInput rather than a Flow round-trip.
                        androidx.compose.runtime.LaunchedEffect(navController) {
                            com.github.itskenny0.r1ha.core.input.KeyActionBus.events.collect { action ->
                                when (action) {
                                    com.github.itskenny0.r1ha.core.input.KeyAction.OPEN_SETTINGS ->
                                        navController.navigate(Routes.SETTINGS)
                                    com.github.itskenny0.r1ha.core.input.KeyAction.OPEN_ASSIST ->
                                        navController.navigate(Routes.ASSIST)
                                    com.github.itskenny0.r1ha.core.input.KeyAction.OPEN_SEARCH ->
                                        navController.navigate(Routes.SEARCH)
                                    com.github.itskenny0.r1ha.core.input.KeyAction.OPEN_DASHBOARD ->
                                        navController.navigate(Routes.DASHBOARD)
                                    com.github.itskenny0.r1ha.core.input.KeyAction.RECONNECT ->
                                        graph.haRepository.reconnectNow()
                                    com.github.itskenny0.r1ha.core.input.KeyAction.PAGE_LEFT,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.PAGE_RIGHT,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.ACTIVATE,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.REFRESH -> {
                                        // Picked up by per-screen collectors
                                        // (CardStackScreen for PAGE_*/ACTIVATE,
                                        // pull-to-refresh-capable screens for REFRESH).
                                        // Nothing for the activity-level handler to do.
                                    }
                                    else -> Unit // WHEEL_* + GO_BACK never land here.
                                }
                            }
                        }
                        // Toast host sits OUTSIDE the responsive column so
                        // toasts always pop at the device's true screen
                        // edges, not the centred column's edges.
                        ToastHost()
                    }
                }
            }
        }
    }

    /**
     * If Android delivers the OAuth redirect to us as a deep-link intent (instead of being
     * intercepted by the WebView's `shouldOverrideUrlLoading`), surface it visibly so we can
     * debug. The WebView's interception is the primary path; this is a safety net.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        R1Log.i("MainActivity.onNewIntent", "data=${intent.data}")
        setIntent(intent) // so subsequent intent.getStringExtra reads see the new intent
        handleOAuthCallback(intent)
        // App-shortcut deep-link — fire the requested route through
        // the in-memory ShortcutBus so the nav-graph can pop it onto
        // its back stack. We don't navigate from here directly because
        // the NavController lives inside the Compose tree.
        intent.getStringExtra(EXTRA_INITIAL_ROUTE)?.takeIf { it.isNotBlank() }?.let { route ->
            R1Log.i("MainActivity.onNewIntent", "shortcut routed: $route")
            com.github.itskenny0.r1ha.core.util.ShortcutBus.request(route)
        }
    }

    /**
     * Coming back to the foreground after the app was backgrounded — kick a
     * reconnect if we're not currently connected. Backgrounded apps on R1
     * (and Android in general) frequently have their WS torn down by the
     * OS power saver; without an explicit nudge here the user would tap
     * back in, see stale data, and wonder why nothing updates until our
     * backoff timer fires. Cheap to call when already connected: the repo
     * short-circuits on the existing connection.
     */
    override fun onResume() {
        super.onResume()
        if (!::graph.isInitialized) return
        val conn = graph.haRepository.connection.value
        // Resume-time reconnect kicks ONLY out of Disconnected / Idle. AuthLost has its
        // own refresh + reconnect loop owned by the repository; piling onResume on top
        // would produce a visible flicker (try → 401 → try → 401) until the loop's
        // refresh path runs to completion, since the token is the same one that just
        // got rejected.
        val needsKick = conn is com.github.itskenny0.r1ha.core.ha.ConnectionState.Disconnected ||
            conn is com.github.itskenny0.r1ha.core.ha.ConnectionState.Idle
        if (needsKick) {
            R1Log.i("MainActivity.onResume", "kicking reconnect; conn=$conn")
            graph.haRepository.reconnectNow()
        }
        // Engage NFC reader mode while the activity is foregrounded — the
        // NfcReader checks the per-feature toggle internally before firing
        // HA events, so calling bind() with the toggle off is a cheap no-op.
        com.github.itskenny0.r1ha.feature.nfc.NfcReader.bind(this)
    }

    override fun onPause() {
        super.onPause()
        // Release reader mode; without this another foreground app would have
        // to wait for our adapter to time out before its own NFC features
        // could engage. Safe to call when bind() was a no-op.
        com.github.itskenny0.r1ha.feature.nfc.NfcReader.unbind(this)
    }

    private fun handleOAuthCallback(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "r1ha" || data.host != "auth-callback") return
        val code = data.getQueryParameter("code")
        val error = data.getQueryParameter("error")
        if (!code.isNullOrBlank()) {
            R1Log.i("MainActivity.handleOAuth", "deep-link delivered code (len=${code.length})")
            Toaster.show("Deep-link delivered OAuth code (WebView should have caught this)", long = true)
        } else {
            R1Log.w("MainActivity.handleOAuth", "deep-link with no code; error=$error")
            Toaster.error("Deep-link with no code: error=$error")
        }
    }

    /** Wall-clock of the last VOLUME-driven wheel emit, per direction.
     *  Lets us throttle the framework's ~30 Hz auto-repeat down to a
     *  more sensible cadence while still letting a held button drive
     *  continuous motion on phones / tablets (the R1's physical wheel
     *  emits each detent as a discrete ACTION_DOWN so this only kicks
     *  in for VOLUME keycodes). */
    private var lastVolumeRepeatUp: Long = 0L
    private var lastVolumeRepeatDown: Long = 0L

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val isDown = event.action == KeyEvent.ACTION_DOWN
        // Press-to-bind capture takes precedence over everything else. The Settings
        // binding dialog installs a callback that swallows the next KEY_DOWN so the
        // act of binding (e.g. pressing VOLUME_UP) doesn't also fire the existing
        // binding for that key.
        if (isDown && event.repeatCount == 0 &&
            com.github.itskenny0.r1ha.core.input.KeyCaptureBus.tryCapture(event.keyCode)
        ) {
            return true
        }
        val action = graph.latestBindings.actionFor(event.keyCode)
            ?: return super.dispatchKeyEvent(event)
        // For physical VOLUME buttons, the framework synthesises auto-repeat events at ~30 Hz
        // when the user holds the button. Throttle the auto-repeat stream to ~8 Hz so a held
        // button gives smooth, controllable motion. The R1's physical wheel maps to DPAD
        // keycodes and emits each detent as a separate ACTION_DOWN with repeatCount=0 — those
        // bypass the throttle entirely so a fast spin never loses an event.
        return when (action) {
            com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_UP -> {
                if (isDown) {
                    val accept = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
                        shouldEmitVolumeRepeat(event, isUp = true)
                    } else true
                    if (accept) graph.wheelInput.emit(WheelEvent.Direction.UP)
                }
                true
            }
            com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_DOWN -> {
                if (isDown) {
                    val accept = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                        shouldEmitVolumeRepeat(event, isUp = false)
                    } else true
                    if (accept) graph.wheelInput.emit(WheelEvent.Direction.DOWN)
                }
                true
            }
            com.github.itskenny0.r1ha.core.input.KeyAction.GO_BACK -> {
                // Dispatch via the activity's OnBackPressedDispatcher so the
                // NavController back stack pops the same way it would for a
                // system Back press. Done here rather than emitted on the bus
                // because the dispatcher is activity-scoped and a Compose-side
                // collector can't reach it cleanly. Fires on KEY_UP to match
                // Android's own back semantics (DOWN is for press-and-hold UI;
                // UP is the actual back gesture).
                if (event.action == KeyEvent.ACTION_UP) onBackPressedDispatcher.onBackPressed()
                true
            }
            else -> {
                if (isDown && event.repeatCount == 0) {
                    com.github.itskenny0.r1ha.core.input.KeyActionBus.emit(action)
                }
                true
            }
        }
    }

    /** Decide whether this VOLUME ACTION_DOWN should produce a wheel
     *  emit. The first press (repeatCount == 0) always fires. Subsequent
     *  framework-synthesised repeats are accepted at most every
     *  [VOLUME_REPEAT_MIN_MS] ms — calibrated to ~8 Hz which feels like
     *  a controllable manual dial spin rather than a runaway. */
    private fun shouldEmitVolumeRepeat(event: KeyEvent, isUp: Boolean): Boolean {
        if (event.repeatCount == 0) {
            // Reset the throttle so the first press is always honoured
            // AND the next auto-repeat measures its delta from this
            // moment instead of any stale previous-burst timestamp.
            if (isUp) lastVolumeRepeatUp = event.eventTime
            else lastVolumeRepeatDown = event.eventTime
            return true
        }
        val last = if (isUp) lastVolumeRepeatUp else lastVolumeRepeatDown
        val delta = event.eventTime - last
        if (delta < VOLUME_REPEAT_MIN_MS) return false
        if (isUp) lastVolumeRepeatUp = event.eventTime
        else lastVolumeRepeatDown = event.eventTime
        return true
    }

    companion object {
        /** Minimum gap between successive wheel emits when a VOLUME
         *  button is held. ~130 ms ≈ 7.7 Hz — the same cadence a
         *  practised thumb on the R1's physical wheel can manage, so
         *  the held-volume-button feel matches a manual spin. */
        private const val VOLUME_REPEAT_MIN_MS = 130L

        /** Intent extra used by the app-shortcut definitions (see
         *  res/xml/shortcuts.xml) to ask MainActivity to deep-link
         *  to a specific top-level route on launch. The value is a
         *  bare route name (e.g. "search", "assist") that AppNavGraph
         *  resolves via Routes constants. */
        const val EXTRA_INITIAL_ROUTE = "initial_route"
    }
}
