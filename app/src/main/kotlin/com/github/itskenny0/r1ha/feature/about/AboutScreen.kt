package com.github.itskenny0.r1ha.feature.about

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

@Composable
fun AboutScreen(
    haRepository: HaRepository,
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onOpenDevMenu: () -> Unit = {},
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val connection by haRepository.connection.collectAsStateWithLifecycle()
    val appSettings by settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "ABOUT", onBack = onBack)

        AdaptiveContent(modifier = Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

                // ── App ────────────────────────────────────────────────────────────────
                item { Section("APP") }
                item { InfoRow("Version", BuildConfig.VERSION_NAME, mono = true) }
                item { InfoRow("Build", BuildConfig.GIT_SHA, mono = true) }
                // Surface the product flavour so the user (and anyone helping them
                // troubleshoot) knows which build they're running. Distinct
                // distribution paths produce subtly different behaviour: the github
                // flavour has the in-app self-updater; the fdroid flavour gets
                // update notifications from the F-Droid client instead.
                item {
                    InfoRow(
                        "Distribution",
                        if (BuildConfig.IS_FDROID_BUILD) "F-Droid" else "GitHub",
                        mono = true,
                    )
                }
                item {
                    LinkRow(
                        label = "Source code",
                        url = BuildConfig.SOURCE_URL,
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(BuildConfig.SOURCE_URL))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                    )
                }
                // Self-updater is omitted on the F-Droid flavour — F-Droid users get
                // update notifications from the F-Droid client and shouldn't see a
                // duplicate in-app affordance. The github flavour keeps it so direct-
                // install users (downloading the APK from GitHub Releases) have a
                // discoverable update path. Gated at composition time so the gradle
                // R8 pass drops the entire UpdaterRow + AppUpdater wiring from the
                // F-Droid APK rather than just hiding it at runtime.
                if (!BuildConfig.IS_FDROID_BUILD) {
                    item { UpdaterRow() }
                } else {
                    // F-Droid builds intentionally strip the self-updater (the
                    // REQUEST_INSTALL_PACKAGES permission would trip the F-Droid
                    // anti-feature scanner). Surface a one-line hint so users know
                    // where to get the next release rather than wondering why the
                    // GitHub UpdaterRow they read about online isn't here.
                    item { FdroidUpdateHint() }
                }
                // File-a-bug link — drops the user straight into the GitHub issue
                // tracker pre-filled with the app version. Lowers the friction for
                // crash reports + UX feedback; without it, users have to type the
                // URL into a desktop browser.
                item {
                    val flavour = if (BuildConfig.IS_FDROID_BUILD) "F-Droid" else "GitHub"
                    val bugUrl = "${BuildConfig.SOURCE_URL}/issues/new?body=" +
                        java.net.URLEncoder.encode(
                            "App: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                                "Build: ${BuildConfig.GIT_SHA}\n" +
                                "Distribution: $flavour\n" +
                                "Android: API ${Build.VERSION.SDK_INT}\n" +
                                "Device: ${Build.MANUFACTURER} ${Build.MODEL}\n\n" +
                                "(describe what happened. If it's a crash, paste the LAST CRASH from the dev menu here.)",
                            "UTF-8",
                        )
                    LinkRow(
                        label = "File a bug",
                        url = bugUrl,
                        // Bare tracker path; the actual URL has a ~300-char
                        // URL-encoded body pre-fill that dominates the row.
                        displayUrl = "${BuildConfig.SOURCE_URL}/issues/new",
                        onOpen = {
                            runCatching {
                                context.startActivity(
                                    Intent(Intent.ACTION_VIEW, Uri.parse(bugUrl))
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        },
                    )
                }

                item { SectionDivider() }

                // ── Connection ─────────────────────────────────────────────────────────
                item { Section("CONNECTION") }
                item { InfoRow("Server", appSettings.server?.url ?: "(not connected)", mono = true) }
                item {
                    InfoRow(
                        label = "WebSocket",
                        value = describeConnection(connection),
                    )
                }
                item {
                    // 'Last event' diagnostic — surfaces the heartbeat the repository tracks
                    // for its REST-fallback poller. When the user's WS is half-broken (the
                    // connection upgrades cleanly but state_changed events get dropped by a
                    // misconfigured reverse proxy), 'WebSocket' above still reads Connected,
                    // but cards update slowly. The seconds-since-last-event number tells
                    // them which case they're in.
                    LastEventRow(haRepository)
                }
                item { InfoRow("Favourites", appSettings.favorites.size.toString(), mono = true) }
                item { EntitiesDiagnosticRow(haRepository) }

                item { SectionDivider() }

                // ── Device ─────────────────────────────────────────────────────────────
                item { Section("DEVICE") }
                item { InfoRow("Manufacturer", Build.MANUFACTURER) }
                item { InfoRow("Model", Build.MODEL) }
                item { InfoRow("Android", "API ${Build.VERSION.SDK_INT} (${Build.VERSION.RELEASE})") }

                item { SectionDivider() }

                // ── License ────────────────────────────────────────────────────────────
                item { Section("LICENSE") }
                item {
                    Text(
                        text = "Released into the public domain via The Unlicense. " +
                            "Copy, modify, redistribute. Commercial or not, by any means.",
                        style = R1.body,
                        color = R1.InkSoft,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    )
                }
                item { SectionDivider() }
                // ── Dev menu ───────────────────────────────────────────────────────────
                item { Section("DEVELOPER") }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .r1Pressable(onClick = onOpenDevMenu)
                            .padding(horizontal = 22.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dev menu", style = R1.bodyEmph, color = R1.Ink)
                            Text(
                                text = "Advanced tunables, behaviour flags, in-app log viewer.",
                                style = R1.body,
                                color = R1.InkMuted,
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(text = "→", style = R1.bodyEmph, color = R1.InkSoft)
                    }
                }
                item { Spacer(Modifier.height(48.dp)) }
            }
        } // AdaptiveContent
    }
}

