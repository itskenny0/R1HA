package com.github.itskenny0.r1ha.feature.broadlink

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.theme.responsiveType

/**
 * Shared visual vocabulary for the Broadlink console. Everything here is
 * drawn from primitives against R1.* tokens; no new assets, no new fonts.
 */

/**
 * Logic-analyzer rendering of a command's synthetic signature: a timing
 * grid, a square pulse train (high = mark, low = space), and the pseudo
 * hex word group with the nominal carrier. Honest by construction: the
 * caption says it is a local rendering because HA never returns the
 * captured bytes.
 */
@Composable
fun SignatureTrace(
    deviceName: String,
    commandName: String,
    type: String,
    modifier: Modifier = Modifier,
    showCaption: Boolean = true,
) {
    val trace = remember(deviceName, commandName, type) {
        BroadlinkSignature.traceFor(deviceName, commandName, type)
    }
    val accent = R1.AccentWarm
    val grid = R1.Hairline
    Column(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(R1.ShapeS)
                .background(R1.Bg)
                .border(1.dp, R1.Hairline, R1.ShapeS)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            val w = size.width
            val h = size.height
            // Timing grid: four horizontal scan rules, hairline weight.
            for (i in 1..3) {
                val y = h * i / 4f
                drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
            }
            // Square pulse train. Each pulse's stored width scales its dwell
            // time; even indices ride the mark rail, odd the space rail.
            val total = trace.pulses.sum()
            if (total <= 0f) return@Canvas
            val markY = h * 0.18f
            val spaceY = h * 0.82f
            var x = 0f
            var lastY = spaceY
            trace.pulses.forEachIndexed { i, p ->
                val segW = w * (p / total)
                val y = if (i % 2 == 0) markY else spaceY
                // Vertical edge then horizontal dwell: the classic scope step.
                drawLine(accent, Offset(x, lastY), Offset(x, y), strokeWidth = 2f)
                drawLine(accent, Offset(x, y), Offset(x + segW, y), strokeWidth = 2f)
                x += segW
                lastY = y
            }
        }
        Spacer(Modifier.height(R1.space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = trace.hexWords.joinToString(" "),
                style = responsiveType(R1.numeralS),
                color = R1.InkSoft,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = trace.carrierLabel,
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
        if (showCaption) {
            Text(
                text = "LOCAL RENDER. HA DOES NOT EXPOSE THE RAW CODE.",
                style = responsiveType(R1.labelMicro),
                color = R1.InkMuted,
            )
        }
    }
}

/**
 * The capture moment: a pulsing emitter dot inside expanding wavefront
 * arcs, with a sweep angle that keeps the eye busy during the wait.
 * Pure draw primitives, accent-on-black, no Material spinner.
 */
@Composable
fun CaptureIndicator(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "blink-capture")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "blink-capture-phase",
    )
    val accent = R1.AccentWarm
    val muted = R1.SurfaceMuted
    Canvas(modifier = modifier.size(120.dp)) {
        val c = center
        val maxR = size.minDimension / 2f
        // Three staggered wavefronts expanding outward and fading.
        for (k in 0..2) {
            val p = (phase + k / 3f) % 1f
            val r = maxR * (0.25f + 0.75f * p)
            val alpha = (1f - p) * 0.8f
            drawCircle(
                color = accent.copy(alpha = alpha),
                radius = r,
                center = c,
                style = Stroke(width = 2.dp.toPx()),
            )
        }
        // Static reference ring: the "antenna" boundary.
        drawCircle(color = muted, radius = maxR * 0.25f, center = c, style = Stroke(width = 1.dp.toPx()))
        // Emitter dot breathing between 60 and 100 percent.
        val breathe = 0.6f + 0.4f * kotlin.math.abs(1f - 2f * phase)
        drawCircle(color = accent, radius = maxR * 0.12f * breathe, center = c)
    }
}

/** All-caps micro section label with the standard breathing room. */
@Composable
fun BroadlinkSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = responsiveType(R1.labelMicro),
        color = R1.InkMuted,
        modifier = modifier.padding(top = R1.space.l, bottom = R1.space.xs),
    )
}

/** Compact IR / RF type badge. */
@Composable
fun CommandTypeBadge(type: String, modifier: Modifier = Modifier) {
    Text(
        text = if (type == "rf") "RF" else "IR",
        style = responsiveType(R1.labelMicro),
        color = if (type == "rf") R1.AccentCool else R1.AccentWarm,
        modifier = modifier
            .clip(R1.ShapeS)
            .border(1.dp, R1.Hairline, R1.ShapeS)
            .padding(horizontal = R1.space.xs, vertical = 1.dp),
    )
}

/** One-line picker row used by selector chips: label + horizontal chips. */
@Composable
fun ChipRow(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.s),
    ) {
        Text(
            text = label,
            style = responsiveType(R1.labelMicro),
            color = R1.InkMuted,
            modifier = Modifier.width(64.dp),
        )
        content()
    }
}

/** Boxed hairline divider used between console sections. */
@Composable
fun HairlineRule(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(R1.Hairline),
    )
}
