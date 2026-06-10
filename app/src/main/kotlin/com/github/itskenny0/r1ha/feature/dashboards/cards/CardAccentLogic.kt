package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.theme.R1

/**
 * Pure colour-accent decisions shared by the glance and tile renderers. Kept out
 * of the Compose composables so each branch is unit-testable; the renderers pass
 * the live [EntityState] and config flags in and use the returned [Color]
 * directly.
 */

/**
 * Resolve the effective `state_color` flag for a single glance/entity tile.
 * HA's `hui-glance-card` reads `entityConf.state_color ?? config.state_color`,
 * so a per-entity override wins and the card-level flag is the fallback (which
 * itself defaults true).
 */
internal fun effectiveStateColor(cardStateColor: Boolean, rowStateColor: Boolean?): Boolean =
    rowStateColor ?: cardStateColor

/**
 * The icon accent for a glance tile. When `state_color` is on we use the shared
 * [stateAccentFor] palette (warm when on, red when unavailable, ...). When it is
 * off HA still flags an unavailable entity (it greys the whole tile, but the
 * red signal is the closest R1 equivalent and stays useful on the small screen);
 * present-and-available entities read neutral so the grid stays calm.
 */
internal fun glanceTileAccent(
    entityId: String,
    state: EntityState?,
    stateColor: Boolean,
): Color {
    if (stateColor) return stateAccentFor(entityId, state)
    if (state == null) return R1.InkMuted
    if (!state.isAvailable) return R1.StatusRed
    return R1.InkSoft
}

/**
 * The icon accent for a tile card, honouring HA's `state_color` and a light's
 * live `rgb_color`. Precedence mirrors `hui-tile-card`:
 *  1. an unavailable entity always reads red (a dead tile shouldn't wear its
 *     decorative colour);
 *  2. an explicit config `color` (theme name / hex) wins while the entity is
 *     active (HA colours the icon by `color` only when on);
 *  3. otherwise, when the entity is an `on` light reporting `rgb_color`, the icon
 *     takes that colour (contrast is left to the small R1 palette);
 *  4. otherwise the state-derived accent when `state_color` is on, or a neutral
 *     on/off accent when it is off.
 * [configAccent] is the pre-resolved [haColorAccent] of the config `color` (null
 * when unset), so this stays Compose-free and unit-testable.
 */
internal fun tileIconAccent(
    entityId: String,
    state: EntityState?,
    configAccent: Color?,
    stateColor: Boolean,
): Color {
    if (state != null && !state.isAvailable) return R1.StatusRed
    val active = state?.isOn == true && state.isAvailable
    if (active) {
        configAccent?.let { return it }
        // A light that is on and reporting rgb_color tints its own icon.
        if (entityId.startsWith("light.")) {
            rgbColorOf(state)?.let { return it }
        }
    }
    if (stateColor) return stateAccentFor(entityId, state)
    if (state == null) return R1.InkMuted
    return if (state.isOn) R1.AccentWarm else R1.InkSoft
}