/**
 * 'Last event' diagnostic — surfaces the [HaRepository.lastEventAtMillis] heartbeat the
 * repository uses to decide whether the REST fallback poller should fire. Renders as a
 * standard row with a 1 s ticker so the seconds-since count stays current as the user
 * watches the screen, and the value is colour-coded by freshness:
 *   - < 30 s ago: muted (everything healthy)
 *   - 30 s – 2 min: amber (heartbeat poller is engaging)
 *   - > 2 min:    red (REST fallback is failing too — server unreachable)
 *
 * 'Just now' / 'Never' / 'N s ago' / 'N min ago' read better than a raw epoch number
 * for the user who's trying to figure out whether the WS is healthy.
 */
@Composable
private fun LastEventRow(haRepository: HaRepository) {
    val lastAt by haRepository.lastEventAtMillis.collectAsStateWithLifecycle()
    // Tick every second so the elapsed seconds count keeps refreshing while the screen
    // is open. Avoids subscribing to a wall-clock StateFlow we don't have; a 1 s delay
    // loop is cheap and stops as soon as the composable leaves composition.
    val now = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis())
    }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now.longValue = System.currentTimeMillis()
            kotlinx.coroutines.delay(1_000L)
        }
    }
    val elapsedSec = if (lastAt <= 0L) -1L else ((now.longValue - lastAt) / 1000L).coerceAtLeast(0L)
    val text = when {
        lastAt <= 0L -> "Never"
        elapsedSec < 2L -> "Just now"
        elapsedSec < 60L -> "$elapsedSec s ago"
        elapsedSec < 3600L -> "${elapsedSec / 60} min ago"
        else -> "${elapsedSec / 3600} h ago"
    }
    val tint = when {
        elapsedSec < 0L -> R1.InkMuted
        elapsedSec < 30L -> R1.InkSoft
        elapsedSec < 120L -> R1.StatusAmber
        else -> R1.StatusRed
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Last event", style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.weight(1f))
        Text(text = text, style = R1.body, color = tint)
    }
}

/**
 * Diagnostic row — fetches /api/states once on tap, groups the returned entities by
 * domain, and renders both per-domain counts and the underlying entity_id list so the
 * user can see exactly what HA shipped back. Designed for the 'where are my X
 * entities?' case where logcat isn't reachable: each domain row expands inline to
 * show every entity_id in that bucket. If `media_player` shows 0 here when the user
 * expects it, the issue is either upstream (HA permissions, entity-level
 * visibility) or in the decoder. If it shows a non-zero count, the issue is
 * downstream and we can debug from there.
 */
