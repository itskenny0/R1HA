package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.core.util.R1Toast
import kotlinx.coroutines.delay

/**
 * Renders the most recent [R1Toast] event on top of whatever screen is composed.
 * Mounts inside a Box so the parent decides the screen anchor — typical use is at
 * the activity root so toasts overlay every navigated destination.
 *
 * Two display states:
 *  - **Compact** (default): a one-line pill at the bottom of the screen with the
 *    tag and a truncated message. Tappable.
 *  - **Expanded**: a multi-line panel showing the full text + tag + level. Tapped
 *    on the compact form to expand, tapped on the X to dismiss, or auto-dismisses
 *    on its own.
 *
 * Auto-dismiss timing scales with level: ERROR / WARN linger longer (8 s compact,
 * 30 s expanded — so a user reading a long error trace doesn't get cut off mid-
 * scroll); INFO / DEBUG go fast (4 s compact, 15 s expanded).
 */
@Composable
fun BoxScope.ToastHost() {
    val current = remember { mutableStateOf<R1Toast.Event?>(null) }
    val expanded = remember { mutableStateOf(false) }
    // Subscribe to the bus once; each new event becomes the visible toast and
    // resets the auto-dismiss timer.
    LaunchedEffect(Unit) {
        R1Toast.bus.collect { event ->
            current.value = event
            expanded.value = false
            val compactMillis = when (event.level) {
                R1Toast.Level.ERROR, R1Toast.Level.WARN -> 8_000L
                R1Toast.Level.INFO, R1Toast.Level.DEBUG -> 4_000L
            }
            delay(compactMillis)
            // The user might have tapped to expand mid-window. Hold the toast a
            // longer beat in the expanded state — generous so they can read a
            // stack trace or copy/paste an error before it disappears.
            if (expanded.value) {
                val expandedMillis = when (event.level) {
                    R1Toast.Level.ERROR, R1Toast.Level.WARN -> 30_000L
                    R1Toast.Level.INFO, R1Toast.Level.DEBUG -> 15_000L
                }
                delay(expandedMillis)
            }
            if (current.value === event) {
                current.value = null
                expanded.value = false
            }
        }
    }
    val event = current.value
    AnimatedVisibility(
        visible = event != null,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .fillMaxWidth(),
    ) {
        if (event != null) {
            val accent = when (event.level) {
                R1Toast.Level.ERROR -> R1.StatusRed
                R1Toast.Level.WARN -> R1.StatusAmber
                R1Toast.Level.INFO -> R1.AccentWarm
                R1Toast.Level.DEBUG -> R1.InkSoft
            }
            Box(
                modifier = Modifier
                    .clip(R1.ShapeM)
                    .background(R1.Bg.copy(alpha = 0.96f))
                    .border(1.dp, accent.copy(alpha = 0.65f), R1.ShapeM)
                    .r1Pressable(
                        onClick = {
                            // Toggle expand/collapse. Long-press dismisses the
                            // toast outright so users can clear a stuck error
                            // without waiting out the timer.
                            expanded.value = !expanded.value
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (expanded.value) {
                    ExpandedBody(event, accent, onDismiss = {
                        current.value = null
                        expanded.value = false
                    })
                } else {
                    CompactBody(event, accent)
                }
            }
        }
    }
}

@Composable
private fun CompactBody(event: R1Toast.Event, accent: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = event.level.name.first().toString(),
            style = R1.labelMicro,
            color = accent,
        )
        Spacer(Modifier.padding(end = 6.dp))
        Text(
            text = event.tag,
            style = R1.labelMicro,
            color = R1.InkSoft,
        )
        Spacer(Modifier.padding(end = 6.dp))
        Text(
            text = event.shortText,
            style = R1.body,
            color = R1.Ink,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExpandedBody(event: R1Toast.Event, accent: Color, onDismiss: () -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${event.level.name} · ${event.tag}",
                style = R1.labelMicro,
                color = accent,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = onDismiss)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Text(text = "DISMISS", style = R1.labelMicro, color = R1.InkSoft)
            }
        }
        Spacer(Modifier.padding(top = 6.dp))
        val scroll = rememberScrollState()
        Text(
            text = event.fullText,
            style = R1.body,
            color = R1.Ink,
            modifier = Modifier
                .heightIn(max = 220.dp)
                .verticalScroll(scroll),
        )
    }
}
