package com.github.itskenny0.r1ha.feature.systemhealth

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.ha.HaRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.Toaster
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * System Health diagnostic screen. Renders `/api/config` (HA version,
 * timezone, components, URLs) and the tail of `/api/error_log` for at-
 * a-glance "is my HA install healthy?" inspection. The error log gets
 * a COPY-to-clipboard affordance for bug-report pasting.
 */
@Composable
fun SystemHealthScreen(
    haRepository: HaRepository,
    settings: com.github.itskenny0.r1ha.core.prefs.SettingsRepository,
    wheelInput: com.github.itskenny0.r1ha.core.input.WheelInput,
    onBack: () -> Unit,
    /** Drill into the dedicated full-log viewer. Default no-op keeps this
     *  composable callable from contexts that don't have nav wiring (tests,
     *  preview). Production wires it to [Routes.LOGS]. */
    onOpenFullLog: () -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val wsClient = androidx.compose.runtime.remember {
        (context.applicationContext as com.github.itskenny0.r1ha.App).graph.wsClient
    }
    val vm: SystemHealthViewModel = viewModel(
        factory = SystemHealthViewModel.factory(haRepository, wsClient),
    )
    val ui by vm.ui.collectAsState()
    val clipboard = LocalClipboardManager.current
    LaunchedEffect(Unit) { vm.refresh() }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "SYSTEM HEALTH",
            onBack = onBack,
            action = {
                // REFRESH chip — pulls a fresh /api/config + /api/error_log.
                // Without this the user had to back-and-re-enter the screen
                // to update the diagnostic, which on a fast-moving HA
                // install (say, while debugging an integration loop) made
                // the panel less useful than it could be.
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
        if (ui.loading && ui.config == null) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    strokeWidth = 2.dp,
                    color = R1.AccentWarm,
                )
            }
            return@Column
        }
        val scrollState = rememberScrollState()
        com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState(
            wheelInput = wheelInput,
            scrollState = scrollState,
            settings = settings,
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
            ) {
                Text(text = "SERVER", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.size(4.dp))
                val cfg = ui.config
                if (cfg != null) {
                    ConfigPanel(cfg)
                } else if (ui.configError != null) {
                    ErrorPanel(ui.configError!!)
                }
                Spacer(Modifier.size(16.dp))
                // System Health: HA's `system_health/info` report grouped by
                // integration domain. Each section lists the key/value detail
                // rows that integration registered, with a clear OK / checking /
                // failed indicator wherever HA marks a reachability check.
                Text(text = "INTEGRATIONS", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.size(4.dp))
                when {
                    ui.healthSections.isNotEmpty() ->
                        ui.healthSections.forEach { section ->
                            HealthSectionPanel(section)
                            Spacer(Modifier.size(8.dp))
                        }
                    ui.healthError != null -> ErrorPanel(ui.healthError!!)
                    ui.loading -> Text(
                        text = "Loading system health…",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                    else -> Text(
                        text = "No integration reported system-health details.",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                Spacer(Modifier.size(12.dp))
                // Inline ping chip: measures round-trip time to /api/config so users
                // can diagnose slow links without leaving the screen. The result
                // sticks until the next press; multiple consecutive presses show how
                // variable the link is.
                PingRow(haRepository)
                Spacer(Modifier.size(12.dp))
                Text(text = "NETWORK SECURITY", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.size(4.dp))
                NetworkSecurityPanel()
                Spacer(Modifier.size(12.dp))
                ShareDebugBundleRow()
                Spacer(Modifier.size(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "ERROR LOG (tail)", style = R1.labelMicro, color = R1.InkSoft)
                    Spacer(Modifier.weight(1f))
                    // OPEN FULL — drills into the dedicated Logs viewer
                    // which streams a larger tail (512 KB vs 32 KB here),
                    // parses log-line levels into chip filters, and supports
                    // substring search + auto-refresh. The COPY chip still
                    // copies whatever's visible on this screen so the bug-
                    // report flow doesn't lose its one-tap path.
                    Box(
                        modifier = Modifier
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable(onClick = onOpenFullLog)
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        Text(text = "OPEN FULL", style = R1.labelMicro, color = R1.AccentWarm)
                    }
                    if (ui.errorLog.isNotBlank()) {
                        Spacer(Modifier.size(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable(onClick = {
                                    clipboard.setText(AnnotatedString(ui.errorLog))
                                    Toaster.show("Copied")
                                })
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(text = "COPY", style = R1.labelMicro, color = R1.InkSoft)
                        }
                    }
                }
                Spacer(Modifier.size(4.dp))
                when {
                    ui.errorLog.isNotBlank() -> ErrorLogPanel(ui.errorLog)
                    ui.errorLogError != null -> ErrorPanel(ui.errorLogError!!)
                    else -> Text(
                        text = "No log output (HA returned an empty body).",
                        style = R1.body,
                        color = R1.InkMuted,
                    )
                }
                Spacer(Modifier.size(24.dp))
            }
        } // AdaptiveContent
    }
}

@Composable
private fun ConfigPanel(cfg: com.github.itskenny0.r1ha.core.ha.HaConfig) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Pair("Version", cfg.version).render()
        Pair("Location", cfg.locationName).render()
        Pair("Time zone", cfg.timeZone).render()
        Pair("Elevation", cfg.elevation?.let { "${it.toInt()} m" }).render()
        Pair("Internal URL", cfg.internalUrl).render()
        Pair("External URL", cfg.externalUrl).render()
        if (cfg.unitSystem.isNotEmpty()) {
            Pair(
                "Units",
                cfg.unitSystem.entries.joinToString(" · ") { "${it.key}=${it.value}" },
            ).render()
        }
        if (cfg.components.isNotEmpty()) {
            Pair(
                "Components (${cfg.components.size})",
                cfg.components.joinToString(", "),
            ).render(multiline = true)
        }
    }
}