@Composable
private fun EntitiesDiagnosticRow(haRepository: HaRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val byDomain = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Map<String, List<String>>?>(null)
    }
    // Raw response prefix counts — populated by the secondary 'PROBE RAW' button.
    // Shows what HA actually returned BEFORE our supported-domain filter and per-row
    // decoder run. Resolves the 'is HA sending media_player.* at all?' question.
    val rawByPrefix = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<Map<String, Int>?>(null)
    }
    val loading = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(false)
    }
    val expandedDomain = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val error = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Entities", style = R1.bodyEmph, color = R1.Ink)
            Spacer(Modifier.weight(1f))
            val pillText = when {
                loading.value -> "FETCHING…"
                error.value != null -> "ERROR"
                byDomain.value != null -> "${byDomain.value!!.values.sumOf { it.size }} TOTAL"
                else -> "TAP TO PROBE HA"
            }
            Box(
                modifier = Modifier
                    .background(R1.SurfaceMuted, shape = R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (loading.value) return@r1Pressable
                        loading.value = true
                        error.value = null
                        scope.launch {
                            haRepository.listAllEntities().fold(
                                onSuccess = { list ->
                                    byDomain.value = list
                                        .groupBy { it.id.domain.prefix }
                                        .mapValues { (_, l) -> l.map { it.id.value } }
                                        .toSortedMap()
                                },
                                onFailure = { error.value = it.message ?: "fetch failed" },
                            )
                            loading.value = false
                        }
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = pillText,
                    style = R1.labelMicro,
                    color = when {
                        error.value != null -> R1.StatusRed
                        byDomain.value != null -> R1.AccentWarm
                        else -> R1.InkSoft
                    },
                )
            }
        }
        // Secondary 'PROBE RAW' button — hits the same /api/states endpoint but
        // groups the response purely by entity_id prefix, including domains the app
        // doesn't support and would otherwise drop. Use this when a domain shows
        // zero in the decoded list above and you want to know whether HA even sent
        // any rows for that prefix.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Raw prefixes (every domain HA returned, including unsupported)",
                style = R1.body,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .background(R1.SurfaceMuted, shape = R1.ShapeS)
                    .r1Pressable(onClick = {
                        if (loading.value) return@r1Pressable
                        loading.value = true
                        scope.launch {
                            haRepository.listAllEntitiesRawPrefixCounts().fold(
                                onSuccess = { rawByPrefix.value = it },
                                onFailure = { error.value = it.message ?: "fetch failed" },
                            )
                            loading.value = false
                        }
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = if (rawByPrefix.value == null) "PROBE RAW" else "${rawByPrefix.value!!.values.sum()} RAW",
                    style = R1.labelMicro,
                    color = if (rawByPrefix.value != null) R1.AccentWarm else R1.InkSoft,
                )
            }
        }
        // Raw prefix list — shows every prefix HA returned, marking unsupported ones
        // (those our app filters out). If media_player is here with a non-zero count
        // but missing from the decoded list above, our decoder is dropping them; if
        // it's missing from BOTH, HA isn't returning them to this auth token.
        rawByPrefix.value?.let { raw ->
            raw.forEach { (prefix, count) ->
                val supported = com.github.itskenny0.r1ha.core.ha.Domain.isSupportedPrefix(prefix)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prefix,
                        style = R1.body.copy(fontFamily = FontFamily.Monospace),
                        color = if (supported) R1.Ink else R1.InkMuted,
                    )
                    if (!supported) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "(filtered)",
                            style = R1.labelMicro,
                            color = R1.StatusAmber,
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        text = count.toString(),
                        style = R1.body.copy(fontFamily = FontFamily.Monospace),
                        color = if (supported) R1.InkSoft else R1.InkMuted,
                    )
                }
            }
        }
        // Per-domain count list. Tapping a row expands to show the entity_ids in
        // that domain inline — useful when the user wants to verify a specific
        // entity_id reached the app.
        byDomain.value?.let { domains ->
            domains.forEach { (domain, ids) ->
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .r1Pressable(onClick = {
                                expandedDomain.value = if (expandedDomain.value == domain) null else domain
                            })
                            .padding(horizontal = 22.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (expandedDomain.value == domain) "▼ " else "▶ ",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                        )
                        Text(
                            text = domain,
                            style = R1.body.copy(fontFamily = FontFamily.Monospace),
                            color = R1.Ink,
                        )
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = ids.size.toString(),
                            style = R1.body.copy(fontFamily = FontFamily.Monospace),
                            color = R1.InkSoft,
                        )
                    }
                    if (expandedDomain.value == domain) {
                        ids.forEach { eid ->
                            Text(
                                text = eid,
                                style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                                color = R1.InkMuted,
                                modifier = Modifier.padding(start = 44.dp, end = 22.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
            }
        }
        error.value?.let { msg ->
            Text(
                text = msg,
                style = R1.labelMicro,
                color = R1.StatusRed,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp),
            )
        }
    }
}

