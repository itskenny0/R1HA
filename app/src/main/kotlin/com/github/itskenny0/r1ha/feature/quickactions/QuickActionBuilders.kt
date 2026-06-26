package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.EntityState

/**
 * Ordered registry of per-domain quick-action providers. [buildQuickActions] scans this
 * list and the first builder whose [QuickActionBuilder.supports] claims the entity wins.
 *
 * Domain builders are registered here: light/climate/media first, then the remaining
 * controllable domains (cover/fan/lock/vacuum/remote/siren/valve/water_heater/humidifier).
 * Any entity no builder claims falls through to [GenericQuickActions] (no chips, manage
 * row only).
 */
internal val DOMAIN_QUICK_ACTION_BUILDERS: List<QuickActionBuilder> =
    lightClimateMediaQuickActionBuilders + extraDomainQuickActionBuilders

/**
 * Fallback builder for any entity no domain builder claims. It returns no groups: the
 * Quick Sheet's manage row already carries More Info / History for read-only entities, so
 * a sensor / device_tracker / unsupported domain opens straight to the manage actions with
 * no empty "quick actions" zone above them.
 */
object GenericQuickActions : QuickActionBuilder {
    override fun supports(state: EntityState): Boolean = true

    override fun build(ctx: QuickActionContext): List<QuickActionGroup> = emptyList()
}

/**
 * Resolve the quick-action groups for [ctx]'s entity: the first domain builder that claims
 * it wins, otherwise [GenericQuickActions] (which carries no chips).
 */
fun buildQuickActions(ctx: QuickActionContext): List<QuickActionGroup> =
    DOMAIN_QUICK_ACTION_BUILDERS.firstOrNull { it.supports(ctx.state) }?.build(ctx)
        ?: GenericQuickActions.build(ctx)
