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

    // --- logical groups (renderer-side, EntityStates-backed) --------------

    @Test fun `and group passes only when every child passes`() {
        val map = states(state("light.k", "on"), state("sensor.t", "21"))
        val and = listOf(
            LovelaceCondition.And(
                listOf(
                    LovelaceCondition.StateEquals("light.k", "on"),
                    LovelaceCondition.NumericState("sensor.t", above = 18.0, below = 25.0),
                ),
            ),
        )
        assertTrue(evaluateConditions(and, map))
        val mapFail = states(state("light.k", "off"), state("sensor.t", "21"))
        assertFalse(evaluateConditions(and, mapFail))
    }

    @Test fun `or group passes when any child passes`() {
        val map = states(state("light.k", "off"), state("light.l", "on"))
        val or = listOf(
            LovelaceCondition.Or(
                listOf(
                    LovelaceCondition.StateEquals("light.k", "on"),
                    LovelaceCondition.StateEquals("light.l", "on"),
                ),
            ),
        )
        assertTrue(evaluateConditions(or, map))
    }

    @Test fun `not group passes when no child passes`() {
        val map = states(state("light.k", "off"))
        val not = listOf(LovelaceCondition.Not(listOf(LovelaceCondition.StateEquals("light.k", "on"))))
        assertTrue(evaluateConditions(not, map))
        val mapOn = states(state("light.k", "on"))
        assertFalse(evaluateConditions(not, mapOn))
    }

    @Test fun `user condition fails open`() {
        assertTrue(evaluateConditions(listOf(LovelaceCondition.User(listOf("uid"))), EntityStates.EMPTY))
    }

    // --- attribute + cross-entity numeric bounds --------------------------

    private fun stateWithAttrs(id: String, raw: String, attrs: Map<String, String>): Pair<EntityId, EntityState> {
        val eid = EntityId(id)
        val json = kotlinx.serialization.json.buildJsonObject {
            attrs.forEach { (k, v) -> put(k, kotlinx.serialization.json.JsonPrimitive(v)) }
        }
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
            attributesJson = json,
        )
    }

    @Test fun `state attribute condition reads the attribute value`() {
        val map = states(stateWithAttrs("climate.x", "heat", mapOf("hvac_action" to "heating")))
        val conds = listOf(
            LovelaceCondition.StateEquals("climate.x", listOf("heating"), negate = false, attribute = "hvac_action"),
        )
        assertTrue(evaluateConditions(conds, map))
        val mapIdle = states(stateWithAttrs("climate.x", "heat", mapOf("hvac_action" to "idle")))
        assertFalse(evaluateConditions(conds, mapIdle))
    }

    @Test fun `numeric attribute condition reads the attribute value`() {
        val map = states(stateWithAttrs("climate.x", "heat", mapOf("temperature" to "22")))
        val conds = listOf(
            LovelaceCondition.NumericState("climate.x", above = 20.0, below = null, attribute = "temperature"),
        )
        assertTrue(evaluateConditions(conds, map))
    }

    @Test fun `numeric bound referencing another entity resolves`() {
        val map = states(state("sensor.in", "21"), state("sensor.out", "20"))
        val conds = listOf(
            LovelaceCondition.NumericState("sensor.in", above = null, below = null, aboveEntity = "sensor.out"),
        )
        assertTrue(evaluateConditions(conds, map))
        val mapEqual = states(state("sensor.in", "20"), state("sensor.out", "20"))
        assertFalse(evaluateConditions(conds, mapEqual))
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
