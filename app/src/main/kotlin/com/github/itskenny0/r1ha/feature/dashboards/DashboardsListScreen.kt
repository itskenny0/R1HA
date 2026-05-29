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
) {
    val vm: DashboardsViewModel = viewModel(
        factory = DashboardsViewModel.factory(haRepository, overrideStore),
    )
    val state by vm.state.collectAsState()
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
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { Hint() }
                    itemsIndexed(state.dashboards, key = { _, d -> d.urlPath ?: "_default_" }) { _, d ->
                        DashboardRow(
                            dashboard = d,
                            config = state.configs[d.urlPath ?: DashboardsViewModel.DEFAULT_KEY],
                            onPickView = { viewPath -> onOpenView(d.urlPath, viewPath) },
                            onReload = { vm.loadConfig(d.urlPath, force = true) },
                        )
                    }
                    item { Spacer(Modifier.height(20.dp)) }
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
            style = R1.body,
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
                    ViewRow(view = v, onClick = { onPickView(v.path) })
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
private fun ViewRow(view: LovelaceView, onClick: () -> Unit) {
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
            Text(text = "Loading dashboards…", style = R1.body, color = R1.InkSoft)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "No dashboards", style = R1.screenTitle, color = R1.Ink, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                text = message,
                style = R1.body,
                color = R1.InkSoft,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
    }
}
