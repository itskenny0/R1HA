package com.github.itskenny0.r1ha.feature.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Today dashboard — single at-a-glance home screen composed from
 * outdoor weather, persons home/away, next calendar event, camera
 * count, and notification count. Each section is its own tappable
 * card that drills into the corresponding full-list screen.
 *
 * The dashboard is **read-only**; no toggles, no service calls. Its
 * job is to answer "what should I know right now?" in one glance,
 * then route the user to the right detail surface for follow-up.
 */
@Composable
fun DashboardScreen(
    haRepository: HaRepository,
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
    onOpenWeather: () -> Unit,
    onOpenPersons: () -> Unit,
    onOpenCalendars: () -> Unit,
    onOpenCameras: () -> Unit,
    onOpenNotifications: () -> Unit,
    onOpenScenes: () -> Unit,
    /** Tap the DRAW tile in MetricsRow → Energy summary surface
     *  (production, today's kWh, top consumers). Same data the
     *  DRAW tile already shows, just expanded. */
    onOpenEnergy: () -> Unit = {},
    /** Tap the battery indicator in the top bar → Device screen
     *  (brightness, volume, flashlight). Only fires when the
     *  indicator is visible (hideStatusBar + opt-in). */
    onOpenDevice: () -> Unit = {},
    /** Cards icon — opens the card stack from anywhere on the
     *  dashboard. Critical for the kiosk-mode 'Start on Dashboard'
     *  path where the back button has no card stack on the back
     *  stack to pop to. */
    onOpenCardStack: () -> Unit = {},
    /** Settings icon — same rationale: when Dashboard is the start
     *  destination, the only way to reach Settings is via this
     *  explicit affordance. */
    onOpenSettings: () -> Unit = {},
    /** Mic glyph — opens HA Assist directly. Same affordance as the
     *  card stack chrome so the action is consistent across surfaces. */
    onOpenAssist: () -> Unit = {},
    /**
     * Drill into a specific entity's recent state history (the same surface that
     * Search and Recent Activity use). Today: wired from LowBatteryCard rows so
     * tapping "sensor.kitchen_motion_battery 8%" jumps straight to that battery's
     * history view rather than leaving the user to search for it.
     */
    onOpenHistory: (entityId: String) -> Unit = {},
    /** True when the back stack has at least one previous entry —
     *  the chevron-back tile renders only when this is true so the
     *  inert chevron isn't visible on the kiosk start path. */
    canGoBack: Boolean = true,
) {
    val vm: DashboardViewModel = viewModel(factory = DashboardViewModel.factory(haRepository, settings))
    val ui by vm.ui.collectAsState()
    // Read per-section visibility + interval/threshold settings live.
    // Falls back to defaults during cold paint so the dashboard is
    // never empty during the first DataStore read.
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val ds = appSettings.dashboard
    // Auto-refresh — interval comes from the dashboard prefs; 0 disables
    // auto-refresh entirely (pull-down only).
    val refreshSec = ds.refreshIntervalSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        // Still trigger one initial load when auto-refresh is off.
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        // Custom top bar — instead of R1TopBar's bare back+title, this
        // dashboard surface needs explicit CARDS + SETTINGS entries so a
        // kiosk-mode 'Start on Dashboard' user isn't trapped. The
        // chevron-back hides entirely when canGoBack is false (the
        // start-destination path).
        DashboardTopBar(
            onBack = onBack,
            canGoBack = canGoBack,
            onOpenCardStack = onOpenCardStack,
            onOpenSettings = onOpenSettings,
            onOpenAssist = onOpenAssist,
            // Mirror the card-stack chrome — when the user has hidden
            // the system status bar AND opted into the app-side battery
            // indicator, surface it here so they don't lose visibility
            // of charge level just by sitting on the dashboard.
            showBatteryIndicator = appSettings.behavior.hideStatusBar &&
                appSettings.behavior.showBatteryWhenStatusBarHidden,
            onOpenDevice = onOpenDevice,
        )
        if (ui.loading && ui.weather == null && ui.persons == null && ui.nextEvent == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        // On tablets cap the dashboard content at 1000 dp and centre it — wide enough
        // to show the 2-column tile pairs without each tile stretching to 640 dp+ on
        // a landscape 1280 dp screen. Uses AdaptiveContent's box rather than the 800 dp
        // list-screen default because the dashboard has 2 columns and needs more room.
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(
            modifier = Modifier.weight(1f),
            maxWidth = 1000.dp,
        ) {
        // Wire the physical wheel to the dashboard's verticalScroll so
        // kiosk-mode users can scroll through a tall dashboard without
        // touching the screen. Same acceleration profile as elsewhere.
        val scrollState = rememberScrollState()
        com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
            wheelInput = wheelInput,
            scrollState = scrollState,
            settings = settings,
        )
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = ui.loading,
            onRefresh = { vm.refresh() },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Detect 'all sections hidden' so we can render a friendly
            // empty state instead of a near-blank dashboard — happens
            // when a user turns every toggle in Settings → DASHBOARD off.
            val anyVisible = ds.showGreeting || ds.showWeather || ds.showSun ||
                ds.showTimers || ds.showMedia || ds.showPersons ||
                ds.showNextEvent || ds.showPower || ds.showMetrics ||
                ds.showLowBattery || ds.showInlineAlerts
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (ds.showGreeting) Greeting()
                // Error banner — surfaces a failed refresh in StatusRed so
                // the user knows why the dashboard is sparse, rather than
                // being left wondering whether HA actually has no data or
                // the app just failed to fetch. Sits below the greeting so
                // the screen still feels like itself; clearing the error
                // happens automatically on the next successful refresh.
                if (ui.error != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(R1.ShapeS)
                            .background(R1.StatusRed.copy(alpha = 0.18f))
                            .border(1.dp, R1.StatusRed.copy(alpha = 0.4f), R1.ShapeS)
                            .r1Pressable(onClick = { vm.refresh() })
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Column {
                            Text(
                                text = "Dashboard refresh failed. Tap to retry.",
                                style = R1.body,
                                color = R1.StatusRed,
                            )
                            Text(
                                text = ui.error ?: "",
                                style = R1.labelMicro,
                                color = R1.InkSoft,
                                maxLines = 2,
                            )
                        }
                    }
                }
                // On tablets (≥ 600 dp) show paired tiles side-by-side. If only one of a
                // pair is visible it falls back to full width automatically.
                val isTablet = LocalConfiguration.current.screenWidthDp >= 600

                // Tile rendering — each branch checks the per-tile show* flag plus any
                // data presence guards that were already in place. Order is driven by
                // ds.tileOrder so the user can reorder under Settings → DASHBOARD →
                // TILE ORDER without us hardcoding the sequence here. Unknown ids are
                // skipped (forward-compat for future tile additions).
                val resolvedOrder = ds.tileOrder.ifEmpty {
                    com.github.itskenny0.r1ha.core.prefs.DashboardSettings.DEFAULT_TILE_ORDER
                }
                for (tileId in resolvedOrder.distinct()) {
                    val tile = runCatching {
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.valueOf(tileId)
                    }.getOrNull() ?: continue
                    when (tile) {
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.WEATHER_PERSONS -> {
                            DashboardPair(
                                isTablet = isTablet,
                                leftVisible = ds.showWeather && ui.weather != null,
                                rightVisible = ds.showPersons && ui.persons != null,
                                left = { ui.weather?.let { WeatherCard(it, onClick = onOpenWeather) } },
                                right = { ui.persons?.let { PersonsCard(it, onClick = onOpenPersons) } },
                            )
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.SUN_CALENDAR -> {
                            DashboardPair(
                                isTablet = isTablet,
                                leftVisible = ds.showSun && ui.sun != null,
                                rightVisible = ds.showNextEvent && ui.nextEvent != null,
                                left = { ui.sun?.let { SunCard(it, onClick = { onOpenHistory("sun.sun") }) } },
                                right = { ui.nextEvent?.let { CalendarCard(it, onClick = onOpenCalendars) } },
                            )
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.TIMERS -> {
                            if (ds.showTimers && ui.timers.isNotEmpty()) {
                                Text(text = "TIMERS", style = R1.labelMicro, color = R1.InkSoft)
                                if (isTablet) {
                                    ui.timers.chunked(2).forEach { pair ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        ) {
                                            pair.forEach { t ->
                                                Box(Modifier.weight(1f)) {
                                                    TimerCard(
                                                        t,
                                                        onPause = { vm.timerService(t.entityId, "pause") },
                                                        onResume = { vm.timerService(t.entityId, "start") },
                                                        onCancel = { vm.timerService(t.entityId, "cancel") },
                                                    )
                                                }
                                            }
                                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                                        }
                                    }
                                } else {
                                    for (t in ui.timers) {
                                        TimerCard(
                                            t,
                                            onPause = { vm.timerService(t.entityId, "pause") },
                                            onResume = { vm.timerService(t.entityId, "start") },
                                            onCancel = { vm.timerService(t.entityId, "cancel") },
                                        )
                                    }
                                }
                            }
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.MEDIA -> {
                            if (ds.showMedia && ui.media.isNotEmpty()) {
                                Text(text = "NOW PLAYING", style = R1.labelMicro, color = R1.InkSoft)
                                for (m in ui.media) {
                                    MediaCard(
                                        media = m,
                                        onPlayPause = {
                                            vm.mediaTransport(
                                                m.entityId,
                                                com.github.itskenny0.r1ha.core.ha.MediaTransport.PLAY_PAUSE,
                                            )
                                        },
                                        onNext = {
                                            vm.mediaTransport(
                                                m.entityId,
                                                com.github.itskenny0.r1ha.core.ha.MediaTransport.NEXT,
                                            )
                                        },
                                        onPrev = {
                                            vm.mediaTransport(
                                                m.entityId,
                                                com.github.itskenny0.r1ha.core.ha.MediaTransport.PREVIOUS,
                                            )
                                        },
                                    )
                                }
                            }
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.METRICS -> {
                            if (ds.showMetrics) {
                                MetricsRow(
                                    cameraCount = ui.cameraCount,
                                    notificationCount = ui.notifications.size,
                                    lightsOnCount = ui.lightsOnCount,
                                    totalPowerW = if (ds.showPower) ui.totalPowerW else -1,
                                    amberW = ds.powerAmberThresholdW,
                                    redW = ds.powerRedThresholdW,
                                    onLights = onOpenScenes,
                                    onLightsLongPress = { vm.allLightsOff() },
                                    onCameras = onOpenCameras,
                                    onNotifications = onOpenNotifications,
                                    onPower = onOpenEnergy,
                                )
                            }
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.LOW_BATTERY -> {
                            if (ds.showLowBattery && ui.lowBatteries.isNotEmpty()) {
                                LowBatteryCard(ui.lowBatteries, onOpenHistory = onOpenHistory)
                            }
                        }
                        com.github.itskenny0.r1ha.core.prefs.DashboardTile.INLINE_ALERTS -> {
                            if (ds.showInlineAlerts && ui.notifications.isNotEmpty() && ds.inlineAlertsCount > 0) {
                                Spacer(Modifier.size(2.dp))
                                Text(text = "RECENT ALERTS", style = R1.labelMicro, color = R1.InkSoft)
                                for (notif in ui.notifications.take(ds.inlineAlertsCount)) {
                                    NotificationPreview(
                                        notif,
                                        onClick = onOpenNotifications,
                                        onDismiss = { vm.dismissNotification(notif.notificationId) },
                                    )
                                }
                            }
                        }
                    }
                }
                if (!anyVisible) {
                    Spacer(Modifier.size(24.dp))
                    Text(
                        text = "Every dashboard tile is hidden.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    Text(
                        text = "Re-enable cards under Settings → DASHBOARD → VISIBLE CARDS.",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(12.dp))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = onOpenSettings)
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    ) {
                        Text(text = "OPEN SETTINGS", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                }
                Spacer(Modifier.size(24.dp))
            }
        }
        } // AdaptiveContent
    }
}

