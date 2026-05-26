package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS
import com.github.itskenny0.r1ha.core.input.KeyAction
import com.github.itskenny0.r1ha.core.input.KeyCaptureBus
import com.github.itskenny0.r1ha.core.input.keyCodeLabel
import com.github.itskenny0.r1ha.core.prefs.AppSettings
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Dedicated subpage for editing the hardware-key bindings. Reached from
 * Settings → Behaviour → Key bindings. One row per [KeyAction] with the
 * currently-bound keys as chips (tap a chip to remove) and an ADD button
 * that arms a press-to-bind overlay.
 *
 * The overlay is drawn inline as a full-screen Box rather than via
 * Compose's `Dialog`. Reason: `Dialog` creates its own sub-window whose
 * key events are routed to the dialog's window callback, not the
 * activity's `dispatchKeyEvent` — so the [KeyCaptureBus] callback would
 * never see the press. Rendering inline keeps the activity's key
 * dispatch as the single source of truth.
 */
@Composable
fun KeyBindingsScreen(
    settings: SettingsRepository,
    tokens: TokenStore,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(settings, tokens))
    val s by vm.state.collectAsStateWithLifecycle()
    var captureFor by remember { mutableStateOf<KeyAction?>(null) }

    Box(modifier = Modifier.fillMaxSize().background(R1.Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            R1TopBar(title = "KEY BINDINGS", onBack = onBack)
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    Text(
                        text = "Tap ADD to assign a hardware key; multiple keys per " +
                            "action are allowed. Tap a chip to remove that binding. " +
                            "RESET reverts to the built-in defaults.",
                        style = R1.body,
                        color = R1.InkMuted,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                    )
                }
                items(KeyAction.entries) { action ->
                    KeyBindingActionRow(
                        action = action,
                        settings = s,
                        onAdd = { captureFor = action },
                        onRemove = { code -> vm.removeKeyBinding(action, code) },
                        onReset = { vm.resetKeyBinding(action) },
                    )
                }
                item {
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(R1.ShapeS)
                                .background(R1.SurfaceMuted)
                                .border(1.dp, R1.Hairline, R1.ShapeS)
                                .r1Pressable({ vm.resetAllKeyBindings() })
                                .padding(horizontal = 14.dp, vertical = 10.dp),
                        ) {
                            Text(text = "RESET ALL TO DEFAULTS", style = R1.labelMicro, color = R1.InkMuted)
                        }
                    }
                    Spacer(Modifier.height(24.dp))
                }
            }
        }
        val target = captureFor
        if (target != null) {
            KeyCaptureOverlay(
                target = target,
                onAssigned = { code ->
                    vm.addKeyBinding(target, code)
                    captureFor = null
                },
                onCancel = { captureFor = null },
            )
        }
    }
}

@Composable
private fun KeyBindingActionRow(
    action: KeyAction,
    settings: AppSettings,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onReset: () -> Unit,
) {
    val effective = settings.keyBindings[action.name]
        ?: DEFAULT_KEY_BINDINGS[action].orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = action.displayLabel.uppercase(),
                    style = R1.bodyEmph,
                    color = R1.Ink,
                )
                Text(
                    text = action.description,
                    style = R1.body,
                    color = R1.InkMuted,
                    modifier = Modifier.padding(top = 1.dp),
                )
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onAdd)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                Text(text = "ADD", style = R1.labelMicro, color = R1.AccentWarm)
            }
            Spacer(Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .r1Pressable(onReset)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                Text(text = "RESET", style = R1.labelMicro, color = R1.InkMuted)
            }
        }
        Spacer(Modifier.height(6.dp))
        if (effective.isEmpty()) {
            Text(text = "UNBOUND", style = R1.labelMicro, color = R1.InkMuted)
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                effective.forEach { code ->
                    Box(
                        modifier = Modifier
                            .padding(end = 6.dp, top = 2.dp)
                            .clip(R1.ShapeS)
                            .background(R1.SurfaceMuted)
                            .border(1.dp, R1.Hairline, R1.ShapeS)
                            .r1Pressable({ onRemove(code) })
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(text = keyCodeLabel(code), style = R1.labelMicro, color = R1.Ink)
                            Spacer(Modifier.width(6.dp))
                            Text(text = "✕", style = R1.labelMicro, color = R1.InkMuted)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Full-screen modal overlay (NOT a `Dialog`) that installs a one-shot
 * [KeyCaptureBus] callback to grab the next hardware KEY_DOWN. Drawn in
 * the activity's window so MainActivity's `dispatchKeyEvent` sees the
 * event and routes it to the installed callback. Compose's `Dialog`
 * cannot be used here because it opens a sub-window with its own input
 * routing, and the callback would never fire.
 */
@Composable
private fun KeyCaptureOverlay(
    target: KeyAction,
    onAssigned: (Int) -> Unit,
    onCancel: () -> Unit,
) {
    DisposableEffect(target) {
        KeyCaptureBus.install { code ->
            onAssigned(code)
            true
        }
        onDispose { KeyCaptureBus.clear() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // Eat taps so the underlying list doesn't scroll while the
            // capture overlay is up. The CANCEL button still works
            // because it sits inside this Box and r1Pressable consumes
            // its own pointer events first.
            .r1Pressable(onCancel)
            .background(R1.Bg.copy(alpha = 0.94f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(R1.Surface)
                .border(1.dp, R1.Hairline, RoundedCornerShape(12.dp))
                .padding(horizontal = 28.dp, vertical = 24.dp),
        ) {
            Text(text = "PRESS A KEY", style = R1.labelMicro, color = R1.InkSoft)
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Press the hardware button you want to assign to",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = target.displayLabel.uppercase(), style = R1.bodyEmph, color = R1.Ink)
            Spacer(Modifier.height(20.dp))
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .border(1.dp, R1.Hairline, R1.ShapeS)
                    .r1Pressable(onCancel)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Text(text = "CANCEL", style = R1.labelMicro, color = R1.InkMuted)
            }
        }
    }
}
