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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.App
import com.github.itskenny0.r1ha.core.input.WheelInput
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.AutoRefresh
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.WheelScrollForScrollState
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import com.github.itskenny0.r1ha.ui.layout.AdaptiveContent

/**
 * Device controls — local-only cards for adjusting the host device
 * (the R1, or whichever phone is running the app). Nothing here is
 * exposed to Home Assistant; the R1 is a hardware peer, not a HA
 * device, so its controls live alongside the HA-entity surfaces
 * rather than inside the HA entity registry.
 *
 * Each card is its own self-contained widget — slider for the
 * brightness / volume readings, toggle for the flashlight, read-only
 * for the battery. The screen is a vertical scroll so adding more
 * local controls in the future doesn't have to fight for chrome
 * space.
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
                        .clip(R1.ShapeS)
                        .background(R1.SurfaceMuted)
                        .border(1.dp, R1.Hairline, R1.ShapeS)
                        .r1Pressable(onClick = { vm.refresh() })
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                ) {
                    Text(text = "REFRESH", style = R1.labelMicro, color = R1.InkSoft)
                }
            },
        )
        AdaptiveContent(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "LOCAL · NOT EXPOSED TO HA",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
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
                Spacer(Modifier.size(24.dp))
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
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
                Spacer(Modifier.height(2.dp))
                // Hand-drawn bolt (filled path) so the colour follows R1.AccentGreen.
                // The ⚡ emoji shipped its own yellow tint that clashed with the green
                // "CHARGING" label above it on the same card.
                com.github.itskenny0.r1ha.ui.components.ChargingBoltGlyph(
                    size = 14.dp,
                    tint = R1.AccentGreen,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onReleaseToSystem)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = "RESET", style = R1.labelMicro, color = R1.InkSoft)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.Bg)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onClick = onOpenSystem)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
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
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onToggle)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Hand-drawn glyph (not the 🔦 emoji) so the icon stays monochrome and reads at
        // hairline weight against the surrounding chrome — the colour-emoji font rendered
        // a chunky orange torch with its own drop-shadow that clashed with everything else
        // on the screen.
        com.github.itskenny0.r1ha.ui.components.FlashlightGlyph(
            size = 28.dp,
            emitting = on,
            tint = if (on) R1.AccentWarm else R1.InkMuted,
        )
        Spacer(Modifier.width(12.dp))
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
                .padding(horizontal = 14.dp, vertical = 6.dp),
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
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .r1Pressable(onClick = onOpenWifi)
            .padding(horizontal = 16.dp, vertical = 12.dp),
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