@Composable
private fun WeatherCard(
    w: DashboardViewModel.WeatherSummary,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = conditionGlyph(w.condition),
            style = R1.numeralXl,
            color = conditionAccent(w.condition),
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = w.name.uppercase(), style = R1.labelMicro, color = R1.InkSoft)
            Text(
                text = w.condition.replace('-', ' ').uppercase(),
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = R1.Ink,
            )
        }
        if (w.temperature != null) {
            Text(
                text = "${"%.0f".format(w.temperature)}${w.temperatureUnit ?: "°"}",
                style = R1.numeralXl,
                color = R1.Ink,
            )
        }
    }
}

@Composable
private fun SunCard(s: DashboardViewModel.SunSummary, onClick: () -> Unit = {}) {
    // Tap opens sun.sun's history so the user can drill into elevation curves and the
    // next-rising/setting transitions without leaving the app.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Sun glyph state — above_horizon = ☀, below_horizon = ☾ +
            // muted tint so the night state reads as quiet.
            val isUp = s.state == "above_horizon"
            Text(
                text = if (isUp) "☀" else "☾",
                style = R1.numeralXl,
                color = if (isUp) R1.AccentWarm else R1.AccentCool,
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "SUN", style = R1.labelMicro, color = R1.InkSoft)
                Text(
                    text = (if (isUp) "ABOVE HORIZON" else "BELOW HORIZON") +
                        (s.elevation?.let { " · ${"%.1f".format(it)}°" } ?: ""),
                    style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                    color = R1.Ink,
                )
            }
        }
        // Next rise / set — relative time + HH:mm absolute. The
        // relative is the at-a-glance answer ('in 4h'); the absolute
        // helps with concrete planning ('alarm before sunrise').
        val locale = java.util.Locale.getDefault()
        val timeFmt = java.time.format.DateTimeFormatter.ofLocalizedTime(
            java.time.format.FormatStyle.SHORT,
        ).withLocale(locale)
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT RISE", style = R1.labelMicro, color = R1.InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelativeTimeLabel(at = s.nextRising, color = R1.AccentWarm, style = R1.labelMicro)
                    s.nextRising?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = it.atZone(java.time.ZoneId.systemDefault()).format(timeFmt),
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT SET", style = R1.labelMicro, color = R1.InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelativeTimeLabel(at = s.nextSetting, color = R1.AccentCool, style = R1.labelMicro)
                    s.nextSetting?.let {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = it.atZone(java.time.ZoneId.systemDefault()).format(timeFmt),
                            style = R1.labelMicro,
                            color = R1.InkSoft,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardTopBar(
    onBack: () -> Unit,
    canGoBack: Boolean,
    onOpenCardStack: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAssist: () -> Unit,
    showBatteryIndicator: Boolean = false,
    onOpenDevice: () -> Unit = {},
) {
    // Match R1TopBar's vertical metrics so the dashboard top edge
    // aligns with every other sub-screen on the device.
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        ) {
            // Chevron-back tile — only rendered when canGoBack is true.
            // On the kiosk 'Start on Dashboard' path the back stack is
            // empty, so the chevron would be inert; hiding it removes
            // the dead affordance + makes the CARDS / SETTINGS shortcuts
            // the obvious escape paths.
            if (canGoBack) {
                com.github.itskenny0.r1ha.ui.components.ChevronBack(onClick = onBack)
                Spacer(Modifier.width(4.dp))
            } else {
                Spacer(Modifier.width(8.dp))
            }
            // 'TODAY · MON' — abbreviated day-of-week alongside the
            // title so the screen identifies which day's snapshot the
            // user is looking at, particularly handy past midnight when
            // a glance might otherwise still 'feel like yesterday'.
            val dayName = androidx.compose.runtime.remember {
                java.time.LocalDate.now().dayOfWeek.getDisplayName(
                    java.time.format.TextStyle.SHORT,
                    java.util.Locale.getDefault(),
                ).uppercase()
            }
            Text(
                text = "TODAY · $dayName",
                style = R1.screenTitle,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
            )
            // Battery indicator — only when both 'hide statusbar' and
            // 'show battery when statusbar hidden' are on. Sits before
            // the action chips so charge level reads naturally
            // left-to-right past the title.
            if (showBatteryIndicator) {
                com.github.itskenny0.r1ha.ui.components.BatteryIndicator(onClick = onOpenDevice)
                Spacer(Modifier.width(8.dp))
            }
            // Assist — same affordance as on the card stack chrome, so the
            // action is consistent across surfaces. Sits before CARDS so it's
            // the closer-to-centre 'talk to HA' tap target for thumb reach on
            // a wall-mounted R1. Uses the hand-drawn AssistMicGlyph (same
            // as the chrome) rather than the 🎤 emoji so the dashboard
            // doesn't switch to colour-emoji rendering mid-row.
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = onOpenAssist),
                contentAlignment = Alignment.Center,
            ) {
                com.github.itskenny0.r1ha.ui.components.AssistMicGlyph(size = 16.dp)
            }
            Spacer(Modifier.width(4.dp))
            // CARDS — opens the card stack. Most-frequent action from the
            // dashboard for kiosk users who occasionally want to control
            // something rather than just glance.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = onOpenCardStack)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = "CARDS", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // SETTINGS gear — wireframe drawn glyph (same as the
            // card-stack chrome) for consistency. Tap opens Settings.
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = onOpenSettings),
                contentAlignment = Alignment.Center,
            ) {
                com.github.itskenny0.r1ha.ui.components.SettingsCogGlyph(size = 18.dp)
            }
        }
        // Hairline divider — matches R1TopBar's exact metric.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun LowBatteryCard(
    entries: List<String>,
    onOpenHistory: (entityId: String) -> Unit,
) {
    // Each entry is "<entity_id>=<pct>": split and render two-column. Each row is
    // a tap target that opens the entity's history view so the user can drill into
    // which battery is dying without scrolling Search for the entity_id.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusAmber.copy(alpha = 0.12f))
            .border(1.dp, R1.StatusAmber.copy(alpha = 0.4f), R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = "${entries.size} BATTERIES LOW",
            style = R1.labelMicro.copy(fontWeight = FontWeight.SemiBold),
            color = R1.StatusAmber,
        )
        for (entry in entries.take(5)) {
            val idx = entry.indexOf('=')
            val id = if (idx > 0) entry.substring(0, idx) else entry
            val pct = if (idx > 0) entry.substring(idx + 1) else "?"
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onOpenHistory(id) })
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = id,
                    style = R1.body,
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "${pct}%",
                    style = R1.body,
                    color = if (pct.toIntOrNull()?.let { it < 10 } == true) R1.StatusRed else R1.StatusAmber,
                )
            }
        }
        if (entries.size > 5) {
            Text(
                text = "and ${entries.size - 5} more…",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun TimerCard(
    t: DashboardViewModel.TimerSummary,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    // Active-or-paused timer with three transport pills. CANCEL is on
    // the right with the StatusRed accent to flag the destructive
    // action; the PAUSE/RESUME pill swaps semantically based on the
    // current state so the user always sees the OTHER option.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (t.state) {
                "active" -> "RUNNING" to R1.AccentGreen
                "paused" -> "PAUSED" to R1.StatusAmber
                else -> t.state.uppercase() to R1.InkSoft
            }
            Text(text = label, style = R1.labelMicro, color = color)
            Spacer(Modifier.width(10.dp))
            Text(text = t.name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(8.dp))
            // Paused timers freeze finishes_at at the pause moment, so a
            // RelativeTimeLabel would tick into the past and show
            // 'finished 5 min ago' even though the timer hasn't fired.
            // Instead surface HA's `remaining` attribute (HH:MM:SS) as
            // a static label so the user sees the actual time left.
            if (t.state == "paused" && !t.remaining.isNullOrBlank()) {
                Text(text = t.remaining, style = R1.labelMicro, color = color)
            } else {
                RelativeTimeLabel(at = t.finishesAt, color = color, style = R1.labelMicro)
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            val isActive = t.state == "active"
            TimerPill(
                modifier = Modifier.weight(1f),
                label = if (isActive) "PAUSE" else "RESUME",
                accent = if (isActive) R1.StatusAmber else R1.AccentGreen,
                onClick = if (isActive) onPause else onResume,
            )
            TimerPill(
                modifier = Modifier.weight(1f),
                label = "CANCEL",
                accent = R1.StatusRed,
                onClick = onCancel,
            )
        }
    }
}

