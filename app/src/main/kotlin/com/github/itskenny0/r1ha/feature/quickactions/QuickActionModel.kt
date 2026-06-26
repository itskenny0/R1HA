package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.ha.ServiceCall
import com.github.itskenny0.r1ha.core.prefs.EntityOverride

/**
 * One tappable quick-action chip in the Quick Sheet. A domain builder turns an entity's
 * current state into a list of these: "TURN OFF", "50%", "COOL", "OPEN", etc.
 *
 *  - [id]         stable identity within its group (used as a Compose key).
 *  - [label]      the short uppercase chip text the user reads / TalkBack announces.
 *  - [glyph]      optional leading symbol (a single emoji / codepoint); null = text only.
 *  - [accentArgb] optional packed-ARGB tint for the chip border + text, e.g. the card's
 *                 per-entity accent or a mode colour. Null = the warm accent / ink default.
 *  - [selected]   whether this chip is the entity's current state (the active mode, the
 *                 current preset); selected chips render filled rather than outlined.
 *  - [onFire]     fired on tap. A builder closes over [QuickActionContext] to dispatch the
 *                 matching [ServiceCall] / percent and, when appropriate, calls the
 *                 context's dismiss.
 */
data class QuickAction(
    val id: String,
    val label: String,
    val glyph: String? = null,
    val accentArgb: Int? = null,
    val selected: Boolean = false,
    val onFire: () -> Unit,
)

/**
 * A titled cluster of [QuickAction] chips. [title] is the small uppercase section label
 * rendered above the chips (e.g. "MODE", "PRESET", "SPEED"); null draws no header so a
 * single anonymous row of actions can sit flush under the card header.
 */
data class QuickActionGroup(
    val title: String?,
    val actions: List<QuickAction>,
)

/**
 * Everything a [QuickActionBuilder] needs to turn one entity into its quick-action groups.
 *
 *  - [state]        the entity's current state, the source of truth for which actions /
 *                   selected flags to surface.
 *  - [override]     the per-card [EntityOverride] (accent colour, favourite colours /
 *                   positions, etc.) so a builder can honour the user's customizations.
 *  - [onEntityCall] dispatch a discrete [ServiceCall] against the entity.
 *  - [onSetPercent] set the entity's scalar percent (brightness / volume / position /
 *                   setpoint), routed through the same path the value bar uses.
 *  - [dismiss]      close the Quick Sheet. A builder calls this from an action that should
 *                   complete and dismiss (e.g. a one-shot toggle); actions that the user
 *                   may want to repeat (stepping a percent) leave the sheet open.
 */
class QuickActionContext(
    val state: EntityState,
    val override: EntityOverride,
    val onEntityCall: (ServiceCall) -> Unit,
    val onSetPercent: (EntityId, Int) -> Unit,
    val dismiss: () -> Unit,
)

/**
 * Per-domain quick-action provider. [supports] claims an entity (by domain / capability);
 * [build] turns the claimed entity into the groups of chips the Quick Sheet renders. The
 * registry in [DOMAIN_QUICK_ACTION_BUILDERS] is scanned in order and the first builder that
 * claims the entity wins (see [buildQuickActions]).
 */
interface QuickActionBuilder {
    fun supports(state: EntityState): Boolean
    fun build(ctx: QuickActionContext): List<QuickActionGroup>
}
