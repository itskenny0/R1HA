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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.Image
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.ui.components.Chevron
import com.github.itskenny0.r1ha.ui.components.ChevronDirection
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.ha.ConnectionState
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.CardPeekMode
import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType
import com.github.itskenny0.r1ha.feature.cardstack.DeckLayout
import com.github.itskenny0.r1ha.feature.cardstack.PEEK_MIN_SHORTEST_SIDE_PX
import com.github.itskenny0.r1ha.feature.cardstack.effectiveDeckLayout
import com.github.itskenny0.r1ha.feature.cardstack.effectivePeek
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.components.rememberWindowTier
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
    val whatsNewOpen = remember { androidx.compose.runtime.mutableStateOf(false) }

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
                // Re-open the one-shot upgrade overlay on demand, for anyone who
                // dismissed it too fast (or wants to see what the last update did).
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = R1.MinTarget)
                            .r1Pressable(
                                onClick = { whatsNewOpen.value = true },
                                contentDescription = "Show what's new in this version",
                            )
                            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("What's new", style = responsiveType(R1.bodyEmph), color = R1.Ink)
                        Spacer(Modifier.width(R1.space.l))
                        Text(
                            text = "SHOW →",
                            style = responsiveType(R1.label),
                            color = R1.AccentWarm,
                            modifier = Modifier.weight(1f),
                            textAlign = TextAlign.End,
                        )
                    }
                }
                // Opt-out lives here, beside the updater rows, rather than in the
                // Settings registry: it only matters in the update context.
                item {
                    val whatsNewScope = androidx.compose.runtime.rememberCoroutineScope()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = R1.MinTarget)
                            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Show what's new after updates",
                                style = responsiveType(R1.bodyEmph),
                                color = R1.Ink,
                            )
                            Text(
                                text = "One-time panel summarising each release.",
                                style = responsiveType(R1.body),
                                color = R1.InkMuted,
                            )
                        }
                        Spacer(Modifier.width(R1.space.l))
                        com.github.itskenny0.r1ha.ui.components.R1Switch(
                            checked = appSettings.behavior.showWhatsNew,
                            onCheckedChange = { on ->
                                whatsNewScope.launch {
                                    settings.update { s ->
                                        s.copy(behavior = s.behavior.copy(showWhatsNew = on))
                                    }
                                }
                            },
                        )
                    }
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

                // ── Version (install / downgrade picker) ─────────────────────────────────
                // github flavour only: the F-Droid policy discourages in-app APK
                // download + install (it needs REQUEST_INSTALL_PACKAGES, which the
                // fdroid flavour intentionally drops). On fdroid we render a single
                // line pointing the user at their app store instead. Gated at
                // composition time so R8 strips the picker + AppUpdater wiring from
                // the fdroid APK rather than just hiding it at runtime.
                if (!BuildConfig.IS_FDROID_BUILD) {
                    item { Section("VERSION") }
                    item { VersionPickerSection() }
                    item { SectionDivider() }
                }

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
                // What the responsive layout actually resolves this device to, and why the
                // peek deck is on or off. Reading these here is how an R1 owner (or a tester
                // on any panel) can see the resolved size class, the raw window pixels, and
                // the exact factor that decided peek without guessing.
                item { DisplayDetectionRows(appSettings) }

                item { SectionDivider() }

                // ── License ────────────────────────────────────────────────────────────
                item { Section("LICENSE") }
                item {
                    Text(
                        text = "Released into the public domain via The Unlicense. " +
                            "Copy, modify, redistribute. Commercial or not, by any means.",
                        style = responsiveType(R1.body),
                        color = R1.InkSoft,
                        modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.xs),
                    )
                }
                item { SectionDivider() }

                // ── Acknowledgements ─────────────────────────────────────────────────────
                // Thanks to the platform R1HA builds on, the Open Home Foundation mark
                // (shown for nominative use), and a prominent unaffiliated-disclaimer:
                // showing the names/marks alongside this disclaimer is exactly the
                // nominative-use case that makes it appropriate.
                item { Section("THANKS") }
                item { AcknowledgementsSection() }

                item { SectionDivider() }
                // ── Dev menu ───────────────────────────────────────────────────────────
                item { Section("DEVELOPER") }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = R1.MinTarget)
                            .r1Pressable(
                                onClick = onOpenDevMenu,
                                contentDescription = "Open dev menu: advanced tunables, " +
                                    "behaviour flags, in-app log viewer",
                            )
                            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dev menu", style = responsiveType(R1.bodyEmph), color = R1.Ink)
                            Text(
                                text = "Advanced tunables, behaviour flags, in-app log viewer.",
                                style = responsiveType(R1.body),
                                color = R1.InkMuted,
                            )
                        }
                        Spacer(Modifier.width(R1.space.m))
                        Chevron(direction = ChevronDirection.Right, tint = R1.InkSoft)
                    }
                }
                item { Spacer(Modifier.height(R1.MinTarget)) }
            }
        } // AdaptiveContent

        // Re-view of the upgrade overlay. A Dialog (not an inline Box) so it
        // floats above the whole screen without restructuring the layout, and
        // system Back closes it like any modal. No version stamping here: this
        // is a re-view, the one-shot gate lives in MainActivity.
        if (whatsNewOpen.value) {
            val disableScope = androidx.compose.runtime.rememberCoroutineScope()
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { whatsNewOpen.value = false },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
            ) {
                com.github.itskenny0.r1ha.feature.whatsnew.WhatsNewOverlay(
                    onDismiss = { whatsNewOpen.value = false },
                    onDisable = {
                        whatsNewOpen.value = false
                        disableScope.launch {
                            settings.update { s ->
                                s.copy(behavior = s.behavior.copy(showWhatsNew = false))
                            }
                        }
                    },
                )
            }
        }
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
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Last event", style = responsiveType(R1.bodyEmph), color = R1.Ink)
        Spacer(Modifier.weight(1f))
        Text(text = text, style = responsiveType(R1.body), color = tint)
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
                .padding(horizontal = R1.space.xl, vertical = R1.space.s),
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
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs),
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
                .padding(horizontal = R1.space.xl, vertical = R1.space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Raw prefixes (every domain HA returned, including unsupported)",
                style = R1.body,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(R1.space.s))
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
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs),
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
                        .padding(horizontal = R1.space.xl, vertical = R1.space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = prefix,
                        style = R1.body.copy(fontFamily = FontFamily.Monospace),
                        color = if (supported) R1.Ink else R1.InkMuted,
                    )
                    if (!supported) {
                        Spacer(Modifier.width(R1.space.xs))
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
                            .padding(horizontal = R1.space.xl, vertical = R1.space.xs),
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
                                modifier = Modifier.padding(start = 44.dp, end = R1.space.xl, top = R1.space.xxs, bottom = R1.space.xxs),
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
                modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.xs),
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
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        Text(text = "UPDATES", style = R1.labelMicro, color = R1.InkSoft)
        androidx.compose.foundation.layout.Spacer(Modifier.height(R1.space.xxs))
        Text(
            text = "F-Droid distribution: install updates via your F-Droid client. GitHub Releases also publishes the same APK.",
            style = responsiveType(R1.body),
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
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
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
                        .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                ) {
                    Text(pillText, style = R1.labelMicro, color = pillColor)
                }
                // CANCEL chip while a download is in flight. Aborts the underlying
                // OkHttp stream + the Compose runtime's resume callbacks so a
                // slow / failed download can be backed out without restarting
                // the app.
                if (state.value is UpdaterState.Downloading) {
                    androidx.compose.foundation.layout.Spacer(Modifier.width(R1.space.xs))
                    Box(
                        modifier = Modifier
                            .background(R1.StatusRed.copy(alpha = 0.18f), shape = R1.ShapeS)
                            .r1Pressable(onClick = {
                                downloadJob.value?.cancel()
                                downloadJob.value = null
                            })
                            .padding(horizontal = R1.space.s, vertical = R1.space.xs),
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
                Spacer(Modifier.height(R1.space.xs))
                Text(
                    text = s.info.notes,
                    style = R1.body,
                    color = R1.InkSoft,
                    maxLines = 6,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
            is UpdaterState.Error -> {
                Spacer(Modifier.height(R1.space.xs))
                Text(s.message, style = R1.labelMicro, color = R1.StatusRed)
            }
            else -> Unit
        }
    }
}