@Composable
private fun TimerPill(
    modifier: Modifier,
    label: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.labelMicro, color = accent)
    }
}

@Composable
private fun Greeting() {
    // Time-of-day greeting + a date/time line. Drives its own 60-second
    // ticker so the time stays current even when the dashboard
    // auto-refresh is disabled (refreshIntervalSec == 0) — otherwise
    // the HH:mm reading froze whenever auto-refresh was off, and the
    // user had to pull-to-refresh just to see the clock advance.
    val tick = androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            // Align the next tick to the next wall-clock minute so the
            // HH:mm reading flips on the minute rather than on an
            // arbitrary 60-second offset from when the screen mounted.
            val now = java.time.LocalDateTime.now()
            val msToNextMinute = (60_000L - (now.second * 1000L + (now.nano / 1_000_000L)))
                .coerceIn(250L, 60_000L)
            kotlinx.coroutines.delay(msToNextMinute)
            tick.intValue++
        }
    }
    // Read tick.intValue so this composable subscribes to the ticker and
    // re-runs on each minute boundary.
    @Suppress("UNUSED_VARIABLE")
    val unused = tick.intValue
    val now = java.time.LocalDateTime.now()
    val hour = now.hour
    val greeting = when (hour) {
        in 5..11 -> "GOOD MORNING"
        in 12..17 -> "GOOD AFTERNOON"
        in 18..21 -> "GOOD EVENING"
        else -> "GOOD NIGHT"
    }
    val locale = java.util.Locale.getDefault()
    val dateLine = now.toLocalDate().format(
        java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMM").withLocale(locale),
    )
    val timeLine = now.format(
        // FormatStyle.SHORT is locale-aware: 12-hour with AM/PM in
        // en-US, 24-hour in en-GB / de-DE / most of EU. The R1's
        // system locale drives the result.
        java.time.format.DateTimeFormatter.ofLocalizedTime(
            java.time.format.FormatStyle.SHORT,
        ).withLocale(locale),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = greeting, style = R1.sectionHeader, color = R1.AccentWarm, modifier = Modifier.weight(1f))
            Text(text = timeLine, style = R1.numeralM, color = R1.Ink)
        }
        Text(text = dateLine.uppercase(), style = R1.labelMicro, color = R1.InkSoft)
    }
}

