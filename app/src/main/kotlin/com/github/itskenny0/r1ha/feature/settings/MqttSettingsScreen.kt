package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.mqtt.MqttPublisher
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1Row
import com.github.itskenny0.r1ha.ui.components.R1Section
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.launch

/**
 * Top-level MQTT broker config. Surfaced as its own settings subpage rather
 * than under Dev menu so the IoT Camera / IoT Sensors flows have somewhere
 * obvious to send users when their broker isn't yet configured.
 *
 * Backing fields still live on AdvancedSettings for storage compatibility;
 * this screen is a friendlier surface over the same struct so the Dev
 * menu's publish controls keep working off whatever was set here.
 *
 * Layout shape from top to bottom:
 *   1. Connection state pill (IDLE / TESTING / CONNECTED / FAILED) with
 *      the last inline error, never a toast.
 *   2. Broker config block: host / port / username / password / TLS /
 *      client id.
 *   3. Test connection chip + persistent last-result.
 *   4. Per-feature MQTT enablement (which features hit this broker).
 */
@Composable
fun MqttSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val advanced = s.advanced
    val scope = rememberCoroutineScope()

    // Per-session test-button state. Holds the most recent test outcome
    // inline so the user can verify a config edit reached the broker
    // without enabling a sink and watching its status card. Not persisted;
    // the broker either works or it doesn't on every fresh attempt.
    var testState by remember { mutableStateOf<TestState>(TestState.Idle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding()
            .imePadding(),
    ) {
        R1TopBar(title = "MQTT BROKER", onBack = onBack)
        // Centre + width-cap the broker form on tablet / desktop tiers so the
        // fields don't stretch full-bleed. R1 / compact stay Unspecified = fill.
        val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
        val listModifier = if (dimens.capsContentWidth) {
            Modifier
                .fillMaxSize()
                .widthIn(max = dimens.maxContentWidth)
                .align(Alignment.CenterHorizontally)
        } else {
            Modifier.fillMaxSize()
        }
        LazyColumn(modifier = listModifier) {
            item {
                Text(
                    text = "Shared broker config. The IoT Camera Mode " +
                        "feature publishes auto-discovery and frames here; " +
                        "the IoT Sensors Mode feature publishes battery, " +
                        "light, vibration and subscribes to control topics. " +
                        "Dev menu's publish tool talks to the same broker.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.m),
                )
            }

            // ── State pill ────────────────────────────────────────────
            item {
                ConnectionStateCard(
                    configured = advanced.mqttHost.isNotBlank(),
                    host = advanced.mqttHost,
                    port = advanced.mqttPort,
                    tls = advanced.mqttUseTls,
                    auth = advanced.mqttUsername.isNotBlank(),
                    testState = testState,
                )
            }

            // ── Broker config ─────────────────────────────────────────
            item {
                R1Section(title = "Broker") {
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl)) {
                        Text("Host", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = advanced.mqttHost,
                            onValueChange = { v ->
                                vm.updateAdvanced { it.copy(mqttHost = v.trim()) }
                            },
                            placeholder = "192.168.1.10 or broker.example.com",
                            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
                        )
                        Spacer(Modifier.height(R1.space.s))
                        Text("Port", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = advanced.mqttPort.toString(),
                            onValueChange = { v ->
                                val p = v.toIntOrNull() ?: return@R1TextField
                                vm.updateAdvanced { it.copy(mqttPort = p.coerceIn(1, 65535)) }
                            },
                            placeholder = "1883 (plain) or 8883 (TLS)",
                            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
                        )
                        Spacer(Modifier.height(R1.space.s))
                        Text("Username (optional)", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = advanced.mqttUsername,
                            onValueChange = { v ->
                                vm.updateAdvanced { it.copy(mqttUsername = v) }
                            },
                            placeholder = "anonymous if blank",
                            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
                        )
                        Spacer(Modifier.height(R1.space.s))
                        Text("Password (optional)", style = R1.labelMicro, color = R1.InkSoft)
                        MaskedSecretField(
                            value = advanced.mqttPassword,
                            onValueChange = { v ->
                                vm.updateAdvanced { it.copy(mqttPassword = v) }
                            },
                            placeholder = "broker password",
                            modifier = Modifier.padding(top = R1.space.xs),
                        )
                        Spacer(Modifier.height(R1.space.m))
                        Row(
                            modifier = Modifier.semantics(mergeDescendants = true) {
                                contentDescription = SettingsA11y.switchRowDescription(
                                    "Use TLS",
                                    "Wrap the socket with TLS (typically port 8883).",
                                    advanced.mqttUseTls,
                                )
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text("Use TLS", style = R1.bodyEmph, color = R1.Ink)
                                Text(
                                    "Wrap the socket with TLS (typically port 8883).",
                                    style = R1.body,
                                    color = R1.InkMuted,
                                )
                            }
                            R1Switch(
                                checked = advanced.mqttUseTls,
                                onCheckedChange = { v ->
                                    vm.updateAdvanced { it.copy(mqttUseTls = v) }
                                },
                            )
                        }
                        Spacer(Modifier.height(R1.space.s))
                        Text("Client id (optional)", style = R1.labelMicro, color = R1.InkSoft)
                        R1TextField(
                            value = advanced.mqttClientId,
                            onValueChange = { v ->
                                vm.updateAdvanced { it.copy(mqttClientId = v.trim()) }
                            },
                            placeholder = "auto-generated per publish if blank",
                            modifier = Modifier.fillMaxWidth().padding(top = R1.space.xs),
                        )
                    }
                }
            }

            // ── Test connection ───────────────────────────────────────
            item {
                R1Section(title = "Test") {
                    TestConnectionBlock(
                        enabled = advanced.mqttHost.isNotBlank(),
                        state = testState,
                        onRun = {
                            testState = TestState.Running
                            scope.launch {
                                val ts = System.currentTimeMillis()
                                val result = MqttPublisher.publish(
                                    host = advanced.mqttHost,
                                    port = advanced.mqttPort,
                                    topic = "r1ha/diagnostic/test",
                                    payload = "r1ha test ping @ $ts".toByteArray(Charsets.UTF_8),
                                    username = advanced.mqttUsername.ifBlank { null },
                                    password = advanced.mqttPassword.ifBlank { null },
                                    useTls = advanced.mqttUseTls,
                                )
                                testState = result.fold(
                                    onSuccess = {
                                        TestState.Success(
                                            topic = "r1ha/diagnostic/test",
                                            atMillis = System.currentTimeMillis(),
                                        )
                                    },
                                    onFailure = { t ->
                                        TestState.Failure(
                                            reason = t.message ?: t::class.java.simpleName,
                                            atMillis = System.currentTimeMillis(),
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

            // ── Per-feature MQTT enablement ───────────────────────────
            // Hierarchy: broker (above) is the connection; the rows below
            // are the features that USE that connection. Each is a quick
            // toggle / link so the user can see at a glance what's
            // currently writing to this broker.
            item {
                R1Section(title = "Used by") {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(R1.space.xs),
                    ) {
                        FeatureRow(
                            title = "IoT Camera Mode",
                            on = s.iotCamera.enabled && s.iotCamera.mqttEnabled,
                            detail = if (s.iotCamera.mqttEnabled) {
                                "MQTT sink on; publishes discovery and JPEG frames"
                            } else {
                                "Camera mode's MQTT sink is off (configure in IoT Camera Mode)"
                            },
                        )
                        FeatureRow(
                            title = "IoT Sensors Mode",
                            on = s.iotSensors.enabled,
                            detail = if (s.iotSensors.enabled) {
                                "Publishing sensors and subscribed to control topics"
                            } else {
                                "Off (configure in IoT Sensors Mode)"
                            },
                        )
                        FeatureRow(
                            title = "Dev menu publish tool",
                            on = false,
                            detail = "One-shot manual publish for testing topics",
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(R1.MinTarget)) }
        }
    }
}

/** Test-state lattice rendered inline by the test block. */
private sealed interface TestState {
    object Idle : TestState
    object Running : TestState
    data class Success(val topic: String, val atMillis: Long) : TestState
    data class Failure(val reason: String, val atMillis: Long) : TestState
}

@Composable
private fun ConnectionStateCard(
    configured: Boolean,
    host: String,
    port: Int,
    tls: Boolean,
    auth: Boolean,
    testState: TestState,
) {
    val pill = when {
        testState is TestState.Running -> Pill("TESTING", R1.StatusAmber)
        testState is TestState.Success -> Pill("REACHABLE", R1.AccentGreen)
        testState is TestState.Failure -> Pill("FAILED", R1.StatusRed)
        configured -> Pill("CONFIGURED", R1.AccentGreen)
        else -> Pill("NOT CONFIGURED", R1.StatusAmber)
    }
    val borderTint = when {
        testState is TestState.Failure -> R1.StatusRed
        testState is TestState.Success -> R1.AccentGreen
        !configured -> R1.StatusAmber
        else -> R1.Hairline
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl, vertical = R1.space.s)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, borderTint, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            R1Chip(text = pill.label, variant = R1ChipVariant.Pill, tone = pill.tint)
            Spacer(Modifier.weight(1f))
        }
        Spacer(Modifier.height(R1.space.s))
        if (configured) {
            Text(
                text = "$host:$port",
                style = R1.body.copy(fontFamily = FontFamily.Monospace),
                color = R1.Ink,
            )
            Spacer(Modifier.height(R1.space.xxs))
            Text(
                text = buildString {
                    append(if (tls) "TLS" else "PLAIN")
                    append(" · ")
                    append(if (auth) "auth" else "anonymous")
                },
                style = R1.labelMicro,
                color = R1.InkSoft,
            )
        } else {
            Text(
                text = "Fill in the fields below to enable any feature that depends on MQTT.",
                style = R1.body,
                color = R1.InkSoft,
            )
        }
        when (val ts = testState) {
            is TestState.Failure -> {
                Spacer(Modifier.height(R1.space.s))
                Text(
                    text = "LAST ERROR",
                    style = R1.labelMicro,
                    color = R1.StatusRed,
                )
                Text(
                    text = ts.reason,
                    style = R1.body,
                    color = R1.StatusRed,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            else -> Unit
        }
    }
}

private data class Pill(val label: String, val tint: Color)

@Composable
private fun TestConnectionBlock(
    enabled: Boolean,
    state: TestState,
    onRun: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = R1.space.xl),
    ) {
        val running = state is TestState.Running
        val tint = when {
            running -> R1.InkSoft
            !enabled -> R1.InkMuted
            state is TestState.Success -> R1.AccentGreen
            state is TestState.Failure -> R1.StatusRed
            else -> R1.AccentWarm
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(R1.MinTarget)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onClick = { if (enabled && !running) onRun() }),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = when {
                    running -> "TESTING…"
                    state is TestState.Success -> "TEST AGAIN"
                    state is TestState.Failure -> "RETRY"
                    else -> "TEST CONNECTION"
                },
                style = R1.labelMicro,
                color = tint,
            )
        }
        Spacer(Modifier.height(R1.space.s))
        when (state) {
            TestState.Idle -> {
                Text(
                    text = "Sends a tiny payload to r1ha/diagnostic/test and " +
                        "closes the socket. Use mosquitto_sub on the broker " +
                        "side to verify the publish landed.",
                    style = R1.body,
                    color = R1.InkMuted,
                )
            }
            TestState.Running -> {
                Text(
                    text = "Opening socket, sending CONNECT + PUBLISH…",
                    style = R1.body,
                    color = R1.InkSoft,
                )
            }
            is TestState.Success -> {
                Text(
                    text = "SUCCESS",
                    style = R1.labelMicro,
                    color = R1.AccentGreen,
                )
                Text(
                    text = "Published to ${state.topic} at " +
                        formatClock(state.atMillis) +
                        ". Broker accepted the CONNECT + PUBLISH cycle.",
                    style = R1.body,
                    color = R1.InkSoft,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
            is TestState.Failure -> {
                Text(
                    text = "FAILED at " + formatClock(state.atMillis),
                    style = R1.labelMicro,
                    color = R1.StatusRed,
                )
                Text(
                    text = state.reason,
                    style = R1.body,
                    color = R1.StatusRed,
                    modifier = Modifier.padding(top = R1.space.xxs),
                )
            }
        }
    }
}

@Composable
private fun FeatureRow(title: String, on: Boolean, detail: String) {
    R1Row(
        label = title,
        description = detail,
        boxed = true,
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(R1.space.s)
                    .clip(R1.ShapeRound)
                    .background(if (on) R1.AccentGreen else R1.InkMuted),
            )
        },
    )
}

/**
 * Password / secret input that defaults to a masked rendering. The field always
 * holds the real secret; masking is purely visual via a
 * [androidx.compose.ui.text.input.PasswordVisualTransformation], so the secret
 * stays editable (backspace, paste, mid-string edits) while hidden and the stored
 * value is never reconstructed from the dot string. A SHOW / HIDE toggle flips the
 * transformation off and on. [androidx.compose.ui.text.input.KeyboardType.Password]
 * with autocorrect off keeps the IME from rewriting the secret as it's typed.
 *
 * Lives in this file but `internal` so the mTLS keystore-password field in
 * SettingsScreen reuses the same treatment.
 */
@Composable
internal fun MaskedSecretField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
) {
    var revealed by remember { mutableStateOf(false) }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        R1TextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = placeholder,
            visualTransformation = if (revealed) {
                androidx.compose.ui.text.input.VisualTransformation.None
            } else {
                androidx.compose.ui.text.input.PasswordVisualTransformation('•')
            },
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                autoCorrect = false,
                keyboardType = androidx.compose.ui.text.input.KeyboardType.Password,
            ),
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.size(R1.space.s))
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(
                    onClick = { revealed = !revealed },
                    contentDescription = if (revealed) "Hide secret" else "Show secret",
                )
                .padding(horizontal = R1.space.m, vertical = R1.space.s),
        ) {
            Text(
                text = if (revealed) "HIDE" else "SHOW",
                style = R1.labelMicro,
                color = R1.AccentWarm,
            )
        }
    }
}

private fun formatClock(millis: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    val s = cal.get(java.util.Calendar.SECOND)
    return "%02d:%02d:%02d".format(h, m, s)
}
