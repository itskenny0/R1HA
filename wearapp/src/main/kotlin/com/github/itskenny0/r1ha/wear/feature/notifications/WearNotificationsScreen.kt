package com.github.itskenny0.r1ha.wear.feature.notifications

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.ha.PersistentNotification
import com.github.itskenny0.r1ha.feature.notifications.NotificationsViewModel

/**
 * Wear OS Notifications screen.
 *
 * Shows every HA `persistent_notification.*` with its title and message.
 * Per-row DISMISS chip calls the HA service and removes the notification
 * optimistically. A DISMISS ALL chip at the top clears everything at once.
 */
@Composable
fun WearNotificationsScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: NotificationsViewModel = viewModel(
        factory = NotificationsViewModel.factory(haRepository),
    )
    val ui by vm.ui.collectAsState()
    val listState = rememberScalingLazyListState()

    LaunchedEffect(Unit) { vm.refresh() }

    Scaffold(timeText = { TimeText() }) {
        when {
            ui.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }

            ui.error != null -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "Error: ${ui.error}",
                        style = MaterialTheme.typography.body2,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }

            else -> {
                ScalingLazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "🔔 Alerts",
                                style = MaterialTheme.typography.title3,
                            )
                            if (ui.notifications.isNotEmpty()) {
                                Chip(
                                    label = {
                                        Text(
                                            "ALL ✕",
                                            style = MaterialTheme.typography.caption2,
                                        )
                                    },
                                    onClick = { vm.dismissAll() },
                                    colors = ChipDefaults.outlinedChipColors(
                                        contentColor = MaterialTheme.colors.error,
                                    ),
                                    modifier = Modifier.padding(start = 4.dp),
                                )
                            }
                        }
                    }

                    if (ui.notifications.isEmpty()) {
                        item {
                            Text(
                                text = "No alerts.",
                                style = MaterialTheme.typography.body2,
                                textAlign = TextAlign.Center,
                                color = MaterialTheme.colors.onBackground.copy(alpha = 0.6f),
                                modifier = Modifier.padding(16.dp),
                            )
                        }
                    } else {
                        items(ui.notifications) { notif ->
                            NotificationRow(
                                notification = notif,
                                isPending = notif.notificationId in ui.pendingDismiss,
                                onDismiss = { vm.dismiss(notif) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NotificationRow(
    notification: PersistentNotification,
    isPending: Boolean,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 3.dp),
    ) {
        notification.title?.let { title ->
            Text(
                text = title,
                style = MaterialTheme.typography.caption1,
                fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = notification.message,
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onBackground.copy(alpha = 0.75f),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Chip(
            label = {
                Text(
                    text = if (isPending) "Dismissing…" else "✕ Dismiss",
                    style = MaterialTheme.typography.caption2,
                )
            },
            onClick = onDismiss,
            enabled = !isPending,
            colors = ChipDefaults.outlinedChipColors(
                contentColor = MaterialTheme.colors.error,
            ),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