@Composable
private fun MediaCard(
    media: DashboardViewModel.MediaSummary,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
) {
    val playing = media.state == "playing"
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (playing) R1.AccentGreen.copy(alpha = 0.22f) else R1.SurfaceMuted)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            ) {
                Text(
                    text = if (playing) "PLAYING" else media.state.uppercase(),
                    style = R1.labelMicro,
                    color = if (playing) R1.AccentGreen else R1.InkSoft,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = media.name,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        val titleLine = listOfNotNull(media.title, media.artist).joinToString(" · ")
        if (titleLine.isNotBlank()) {
            Text(text = titleLine, style = R1.labelMicro, color = R1.InkSoft, maxLines = 2)
        }
        // Transport row — prev / play-pause / next.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransportButton(label = "◄◄", onClick = onPrev, modifier = Modifier.weight(1f))
            TransportButton(
                label = if (playing) "❚❚" else "▶",
                onClick = onPlayPause,
                modifier = Modifier.weight(1f),
                accent = R1.AccentWarm,
            )
            TransportButton(label = "►►", onClick = onNext, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun TransportButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: androidx.compose.ui.graphics.Color = R1.InkSoft,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = R1.body, color = accent)
    }
}

@Composable
private fun PersonsCard(
    p: DashboardViewModel.PersonsSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "PEOPLE", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.weight(1f))
            Text(text = "${p.homeCount} HOME", style = R1.labelMicro, color = R1.AccentGreen)
            Spacer(Modifier.width(8.dp))
            Text(text = "${p.awayCount} AWAY", style = R1.labelMicro, color = R1.StatusAmber)
        }
        for ((name, state) in p.rows) {
            Row {
                Text(text = name, style = R1.body, color = R1.Ink, modifier = Modifier.weight(1f), maxLines = 1)
                Spacer(Modifier.width(8.dp))
                val color = when (state.lowercase()) {
                    "home" -> R1.AccentGreen
                    "not_home", "away" -> R1.StatusAmber
                    "unknown", "unavailable" -> R1.StatusRed
                    else -> R1.AccentCool
                }
                Text(text = state.uppercase(), style = R1.labelMicro, color = color)
            }
        }
        if (p.total > p.rows.size) {
            Text(
                text = "and ${p.total - p.rows.size} more. Tap to see all",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun CalendarCard(
    c: DashboardViewModel.CalendarSummary,
    onClick: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.happeningNow) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentGreen.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = "NOW", style = R1.labelMicro, color = R1.AccentGreen)
                }
                Spacer(Modifier.width(8.dp))
            } else {
                Text(text = "NEXT", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.width(8.dp))
            }
            if (c.allDay) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.AccentCool.copy(alpha = 0.22f))
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                ) {
                    Text(text = "ALL-DAY", style = R1.labelMicro, color = R1.AccentCool)
                }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                text = c.calendarName.uppercase(),
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            RelativeTimeLabel(at = c.eventStart, color = R1.InkMuted, style = R1.labelMicro)
        }
        Text(text = c.eventTitle, style = R1.body, color = R1.Ink, maxLines = 2)
    }
}

