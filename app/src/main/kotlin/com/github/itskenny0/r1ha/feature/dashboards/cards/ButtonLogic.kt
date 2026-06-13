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

/**
 * The past-tense confirmation verb that crossfades in OVER [buttonTapHint] for a
 * beat right after the action is dispatched ("SENT" for an IR blast, "FIRED" for
 * a press, "DONE" for a generic run). It is the worded half of the FIRED
 * confirmation; the radiating signal pulse is the drawn half. Tense-matched to
 * the affordance verb so the face reads "TAP TO SEND" then flips to "SENT".
 *
 * Returns null exactly when [buttonTapHint] does (an inert surface never claims
 * to have fired), so the renderer can gate both on one nullability check.
 *
 * Pure (no Compose) so the verb table is unit-tested directly.
 */
fun buttonSentLabel(action: LovelaceAction?): String? = when (action) {
    null -> null
    is LovelaceAction.CallService -> when {
        action.service == "remote.send_command" -> "SENT"
        action.service.endsWith(".press") -> "FIRED"
        action.service.endsWith(".toggle") -> "TOGGLED"
        else -> "DONE"
    }
    is LovelaceAction.Navigate, is LovelaceAction.Url -> "OPENED"
    is LovelaceAction.Builtin -> when (action.name) {
        "toggle" -> "TOGGLED"
        "more-info" -> "OPENED"
        "assist" -> "LISTENING"
        else -> null
    }
    is LovelaceAction.Invalid -> null
}

/**
 * Whether a button's resolved tap action is a FIRE-and-forget SIGNAL: an IR / RF
 * blast (`remote.send_command`), an automation trigger, a scene / script run, or
 * a `*.press`. These are one-shot launches with no toggled-on counter-state, so
 * the face plays the full radiating signal pulse (concentric rings flung outward
 * from the actuator) on each dispatch, the unmistakable "it fired" cue. A toggle
 * (a light, a switch) is NOT a signal: it has an on/off state the disc already
 * reflects, so it gets a gentler flare instead of the launch animation.
 *
 * `navigate` / `url` / `more-info` / `assist` are navigations, not actuations,
 * so they return false; an [LovelaceAction.Invalid] or null never fired at all.
 *
 * Pure (no Compose) so the classification is unit-tested directly.
 */
fun buttonFiresSignal(action: LovelaceAction?): Boolean = when (action) {
    is LovelaceAction.CallService -> when {
        action.service.endsWith(".toggle") -> false
        action.service.endsWith(".turn_off") -> false
        // turn_on of a scene / script reads as a launch; turn_on of a light is a
        // toggle-ish set, but a button card firing light.turn_on is rare and the
        // pulse still reads fine, so the simple rule (every non-toggle service
        // fires) keeps the table free of per-domain sniffing.
        else -> true
    }
    // Builtins (toggle / more-info / assist / none) are toggles or navigations,
    // never a fire-and-forget signal; navigate / url / invalid / null likewise.
    else -> false
}

/**
 * The icon slug a button card should draw, overriding the stored `icon:` ONLY
 * when the resolved tap fires `remote.send_command`. The user's existing pinned
 * IR cards were generated before the icon set learned `mdi:remote`, so they
 * carry whatever slug the editor defaulted to (a cog / robot that reads as a
 * settings tile). Forcing the remote glyph for the unmistakable send service
 * fixes those in place without a re-pin, while every other button keeps its
 * configured [configIcon] untouched (a scene stays a scene, an automation keeps
 * its robot if the user chose one).
 *
 * Returns the slug to draw: "remote" for a send_command tap, else [configIcon]
 * verbatim (which may itself be null, leaving the entity / domain fallback to
 * run). Pure (no Compose) so the override decision is unit-tested directly.
 */
fun buttonIconSlug(tapAction: LovelaceAction?, configIcon: String?): String? {
    val firesRemote = tapAction is LovelaceAction.CallService &&
        tapAction.service == "remote.send_command"
    return if (firesRemote) "remote" else configIcon
}

/**
 * Split an optional leading bracket tag off a button name so the face can render
 * it as a subtle inline badge instead of shouting "[IR]" in the title. Returns a
 * (badge, rest) pair: badge is the bracket contents upper-cased ("IR"), rest is
 * the remainder trimmed. When the name has no clean leading `[...]` tag, or the
 * tag / remainder would be empty, returns (null, the original name) so an odd
 * name (a bare "[", "[]", "[only]") never crashes or loses text.
 *
 * Pure (no Compose) so the parse is unit-tested directly.
 */
fun buttonNameBadge(name: String): Pair<String?, String> {
    val trimmed = name.trim()
    if (!trimmed.startsWith("[")) return null to name
    val close = trimmed.indexOf(']')
    if (close <= 1) return null to name // "[" or "[]" — nothing usable inside.
    val tag = trimmed.substring(1, close).trim()
    val rest = trimmed.substring(close + 1).trim()
    // A tag with no following label (e.g. "[scene]") would leave the face
    // nameless; keep the original name intact rather than badge-only.
    if (tag.isEmpty() || rest.isEmpty()) return null to name
    return tag.uppercase() to rest
}

/**
 * Per-ring geometry for the radiating signal pulse, factored out of the Canvas
 * so the easing is unit-testable without a UI harness. One concentric ring is
 * launched from the actuator on every fire; [ringIndex] (0-based) staggers the
 * later rings so they trail the first like a sonar ping rather than all flying
 * out in lockstep.
 *
 * @param progress   the shared 0..1 animation clock for this fire (0 at the tap,
 *                   1 when the pulse has fully dissipated).
 * @param ringIndex  which ring this is (0 = leading edge, higher = trails).
 * @param ringCount  total rings in the burst, used to space the stagger.
 *
 * Returns a [PulseRing]: `radiusFraction` is 0..1 of the maximum reach (the
 * caller multiplies by the real max radius in px) and `alpha` is 0..1 opacity.
 * A ring that has not started yet (its staggered slice of the clock hasn't begun)
 * or has fully faded returns alpha 0 so the caller can skip drawing it. Radius
 * eases out (fast launch, decelerating) and alpha decays so the ring thins as it
 * widens, the signal-dissipating-into-the-room look.
 *
 * Pure (no Compose, no Canvas) so the curve is asserted directly in a unit test.
 */
data class PulseRing(val radiusFraction: Float, val alpha: Float)

fun pulseRing(progress: Float, ringIndex: Int, ringCount: Int): PulseRing {
    if (progress <= 0f || progress >= 1f) return PulseRing(0f, 0f)
    val rings = ringCount.coerceAtLeast(1)
    // Each ring owns a window of the clock starting at a staggered offset and
    // running to the end. Stagger spans the first ~35% so trailing rings still
    // have most of the clock to travel and don't get truncated.
    val stagger = 0.35f
    val start = (ringIndex.toFloat() / rings) * stagger
    if (progress < start) return PulseRing(0f, 0f)
    val span = (1f - start).coerceAtLeast(0.0001f)
    val local = ((progress - start) / span).coerceIn(0f, 1f)
    // Radius eases out: 1 - (1-t)^2 launches fast then decelerates as it reaches
    // the rim, the way a real expanding wavefront slows against the room.
    val radiusFraction = 1f - (1f - local) * (1f - local)
    // Alpha decays linearly to zero so the ring is brightest at birth and gone
    // by the time it reaches max radius.
    val alpha = (1f - local).coerceIn(0f, 1f)
    return PulseRing(radiusFraction, alpha)
}
