package com.github.itskenny0.r1ha.feature.updates

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import com.github.itskenny0.r1ha.ui.i18n.Text
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
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Button
import com.github.itskenny0.r1ha.ui.components.R1ButtonVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Lists every `update.*` entity HA exposes — HA Core, Supervisor, OS,
 * add-ons, integration firmware — with installed/latest version, a
 * release-notes peek, and an INSTALL action. Mirrors HA's frontend
 * "Settings → System → Updates" pane.
 *
 * Layout: a header summary chip ("3 UPDATES AVAILABLE") + REFRESH, then a
 * sorted LazyColumn (in-progress first, available next, up-to-date last).
 * Each row shows a category badge (CORE / ADD-ON / INTEGRATION), the
 * version diff, the release summary line, and the per-row INSTALL /
 * details affordances. Tap a row to open a details dialog with the full
 * summary, a link to the release notes URL, an optional "Back up first"
 * toggle (when the entity reports backup support), and a SKIP button.
 *
 * No long-press affordance: the install confirmation is intentionally an
 * explicit tap so a stray brush can't kick off a 20-minute HA Core
 * upgrade. Long-press is reserved for the future per-card "ignore from
 * this list" behaviour — once we have a setting for it.
 */
@Composable
fun UpdatesScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val vm: UpdatesViewModel = viewModel(factory = UpdatesViewModel.factory(haRepository))
    val ui by vm.ui.collectAsState()
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    LaunchedEffect(Unit) { vm.refresh() }

    // Entity currently being inspected in the details dialog. Holds the full
    // Entry so the dialog can render the latest version + release notes even
    // if the underlying list refreshes underneath it.
    var detailFor by remember { mutableStateOf<UpdatesViewModel.Entry?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(
            title = "UPDATES",
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
        com.github.itskenny0.r1ha.ui.layout.AdaptiveContent(modifier = Modifier.weight(1f)) {
            // Summary band — gives an at-a-glance answer to "is there anything
            // I need to install right now?" before the user even scrolls. Hidden
            // during the initial load so the band doesn't briefly read "0
            // updates" before the fetch returns.
            if (!ui.loading || ui.all.isNotEmpty()) {
                SummaryBand(ui)
            }
            when {
                ui.loading && ui.all.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = R1.AccentWarm,
                    )
                }
                ui.error != null && ui.all.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Updates load failed: ${ui.error}",
                        style = R1.body,
                        color = R1.StatusRed,
                    )
                }
                ui.all.isEmpty() -> Box(
                    modifier = Modifier.fillMaxSize().padding(22.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "No update entities. HA exposes update.* entries " +
                            "once the Supervisor / add-ons / integrations are " +
                            "installed and configured to report versions.",
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
                        items(items = ui.ordered, key = { it.id.value }) { entry ->
                            UpdateRow(
                                entry = entry,
                                onOpen = { detailFor = entry },
                                onInstall = { vm.install(entry, backup = false) },
                            )
                        }
                    }
                }
            }
        }
    }
    detailFor?.let { entry ->
        UpdateDetailDialog(
            entry = entry,
            onDismiss = { detailFor = null },
            onInstall = { backup ->
                detailFor = null
                vm.install(entry, backup = backup)
            },
            onSkip = {
                detailFor = null
                vm.skip(entry)
            },
        )
    }
}

@Composable
private fun SummaryBand(ui: UpdatesViewModel.UiState) {
    val color = when {
        ui.inProgressCount > 0 -> R1.AccentWarm
        ui.availableCount > 0 -> R1.AccentCool
        else -> R1.AccentGreen
    }
    val text = when {
        ui.inProgressCount > 0 ->
            "${ui.inProgressCount} INSTALLING · ${ui.availableCount} AVAILABLE"
        ui.availableCount > 0 -> "${ui.availableCount} UPDATES AVAILABLE"
        else -> "EVERYTHING UP TO DATE"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(R1.ShapeS)
            .background(color.copy(alpha = 0.12f))
            .border(1.dp, color.copy(alpha = 0.5f), R1.ShapeS)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text = text, style = R1.labelMicro, color = color)
    }
}

