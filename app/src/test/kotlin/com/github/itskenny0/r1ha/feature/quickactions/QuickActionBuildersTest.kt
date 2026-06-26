package com.github.itskenny0.r1ha.feature.quickactions

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.EntityOverride
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class QuickActionBuildersTest {

    private fun sampleState(id: String = "light.kitchen") = EntityState(
        id = EntityId(id),
        friendlyName = "Kitchen",
        area = null,
        isOn = true,
        percent = null,
        raw = null,
        lastChanged = Instant.EPOCH,
        isAvailable = true,
    )

    private fun ctxFor(state: EntityState) = QuickActionContext(
        state = state,
        override = EntityOverride.NONE,
        onEntityCall = {},
        onSetPercent = { _, _ -> },
        dismiss = {},
    )

    @Test fun `an unclaimed domain falls through to generic with no groups`() {
        // Domain builders are registered, but none claims a plain sensor, so resolution
        // falls through to GenericQuickActions, which carries no chips.
        assertThat(DOMAIN_QUICK_ACTION_BUILDERS).isNotEmpty()
        assertThat(buildQuickActions(ctxFor(sampleState("sensor.outside_temp")))).isEmpty()
    }

    @Test fun `a controllable domain is claimed by a registered builder`() {
        // A light is claimed by a domain builder, so buildQuickActions returns its groups
        // rather than the empty generic fallback.
        assertThat(buildQuickActions(ctxFor(sampleState("light.kitchen")))).isNotEmpty()
    }

    @Test fun `generic builder claims every entity and emits nothing`() {
        val state = sampleState("sensor.outside_temp")
        assertThat(GenericQuickActions.supports(state)).isTrue()
        assertThat(GenericQuickActions.build(ctxFor(state))).isEmpty()
    }

    @Test fun `the first builder whose supports returns true is selected over the fallback`() {
        // Mirrors the selection idiom inside buildQuickActions: a builder that claims the
        // entity wins and its groups are returned instead of the generic fallback's.
        val claimed = QuickActionGroup(
            title = "TEST",
            actions = listOf(QuickAction(id = "x", label = "X", onFire = {})),
        )
        val builder = object : QuickActionBuilder {
            override fun supports(state: EntityState): Boolean = true
            override fun build(ctx: QuickActionContext): List<QuickActionGroup> = listOf(claimed)
        }
        val ctx = ctxFor(sampleState())
        val selected = listOf(builder).firstOrNull { it.supports(ctx.state) }?.build(ctx)
            ?: GenericQuickActions.build(ctx)
        assertThat(selected).containsExactly(claimed)
    }

    @Test fun `QuickAction onFire invokes its lambda`() {
        var fired = false
        val action = QuickAction(id = "toggle", label = "TOGGLE", onFire = { fired = true })
        action.onFire()
        assertThat(fired).isTrue()
    }
}
