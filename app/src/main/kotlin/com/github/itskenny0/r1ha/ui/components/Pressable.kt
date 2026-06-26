package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics

/**
 * Make any element tactile in the R1 idiom: a tight scale + alpha dip on press, and a
 * CLOCK_TICK haptic on click. No Material ripple — the ripple's watercolour splash fights
 * the sharp industrial dashboard language. The scale dip is animated with a critically-
 * damped spring so it feels mechanical (snap, no overshoot).
 *
 * Use everywhere a clickable row / chip / icon button would otherwise be a bare
 * [Modifier.clickable]: settings rows, nav rows, theme cards, info-row link, etc. The 0.97
 * scale and 0.78 pressed-alpha are deliberately subtle — visible enough that the press
 * registers, quiet enough that scrolling lists don't shimmer if a stray finger grazes them.
 */
fun Modifier.r1Pressable(
    onClick: () -> Unit,
    hapticOnClick: Boolean = true,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.78f,
    contentDescription: String? = null,
    // An optional caller-owned press stream. A face that animates its own chrome
    // off the press state (the fire actuator's disc wash) passes its source in so
    // its chrome and this modifier's scale/alpha dip animate in lockstep; default
    // null keeps a private source for every other call site.
    interactionSource: MutableInteractionSource? = null,
): Modifier = composed {
    val interactionSource = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-press-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-press-alpha",
    )
    val view = LocalView.current
    // Direct-to-vibrator helper — bypasses View.performHapticFeedback
    // which Xiaomi/MIUI and a few other vendor ROMs route through the
    // touch-feedback gate (off by default; users have to enable it
    // manually under system Sound settings). R1Haptic uses the
    // VibratorManager API which behaves uniformly across ROMs.
    val haptic = rememberR1Haptic()
    this
        // Single graphicsLayer for both transforms — cheaper than chaining .graphicsLayer { scale }
        // and .alpha(), both of which would force separate compositing layers.
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            onClick = {
                if (hapticOnClick) haptic.tick(view)
                onClick()
            },
        )
        // Merge descendant semantics so a chip-with-Text reads as one Button node to
        // TalkBack instead of "$text, double-tap to activate" split across two nodes.
        // If a caller supplies an explicit contentDescription (icon-only buttons that
        // have no readable text child), apply it here.
        .then(
            if (contentDescription != null) {
                Modifier.semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = androidx.compose.ui.semantics.Role.Button
                }
            } else {
                Modifier.semantics(mergeDescendants = true) {
                    role = androidx.compose.ui.semantics.Role.Button
                }
            },
        )
}

/**
 * Variant of [r1Pressable] that also handles long-press. Same press-state visual + tap
 * haptic, plus a heavier [R1Haptic.longPress] effect when [onLongPress] fires. The
 * press-state is driven through the same MutableInteractionSource so the scale dip
 * matches; press-down → hold → long-press triggers and the scale stays dipped until
 * release, which reads as "the system noticed the hold".
 *
 * Use this for list rows where short tap = primary action (toggle, navigate) and long
 * press = secondary action (preview, context menu). Favourites picker uses it to wire
 * long-press → card preview without making a short tap accidentally trigger it.
 */
fun Modifier.r1RowPressable(
    onTap: () -> Unit,
    onLongPress: () -> Unit,
    pressedScale: Float = 0.97f,
    pressedAlpha: Float = 0.78f,
    contentDescription: String? = null,
    /**
     * Optional double-tap handler. Null (the default) keeps the cheaper
     * single-tap path with no double-tap disambiguation delay; passing a handler
     * makes [detectTapGestures] wait out the double-tap timeout before firing
     * [onTap], so wire it only when a card has opted in (e.g. double-tap to open
     * more-info).
     */
    onDoubleTap: (() -> Unit)? = null,
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-row-press-scale",
    )
    val alpha by animateFloatAsState(
        targetValue = if (pressed) pressedAlpha else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "r1-row-press-alpha",
    )
    val view = LocalView.current
    val haptic = rememberR1Haptic()
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
            this.alpha = alpha
        }
        .pointerInput(onTap, onLongPress, onDoubleTap) {
            detectTapGestures(
                onDoubleTap = onDoubleTap?.let { handler ->
                    {
                        haptic.tick(view)
                        handler()
                    }
                },
                onPress = { offset ->
                    // Drive the MutableInteractionSource manually so the press-state spring
                    // animates while the user is holding. tryAwaitRelease returns true on a
                    // normal release, false if the gesture is cancelled (finger leaves the
                    // bounds before release) — emit the matching Release / Cancel either way.
                    val press = PressInteraction.Press(offset)
                    interactionSource.emit(press)
                    val released = tryAwaitRelease()
                    interactionSource.emit(
                        if (released) PressInteraction.Release(press)
                        else PressInteraction.Cancel(press),
                    )
                },
                onTap = {
                    haptic.tick(view)
                    onTap()
                },
                onLongPress = {
                    // longPress() fires a noticeably heavier effect than tick() so the
                    // user can feel the gesture register as something distinct from a tap.
                    haptic.longPress(view)
                    onLongPress()
                },
            )
        }
        .then(
            if (contentDescription != null) {
                Modifier.semantics(mergeDescendants = true) {
                    this.contentDescription = contentDescription
                    role = androidx.compose.ui.semantics.Role.Button
                }
            } else {
                Modifier.semantics(mergeDescendants = true) {
                    role = androidx.compose.ui.semantics.Role.Button
                }
            },
        )
}
