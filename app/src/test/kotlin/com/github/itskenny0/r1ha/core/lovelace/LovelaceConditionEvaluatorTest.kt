package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Locale

class LovelaceConditionEvaluatorTest {

    @BeforeEach
    fun setUp() {
        Locale.setDefault(Locale.US)
    }

    private fun eval(condition: LovelaceCondition, states: Map<String, String>): Boolean =
        evaluateLovelaceConditions(listOf(condition), states)

    // --- aggregation -------------------------------------------------------

    @Test
    fun `empty conditions are vacuously true`() {
        assertThat(evaluateLovelaceConditions(emptyList(), emptyMap())).isTrue()
    }

    @Test
    fun `all conditions must pass (AND)`() {
        val conditions = listOf(
            LovelaceCondition.StateEquals("a", "on"),
            LovelaceCondition.StateEquals("b", "on"),
        )
        assertThat(evaluateLovelaceConditions(conditions, mapOf("a" to "on", "b" to "on"))).isTrue()
        assertThat(evaluateLovelaceConditions(conditions, mapOf("a" to "on", "b" to "off"))).isFalse()
    }

    @Test
    fun `Never fails closed even alongside passing conditions`() {
        val conditions = listOf(
            LovelaceCondition.StateEquals("a", "on"),
            LovelaceCondition.Never,
        )
        assertThat(evaluateLovelaceConditions(conditions, mapOf("a" to "on"))).isFalse()
    }

    @Test
    fun `AlwaysTrue passes`() {
        assertThat(eval(LovelaceCondition.AlwaysTrue, emptyMap())).isTrue()
    }

    // --- state -------------------------------------------------------------

    @Test
    fun `state matches single value`() {
        val c = LovelaceCondition.StateEquals("light.k", "on")
        assertThat(eval(c, mapOf("light.k" to "on"))).isTrue()
        assertThat(eval(c, mapOf("light.k" to "off"))).isFalse()
    }

    @Test
    fun `state matches one of a list`() {
        val c = LovelaceCondition.StateEquals("s.mode", listOf("home", "away"))
        assertThat(eval(c, mapOf("s.mode" to "away"))).isTrue()
        assertThat(eval(c, mapOf("s.mode" to "night"))).isFalse()
    }

    @Test
    fun `state match is case-insensitive`() {
        val c = LovelaceCondition.StateEquals("light.k", "ON")
        assertThat(eval(c, mapOf("light.k" to "on"))).isTrue()
    }

    @Test
    fun `state missing entity fails closed`() {
        val c = LovelaceCondition.StateEquals("light.k", "on")
        assertThat(eval(c, emptyMap())).isFalse()
    }

    @Test
    fun `state unavailable or unknown or none fails closed`() {
        val c = LovelaceCondition.StateEquals("light.k", "on")
        assertThat(eval(c, mapOf("light.k" to "unavailable"))).isFalse()
        assertThat(eval(c, mapOf("light.k" to "unknown"))).isFalse()
        assertThat(eval(c, mapOf("light.k" to "UNKNOWN"))).isFalse()
        assertThat(eval(c, mapOf("light.k" to "none"))).isFalse()
        assertThat(eval(c, mapOf("light.k" to ""))).isFalse()
    }

    @Test
    fun `state_not passes when state differs and fails when it matches`() {
        val c = LovelaceCondition.StateEquals("s.mode", listOf("off"), negate = true)
        assertThat(eval(c, mapOf("s.mode" to "on"))).isTrue()
        assertThat(eval(c, mapOf("s.mode" to "off"))).isFalse()
    }

    @Test
    fun `state_not still fails closed on missing or unusable state`() {
        val c = LovelaceCondition.StateEquals("s.mode", listOf("off"), negate = true)
        assertThat(eval(c, emptyMap())).isFalse()
        assertThat(eval(c, mapOf("s.mode" to "unavailable"))).isFalse()
        assertThat(eval(c, mapOf("s.mode" to "unknown"))).isFalse()
    }

    // --- numeric_state -----------------------------------------------------

    @Test
    fun `numeric above and below are strict bounds`() {
        val c = LovelaceCondition.NumericState("s.t", above = 10.0, below = 20.0)
        assertThat(eval(c, mapOf("s.t" to "15"))).isTrue()
        assertThat(eval(c, mapOf("s.t" to "10"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "20"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "5"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "25"))).isFalse()
    }

    @Test
    fun `numeric only above`() {
        val c = LovelaceCondition.NumericState("s.t", above = 0.0, below = null)
        assertThat(eval(c, mapOf("s.t" to "0.1"))).isTrue()
        assertThat(eval(c, mapOf("s.t" to "0"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "-1"))).isFalse()
    }

    @Test
    fun `numeric only below`() {
        val c = LovelaceCondition.NumericState("s.t", above = null, below = 100.0)
        assertThat(eval(c, mapOf("s.t" to "99.9"))).isTrue()
        assertThat(eval(c, mapOf("s.t" to "100"))).isFalse()
    }

    @Test
    fun `numeric parses decimals under US locale`() {
        val c = LovelaceCondition.NumericState("s.t", above = 1.5, below = null)
        assertThat(eval(c, mapOf("s.t" to "1.75"))).isTrue()
    }

    @Test
    fun `numeric non-numeric state fails closed`() {
        val c = LovelaceCondition.NumericState("s.t", above = 0.0, below = null)
        assertThat(eval(c, mapOf("s.t" to "never"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "warm"))).isFalse()
    }

