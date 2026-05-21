package com.github.itskenny0.r1ha.wear.input

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import com.github.itskenny0.r1ha.core.input.WheelEvent
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Detects a finger sliding along the outer ring of a round watch screen and
 * translates the angular motion into [WheelEvent] steps, exactly mirroring what
 * the R1's physical scroll wheel does on the phone.
 *
 * ## How it works
 *
 * The modifier divides the screen into two concentric zones:
 *
 * ```
 *   ┌──────────────────────────────────┐
 *   │         outer rim zone           │  ← r > (radius * outerFraction)
 *   │    ┌──────────────────────┐      │
 *   │    │    inner tap zone    │      │  ← r ≤ (radius * outerFraction)
 *   │    │  (normal touch pass- │      │
 *   │    │   through; gestures  │      │
 *   │    │   handled by child   │      │
 *   │    │   composables)       │      │
 *   │    └──────────────────────┘      │
 *   └──────────────────────────────────┘
 * ```
 *
 * A drag that **starts** inside the outer rim zone is claimed by this modifier.
 * The angle from the screen centre to the finger is tracked with `atan2`. Every
 * time the accumulated angular delta exceeds `π / stepsPerHalfTurn` radians
 * (default: 9° per step), one [WheelEvent] is emitted:
 *
 * - **Clockwise** arc  → [WheelEvent.Direction.DOWN]
 * - **Counter-clockwise** arc → [WheelEvent.Direction.UP]
 *
 * This directly replaces the R1's physical detent-per-step wheel behaviour.
 *
 * ## Tuning
 *
 * | Parameter | Default | Effect |
 * |-----------|---------|--------|
 * | `outerFraction` | 0.24 | Outer 24 % of radius triggers rim scroll |
 * | `stepsPerHalfTurn` | 20 | 20 steps per 180°, i.e. 9° per step |
 *
 * Increase `stepsPerHalfTurn` for finer granularity (better for precision value
 * adjustment); decrease for fewer, larger steps per swipe.
 *
 * ## Integration with [WheelInput]
 *
 * ```kotlin
 * Box(
 *     modifier = Modifier
 *         .outerRimScroll { dir -> wheelInput.emit(dir) }
 *         .fillMaxSize()
 * ) { ... }
 * ```
 *
 * The emitted events land in the same `WheelInput` flow that the rotary crown
 * and the phone's scroll wheel feed into, so all downstream entity-value
 * adjustment and list-scroll logic requires zero changes.
 */
fun Modifier.outerRimScroll(
    outerFraction: Float = 0.24f,
    stepsPerHalfTurn: Int = 20,
    onStep: (WheelEvent.Direction) -> Unit,
): Modifier = composed {
    // Per-gesture mutable state. `composed` gives us a stable remember scope
    // per modifier instance, so multiple outerRimScroll modifiers in the tree
    // don't share state.
    var lastAngle by remember { mutableFloatStateOf(0f) }
    var tracking by remember { androidx.compose.runtime.mutableStateOf(false) }
    var accumulated by remember { mutableFloatStateOf(0f) }

    pointerInput(outerFraction, stepsPerHalfTurn) {
        val stepAngle = PI.toFloat() / stepsPerHalfTurn   // radians per step

        detectDragGestures(
            onDragStart = { offset ->
                val center = Offset(size.width / 2f, size.height / 2f)
                val rel = offset - center
                val radius = size.width / 2f
                val minRadius = radius * (1f - outerFraction)

                tracking = rel.getDistance() >= minRadius
                if (tracking) {
                    lastAngle = atan2(rel.y, rel.x)
                    accumulated = 0f
                }
            },
            onDrag = { change, _ ->
                if (!tracking) return@detectDragGestures

                val center = Offset(size.width / 2f, size.height / 2f)
                val rel = change.position - center
                val radius = size.width / 2f

                // If finger drifts significantly into the centre, stop tracking.
                // The 0.80 hysteresis threshold prevents jitter at the boundary.
                if (rel.getDistance() < radius * (1f - outerFraction) * 0.80f) {
                    tracking = false
                    return@detectDragGestures
                }

                val angle = atan2(rel.y, rel.x)
                var delta = angle - lastAngle

                // Wrap angular delta to [-π, π] so the transition through
                // the ±180° discontinuity (the "west" edge) is handled cleanly.
                if (delta > PI.toFloat()) delta -= 2f * PI.toFloat()
                if (delta < -PI.toFloat()) delta += 2f * PI.toFloat()

                accumulated += delta
                val steps = (accumulated / stepAngle).toInt()

                if (steps != 0) {
                    accumulated -= steps * stepAngle
                    val dir = if (steps > 0) WheelEvent.Direction.DOWN else WheelEvent.Direction.UP
                    repeat(abs(steps)) { onStep(dir) }
                }

                lastAngle = angle
            },
            onDragEnd = {
                tracking = false
                accumulated = 0f
            },
            onDragCancel = {
                tracking = false
                accumulated = 0f
            },
        )
    }
}
