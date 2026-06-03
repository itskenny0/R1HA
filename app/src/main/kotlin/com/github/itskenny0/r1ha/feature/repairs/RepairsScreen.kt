package com.github.itskenny0.r1ha.feature.repairs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.RepairIssue
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
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
    // Hoisted so the feed keeps its scroll position across refreshes and so the
    // physical scroll wheel can drive it once the wheel input is plumbed through
    // (see the SHARED CHANGE REQUEST in the surface report).
    val listState = rememberLazyListState()
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
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = R1.space.s, vertical = R1.space.xs)
                        .semantics { contentDescription = "Refresh repairs" },
                    contentAlignment = Alignment.Center,
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
                            .padding(R1.space.xl),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "COULDN'T LOAD REPAIRS",
                            style = R1.labelMicro,
                            color = R1.StatusAmber,
                        )
                        Spacer(Modifier.height(R1.space.xs))
                        Text(
                            text = ui.error ?: "",
                            style = R1.body,
                            color = R1.InkSoft,
                        )
                        Spacer(Modifier.height(R1.space.m))
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
                            .padding(R1.space.xl),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = "NO OPEN REPAIRS",
                            style = R1.labelMicro,
                            color = R1.AccentCool,
                        )
                        Spacer(Modifier.height(R1.space.xs))
                        Text(
                            text = "Nothing for HA's integrations to flag.",
                            style = R1.body,
                            color = R1.InkMuted,
                        )
                    }
                }
                else -> PullToRefreshBox(
                    isRefreshing = ui.loading,
                    onRefresh = { vm.refresh() },
                    modifier = Modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = R1.space.m, vertical = R1.space.s),
                        verticalArrangement = Arrangement.spacedBy(R1.space.s),
                    ) {
                        item(key = "summary") {
                            // Compose a severity breakdown line: gives the user
                            // a one-glance read on how alarming the list is
                            // before they scroll through individual rows.
                            val b = RepairsLogic.breakdown(ui.issues)
                            val summary = RepairsLogic.summaryLine(b)
                            val accent = when {
                                b.critical > 0 -> R1.StatusRed
                                b.errors > 0 -> R1.StatusAmber
                                b.warnings > 0 -> R1.AccentWarm
                                else -> R1.AccentCool
                            }
                            Text(
                                text = summary,
                                style = R1.labelMicro,
                                color = accent,
                                modifier = Modifier.padding(vertical = R1.space.xs),
                            )
                        }
                        items(ui.issues, key = { it.domain + "/" + it.issueId }) { issue ->
                            RepairRow(
                                issue = issue,
                                repairsUrl = ui.repairsUrl,
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
    repairsUrl: String?,
    onToggleIgnore: () -> Unit,
) {
    val context = LocalContext.current
    val tone = when (issue.severity.lowercase()) {
        "critical" -> R1.StatusRed
        "error" -> R1.StatusAmber
        "warning" -> R1.AccentWarm
        else -> R1.InkMuted
    }
    // Ignored rows mute their accent so the active issues read first; the live
    // severity colour returns the moment the user restores the issue.
    val accent = if (issue.ignored) R1.Hairline else tone
    Row(
        modifier = Modifier
            .fillMaxWidth()
            // IntrinsicSize.Min lets the leading severity stripe stretch to the
            // measured height of the text column beside it; without it a
            // fillMaxHeight child in a wrap-content Row collapses to zero.
            .height(IntrinsicSize.Min)
            .clip(R1.ShapeS)
            .background(if (issue.ignored) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS),
    ) {
        // Severity stripe: an accent rail down the leading edge so the list
        // can be triaged by colour at a glance without reading each badge.
        Box(
            modifier = Modifier
                .width(R1.space.xxs)
                .fillMaxHeight()
                .background(accent),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
            verticalArrangement = Arrangement.spacedBy(R1.space.xs),
        ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Severity glyph: an alert siren for critical/error/warning, the
            // neutral generic mark for anything HA didn't flag, tinted to the
            // row's severity tone so the list triages by colour + shape.
            Icon(
                imageVector = RepairsLogic.severityIcon(issue.severity),
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(R1.space.xs))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(tone.copy(alpha = 0.18f))
                    .border(1.dp, tone.copy(alpha = 0.5f), R1.ShapeS)
                    .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
            ) {
                Text(
                    text = issue.severity.uppercase(),
                    style = R1.labelMicro,
                    color = tone,
                )
            }
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = issue.domain,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (issue.ignored) {
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = "IGNORED",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
            // Fixable issues route through HA's multi-step fix flow; the rest
            // are informational and can only be read or ignored. Labelling
            // both makes the distinction explicit rather than implied.
            Spacer(Modifier.width(R1.space.s))
            Text(
                text = if (issue.isFixable) "FIXABLE" else "INFO",
                style = R1.labelMicro,
                color = if (issue.isFixable) R1.AccentCool else R1.InkMuted,
            )
        }
        Text(
            text = RepairsLogic.humanizeTitle(issue.translationKey, issue.issueId),
            style = R1.bodyEmph,
            color = if (issue.ignored) R1.InkMuted else R1.Ink,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!issue.description.isNullOrBlank()) {
            Text(
                text = issue.description,
                style = R1.labelMicro,
                color = R1.InkSoft,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // "Breaks in <ver>" chip: a distinct, scannable callout for deprecation
        // repairs that name the HA release where the flagged behaviour stops
        // working, kept separate from the body so the deadline reads at a glance.
        if (!issue.breaksInHaVersion.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.StatusAmber.copy(alpha = 0.15f))
                    .border(1.dp, R1.StatusAmber.copy(alpha = 0.5f), R1.ShapeS)
                    .padding(horizontal = R1.space.s, vertical = R1.space.xxs),
            ) {
                Text(
                    text = "Breaks in ${issue.breaksInHaVersion}",
                    style = R1.labelMicro,
                    color = R1.StatusAmber,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // LEARN MORE: HA's per-issue documentation link, opened in the system
        // browser. Rendered as a tappable affordance rather than dumped into the
        // body text (which is where the URL used to be mis-mapped).
        if (!issue.learnMoreUrl.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { openUrl(context, issue.learnMoreUrl) })
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs)
                    .semantics {
                        contentDescription = "Learn more about ${issue.domain} issue"
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "LEARN MORE ↗",
                    style = R1.labelMicro,
                    color = R1.AccentCool,
                )
            }
        }
        val createdAt = RepairsLogic.parseCreatedAt(issue.createdAt)
        if (createdAt != null) {
            RelativeTimeLabel(
                at = createdAt,
                color = R1.InkMuted,
                style = R1.labelMicro,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The full multi-step fix flow lives in HA's own frontend. When we
            // know HA's web-UI URL we deep-link straight into the repairs
            // dashboard; otherwise we still surface the entry point with copy
            // telling the user where to finish the fix.
            if (issue.isFixable && !issue.ignored) {
                Box(
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.AccentCool.copy(alpha = 0.5f), R1.ShapeS)
                        .r1Pressable(onClick = { openFixFlow(context, repairsUrl) })
                        .padding(horizontal = R1.space.m, vertical = R1.space.s)
                        .semantics {
                            contentDescription = "Fix ${issue.domain} issue in Home Assistant"
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (repairsUrl != null) "FIX IN HA ↗" else "FIX IN HOME ASSISTANT",
                        style = R1.labelMicro,
                        color = R1.AccentCool,
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            Box(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onToggleIgnore)
                    .padding(horizontal = R1.space.m, vertical = R1.space.s)
                    .semantics {
                        contentDescription = if (issue.ignored) {
                            "Restore ${issue.domain} issue"
                        } else {
                            "Ignore ${issue.domain} issue"
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (issue.ignored) "RESTORE" else "IGNORE",
                    style = R1.labelMicro,
                    color = if (issue.ignored) R1.AccentWarm else R1.StatusAmber,
                )
            }
        }
        } // inner Column
    }
}

/**
 * Launch HA's repairs fix flow. With a resolved web-UI URL we hand off to the
 * system browser at the repairs dashboard; without one we can't deep-link, so
 * we tell the user to open Home Assistant and finish there.
 */
private fun openFixFlow(context: android.content.Context, repairsUrl: String?) {
    if (repairsUrl == null) {
        Toaster.show("Open Home Assistant to run this fix")
        return
    }
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(repairsUrl),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toaster.error("No browser to open Home Assistant")
    }
}

/** Open an arbitrary issue URL (the repair's learn-more link) in the system browser. */
private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url),
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }.onFailure {
        Toaster.error("No browser to open this link")
    }
}
