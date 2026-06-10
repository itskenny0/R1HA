package com.github.itskenny0.r1ha.feature.dashboard

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import com.github.itskenny0.r1ha.ui.icons.R1Icons
import com.github.itskenny0.r1ha.ui.icons.R1IconSet
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.r1RowPressable

/**
 * Today dashboard: single at-a-glance home screen composed from
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
    /** Cards icon: opens the card stack from anywhere on the
     *  dashboard. Critical for the kiosk-mode 'Start on Dashboard'
     *  path where the back button has no card stack on the back
     *  stack to pop to. */
    onOpenCardStack: () -> Unit = {},
    /** Settings icon: same rationale; when Dashboard is the start
     *  destination, the only way to reach Settings is via this
     *  explicit affordance. */
    onOpenSettings: () -> Unit = {},
    /** Mic glyph: opens HA Assist directly. Same affordance as the
     *  card stack chrome so the action is consistent across surfaces. */
    onOpenAssist: () -> Unit = {},
    /**
     * Drill into a specific entity's recent state history (the same surface that
     * Search and Recent Activity use). Today: wired from LowBatteryCard rows so
     * tapping "sensor.kitchen_motion_battery 8%" jumps straight to that battery's
     * history view rather than leaving the user to search for it.
     */
    onOpenHistory: (entityId: String) -> Unit = {},
    /** True when the back stack has at least one previous entry;
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
    // Auto-refresh: interval comes from the dashboard prefs; 0 disables
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
        // Custom top bar: instead of R1TopBar's bare back+title, this
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
            // Mirror the card-stack chrome: when the user has hidden
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
        // Flagship responsive surface. The Today dashboard is the reference implementation
        // other surfaces copy: it reads the window tier once, then flows its tiles into
        // 1 / 2 / 3 columns by tier and centres + caps the whole column on the largest
        // windows (via R1CenteredContent) so a 13" tablet shows a genuine multi-column
        // dashboard rather than a stretched phone layout. On R1 / compact this is a
        // single-column passthrough, identical to before.
        val window = com.github.itskenny0.r1ha.ui.components.rememberWindowTier()
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        com.github.itskenny0.r1ha.ui.components.R1CenteredContent(
            modifier = Modifier.weight(1f),
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
            isRefreshing = ui.refreshing,
            onRefresh = { vm.refresh(indicate = true) },
            modifier = Modifier.fillMaxSize(),
        ) {
            // Detect 'all sections hidden' so we can render a friendly
            // empty state instead of a near-blank dashboard. Happens
            // when a user turns every toggle in Settings → DASHBOARD off.
            val anyVisible = ds.showGreeting || ds.showWeather || ds.showSun ||
                ds.showTimers || ds.showMedia || ds.showPersons ||
                ds.showNextEvent || ds.showPower || ds.showMetrics ||
                ds.showLowBattery || ds.showInlineAlerts
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.screenGutter, vertical = R1.space.s)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(dimens.sectionGap),
            ) {
                if (ds.showGreeting) Greeting()
                // Error banner: surfaces a failed refresh in StatusRed so
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
                            .padding(horizontal = R1.space.m, vertical = R1.space.s),
                    ) {
                        Column {
                            Text(
                                text = "Dashboard refresh failed. Tap to retry.",
                                style = responsiveType(R1.body),
                                color = R1.StatusRed,
                            )
                            Text(
                                text = ui.error ?: "",
                                style = responsiveType(R1.labelMicro),
                                color = R1.InkSoft,
                                maxLines = 2,
                            )
                        }
                    }
                }
                // Within a tile, pair the weather/persons + sun/calendar halves side by
                // side once there's medium-or-wider room. (Distinct from the cross-tile
                // column flow below, which arranges whole tiles into N columns.)
                val pairWithinTile = window.isAtLeastMedium

                // Tile rendering: order is driven by ds.tileOrder so the user can reorder
                // under Settings → DASHBOARD → TILE ORDER without us hardcoding the sequence.
                // Unknown ids are skipped (forward-compat for future tile additions). Each
                // visible tile is captured as a composable lambda so the multi-column flow
                // can distribute whole tiles across columns on big tablets.
                val resolvedOrder = ds.tileOrder.ifEmpty {
                    com.github.itskenny0.r1ha.core.prefs.DashboardSettings.DEFAULT_TILE_ORDER
                }
                val tiles = buildList<@Composable () -> Unit> {
                    for (tileId in resolvedOrder.distinct()) {
                        val tile = runCatching {
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.valueOf(tileId)
                        }.getOrNull() ?: continue
                        when (tile) {
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.WEATHER_PERSONS -> {
                                val l = ds.showWeather && ui.weather != null
                                val r = ds.showPersons && ui.persons != null
                                if (l || r) add {
                                    DashboardPair(
                                        isTablet = pairWithinTile,
                                        leftVisible = l,
                                        rightVisible = r,
                                        left = { ui.weather?.let { WeatherCard(it, onClick = onOpenWeather) } },
                                        right = { ui.persons?.let { PersonsCard(it, onClick = onOpenPersons) } },
                                    )
                                }
                            }
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.SUN_CALENDAR -> {
                                val l = ds.showSun && ui.sun != null
                                val r = ds.showNextEvent && ui.nextEvent != null
                                if (l || r) add {
                                    DashboardPair(
                                        isTablet = pairWithinTile,
                                        leftVisible = l,
                                        rightVisible = r,
                                        left = { ui.sun?.let { SunCard(it, onClick = { onOpenHistory("sun.sun") }) } },
                                        right = { ui.nextEvent?.let { CalendarCard(it, onClick = onOpenCalendars) } },
                                    )
                                }
                            }
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.TIMERS -> {
                                if (ds.showTimers && ui.timers.isNotEmpty()) add {
                                    R1Section(title = "Timers", count = ui.timers.size, topSpace = R1.space.s) {
                                        if (pairWithinTile) {
                                            ui.timers.chunked(2).forEach { pair ->
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(R1.space.s),
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
                            }
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.MEDIA -> {
                                if (ds.showMedia && ui.media.isNotEmpty()) add {
                                    R1Section(title = "Now playing", count = ui.media.size, topSpace = R1.space.s) {
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
                            }
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.METRICS -> {
                                if (ds.showMetrics) add {
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
                                if (ds.showLowBattery && ui.lowBatteries.isNotEmpty()) add {
                                    LowBatteryCard(ui.lowBatteries, onOpenHistory = onOpenHistory)
                                }
                            }
                            com.github.itskenny0.r1ha.core.prefs.DashboardTile.INLINE_ALERTS -> {
                                if (ds.showInlineAlerts && ui.notifications.isNotEmpty() && ds.inlineAlertsCount > 0) add {
                                    val shown = ui.notifications.take(ds.inlineAlertsCount)
                                    R1Section(title = "Recent alerts", count = shown.size, topSpace = R1.space.s) {
                                        for (notif in shown) {
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
                    }
                }
                // Flow whole tiles into the tier's column count. One column on R1 / compact
                // (a plain stack, identical to before); two on medium / expanded; three on
                // extra-large, so a big tablet shows a true multi-column dashboard. Tiles are
                // distributed by [distributeIntoColumns] which keeps source order top-to-bottom
                // within each column.
                DashboardColumns(
                    columns = dimens.dashboardColumns,
                    gap = dimens.sectionGap,
                    tiles = tiles,
                )
                if (!anyVisible) {
                    Spacer(Modifier.size(R1.space.xl))
                    Text(
                        text = "Every dashboard tile is hidden.",
                        style = responsiveType(R1.body),
                        color = R1.InkMuted,
                    )
                    Text(
                        text = "Re-enable cards under Settings → DASHBOARD → VISIBLE CARDS.",
                        style = responsiveType(R1.labelMicro),
                        color = R1.InkSoft,
                    )
                    Spacer(Modifier.size(R1.space.m))
                    R1Chip(
                        text = "OPEN SETTINGS",
                        variant = R1ChipVariant.Action,
                        onClick = onOpenSettings,
                    )
                }
                Spacer(Modifier.size(R1.space.xl))
            }
        }
        } // R1CenteredContent
    }
}

/**
 * Renders [tiles] either as a single vertical stack ([columns] == 1, the R1 / compact path,
 * byte-for-byte the old behavior) or distributed across [columns] balanced columns for the
 * multi-column tablet dashboard. Distribution preserves source order top-to-bottom within
 * each column via [distributeIntoColumns]; columns share width equally and each tile keeps
 * its own internal `fillMaxWidth`, so nothing stretches awkwardly.
 */