/**
 * One integration domain's system-health detail block: a titled card whose
 * header carries the domain name plus a status dot (red when any reachability
 * check failed, amber while one is still resolving, green otherwise), followed
 * by the key/value rows.
 */
@Composable
private fun HealthSectionPanel(section: HealthSection) {
    val headerStatus = when {
        section.hasFailure -> HealthStatus.FAILED
        section.hasPending -> HealthStatus.PENDING
        else -> HealthStatus.OK
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(headerStatus)
            Spacer(Modifier.size(6.dp))
            Text(
                text = section.title.uppercase(),
                style = R1.labelMicro,
                color = R1.Ink,
            )
        }
        section.rows.forEach { HealthRowView(it) }
    }
}

@Composable
private fun HealthRowView(row: HealthRow) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = row.label.uppercase(), style = R1.labelMicro, color = R1.InkMuted)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (row.value.status != HealthStatus.NEUTRAL) {
                StatusDot(row.value.status)
                Spacer(Modifier.size(6.dp))
            }
            Text(
                text = row.value.display,
                style = R1.body,
                color = statusColor(row.value.status),
                maxLines = Int.MAX_VALUE,
            )
        }
    }
}

@Composable
private fun StatusDot(status: HealthStatus) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(androidx.compose.foundation.shape.CircleShape)
            .background(statusColor(status)),
    )
}

private fun statusColor(status: HealthStatus): androidx.compose.ui.graphics.Color = when (status) {
    HealthStatus.OK -> R1.AccentGreen
    HealthStatus.PENDING -> R1.StatusAmber
    HealthStatus.FAILED -> R1.StatusRed
    HealthStatus.NEUTRAL -> R1.Ink
}

@Composable
private fun Pair<String, String?>.render(multiline: Boolean = false) {
    val value = second
    if (value.isNullOrBlank()) return
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = first.uppercase(), style = R1.labelMicro, color = R1.InkMuted)
        Text(
            text = value,
            style = R1.body,
            color = R1.Ink,
            maxLines = if (multiline) Int.MAX_VALUE else 1,
        )
    }
}

@Composable
private fun ErrorLogPanel(body: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(
            text = body,
            style = R1.body.copy(
                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
            ),
            color = R1.InkSoft,
        )
    }
}

@Composable
private fun ErrorPanel(msg: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.StatusRed.copy(alpha = 0.18f))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(text = msg, style = R1.body, color = R1.StatusRed)
    }
}

