package com.github.itskenny0.r1ha.feature.settings

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.itskenny0.r1ha.core.input.DEFAULT_KEY_BINDINGS
import com.github.itskenny0.r1ha.core.input.KeyAction
import com.github.itskenny0.r1ha.core.input.KeyCaptureBus
import com.github.itskenny0.r1ha.core.input.keyCodeLabel
import com.github.itskenny0.r1ha.core.prefs.SettingsRepository
import com.github.itskenny0.r1ha.core.prefs.TokenStore
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.R1Chip
import com.github.itskenny0.r1ha.ui.components.R1ChipVariant
import com.github.itskenny0.r1ha.ui.components.R1TopBar
import com.github.itskenny0.r1ha.ui.components.r1Pressable
import kotlinx.coroutines.delay

/**
 * Dedicated subpage for editing the hardware-key bindings. Reached from
 * Settings, Behaviour, Key bindings. One row per [KeyAction] with the
 * currently-bound keys as chips (tap a chip to remove) and an ADD button
 * that arms a press-to-bind overlay.
 *
 * The overlay is drawn inline as a full-screen Box rather than via
 * Compose's `Dialog`. Reason: `Dialog` creates its own sub-window whose
 * key events are routed to the dialog's window callback, not the
 * activity's `dispatchKeyEvent`, so the [KeyCaptureBus] callback would
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

    // Effective binding map (stored override OR default fallback) for the
    // whole table. Used to compute the customized count + the per-key
    // conflict set so the UI can light up rows that share a binding.
    val effectiveByAction: Map<KeyAction, List<Int>> = remember(s.keyBindings) {
        KeyAction.entries.associateWith { action ->
            s.keyBindings[action.name] ?: DEFAULT_KEY_BINDINGS[action].orEmpty()
        }
    }
    val conflictingCodes: Set<Int> = remember(effectiveByAction) {
        val counts = mutableMapOf<Int, Int>()
        for (codes in effectiveByAction.values) {
            for (code in codes) counts[code] = (counts[code] ?: 0) + 1
        }
        counts.filterValues { it > 1 }.keys
    }
    val customizedCount: Int = remember(s.keyBindings) {
        KeyAction.entries.count { action ->
            val stored = s.keyBindings[action.name] ?: return@count false
            stored != DEFAULT_KEY_BINDINGS[action].orEmpty()
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(R1.Bg).systemBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            R1TopBar(title = "KEY BINDINGS", onBack = onBack)
            // Centre + width-cap the binding rows on tablet / desktop tiers so a
            // key + chips row doesn't span a 1280 dp+ panel. R1 / compact fill.
            val dimens = com.github.itskenny0.r1ha.core.theme.rememberResponsiveDimens()
            val listModifier = if (dimens.capsContentWidth) {
                Modifier
                    .fillMaxSize()
                    .widthIn(max = dimens.maxContentWidth)
                    .align(Alignment.CenterHorizontally)
            } else {
                Modifier.fillMaxSize()
            }
            LazyColumn(
                modifier = listModifier,
                verticalArrangement = Arrangement.spacedBy(R1.space.xs),
            ) {
                item { HeaderBlock(customizedCount = customizedCount, conflictCount = conflictingCodes.size) }
                items(KeyAction.entries, key = { it.name }) { action ->
                    val effective = effectiveByAction[action].orEmpty()
                    val isCustomized = s.keyBindings.containsKey(action.name) &&
                        effective != DEFAULT_KEY_BINDINGS[action].orEmpty()
                    KeyBindingActionRow(
                        action = action,
                        bound = effective,
                        isCustomized = isCustomized,
                        conflictingCodes = conflictingCodes,
                        capturing = captureFor == action,
                        onAdd = { captureFor = action },
                        onRemove = { code -> vm.removeKeyBinding(action, code) },
                        onReset = { vm.resetKeyBinding(action) },
                    )
                }
                item {
                    Spacer(Modifier.height(R1.space.s))
                    GlobalResetRow(onResetAll = { vm.resetAllKeyBindings() })
                    Spacer(Modifier.height(R1.space.xl))
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

/**
 * Intro block: short instruction sentence plus a compact status strip that
 * tells the user at a glance how many actions are customised and whether
 * any keys are double-booked. The status strip silently disappears when
 * everything is default + clean, so it doesn't add noise.
 */