@Composable
private fun DashboardColumns(
    columns: Int,
    gap: androidx.compose.ui.unit.Dp,
    tiles: List<@Composable () -> Unit>,
) {
    if (columns <= 1) {
        for (tile in tiles) tile()
        return
    }
    val buckets = distributeIntoColumns(tiles.size, columns)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap),
        verticalAlignment = Alignment.Top,
    ) {
        for (bucket in buckets) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(gap),
            ) {
                for (idx in bucket) tiles[idx]()
            }
        }
    }
}

/**
 * Splits [count] indices (0 until count) into [columns] buckets, dealing round-robin so the
 * source order reads naturally top-to-bottom within each column and the columns stay balanced
 * in length (the first columns get the extra tile when it doesn't divide evenly). Pure and
 * unit-tested (no Compose, no view state) so the tile-to-column maths is locked down.
 *
 * Example: count = 5, columns = 2 -> [[0, 2, 4], [1, 3]].
 */
fun distributeIntoColumns(count: Int, columns: Int): List<List<Int>> {
    if (columns <= 1) return listOf((0 until count).toList())
    val buckets = List(columns) { mutableListOf<Int>() }
    for (i in 0 until count) buckets[i % columns].add(i)
    return buckets
}

@Composable
private fun WeatherCard(
    w: DashboardViewModel.WeatherSummary,
    onClick: () -> Unit,
) {
    val unit = w.temperatureUnit ?: "°"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = R1.space.l, vertical = R1.space.l),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // In-house condition line vector (replaces the unicode glyph).
        Icon(
            imageVector = R1Icons.conditionIcon(w.condition),
            contentDescription = null,
            tint = conditionAccent(w.condition),
            modifier = Modifier.size(34.dp),
        )
        Spacer(Modifier.width(R1.space.l))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = w.name.uppercase(),
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = w.condition.replace('-', ' ').uppercase(),
                style = responsiveType(R1.body).copy(fontWeight = FontWeight.SemiBold),
                color = R1.Ink,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // Secondary line: feels-like and humidity when the integration
            // reports them. HA surfaces these in the more-info weather view;
            // they're the next most-glanceable facts after the headline temp.
            val extras = buildList {
                w.apparentTemperature?.let { add("FEELS ${"%.0f".format(it)}$unit") }
                w.humidity?.let { add("$it% RH") }
            }
            if (extras.isNotEmpty()) {
                Text(
                    text = extras.joinToString("  ·  "),
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (w.temperature != null) {
            Text(
                text = "${"%.0f".format(w.temperature)}$unit",
                style = responsiveType(R1.numeralXl),
                color = R1.Ink,
            )
        }
        // Drill-in affordance: the card opens the full Weather surface.
        Spacer(Modifier.width(R1.space.s))
        Chevron(direction = ChevronDirection.Right, tint = R1.InkMuted)
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
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // In-house sun / moon line vector by state: above_horizon = sun
            // (warm), below_horizon = crescent (cool), the muted tint keeping
            // the night state quiet.
            val isUp = s.state == "above_horizon"
            Icon(
                imageVector = if (isUp) R1IconSet.Sun else R1IconSet.ClearNight,
                contentDescription = null,
                tint = if (isUp) R1.AccentWarm else R1.AccentCool,
                modifier = Modifier.size(30.dp),
            )
            Spacer(Modifier.width(R1.space.m))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "SUN", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                Text(
                    text = (if (isUp) "ABOVE HORIZON" else "BELOW HORIZON") +
                        (s.elevation?.let { " · ${"%.1f".format(java.util.Locale.US, it)}°" } ?: ""),
                    style = responsiveType(R1.body).copy(fontWeight = FontWeight.SemiBold),
                    color = R1.Ink,
                )
            }
        }
        // Next rise/set: relative time and HH:mm absolute. The
        // relative is the at-a-glance answer ('in 4h'); the absolute
        // helps with concrete planning ('alarm before sunrise').
        val locale = java.util.Locale.getDefault()
        val timeFmt = java.time.format.DateTimeFormatter.ofLocalizedTime(
            java.time.format.FormatStyle.SHORT,
        ).withLocale(locale)
        Row {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT RISE", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelativeTimeLabel(at = s.nextRising, color = R1.AccentWarm, style = responsiveType(R1.labelMicro))
                    s.nextRising?.let {
                        Spacer(Modifier.width(R1.space.s))
                        Text(
                            text = it.atZone(java.time.ZoneId.systemDefault()).format(timeFmt),
                            style = responsiveType(R1.labelMicro),
                            color = R1.InkSoft,
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "NEXT SET", style = responsiveType(R1.labelMicro), color = R1.InkMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RelativeTimeLabel(at = s.nextSetting, color = R1.AccentCool, style = responsiveType(R1.labelMicro))
                    s.nextSetting?.let {
                        Spacer(Modifier.width(R1.space.s))
                        Text(
                            text = it.atZone(java.time.ZoneId.systemDefault()).format(timeFmt),
                            style = responsiveType(R1.labelMicro),
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
                .heightIn(min = R1.MinTarget)
                .padding(start = R1.space.xs, end = R1.space.l, top = R1.space.xs, bottom = R1.space.xs),
        ) {
            // Chevron-back tile: only rendered when canGoBack is true.
            // On the kiosk 'Start on Dashboard' path the back stack is
            // empty, so the chevron would be inert; hiding it removes
            // the dead affordance and makes the CARDS / SETTINGS shortcuts
            // the obvious escape paths.
            if (canGoBack) {
                com.github.itskenny0.r1ha.ui.components.ChevronBack(onClick = onBack)
                Spacer(Modifier.width(R1.space.xs))
            } else {
                Spacer(Modifier.width(R1.space.l))
            }
            // 'TODAY · MON': abbreviated day-of-week alongside the
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
                style = responsiveType(R1.screenTitle),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
            )
            // Battery indicator: only when both 'hide statusbar' and
            // 'show battery when statusbar hidden' are on. Sits before
            // the action chips so charge level reads naturally
            // left-to-right past the title.
            if (showBatteryIndicator) {
                com.github.itskenny0.r1ha.ui.components.BatteryIndicator(onClick = onOpenDevice)
                Spacer(Modifier.width(R1.space.s))
            }
            // Assist: same affordance as on the card stack chrome, so the
            // action is consistent across surfaces. Sits before CARDS so it's
            // the closer-to-center 'talk to HA' tap target for thumb reach on
            // a wall-mounted R1. Uses the hand-drawn AssistMicGlyph (same
            // as the chrome) rather than the 🎤 emoji so the dashboard
            // doesn't switch to color-emoji rendering mid-row.
            Box(
                modifier = Modifier
                    .size(R1.MinTarget)
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = onOpenAssist, contentDescription = "Open Assist"),
                contentAlignment = Alignment.Center,
            ) {
                com.github.itskenny0.r1ha.ui.components.AssistMicGlyph(size = 16.dp)
            }
            Spacer(Modifier.width(R1.space.xs))
            // CARDS: opens the card stack. Most-frequent action from the
            // dashboard for kiosk users who occasionally want to control
            // something rather than just glance.
            R1Chip(
                text = "CARDS",
                variant = R1ChipVariant.Action,
                onClick = onOpenCardStack,
            )
            Spacer(Modifier.width(R1.space.s))
            // SETTINGS gear: wireframe drawn glyph (same as the
            // card-stack chrome) for consistency. Tap opens Settings.
            Box(
                modifier = Modifier
                    .size(R1.MinTarget)
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = onOpenSettings, contentDescription = "Open settings"),
                contentAlignment = Alignment.Center,
            ) {
                com.github.itskenny0.r1ha.ui.components.SettingsCogGlyph(size = 18.dp)
            }
        }
        // Hairline divider: matches R1TopBar's exact metric.
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
    entries: List<DashboardViewModel.LowBattery>,
    onOpenHistory: (entityId: String) -> Unit,
) {
    // Each row shows the battery sensor's friendly name (the raw entity_id read
    // as plumbing) with the in-house battery glyph and the percent. Each row is
    // a tap target that opens the entity's history view so the user can drill
    // into which battery is dying without scrolling Search for the entity_id.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusAmber.copy(alpha = 0.12f))
            .border(1.dp, R1.StatusAmber.copy(alpha = 0.4f), R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = R1IconSet.Battery,
                contentDescription = null,
                tint = R1.StatusAmber,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(R1.space.xs))
            Text(
                text = "${entries.size} BATTERIES LOW",
                style = responsiveType(R1.labelMicro).copy(fontWeight = FontWeight.SemiBold),
                color = R1.StatusAmber,
            )
        }
        for (entry in entries.take(5)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .r1Pressable(onClick = { onOpenHistory(entry.entityId) })
                    .heightIn(min = R1.MinTarget)
                    .padding(vertical = R1.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = entry.name,
                    style = responsiveType(R1.body),
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = "${entry.pct}%",
                    style = responsiveType(R1.body),
                    color = if (entry.pct < 10) R1.StatusRed else R1.StatusAmber,
                )
                // Drill-in affordance: each battery row opens its history view.
                Spacer(Modifier.width(R1.space.xs))
                Chevron(direction = ChevronDirection.Right, tint = R1.InkMuted)
            }
        }
        if (entries.size > 5) {
            Text(
                text = "and ${entries.size - 5} more…",
                style = responsiveType(R1.labelMicro),
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
    // action. The PAUSE/RESUME pill swaps semantically based on the
    // current state so the user always sees the OTHER option.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (label, color) = when (t.state) {
                "active" -> "RUNNING" to R1.AccentGreen
                "paused" -> "PAUSED" to R1.StatusAmber
                else -> t.state.uppercase() to R1.InkSoft
            }
            Text(text = label, style = responsiveType(R1.labelMicro), color = color)
            Spacer(Modifier.width(R1.space.m))
            Text(text = t.name, style = responsiveType(R1.bodyEmph), color = R1.Ink, modifier = Modifier.weight(1f), maxLines = 1)
            Spacer(Modifier.width(R1.space.s))
            // Paused timers freeze finishes_at at the pause moment, so a
            // RelativeTimeLabel would tick into the past and show
            // 'finished 5 min ago' even though the timer hasn't fired.
            // Instead, surface HA's `remaining` attribute (HH:MM:SS) as
            // a static label so the user sees the actual time left. The
            // same static fallback covers an active timer whose
            // finishes_at failed to parse, so a control without a
            // readable countdown still shows *something*.
            if ((t.state == "paused" || t.finishesAt == null) && !t.remaining.isNullOrBlank()) {
                Text(text = t.remaining, style = responsiveType(R1.labelMicro), color = color)
            } else {
                RelativeTimeLabel(at = t.finishesAt, color = color, style = responsiveType(R1.labelMicro))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
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
            .heightIn(min = R1.MinTarget)
            .padding(vertical = R1.space.s),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = responsiveType(R1.labelMicro), color = accent)
    }
}

