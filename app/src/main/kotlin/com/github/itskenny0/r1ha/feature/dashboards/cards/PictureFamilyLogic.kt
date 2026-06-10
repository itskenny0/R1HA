package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.EntityRow
import com.github.itskenny0.r1ha.core.lovelace.LovelaceAction
import com.github.itskenny0.r1ha.core.lovelace.PicturePosition

/**
 * Pure decision logic for the picture-card family (glance grouping, state-icon
 * active colouring, element default actions, percentage-to-pixel anchoring).
 * Kept Compose-free and IO-free so each rule is unit-tested directly, mirroring
 * the HA frontend's `stateActive` / `DOMAINS_TOGGLE` semantics.
 */

/**
 * HA's `DOMAINS_TOGGLE` (src/common/const.ts): the domains whose picture-glance
 * chip defaults to a `toggle` tap (and renders in the right-hand "toggle" group)
 * rather than `more-info` (left-hand "dialog" group).
 */
internal val DOMAINS_TOGGLE = setOf(
    "fan", "input_boolean", "light", "switch", "group", "automation",
    "humidifier", "valve",
)

/** The two ordered groups a picture-glance chip row splits into, mirroring HA's
 *  `_entitiesDialog` (left) and `_entitiesToggle` (right). */
internal data class GlanceGroups(
    val dialog: List<EntityRow>,
    val toggle: List<EntityRow>,
)

/**
 * Split a picture-glance `entities:` list the way HA does: an entity whose
 * domain is NOT in [DOMAINS_TOGGLE] (or any entity when [forceDialog]) goes to
 * the left "dialog" group (default tap = more-info); the rest go to the right
 * "toggle" group (default tap = toggle). Order within each group is preserved.
 */
internal fun glanceGroups(entities: List<EntityRow>, forceDialog: Boolean): GlanceGroups {
    val dialog = ArrayList<EntityRow>()
    val toggle = ArrayList<EntityRow>()
    for (row in entities) {
        val domain = row.entityId.substringBefore('.', missingDelimiterValue = "")
        if (forceDialog || domain !in DOMAINS_TOGGLE) dialog.add(row) else toggle.add(row)
    }
    return GlanceGroups(dialog, toggle)
}

/**
 * The default tap action HA applies to a picture-glance chip given its group:
 * `more-info` for the dialog group, `toggle` for the toggle group. An explicit
 * per-row `tap_action` overrides this (handled by the caller).
 */
internal fun glanceChipDefaultTap(entityId: String, dialogGroup: Boolean): LovelaceAction =
    if (dialogGroup) LovelaceAction.Builtin("more-info", entityId)
    else LovelaceAction.Builtin("toggle", entityId)

/**
 * HA's `stateActive` (src/common/entity/state_active.ts): whether an entity is
 * in an "active" state and should render with the full (coloured / bright) icon
 * rather than the dimmed off-state. Domain-aware so e.g. a closed cover, a
 * docked vacuum, or a locked lock all read as inactive.
 *
 * Pure over the raw state string + domain; takes them directly so it can be
 * exercised without an [EntityState].
 */
internal fun stateActive(domain: String, rawState: String?): Boolean {
    val s = rawState?.lowercase()
    if (domain in setOf("button", "event", "input_button", "scene")) {
        return s != "unavailable"
    }
    if (s == null || s == "unavailable" || s == "unknown") return false
    if (s == "off" && domain != "alert") return false
    return when (domain) {
        "alarm_control_panel" -> s != "disarmed"
        "alert" -> s != "idle"
        "cover" -> s != "closed"
        "device_tracker", "person" -> s != "not_home"
        "lawn_mower" -> s !in setOf("docked", "paused")
        "lock" -> s != "locked"
        "media_player" -> s != "standby"
        "vacuum" -> s !in setOf("idle", "docked", "paused")
        "valve" -> s != "closed"
        "plant" -> s == "problem"
        "group" -> s in setOf("on", "home", "open", "locked", "problem")
        "timer" -> s == "active"
        "camera" -> s == "streaming"
        else -> true
    }
}

/** [stateActive] over an [EntityState], deriving the domain from the entity id. */
internal fun stateActive(entityId: String, state: EntityState?): Boolean {
    val domain = entityId.substringBefore('.', missingDelimiterValue = "")
    return stateActive(domain, state?.rawState)
}

/**
 * Resolve a picture-element's tap action. HA's element default is `more-info`
 * on the element's own entity; an explicit [tapAction] wins (bound to the
 * entity when it carries none). An element with neither a tap action nor an
 * entity is inert (null), and an explicit `none` action stays inert.
 */
internal fun elementTapAction(tapAction: LovelaceAction?, entityId: String?): LovelaceAction? {
    if (tapAction != null) {
        if (tapAction is LovelaceAction.Builtin && tapAction.name == "none") return null
        return tapAction.boundTo(entityId)
    }
    return entityId?.let { LovelaceAction.Builtin("more-info", it) }
}

/**
 * Convert a [PicturePosition] anchor into an absolute pixel offset within an
 * image box of [boxPx] px along that axis. Percentages scale against the box;
 * pixel values pass through unchanged. The result is the element's anchor point
 * before the centring transform is applied.
 */
internal fun anchorPx(pos: PicturePosition, boxPx: Float): Float =
    if (pos.isPixel) pos.value.toFloat() else (pos.value / 100.0 * boxPx).toFloat()

/**
 * Whether the element keeps HA's default `translate(-50%,-50%)` centring (true)
 * or supplies its own transform that we should not second-guess (false). A
 * `none` / `translate(0,0)`-style override means anchor at the top-left corner
 * instead of the centre.
 */
internal fun elementCentersOnAnchor(transformOverride: String?): Boolean {
    if (transformOverride == null) return true
    val t = transformOverride.trim().lowercase()
    // Only the default centring transform (or an absent one) centres the element;
    // any explicit transform anchors at the raw point so we don't double-apply.
    return t.replace(" ", "").contains("translate(-50%,-50%)")
}