/**
 * Self-update row — talks to the GitHub Releases API, compares the latest release's
 * derived versionCode against [BuildConfig.VERSION_CODE], and surfaces a download +
 * install flow when there's something newer. State is fully local (no VM needed) —
 * the row is self-contained and feature-flag-friendly. Status pill changes by
 * state: IDLE → CHECKING → UP TO DATE | UPDATE AVAILABLE | DOWNLOADING (%) | ERROR.
 *
 * Downloads land in cacheDir/updates/ via [com.github.itskenny0.r1ha.core.update.AppUpdater],
 * which then fires ACTION_VIEW so Android's package installer prompts the user.
 * No silent installs.
 */
/**
 * F-Droid-flavour-only hint: tells the user where to get updates since the
 * self-updater isn't compiled into this APK. Renders as a muted one-liner
 * under the About section, matching the visual weight of other AboutScreen
 * footer rows.
 */
@Composable
private fun FdroidUpdateHint() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Text(text = "UPDATES", style = R1.labelMicro, color = R1.InkSoft)
        androidx.compose.foundation.layout.Spacer(Modifier.height(2.dp))
        Text(
            text = "F-Droid distribution: install updates via your F-Droid client. GitHub Releases also publishes the same APK.",
            style = R1.body,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun UpdaterRow() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val state = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<UpdaterState>(UpdaterState.Idle)
    }
    val updater = androidx.compose.runtime.remember {
        com.github.itskenny0.r1ha.core.update.AppUpdater(
            http = okhttp3.OkHttpClient(),
        )
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Updates", style = R1.bodyEmph, color = R1.Ink)
            Spacer(Modifier.weight(1f))
            val pillText = when (val s = state.value) {
                UpdaterState.Idle -> "TAP TO CHECK"
                UpdaterState.Checking -> "CHECKING…"
                is UpdaterState.UpToDate -> "UP TO DATE"
                is UpdaterState.Available -> "v${s.info.versionName} AVAILABLE"
                is UpdaterState.Downloading -> "DOWNLOADING ${(s.fraction * 100).toInt()}%"
                // Truncate so a 200-char IOException doesn't overflow the chip;
                // expanding the row would shift the layout. Tap-to-retry still
                // works because the click handler treats Error like Idle.
                is UpdaterState.Error -> "ERROR · ${s.message.take(40)}"
            }
            val pillColor = when (state.value) {
                is UpdaterState.Available, is UpdaterState.Downloading -> R1.AccentWarm
                is UpdaterState.Error -> R1.StatusRed
                else -> R1.InkSoft
            }
            val downloadJob = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .background(R1.SurfaceMuted, shape = R1.ShapeS)
                        .r1Pressable(onClick = {
                            // Tap dispatches based on current state: idle/up-to-date/
                            // error → re-check; available → start download.
                            when (val s = state.value) {
                                is UpdaterState.Available -> {
                                    state.value = UpdaterState.Downloading(s.info, 0f)
                                    downloadJob.value = scope.launch {
                                        runCatching {
                                            updater.downloadAndInstall(context, s.info) { read, total ->
                                                val frac = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                                                state.value = UpdaterState.Downloading(s.info, frac)
                                            }
                                            // Hand-off complete: Android's installer
                                            // takes over and the user lands back here
                                            // after the new build starts.
                                            state.value = UpdaterState.Available(s.info)
                                        }.onFailure {
                                            if (it is kotlinx.coroutines.CancellationException) {
                                                state.value = UpdaterState.Available(s.info)
                                            } else {
                                                state.value = UpdaterState.Error(it.message ?: "download failed")
                                            }
                                        }
                                        downloadJob.value = null
                                    }
                                }
                                else -> {
                                    state.value = UpdaterState.Checking
                                    scope.launch {
                                        state.value = when (val r = updater.checkForUpdate()) {
                                            is com.github.itskenny0.r1ha.core.update.AppUpdater.CheckResult.Available -> UpdaterState.Available(r.info)
                                            is com.github.itskenny0.r1ha.core.update.AppUpdater.CheckResult.UpToDate -> UpdaterState.UpToDate
                                            is com.github.itskenny0.r1ha.core.update.AppUpdater.CheckResult.Failed -> UpdaterState.Error(r.message)
                                        }
                                    }
                                }
                            }
                        })
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(pillText, style = R1.labelMicro, color = pillColor)
                }
                // CANCEL chip while a download is in flight. Aborts the underlying
                // OkHttp stream + the Compose runtime's resume callbacks so a
                // slow / failed download can be backed out without restarting
                // the app.
                if (state.value is UpdaterState.Downloading) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(R1.StatusRed.copy(alpha = 0.18f), shape = R1.ShapeS)
                            .r1Pressable(onClick = {
                                downloadJob.value?.cancel()
                                downloadJob.value = null
                            })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(text = "CANCEL", style = R1.labelMicro, color = R1.StatusRed)
                    }
                }
            }
        }
        // Error / release-notes detail. Available + Error reveal additional text
        // under the row. Notes are truncated to ~6 lines so the about screen
        // doesn't grow unreasonably for a long changelog.
        when (val s = state.value) {
            is UpdaterState.Available -> if (s.info.notes.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = s.info.notes,
                    style = R1.body,
                    color = R1.InkSoft,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            is UpdaterState.Error -> {
                Spacer(Modifier.height(4.dp))
                Text(s.message, style = R1.labelMicro, color = R1.StatusRed)
            }
            else -> Unit
        }
    }
}

