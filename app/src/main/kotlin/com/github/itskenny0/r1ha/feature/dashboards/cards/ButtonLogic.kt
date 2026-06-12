package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.ui.graphics.Color
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
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
 * One deliberate R1HA divergence: a bare ACTION button (no entity bound, e.g. a
 * pinned IR-command button) takes the warm accent rather than HA's neutral. It
 * has no state to be "off", so the neutral grey read as a disabled control on
 * the near-black deck; the accent says "this fires something" at a glance.
 *
 * Pure (no Compose) so the precedence is unit-tested without a UI harness.
 */
fun buttonAccent(
    color: String?,
    stateColor: Boolean,
    entityId: String?,
    state: EntityState?,
): Color {
    haColorAccent(color)?.let { return it }
    if (entityId == null) return R1.AccentWarm
    if (!stateColor || state == null) return R1.InkSoft
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

/**
 * The all-caps micro affordance verb a button card prints under its label
 * ("TAP TO SEND" etc.), derived from the RESOLVED primary tap action. This is
 * what makes the face read as a pressable control rather than a passive
 * readout; the verb stays generic per action shape (no card-type or
 * integration sniffing) so one rule covers IR buttons, scripts, scenes and
 * navigation alike. `remote.send_command` is the one service called out by
 * name: "send" is the fire-and-forget semantic every remote/IR button shares.
 *
 * Null (no hint line) when the surface is inert: no tap action, an explicit
 * `none`, or an action we already know we cannot fire.
 *
 * Pure (no Compose) so the verb table is unit-tested directly.
 */
fun buttonTapHint(action: LovelaceAction?): String? = when (action) {
    null -> null
    is LovelaceAction.CallService -> when {
        action.service == "remote.send_command" -> "TAP TO SEND"
        action.service.endsWith(".press") -> "TAP TO PRESS"
        action.service.endsWith(".toggle") -> "TAP TO TOGGLE"
        else -> "TAP TO RUN"
    }
    is LovelaceAction.Navigate, is LovelaceAction.Url -> "TAP TO OPEN"
    is LovelaceAction.Builtin -> when (action.name) {
        "toggle" -> "TAP TO TOGGLE"
        "more-info" -> "TAP FOR INFO"
        "assist" -> "TAP TO ASK"
        else -> null
    }
    is LovelaceAction.Invalid -> null
}
