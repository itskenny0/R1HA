package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Resolve the accent colour a button card tints its icon and name with, applying
 * HA's precedence:
 *
 *  1. An explicit `color` (theme name or hex) always wins.
 *  2. Otherwise, when `state_color` is on, an active entity tints with the
 *     state-derived accent; an inactive / off entity stays neutral.
 *  3. With `state_color` off the button is neutral regardless of state.
 *
 * Pure (no Compose) so the precedence is unit-tested without a UI harness. The
 * neutral default matches HA's un-tinted button surface.
 */
fun buttonAccent(
    color: String?,
    stateColor: Boolean,
    entityId: String?,
    state: EntityState?,
): Color {
    haColorAccent(color)?.let { return it }
    if (!stateColor || entityId == null || state == null) return R1.InkSoft
    // An unavailable entity always reads red (a fault the user should see),
    // regardless of the neutral default, so defer to the state accent for it.
    if (!state.isAvailable) return stateAccentFor(entityId, state)
    // state_color on: an active (on) or otherwise non-off/unknown entity tints;
    // an off / unknown entity stays neutral so a wall of buttons isn't all warm.
    val raw = state.rawState.orEmpty()
    val active = state.isOn ||
        (!raw.equals("off", ignoreCase = true) && !raw.equals("unknown", ignoreCase = true) && raw.isNotBlank())
    return if (active) stateAccentFor(entityId, state) else R1.InkSoft
}

/**
 * Convert HA's `icon_height` CSS length to a dp value for the button icon.
 * Accepts a bare number ("48"), pixels ("48px"), or em ("2.5em", treated as
 * multiples of HA's 24px base icon). Anything unparseable returns null so the
 * caller keeps its default disc size. The result is clamped to a sane on-screen
 * range so a typo'd "9999px" can't blow out the 640x480 layout.
 *
 * Pure; returns a Float so the renderer wraps it in `.dp` without depending on
 * Compose units here.
 */
internal fun iconHeightDp(raw: String?): Float? {
    val s = raw?.trim()?.lowercase()?.takeUnless { it.isBlank() } ?: return null
    val px = when {
        s.endsWith("px") -> s.removeSuffix("px").trim().toFloatOrNull()
        s.endsWith("em") -> s.removeSuffix("em").trim().toFloatOrNull()?.let { it * 24f }
        else -> s.toFloatOrNull()
    } ?: return null
    if (px <= 0f) return null
    return px.coerceIn(16f, 96f)
}
