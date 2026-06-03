package com.github.itskenny0.r1ha.feature.dashboards

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConfig
import com.github.itskenny0.r1ha.core.lovelace.LovelaceDashboard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceOverrideStore
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * Top-level dashboards-list surface. Opened from Settings → Appearance
 * → Dashboards. Shows:
 *  - every dashboard HA exposes via `lovelace/dashboards/list`, with the
 *    default dashboard pinned to slot 0.
 *  - tapping a dashboard fetches + caches its config and reveals the
 *    list of views inside that dashboard.
 *  - tapping a view navigates the host into [DashboardViewScreen] for
 *    full-screen rendering.
 *
 * Hidden on R1 small-screen tier WITHOUT EXCEPTION. the host caller
 * applies the breakpoint gate (the Settings entry row hides on R1).
 *
 * Visual idiom: industrial-chrome card surfaces, hairline dividers, no
 * bottom shadow elevation. Each row gives at-a-glance metadata
 * (urlPath, view count, mode flag) so the user can tell two dashboards
 * with similar titles apart.
 */
@Composable
fun DashboardsListScreen(
    haRepository: HaRepository,
    overrideStore: LovelaceOverrideStore,
    onOpenView: (dashboardUrlPath: String?, viewPath: String) -> Unit,
    onBack: () -> Unit,
    /** Settings repository, used to read + mutate the pinned-dashboard list so each
     *  view row can carry a pin / unpin toggle. Null (the isolation-render default)
     *  hides the per-row pin affordance entirely. */
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository? = null,
) {
    val vm: DashboardsViewModel = viewModel(
        factory = DashboardsViewModel.factory(haRepository, overrideStore),
    )
    val state by vm.state.collectAsState()
    // Live pinned-dashboard routes so each view row reflects its pinned state and a
    // toggle updates immediately. Null settings (isolation render) leaves the set
    // empty and the pin affordance hidden.
    val appSettings = settings?.settings?.collectAsState(initial = null)?.value
    val pinnedRoutes: Set<String> = appSettings?.navPanel?.pinnedDashboards
        ?.map { it.route }?.toSet() ?: emptySet()
    val pinScope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) { vm.loadDashboards() }
    // Auto-fetch the config for each listed dashboard so the view counts
    // appear without a per-row tap. Cheap on small installs; bounded at
    // 16 fetches to avoid spamming a server with 50 dashboards.
    LaunchedEffect(state.dashboards) {
        state.dashboards.take(16).forEach { d -> vm.loadConfig(d.urlPath) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "Dashboards", onBack = onBack)
        when {
            state.isLoadingList && state.dashboards.isEmpty() -> {
                LoadingState()
            }
            state.dashboards.isEmpty() -> {
                EmptyState(message = state.listError ?: "No dashboards published yet.")
            }
            else -> {
                // Centre + width-cap the list column on roomy tiers so the
                // rows read as a centred column instead of one wall-wide line
                // on a 13in panel; horizontal gutter steps up per tier. On
                // R1 / compact maxContentWidth is Unspecified (widthIn no-op)
                // so the list fills the narrow panel exactly as before.
                val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
                val capWidth = if (dimens.capsContentWidth) {
                    Modifier.widthIn(max = dimens.maxContentWidth)
                } else {
                    Modifier
                }
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter,
                ) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(capWidth)
                        .padding(horizontal = dimens.screenGutter, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Hint() }
                    itemsIndexed(state.dashboards, key = { _, d -> d.urlPath ?: "_default_" }) { _, d ->
                        DashboardRow(
                            dashboard = d,
                            config = state.configs[d.urlPath ?: DashboardsViewModel.DEFAULT_KEY],
                            onPickView = { viewPath -> onOpenView(d.urlPath, viewPath) },
                            onReload = { vm.loadConfig(d.urlPath, force = true) },
                            // Pin affordance: only when settings is wired. routeFor builds the
                            // concrete dashboards-view route the pin list keys on; the toggle
                            // pins with the view's title + icon and unpins by route.
                            showPin = settings != null,
                            isPinned = { viewPath ->
                                com.github.itskenny0.r1ha.nav.Routes
                                    .dashboardsViewRoute(d.urlPath, viewPath) in pinnedRoutes
                            },
                            onTogglePin = { view ->
                                val s = settings ?: return@DashboardRow
                                val route = com.github.itskenny0.r1ha.nav.Routes
                                    .dashboardsViewRoute(d.urlPath, view.path)
                                val title = view.title?.takeUnless { it.isBlank() } ?: view.path
                                pinScope.launch {
                                    if (route in pinnedRoutes) s.unpinDashboard(route)
                                    else s.pinDashboard(route, title, view.icon)
                                }
                            },
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
                }
                }
            }
        }
    }
}

