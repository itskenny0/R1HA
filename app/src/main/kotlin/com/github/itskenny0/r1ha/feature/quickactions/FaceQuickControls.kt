package com.github.itskenny0.r1ha.feature.quickactions

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.github.itskenny0.r1ha.core.theme.LocalEntityOverrides
import com.github.itskenny0.r1ha.core.theme.LocalOnEntityCall
import com.github.itskenny0.r1ha.core.theme.LocalOnSetEntityPercent
import com.github.itskenny0.r1ha.core.theme.R1
import com.github.itskenny0.r1ha.ui.components.r1Pressable

/**
 * Inline quick-controls row rendered directly on a focused card face. Surfaces the
 * primary quick-action group for the entity as a compact, horizontally-scrollable row
 * of chips — the same actions the Quick Sheet exposes on long-press, but always
 * visible so the user does not need to open a sheet for the most common controls.
 *
 * Chip dispatch routes through [LocalOnEntityCall] / [LocalOnSetEntityPercent], the
 * same composition locals [QuickActionSheet] uses. On previews or any host that does
 * not provide those locals the chips still render; [QuickAction.onFire] is a no-op.
 *
 * Gating note: this composable is purely additive. The caller (the card renderer)
 * decides WHEN to show it based on its own focus and per-entity settings state; no
 * focus or opt-in logic lives here.
 *
 * @param state      the card's entity state, forwarded to [buildQuickActions].
 * @param modifier   applied to the outer horizontally-scrollable [Row].
 * @param maxActions cap on how many chips to show. Surplus actions are not rendered;
 *                   domain builders put the most-used actions first by convention.
 *                   Defaults to 4.
 */
@Composable
fun FaceQuickControlRow(
    state: EntityState,
    modifier: Modifier = Modifier,
    maxActions: Int = 4,
) {
    val override = LocalEntityOverrides.current[state.id.value] ?: EntityOverride.NONE
    val onCall = LocalOnEntityCall.current
    val onPct = LocalOnSetEntityPercent.current

    val ctx = QuickActionContext(
        state = state,
        override = override,
        onEntityCall = { call -> onCall?.invoke(call) },
        onSetPercent = { id, pct -> onPct?.invoke(id, pct) },
        dismiss = {},
    )
    val groups = buildQuickActions(ctx)
    // Only the first (primary) group is surfaced here. A sheet can afford a titled
    // multi-group layout; a card face cannot. If there are no primary actions this
    // composable emits nothing so the card layout is not disturbed.
    val primary = groups.firstOrNull()?.takeIf { it.actions.isNotEmpty() } ?: return

    val scrollState = rememberScrollState()
    Row(
        modifier = modifier.horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(R1.space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (action in primary.actions.take(maxActions)) {
            FaceQuickChip(action)
        }
    }
}

/**
 * A single compact quick-action chip for [FaceQuickControlRow]. Mirrors the chip
 * style from [QuickActionSheet] with reduced padding so it sits flush in the card
 * face. Shows the glyph when present (icon-only, maximally compact), falling back to
 * the label text when there is no glyph. The [QuickAction.accentArgb] colour tints
 * border and content for swatch-style chips; selected chips receive a faint accent
 * fill to indicate the current active state.
 */
@Composable
private fun FaceQuickChip(action: QuickAction) {
    val accentColor = action.accentArgb?.let { Color(it) }
    val borderColor = accentColor ?: if (action.selected) R1.AccentWarm else R1.Hairline
    val fillColor = if (action.selected) {
        (accentColor ?: R1.AccentWarm).copy(alpha = 0.18f)
    } else {
        Color.Transparent
    }
    val contentColor = accentColor ?: if (action.selected) R1.AccentWarm else R1.Ink

    Box(
        modifier = Modifier
            .clip(R1.ShapeS)
            .background(fillColor)
            .border(1.dp, borderColor, R1.ShapeS)
            .r1Pressable(onClick = { action.onFire() }, contentDescription = action.label)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        // Prefer the glyph when present (denser, icon-only chip); fall back to the
        // label text so text-only actions are always legible. A Row with both would
        // be too wide at arm's length on the R1's compact face real-estate.
        Text(
            text = action.glyph ?: action.label,
            style = R1.labelMicro,
            color = contentColor,
        )
    }
}
