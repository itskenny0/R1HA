package com.github.itskenny0.r1ha.feature.settings

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.iotsensors.LockAdminReceiver
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Switch
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Settings subpage for IoT Sensors Mode. Master toggle plus per-entity
 * opt-ins, grouped into collapsible categories (BATTERY & POWER,
 * ENVIRONMENT, DEVICE STATE, CONTROLS, ADVANCED). Each entity carries
 * its discovery topic as a small monospace caption so power users can
 * spot which HA entity_id will surface.
 *
 * Two privileged controls (brightness, lock-screen) need permissions
 * the master toggle can't grant for the user. Brightness needs
 * WRITE_SETTINGS via ACTION_MANAGE_WRITE_SETTINGS; lock-screen needs
 * Device Admin via ACTION_ADD_DEVICE_ADMIN. Each renders a permission
 * banner with a GRANT chip when the entity is on but the grant is
 * missing. The discovery payload still publishes either way; the
 * command handler logs and notifies if a grant is missing at fire time.
 */
@Composable
fun IotSensorsSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    /** "MQTT not configured" warning routes the user straight to the broker
     *  setup screen; same UX as the IoT Camera settings page. */
    onOpenMqttSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val sensors = s.iotSensors
    val context = LocalContext.current

    // Permission status is re-read every recomposition; both calls are
    // local ContentProvider / system-service queries (cheap), and neither
    // has a callback API we could subscribe to, so polling on entry +
    // post-grant returns to the screen is the simplest live model.
    val canWriteBrightness = Settings.System.canWrite(context)
    val dpm = remember {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
    }
    val adminComp = remember { ComponentName(context, LockAdminReceiver::class.java) }
    val deviceAdminGranted = dpm?.isAdminActive(adminComp) == true

    val mqttConfigured = s.advanced.mqttHost.isNotBlank()
    val nodeId = sensors.nodeId.ifBlank { "default" }

    // Per-section expansion state. Defaults: BATTERY + CONTROLS open, the
    // rest closed; matches the most-touched sections on first entry without
    // dumping a wall of toggles on the user.
    var expanded by remember {
        mutableStateOf(setOf(SectionKey.BATTERY, SectionKey.CONTROLS))
    }
    val toggle: (SectionKey) -> Unit = { k ->
        expanded = if (k in expanded) expanded - k else expanded + k
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg),
    ) {
        R1TopBar(title = "IOT SENSORS MODE", onBack = onBack)
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item {
                Text(
                    text = "Publishes device sensors (battery, light, " +
                        "vibration) and exposes controls (flashlight, " +
                        "brightness, volume, lock) to Home Assistant via " +
                        "MQTT auto-discovery. Uses the same broker as " +
                        "IoT Camera Mode; configure under Settings, " +
                        "MQTT broker.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            if (!mqttConfigured) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 4.dp)
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.StatusAmber, R1.ShapeS)
                            .r1Pressable(onClick = onOpenMqttSettings)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                    ) {
                        Column {
                            Text(
                                text = "MQTT BROKER NOT CONFIGURED",
                                style = R1.labelMicro,
                                color = R1.StatusAmber,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = "Tap to configure. Sensors mode won't connect to " +
                                    "the broker until host, port, and credentials are set.",
                                style = R1.body,
                                color = R1.InkSoft,
                            )
                        }
                    }
                }
            }
            item {
                MasterSwitchRow(
                    label = "ENABLED",
                    description = if (sensors.enabled) {
                        "Publishing to ${s.advanced.mqttHost.ifBlank { "(broker not set)" }} " +
                            "as r1ha/$nodeId"
                    } else {
                        "Off. Toggle on to start the foreground service."
                    },
                    checked = sensors.enabled,
                    onCheckedChange = { vm.updateIotSensors { it.copy(enabled = !it.enabled) } },
                )
            }

            // ── BATTERY & POWER ────────────────────────────────────────
            categorySection(
                key = SectionKey.BATTERY,
                label = "BATTERY & POWER",
                summary = sensorCountSummary(
                    sensors.publishBattery,
                    sensors.publishCharging,
                ),
                expanded = expanded,
                onToggle = toggle,
            )
            if (SectionKey.BATTERY in expanded) {
                item {
                    SensorRow(
                        title = "Battery level",
                        topic = "sensor.${nodeId}_battery",
                        meta = "percent, measurement",
                        checked = sensors.publishBattery,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishBattery = !it.publishBattery) } },
                    )
                }
                item {
                    SensorRow(
                        title = "Charging state",
                        topic = "binary_sensor.${nodeId}_charging",
                        meta = "device_class: battery_charging",
                        checked = sensors.publishCharging,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishCharging = !it.publishCharging) } },
                    )
                }
            }

            // ── ENVIRONMENT ────────────────────────────────────────────
            categorySection(
                key = SectionKey.ENVIRONMENT,
                label = "ENVIRONMENT",
                summary = sensorCountSummary(
                    sensors.publishLightSensor,
                    sensors.publishVibration,
                ),
                expanded = expanded,
                onToggle = toggle,
            )
            if (SectionKey.ENVIRONMENT in expanded) {
                item {
                    SensorRow(
                        title = "Ambient light",
                        topic = "sensor.${nodeId}_illuminance",
                        meta = "lux. Off on devices without an ALS.",
                        checked = sensors.publishLightSensor,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishLightSensor = !it.publishLightSensor) } },
                    )
                }
                item {
                    SensorRow(
                        title = "Vibration",
                        topic = "binary_sensor.${nodeId}_vibration",
                        meta = "software detector from accelerometer; raise " +
                            "threshold if a still device triggers it.",
                        checked = sensors.publishVibration,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishVibration = !it.publishVibration) } },
                    )
                }
                if (sensors.publishVibration) {
                    item {
                        NumberStepperRow(
                            label = "Vibration threshold",
                            unit = "g",
                            value = sensors.vibrationThresholdG,
                            step = 0.25f,
                            min = 0.25f,
                            max = 5f,
                            onChange = { v -> vm.updateIotSensors { it.copy(vibrationThresholdG = v) } },
                        )
                    }
                }
            }

            // ── DEVICE STATE ───────────────────────────────────────────
            categorySection(
                key = SectionKey.DEVICE,
                label = "DEVICE STATE",
                summary = sensorCountSummary(
                    sensors.publishScreenOn,
                    sensors.publishWifiSsid,
                ),
                expanded = expanded,
                onToggle = toggle,
            )
            if (SectionKey.DEVICE in expanded) {
                item {
                    SensorRow(
                        title = "Screen on / off",
                        topic = "binary_sensor.${nodeId}_screen",
                        meta = "follows ACTION_SCREEN_ON / OFF.",
                        checked = sensors.publishScreenOn,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishScreenOn = !it.publishScreenOn) } },
                    )
                }
                item {
                    SensorRow(
                        title = "WiFi SSID",
                        topic = "sensor.${nodeId}_ssid",
                        meta = "diagnostic. Off by default; SSID can " +
                            "identify your home network.",
                        checked = sensors.publishWifiSsid,
                        onCheckedChange = { vm.updateIotSensors { it.copy(publishWifiSsid = !it.publishWifiSsid) } },
                    )
                }
                item {
                    NumberStepperRow(
                        label = "Publish interval",
                        unit = "s",
                        value = sensors.publishIntervalSec.toFloat(),
                        step = 15f,
                        min = 15f,
                        max = 600f,
                        onChange = { v ->
                            vm.updateIotSensors { it.copy(publishIntervalSec = v.toInt().coerceAtLeast(5)) }
                        },
                    )
                }
            }

            // ── CONTROLS (HA → DEVICE) ─────────────────────────────────
            categorySection(
                key = SectionKey.CONTROLS,
                label = "CONTROLS (HA → DEVICE)",
                summary = sensorCountSummary(
                    sensors.controlFlashlight,
                    sensors.controlBrightness,
                    sensors.controlVolume,
                    sensors.controlLockScreen,
                ),
                expanded = expanded,
                onToggle = toggle,
            )
            if (SectionKey.CONTROLS in expanded) {
                item {
                    SensorRow(
                        title = "Flashlight",
                        topic = "switch.${nodeId}_flashlight",
                        meta = "no extra permission required.",
                        checked = sensors.controlFlashlight,
                        onCheckedChange = { vm.updateIotSensors { it.copy(controlFlashlight = !it.controlFlashlight) } },
                    )
                }
                item {
                    SensorRow(
                        title = "Screen brightness",
                        topic = "number.${nodeId}_brightness",
                        meta = "0 to 100. Requires Modify system settings.",
                        checked = sensors.controlBrightness,
                        onCheckedChange = { vm.updateIotSensors { it.copy(controlBrightness = !it.controlBrightness) } },
                    )
                }
                if (sensors.controlBrightness && !canWriteBrightness) {
                    item {
                        PermissionGrantBanner(
                            message = "Brightness control needs \"Modify system settings\". " +
                                "Tap GRANT to open the system page and toggle R1HA in.",
                            cta = "GRANT",
                            onClick = {
                                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                                    .setData(Uri.parse("package:${context.packageName}"))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                        )
                    }
                }
                item {
                    SensorRow(
                        title = "Media volume",
                        topic = "number.${nodeId}_volume",
                        meta = "0 to 100. STREAM_MUSIC.",
                        checked = sensors.controlVolume,
                        onCheckedChange = { vm.updateIotSensors { it.copy(controlVolume = !it.controlVolume) } },
                    )
                }
                item {
                    SensorRow(
                        title = "Lock screen",
                        topic = "button.${nodeId}_lock_screen",
                        meta = "needs Device Admin (force-lock). Revoke " +
                            "any time in system Security settings.",
                        checked = sensors.controlLockScreen,
                        onCheckedChange = { vm.updateIotSensors { it.copy(controlLockScreen = !it.controlLockScreen) } },
                    )
                }
                if (sensors.controlLockScreen && !deviceAdminGranted) {
                    item {
                        PermissionGrantBanner(
                            message = "Lock screen control needs the Device Admin " +
                                "grant. Android shows the policy summary " +
                                "(force-lock only) on the next screen.",
                            cta = "GRANT ADMIN",
                            onClick = {
                                val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                                    .putExtra(
                                        android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                                        adminComp,
                                    )
                                    .putExtra(
                                        android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                                        "Allow R1HA to lock the screen when " +
                                            "Home Assistant fires the lock_screen button.",
                                    )
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            },
                        )
                    }
                }
            }

            // ── ADVANCED ───────────────────────────────────────────────
            categorySection(
                key = SectionKey.ADVANCED,
                label = "ADVANCED",
                summary = null,
                expanded = expanded,
                onToggle = toggle,
            )
            if (SectionKey.ADVANCED in expanded) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                    ) {
                        Text("Node id", style = R1.labelMicro, color = R1.InkSoft)
                        Text(
                            text = sensors.nodeId.ifBlank { "(assigned on first enable)" },
                            style = R1.body.copy(fontFamily = FontFamily.Monospace),
                            color = R1.Ink,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "Topics: r1ha/$nodeId/<entity>/state. " +
                                "Shared with the camera's node id so HA groups " +
                                "everything under one device.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 8.dp),
                    ) {
                        Text("Discovery prefix", style = R1.labelMicro, color = R1.InkSoft)
                        Text(
                            text = sensors.discoveryPrefix.ifBlank { "homeassistant" },
                            style = R1.body.copy(fontFamily = FontFamily.Monospace),
                            color = R1.Ink,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                        Text(
                            text = "Default \"homeassistant\" matches HA's out-of-the-box config.",
                            style = R1.labelMicro,
                            color = R1.InkMuted,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(48.dp)) }
        }
    }
}

