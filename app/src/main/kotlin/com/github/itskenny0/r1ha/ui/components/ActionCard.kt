package com.github.itskenny0.r1ha.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.util.areaLabel
import com.github.itskenny0.r1ha.core.theme.LocalCardFillSlot
import com.github.itskenny0.r1ha.core.theme.LocalCardInk
import com.github.itskenny0.r1ha.core.theme.R1
import kotlinx.coroutines.delay

/**
 * Card variant for stateless action entities — scenes, scripts, buttons. No on/off state,
 * no scalar to drive; the whole card is "fire the trigger". Layout mirrors SwitchCard's
 * header (DOMAIN · AREA, friendly name) so the deck reads cohesively, but the bottom
 * occupies a large ACTIVATE button instead of an on/off switch track.
 *
 * Pressing the button briefly flashes the label to "FIRED" + the button to a slightly
 * brighter accent so the user gets visible confirmation that their tap registered — HA's
 * state_changed event for these entities is just a timestamp bump and doesn't really
 * surface back through the existing reconciliation path, so we synthesise the feedback
 * locally.
 */
@Composable
fun ActionCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    onFire: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Local-feedback flash: when the user taps, hold "FIRED" for ~700 ms then return to
    // ACTIVATE. Pure UI state — the actual service-call goes through the same haRepository
    // path as everything else and gets the failure-toast/rollback treatment automatically.
    var fired by remember { mutableStateOf(false) }
    LaunchedEffect(fired) {
        if (fired) {
            delay(700L)
            fired = false
        }
    }

    val buttonColor by animateColorAsState(
        targetValue = if (fired) accent else accent.copy(alpha = 0.88f),
        label = "action-btn-color",
    )

    // Ink rides in from the theme via the EntityCard wrapper (white over a gradient
    // backdrop, the classic R1 greys everywhere else). The backdrop itself is painted
    // by the wrapper too, so the card stays theme-agnostic.
    val ink = LocalCardInk.current
    // Wrap mode (the DYNAMIC deck, LocalCardFillSlot = false): the card sizes
    // to its content so every control stays visible; fill mode keeps the
    // historical full-slot layout.
    val fillSlot = LocalCardFillSlot.current
    Column(
        modifier = modifier
            .then(if (fillSlot) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = 22.dp, vertical = 18.dp),
    ) {
        // ── Header ─────────────────────────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(width = 14.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(accent),
            )
            Spacer(Modifier.width(8.dp))
            Text(domainLabel, style = R1.labelMicro, color = ink.ink)
            Spacer(Modifier.width(8.dp))
            Text("· TRIGGER", style = R1.labelMicro, color = ink.muted)
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = ink.muted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = areaLabel(state.area),
                    style = R1.labelMicro,
                    color = ink.soft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = ink.ink,
            maxLines = 2,
        )
        // When the script last ran ("ran 2h ago"). last_triggered, not last_changed, so it
        // reflects the actual fire rather than a config reload. Hidden until it has fired.
        val ran = rememberRelativeTime(state.lastTriggered)
        if (ran.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(text = "ran $ran", style = R1.labelMicro, color = ink.muted, maxLines = 1)
        }

        // Fill mode floats the ACTIVATE button to the slot bottom; wrap mode
        // keeps a fixed gap so the card stays content-sized.
        if (fillSlot) Spacer(Modifier.weight(1f)) else Spacer(Modifier.height(14.dp))

        // ── Big ACTIVATE button ─────────────────────────────────────────────────────
        // Full-width 72dp tile, accent fill, monospace label centred. r1Pressable gives
        // the scale dip + haptic; the local `fired` flag swaps the label to FIRED for a
        // beat so the user sees their press registered.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .clip(R1.ShapeM)
                .background(buttonColor)
                .r1Pressable(
                    onClick = {
                        fired = true
                        onFire()
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                // ▶ glyph drawn in monospace for visual weight; "FIRED"/"ACTIVATE" label
                // swaps via the local fired state.
                Text(
                    text = if (fired) "● FIRED" else "▶ ACTIVATE",
                    style = R1.numeralM,
                    color = R1.Bg,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        // Secondary hint — clarifies that this is a one-shot trigger, not a toggle, so
        // users who hit it accidentally know there's no "undo" by tapping again.
        Text(
            text = if (state.isOn) "RUNNING…" else "TAP TO FIRE · NO TOGGLE",
            style = R1.labelMicro,
            color = ink.muted,
        )
    }
}
