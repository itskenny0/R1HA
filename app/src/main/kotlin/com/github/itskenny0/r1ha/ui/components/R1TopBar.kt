package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * The single canonical top-bar for every sub-screen: an optional chevron-back, the screen
 * title, an optional trailing action slot, and a 1dp hairline rule. This is the one way to
 * head a screen so Settings, the registry browsers, Logbook, About, and the pickers all stay
 * aligned to the pixel and any future restyling lands here.
 *
 * The chevron lives flush-left so its visual centre lines up with the content gutter the
 * rows below use ([R1.space.l]). [title] renders in [R1.screenTitle]; pass it already
 * uppercased when you want all-caps chrome (most browser screens do).
 *
 * [onBack] is nullable: pass null for a top-level surface that has no back affordance (the
 * title then starts flush at the gutter). All current call sites pass a handler, so this is
 * a source-compatible widening.
 */
@Composable
fun R1TopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    /** Optional trailing-edge slot, usually a small [R1Chip] such as REFRESH or DISMISS ALL.
     *  Pushed to the right edge with the title taking the remaining width. */
    action: (@Composable () -> Unit)? = null,
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = R1.MinTarget)
                .padding(
                    start = if (onBack != null) R1.space.xs else R1.space.l,
                    end = R1.space.l,
                    top = R1.space.xs,
                    bottom = R1.space.xs,
                ),
        ) {
            if (onBack != null) {
                ChevronBack(onClick = onBack)
                Spacer(Modifier.width(R1.space.xs))
            }
            // Title always takes the weight so a trailing action sits flush against the
            // right gutter without shifting the title. Without an action the weight is
            // harmless (the title left-aligns either way).
            Text(
                title,
                style = R1.screenTitle,
                color = R1.Ink,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Generic pin / unpin affordance — lit by the ambient [LocalSurfacePinner]
            // whenever the current surface is pinnable. Drawn leading-of the screen's
            // own action so a screen that already passes an `action` keeps it flush at
            // the right gutter and the pin star sits just to its left. Screens that
            // pass no action get the pin star alone, still right-aligned.
            val pinner by rememberUpdatedState(LocalSurfacePinner.current)
            pinner?.takeIf { it.pinnable }?.let { ctl ->
                Spacer(Modifier.width(R1.space.s))
                PinToggle(pinned = ctl.isPinned, onClick = ctl.toggle)
            }
            if (action != null) {
                Spacer(Modifier.width(R1.space.s))
                action()
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(R1.Hairline),
        )
    }
}
