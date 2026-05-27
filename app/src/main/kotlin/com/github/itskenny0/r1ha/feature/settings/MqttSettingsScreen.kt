package com.github.itskenny0.r1ha.feature.settings

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TextField
import com.github.itskenny0.r1ha.ui.components.R1TopBar

/**
 * Top-level MQTT broker config. Lifted out of the Dev menu's one-shot
 * publish surface so the IoT Camera Mode flow has somewhere obvious to
 * send users when their broker isn't configured yet — burying broker
 * credentials under "Dev menu" suggested a power-user experiment when
 * for the IoT Camera path it's a required step.
 *
 * Backing fields still live on [com.github.itskenny0.r1ha.core.prefs.AdvancedSettings]
 * for storage compatibility; this screen is a friendlier surface over the
 * same struct so a user who configured MQTT here will also see it work
 * in the Dev menu's publish controls without reconfiguring.
 *
 * Consumers list at the bottom is informational — saves the user the
 * forensic "what's using this?" question after they edit.
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        R1TopBar(title = "MQTT BROKER", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Shared MQTT broker config. Same broker your Home " +
                        "Assistant is connected to — the IoT Camera Mode " +
                        "feature publishes auto-discovery + frames here, and " +
                        "the Dev menu's publish tool talks to it too.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            item {
                val configured = advanced.mqttHost.isNotBlank()
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 6.dp)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(
                            1.dp,
                            if (configured) R1.Hairline else R1.StatusAmber,
                            R1.ShapeS,
                        )
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                ) {
                    Text(
                        text = if (configured) "CONFIGURED" else "NOT CONFIGURED",
                        style = R1.labelMicro,
                        color = if (configured) R1.AccentGreen else R1.StatusAmber,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (configured) {
                            "Host: ${advanced.mqttHost}:${advanced.mqttPort} · " +
                                (if (advanced.mqttUseTls) "TLS · " else "") +
                                (if (advanced.mqttUsername.isNotBlank()) "auth" else "anonymous")
                        } else {
                            "Fill in the fields below to enable any feature that depends on MQTT."
                        },
                        style = R1.body,
                        color = R1.InkSoft,
                    )
                }
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text("Host", style = R1.labelMicro, color = R1.InkSoft)
                    R1TextField(
                        value = advanced.mqttHost,
                        onValueChange = { v ->
                            vm.updateAdvanced { it.copy(mqttHost = v.trim()) }
                        },
                        placeholder = "192.168.1.10 or broker.example.com",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Port", style = R1.labelMicro, color = R1.InkSoft)
                    R1TextField(
                        value = advanced.mqttPort.toString(),
                        onValueChange = { v ->
                            val p = v.toIntOrNull() ?: return@R1TextField
                            vm.updateAdvanced { it.copy(mqttPort = p.coerceIn(1, 65535)) }
                        },
                        placeholder = "1883 (plain) or 8883 (TLS)",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Username (optional)", style = R1.labelMicro, color = R1.InkSoft)
                    R1TextField(
                        value = advanced.mqttUsername,
                        onValueChange = { v ->
                            vm.updateAdvanced { it.copy(mqttUsername = v) }
                        },
                        placeholder = "anonymous if blank",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Password (optional)", style = R1.labelMicro, color = R1.InkSoft)
                    R1TextField(
                        value = advanced.mqttPassword,
                        onValueChange = { v ->
                            vm.updateAdvanced { it.copy(mqttPassword = v) }
                        },
                        placeholder = "broker password",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
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
                    Spacer(Modifier.height(8.dp))
                    Text("Client id (optional)", style = R1.labelMicro, color = R1.InkSoft)
                    R1TextField(
                        value = advanced.mqttClientId,
                        onValueChange = { v ->
                            vm.updateAdvanced { it.copy(mqttClientId = v.trim()) }
                        },
                        placeholder = "auto-generated per publish if blank",
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    )
                }
            }
            item {
                Text(
                    text = "USED BY",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 4.dp),
                )
                Text(
                    text = "· IoT Camera Mode — publishes discovery + frames\n" +
                        "· Dev menu → MQTT — one-shot publish for testing",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 24.dp),
                )
            }
            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}