@Composable
private fun Hint() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            text = "Imports HA's Lovelace configuration read-only. Local edits stay on this device; HA's setup is never modified.",
            style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun DashboardRow(
    dashboard: LovelaceDashboard,
    config: LovelaceConfig?,
    onPickView: (String) -> Unit,
    onReload: () -> Unit,
    /** When true each view row sprouts a pin / unpin toggle. */
    showPin: Boolean = false,
    /** Is the view at [viewPath] within this dashboard currently pinned? */
    isPinned: (viewPath: String) -> Boolean = { false },
    /** Toggle the pinned state of [view] (pins with its title + icon, or unpins). */
    onTogglePin: (LovelaceView) -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.Surface)
            .border(1.dp, R1.Hairline, R1.ShapeM)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = dashboard.title,
                    style = R1.titleCard,
                    color = R1.Ink,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = buildString {
                        append(dashboard.urlPath?.let { "/$it" } ?: "(default)")
                        config?.let {
                            append("  ·  ")
                            append("${it.views.size} view${if (it.views.size == 1) "" else "s"}")
                        }
                        dashboard.mode?.let { append("  ·  mode: $it") }
                    },
                    style = R1.numeralS,
                    color = R1.InkMuted,
                )
            }
            Text(
                text = "RELOAD",
                style = R1.labelMicro,
                color = R1.InkSoft,
                modifier = Modifier
                    .clip(R1.ShapeRound)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeRound)
                    .r1Pressable(onClick = onReload)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        if (config != null && config.views.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                config.views.forEach { v ->
                    ViewRow(
                        view = v,
                        onClick = { onPickView(v.path) },
                        showPin = showPin,
                        pinned = isPinned(v.path),
                        onTogglePin = { onTogglePin(v) },
                    )
                }
            }
        } else if (config != null && config.isStrategyGenerated) {
            // Strategy-generated dashboard: no concrete views to list. Offer a
            // drill-in that lands on the view screen's strategy fallback (which
            // routes to the full Lovelace WebView). The sentinel path won't
            // match any real view, so the screen shows the fallback panel.
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Generated by a Home Assistant strategy.",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeM)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.AccentWarm.copy(alpha = 0.5f), R1.ShapeM)
                    .r1Pressable(onClick = { onPickView("_strategy_") })
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(text = "OPEN IN LOVELACE", style = R1.labelMicro, color = R1.AccentWarm)
            }
        } else if (config != null) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "No views in this dashboard.",
                style = R1.body,
                color = R1.InkMuted,
            )
        }
    }
}

@Composable
private fun ViewRow(
    view: LovelaceView,
    onClick: () -> Unit,
    showPin: Boolean = false,
    pinned: Boolean = false,
    onTogglePin: () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeM)
            .background(R1.SurfaceMuted)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = view.title?.takeUnless { it.isBlank() } ?: view.path,
                style = R1.bodyEmph,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = "${view.cards.size} card${if (view.cards.size == 1) "" else "s"}" +
                    (if (view.panel) "  ·  panel" else ""),
                style = R1.numeralS,
                color = R1.InkMuted,
            )
        }
        // Pin / unpin this view. Drawn as its own tap target so a pin tap doesn't
        // also fire the row's OPEN. Reuses the shared star affordance for parity
        // with the dashboard view's top-bar toggle.
        if (showPin) {
            com.github.itskenny0.r1ha.ui.components.PinToggle(
                pinned = pinned,
                onClick = onTogglePin,
            )
            Spacer(Modifier.size(4.dp))
        }
        Text(text = "OPEN", style = R1.labelMicro, color = R1.AccentWarm)
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = R1.AccentWarm, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            Spacer(Modifier.height(10.dp))
            Text(text = "Loading dashboards…", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body), color = R1.InkSoft)
        }
    }
}

@Composable
private fun EmptyState(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.widthIn(max = 420.dp),
        ) {
            Text(text = "No dashboards", style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.screenTitle), color = R1.Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = com.github.itskenny0.r1ha.core.theme.responsiveType(R1.body),
                color = R1.InkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