/**
 * Acknowledgements: thanks to Home Assistant + Nabu Casa, the Open Home Foundation
 * mark, and the prominent unaffiliated-disclaimer. The disclaimer is what makes
 * showing the names/marks here nominative use rather than implying endorsement, so
 * it sits right under the logo, not buried.
 */
@Composable
private fun AcknowledgementsSection() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        Text(
            text = "R1HA stands on Home Assistant and Nabu Casa: thank you for the open " +
                "platform, the local-first APIs, and the community this client plugs into.",
            style = responsiveType(R1.body),
            color = R1.InkSoft,
        )
        Spacer(Modifier.height(R1.space.m))
        // Open Home Foundation mark: an original vector recreation (res/drawable/
        // ohf_logo.xml), tinted to the theme accent. Centred above the wordmark.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(
                painter = androidx.compose.ui.res.painterResource(
                    id = com.github.itskenny0.r1ha.R.drawable.ohf_logo,
                ),
                contentDescription = "Open Home Foundation logo",
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(R1.AccentWarm),
                modifier = Modifier
                    .height(48.dp)
                    .width(48.dp),
            )
            Spacer(Modifier.width(R1.space.m))
            Text(
                text = "Open Home Foundation",
                style = responsiveType(R1.bodyEmph),
                color = R1.Ink,
            )
        }
        Spacer(Modifier.height(R1.space.m))
        // Prominent disclaimer. Bordered + accent-tinted so it reads as the
        // load-bearing legal note, not a footnote. Nominative-use wording.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(R1.SurfaceMuted, shape = R1.ShapeS)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
        ) {
            Text(
                text = "R1HA is an unofficial, community-built client. It is not " +
                    "affiliated with, endorsed by, or supported by Home Assistant, " +
                    "Nabu Casa, or the Open Home Foundation.",
                style = responsiveType(R1.body),
                color = R1.Ink,
            )
        }
    }
}