/** Stable identity for each collapsible category. */
private enum class SectionKey { BATTERY, ENVIRONMENT, DEVICE, CONTROLS, ADVANCED }

/** Render a tappable category header that toggles expansion. Wrapped as a
 *  LazyListScope extension so call sites stay flat in the parent LazyColumn. */
private fun androidx.compose.foundation.lazy.LazyListScope.categorySection(
    key: SectionKey,
    label: String,
    summary: String?,
    expanded: Set<SectionKey>,
    onToggle: (SectionKey) -> Unit,
) {
    item("section_$key") {
        CategoryHeader(
            label = label,
            summary = summary,
            expanded = key in expanded,
            onToggle = { onToggle(key) },
        )
    }
}

/** "3/4 on" style summary the category header shows when collapsed so the
 *  user can tell at a glance which categories carry active publishers. */
private fun sensorCountSummary(vararg flags: Boolean): String? {
    val total = flags.size
    val on = flags.count { it }
    if (total == 0) return null
    return "$on/$total on"
}

@Composable
private fun CategoryHeader(
    label: String,
    summary: String?,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .r1Pressable(onClick = onToggle)
            .padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = R1.sectionHeader, color = R1.AccentWarm)
        if (summary != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = summary,
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(R1.Hairline),
        )
        Spacer(Modifier.width(10.dp))
        Box(
            modifier = Modifier
                .size(48.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (expanded) "−" else "+",
                style = R1.bodyEmph,
                color = R1.InkSoft,
            )
        }
    }
}

