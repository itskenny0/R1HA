package com.github.itskenny0.r1ha.wear.feature.remote

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onPreRotaryScrollEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import com.github.itskenny0.r1ha.core.ha.HaRepository

/**
 * Wear OS Remote Control screen — a touchpad + media/volume controls that
 * send `unified_remote/command` messages through the existing HA WebSocket.
 *
 * Layout (top → bottom in the circular watch screen):
 *   - TimeText (floating, handled by Scaffold)
 *   - 36 dp spacer  (clears TimeText)
 *   - Touchpad box  (weight=1f, fills remaining height)
 *       • Drag  → mouse move (scaled 2.5×)
 *       • Tap   → left click
 *       • Double-tap → double click
 *       • Long-press → right click
 *   - Media row: ⏮ ⏯ ⏭
 *   - Volume row: 🔉 🔇 🔊
 *   - 10 dp bottom spacer
 *
 * Bezel / crown rotary events are intercepted at the Scaffold level and
 * forwarded as `unified_remote/command` scroll messages.
 */
@Composable
fun WearRemoteScreen(
    haRepository: HaRepository,
    onBack: () -> Unit,
) {
    val vm: WearRemoteViewModel = viewModel(factory = WearRemoteViewModel.factory(haRepository))

    Scaffold(
        timeText = { TimeText() },
        modifier = Modifier.onPreRotaryScrollEvent { event ->
            // Bezel / crown → vertical scroll on the remote PC.
            // verticalScrollPixels is negative when the bezel rotates "up"
            // (away from you), so we pass the raw value and let UR interpret
            // the sign convention it already knows.
            vm.sendScroll(event.verticalScrollPixels)
            true  // consume — don't let the scroll bubble further
        },
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // ── Spacer under TimeText ────────────────────────────────────
            Spacer(Modifier.height(36.dp))

            // ── Touchpad area ────────────────────────────────────────────
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFF1A1A2E))
                    // Tap / double-tap / long-press → click variants
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = { vm.sendClick() },
                            onDoubleTap = { vm.sendDoubleClick() },
                            onLongPress = { vm.sendRightClick() },
                        )
                    }
                    // Drag → relative mouse move
                    // detectDragGestures kicks in only after touch slop is
                    // exceeded, so quick taps still reach detectTapGestures
                    // above (moves are consumed after slop, cancelling tap).
                    .pointerInput(Unit) {
                        detectDragGestures { _, dragAmount ->
                            vm.sendMove(dragAmount.x, dragAmount.y)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                // Subtle dot-grid gives the surface a trackpad feel
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val dotColor = Color.White.copy(alpha = 0.12f)
                    val dotRadius = 2.dp.toPx()
                    val cols = 5; val rows = 4
                    val xSpacing = size.width / (cols + 1)
                    val ySpacing = size.height / (rows + 1)
                    for (r in 1..rows) {
                        for (c in 1..cols) {
                            drawCircle(
                                color = dotColor,
                                radius = dotRadius,
                                center = Offset(c * xSpacing, r * ySpacing),
                            )
                        }
                    }
                }
                // Small label so users know what the dark area does
                Text(
                    text = "↕  ↔",
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.30f),
                )
            }

            Spacer(Modifier.height(6.dp))

            // ── Media transport ──────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(label = "⏮", onClick = vm::sendPrevious)
                RemoteButton(label = "⏯", onClick = vm::sendPlayPause, primary = true)
                RemoteButton(label = "⏭", onClick = vm::sendNext)
            }

            Spacer(Modifier.height(4.dp))

            // ── Volume ───────────────────────────────────────────────────
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RemoteButton(label = "🔉", onClick = vm::sendVolumeDown)
                RemoteButton(label = "🔇", onClick = vm::sendMute)
                RemoteButton(label = "🔊", onClick = vm::sendVolumeUp)
            }

            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun RemoteButton(
    label: String,
    onClick: () -> Unit,
    primary: Boolean = false,
) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(if (primary) 44.dp else 36.dp),
        colors = if (primary)
            ButtonDefaults.buttonColors(backgroundColor = MaterialTheme.colors.primary)
        else
            ButtonDefaults.secondaryButtonColors(),
    ) {
        Text(
            text = label,
            fontSize = if (primary) 16.sp else 13.sp,
            textAlign = TextAlign.Center,
        )
    }
}

