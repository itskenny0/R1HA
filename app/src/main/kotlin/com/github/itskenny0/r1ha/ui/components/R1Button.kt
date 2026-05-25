package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Primary action button in the R1 idiom. Sharp 4dp slot with an accent fill and Bg ink;
 * monospace label spaced like a hardware-panel switch. No Material elevation, no ripple
 * (the press feedback comes from [r1Pressable]). The only reason to reach for Material's
 * `Button` over this is if you actually need a Material container — never on a polished R1
 * surface.
 *
 * Use [variant] to switch between filled-accent (primary actions) and outlined-hairline
 * (secondary actions, "cancel"-style).
 */
enum class R1ButtonVariant { Filled, Outlined }

@Composable
fun R1Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    variant: R1ButtonVariant = R1ButtonVariant.Filled,
    accent: Color = R1.AccentWarm,
    leadingContent: (@Composable () -> Unit)? = null,
) {
    val containerColor = when {
        !enabled -> R1.SurfaceMuted
        variant == R1ButtonVariant.Filled -> accent
        else -> Color.Transparent
    }
    val textColor = when {
        !enabled -> R1.InkMuted
        variant == R1ButtonVariant.Filled -> R1.Bg
        else -> accent
    }
    val borderColor = when {
        !enabled -> R1.Hairline
        variant == R1ButtonVariant.Filled -> accent
        else -> accent
    }
    Box(
        modifier = modifier
            .clip(R1.ShapeM)
            .background(containerColor)
            .border(1.dp, borderColor, R1.ShapeM)
            .then(if (enabled) Modifier.r1Pressable(onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            if (leadingContent != null) {
                leadingContent()
            }
            Text(text = text, style = R1.labelMicro, color = textColor)
        }
    }
}