@Composable
private fun HeaderBlock(customizedCount: Int, conflictCount: Int) {
    Column(modifier = Modifier.padding(horizontal = R1.space.xl, vertical = R1.space.l)) {
        Text(
            text = "Map hardware keys to in-app actions. " +
                "Tap ADD to capture the next key press; tap a chip to remove it; " +
                "RESET reverts a row to its default.",
            style = R1.body,
            color = R1.InkMuted,
        )
        if (customizedCount > 0 || conflictCount > 0) {
            Spacer(Modifier.height(R1.space.m))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(R1.space.s),
            ) {
                if (customizedCount > 0) {
                    R1Chip(
                        text = "$customizedCount CUSTOM",
                        variant = R1ChipVariant.Pill,
                        tone = R1.AccentWarm,
                    )
                }
                if (conflictCount > 0) {
                    R1Chip(
                        text = "$conflictCount CONFLICT" + if (conflictCount > 1) "S" else "",
                        variant = R1ChipVariant.Pill,
                        tone = R1.StatusRed,
                    )
                }
            }
        }
    }
}

/**
 * One row per [KeyAction]. Lays out as:
 *   - Left rail: 2dp vertical accent stroke when this row is customised,
 *     transparent when default. Reads at a glance which actions diverge.
 *   - Title block: friendly label (uppercase, primary ink) + plain-language
 *     description (muted).
 *   - Action chips: ADD (always shown) and RESET (only when customised, so
 *     the row chrome is quieter for rows the user hasn't touched).
 *   - Chip strip: the currently-bound keycodes, or UNBOUND if there are
 *     none. A conflicting chip is bordered StatusRed and prefixed with a
 *     small marker so the user can see which side of the conflict to
 *     remove.
 */
@Composable
private fun KeyBindingActionRow(
    action: KeyAction,
    bound: List<Int>,
    isCustomized: Boolean,
    conflictingCodes: Set<Int>,
    capturing: Boolean,
    onAdd: () -> Unit,
    onRemove: (Int) -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = R1.space.l, end = R1.space.xl, top = R1.space.m, bottom = R1.space.m),
    ) {
        // Left rail: 2dp accent stripe when customised. drawBehind so the
        // stripe stays glued to the row's full height even if the title
        // wraps to a second line.
        Box(
            modifier = Modifier
                .width(R1.space.s)
                .drawBehind {
                    if (isCustomized) {
                        drawLine(
                            color = R1.AccentWarm,
                            start = Offset(2f, 4f),
                            end = Offset(2f, size.height - 4f),
                            strokeWidth = 4f,
                        )
                    }
                },
        )
        Column(modifier = Modifier.fillMaxWidth()) {
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
                        modifier = Modifier.padding(top = R1.space.xxs),
                    )
                }
                R1Chip(
                    // Selected (armed) only while this row's capture overlay is
                    // active; otherwise the chip reads as a plain idle action
                    // so every row no longer looks permanently armed.
                    text = if (capturing) "ADD…" else "ADD",
                    variant = R1ChipVariant.Action,
                    tone = R1.AccentWarm,
                    selected = capturing,
                    onClick = onAdd,
                    contentDescription = "Add a key binding",
                )
                if (isCustomized) {
                    Spacer(Modifier.width(R1.space.s))
                    R1Chip(
                        text = "RESET",
                        variant = R1ChipVariant.Action,
                        onClick = onReset,
                        contentDescription = "Reset this binding",
                    )
                }
            }
            Spacer(Modifier.height(R1.space.s))
            if (bound.isEmpty()) {
                R1Chip(text = "UNBOUND", variant = R1ChipVariant.Pill, tone = R1.InkMuted)
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    bound.forEach { code ->
                        BoundKeyChip(
                            code = code,
                            isConflict = code in conflictingCodes,
                            onRemove = { onRemove(code) },
                        )
                    }
                }
            }
        }
    }
}

/**
 * One bound-key chip. Normal state is a hairline outline + Ink label; a
 * conflicting binding flips the border + label to StatusRed and prepends a
 * small dot so it reads as "this is the one to fix" at a glance.
 */
@Composable
private fun BoundKeyChip(
    code: Int,
    isConflict: Boolean,
    onRemove: () -> Unit,
) {
    val borderColor = if (isConflict) R1.StatusRed.copy(alpha = 0.7f) else R1.Hairline
    val textColor = if (isConflict) R1.StatusRed else R1.Ink
    Box(
        modifier = Modifier
            .padding(end = R1.space.s, top = R1.space.xxs, bottom = R1.space.xxs)
            .clip(R1.ShapeS)
            .background(R1.SurfaceMuted)
            .border(1.dp, borderColor, R1.ShapeS)
            .r1Pressable(onClick = onRemove, contentDescription = "Remove ${keyCodeLabel(code)}")
            .padding(horizontal = R1.space.m, vertical = R1.space.s),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isConflict) {
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(RoundedCornerShape(50))
                        .background(R1.StatusRed),
                )
                Spacer(Modifier.width(R1.space.s))
            }
            Text(text = keyCodeLabel(code), style = R1.labelMicro, color = textColor)
            Spacer(Modifier.width(R1.space.s))
            Text(text = "X", style = R1.labelMicro, color = R1.InkMuted)
        }
    }
}

