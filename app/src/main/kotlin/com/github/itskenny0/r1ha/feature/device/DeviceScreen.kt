package com.github.itskenny0.r1ha.feature.device

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.IotSensorsSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.RelativeTimeLabel
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent
import java.time.Instant

/**
 * Device surface — the host device's own status (the R1, or whichever
 * phone is running the app). Two roles share this screen:
 *
 *  - Local controls: slider for the brightness / volume readings, toggle
 *    for the flashlight. These affect THIS device only, never HA.
 *  - Companion-style mirror: when IoT Sensors Mode is on the device
 *    publishes battery, charging, light, screen and (optionally) SSID to
 *    Home Assistant as MQTT-discovered entities, the same way the official
 *    HA companion app exposes a phone's sensors. The MIRRORED TO HA card
 *    reflects exactly which of those sensors are live so the user can read
 *    the device's HA-facing identity without leaving for the settings
 *    screen.
 *
 * The screen is a vertical scroll so adding more local controls in the
 * future doesn't have to fight for chrome space.
 */
@Composable
fun DeviceScreen(
    settings: SettingsRepository,
    wheelInput: WheelInput,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as App
    val vm: DeviceViewModel = viewModel(factory = DeviceViewModel.factory(app))
    val ui by vm.ui.collectAsState()
    val scrollState = rememberScrollState()
    // Pulled from settings so we can hide the "not exposed to HA" banner
    // when IoT Sensors Mode is publishing this device to HA — the banner
    // would otherwise contradict reality.
    val appSettings by settings.settings.collectAsState(initial = com.github.itskenny0.r1ha.core.prefs.AppSettings())
    val iot = appSettings.iotSensors
    val sensorsExposed = iot.enabled
    WheelScrollForScrollState(wheelInput = wheelInput, scrollState = scrollState, settings = settings)

    // Volume + flashlight + battery can be changed from outside our
    // app (system volume key, another app using the torch). Refresh
    // every 5 s while the screen is open so the displayed values
    // stay current.
    AutoRefresh(everyMillis = 5_000L) { vm.refresh() }

    // Bind the activity's per-window brightness setter so the
    // brightness slider can take effect immediately. Cleared on
    // disposal so we never leak a reference to the host activity
    // after navigating away.
    val activity = remember(context) { context as? android.app.Activity }
    DisposableEffect(activity) {
        if (activity != null) {
            vm.bindWindowBrightnessApplier { fraction ->
                val params = activity.window.attributes
                params.screenBrightness = fraction
                activity.window.attributes = params
            }
        }
        onDispose { vm.unbindWindowBrightness() }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(R1.Bg)
            .systemBarsPadding(),
    ) {
        R1TopBar(
            title = "DEVICE",
            onBack = onBack,
            action = {
                Box(
                    modifier = Modifier
                        .heightIn(min = R1.MinTarget)
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() }, contentDescription = "Refresh device status")
                        .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(text = "REFRESH", style = R1.labelMicro, color = R1.InkSoft)
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = R1.space.m, vertical = R1.space.s)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        // Flip the framing when IoT Sensors Mode is on — same
                        // line slot, opposite meaning, so the label always
                        // matches the device's actual exposure to HA.
                        text = if (sensorsExposed) "PUBLISHED TO HA · IoT SENSORS MODE"
                        else "LOCAL · NOT EXPOSED TO HA",
                        style = R1.labelMicro,
                        color = if (sensorsExposed) R1.AccentGreen else R1.InkMuted,
                        modifier = Modifier.weight(1f),
                    )
                    // The 5 s AutoRefresh stamps lastReadAtMillis on every read,
                    // so surfacing it gives the companion-style "last updated"
                    // read-out the values are otherwise silent about.
                    if (ui.lastReadAtMillis > 0L) {
                        RelativeTimeLabel(
                            at = Instant.ofEpochMilli(ui.lastReadAtMillis),
                            color = R1.InkMuted,
                            style = R1.labelMicro,
                        )
                    }
                }
                BatteryCard(ui)
                BrightnessCard(
                    pct = ui.brightnessPct,
                    systemPct = ui.systemBrightnessPct,
                    onChange = { vm.setBrightness(it) },
                    onReleaseToSystem = { vm.setBrightness(-1) },
                    onOpenSystem = { vm.openSystemDisplaySettings() },
                )
                VolumeCard(
                    label = "MEDIA",
                    pct = ui.mediaVolumePct,
                    onChange = { vm.setMediaVolume(it) },
                )
                VolumeCard(
                    label = "NOTIFICATION",
                    pct = ui.notificationVolumePct,
                    onChange = { vm.setNotificationVolume(it) },
                )
                VolumeCard(
                    label = "ALARM",
                    pct = ui.alarmVolumePct,
                    onChange = { vm.setAlarmVolume(it) },
                )
                if (ui.flashlightAvailable) {
                    FlashlightCard(on = ui.flashlightOn, onToggle = { vm.toggleFlashlight() })
                }
                NetworkCard(ssid = ui.wifiSsid, onOpenWifi = { vm.openWifiSettings() })
                if (sensorsExposed) {
                    MirroredSensorsCard(iot = iot, ui = ui)
                }
                Spacer(Modifier.size(R1.space.xl))
            }
        } // AdaptiveContent
    }
}