@Composable
private fun Greeting() {
    // Time-of-day greeting and a date/time line. Drives its own 60-second
    // ticker so the time stays current even when the dashboard
    // auto-refresh is disabled (refreshIntervalSec == 0). Otherwise
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
    // Shared clock-format pipeline: AUTO resolves against the Android system
    // 12/24-hour setting (which itself defaults from the locale, so existing
    // installs keep their current rendering), and the Settings → Appearance →
    // Clock format choice can force either style.
    val use24h = com.github.itskenny0.r1ha.ui.components.rememberUse24HourClock()
    val timeLine = now.format(
        java.time.format.DateTimeFormatter.ofPattern(
            com.github.itskenny0.r1ha.ui.components.clockPattern(use24h),
            java.util.Locale.US,
        ),
    )
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xs, vertical = R1.space.xs)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(text = greeting, style = responsiveType(R1.sectionHeader), color = R1.AccentWarm, modifier = Modifier.weight(1f))
            Text(text = timeLine, style = responsiveType(R1.numeralM), color = R1.Ink)
        }
        Text(text = dateLine.uppercase(), style = responsiveType(R1.labelMicro), color = R1.InkSoft)
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
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            R1Chip(
                text = if (playing) "PLAYING" else media.state.uppercase(),
                variant = R1ChipVariant.Pill,
                tone = if (playing) R1.AccentGreen else R1.InkSoft,
            )
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = media.name,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
        }
        val titleLine = listOfNotNull(media.title, media.artist).joinToString(" · ")
        if (titleLine.isNotBlank()) {
            Text(text = titleLine, style = responsiveType(R1.labelMicro), color = R1.InkSoft, maxLines = 2)
        }
        // Transport row: prev/play-pause/next, each gated on the
        // player's supported_features so we never offer a control the
        // player will silently no-op. When the player advertises none of
        // them (e.g. a cast group reporting bare state) the row collapses.
        if (media.canPrev || media.canPlayPause || media.canNext) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                if (media.canPrev) {
                    TransportButton(
                        glyph = TransportGlyph.Previous,
                        onClick = onPrev,
                        modifier = Modifier.weight(1f),
                        contentDescription = "Previous track",
                    )
                }
                if (media.canPlayPause) {
                    TransportButton(
                        glyph = if (playing) TransportGlyph.Pause else TransportGlyph.Play,
                        onClick = onPlayPause,
                        modifier = Modifier.weight(1f),
                        accent = R1.AccentWarm,
                        contentDescription = if (playing) "Pause" else "Play",
                    )
                }
                if (media.canNext) {
                    TransportButton(
                        glyph = TransportGlyph.Next,
                        onClick = onNext,
                        modifier = Modifier.weight(1f),
                        contentDescription = "Next track",
                    )
                }
            }
        }
    }
}

