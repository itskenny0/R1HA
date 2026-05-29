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
}
