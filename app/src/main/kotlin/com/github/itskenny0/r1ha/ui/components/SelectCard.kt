package com.github.itskenny0.r1ha.ui.components

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Read/write card variant for `select.*` / `input_select.*` entities — a settable enum
 * (Server Fan Mode = auto/manual, vacuum-room-select = kitchen/lounge/bedroom, etc.).
 * The current option is the big readout; tapping the card opens a full-screen picker
 * listing every option for one-tap switching. The wheel cycles through options
 * (handled at the screen layer via [LocalOnCycleSelectOption]) so a quick spin moves
 * between values without leaving the card.
 *
 * Sizing uses the same fluid type ramp as SensorCard — option strings can be long
 * (e.g. "Eco - reduced power" / "Boost (next 30 min)"), so the readout auto-shrinks
 * to fit rather than truncating mid-word. The per-card sp override still wins when
 * set explicitly via the customize dialog.
 */
@Composable
fun SelectCard(
    state: EntityState,
    accent: Color,
    domainLabel: String,
    showArea: Boolean,
    /**
     * Absolute readout size in sp from [EntityOverride.textSizeSp]. Null = the smart
     * default scaled by string length, matching [SensorCard]'s behaviour so option
     * strings of varying length all sit on a single card without truncation.
     */
    textSizeSp: Int? = null,
    /**
     * When the resolved per-card [EntityOverride.wheelEnabled] is false (either explicit
     * per-card or via the per-domain default for select / input_select), the
     * "WHEEL CYCLES OPTIONS" hint is suppressed so it doesn't lie about an affordance
     * the user has turned off.
     */
    wheelEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val onOpenPicker = com.github.itskenny0.r1ha.core.theme.LocalOnOpenSelectPicker.current
    val current = state.currentOption ?: state.rawState?.takeIf { it != "unknown" && it != "unavailable" }
    val display = current?.uppercase() ?: "—"
    val (bodyStyle, _) = selectReadoutStyle(display, textSizeSp)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(R1.Bg)
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
            Text(domainLabel, style = R1.labelMicro, color = R1.Ink)
            Spacer(Modifier.width(8.dp))
            Text("· ${state.selectOptions.size} OPTIONS", style = R1.labelMicro, color = R1.InkMuted)
            if (showArea && !state.area.isNullOrBlank()) {
                Spacer(Modifier.width(8.dp))
                Text("·", style = R1.labelMicro, color = R1.InkMuted)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = state.area.replace('_', ' ').uppercase(),
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = state.friendlyName,
            style = R1.titleCard,
            color = R1.Ink,
            maxLines = 2,
        )
        Spacer(Modifier.height(20.dp))

        // ── Current option — the big readout. softWrap so long enum strings fit. ──
        Text(
            text = display,
            style = bodyStyle,
            color = if (current != null) accent else R1.InkSoft,
            softWrap = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(14.dp))

        // ── CHOOSE button — opens the picker overlay at screen scope. Fills width
        // for thumb-friendly tap area. Wheel-cycle is wired at the CardStackScreen
        // layer so an explicit button isn't the only way to change the value.
        if (state.selectOptions.isNotEmpty() && onOpenPicker != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(R1.ShapeS)
                    .background(R1.SurfaceMuted)
                    .r1Pressable(onClick = { onOpenPicker(state.id) })
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "CHOOSE OPTION",
                    style = R1.labelMicro,
                    color = R1.InkSoft,
                )
            }
            if (wheelEnabled) {
                Spacer(Modifier.height(6.dp))
                // Hint about the wheel — first-time users won't know the wheel cycles
                // options unless we tell them. Subtle InkMuted so it doesn't dominate.
                // Suppressed when wheel-cycling is off for this card (per-card override
                // or per-domain default) so the hint never claims an affordance that
                // doesn't work.
                Text(
                    text = "WHEEL CYCLES OPTIONS",
                    style = R1.labelMicro,
                    color = R1.InkMuted,
                )
            }
        }

        Spacer(Modifier.weight(1f))
    }
}

/**
 * Compute the body [androidx.compose.ui.text.TextStyle] for the SelectCard's current
 * option readout. Mirrors [SensorCard]'s sensorReadoutStyle: when the per-card sp
 * override is set use it verbatim; otherwise pick from a length-based ramp so a
 * 30-character enum like "AUTO (NEXT 30 MINUTES)" still fits without truncation
 * while a short "AUTO" gets the full punchy 72 sp treatment.
 */
private fun selectReadoutStyle(
    value: String,
    override: Int?,
): Pair<androidx.compose.ui.text.TextStyle, androidx.compose.ui.text.TextStyle> {
    val bodySp: Float = when {
        override != null -> override.toFloat()
        value.length <= 6 -> 72f
        value.length <= 10 -> 56f
        value.length <= 16 -> 40f
        value.length <= 28 -> 28f
        value.length <= 48 -> 20f
        else -> 16f
    }
    val tracking = if (bodySp < 24f) 0.sp else (-2).sp
    val body = R1.numeralXl.copy(
        fontSize = bodySp.sp,
        lineHeight = (bodySp * 1.15f).sp,
        letterSpacing = tracking,
    )
    return body to R1.numeralM
}