@Composable
private fun MetricsRow(
    cameraCount: Int,
    notificationCount: Int,
    lightsOnCount: Int,
    totalPowerW: Int,
    amberW: Int,
    redW: Int,
    onLights: () -> Unit,
    onLightsLongPress: () -> Unit,
    onCameras: () -> Unit,
    onNotifications: () -> Unit,
    onPower: () -> Unit = {},
) {
    // Power tile sits on its own row when present (wider value display).
    // Hidden entirely when the install has no power-class sensors. Tap
    // routes to the Energy summary — same data but with production +
    // top consumers + today's kWh.
    if (totalPowerW >= 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = onPower)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "DRAW", style = R1.labelMicro, color = R1.InkSoft)
                    Text(
                        text = "${totalPowerW} W",
                        style = R1.numeralXl,
                        color = when {
                            totalPowerW > redW -> R1.StatusRed
                            totalPowerW > amberW -> R1.StatusAmber
                            else -> R1.AccentCool
                        },
                    )
                }
                Text(
                    text = "sum of power sensors",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Lights-on count from a server-side Jinja count() — much
        // lighter than fetching every light entity. -1 sentinel
        // renders as '—' so the tile doesn't claim "0 on" while the
        // template is still rendering. Tap routes to the Scenes
        // screen for the master-action trio; long-press fires
        // ALL LIGHTS OFF directly from the dashboard without an
        // extra navigation hop — ideal kiosk affordance for "you
        // can see they're on, deal with it now".
        Metric(
            modifier = Modifier.weight(1f),
            label = "LIGHTS ON",
            value = if (lightsOnCount < 0) "—" else lightsOnCount.toString(),
            accent = if (lightsOnCount > 0) R1.AccentWarm else R1.InkSoft,
            onClick = onLights,
            onLongPress = onLightsLongPress,
        )
        Metric(
            modifier = Modifier.weight(1f),
            label = "CAMERAS",
            value = cameraCount.toString(),
            accent = R1.AccentCool,
            onClick = onCameras,
        )
        Metric(
            modifier = Modifier.weight(1f),
            label = "ALERTS",
            value = notificationCount.toString(),
            accent = if (notificationCount > 0) R1.StatusRed else R1.InkSoft,
            onClick = onNotifications,
        )
    }
}