/** Local state machine for the updater row's tap flow. */
private sealed interface UpdaterState {
    data object Idle : UpdaterState
    data object Checking : UpdaterState
    data object UpToDate : UpdaterState
    data class Available(val info: com.github.itskenny0.r1ha.core.update.UpdateInfo) : UpdaterState
    data class Downloading(
        val info: com.github.itskenny0.r1ha.core.update.UpdateInfo,
        val fraction: Float,
    ) : UpdaterState
    data class Error(val message: String) : UpdaterState
}

@Composable
private fun Section(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = R1.sectionHeader, color = R1.AccentWarm)
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.width(16.dp))
        Text(
            text = value,
            style = if (mono) R1.body.copy(fontFamily = FontFamily.Monospace) else R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun LinkRow(
    label: String,
    url: String,
    onOpen: () -> Unit,
    /** Optional shorter preview rendered in place of the full [url]. Use when
     *  the actual URL is long (deep-linked tracker form, signed media URL,
     *  etc.) and rendering it raw would dominate the row. The click still
     *  opens [url]. */
    displayUrl: String = url,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onOpen)
            .padding(horizontal = 22.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
        Spacer(Modifier.height(2.dp))
        Text(
            text = displayUrl,
            // Underline so the URL reads as interactive even without a chevron.
            style = R1.body.copy(
                fontFamily = FontFamily.Monospace,
                textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
            ),
            color = R1.AccentWarm,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

private fun describeConnection(state: ConnectionState): String = when (state) {
    ConnectionState.Idle -> "Idle"
    ConnectionState.Connecting -> "Connecting…"
    ConnectionState.Authenticating -> "Authenticating…"
    is ConnectionState.Connected ->
        "Connected${state.haVersion?.let { " · HA $it" } ?: ""}"
    is ConnectionState.Disconnected -> when (val c = state.cause) {
        ConnectionState.Cause.Network -> "Disconnected · network"
        ConnectionState.Cause.ServerClosed -> "Disconnected · server closed"
        is ConnectionState.Cause.Error -> "Disconnected · ${c.throwable.message ?: "error"}"
    }
    is ConnectionState.AuthLost -> "Auth lost · ${state.reason ?: "tokens invalid"}"
}