@Composable
private fun UpdateRow(
    entry: UpdatesViewModel.Entry,
    onOpen: () -> Unit,
    onInstall: () -> Unit,
) {
    // Status colour: amber while installing, cool blue when an update is
    // queued, neutral grey when up to date. Drives both the border and the
    // status chip so the row reads as a unit.
    val statusColor = when {
        entry.inProgress -> R1.AccentWarm
        entry.updateAvailable -> R1.AccentCool
        else -> R1.InkMuted
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, statusColor.copy(alpha = 0.4f), R1.ShapeS)
            .r1Pressable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Bucket badge — at-a-glance category. CORE entries are coloured
            // to read as "system-level" (warm); ADD-ON is cool-blue to read
            // as third-party but managed; INTEGRATION is muted because it's
            // the noisiest category and a dim chip keeps the row legible.
            val (badgeColor, badgeBg) = when (entry.bucket) {
                UpdatesViewModel.Bucket.CORE -> R1.AccentWarm to R1.AccentWarm.copy(alpha = 0.18f)
                UpdatesViewModel.Bucket.ADDON -> R1.AccentCool to R1.AccentCool.copy(alpha = 0.18f)
                UpdatesViewModel.Bucket.INTEGRATION -> R1.InkSoft to R1.SurfaceMuted
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(badgeBg)
                    .border(1.dp, badgeColor.copy(alpha = 0.5f), R1.ShapeS)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(text = entry.bucket.label, style = R1.labelMicro, color = badgeColor)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = entry.title,
                style = R1.body,
                color = R1.Ink,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            if (entry.autoUpdate) {
                Box(
                    modifier = Modifier
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(text = "AUTO", style = R1.labelMicro, color = R1.InkMuted)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        // Version diff line. When latest matches installed we say UP TO DATE
        // explicitly rather than rendering "1.2.3 → 1.2.3" which is noisy.
        val versionLine = when {
            entry.inProgress -> entry.progressPercent?.let { "INSTALLING · $it %" }
                ?: "INSTALLING…"
            entry.updateAvailable && entry.installedVersion != null && entry.latestVersion != null ->
                "${entry.installedVersion} → ${entry.latestVersion}"
            entry.updateAvailable && entry.latestVersion != null ->
                "→ ${entry.latestVersion}"
            entry.installedVersion != null -> "${entry.installedVersion} · UP TO DATE"
            else -> "UP TO DATE"
        }
        Text(text = versionLine, style = R1.labelMicro, color = statusColor)
        // Release-summary peek — truncated to a single line; tapping the row
        // opens the dialog with the full text.
        if (!entry.releaseSummary.isNullOrBlank()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = entry.releaseSummary,
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
        if (entry.updateAvailable && !entry.inProgress) {
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                R1Button(
                    text = "INSTALL",
                    onClick = onInstall,
                    accent = R1.AccentCool,
                )
                Spacer(Modifier.weight(1f))
                if (entry.hasReleaseNotes) {
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = onOpen)
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(text = "NOTES", style = R1.labelMicro, color = R1.InkSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun UpdateDetailDialog(
    entry: UpdatesViewModel.Entry,
    onDismiss: () -> Unit,
    onInstall: (backup: Boolean) -> Unit,
    onSkip: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    // Backup toggle only meaningful when the underlying integration reports
    // SUPPORT_BACKUP. Defaulted to true for CORE entries (HA always wants a
    // pre-install snapshot before Core / Supervisor / OS rollouts) and false
    // elsewhere so the user doesn't think every add-on install triggers a
    // disk-thrashing backup.
    var backup by remember(entry.id.value) {
        mutableStateOf(
            entry.supportsBackup && entry.bucket == UpdatesViewModel.Bucket.CORE,
        )
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = R1.Bg,
        title = {
            Column {
                Text(text = entry.title, style = R1.sectionHeader, color = R1.Ink)
                Text(
                    text = entry.id.value,
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
        },
        text = {
            Column {
                // Version line — same priority as the row, with the in-progress
                // tease swapped for the actual percentage when known.
                val versionLine = when {
                    entry.inProgress -> entry.progressPercent?.let { "Installing · $it %" }
                        ?: "Installing…"
                    entry.updateAvailable && entry.installedVersion != null && entry.latestVersion != null ->
                        "${entry.installedVersion} → ${entry.latestVersion}"
                    entry.updateAvailable && entry.latestVersion != null ->
                        "Next: ${entry.latestVersion}"
                    entry.installedVersion != null -> "Installed: ${entry.installedVersion}"
                    else -> "No version reported"
                }
                Text(text = versionLine, style = R1.body, color = R1.Ink)
                if (!entry.releaseSummary.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(text = "RELEASE NOTES", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = entry.releaseSummary,
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                if (!entry.releaseUrl.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = {
                                runCatching {
                                    context.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse(entry.releaseUrl),
                                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                                    )
                                }.onFailure {
                                    com.github.itskenny0.r1ha.core.util.Toaster.error(
                                        "No browser to open release notes",
                                    )
                                }
                            })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            text = "OPEN FULL CHANGELOG ↗",
                            style = R1.labelMicro,
                            color = R1.AccentCool,
                        )
                    }
                }
                if (entry.supportsBackup) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 36.dp, height = 18.dp)
                                .clip(R1.ShapeS)
                                .background(
                                    if (backup) R1.AccentWarm.copy(alpha = 0.3f) else R1.SurfaceMuted,
                                )
                                .border(
                                    1.dp,
                                    if (backup) R1.AccentWarm else R1.Hairline,
                                    R1.ShapeS,
                                )
                                .r1Pressable(onClick = { backup = !backup }),
                            contentAlignment = if (backup) Alignment.CenterEnd else Alignment.CenterStart,
                        ) {
                            Box(
                                modifier = Modifier
                                    .padding(2.dp)
                                    .size(14.dp)
                                    .clip(R1.ShapeS)
                                    .background(if (backup) R1.AccentWarm else R1.InkMuted),
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Back up before installing",
                                style = R1.body,
                                color = R1.Ink,
                            )
                            Text(
                                text = "Recommended for HA Core / Supervisor / OS updates",
                                style = R1.labelMicro,
                                color = R1.InkMuted,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (entry.updateAvailable && !entry.inProgress) {
                R1Button(
                    text = "INSTALL",
                    onClick = { onInstall(backup) },
                    accent = R1.AccentCool,
                )
            } else {
                R1Button(text = "CLOSE", onClick = onDismiss)
            }
        },
        dismissButton = {
            // SKIP is only meaningful for available updates the user wants to
            // dismiss without installing. Hidden for up-to-date / in-progress
            // entries so the dialog footer stays uncluttered.
            if (entry.updateAvailable && !entry.inProgress) {
                R1Button(
                    text = "SKIP",
                    onClick = onSkip,
                    variant = R1ButtonVariant.Outlined,
                )
            } else {
                R1Button(
                    text = "CANCEL",
                    onClick = onDismiss,
                    variant = R1ButtonVariant.Outlined,
                )
            }
        },
    )
}
