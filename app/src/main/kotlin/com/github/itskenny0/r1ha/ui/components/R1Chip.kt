package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The one chip in the R1 design system. Replaces the half-dozen hand-rolled chip boxes that
 * had drifted apart across Devices (group toggles), Logbook (window picker, TAIL), Settings
 * (RESET / modified-count pills), and the various top-bar action chips. Pick a [variant] and
 * a [tone]; everything else (padding, border, fill alpha, text style) is fixed here so the
 * surfaces stay aligned to the pixel.
 *
 * Variants:
 *  - [R1ChipVariant.Filter]: a toggle. Selected = tinted fill + accent border + accent text;
 *    unselected = muted surface + hairline + soft ink. The standard "pick one of N" control.
 *  - [R1ChipVariant.Action]: a tap affordance (REFRESH, DISMISS ALL, TAIL). Muted surface,
 *    hairline border, soft-ink text; [tone] only colours the text/border when emphasised.
 *  - [R1ChipVariant.Pill]: a non-interactive status badge (DISABLED, a modified-count).
 *    Tinted fill in [tone] with no tap target; pass [onClick] = null.
 *
 * [tone] is the accent colour that drives selected/emphasis state (defaults to the R1
 * orange). For status pills pass the relevant status colour (e.g. `R1.StatusAmber`).
 *
 * Text is rendered uppercase in [R1.labelMicro] to match existing chrome; pass an already-
 * formatted [text]. A 32dp min height keeps chips on a comfortable wheel-tap target while
 * staying visually compact (full 48dp rows are for [R1Row]).
 */
enum class R1ChipVariant { Filter, Action, Pill }

@Composable
fun R1Chip(
    text: String,
    modifier: Modifier = Modifier,
    variant: R1ChipVariant = R1ChipVariant.Action,
    selected: Boolean = false,
    tone: Color = R1.AccentWarm,
    onClick: (() -> Unit)? = null,
    /** Optional leading glyph/content drawn before the label (e.g. a small dot or icon). */
    leadingContent: (@Composable () -> Unit)? = null,
    contentDescription: String? = null,
) {
    // Filter and Action share their visual treatment (the difference is intent:
    // Filter is a toggle that lives in a selected/unselected group, Action is a
    // one-shot tap). A non-Pill chip is "emphasised" when selected. Pill is always
    // tinted in its tone and carries no tap target.
    val emphasised = variant == R1ChipVariant.Pill || selected
    val containerColor: Color = if (emphasised) tone.copy(alpha = 0.18f) else R1.SurfaceMuted
    val borderColor: Color = when {
        variant == R1ChipVariant.Pill -> tone.copy(alpha = 0.5f)
        selected -> tone.copy(alpha = 0.6f)
        else -> R1.Hairline
    }
    val textColor: Color = if (emphasised) tone else R1.InkSoft

    val pressable = if (onClick != null) {
        Modifier.r1Pressable(onClick = onClick, contentDescription = contentDescription)
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(containerColor)
            .border(1.dp, borderColor, R1.ShapeS)
            .then(pressable)
            .heightIn(min = 32.dp)
            .padding(horizontal = R1.space.s, vertical = R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
    ) {
        if (leadingContent != null) leadingContent()
        Text(text = text, style = R1.labelMicro, color = textColor)
    }
}
