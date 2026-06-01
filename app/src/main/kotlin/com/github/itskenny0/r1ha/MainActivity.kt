package com.github.itskenny0.r1ha

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.InputMethodManager
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
import kotlinx.coroutines.launch
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import com.github.itskenny0.r1ha.core.input.WheelEvent
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.theme.LocalUiOptions
import com.github.itskenny0.r1ha.core.theme.R1ThemeHost
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.nav.AppNavGraph
import com.github.itskenny0.r1ha.nav.Routes

/**
 * Pure decision for the locked start destination. Kept separate from the Activity
 * so it can be unit-tested without an Activity / Compose. "Today" is refused as a
 * landing screen when the user has hidden it (even with startOnDashboard on) so a
 * hidden Today never loads or polls.
 */
fun resolveStartDestination(settings: AppSettings): String = when {
    settings.server == null -> Routes.ONBOARDING
    settings.behavior.startOnDashboard &&
        com.github.itskenny0.r1ha.core.prefs.NavItemId.TODAY !in settings.navPanel.hiddenNavItems ->
        Routes.DASHBOARD
    else -> Routes.CARD_STACK
}

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
            val startDestination = remember(initial) { resolveStartDestination(initial) }
            val navController = rememberNavController()
            R1Log.d("MainActivity.setContent", "startDestination=$startDestination server=${initial.server?.url ?: "null"}")

            // Mirror the current nav destination onto AppGraph so
            // dispatchKeyEvent (synchronous, can't call into Compose) can
            // gate the key-binding intercept on the route. Bindings should
            // only fire on the card stack and dashboard — anywhere else
            // (Settings, Onboarding, Assist text field, etc.) we want
            // keystrokes to pass through to whatever's focused.
            androidx.compose.runtime.DisposableEffect(navController) {
                val listener = androidx.navigation.NavController.OnDestinationChangedListener {
                    _, dest, _ -> graph.currentNavRoute = dest.route
                }
                navController.addOnDestinationChangedListener(listener)
                onDispose {
                    navController.removeOnDestinationChangedListener(listener)
                }
            }

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
            // Provide LocalUiOptions from a NARROWED flow so the CompositionLocal
            // only re-provides (and recomposes the whole card world below) when
            // UiOptions actually changes — not on every unrelated settings edit
            // (a wheel-step toggle, a key rebind, etc.) that mints a new AppSettings.
            // UiOptions is a value-equal @Immutable data class, so
            // distinctUntilChanged collapses no-op emissions. Seeded from the
            // already-collected `settings` so there's no extra suspend before the
            // first frame.
            val uiOptions by remember {
                graph.settings.settings
                    .map { it.ui }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(initialValue = settings.ui)
            // Strict-mode background-refresh multiplier, narrowed so unrelated settings edits
            // don't re-provide it. Only meaningful when strict mode is on (otherwise 1).
            val bgRefreshMultiplier by remember {
                graph.settings.settings
                    .map { com.github.itskenny0.r1ha.core.ha.ConnectionTuning.from(it.connection).backgroundRefreshMultiplier }
                    .distinctUntilChanged()
            }.collectAsStateWithLifecycle(
                initialValue = com.github.itskenny0.r1ha.core.ha.ConnectionTuning.from(settings.connection).backgroundRefreshMultiplier,
            )
            R1ThemeHost(themeId = themeNow) {
                CompositionLocalProvider(
                    LocalUiOptions provides uiOptions,
                    com.github.itskenny0.r1ha.core.theme.LocalHaBearerToken provides bearerToken,
                    com.github.itskenny0.r1ha.ui.components.LocalBackgroundRefreshMultiplier provides bgRefreshMultiplier,
                ) {
                    // Wrap the nav graph in a Box so the in-app ToastHost can
                    // overlay every navigated screen. The toast bus is process-
                    // scoped (see R1Toast); the host just renders whatever event
                    // it last received as long as the toast feature is enabled.
                    //
                    // The shell paints the bezel area with the theme background
                    // so every screen renders bit-for-bit identical to before on
                    // the R1 while larger displays get a clean backdrop instead
                    // of bare window edges. Structural width decisions read
                    // LocalWindowTier; the few raw-width exceptions opt out via
                    // the helpers in ui/layout/Breakpoints (Cameras GRID uses
                    // gridColumnsFor to keep its column count adaptive).
                    Box(
                        modifier = androidx.compose.ui.Modifier
                            .fillMaxSize()
                            .background(com.github.itskenny0.r1ha.core.theme.R1.Bg),
                    ) {
                        // Provide the responsive window tier high in the tree so every
                        // screen below can read LocalWindowTier without re-measuring, and
                        // wrap the nav graph in the AdaptiveNavShell. On the R1 / compact
                        // tiers the shell is a pure passthrough (no rail / drawer), so the
                        // card-stack + wheel experience renders exactly as before; medium
                        // gets a NavigationRail and expanded / XL a permanent drawer. The
                        // shell's onNavigate routes through the same NavController with the
                        // top-level back-stack semantics so tab switches don't pile entries.
                        com.github.itskenny0.r1ha.ui.components.ProvideWindowTier {
                            val currentRoute = navController
                                .currentBackStackEntryAsState().value?.destination?.route
                            // User control over the side panel: master enable + per-item
                            // visibility. Collected as its own slice so panel edits don't
                            // recompose on every unrelated settings change.
                            val navPanel by graph.settings.settings
                                .map { it.navPanel }
                                .distinctUntilChanged()
                                .collectAsStateWithLifecycle(initialValue = settings.navPanel)
                            val navDestinations = androidx.compose.runtime.remember(navPanel.hiddenNavItems) {
                                com.github.itskenny0.r1ha.ui.components.defaultNavDestinations(
                                    homeRoute = Routes.CARD_STACK,
                                    dashboardRoute = Routes.DASHBOARD,
                                    searchRoute = Routes.SEARCH,
                                    assistRoute = Routes.ASSIST,
                                    settingsRoute = Routes.SETTINGS,
                                ).filter { it.id !in navPanel.hiddenNavItems }
                            }
                            // Suppress the rail / drawer on full-bleed flows where there's
                            // no app to navigate yet (onboarding, the long-lived-token
                            // setup), and whenever the user has turned the side panel off.
                            // Everywhere else the shell decides chrome by tier.
                            val showShellChrome = when (currentRoute) {
                                Routes.ONBOARDING, Routes.LONG_LIVED_TOKEN -> false
                                else -> navPanel.sidePanelEnabled
                            }
                            com.github.itskenny0.r1ha.ui.components.AdaptiveNavShell(
                                destinations = navDestinations,
                                currentRoute = currentRoute,
                                showChrome = showShellChrome,
                                onNavigate = { route ->
                                    navController.navigate(route) {
                                        // Top-level switch semantics: keep a single copy of
                                        // each tab and restore its state rather than stacking.
                                        launchSingleTop = true
                                        restoreState = true
                                        popUpTo(navController.graph.startDestinationId) {
                                            saveState = true
                                        }
                                    }
                                },
                            ) {
                            AppNavGraph(
                                navController = navController,
                                startDestination = startDestination,
                                haRepository = graph.haRepository,
                                settings = graph.settings,
                                tokens = graph.tokens,
                                wheelInput = graph.wheelInput,
                                overrideStore = graph.lovelaceOverrideStore,
                            )
                            }
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
                                    com.github.itskenny0.r1ha.core.input.KeyAction.CARD_UP,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.CARD_DOWN,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.ACTIVATE,
                                    com.github.itskenny0.r1ha.core.input.KeyAction.REFRESH -> {
                                        // Picked up by per-screen collectors
                                        // (CardStackScreen for PAGE_*/CARD_*/ACTIVATE,
                                        // pull-to-refresh-capable screens for REFRESH).
                                        // Nothing for the activity-level handler to do.
                                    }
                                    else -> Unit // WHEEL_* + GO_BACK never land here.
                                }
                            }
                        }
                        // First-run sync onboarding overlay. Renders only when
                        // a server is configured AND the WS is connected AND
                        // the user hasn't dismissed/accepted it before — see
                        // [HaSyncOnboardingPrompt] for the gating logic.
                        // Lives in the activity window (not a Dialog) so that
                        // its capture overlay receives input events normally.
                        val settingsForPrompt by graph.settings.settings
                            .collectAsStateWithLifecycle(initialValue = initial)
                        val connectionForPrompt by graph.haRepository.connection
                            .collectAsStateWithLifecycle()
                        val promptScope = androidx.compose.runtime.rememberCoroutineScope()
                        com.github.itskenny0.r1ha.feature.sync.HaSyncOnboardingPrompt(
                            settings = settingsForPrompt,
                            connection = connectionForPrompt,
                            onMarkSeen = {
                                promptScope.launch {
                                    graph.settings.update { s ->
                                        s.copy(
                                            integrations = s.integrations.copy(
                                                haSyncPromptSeen = true,
                                            ),
                                        )
                                    }
                                }
                            },
                            onChooseImport = { excludedNames ->
                                promptScope.launch {
                                    graph.settings.update { s ->
                                        s.copy(
                                            integrations = s.integrations.copy(
                                                haSyncEnabled = true,
                                                haSyncExcludedCategories = excludedNames,
                                            ),
                                        )
                                    }
                                    // Enable observer fires the initial pull;
                                    // pullNow() makes the import feel immediate
                                    // rather than waiting for the collector to
                                    // wake up.
                                    graph.haSettingsSync.pullNow()
                                }
                            },
                            onChoosePush = { excludedNames ->
                                promptScope.launch {
                                    graph.settings.update { s ->
                                        s.copy(
                                            integrations = s.integrations.copy(
                                                haSyncEnabled = true,
                                                haSyncExcludedCategories = excludedNames,
                                            ),
                                        )
                                    }
                                    graph.haSettingsSync.pushNow()
                                }
                            },
                        )
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
        // Software-keyboard events NEVER trigger bindings. The user's
        // model: bindings exist for the R1's physical wheel + side
        // buttons + any external hardware keyboard the user attaches.
        // The on-screen IME is for typing, not control. Catching this
        // by event flag is robust across IME implementations (some
        // never set deviceId properly; FLAG_SOFT_KEYBOARD is set by the
        // framework whenever the source is an InputMethodService).
        // VIRTUAL_KEYBOARD deviceId is the secondary signal in case an
        // IME sends KeyEvents without the flag.
        val fromSoftKeyboard = (event.flags and KeyEvent.FLAG_SOFT_KEYBOARD) != 0 ||
            event.deviceId == android.view.KeyCharacterMap.VIRTUAL_KEYBOARD
        if (fromSoftKeyboard) {
            return super.dispatchKeyEvent(event)
        }

        val candidate = graph.latestBindings.actionFor(event.keyCode)
        val isWheelKey = candidate == com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_UP ||
            candidate == com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_DOWN

        // Wheel keys must intercept BEFORE the view hierarchy gets the
        // event — otherwise VOLUME_UP/DOWN bound to the wheel would
        // trigger the system volume slider, and DPAD bindings would
        // bleed into Compose's focus-search nav. So wheel handling
        // happens here, eagerly.
        if (isWheelKey && candidate != null) {
            return handleWheelAction(candidate, event, isDown)
        }

        // Non-wheel hardware key with a binding: still gate on the
        // allowlisted routes so a stray side-button press in Settings
        // doesn't accidentally fire a card-stack action. Inside the
        // allowlist, fire the action; outside, pass through to the
        // view hierarchy.
        if (candidate == null) return super.dispatchKeyEvent(event)
        if (!isBindingAllowedRoute(graph.currentNavRoute)) {
            return super.dispatchKeyEvent(event)
        }
        val action = candidate
        // For physical VOLUME buttons, the framework synthesises auto-repeat events at ~30 Hz
        // when the user holds the button. Throttle the auto-repeat stream to ~8 Hz so a held
        // button gives smooth, controllable motion. The R1's physical wheel maps to DPAD
        // keycodes and emits each detent as a separate ACTION_DOWN with repeatCount=0 — those
        // bypass the throttle entirely so a fast spin never loses an event.
        // WHEEL actions never reach here — they're handled eagerly above
        // before super.dispatchKeyEvent, so the system volume slider /
        // focus-search nav never get a chance to consume them.
        return when (action) {
            com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_UP,
            com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_DOWN -> true
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

    /**
     * Emit the wheel direction for a key bound to WHEEL_UP / WHEEL_DOWN.
     * Called from the eager-intercept path in [dispatchKeyEvent] so the
     * system volume slider (for VOLUME_* keycodes) and Compose focus
     * search (for DPAD_* keycodes) never get a shot at the event.
     *
     * VOLUME keycodes auto-repeat at ~30 Hz when held; throttle to ~8 Hz
     * for a controllable feel. DPAD keycodes come from the physical
     * wheel one detent per ACTION_DOWN so they bypass throttling.
     */
    private fun handleWheelAction(
        action: com.github.itskenny0.r1ha.core.input.KeyAction,
        event: KeyEvent,
        isDown: Boolean,
    ): Boolean {
        if (isDown) {
            val isUp = action == com.github.itskenny0.r1ha.core.input.KeyAction.WHEEL_UP
            val accept = when {
                event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ->
                    shouldEmitVolumeRepeat(event, isUp = true)
                event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ->
                    shouldEmitVolumeRepeat(event, isUp = false)
                else -> true
            }
            if (accept) {
                graph.wheelInput.emit(
                    if (isUp) WheelEvent.Direction.UP else WheelEvent.Direction.DOWN,
                )
            }
        }
        return true
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

    /**
     * Allowlist for routes where custom key bindings fire. Only the card
     * stack and dashboard count — they're the surfaces designed around
     * hardware-key control. Everywhere else is config / forms / text
     * input, where intercepting a bound key would steal the keystroke
     * from a focused input field. WHEEL bindings bypass this check
     * separately so the physical wheel keeps scrolling lists on every
     * screen.
     *
     * Null route (very early boot, before navigation settles) returns
     * false so a stray key event during launch never fires an action.
     */
    private fun isBindingAllowedRoute(route: String?): Boolean = when (route) {
        Routes.CARD_STACK, Routes.DASHBOARD -> true
        else -> false
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