/** Media transport icons drawn in-house (replacing the unicode arrows /
 *  bars), so the dashboard never switches to symbol-font / color-emoji
 *  rendering mid-row. */
private enum class TransportGlyph { Previous, Play, Pause, Next }

@Composable
private fun TransportButton(
    glyph: TransportGlyph,
    onClick: () -> Unit,
    modifier: Modifier,
    accent: androidx.compose.ui.graphics.Color = R1.InkSoft,
    contentDescription: String? = null,
) {
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick, contentDescription = contentDescription)
            .heightIn(min = R1.MinTarget)
            .padding(vertical = R1.space.s),
        contentAlignment = Alignment.Center,
    ) {
        TransportIcon(glyph = glyph, tint = accent, modifier = Modifier.size(18.dp))
    }
}

/** Draws a single transport glyph on a 24x24 canvas in the Mission Control
 *  line aesthetic: filled triangles for play/skip, two bars for pause. */
@Composable
private fun TransportIcon(
    glyph: TransportGlyph,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        fun tri(x0: Float, x1: Float, pointRight: Boolean): Path = Path().apply {
            val top = h * 0.22f
            val bot = h * 0.78f
            val midY = h * 0.5f
            if (pointRight) {
                moveTo(w * x0, top); lineTo(w * x1, midY); lineTo(w * x0, bot)
            } else {
                moveTo(w * x1, top); lineTo(w * x0, midY); lineTo(w * x1, bot)
            }
            close()
        }
        when (glyph) {
            TransportGlyph.Play -> drawPath(tri(0.28f, 0.74f, pointRight = true), color = tint)
            TransportGlyph.Pause -> {
                val barW = w * 0.14f
                val top = h * 0.24f
                val bot = h * 0.76f
                drawLine(tint, Offset(w * 0.38f, top), Offset(w * 0.38f, bot), strokeWidth = barW, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.62f, top), Offset(w * 0.62f, bot), strokeWidth = barW, cap = StrokeCap.Round)
            }
            TransportGlyph.Previous -> {
                drawPath(tri(0.20f, 0.50f, pointRight = false), color = tint)
                drawPath(tri(0.50f, 0.80f, pointRight = false), color = tint)
            }
            TransportGlyph.Next -> {
                drawPath(tri(0.20f, 0.50f, pointRight = true), color = tint)
                drawPath(tri(0.50f, 0.80f, pointRight = true), color = tint)
            }
        }
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
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "PEOPLE", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
            Spacer(Modifier.weight(1f))
            Text(text = "${p.homeCount} HOME", style = responsiveType(R1.labelMicro), color = R1.AccentGreen)
            Spacer(Modifier.width(R1.space.s))
            Text(text = "${p.awayCount} AWAY", style = responsiveType(R1.labelMicro), color = R1.StatusAmber)
            // Drill-in affordance: the card opens the full Who's Home surface.
            Spacer(Modifier.width(R1.space.xs))
            Chevron(direction = ChevronDirection.Right, tint = R1.InkMuted)
        }
        for ((name, state) in p.rows) {
            Row {
                Text(
                    text = name,
                    style = responsiveType(R1.body),
                    color = R1.Ink,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(R1.space.s))
                val color = when (state.lowercase()) {
                    "home" -> R1.AccentGreen
                    "not_home", "away" -> R1.StatusAmber
                    "unknown", "unavailable" -> R1.StatusRed
                    else -> R1.AccentCool
                }
                // HA reports "not_home" as the literal state for "away from any
                // known zone"; render it as the friendlier "AWAY" the rest of
                // HA uses rather than the raw enum value.
                val label = when (state.lowercase()) {
                    "not_home" -> "AWAY"
                    else -> state.uppercase()
                }
                Text(text = label, style = responsiveType(R1.labelMicro), color = color)
            }
        }
        if (p.total > p.rows.size) {
            Text(
                text = "and ${p.total - p.rows.size} more. Tap to see all",
                style = responsiveType(R1.labelMicro),
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
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (c.happeningNow) {
                R1Chip(text = "NOW", variant = R1ChipVariant.Pill, tone = R1.AccentGreen)
                Spacer(Modifier.width(R1.space.s))
            } else {
                Text(text = "NEXT", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                Spacer(Modifier.width(R1.space.s))
            }
            if (c.allDay) {
                R1Chip(text = "ALL-DAY", variant = R1ChipVariant.Pill, tone = R1.AccentCool)
                Spacer(Modifier.width(R1.space.s))
            }
            Text(
                text = c.calendarName.uppercase(),
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            RelativeTimeLabel(at = c.eventStart, color = R1.InkMuted, style = responsiveType(R1.labelMicro))
            // Drill-in affordance: the card opens the full Calendars surface.
            Spacer(Modifier.width(R1.space.xs))
            Chevron(direction = ChevronDirection.Right, tint = R1.InkMuted)
        }
        Text(
            text = c.eventTitle,
            style = responsiveType(R1.bodyEmph),
            color = R1.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
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
    // routes to the Energy summary: same data but with production,
    // top consumers, and today's kWh.
    if (totalPowerW >= 0) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = onPower)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // In-house power bolt instead of a bare label-only tile.
                Icon(
                    imageVector = R1IconSet.Power,
                    contentDescription = null,
                    tint = when {
                        totalPowerW > redW -> R1.StatusRed
                        totalPowerW > amberW -> R1.StatusAmber
                        else -> R1.AccentCool
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(R1.space.s))
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "DRAW", style = responsiveType(R1.labelMicro), color = R1.InkSoft)
                    Text(
                        // Format kilowatts once the draw reaches 1 kW so a
                        // four-digit watt figure doesn't blow out the tile;
                        // one decimal of kW is plenty of precision at a glance.
                        text = formatPower(totalPowerW),
                        style = responsiveType(R1.numeralXl),
                        color = when {
                            totalPowerW > redW -> R1.StatusRed
                            totalPowerW > amberW -> R1.StatusAmber
                            else -> R1.AccentCool
                        },
                    )
                }
                Text(
                    text = "sum of power sensors",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        // Lights-on count from a server-side Jinja count(): much
        // lighter than fetching every light entity. -1 sentinel
        // renders as '—' so the tile doesn't claim "0 on" while the
        // template is still rendering. Tap routes to the Scenes
        // screen for the master-action trio; long-press fires
        // ALL LIGHTS OFF directly from the dashboard without an
        // extra navigation hop. Ideal kiosk affordance for "you
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
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Text(text = label, style = responsiveType(R1.labelMicro), color = R1.InkSoft)
        Text(text = value, style = responsiveType(R1.numeralXl), color = accent)
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
            .heightIn(min = R1.MinTarget)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = n.title?.takeIf { it.isNotBlank() } ?: n.notificationId,
                    style = responsiveType(R1.bodyEmph),
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(R1.space.s))
                // 'Created at' relative timestamp: surfaces 'just now'
                // or '2 m' so the user can tell a fresh alert from a
                // long-standing one without leaving the dashboard.
                RelativeTimeLabel(
                    at = n.createdAt,
                    color = R1.InkMuted,
                    style = responsiveType(R1.labelMicro),
                )
            }
            Text(
                text = n.message,
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(R1.space.s))
        // Dismiss tile: separate tap target from the row's onClick so a dismiss
        // doesn't accidentally navigate to the Notifications surface (and vice
        // versa). The X is an in-house line glyph rather than the unicode ✕ so
        // the row doesn't switch to symbol-font rendering.
        Box(
            modifier = Modifier
                .size(R1.MinTarget)
                .clip(R1.ShapeS)
                .r1Pressable(onClick = onDismiss, contentDescription = "Dismiss notification"),
            contentAlignment = Alignment.Center,
        ) {
            DismissGlyph(tint = R1.InkSoft, modifier = Modifier.size(14.dp))
        }
    }
}

/** A small X (dismiss) drawn as two crossing strokes, matching the Mission
 *  Control hairline aesthetic; replaces the unicode ✕. */
@Composable
private fun DismissGlyph(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val pad = size.minDimension * 0.18f
        val sw = size.minDimension * 0.14f
        drawLine(
            tint,
            Offset(pad, pad),
            Offset(size.width - pad, size.height - pad),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
        drawLine(
            tint,
            Offset(size.width - pad, pad),
            Offset(pad, size.height - pad),
            strokeWidth = sw,
            cap = StrokeCap.Round,
        )
    }
}

/** Format a watt reading for the DRAW tile: bare watts under 1 kW, one-decimal
 *  kilowatts at or above 1000 W so a four-digit figure doesn't overflow. */
private fun formatPower(watts: Int): String =
    if (watts >= 1000) {
        "${"%.1f".format(java.util.Locale.US, watts / 1000.0)} kW"
    } else {
        "$watts W"
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
 * renders them in a plain vertical stack. The visible one fills full width.
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
            horizontalArrangement = Arrangement.spacedBy(R1.space.s),
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