    @Test
    fun `numeric missing or unusable state fails closed`() {
        val c = LovelaceCondition.NumericState("s.t", above = 0.0, below = null)
        assertThat(eval(c, emptyMap())).isFalse()
        assertThat(eval(c, mapOf("s.t" to "unavailable"))).isFalse()
        assertThat(eval(c, mapOf("s.t" to "unknown"))).isFalse()
    }

    @Test
    fun `numeric bound referencing another entity resolves at eval time`() {
        val c = LovelaceCondition.NumericState("s.in", above = null, below = null, aboveEntity = "s.out")
        // s.in must be strictly greater than the referenced s.out value.
        assertThat(eval(c, mapOf("s.in" to "21", "s.out" to "20"))).isTrue()
        assertThat(eval(c, mapOf("s.in" to "20", "s.out" to "20"))).isFalse()
        assertThat(eval(c, mapOf("s.in" to "19", "s.out" to "20"))).isFalse()
    }

    @Test
    fun `numeric entity-bound fails closed when the referenced entity is missing or non-numeric`() {
        val c = LovelaceCondition.NumericState("s.in", above = null, below = null, aboveEntity = "s.out")
        assertThat(eval(c, mapOf("s.in" to "21"))).isFalse()
        assertThat(eval(c, mapOf("s.in" to "21", "s.out" to "unavailable"))).isFalse()
        assertThat(eval(c, mapOf("s.in" to "21", "s.out" to "warm"))).isFalse()
    }

    @Test
    fun `state attribute condition fails closed in the state-only evaluator`() {
        // The core evaluator has no attribute data, so an attribute gate hides
        // the card; the renderer's EntityStates evaluator resolves it instead.
        val c = LovelaceCondition.StateEquals("climate.x", listOf("heating"), negate = false, attribute = "hvac_action")
        assertThat(eval(c, mapOf("climate.x" to "heat"))).isFalse()
    }

    // --- logical groups ----------------------------------------------------

    @Test
    fun `and passes only when every child passes`() {
        val c = LovelaceCondition.And(
            listOf(
                LovelaceCondition.StateEquals("a", "on"),
                LovelaceCondition.StateEquals("b", "on"),
            ),
        )
        assertThat(eval(c, mapOf("a" to "on", "b" to "on"))).isTrue()
        assertThat(eval(c, mapOf("a" to "on", "b" to "off"))).isFalse()
    }

    @Test
    fun `empty and is vacuously true`() {
        assertThat(eval(LovelaceCondition.And(emptyList()), emptyMap())).isTrue()
    }

    @Test
    fun `or passes when any child passes`() {
        val c = LovelaceCondition.Or(
            listOf(
                LovelaceCondition.StateEquals("a", "on"),
                LovelaceCondition.StateEquals("b", "on"),
            ),
        )
        assertThat(eval(c, mapOf("a" to "off", "b" to "on"))).isTrue()
        assertThat(eval(c, mapOf("a" to "off", "b" to "off"))).isFalse()
    }

    @Test
    fun `empty or is vacuously true`() {
        assertThat(eval(LovelaceCondition.Or(emptyList()), emptyMap())).isTrue()
    }

    @Test
    fun `not passes when none of its children pass`() {
        val c = LovelaceCondition.Not(
            listOf(LovelaceCondition.StateEquals("a", "on")),
        )
        assertThat(eval(c, mapOf("a" to "off"))).isTrue()
        assertThat(eval(c, mapOf("a" to "on"))).isFalse()
        // not over a missing entity: the inner state fails closed, so none-pass
        // is satisfied and the not is true (matches HA's negation of an AND).
        assertThat(eval(c, emptyMap())).isTrue()
    }

    @Test
    fun `not over multiple children passes when at least one child fails (HA negation-of-AND)`() {
        // HA's `not` is !(every child passes), so it shows when ANY child fails.
        val c = LovelaceCondition.Not(
            listOf(
                LovelaceCondition.StateEquals("a", "on"),
                LovelaceCondition.StateEquals("b", "on"),
            ),
        )
        // both pass -> the inner AND is true -> not is false (hidden)
        assertThat(eval(c, mapOf("a" to "on", "b" to "on"))).isFalse()
        // one fails -> inner AND false -> not is true (shown)
        assertThat(eval(c, mapOf("a" to "on", "b" to "off"))).isTrue()
        assertThat(eval(c, mapOf("a" to "off", "b" to "off"))).isTrue()
    }

    @Test
    fun `nested and-or-not composes`() {
        // (a == on) AND ( (b == on) OR NOT(c == on) )
        val c = LovelaceCondition.And(
            listOf(
                LovelaceCondition.StateEquals("a", "on"),
                LovelaceCondition.Or(
                    listOf(
                        LovelaceCondition.StateEquals("b", "on"),
                        LovelaceCondition.Not(listOf(LovelaceCondition.StateEquals("c", "on"))),
                    ),
                ),
            ),
        )
        assertThat(eval(c, mapOf("a" to "on", "b" to "off", "c" to "off"))).isTrue()
        assertThat(eval(c, mapOf("a" to "on", "b" to "on", "c" to "on"))).isTrue()
        assertThat(eval(c, mapOf("a" to "on", "b" to "off", "c" to "on"))).isFalse()
        assertThat(eval(c, mapOf("a" to "off", "b" to "on", "c" to "off"))).isFalse()
    }

    @Test
    fun `user condition fails open (current user id unavailable)`() {
        assertThat(eval(LovelaceCondition.User(listOf("some-uuid")), emptyMap())).isTrue()
    }
}