@Composable
private fun PingRow(haRepository: HaRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val result = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    val inFlight = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "PING", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.weight(1f))
        if (result.value != null) {
            Text(
                text = result.value!!,
                style = R1.body,
                color = R1.AccentWarm,
            )
            Spacer(Modifier.size(8.dp))
        }
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {
                    if (inFlight.value) return@r1Pressable
                    inFlight.value = true
                    scope.launch {
                        val start = System.currentTimeMillis()
                        val outcome = haRepository.fetchHaConfig()
                        val elapsed = System.currentTimeMillis() - start
                        result.value = outcome.fold(
                            onSuccess = { "${elapsed} ms" },
                            onFailure = { "failed (${elapsed} ms)" },
                        )
                        inFlight.value = false
                    }
                })
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(
                text = if (inFlight.value) "…" else "TEST",
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        }
    }
}

/**
 * Read-only diagnostic for the recently-added TLS pinning + mTLS surface.
 * Reads directly from [SecurityPolicyStore] (sync) and renders three lines:
 * pinning state, mTLS state, and a one-line summary. Lives in System Health
 * so a user wondering "is my certificate actually being enforced?" can find
 * out without re-entering Settings.
 */
@Composable
private fun NetworkSecurityPanel() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val app = context.applicationContext as com.github.itskenny0.r1ha.App
    val policy = androidx.compose.runtime.remember { app.graph.securityPolicy.current() }
    val pinningStatus = when {
        policy.tlsPinningEnabled && policy.sha256Pins.isNotEmpty() ->
            "ON · ${policy.sha256Pins.size} pin${if (policy.sha256Pins.size == 1) "" else "s"}"
        policy.tlsPinningEnabled -> "ARMED · no pins configured"
        else -> "OFF"
    }
    val mtlsStatus = when {
        policy.mtlsEnabled && !policy.mtlsKeystorePath.isNullOrBlank() -> "ON · keystore loaded"
        policy.mtlsEnabled -> "ARMED · no keystore"
        else -> "OFF"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Pair("TLS pinning", pinningStatus).render()
        Pair("mTLS", mtlsStatus).render()
        Pair(
            "Effective at",
            "next app launch (policy reads at OkHttp build time)",
        ).render(multiline = true)
    }
}

@Composable
private fun ShareDebugBundleRow() {
    val context = androidx.compose.ui.platform.LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "SHARE DEBUG BUNDLE", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.weight(1f))
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = {
                    // Assemble a plaintext bundle from the in-memory log buffer + the
                    // last crash file. Sharing via ACTION_SEND lets the user route to
                    // any installed text-receiving app (email, GitHub Mobile, Slack,
                    // even Notes); avoids forcing a specific share target.
                    val sb = StringBuilder(8192)
                    sb.append("R1HA debug bundle · ")
                        .append(java.time.Instant.now().toString()).append('\n')
                    sb.append("App ").append(com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME)
                        .append(" (").append(com.github.itskenny0.r1ha.BuildConfig.VERSION_CODE)
                        .append(")\n")
                    val crashFile = java.io.File(context.filesDir, "last_crash.txt")
                    if (crashFile.exists()) {
                        sb.append("\n--- LAST CRASH ---\n")
                        sb.append(crashFile.readText())
                    }
                    sb.append("\n--- LOG TAIL (newest first) ---\n")
                    val logs = com.github.itskenny0.r1ha.core.util.R1LogBuffer.snapshot().reversed()
                    for (e in logs.take(200)) {
                        val ts = java.time.Instant.ofEpochMilli(e.timestampMillis).toString()
                        sb.append("[$ts] ").append(e.level).append(' ').append(e.tag)
                            .append(" — ").append(e.message).append('\n')
                    }
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_SUBJECT, "R1HA debug bundle")
                        putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
                    }
                    runCatching {
                        context.startActivity(
                            android.content.Intent.createChooser(send, "Share debug bundle")
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                })
                .padding(horizontal = 10.dp, vertical = 4.dp),
        ) {
            Text(text = "SHARE", style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}
