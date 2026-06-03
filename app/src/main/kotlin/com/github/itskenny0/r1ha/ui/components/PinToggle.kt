package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The pin / unpin affordance drawn in a screen's [R1TopBar] when [LocalSurfacePinner]
 * exposes a pinnable surface. Filled pin mark + warm accent when pinned, hollow mark +
 * soft ink when not. 44dp tap target, [r1Pressable] feedback, no Material ripple, in
 * keeping with [ChevronBack] and the rest of the bar's chrome language.
 *
 * Drawn as a glyph rather than an [com.github.itskenny0.r1ha.ui.icons.R1Icons] vector
 * because the top bar's action row is text-glyph territory (matching the rail / drawer
 * marks) and a single character keeps the bar's metrics stable next to a screen's own
 * action chip.
 */
@Composable
fun PinToggle(
    pinned: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(44.dp)
            .r1Pressable(
                onClick = onClick,
                contentDescription = if (pinned) "Unpin from side panel" else "Pin to side panel",
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (pinned) "★" else "☆",
            style = R1.numeralM,
            color = if (pinned) R1.AccentWarm else R1.InkSoft,
        )
    }
}
