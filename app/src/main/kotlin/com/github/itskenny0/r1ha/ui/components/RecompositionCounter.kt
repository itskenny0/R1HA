package com.github.itskenny0.r1ha.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import com.github.itskenny0.r1ha.ui.i18n.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.BuildConfig
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Debug-only overlay that counts how many times its surrounding composable is
 * recomposed since first composition. Wrap a Box around a composable subtree
 * with [RecompositionBadge] aligned to a corner to spot recomposition hot
 * spots while iterating on stability annotations + remember keys.
 *
 * Counts via a remember-mutated counter inside a Composable that always
 * re-runs when its parent recomposes. The value reads through Compose's
 * snapshot system so the overlay tracks live recomposition counts even
 * within a skippable parent.
 *
 * Compiled in only for debug builds — release sees an empty function.
 */
@Composable
fun RecompositionBadge(
    tag: String = "",
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) return
    // `remember { Counter() }` survives across recompositions; on each recompose
    // we bump the counter and read its current value. The counter itself is a
    // plain `IntArray(1)` rather than a MutableState so the badge doesn't
    // trigger its own recomposition — we just sample the current count.
    val counter = remember { IntArray(1) }
    counter[0]++
    Box(
        modifier = modifier
            .clip(R1.ShapeS)
            .background(Color(0xCCFF6600))
            .padding(horizontal = 6.dp, vertical = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = if (tag.isBlank()) "${counter[0]}" else "$tag: ${counter[0]}",
            style = R1.labelMicro,
            color = Color.White,
        )
    }
}