@Composable
private fun Metric(
    modifier: Modifier,
    label: String,
    value: String,
    accent: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
) {
    val pressable = if (onLongPress != null) {
        Modifier.r1RowPressable(onTap = onClick, onLongPress = onLongPress)
    } else {
        Modifier.r1Pressable(onClick = onClick)
    }
    Column(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .then(pressable)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Text(text = label, style = R1.labelMicro, color = R1.InkSoft)
        Text(text = value, style = R1.numeralXl, color = accent)
    }
}

@Composable
private fun NotificationPreview(
    n: com.github.itskenny0.r1ha.core.ha.PersistentNotification,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusRed.copy(alpha = 0.10f))
            .border(1.dp, R1.StatusRed.copy(alpha = 0.35f), R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = n.title?.takeIf { it.isNotBlank() } ?: n.notificationId,
                    style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                    color = R1.Ink,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(6.dp))
                // 'Created at' relative timestamp — surfaces 'just now'
                // / '2 m' so the user can tell a fresh alert from a
                // long-standing one without leaving the dashboard.
                RelativeTimeLabel(
                    at = n.createdAt,
                    color = R1.InkMuted,
                    style = R1.labelMicro,
                )
            }
            Text(
                text = n.message,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(8.dp))
        // ✕ dismiss tile — separate tap target from the row's onClick
        // so a dismiss doesn't accidentally navigate to the
        // Notifications surface (and vice versa).
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(R1.ShapeS)
                .r1Pressable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "✕", style = R1.body, color = R1.InkSoft)
        }
    }
}