@Composable
private fun BatteryCard(ui: DeviceViewModel.UiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "BATTERY", style = R1.labelMicro, color = R1.InkSoft)
            // Per-band tint matching the rest of the app's battery
            // language — red < 10, amber < 25, ink otherwise.
            val tint = when {
                ui.batteryPct < 0 -> R1.InkMuted
                ui.batteryPct < 10 -> R1.StatusRed
                ui.batteryPct < 25 -> R1.StatusAmber
                else -> R1.Ink
            }
            Text(
                text = if (ui.batteryPct >= 0) "${ui.batteryPct}%" else "—",
                style = R1.numeralXl.copy(fontWeight = FontWeight.SemiBold),
                color = tint,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = ui.batteryStatus.ifBlank { "—" },
                style = R1.labelMicro,
                color = if (ui.isCharging) R1.AccentGreen else R1.InkSoft,
            )
            if (ui.isCharging) {
                Spacer(Modifier.height(R1.space.xxs))
                // Hand-drawn bolt (filled path) so the colour follows R1.AccentGreen.
                // The ⚡ emoji shipped its own yellow tint that clashed with the green
                // "CHARGING" label above it on the same card.
                com.github.itskenny0.r1ha.ui.components.ChargingBoltGlyph(
                    size = R1.space.l - R1.space.xxs,
                    tint = R1.AccentGreen,
                    modifier = Modifier.semantics { contentDescription = "Charging" },
                )
            }
        }
    }
}

@Composable
private fun BrightnessCard(
    pct: Int,
    systemPct: Int,
    onChange: (Int) -> Unit,
    onReleaseToSystem: () -> Unit,
    onOpenSystem: () -> Unit,
) {
    // pct < 0 means we're following the system brightness (no per-window override
    // applied). In that case the slider position is the SYSTEM brightness — read
    // from Settings.System.SCREEN_BRIGHTNESS — so the slider reflects what the
    // user is actually looking at instead of snapping to 50% the moment they touch
    // it. RESET releases the override after the user has dragged it.
    val isOverride = pct >= 0
    val sliderPosition = if (isOverride) pct else systemPct
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "SCREEN BRIGHTNESS", style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.weight(1f))
            Text(
                // FOLLOW SYSTEM mode shows the SYSTEM value alongside the label so
                // the user can read the current brightness without dragging the
                // slider into override mode just to find out.
                text = if (isOverride) "${pct}%" else "FOLLOW SYSTEM · ${systemPct}%",
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (isOverride) R1.AccentWarm else R1.InkSoft,
            )
        }
        Slider(
            value = sliderPosition.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                // Muted thumb + track when the slider is reflecting system brightness
                // (no override active) so it doesn't look like the user is in control.
                // The moment they drag, it switches into override mode and the warm
                // accent comes back through the recomposition.
                thumbColor = if (isOverride) R1.AccentWarm else R1.InkSoft,
                activeTrackColor = if (isOverride) R1.AccentWarm else R1.InkSoft,
                inactiveTrackColor = R1.Hairline,
            ),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Per-app only. Leaves system brightness untouched.",
                style = R1.labelMicro,
                color = R1.InkMuted,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(
                        onClick = onReleaseToSystem,
                        contentDescription = "Release brightness override, follow system",
                    )
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "RESET", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(R1.space.xs + R1.space.xxs))
            Box(
                modifier = Modifier
                    .heightIn(min = R1.MinTarget)
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(
                        onClick = onOpenSystem,
                        contentDescription = "Open system display settings",
                    )
                    .padding(horizontal = R1.space.s, vertical = R1.space.xs),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = "SYSTEM", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
    }
}

@Composable
private fun VolumeCard(label: String, pct: Int, onChange: (Int) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "$label VOLUME", style = R1.labelMicro, color = R1.InkSoft, modifier = Modifier.weight(1f))
            Text(
                text = "${pct}%",
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (pct > 0) R1.AccentWarm else R1.InkMuted,
            )
        }
        Slider(
            value = pct.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = R1.AccentWarm,
                activeTrackColor = R1.AccentWarm,
                inactiveTrackColor = R1.Hairline,
            ),
        )
    }
}

