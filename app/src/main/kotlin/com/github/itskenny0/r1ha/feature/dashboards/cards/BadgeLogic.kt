package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceBadge
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Resolve the accent colour for a badge chip, mirroring HA's
 * `_computeStateColor` in `hui-entity-badge.ts`:
 *
 *  1. When a custom [configColor] is configured AND the entity is active,
 *     apply the mapped colour (inactive entities fall back to the state accent
 *     so the badge goes grey when off, matching HA's behaviour).
 *  2. No config colour: use the state-derived accent ([stateAccentFor]).
 *  3. No entity at all (entity-less shortcut/button badges): apply [configColor]
 *     unconditionally, falling back to [R1.InkSoft] when absent.
 *
 * The distinction between cases 1 and 3 matches HA:
 *  - entity badge: `color` applied only while `stateActive(stateObj)` is true.
 *  - shortcut/button badge: `color` is always the icon/text tint.
 */
internal fun badgeColorAccent(
    configColor: String?,
    entityId: String?,
    state: EntityState?,
): Color {
    if (entityId == null) {
        // Entity-less badge (shortcut, button): config colour applied unconditionally.
        return haColorAccent(configColor) ?: R1.InkSoft
    }
    val mapped = haColorAccent(configColor)
    return if (mapped != null) {
        // Custom colour: HA applies it only while the entity is active.
        val active = stateActive(entityId, state)
        if (active) mapped else stateAccentFor(entityId, state)
    } else {
        stateAccentFor(entityId, state)
    }
}

/**
 * Compose the badge's state value line from [badge] and [state].
 *
 * When [badge.stateContent] is non-empty, the token list is resolved via
 * [resolveStateContent] (shared with the tile card): each token is one of
 * "state", "last_changed", "last_updated", or an attribute key. The results
 * are joined with a space and blank results are omitted.
 *
 * When [badge.stateContent] is empty, falls back to [compactStateText].
 *
 * Returns null when the resulting text would be blank (so the caller
 * can omit a stray space next to the icon).
 */
internal fun badgeStateText(badge: LovelaceBadge, state: EntityState): String? {
    val text = if (badge.stateContent.isNotEmpty()) {
        resolveStateContent(badge.stateContent, state)
    } else {
        compactStateText(state)
    }
    return text.takeUnless { it.isBlank() }
}
