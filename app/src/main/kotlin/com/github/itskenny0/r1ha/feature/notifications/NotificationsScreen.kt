package com.github.itskenny0.r1ha.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1EmptyState
import com.github.itskenny0.r1ha.ui.components.R1ErrorState
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.SkeletonRow
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Notifications viewer: lists HA persistent_notification.* entries
 * with title, message and a DISMISS chip per row. Same conceptual
 * surface as HA's frontend bell icon: integration failures, firmware
 * updates available, "you should restart HA" prompts, automation-side
 * `persistent_notification.create` messages.
 *
 * Polling: refreshed once on screen entry; user pulls down or backs
 * out/in to re-fetch. We don't subscribe to a state-stream for these
 * because they're low-cardinality and short-lived; a fresh GET each
 * time is the lighter footprint.
 */
@Composable
fun NotificationsScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: NotificationsViewModel = viewModel(factory = NotificationsViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val dimens = rememberResponsiveDimens()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    // Auto-refresh: cadence comes from Settings. Integrations.
    // 'Notifications refresh'. 0 disables auto-refresh (pull-down only).
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.notificationsRefreshSec
    if (refreshSec > 0) {
        AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "NOTIFICATIONS", onBack = onBack)
        AdaptiveContent(modifier = Modifier.weight(1f)) {
        // Create affordance: a small inline form (title + message) that
        // fires persistent_notification.create. Always available, even on the
        // empty/all-clear state, so it doubles as a way to verify the dismiss
        // path end to end without waiting for a real integration to raise one.
        CreateNotificationForm(
            creating = ui.creating,
            gutter = dimens.screenGutter,
            onCreate = { title, message -> vm.create(title, message) },
        )
        // Bulk DISMISS ALL: only rendered when there's at least one
        // notification to dismiss. Two-stage confirm via the armed/commit
        // pattern (single tap arms, second tap within 3 s fires) so a
        // muscle-memory tap doesn't accidentally clear everything.
        if (ui.notifications.isNotEmpty()) {
            val armed = remember { mutableStateOf(false) }
            LaunchedEffect(armed.value) {
                if (armed.value) {
                    delay(3_000L)
                    armed.value = false
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = dimens.screenGutter, vertical = R1.space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${ui.notifications.size} active",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .defaultMinSize(minHeight = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = if (armed.value) 0.32f else 0.18f))
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(
                            onClick = {
                                if (armed.value) {
                                    vm.dismissAll()
                                    armed.value = false
                                } else {
                                    armed.value = true
                                }
                            },
                            contentDescription = NotificationsViewModel.dismissAllDescription(
                                ui.notifications.size,
                                armed.value,
                            ),
                        )
                        .padding(horizontal = R1.space.m, vertical = R1.space.s),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (armed.value) "TAP AGAIN" else "DISMISS ALL",
                        style = responsiveType(R1.labelMicro),
                        color = R1.StatusRed,
                    )
                }
            }
        }
        when {
            // Only show the skeleton on the first load, when there's literally
            // nothing else to render. Subsequent refreshes keep the existing list +
            // DISMISS ALL row visible and rely on the pull-to-refresh spinner instead,
            // so the user doesn't lose scroll position or bulk-action access during a
            // routine 30-second auto-refresh.
            ui.loading && ui.notifications.isEmpty() && ui.error == null -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = dimens.screenGutter, vertical = R1.space.s),
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                // Skeleton rows teach the eye where notifications will land
                // instead of leaving a void with a tiny centred spinner.
                // Three rows roughly cover the R1's portrait viewport.
                repeat(3) {
                    SkeletonRow()
                }
            }
            // Distinct from "all clear": the request itself failed.
            ui.error != null && ui.notifications.isEmpty() -> R1ErrorState(
                title = "COULDN'T LOAD NOTIFICATIONS",
                message = ui.error,
                onRetry = { vm.refresh() },
            )
            ui.notifications.isEmpty() -> R1EmptyState(
                title = "NO NOTIFICATIONS",
                body = "HA has no persistent notifications right now.",
            )
            else -> PullToRefreshBox(
                isRefreshing = ui.refreshing,
                onRefresh = { vm.refresh(indicate = true) },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        horizontal = dimens.screenGutter, vertical = R1.space.s,
                    ),
                    verticalArrangement = Arrangement.spacedBy(R1.space.xs),
                ) {
                    items(items = ui.notifications, key = { it.notificationId }) { n ->
                        NotificationRow(
                            notification = n,
                            pendingDismiss = n.notificationId in ui.pendingDismiss,
                            onDismiss = { vm.dismiss(n) },
                        )
                    }
                }
            }
        }
        } // AdaptiveContent
    }
}