/**
 * VERSION section (github flavour only): shows the running version and a SELECT
 * overlay listing the releases available on GitHub for this flavour, newest-first,
 * with the current one marked. INSTALL downloads + hands the chosen APK to the
 * package installer via [com.github.itskenny0.r1ha.core.update.AppUpdater].
 *
 * States: idle / loading / loaded(list) / error (offline, 403 rate-limit, no
 * installable releases) / downloading(%). Selecting the current version disables
 * INSTALL. Choosing an OLDER versionCode surfaces a downgrade note: Android blocks
 * an in-place downgrade for release builds, so the installer will ask the user to
 * uninstall first (losing app data). We don't pretend that's seamless.
 */
@Composable
private fun VersionPickerSection() {
    val context = LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val updater = androidx.compose.runtime.remember {
        com.github.itskenny0.r1ha.core.update.AppUpdater(http = okhttp3.OkHttpClient())
    }
    val state = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<VersionPickerState>(VersionPickerState.Idle)
    }
    val selected = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.update.ReleaseOption?>(null)
    }
    val pickerOpen = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val downloadJob = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<kotlinx.coroutines.Job?>(null)
    }
    val installedCode = BuildConfig.VERSION_CODE.toLong()

    fun loadReleases() {
        state.value = VersionPickerState.Loading
        scope.launch {
            state.value = when (val r = updater.listReleases()) {
                is com.github.itskenny0.r1ha.core.update.AppUpdater.ReleasesResult.Ok ->
                    if (r.releases.isEmpty()) {
                        VersionPickerState.Error("No installable releases found for this build.")
                    } else {
                        // Default the selection to the current build if present.
                        selected.value = r.releases.firstOrNull { it.isCurrent } ?: r.releases.first()
                        VersionPickerState.Loaded(r.releases)
                    }
                is com.github.itskenny0.r1ha.core.update.AppUpdater.ReleasesResult.Failed ->
                    VersionPickerState.Error(r.message)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
    ) {
        // Current version + a SELECT chip that loads (then opens) the picker.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Installed", style = R1.bodyEmph, color = R1.Ink)
                Text(
                    text = "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = R1.body.copy(fontFamily = FontFamily.Monospace),
                    color = R1.InkSoft,
                )
            }
            Spacer(Modifier.width(R1.space.m))
            val chipText = when (state.value) {
                is VersionPickerState.Loading -> "LOADING…"
                is VersionPickerState.Loaded -> "SELECT ▾"
                is VersionPickerState.Downloading -> "DOWNLOADING ${((state.value as VersionPickerState.Downloading).fraction * 100).toInt()}%"
                is VersionPickerState.Error -> "RETRY"
                else -> "CHOOSE VERSION"
            }
            Box(
                modifier = Modifier
                    .background(R1.SurfaceMuted, shape = R1.ShapeS)
                    .r1Pressable(
                        onClick = {
                            when (state.value) {
                                is VersionPickerState.Loaded -> pickerOpen.value = true
                                is VersionPickerState.Downloading -> Unit
                                else -> loadReleases()
                            }
                        },
                        contentDescription = "Choose an app version to install from GitHub",
                    )
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs),
            ) {
                Text(
                    text = chipText,
                    style = R1.labelMicro,
                    color = if (state.value is VersionPickerState.Error) R1.StatusRed else R1.AccentWarm,
                )
            }
        }

        // Error line under the row.
        (state.value as? VersionPickerState.Error)?.let { e ->
            Spacer(Modifier.height(R1.space.xs))
            Text(e.message, style = R1.labelMicro, color = R1.StatusRed)
        }

        // Selected version + INSTALL, shown once a list is loaded.
        if (state.value is VersionPickerState.Loaded || state.value is VersionPickerState.Downloading) {
            val sel = selected.value
            if (sel != null) {
                Spacer(Modifier.height(R1.space.s))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Selected: ${sel.versionName}${if (sel.isCurrent) " (current)" else ""}",
                            style = R1.body,
                            color = R1.Ink,
                        )
                    }
                    Spacer(Modifier.width(R1.space.m))
                    val downloading = state.value is VersionPickerState.Downloading
                    // Hold onto the loaded list so we can restore it after the
                    // download hand-off without re-hitting the network.
                    val loadedReleases = (state.value as? VersionPickerState.Loaded)?.releases
                    // Current version: nothing to install. Disable the button
                    // (but while downloading the same box turns into CANCEL).
                    val disabled = sel.isCurrent && !downloading
                    Box(
                        modifier = Modifier
                            .background(
                                when {
                                    downloading -> R1.StatusRed.copy(alpha = 0.18f)
                                    disabled -> R1.SurfaceMuted
                                    else -> R1.AccentWarm.copy(alpha = 0.18f)
                                },
                                shape = R1.ShapeS,
                            )
                            .r1Pressable(
                                onClick = {
                                    // While a download runs, this box is CANCEL.
                                    if (downloading) {
                                        downloadJob.value?.cancel()
                                        downloadJob.value = null
                                        return@r1Pressable
                                    }
                                    if (disabled) return@r1Pressable
                                    val target = loadedReleases ?: emptyList()
                                    state.value = VersionPickerState.Downloading(0f)
                                    downloadJob.value = scope.launch {
                                        runCatching {
                                            updater.downloadAndInstall(context, sel.toUpdateInfo()) { read, total ->
                                                val frac = if (total > 0) (read.toFloat() / total).coerceIn(0f, 1f) else 0f
                                                state.value = VersionPickerState.Downloading(frac)
                                            }
                                            // Installer takes over; restore the list.
                                            state.value = VersionPickerState.Loaded(target)
                                        }.onFailure {
                                            state.value = if (it is kotlinx.coroutines.CancellationException) {
                                                // Back to the list, not an error screen.
                                                VersionPickerState.Loaded(target)
                                            } else {
                                                VersionPickerState.Error(it.message ?: "Install failed.")
                                            }
                                        }
                                        downloadJob.value = null
                                    }
                                },
                                contentDescription = if (downloading) "Cancel the download" else "Install the selected version",
                            )
                            .padding(horizontal = R1.space.m, vertical = R1.space.xs),
                    ) {
                        Text(
                            text = if (downloading) "CANCEL" else "INSTALL",
                            style = R1.labelMicro,
                            color = when {
                                downloading -> R1.StatusRed
                                disabled -> R1.InkMuted
                                else -> R1.AccentWarm
                            },
                        )
                    }
                }
                // Downgrade honesty note: installing an older versionCode in place
                // is blocked by Android (INSTALL_FAILED_VERSION_DOWNGRADE); the
                // installer will offer to uninstall first, losing app data.
                if (!sel.isCurrent && sel.versionCode < installedCode) {
                    Spacer(Modifier.height(R1.space.xs))
                    Text(
                        text = "Older versions may require uninstalling the current app first; " +
                            "your settings would be lost.",
                        style = R1.labelMicro,
                        color = R1.StatusAmber,
                    )
                }
            }
        }
    }

    // The picker overlay: a modal list of versions, newest-first, current marked.
    if (pickerOpen.value) {
        val releases = (state.value as? VersionPickerState.Loaded)?.releases.orEmpty()
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { pickerOpen.value = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .heightIn(max = 420.dp)
                    .background(R1.Bg, shape = R1.ShapeS)
                    .padding(R1.space.l),
            ) {
                Text("CHOOSE VERSION", style = R1.sectionHeader, color = R1.AccentWarm)
                Spacer(Modifier.height(R1.space.s))
                LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                    items(releases) { r ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = R1.MinTarget)
                                .r1Pressable(
                                    onClick = {
                                        selected.value = r
                                        pickerOpen.value = false
                                    },
                                    contentDescription = "Select version ${r.versionName}",
                                )
                                .padding(vertical = R1.space.s),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = r.versionName,
                                    style = R1.bodyEmph,
                                    color = if (selected.value?.tagName == r.tagName) R1.AccentWarm else R1.Ink,
                                )
                                val tail = buildString {
                                    if (r.isCurrent) append("current")
                                    else if (r.versionCode < installedCode) append("older")
                                    else append("newer")
                                }
                                Text(
                                    text = "${r.tagName} · $tail",
                                    style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                                    color = R1.InkMuted,
                                )
                            }
                            if (r.isCurrent) {
                                Text("●", style = R1.labelMicro, color = R1.AccentWarm)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Local state machine for the version picker. */
private sealed interface VersionPickerState {
    data object Idle : VersionPickerState
    data object Loading : VersionPickerState
    data class Loaded(val releases: List<com.github.itskenny0.r1ha.core.update.ReleaseOption>) : VersionPickerState
    data class Downloading(val fraction: Float) : VersionPickerState
    data class Error(val message: String) : VersionPickerState
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
            .padding(start = R1.space.xl, end = R1.space.xl, top = R1.space.xl, bottom = R1.space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = responsiveType(R1.sectionHeader), color = R1.AccentWarm)
        Spacer(Modifier.width(R1.space.s))
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
    Spacer(Modifier.height(R1.space.xxs))
}

@Composable
private fun InfoRow(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, style = responsiveType(R1.bodyEmph), color = R1.Ink)
        Spacer(Modifier.width(R1.space.l))
        Text(
            text = value,
            style = responsiveType(
                if (mono) R1.body.copy(fontFamily = FontFamily.Monospace) else R1.body,
            ),
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.End,
        )
    }
}

