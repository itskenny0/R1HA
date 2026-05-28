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
 * Settings subpage for IoT Sensors Mode. Master toggle + per-entity opt-ins,
 * grouped into "sensors HA reads from us" and "controls HA writes to us".
 * Each entity is its own switch so the user can keep, say, the battery on
 * HA while leaving the vibration detector off (which is the default
 * because a phone-on-a-desk reports constant micro-vibrations).
 *
 * The two privileged controls (brightness, lock-screen) need permissions
 * the master toggle can't grant for the user:
 *  - Brightness needs WRITE_SETTINGS, granted via
 *    ACTION_MANAGE_WRITE_SETTINGS. We surface a banner with a GRANT button
 *    when the user has the entity on but the permission missing.
 *  - Lock screen needs Device Admin, granted via ACTION_ADD_DEVICE_ADMIN.
 *    Same banner pattern.
 *
 * Both privileged controls still appear under HA discovery even when the
 * grant is missing; the command handler logs + posts a notification so the
 * user knows why their slider isn't doing anything.
 */
@Composable
fun IotSensorsSettingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    /** "MQTT not configured" warning routes the user straight to the broker
     *  setup screen — same UX as the IoT Camera settings page. */
    onOpenMqttSettings: () -> Unit,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    val sensors = s.iotSensors
    val context = LocalContext.current

    // Permission status is read once per screen entry. The fine-grained
    // `Settings.System.canWrite` and `DevicePolicyManager.isAdminActive`
    // calls don't have callback APIs we can subscribe to, so we re-read
    // them on every recomposition (cheap; both are local ContentProvider
    // / system-service queries). If the user grants outside our flow,
    // returning to the screen reflects it.
    val canWriteBrightness = Settings.System.canWrite(context)
    val dpm = remember {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
    }
    val adminComp = remember { ComponentName(context, LockAdminReceiver::class.java) }
    val deviceAdminGranted = dpm?.isAdminActive(adminComp) == true

    val mqttConfigured = s.advanced.mqttHost.isNotBlank()

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
                        "MQTT auto-discovery. Same broker as IoT Camera " +
                        "Mode; configure under Settings → MQTT broker.",
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
            }
            // MQTT prerequisite banner — same pattern as IotCameraSettingsScreen.
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
                                    "the broker until host/port/credentials are set.",
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
                            "as r1ha/${sensors.nodeId.ifBlank { "default" }}"
                    } else {
                        "Off. Toggle on to start the foreground service."
                    },
                    checked = sensors.enabled,
                    onCheckedChange = { vm.updateIotSensors { it.copy(enabled = !it.enabled) } },
                )
            }
            item { SectionHeader("READ-ONLY SENSORS") }
            item {
                ToggleRow(
                    title = "Battery",
                    subtitle = "sensor.<id>_battery, %",
                    checked = sensors.publishBattery,
                    onCheckedChange = { vm.updateIotSensors { it.copy(publishBattery = !it.publishBattery) } },
                )
            }
            item {
                ToggleRow(
                    title = "Charging",
                    subtitle = "binary_sensor.<id>_charging",
                    checked = sensors.publishCharging,
                    onCheckedChange = { vm.updateIotSensors { it.copy(publishCharging = !it.publishCharging) } },
                )
            }
            item {
                ToggleRow(
                    title = "Light sensor",
                    subtitle = "sensor.<id>_illuminance, lux. Off on " +
                        "devices without an ambient-light sensor.",
                    checked = sensors.publishLightSensor,
                    onCheckedChange = { vm.updateIotSensors { it.copy(publishLightSensor = !it.publishLightSensor) } },
                )
            }
            item {
                ToggleRow(
                    title = "Vibration",
                    subtitle = "binary_sensor.<id>_vibration. Software " +
                        "detector from the accelerometer. Raise the " +
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
            item {
                ToggleRow(
                    title = "Screen on/off",
                    subtitle = "binary_sensor.<id>_screen, follows the " +
                        "ACTION_SCREEN_ON / OFF broadcast.",
                    checked = sensors.publishScreenOn,
                    onCheckedChange = { vm.updateIotSensors { it.copy(publishScreenOn = !it.publishScreenOn) } },
                )
            }
            item {
                ToggleRow(
                    title = "WiFi SSID",
                    subtitle = "sensor.<id>_ssid, diagnostic. Off by " +
                        "default: SSID can identify your home network.",
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
            item { SectionHeader("CONTROLS (HA → DEVICE)") }
            item {
                ToggleRow(
                    title = "Flashlight",
                    subtitle = "switch.<id>_flashlight. No extra permission needed.",
                    checked = sensors.controlFlashlight,
                    onCheckedChange = { vm.updateIotSensors { it.copy(controlFlashlight = !it.controlFlashlight) } },
                )
            }
            item {
                ToggleRow(
                    title = "Screen brightness",
                    subtitle = "number.<id>_brightness, 0-100. Needs " +
                        "\"Modify system settings\" permission.",
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
                ToggleRow(
                    title = "Media volume",
                    subtitle = "number.<id>_volume, 0-100.",
                    checked = sensors.controlVolume,
                    onCheckedChange = { vm.updateIotSensors { it.copy(controlVolume = !it.controlVolume) } },
                )
            }
            item {
                ToggleRow(
                    title = "Lock screen",
                    subtitle = "button.<id>_lock_screen. Needs Device " +
                        "Admin so we can call lockNow(). Revoke any time " +
                        "from Settings → Security.",
                    checked = sensors.controlLockScreen,
                    onCheckedChange = { vm.updateIotSensors { it.copy(controlLockScreen = !it.controlLockScreen) } },
                )
            }
            if (sensors.controlLockScreen && !deviceAdminGranted) {
                item {
                    PermissionGrantBanner(
                        message = "Lock screen control needs the Device Admin " +
                            "grant. Android prompts you with the policy " +
                            "summary (force-lock only) on the next screen.",
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
            item { SectionHeader("ADVANCED") }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text("Node id", style = R1.labelMicro, color = R1.InkSoft)
                    Text(
                        text = sensors.nodeId.ifBlank { "(assigned on first enable)" },
                        style = R1.body,
                        color = R1.Ink,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text(
                        text = "Topics: r1ha/${sensors.nodeId.ifBlank { "default" }}/<entity>/state. " +
                            "Shared with the camera's node id so HA groups everything under one device.",
                        style = R1.labelMicro,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
                    Text("Discovery prefix", style = R1.labelMicro, color = R1.InkSoft)
                    Text(
                        text = sensors.discoveryPrefix.ifBlank { "homeassistant" },
                        style = R1.body,
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
            item { Spacer(Modifier.height(48.dp)) }
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
            .border(1.dp, R1.Hairline, R1.ShapeS)
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
private fun ToggleRow(
    title: String,
    subtitle: String,
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
            Text(subtitle, style = R1.labelMicro, color = R1.InkMuted)
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
        StepperButton(label = "-") {
            onChange((value - step).coerceAtLeast(min))
        }
        Spacer(Modifier.width(8.dp))
        StepperButton(label = "+") {
            onChange((value + step).coerceAtMost(max))
        }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(R1.Bg)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = R1.bodyEmph, color = R1.Ink)
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
                .clip(R1.ShapeS)
                .background(R1.Bg)
                .border(1.dp, R1.StatusAmber, R1.ShapeS)
                .r1Pressable(onClick = onClick)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Text(cta, style = R1.labelMicro, color = R1.StatusAmber)
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = R1.labelMicro,
        color = R1.InkSoft,
        modifier = Modifier.padding(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 4.dp),
    )
}