/**
 * Collapsible compose affordance for `persistent_notification.create`:
 * renders a single NEW chip by default; tapping it expands an inline form
 * with an optional title field, a required message field, and a CREATE
 * button. Kept compact (no modal) so it sits naturally above the list on the
 * R1's portrait viewport. The form clears + collapses on a successful submit.
 */
@Composable
private fun CreateNotificationForm(
    creating: Boolean,
    gutter: androidx.compose.ui.unit.Dp,
    onCreate: (title: String, message: String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    // Collapse + clear once a create finishes (creating flips back to false
    // while the form is open and a message was entered).
    val wasCreating = remember { mutableStateOf(false) }
    LaunchedEffect(creating) {
        if (wasCreating.value && !creating) {
            open = false
            title = ""
            message = ""
        }
        wasCreating.value = creating
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = gutter, vertical = R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PERSISTENT NOTIFICATIONS",
                style = responsiveType(R1.labelMicro),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            R1Chip(
                text = if (open) "CLOSE" else "+ NEW",
                variant = R1ChipVariant.Action,
                selected = open,
                onClick = { open = !open },
                contentDescription = if (open) "Close create form" else "Create notification",
            )
        }
        if (open) {
            Spacer(Modifier.size(R1.space.xs))
            R1TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "Title: optional",
                monospace = false,
                enabled = !creating,
            )
            Spacer(Modifier.size(R1.space.xs))
            R1TextField(
                value = message,
                onValueChange = { message = it },
                placeholder = "Message",
                monospace = false,
                singleLine = false,
                minLines = 2,
                enabled = !creating,
            )
            Spacer(Modifier.size(R1.space.xs))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                val canCreate = !creating && message.isNotBlank()
                R1Chip(
                    text = if (creating) "CREATING…" else "CREATE",
                    variant = R1ChipVariant.Action,
                    selected = canCreate,
                    onClick = if (canCreate) {
                        { onCreate(title, message) }
                    } else null,
                    contentDescription = "Submit new notification",
                )
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: PersistentNotification,
    pendingDismiss: Boolean,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.title?.takeIf { it.isNotBlank() } ?: notification.notificationId,
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(R1.space.s))
            // Relative timestamp: same ticker as the rest of the app so
            // "2 m ago" updates without us having to invalidate manually.
            // HA's notification drawer always renders a time element; when
            // created_at is missing (some auto-generated notifications omit
            // it) fall back to a static label rather than the blank that
            // RelativeTimeLabel emits for a null instant, so the row never
            // reads as "no time at all".
            if (notification.createdAt != null) {
                RelativeTimeLabel(
                    at = notification.createdAt,
                    color = R1.InkMuted,
                    style = responsiveType(R1.labelMicro),
                )
            } else {
                Text(
                    text = "no date",
                    style = responsiveType(R1.labelMicro),
                    color = R1.InkMuted,
                )
            }
        }
        Spacer(Modifier.size(R1.space.xs))
        // HACS update lists and other "here are 14 components needing review" payloads
        // routinely exceed 6 lines. Collapse by default; tap to expand the full body.
        val expanded = remember(notification.notificationId) {
            mutableStateOf(false)
        }
        // HA renders the message as markdown (with line breaks). We have no
        // markdown renderer on this surface, so reduce the common inline
        // markdown to readable plain text: strip emphasis fences, render
        // [text](url) as "text", drop heading hashes / list bullets to a
        // dash; rather than showing literal `**`, `#` and link syntax.
        val plainMessage = remember(notification.message) {
            markdownToPlain(notification.message)
        }
        val collapsedLines = 6
        val needsExpand = plainMessage.lineSequence().count() > collapsedLines ||
            plainMessage.length > 280
        Text(
            text = plainMessage,
            style = responsiveType(R1.body),
            color = R1.InkSoft,
            maxLines = if (expanded.value) Int.MAX_VALUE else collapsedLines,
            modifier = if (needsExpand) {
                Modifier.r1Pressable(
                    onClick = { expanded.value = !expanded.value },
                    contentDescription = if (expanded.value) {
                        "Collapse message"
                    } else {
                        "Expand message"
                    },
                )
            } else Modifier,
        )
        if (needsExpand) {
            Text(
                text = if (expanded.value) "↑ COLLAPSE" else "↓ EXPAND",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier
                    .defaultMinSize(minHeight = R1.MinTarget)
                    .padding(top = R1.space.xxs)
                    .r1Pressable(
                        onClick = { expanded.value = !expanded.value },
                        contentDescription = if (expanded.value) {
                            "Collapse message"
                        } else {
                            "Expand message"
                        },
                    ),
            )
        }
        Spacer(Modifier.size(R1.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.notificationId,
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(R1.space.s))
            Box(
                modifier = Modifier
                    .defaultMinSize(minHeight = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .background(
                        if (pendingDismiss) R1.SurfaceMuted else R1.StatusRed.copy(alpha = 0.18f),
                    )
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(
                        onClick = { if (!pendingDismiss) onDismiss() },
                        contentDescription = if (pendingDismiss) {
                            "Dismissing notification"
                        } else {
                            "Dismiss notification ${notification.notificationId}"
                        },
                    )
                    .padding(horizontal = R1.space.m, vertical = R1.space.s),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (pendingDismiss) "DISMISSING…" else "DISMISS",
                    style = responsiveType(R1.labelMicro),
                    color = if (pendingDismiss) R1.InkMuted else R1.StatusRed,
                )
            }
        }
    }
}

