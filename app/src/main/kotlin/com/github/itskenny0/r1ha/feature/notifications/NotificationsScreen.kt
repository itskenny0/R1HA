package com.github.itskenny0.r1ha.feature.notifications

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Notifications viewer — lists HA persistent_notification.* entries
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
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    // Auto-refresh — cadence comes from Settings → Integrations →
    // 'Notifications refresh'. 0 disables auto-refresh (pull-down only).
    val appSettings by settings.settings.collectAsState(
        initial = com.github.itskenny0.r1ha.core.prefs.AppSettings(),
    )
    val refreshSec = appSettings.integrations.notificationsRefreshSec
    if (refreshSec > 0) {
        com.github.itskenny0.r1ha.ui.components.AutoRefresh(refreshSec * 1000L) { vm.refresh() }
    } else {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refresh() }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "NOTIFICATIONS", onBack = onBack)
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
        // Create affordance — a small inline form (title + message) that
        // fires persistent_notification.create. Always available, even on the
        // empty/all-clear state, so it doubles as a way to verify the dismiss
        // path end to end without waiting for a real integration to raise one.
        CreateNotificationForm(
            creating = ui.creating,
            onCreate = { title, message -> vm.create(title, message) },
        )
        // Bulk DISMISS ALL — only rendered when there's at least one
        // notification to dismiss. Two-stage confirm via the armed/commit
        // pattern (single tap arms, second tap within 3 s fires) so a
        // muscle-memory tap doesn't accidentally clear everything.
        if (ui.notifications.isNotEmpty()) {
            val armed = remember { mutableStateOf(false) }
            LaunchedEffect(armed.value) {
                if (armed.value) {
                    kotlinx.coroutines.delay(3_000L)
                    armed.value = false
                }
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${ui.notifications.size} active",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.StatusRed.copy(alpha = if (armed.value) 0.32f else 0.18f))
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = {
                            if (armed.value) {
                                vm.dismissAll()
                                armed.value = false
                            } else {
                                armed.value = true
                            }
                        })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = if (armed.value) "TAP AGAIN" else "DISMISS ALL",
                        style = R1.labelMicro,
                        color = R1.StatusRed,
                    )
                }
            }
        }
        when {
            // Only show the centred spinner on the first load, when there's literally
            // nothing else to render. Subsequent refreshes keep the existing list +
            // DISMISS ALL row visible and rely on the pull-to-refresh spinner instead,
            // so the user doesn't lose scroll position or bulk-action access during a
            // routine 30-second auto-refresh.
            ui.loading && ui.notifications.isEmpty() && ui.error == null -> androidx.compose.foundation.layout.Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Skeleton rows teach the eye where notifications will land
                // instead of leaving a void with a tiny centred spinner.
                // Three rows roughly cover the R1's portrait viewport.
                repeat(3) {
                    com.github.itskenny0.r1ha.ui.components.SkeletonRow()
                }
            }
            ui.error != null && ui.notifications.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                // Distinct from "all clear" — the request itself failed.
                Text(
                    text = "Notifications load failed: ${ui.error}",
                    style = R1.body,
                    color = R1.StatusRed,
                )
            }
            ui.notifications.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "No persistent notifications in HA. All clear.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            else -> androidx.compose.material3.pulltorefresh.PullToRefreshBox(
                isRefreshing = ui.loading,
                onRefresh = { vm.refresh() },
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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
 * Collapsible compose affordance for `persistent_notification.create`.
 * Renders a single NEW chip by default; tapping it expands an inline form
 * with an optional title field, a required message field, and a CREATE
 * button. Kept compact (no modal) so it sits naturally above the list on the
 * R1's portrait viewport. The form clears + collapses on a successful submit.
 */
@Composable
private fun CreateNotificationForm(
    creating: Boolean,
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
            .padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "PERSISTENT NOTIFICATIONS",
                style = R1.labelMicro,
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
            Spacer(Modifier.size(6.dp))
            R1TextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "Title (optional)",
                monospace = false,
                enabled = !creating,
            )
            Spacer(Modifier.size(6.dp))
            R1TextField(
                value = message,
                onValueChange = { message = it },
                placeholder = "Message",
                monospace = false,
                singleLine = false,
                minLines = 2,
                enabled = !creating,
            )
            Spacer(Modifier.size(6.dp))
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
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.title?.takeIf { it.isNotBlank() } ?: notification.notificationId,
                style = R1.body,
                color = R1.Ink,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            Spacer(Modifier.width(8.dp))
            // Relative timestamp — same ticker as the rest of the app so
            // "2 m ago" updates without us having to invalidate manually.
            RelativeTimeLabel(
                at = notification.createdAt,
                color = R1.InkMuted,
                style = R1.labelMicro,
            )
        }
        Spacer(Modifier.size(4.dp))
        // HACS update lists and other "here are 14 components needing review" payloads
        // routinely exceed 6 lines. Collapse by default; tap to expand the full body.
        val expanded = androidx.compose.runtime.remember(notification.notificationId) {
            androidx.compose.runtime.mutableStateOf(false)
        }
        val collapsedLines = 6
        val needsExpand = notification.message.lineSequence().count() > collapsedLines ||
            notification.message.length > 280
        Text(
            text = notification.message,
            style = R1.body,
            color = R1.InkSoft,
            maxLines = if (expanded.value) Int.MAX_VALUE else collapsedLines,
            modifier = if (needsExpand) {
                Modifier.r1Pressable(onClick = { expanded.value = !expanded.value })
            } else Modifier,
        )
        if (needsExpand) {
            Text(
                text = if (expanded.value) "↑ COLLAPSE" else "↓ EXPAND",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .r1Pressable(onClick = { expanded.value = !expanded.value }),
            )
        }
        Spacer(Modifier.size(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = notification.notificationId,
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(
                        if (pendingDismiss) R1.SurfaceMuted else R1.StatusRed.copy(alpha = 0.18f),
                    )
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = { if (!pendingDismiss) onDismiss() })
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(
                    text = if (pendingDismiss) "DISMISSING…" else "DISMISS",
                    style = R1.labelMicro,
                    color = if (pendingDismiss) R1.InkMuted else R1.StatusRed,
                )
            }
        }
    }
}