@Composable
private fun FlashlightCard(on: Boolean, onToggle: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(
                onClick = onToggle,
                contentDescription = if (on) "Flashlight on, tap to turn off" else "Flashlight off, tap to turn on",
            )
            .padding(horizontal = R1.space.l, vertical = R1.space.m + R1.space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hand-drawn glyph (not the 🔦 emoji) so the icon stays monochrome and reads at
        // hairline weight against the surrounding chrome — the colour-emoji font rendered
        // a chunky orange torch with its own drop-shadow that clashed with everything else
        // on the screen.
        com.github.itskenny0.r1ha.ui.components.FlashlightGlyph(
            size = R1.space.xl + R1.space.xs,
            emitting = on,
            tint = if (on) R1.AccentWarm else R1.InkMuted,
        )
        Spacer(Modifier.width(R1.space.m))
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "FLASHLIGHT", style = R1.labelMicro, color = R1.InkSoft)
            Text(
                text = if (on) "ON" else "OFF",
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (on) R1.AccentWarm else R1.InkSoft,
            )
        }
        Box(
            modifier = Modifier
                .clip(R1.ShapeS)
                .background(if (on) R1.AccentWarm.copy(alpha = 0.18f) else R1.Bg)
                .border(
                    1.dp,
                    if (on) R1.AccentWarm.copy(alpha = 0.5f) else R1.Hairline,
                    R1.ShapeS,
                )
                .padding(horizontal = R1.space.m + R1.space.xxs, vertical = R1.space.s - R1.space.xxs),
        ) {
            Text(
                text = if (on) "TURN OFF" else "TURN ON",
                style = R1.labelMicro,
                color = if (on) R1.AccentWarm else R1.InkSoft,
            )
        }
    }
}

@Composable
private fun NetworkCard(ssid: String?, onOpenWifi: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = R1.MinTarget)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onOpenWifi, contentDescription = "Open WiFi settings")
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "WIFI", style = R1.labelMicro, color = R1.InkSoft)
            Text(
                text = ssid ?: "—",
                style = R1.body.copy(fontWeight = FontWeight.SemiBold),
                color = if (ssid != null) R1.Ink else R1.InkMuted,
            )
        }
        Text(text = "OPEN", style = R1.labelMicro, color = R1.AccentWarm)
    }
}

/**
 * Companion-style read-out of which device sensors are mirrored to HA
 * while IoT Sensors Mode is on. The enable toggles + persistence for
 * these live on the dedicated IoT Sensors settings screen (one owner for
 * the service config); this card mirrors that config read-only so the
 * Device surface can answer "what does HA see about this device right
 * now" at a glance, the way the HA companion app lists its sensors.
 *
 * Each row pairs the sensor's current local READING (the same values the
 * cards above show, which are exactly what the service publishes) with an
 * ON/OFF mirror badge, so an enabled-but-unreadable sensor (e.g. SSID
 * hidden without location permission) is distinguishable from a disabled
 * one.
 */
@Composable
private fun MirroredSensorsCard(iot: IotSensorsSettings, ui: DeviceViewModel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.l, vertical = R1.space.m),
        verticalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "MIRRORED TO HA",
                style = R1.labelMicro,
                color = R1.AccentGreen,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "EVERY ${iot.publishIntervalSec}s",
                style = R1.labelMicro,
                color = R1.InkMuted,
            )
        }
        // Order mirrors the publish payloads emitted by IotSensorsService.
        MirrorRow(
            label = "BATTERY",
            enabled = iot.publishBattery,
            reading = if (ui.batteryPct >= 0) "${ui.batteryPct}%" else null,
        )
        MirrorRow(
            label = "CHARGING",
            enabled = iot.publishCharging,
            reading = if (ui.isCharging) "ON" else "OFF",
        )
        MirrorRow(
            label = "LIGHT",
            enabled = iot.publishLightSensor,
            // Illuminance is event-driven and read by the service, not by this
            // VM, so we can't echo a lux value here — show the live/idle state.
            reading = null,
        )
        MirrorRow(
            label = "VIBRATION",
            enabled = iot.publishVibration,
            reading = null,
        )
        MirrorRow(
            label = "SCREEN",
            enabled = iot.publishScreenOn,
            reading = "ON",
        )
        MirrorRow(
            label = "WIFI SSID",
            enabled = iot.publishWifiSsid,
            reading = ui.wifiSsid,
        )
        Text(
            text = "Read-only here. Toggle sensors under Settings · IoT Sensors.",
            style = R1.labelMicro,
            color = R1.InkMuted,
        )
    }
}

@Composable
private fun MirrorRow(label: String, enabled: Boolean, reading: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        // Hairline status dot — green when this sensor is live to HA, muted
        // otherwise. Cheaper than a glyph and consistent with the dashboard's
        // status-dot language.
        Box(
            modifier = Modifier
                .size(R1.space.s)
                .clip(R1.ShapeRound)
                .background(if (enabled) R1.AccentGreen else R1.Hairline),
        )
        Spacer(Modifier.width(R1.space.s))
        Text(
            text = label,
            style = R1.labelMicro,
            color = if (enabled) R1.InkSoft else R1.InkMuted,
            modifier = Modifier.weight(1f),
        )
        if (enabled && reading != null) {
            Text(
                text = reading,
                style = R1.labelMicro,
                color = R1.Ink,
            )
            Spacer(Modifier.width(R1.space.s))
        }
        Text(
            text = if (enabled) "MIRRORING" else "OFF",
            style = R1.labelMicro,
            color = if (enabled) R1.AccentGreen else R1.InkMuted,
        )
    }
}