/**
 * Reduce the common inline/block markdown HA emits in notification bodies to
 * readable plain text. This is deliberately small and conservative, not a real
 * markdown parser: this surface has no rich-text renderer, so the goal is only
 * to stop literal `**`, `#`, backticks and `[text](url)` link syntax from
 * showing through as noise. Anything it doesn't recognize passes through
 * unchanged. Pure + side-effect free so it can be memoized per message.
 */
internal fun markdownToPlain(raw: String): String {
    if (raw.isBlank()) return raw
    var s = raw
    // Images first: ![alt](url) -> alt (so the leading '!' doesn't survive the
    // plain link pass below).
    s = Regex("""!\[([^\]]*)]\((?:[^)]*)\)""").replace(s) { it.groupValues[1] }
    // Links: [label](url) -> label
    s = Regex("""\[([^\]]+)]\((?:[^)]*)\)""").replace(s) { it.groupValues[1] }
    // Bold / italic / strikethrough fences: **x**, __x__, *x*, _x_, ~~x~~ -> x
    s = Regex("""\*\*([^*]+)\*\*""").replace(s) { it.groupValues[1] }
    s = Regex("""__([^_]+)__""").replace(s) { it.groupValues[1] }
    s = Regex("""\*([^*\n]+)\*""").replace(s) { it.groupValues[1] }
    s = Regex("""(?<![A-Za-z0-9])_([^_\n]+)_(?![A-Za-z0-9])""").replace(s) { it.groupValues[1] }
    s = Regex("""~~([^~]+)~~""").replace(s) { it.groupValues[1] }
    // Inline code `x` -> x
    s = Regex("""`([^`]+)`""").replace(s) { it.groupValues[1] }
    // Per-line block syntax: heading hashes and list bullets.
    s = s.lineSequence().joinToString("\n") { line ->
        var l = line
        // Leading heading markers: "## Title" -> "Title".
        l = l.replaceFirst(Regex("""^\s{0,3}#{1,6}\s+"""), "")
        // Unordered list markers "* ", "- ", "+ " -> "- " (uniform bullet).
        l = l.replaceFirst(Regex("""^(\s*)[*+\-]\s+"""), "$1- ")
        l
    }
    return s.trim()
}