@Composable
private fun MasterSwitchRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(
                1.dp,
                if (checked) R1.AccentGreen.copy(alpha = 0.45f) else R1.Hairline,
                R1.ShapeS,
            )
            .r1Pressable(onClick = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(description, style = R1.body, color = R1.InkMuted)
        }
        R1Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun SensorRow(
    title: String,
    topic: String,
    meta: String,
    checked: Boolean,
    onCheckedChange: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onCheckedChange)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = R1.bodyEmph, color = R1.Ink)
            Spacer(Modifier.height(2.dp))
            Text(
                text = topic,
                style = R1.numeralS,
                color = if (checked) R1.AccentWarm else R1.InkMuted,
            )
            Text(
                text = meta,
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        R1Switch(checked = checked, onCheckedChange = { onCheckedChange() })
    }
}

@Composable
private fun NumberStepperRow(
    label: String,
    unit: String,
    value: Float,
    step: Float,
    min: Float,
    max: Float,
    onChange: (Float) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = R1.bodyEmph, color = R1.Ink)
            Text(
                text = "${"%.2f".format(value).trimEnd('0').trimEnd('.')} $unit",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        StepperButton(label = "−", enabled = value > min) {
            onChange((value - step).coerceAtLeast(min))
        }
        Spacer(Modifier.width(8.dp))
        StepperButton(label = "+", enabled = value < max) {
            onChange((value + step).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepperButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(R1.ShapeS)
            .background(if (enabled) R1.Bg else R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = { if (enabled) onClick() }),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = R1.bodyEmph, color = if (enabled) R1.Ink else R1.InkMuted)
    }
}

@Composable
private fun PermissionGrantBanner(
    message: String,
    cta: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 4.dp)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.StatusAmber, R1.ShapeS)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = R1.body,
            color = R1.InkSoft,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .height(48.dp)
                .clip(R1.ShapeS)
                .background(R1.Bg)
                .border(1.dp, R1.StatusAmber, R1.ShapeS)
                .r1Pressable(onClick = onClick)
                .padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(cta, style = R1.labelMicro, color = R1.StatusAmber)
        }
    }
}
