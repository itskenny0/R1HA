package com.github.itskenny0.r1ha.feature.devmenu

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
import androidx.compose.foundation.lazy.rememberLazyListState
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.AdvancedSettings
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Log
import com.github.itskenny0.r1ha.core.util.R1LogBuffer
import com.github.itskenny0.r1ha.feature.settings.SettingsViewModel
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollFor
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Dev menu — a single scrollable surface with every advanced setting + the in-memory
 * log buffer viewer. Aimed at the user who already knows what they're doing; minimal
 * hand-holding. Every toggle / number picker writes through [SettingsViewModel] to
 * the same DataStore as the regular settings; the AdvancedSettings struct is
 * persisted as a single JSON blob so adding a new field doesn't require a
 * preferences migration.
 */
@Composable
fun DevMenuScreen(
    settings: SettingsRepository,
    tokens: com.github.itskenny0.r1ha.core.prefs.TokenStore,
    wheelInput: WheelInput,
    onBack: () -> Unit,
    /** Optional repository for power-tool panels (FIRE EVENT). Null in previews. */
    haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository? = null,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val state by vm.state.collectAsStateWithLifecycle(initialValue = AppSettings())
    val listState = rememberLazyListState()
    WheelScrollFor(wheelInput = wheelInput, listState = listState, settings = settings)
    val advanced = state.advanced

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(title = "DEV MENU", onBack = onBack)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {

            // ── Service-call timing ─────────────────────────────────────────────────
            item { Section("SERVICE CALL TIMING") }
            item {
                IntStepperRow(
                    label = "Debounce (ms)",
                    subtitle = "Trailing-edge silence window before the wire call fires.",
                    value = advanced.serviceDebounceMs,
                    step = 10,
                    range = 10..500,
                    onSet = { v -> vm.updateAdvanced { it.copy(serviceDebounceMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Max interval (ms)",
                    subtitle = "Force-fire after this much continuous in-flight gesture.",
                    value = advanced.serviceMaxIntervalMs,
                    step = 25,
                    range = 50..1000,
                    onSet = { v -> vm.updateAdvanced { it.copy(serviceMaxIntervalMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Wheel rate window (ms)",
                    subtitle = "Sliding window used to compute events/sec for the acceleration ramp.",
                    value = advanced.wheelRateWindowMs,
                    step = 25,
                    range = 50..1000,
                    onSet = { v -> vm.updateAdvanced { it.copy(wheelRateWindowMs = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Nav step cap",
                    subtitle = "Max cards per detent during a fast wheel spin.",
                    value = advanced.navAccelCap,
                    step = 1,
                    range = 1..20,
                    onSet = { v -> vm.updateAdvanced { it.copy(navAccelCap = v) } },
                )
            }
            item { SectionDivider() }

            // ── Network ─────────────────────────────────────────────────────────────
            item { Section("NETWORK") }
            item {
                IntStepperRow(
                    label = "REST timeout (s)",
                    subtitle = "Per-request timeout for /api/states and /api/history.",
                    value = advanced.restTimeoutSec,
                    step = 5,
                    range = 5..120,
                    onSet = { v -> vm.updateAdvanced { it.copy(restTimeoutSec = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "Reconnect backoff cap (s)",
                    subtitle = "Maximum seconds between WS reconnect attempts.",
                    value = advanced.reconnectBackoffMaxSec,
                    step = 5,
                    range = 5..300,
                    onSet = { v -> vm.updateAdvanced { it.copy(reconnectBackoffMaxSec = v) } },
                )
            }
            item {
                IntStepperRow(
                    label = "WS ping interval (s)",
                    subtitle = "0 = OkHttp default (30 s). Increase on flaky networks if HA drops the WS.",
                    value = advanced.wsPingIntervalSec,
                    step = 5,
                    range = 0..300,
                    onSet = { v -> vm.updateAdvanced { it.copy(wsPingIntervalSec = v) } },
                )
            }
            item { SectionDivider() }

            // ── Sensor / history ────────────────────────────────────────────────────
            item { Section("HISTORY") }
            item {
                IntStepperRow(
                    label = "Sensor history hours",
                    subtitle = "Span fetched per sensor card on open. Smaller = faster initial render.",
                    value = advanced.sensorHistoryHours,
                    step = 1,
                    range = 1..168,
                    onSet = { v -> vm.updateAdvanced { it.copy(sensorHistoryHours = v) } },
                )
            }
            item { SectionDivider() }

            // ── Toggles ─────────────────────────────────────────────────────────────
            item { Section("BEHAVIOUR FLAGS") }
            item {
                DevSwitchRow(
                    label = "Keep log buffer",
                    subtitle = "Append R1Log entries to a 500-row ring for the viewer below.",
                    checked = advanced.keepLogBuffer,
                    onChange = { v -> vm.updateAdvanced { it.copy(keepLogBuffer = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Strict entity decode",
                    subtitle = "Drop rows that fail to construct an EntityState instead of logging and skipping. Useful for spotting decoder issues — sets the floor lower so problems surface.",
                    checked = advanced.strictEntityDecode,
                    onChange = { v -> vm.updateAdvanced { it.copy(strictEntityDecode = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Pin optimistic",
                    subtitle = "Never auto-clear the optimistic UI override. Diagnostic for the reconcile path.",
                    checked = advanced.pinOptimistic,
                    onChange = { v -> vm.updateAdvanced { it.copy(pinOptimistic = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Slow pager transitions",
                    subtitle = "Stretch the swipe animation by 1.4× — makes the deck feel more physical.",
                    checked = advanced.slowPagerTransitions,
                    onChange = { v -> vm.updateAdvanced { it.copy(slowPagerTransitions = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Show entity_id on cards",
                    subtitle = "Render the HA entity_id under the friendly name. Useful for debugging.",
                    checked = advanced.showEntityIdOnCards,
                    onChange = { v -> vm.updateAdvanced { it.copy(showEntityIdOnCards = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Show debug strip",
                    subtitle = "Per-card debug strip — cached percent, supportsScalar, rawState.",
                    checked = advanced.showDebugStripOnCards,
                    onChange = { v -> vm.updateAdvanced { it.copy(showDebugStripOnCards = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Persist cache to disk",
                    subtitle = "Snapshot the HA entity cache on every change so cold starts paint cards from disk before the WebSocket connects. Off by default — needs an app restart to take effect.",
                    checked = advanced.persistCacheToDisk,
                    onChange = { v -> vm.updateAdvanced { it.copy(persistCacheToDisk = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose service calls",
                    subtitle = "Log every HA service call payload via R1Log.i (surface in toast if level high enough).",
                    checked = advanced.verboseServiceCalls,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseServiceCalls = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose HTTP",
                    subtitle = "Log REST request/response details. Heavy.",
                    checked = advanced.verboseHttp,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseHttp = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Verbose WebSocket",
                    subtitle = "Log every inbound/outbound WS frame at DEBUG. Very chatty.",
                    checked = advanced.verboseWebSocket,
                    onChange = { v -> vm.updateAdvanced { it.copy(verboseWebSocket = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Skip preflight refresh",
                    subtitle = "Don't call TokenRefresher.ensureFresh() before REST. Tests the 401-retry path.",
                    checked = advanced.skipPreflightRefresh,
                    onChange = { v -> vm.updateAdvanced { it.copy(skipPreflightRefresh = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Keep optimistic on failure",
                    subtitle = "Don't roll back the optimistic UI override when HA rejects a service call.",
                    checked = advanced.keepOptimisticOnFailure,
                    onChange = { v -> vm.updateAdvanced { it.copy(keepOptimisticOnFailure = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "External automation intent",
                    subtitle = "Let Tasker / MacroDroid / Automate broadcast com.github.itskenny0.r1ha.action.HA_SERVICE_CALL to fire HA service calls through this app's connection. Off by default: every installed app can broadcast, so flipping this on widens the attack surface. Extras: ha_domain (str), ha_service (str), ha_entity_id (str, opt), ha_data_json (str, opt).",
                    checked = advanced.externalAutomationEnabled,
                    onChange = { v -> vm.updateAdvanced { it.copy(externalAutomationEnabled = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Background refresh",
                    subtitle = "Schedule a JobService that warms the entity cache via /api/states every ~15 min while the app is closed. Useful for Quick Tile freshness + cold-start paint speed; the foreground WS already does this when the app is open. Takes effect on next app launch.",
                    checked = advanced.backgroundRefreshEnabled,
                    onChange = { v -> vm.updateAdvanced { it.copy(backgroundRefreshEnabled = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "Mirror HA notifications",
                    subtitle = "Post HA persistent_notification entities as Android notifications, with a DISMISS action that fires persistent_notification.dismiss server-side. Polls at the same cadence as the Notifications screen. Off by default; Android 13+ will prompt for POST_NOTIFICATIONS the first time you enable it.",
                    checked = advanced.mirrorHaNotifications,
                    onChange = { v -> vm.updateAdvanced { it.copy(mirrorHaNotifications = v) } },
                )
            }
            item {
                DevSwitchRow(
                    label = "NFC tag scanner",
                    subtitle = "When the app is foregrounded and an NFC tag is tapped against the device, fire HA's tag_scanned event with the tag's UID as tag_id. Foreground-only so it doesn't compete with other tag-handler apps installed on the device.",
                    checked = advanced.nfcTagScannerEnabled,
                    onChange = { v -> vm.updateAdvanced { it.copy(nfcTagScannerEnabled = v) } },
                )
            }
            item { SectionDivider() }

            // ── iBeacon advertiser ──────────────────────────────────────────
            item { Section("IBEACON") }
            item { IBeaconPanel(advanced, vm) }
            item { SectionDivider() }

            // ── Webhook receiver ────────────────────────────────────────────
            item { Section("WEBHOOK") }
            item { WebhookPanel(advanced, vm) }
            item { SectionDivider() }

            // ── MQTT publish ────────────────────────────────────────────────
            item { Section("MQTT") }
            item { MqttPanel(advanced, vm) }
            item { SectionDivider() }

            // ── Fire event ──────────────────────────────────────────────────────────
            if (haRepository != null) {
                item { Section("FIRE EVENT") }
                item { FireEventPanel(haRepository) }
                item { SectionDivider() }
                // ── Live events tail ───────────────────────────────────────────
                item { Section("LIVE EVENTS") }
                item { LiveEventsPanel(haRepository) }
                item { SectionDivider() }
            }

            // ── Log viewer ──────────────────────────────────────────────────────────
            item { Section("APP LOG") }
            item { LogViewer() }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/**
 * Power-user tool: fire an arbitrary HA event by type + optional JSON payload.
 * POSTs to `/api/events/<event_type>`; useful for testing automations that listen
 * for custom events (e.g. `r1_button_pressed`).
 */
@Composable
private fun FireEventPanel(haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var eventType by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var data by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var inFlight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var result by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("") }
    var error by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text = "EVENT TYPE", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = eventType,
            onValueChange = { eventType = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
            placeholder = "my_custom_event",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = "DATA (JSON, optional)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = data,
            onValueChange = { data = it },
            placeholder = """{"source":"r1"}""",
            monospace = true,
            singleLine = false,
            minLines = 2,
            modifier = Modifier.fillMaxWidth().height(72.dp),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            com.github.itskenny0.r1ha.ui.components.R1Button(
                text = if (inFlight) "FIRING…" else "FIRE",
                enabled = !inFlight && eventType.isNotBlank(),
                onClick = {
                    val payload = if (data.isBlank()) {
                        kotlinx.serialization.json.JsonObject(emptyMap())
                    } else {
                        runCatching {
                            kotlinx.serialization.json.Json.parseToJsonElement(data)
                                as? kotlinx.serialization.json.JsonObject
                                ?: error("Data must be a JSON object")
                        }.getOrElse { t ->
                            error = "Bad JSON: ${t.message}"; return@R1Button
                        }
                    }
                    inFlight = true
                    error = null
                    result = ""
                    scope.launch {
                        haRepository.fireEvent(eventType.trim(), payload).fold(
                            onSuccess = { body ->
                                inFlight = false
                                result = body.ifBlank { "(fired)" }
                            },
                            onFailure = { t ->
                                inFlight = false
                                error = t.message ?: "fire_event failed"
                            },
                        )
                    }
                },
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = "POST /api/events/${eventType.ifBlank { "<type>" }}",
                style = R1.labelMicro,
                color = R1.InkMuted,
                maxLines = 1,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(6.dp))
            Text(text = error ?: "", style = R1.labelMicro, color = R1.StatusRed)
        }
        if (result.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(text = result, style = R1.labelMicro, color = R1.InkSoft)
        }
    }
}

/**
 * Process-scope log viewer — taps the in-memory ring [R1LogBuffer] and renders the
 * last N entries newest → oldest. Tapping an entry expands it with the stack trace
 * (when present); tapping CLEAR empties the buffer.
 */
@Composable
private fun LogViewer() {
    // Subscribe to the bump-on-append flag so the viewer recomposes when new
    // entries land. The snapshot itself is read inline.
    val tick by R1LogBuffer.updates.collectAsStateWithLifecycle()
    val expanded = remember { androidx.compose.runtime.mutableStateOf<Int?>(null) }
    val entries = remember(tick) { R1LogBuffer.snapshot().reversed() }
    // SAF launcher for the EXPORT button — writes the log buffer as a plain
    // text file the user can share for diagnostics. Held outside the button's
    // onClick so it's stable across recompositions (the activity-result
    // contract registers once per composition).
    val context = androidx.compose.ui.platform.LocalContext.current
    val pendingExport = remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val exportLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: android.net.Uri? ->
        val blob = pendingExport.value
        pendingExport.value = null
        if (uri == null || blob == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(blob.toByteArray(Charsets.UTF_8))
            } ?: error("couldn't open output stream")
            com.github.itskenny0.r1ha.core.util.Toaster.show("Logs saved")
        }.onFailure { t ->
            R1Log.w("DevMenu.exportLogs", "write failed: ${t.message}")
            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                shortText = "Log export failed",
                fullText = "Couldn't write the log file.\n\n${t.message ?: t.toString()}",
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${entries.size} entries (newest first)",
                style = R1.body,
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { R1LogBuffer.clear() })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("CLEAR", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        R1Log.i("DevMenu", "test-INFO ping from dev menu — verify the log viewer + toasts route correctly")
                        R1Log.w("DevMenu", "test-WARN ping from dev menu")
                        R1Log.e("DevMenu", "test-ERROR ping from dev menu", IllegalStateException("synthetic"))
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("PING", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // Clear the in-memory + on-disk album-cover cache. Useful when a
            // user's HA media_player_proxy URL changes and the cached bytes
            // would otherwise paint a stale cover until eviction. Pushes a
            // toast so the user sees it took effect (force-shown so it lands
            // even with diagnostic toasts off — this is a deliberate action).
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        com.github.itskenny0.r1ha.ui.components.AsyncBitmapCache.clear()
                        R1Log.i("DevMenu", "AsyncBitmapCache cleared")
                        com.github.itskenny0.r1ha.core.util.Toaster.error("Image cache cleared")
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("IMG CACHE", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // EXPORT — drop the entire log buffer to a SAF-picked file the
            // user can share for diagnostics. Plain text (one line per
            // entry) so non-developers can read it; timestamps in ISO
            // format for grep-friendliness; throwables appended after their
            // message with indented stack frames.
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = {
                        val now = R1LogBuffer.snapshot()
                        val blob = buildString {
                            append("R1HA log export · ")
                            append(java.time.Instant.now().toString())
                            append('\n')
                            append("App ${com.github.itskenny0.r1ha.BuildConfig.VERSION_NAME} (${com.github.itskenny0.r1ha.BuildConfig.VERSION_CODE})\n")
                            append("${now.size} entries\n\n")
                            for (e in now) {
                                val ts = java.time.Instant.ofEpochMilli(e.timestampMillis).toString()
                                append("[$ts] ${e.level} ${e.tag} — ${e.message}\n")
                                e.throwable?.let { t ->
                                    append("    ").append(t::class.java.name)
                                    t.message?.let { append(": ").append(it) }
                                    append('\n')
                                    for (line in t.stackTraceToString().lines().take(20)) {
                                        append("    ").append(line).append('\n')
                                    }
                                }
                            }
                        }
                        pendingExport.value = blob
                        val stamp = java.text.SimpleDateFormat(
                            "yyyyMMdd-HHmm",
                            java.util.Locale.US,
                        ).format(java.util.Date())
                        exportLauncher.launch("r1ha-logs-$stamp.txt")
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text("EXPORT", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            // LAST CRASH — reads the persisted crash report written by the
            // uncaught-exception handler in App.onCreate and surfaces it
            // through the expandable error toast. After-the-fact diagnostics
            // for the most-recent crash; the file persists until overwritten
            // by the next crash, so the user has a window to retrieve it
            // after re-launching. The chip tints with StatusRed when a
            // crash file exists so it's obvious there's something to look
            // at; otherwise stays SurfaceMuted like the other dev chips.
            val crashFilesExist = remember {
                val a = java.io.File(context.filesDir, "last_crash.txt").let { it.exists() && it.length() > 0L }
                val b = java.io.File(context.filesDir, "last_crash_seen.txt").let { it.exists() && it.length() > 0L }
                a || b
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(
                        if (crashFilesExist) R1.StatusRed.copy(alpha = 0.25f)
                        else R1.SurfaceMuted
                    )
                    .r1Pressable(onClick = {
                        // Try the un-seen file first (most-recent crash that
                        // wasn't auto-surfaced yet), then the seen file (the
                        // last crash that the auto-surface already showed).
                        val unseen = java.io.File(context.filesDir, "last_crash.txt")
                        val seen = java.io.File(context.filesDir, "last_crash_seen.txt")
                        val file = when {
                            unseen.exists() && unseen.length() > 0L -> unseen
                            seen.exists() && seen.length() > 0L -> seen
                            else -> null
                        }
                        if (file == null) {
                            com.github.itskenny0.r1ha.core.util.Toaster.show("No crash report on disk")
                        } else {
                            val raw = runCatching { file.readText(Charsets.UTF_8) }
                                .getOrElse { "(read failed: ${it.message})" }
                            com.github.itskenny0.r1ha.core.util.Toaster.errorExpandable(
                                shortText = "Last crash · ${raw.lineSequence().firstOrNull()?.take(40) ?: "(empty)"}",
                                fullText = raw,
                            )
                            // Delete both files after surfacing so the red
                            // chip clears on next dev-menu visit. The user
                            // has the trace in the toast; no reason to keep
                            // it on disk after they've seen it.
                            runCatching {
                                if (unseen.exists()) unseen.delete()
                                if (seen.exists()) seen.delete()
                            }
                        }
                    })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    text = "LAST CRASH",
                    style = R1.labelMicro,
                    color = if (crashFilesExist) R1.StatusRed else R1.InkSoft,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        entries.forEachIndexed { idx, entry ->
            val isOpen = expanded.value == idx
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(
                        when (entry.level) {
                            R1LogBuffer.Level.E -> R1.StatusRed.copy(alpha = 0.18f)
                            R1LogBuffer.Level.W -> R1.StatusAmber.copy(alpha = 0.18f)
                            R1LogBuffer.Level.I -> R1.SurfaceMuted
                            R1LogBuffer.Level.D -> R1.SurfaceMuted
                        },
                    )
                    .r1Pressable(onClick = { expanded.value = if (isOpen) null else idx })
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = entry.level.name,
                        style = R1.labelMicro,
                        color = when (entry.level) {
                            R1LogBuffer.Level.E -> R1.StatusRed
                            R1LogBuffer.Level.W -> R1.StatusAmber
                            else -> R1.InkSoft
                        },
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = entry.tag,
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                    )
                }
                Text(
                    text = entry.message,
                    style = R1.body.copy(fontFamily = FontFamily.Monospace),
                    color = R1.Ink,
                    maxLines = if (isOpen) Int.MAX_VALUE else 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                if (isOpen && entry.throwable != null) {
                    Text(
                        text = entry.throwable.stackTraceToString(),
                        style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                        color = R1.InkMuted,
                    )
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
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
                .background(R1.Hairline)
                .fillMaxWidth(),
        )
    }
}

@Composable
private fun SectionDivider() {
    Spacer(Modifier.height(2.dp))
}

@Composable
private fun DevSwitchRow(
    label: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = { onChange(!checked) })
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (checked) R1.AccentWarm else R1.SurfaceMuted)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = if (checked) "ON" else "OFF",
                style = R1.labelMicro,
                color = if (checked) R1.Bg else R1.InkSoft,
            )
        }
    }
}

@Composable
private fun IntStepperRow(
    label: String,
    subtitle: String,
    value: Int,
    step: Int,
    range: IntRange,
    onSet: (Int) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(subtitle, style = R1.body, color = R1.InkMuted)
        }
        Spacer(Modifier.width(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSet(coerce(value - step, range.first, range.last)) }),
                contentAlignment = Alignment.Center,
            ) { Text("−", style = R1.bodyEmph, color = R1.Ink) }
            Spacer(Modifier.width(6.dp))
            Text(
                text = value.toString(),
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.Ink,
                modifier = Modifier.width(40.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onSet(coerce(value + step, range.first, range.last)) }),
                contentAlignment = Alignment.Center,
            ) { Text("+", style = R1.bodyEmph, color = R1.Ink) }
        }
    }
}

private fun coerce(value: Int, low: Int, high: Int): Int =
    if (value < low) low else if (value > high) high else value

/**
 * Webhook receiver controls. Toggling the switch on starts a foreground service
 * with a tiny HTTP listener; the user can POST to `/webhook/<id>` from HA's
 * webhook automation and see the body surface as a toast in the app.
 *
 * The port + id fields write through to DataStore immediately; the app-level
 * observer restarts the service with new extras on the next emission, so the
 * user doesn't have to toggle off-and-on to change the bind.
 */
@Composable
private fun WebhookPanel(
    advanced: com.github.itskenny0.r1ha.core.prefs.AdvancedSettings,
    vm: com.github.itskenny0.r1ha.feature.settings.SettingsViewModel,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        DevSwitchRow(
            label = "Listen for HA webhooks",
            subtitle = "Run a foreground HTTP server on the device. HA's webhook automation can POST at http://<device-ip>:<port>/webhook/<id>; the body shows up as an expandable toast. The service holds a persistent notification while active.",
            checked = advanced.webhookEnabled,
            onChange = { v -> vm.updateAdvanced { it.copy(webhookEnabled = v) } },
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "PORT", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.webhookPort.toString(),
            onValueChange = { v ->
                val n = v.toIntOrNull()?.coerceIn(1024, 65535)
                if (n != null) vm.updateAdvanced { it.copy(webhookPort = n) }
            },
            placeholder = "8765",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "WEBHOOK ID (URL PATH)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.webhookId,
            onValueChange = { v ->
                val cleaned = v.filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }
                vm.updateAdvanced { it.copy(webhookId = cleaned) }
            },
            placeholder = "r1",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "HA configuration.yaml: webhook → automation trigger 'webhook' with id '${advanced.webhookId}'. The action can target the URL printed in the persistent notification.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

/**
 * MQTT publish controls. One-shot publish-only client: every fire opens a fresh
 * TCP socket, CONNECTs with the configured auth, PUBLISHes the topic/payload at
 * QoS 0, and DISCONNECTs. No long-lived client, no subscription support — the
 * bare-bones surface in [com.github.itskenny0.r1ha.core.mqtt.MqttPublisher].
 *
 * Field changes persist to DataStore immediately; no separate save step.
 */
@Composable
private fun MqttPanel(
    advanced: com.github.itskenny0.r1ha.core.prefs.AdvancedSettings,
    vm: com.github.itskenny0.r1ha.feature.settings.SettingsViewModel,
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var topic by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("r1/test") }
    var payload by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf("hello from r1") }
    var retain by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var inFlight by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(
            text = "Connect to an MQTT broker and publish a single message. The client is publish-only and one-shot — no subscriptions, no retained-session machinery. Useful for triggering automations on broker-side rules or pushing app state to a broker shared with HA.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "BROKER HOST", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.mqttHost,
            onValueChange = { v -> vm.updateAdvanced { it.copy(mqttHost = v.trim()) } },
            placeholder = "192.168.1.10",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "PORT", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(2.dp))
                com.github.itskenny0.r1ha.ui.components.R1TextField(
                    value = advanced.mqttPort.toString(),
                    onValueChange = { v ->
                        val n = v.toIntOrNull()?.coerceIn(1, 65535)
                        if (n != null) vm.updateAdvanced { it.copy(mqttPort = n) }
                    },
                    placeholder = "1883",
                    monospace = true,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(2f)) {
                DevSwitchRow(
                    label = "TLS",
                    subtitle = "Wrap the socket in SSL. Use port 8883 for the standard mqtts:// endpoint.",
                    checked = advanced.mqttUseTls,
                    onChange = { v -> vm.updateAdvanced { it.copy(mqttUseTls = v) } },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(text = "USERNAME (OPTIONAL)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.mqttUsername,
            onValueChange = { v -> vm.updateAdvanced { it.copy(mqttUsername = v) } },
            placeholder = "homeassistant",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "PASSWORD (OPTIONAL)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.mqttPassword,
            onValueChange = { v -> vm.updateAdvanced { it.copy(mqttPassword = v) } },
            placeholder = "",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "CLIENT ID (OPTIONAL)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.mqttClientId,
            onValueChange = { v -> vm.updateAdvanced { it.copy(mqttClientId = v.trim()) } },
            placeholder = "auto",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(text = "PUBLISH", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        Text(text = "TOPIC", style = R1.labelMicro, color = R1.InkMuted)
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = topic,
            onValueChange = { topic = it.trim() },
            placeholder = "r1/test",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(text = "PAYLOAD", style = R1.labelMicro, color = R1.InkMuted)
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = payload,
            onValueChange = { payload = it },
            placeholder = "hello",
            monospace = true,
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        DevSwitchRow(
            label = "Retain",
            subtitle = "Broker stores the message and replays it to future subscribers of this topic. Common for state topics.",
            checked = retain,
            onChange = { retain = it },
        )
        Spacer(Modifier.height(8.dp))
        val canPublish = advanced.mqttHost.isNotBlank() && topic.isNotBlank() && !inFlight
        com.github.itskenny0.r1ha.ui.components.R1Button(
            text = if (inFlight) "PUBLISHING…" else "PUBLISH",
            onClick = {
                if (canPublish) {
                    inFlight = true
                    scope.launch {
                        val cid = advanced.mqttClientId.ifBlank {
                            "r1ha-${System.currentTimeMillis() and 0xFFFF}"
                        }
                        val result = com.github.itskenny0.r1ha.core.mqtt.MqttPublisher.publish(
                            host = advanced.mqttHost,
                            port = advanced.mqttPort,
                            topic = topic,
                            payload = payload.toByteArray(Charsets.UTF_8),
                            clientId = cid,
                            username = advanced.mqttUsername.ifBlank { null },
                            password = advanced.mqttPassword.ifBlank { null },
                            useTls = advanced.mqttUseTls,
                            retain = retain,
                        )
                        result.fold(
                            onSuccess = {
                                com.github.itskenny0.r1ha.core.util.Toaster.show(
                                    "MQTT published to $topic",
                                )
                            },
                            onFailure = { t ->
                                com.github.itskenny0.r1ha.core.util.Toaster.error(
                                    "MQTT publish failed: ${t.message ?: t::class.java.simpleName}",
                                )
                            },
                        )
                        inFlight = false
                    }
                }
            },
            enabled = canPublish,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * iBeacon advertiser controls. Surfaces the four backing fields (toggle +
 * UUID + major + minor) and routes the toggle through Android 12+'s runtime
 * permission flow when the user flips it on for the first time. The actual
 * advertise lifecycle lives in [com.github.itskenny0.r1ha.core.ibeacon.IBeaconAdvertiser]
 * driven by [com.github.itskenny0.r1ha.App]'s settings observer, so this
 * panel only owns the UI + permission prompt; the advertiser sees the new
 * values the moment they hit DataStore.
 */
@Composable
private fun IBeaconPanel(
    advanced: com.github.itskenny0.r1ha.core.prefs.AdvancedSettings,
    vm: com.github.itskenny0.r1ha.feature.settings.SettingsViewModel,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val permissionLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
        contract = androidx.activity.result.contract.ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            vm.updateAdvanced { it.copy(iBeaconEnabled = true) }
        } else {
            com.github.itskenny0.r1ha.core.util.Toaster.error(
                "BLUETOOTH_ADVERTISE denied; iBeacon stays off",
            )
        }
    }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        DevSwitchRow(
            label = "Advertise iBeacon",
            subtitle = "Broadcast an iBeacon advertisement so HA's iBeacon integration picks the device up as a device_tracker. Asks for BLUETOOTH_ADVERTISE on Android 12+. Off by default.",
            checked = advanced.iBeaconEnabled,
            onChange = { v ->
                if (v) {
                    // Android 12+ requires runtime permission. Hand the launcher
                    // a request; the result branch flips the toggle on success.
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                        !com.github.itskenny0.r1ha.core.ibeacon.IBeaconAdvertiser.hasPermission(context)
                    ) {
                        permissionLauncher.launch(android.Manifest.permission.BLUETOOTH_ADVERTISE)
                    } else {
                        vm.updateAdvanced { it.copy(iBeaconEnabled = true) }
                    }
                } else {
                    vm.updateAdvanced { it.copy(iBeaconEnabled = false) }
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Text(text = "UUID", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = advanced.iBeaconUuid,
            onValueChange = { v -> vm.updateAdvanced { it.copy(iBeaconUuid = v.trim()) } },
            placeholder = "12345678-1234-1234-1234-123456789abc",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        // Major + minor side by side; both are uint16 (0..65535).
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "MAJOR", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(2.dp))
                com.github.itskenny0.r1ha.ui.components.R1TextField(
                    value = advanced.iBeaconMajor.toString(),
                    onValueChange = { v ->
                        val n = v.toIntOrNull()?.coerceIn(0, 65535)
                        if (n != null) vm.updateAdvanced { it.copy(iBeaconMajor = n) }
                    },
                    placeholder = "1",
                    monospace = true,
                )
            }
            Spacer(Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = "MINOR", style = R1.labelMicro, color = R1.InkSoft)
                Spacer(Modifier.height(2.dp))
                com.github.itskenny0.r1ha.ui.components.R1TextField(
                    value = advanced.iBeaconMinor.toString(),
                    onValueChange = { v ->
                        val n = v.toIntOrNull()?.coerceIn(0, 65535) ?: return@R1TextField
                        vm.updateAdvanced { it.copy(iBeaconMinor = n) }
                    },
                    placeholder = "1",
                    monospace = true,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = "HA → Settings → Devices & services → Add integration → iBeacon Tracker, then add this UUID/major/minor combination to make the device show up as a device_tracker.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

/**
 * Live events tail under DevMenu. Wraps [HaRepository.subscribeEvents] with a small
 * fixed-size ring buffer + a START / STOP affordance. Useful when wiring up new
 * HA automations: subscribe to "state_changed" to see exactly which entity_id's
 * state HA published to the bus and when.
 */
@Composable
private fun LiveEventsPanel(haRepository: com.github.itskenny0.r1ha.core.ha.HaRepository) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var eventType by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf("state_changed")
    }
    var subscription by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<com.github.itskenny0.r1ha.core.ha.HaRepository.EventSubscription?>(null)
    }
    var entries by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<List<String>>(emptyList())
    }
    var error by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<String?>(null)
    }
    val isOn = subscription != null
    // Stop the subscription when the panel leaves composition so the screen
    // doesn't leak the WS sub.
    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            scope.launch { subscription?.cancel() }
        }
    }
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(text = "EVENT TYPE (blank = all)", style = R1.labelMicro, color = R1.InkSoft)
        Spacer(Modifier.height(2.dp))
        com.github.itskenny0.r1ha.ui.components.R1TextField(
            value = eventType,
            onValueChange = { eventType = it.lowercase().filter { c -> c.isLetterOrDigit() || c == '_' } },
            placeholder = "state_changed",
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(if (isOn) R1.AccentCool.copy(alpha = 0.18f) else R1.SurfaceMuted)
                    .border(
                        1.dp,
                        if (isOn) R1.AccentCool.copy(alpha = 0.6f) else R1.Hairline,
                        R1.ShapeS,
                    )
                    .r1Pressable(onClick = {
                        if (isOn) {
                            scope.launch {
                                subscription?.cancel()
                                subscription = null
                            }
                        } else {
                            scope.launch {
                                val typeOrNull = eventType.takeIf { it.isNotBlank() }
                                haRepository.subscribeEvents(typeOrNull) { event ->
                                    val time = java.time.Instant.now().toString().take(19)
                                    val short = buildString {
                                        append(time).append(" | ")
                                        append((event["event_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "?")
                                        val data = event["data"] as? kotlinx.serialization.json.JsonObject
                                        val eid = (data?.get("entity_id") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                        if (eid != null) append(" ").append(eid)
                                    }
                                    entries = (listOf(short) + entries).take(200)
                                }.fold(
                                    onSuccess = {
                                        subscription = it
                                        error = null
                                    },
                                    onFailure = { t -> error = t.message ?: t.toString() },
                                )
                            }
                        }
                    })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(
                    text = if (isOn) "STOP" else "START",
                    style = R1.labelMicro,
                    color = if (isOn) R1.StatusAmber else R1.AccentWarm,
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { entries = emptyList() })
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = "CLEAR", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${entries.size} captured",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        if (error != null) {
            Spacer(Modifier.height(4.dp))
            Text(text = error ?: "", style = R1.labelMicro, color = R1.StatusAmber)
        }
        if (entries.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            for (line in entries.take(30)) {
                Text(
                    text = line,
                    style = R1.labelMicro.copy(fontFamily = FontFamily.Monospace),
                    color = R1.Ink,
                    maxLines = 2,
                )
            }
            if (entries.size > 30) {
                Text(
                    text = "(${entries.size - 30} more not shown — clear to reset)",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }
    }
}
