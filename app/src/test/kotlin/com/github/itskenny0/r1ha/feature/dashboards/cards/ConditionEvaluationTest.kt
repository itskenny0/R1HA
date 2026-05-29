package com.github.itskenny0.r1ha.feature.dashboards.cards

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ConditionEvaluationTest {

    private fun state(id: String, raw: String): Pair<EntityId, EntityState> {
        val eid = EntityId(id)
        return eid to EntityState(
            id = eid,
            friendlyName = id,
            area = null,
            isOn = raw.equals("on", ignoreCase = true),
            percent = null,
            raw = null,
            lastChanged = Instant.EPOCH,
            isAvailable = true,
            rawState = raw,
        )
    }

    private fun states(vararg pairs: Pair<EntityId, EntityState>): EntityStates =
        EntityStates.of(pairs.toMap())

    @Test fun `empty conditions always pass`() {
        assertTrue(evaluateConditions(emptyList(), EntityStates.EMPTY))
    }

    @Test fun `state-equals condition met shows`() {
        val map = states(state("light.kitchen", "on"))
        val conds = listOf(LovelaceCondition.StateEquals("light.kitchen", "on"))
        assertTrue(evaluateConditions(conds, map))
    }

    @Test fun `state-equals condition not met hides`() {
        val map = states(state("light.kitchen", "off"))
        val conds = listOf(LovelaceCondition.StateEquals("light.kitchen", "on"))
        assertFalse(evaluateConditions(conds, map))
    }

    @Test fun `state-equals with missing entity fails closed`() {
        val conds = listOf(LovelaceCondition.StateEquals("light.kitchen", "on"))
        assertFalse(evaluateConditions(conds, EntityStates.EMPTY))
    }

    @Test fun `numeric-state in range shows`() {
        val map = states(state("sensor.temp", "21"))
        val conds = listOf(LovelaceCondition.NumericState("sensor.temp", above = 18.0, below = 25.0))
        assertTrue(evaluateConditions(conds, map))
    }

    @Test fun `numeric-state out of range hides`() {
        val map = states(state("sensor.temp", "30"))
        val conds = listOf(LovelaceCondition.NumericState("sensor.temp", above = 18.0, below = 25.0))
        assertFalse(evaluateConditions(conds, map))
    }

    @Test fun `numeric-state with missing entity fails closed`() {
        val conds = listOf(LovelaceCondition.NumericState("sensor.temp", above = 18.0, below = null))
        assertFalse(evaluateConditions(conds, EntityStates.EMPTY))
    }

    @Test fun `all conditions must pass`() {
        val map = states(state("light.kitchen", "on"), state("sensor.temp", "21"))
        val pass = listOf(
            LovelaceCondition.StateEquals("light.kitchen", "on"),
            LovelaceCondition.NumericState("sensor.temp", above = 18.0, below = 25.0),
        )
        assertTrue(evaluateConditions(pass, map))
        val fail = listOf(
            LovelaceCondition.StateEquals("light.kitchen", "off"),
            LovelaceCondition.NumericState("sensor.temp", above = 18.0, below = 25.0),
        )
        assertFalse(evaluateConditions(fail, map))
    }

    @Test fun `state-not condition passes when state differs`() {
        val map = states(state("light.kitchen", "off"))
        val conds = listOf(LovelaceCondition.StateEquals("light.kitchen", "on", negate = true))
        assertTrue(evaluateConditions(conds, map))
    }

    @Test fun `state-not condition fails when state matches`() {
        val map = states(state("light.kitchen", "on"))
        val conds = listOf(LovelaceCondition.StateEquals("light.kitchen", "on", negate = true))
        assertFalse(evaluateConditions(conds, map))
    }

    @Test fun `state list matches any member`() {
        val map = states(state("alarm_control_panel.home", "armed_away"))
        val conds = listOf(
            LovelaceCondition.StateEquals(
                "alarm_control_panel.home",
                listOf("armed_home", "armed_away", "armed_night"),
            ),
        )
        assertTrue(evaluateConditions(conds, map))
    }

    @Test fun `state list with no member matching hides`() {
        val map = states(state("alarm_control_panel.home", "disarmed"))
        val conds = listOf(
            LovelaceCondition.StateEquals(
                "alarm_control_panel.home",
                listOf("armed_home", "armed_away"),
            ),
        )
        assertFalse(evaluateConditions(conds, map))
    }

    @Test fun `Never condition always hides`() {
        // A condition we couldn't model (screen / user / template) fails closed,
        // so the wrapped card is hidden even when everything else would pass.
        assertFalse(evaluateConditions(listOf(LovelaceCondition.Never), EntityStates.EMPTY))
        val map = states(state("light.kitchen", "on"))
        assertFalse(
            evaluateConditions(
                listOf(
                    LovelaceCondition.StateEquals("light.kitchen", "on"),
                    LovelaceCondition.Never,
                ),
                map,
            ),
        )
    }

    @Test fun `aspect ratio parses common shapes`() {
        assertEquals(16f / 9f, parseAspectRatio("16:9"), 0.001f)
        assertEquals(1.5f, parseAspectRatio("1.5"), 0.001f)
        // HA percentage is height/width; 50% -> 2:1 wide.
        assertEquals(2.0f, parseAspectRatio("50%"), 0.001f)
        // Unparseable falls back to 16:9.
        assertEquals(16f / 9f, parseAspectRatio("garbage"), 0.001f)
        assertEquals(16f / 9f, parseAspectRatio(null), 0.001f)
    }
}
