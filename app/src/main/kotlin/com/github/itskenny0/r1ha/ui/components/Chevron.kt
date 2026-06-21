package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Drawing primitive — a sharp two-stroke chevron in the R1 idiom. Replaces Material's
 * `ArrowForwardIos` / `ArrowBackIos` / `ArrowUp` / `ArrowDown`, all of which have a slight
 * rounded geometry and Material-typical proportions that fight the dashboard language.
 *
 * Built from two `drawLine` calls (with `Butt` caps so the joint reads as a hard angle) so it
 * matches the 1dp hairlines used elsewhere. Stroke width scales lightly with the requested
 * size so a 12dp chevron stays crisp without being feather-thin.
 */
enum class ChevronDirection { Left, Right, Up, Down }

@Composable
fun Chevron(
    direction: ChevronDirection,
    modifier: Modifier = Modifier,
    size: Dp = 10.dp,
    tint: Color = R1.InkMuted,
    strokeWidth: Dp = 1.5.dp,
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val sw = strokeWidth.toPx()
            val w = this.size.width
            val h = this.size.height
            // Inset slightly so the stroke doesn't clip at the box edges.
            val inset = sw / 2f
            // Anchor + the two end points; orientation flips which corners we connect.
            val (a, mid, b) = when (direction) {
                ChevronDirection.Right -> Triple(
                    Offset(inset, inset),
                    Offset(w - inset, h / 2f),
                    Offset(inset, h - inset),
                )
                ChevronDirection.Left -> Triple(
                    Offset(w - inset, inset),
                    Offset(inset, h / 2f),
                    Offset(w - inset, h - inset),
                )
                ChevronDirection.Down -> Triple(
                    Offset(inset, inset),
                    Offset(w / 2f, h - inset),
                    Offset(w - inset, inset),
                )
                ChevronDirection.Up -> Triple(
                    Offset(inset, h - inset),
                    Offset(w / 2f, inset),
                    Offset(w - inset, h - inset),
                )
            }
            drawLine(tint, start = a, end = mid, strokeWidth = sw, cap = StrokeCap.Butt)
            drawLine(tint, start = mid, end = b, strokeWidth = sw, cap = StrokeCap.Butt)
        }
    }
}

/**
 * The "there is more deck below" cue at the bottom of the card stack. Replaces the
 * bare [Chevron]: that one is drawn from two butt-capped strokes, so its apex reads as
 * a hard notch, static and a touch crude under a soft fade. This one is a single
 * stroked path with a ROUND join (clean point) and round caps, and it breathes: a slow
 * vertical bob paired with a fade, so it gently beckons downward instead of just
 * sitting there. The bob/fade are applied through [graphicsLayer] (a layer transform on
 * a one-shot-cached path), not by redrawing the path each frame, so it stays cheap even
 * on the GPU-bound low-end devices. Wrap it in the caller's AnimatedVisibility as before.
 */
@Composable
fun ScrollCueDown(
    modifier: Modifier = Modifier,
    tint: Color = R1.InkMuted,
) {
    val anim = rememberInfiniteTransition(label = "scrollCue")
    val bob by anim.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "bob",
    )
    Canvas(
        modifier = modifier
            .size(width = 24.dp, height = 13.dp)
            .graphicsLayer {
                translationY = bob * 4.dp.toPx()
                alpha = 0.32f + 0.5f * bob
            },
    ) {
        val sw = 1.5.dp.toPx()
        val inset = sw
        val path = Path().apply {
            moveTo(inset, inset)
            lineTo(size.width / 2f, size.height - inset)
            lineTo(size.width - inset, inset)
        }
        drawPath(
            path = path,
            color = tint,
            style = Stroke(width = sw, cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
    }
}