private fun conditionGlyph(condition: String): String = when (condition.lowercase()) {
    "sunny", "clear" -> "☀"
    "clear-night" -> "☾"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁"
    "rainy" -> "☂"
    "pouring" -> "☔"
    "snowy", "snowy-rainy" -> "❄"
    "fog" -> "≋"
    "lightning", "lightning-rainy" -> "⚡"
    "windy", "windy-variant" -> "🌬"
    "hail" -> "•"
    else -> "·"
}

private fun conditionAccent(condition: String): androidx.compose.ui.graphics.Color =
    when (condition.lowercase()) {
        "sunny", "clear" -> R1.AccentWarm
        "rainy", "pouring", "snowy", "snowy-rainy", "fog" -> R1.AccentCool
        "lightning", "lightning-rainy" -> R1.StatusAmber
        "windy", "windy-variant" -> R1.AccentNeutral
        else -> R1.InkSoft
    }

/**
 * Responsive tile pair for the dashboard.
 *
 * On tablets (isTablet == true) and when BOTH tiles are visible, renders them
 * side-by-side at equal width. On phones / R1, or when only one tile is visible,
 * renders them in a plain vertical stack — the visible one fills full width.
 * Tiles themselves use fillMaxWidth internally so they naturally stretch to
 * their container's width without any modification.
 */
@Composable
private fun DashboardPair(
    isTablet: Boolean,
    leftVisible: Boolean,
    rightVisible: Boolean,
    left: @Composable () -> Unit,
    right: @Composable () -> Unit,
) {
    if (isTablet && leftVisible && rightVisible) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(modifier = Modifier.weight(1f)) { left() }
            Box(modifier = Modifier.weight(1f)) { right() }
        }
    } else {
        if (leftVisible) left()
        if (rightVisible) right()
    }
}
