package com.github.itskenny0.r1ha.feature.repairs

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.RepairIssue
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Repairs / Issues feed — surfaces every HA repair issue (the same set HA's frontend
 * shows under Settings > System > Repairs) with severity-coloured badges, ignore /
 * restore actions, and a "(server offline)" banner when the WS is down. The
 * full multi-step fix flow lives in HA's own UI; this surface is read-and-
 * ignore plus a chip to launch HA's web UI in the system browser for the
 * actual fix.
 */
@Composable
fun RepairsScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: RepairsViewModel = viewModel(factory = RepairsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "REPAIRS",
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(
                        text = if (ui.loading) "…" else "REFRESH",
                        style = R1.labelMicro,
                        color = R1.InkSoft,
                    )
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            when {
                ui.loading && ui.issues.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                            color = R1.AccentWarm,
                        )
                    }
                }
                ui.error != null && ui.issues.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "COULDN'T LOAD REPAIRS",
                            style = R1.labelMicro,
                            color = R1.StatusAmber,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = ui.error ?: "",
                            style = R1.body,
                            color = R1.InkSoft,
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = "Repairs only flows over the live WebSocket. If your link is down or the server is offline, retry once it reconnects.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                    }
                }
                ui.issues.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "NO OPEN REPAIRS",
                            style = R1.labelMicro,
                            color = R1.AccentCool,
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Nothing for HA's integrations to flag.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            val active = ui.issues.filterNot { it.ignored }
                            val critical = active.count { it.severity.equals("critical", ignoreCase = true) }
                            val errors = active.count { it.severity.equals("error", ignoreCase = true) }
                            val warnings = active.size - critical - errors
                            val ignored = ui.issues.size - active.size
                            // Compose a severity breakdown line — gives the user
                            // a one-glance read on how alarming the list is
                            // before they scroll through individual rows.
                            val parts = buildList {
                                if (critical > 0) add("$critical CRITICAL")
                                if (errors > 0) add("$errors ERROR${if (errors == 1) "" else "S"}")
                                if (warnings > 0) add("$warnings WARNING${if (warnings == 1) "" else "S"}")
                                if (ignored > 0) add("$ignored IGNORED")
                            }
                            val summary = if (parts.isEmpty()) "${ui.issues.size} ITEMS"
                            else parts.joinToString(" · ")
                            val accent = when {
                                critical > 0 -> R1.StatusRed
                                errors > 0 -> R1.StatusAmber
                                warnings > 0 -> R1.AccentWarm
                                else -> R1.AccentCool
                            }
                            Text(
                                text = summary,
                                style = R1.labelMicro,
                                color = accent,
                                modifier = Modifier.padding(vertical = 4.dp),
                            )
                        }
                        items(ui.issues, key = { it.domain + "/" + it.issueId }) { issue ->
                            RepairRow(
                                issue = issue,
                                onToggleIgnore = { vm.ignore(issue) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepairRow(
    issue: RepairIssue,
    onToggleIgnore: () -> Unit,
) {
    val tone = when (issue.severity.lowercase()) {
        "critical" -> R1.StatusRed
        "error" -> R1.StatusAmber
        "warning" -> R1.AccentWarm
        else -> R1.InkMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(if (issue.ignored) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(tone.copy(alpha = 0.18f))
                    .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text(
                    text = issue.severity.uppercase(),
                    style = R1.labelMicro,
                    color = tone,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = issue.domain,
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
            if (issue.ignored) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "IGNORED",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            if (issue.isFixable) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "FIXABLE",
                    style = R1.labelMicro,
                    color = R1.AccentCool,
                )
            }
        }
        Text(
            text = issue.translationKey ?: issue.issueId,
            style = R1.bodyEmph,
            color = if (issue.ignored) R1.InkMuted else R1.Ink,
        )
        if (!issue.description.isNullOrBlank()) {
            Text(
                text = issue.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onToggleIgnore)
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (issue.ignored) "RESTORE" else "IGNORE",
                    style = R1.labelMicro,
                    color = if (issue.ignored) R1.AccentWarm else R1.StatusAmber,
                )
            }
        }
    }
}