/**
 * Read-only diagnostics for the responsive layout: the resolved size class with the raw
 * dp / pixel dimensions it came from, the orientation, and whether the peek deck is on with
 * the single factor that decided it. Surfaces exactly what [effectivePeek] and the window
 * tier see, so a tester can confirm a device is classified as intended (the Rabbit R1 in
 * particular, whose dp width is density- and ROM-dependent) without reading logs.
 */
@Composable
private fun DisplayDetectionRows(appSettings: AppSettings) {
    val window = rememberWindowTier()
    val px = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val shortestPx = minOf(px.width, px.height)
    val isPortrait = androidx.compose.ui.platform.LocalConfiguration.current
        .orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
    val mode = appSettings.ui.cardPeekMode
    val peeks = effectivePeek(mode, window.tier, isPortrait, shortestPx)
    InfoRow("Size class", "${window.tier} · ${window.widthDp}×${window.heightDp} dp", mono = true)
    InfoRow("Window", "${px.width}×${px.height} px (min $shortestPx)", mono = true)
    InfoRow("Orientation", if (isPortrait) "Portrait" else "Landscape")
    InfoRow("Peek deck", peekDeckExplanation(mode, window.tier, isPortrait, shortestPx, peeks))
    val layoutMode = appSettings.ui.deckLayoutMode
    InfoRow(
        "Deck layout",
        deckLayoutExplanation(layoutMode, window.tier, effectiveDeckLayout(layoutMode, window.tier)),
    )
}