@Composable
private fun GlobalResetRow(onResetAll: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = R1.space.xl),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .clip(R1.ShapeS)
                .background(R1.SurfaceMuted)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .r1Pressable(onResetAll)
                .padding(horizontal = R1.space.l, vertical = R1.space.m),
            contentAlignment = Alignment.Center,
        ) {
            Text(text = "RESET ALL TO DEFAULTS", style = R1.labelMicro, color = R1.InkMuted)
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
 *
 * The overlay times out after [CAPTURE_TIMEOUT_SECONDS] seconds so a user
 * who can't find a free key isn't stuck in capture mode forever; the
 * progress bar at the bottom of the sheet drains visibly as the timer
 * counts down.
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
    // Countdown: integer-second display + a 0..1 fraction for the
    // progress bar. Tick every 50ms so the bar drains smoothly rather
    // than stepping a chunky 10% every second.
    var remainingMs by remember(target) { mutableStateOf(CAPTURE_TIMEOUT_MS) }
    LaunchedEffect(target) {
        val tickMs = 50L
        while (remainingMs > 0L) {
            delay(tickMs)
            remainingMs = (remainingMs - tickMs).coerceAtLeast(0L)
        }
        // Auto-dismiss after timeout so we don't pin the overlay forever
        // when the user never finds a free key.
        onCancel()
    }
    val secondsLeft = ((remainingMs + 999L) / 1000L).toInt().coerceAtLeast(0)
    val fraction by animateFloatAsState(
        targetValue = (remainingMs.toFloat() / CAPTURE_TIMEOUT_MS.toFloat()).coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 60, easing = LinearEasing),
        label = "capture-countdown",
    )

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
                .padding(horizontal = R1.space.xxl)
                .clip(RoundedCornerShape(12.dp))
                .background(R1.Surface)
                .border(1.dp, R1.AccentWarm.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
                .padding(horizontal = R1.space.xxl, vertical = R1.space.xl),
        ) {
            // Pulsing accent dot to signal active capture. Always-on label
            // beside it so screen readers and low-vision users don't need to
            // catch the pulse to know the overlay is armed.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                CapturePulseDot()
                Spacer(Modifier.width(R1.space.s))
                Text(
                    text = "PRESS A KEY NOW",
                    style = R1.labelMicro,
                    color = R1.AccentWarm,
                )
            }
            Spacer(Modifier.height(R1.space.l))
            Text(
                text = "Assigning to",
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(R1.space.xxs))
            Text(
                text = target.displayLabel.uppercase(),
                style = R1.screenTitle,
                color = R1.Ink,
            )
            Spacer(Modifier.height(R1.space.xs))
            Text(
                text = target.description,
                style = R1.body,
                color = R1.InkMuted,
            )
            Spacer(Modifier.height(R1.space.xl))
            // Countdown bar: 1dp tall, drains from full to empty. Sits
            // immediately above the timer label so the eye reads them as
            // one unit.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(R1.SurfaceMuted),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(R1.AccentWarm),
                )
            }
            Spacer(Modifier.height(R1.space.s))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${secondsLeft}s LEFT",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                    modifier = Modifier.weight(1f),
                )
                R1Chip(
                    text = "CANCEL",
                    variant = R1ChipVariant.Action,
                    onClick = onCancel,
                    contentDescription = "Cancel key capture",
                )
            }
        }
    }
}

/** Soft accent dot that fades in/out on a 600ms cycle, signalling that the
 *  capture overlay is actively listening for a key press. Using alpha
 *  animation (cheap) rather than scale (would force a re-layout). */
@Composable
private fun CapturePulseDot() {
    val infinite = rememberInfiniteTransition(label = "capture-pulse")
    val alpha by infinite.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "capture-pulse-alpha",
    )
    Box(
        modifier = Modifier
            .size(8.dp)
            .clip(RoundedCornerShape(50))
            .background(R1.AccentWarm.copy(alpha = alpha)),
    )
}

private const val CAPTURE_TIMEOUT_MS: Long = 10_000L