/**
 * Human-readable "Full / Dynamic (why)" for the deck-layout decision, mirroring
 * [effectiveDeckLayout]'s branches like [peekDeckExplanation] does for peek.
 */
private fun deckLayoutExplanation(
    mode: DeckLayoutMode,
    tier: WindowTier,
    layout: DeckLayout,
): String {
    val state = when (layout) {
        DeckLayout.DYNAMIC -> "Dynamic"
        DeckLayout.HALF_HEIGHT -> "Half"
        DeckLayout.FULLSCREEN -> "Full"
    }
    val reason = when (mode) {
        DeckLayoutMode.FULLSCREEN -> "mode FULL"
        DeckLayoutMode.HALF_HEIGHT -> "mode HALF"
        DeckLayoutMode.DYNAMIC -> "mode DYNAMIC"
        DeckLayoutMode.AUTO ->
            if (tier == WindowTier.R1) "AUTO, R1 panel" else "AUTO, $tier is larger than the R1"
    }
    return "$state ($reason)"
}

/**
 * Human-readable "On / Off (why)" for the peek-deck decision, mirroring [effectivePeek]'s
 * branches so the displayed reason is always the actual deciding factor.
 */
private fun peekDeckExplanation(
    mode: CardPeekMode,
    tier: WindowTier,
    isPortrait: Boolean,
    shortestPx: Int,
    peeks: Boolean,
): String {
    val state = if (peeks) "On" else "Off"
    val reason = when (mode) {
        CardPeekMode.NEVER -> "mode NEVER"
        CardPeekMode.ALWAYS -> "mode ALWAYS"
        CardPeekMode.AUTO -> when {
            !isPortrait -> "AUTO, landscape"
            tier != WindowTier.COMPACT && tier != WindowTier.MEDIUM ->
                "AUTO, $tier is not a phone width"
            shortestPx < PEEK_MIN_SHORTEST_SIDE_PX ->
                "AUTO, $shortestPx px below the $PEEK_MIN_SHORTEST_SIDE_PX px floor"
            else -> "AUTO, phone in portrait"
        }
    }
    return "$state ($reason)"
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
            .heightIn(min = R1.MinTarget)
            // Icon-less link: read the label plus the destination so TalkBack
            // announces "Source code link, opens github.com/..." rather than the
            // raw underlined URL on its own line.
            .r1Pressable(onOpen, contentDescription = "$label link, opens $displayUrl")
            .padding(horizontal = R1.space.xl, vertical = R1.space.s),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(label, style = responsiveType(R1.bodyEmph), color = R1.Ink)
        Spacer(Modifier.height(R1.space.xxs))
        Text(
            text = displayUrl,
            // Underline so the URL reads as interactive even without a chevron.
            style = responsiveType(
                R1.body.copy(
                    fontFamily = FontFamily.Monospace,
                    textDecoration = androidx.compose.ui.text.style.TextDecoration.Underline,
                ),
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
