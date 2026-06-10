package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Pure decision-logic coverage for the entity-filter card: the operator matrix
 * (every operator + numeric coercion + regex + in/not-in lists), the conditions
 * filter, and the per-entity / card-level override precedence. Mirrors HA's
 * `evaluateStateFilter` and `hui-entity-filter-card`'s update().
 */
class EntityFilterLogicTest {

    private fun rule(op: StateFilterOperator, vararg values: String, attribute: String? = null) =
        StateFilterRule(op, values.toList(), attribute)

    // ── Operator matrix ───────────────────────────────────────────────────────

    @Test fun `eq matches identical string`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "on"), "on")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "on"), "off")).isFalse()
    }

    @Test fun `eq is case sensitive`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "HOME"), "home")).isFalse()
    }

    @Test fun `eq coerces both numeric operands`() {
        // HA: when both sides parse as numbers they compare numerically.
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "21"), "21.0")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "21"), "22")).isFalse()
    }

    @Test fun `ne inverts eq with coercion`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.NE, "21"), "21.0")).isFalse()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.NE, "on"), "off")).isTrue()
    }

    @Test fun `ordering operators compare numerically`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.LT, "10"), "5")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.LT, "10"), "10")).isFalse()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.LTE, "10"), "10")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.GT, "10"), "20")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.GT, "10"), "10")).isFalse()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.GTE, "10"), "10")).isTrue()
    }

    @Test fun `ordering operators fail closed on non-numeric operand`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.GT, "10"), "warm")).isFalse()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.LT, "cold"), "5")).isFalse()
    }

    @Test fun `in checks list membership stringified`() {
        val r = rule(StateFilterOperator.IN, "home", "work", "5")
        assertThat(evaluateStateFilterRule(r, "work")).isTrue()
        assertThat(evaluateStateFilterRule(r, "5")).isTrue()
        assertThat(evaluateStateFilterRule(r, "away")).isFalse()
    }

    @Test fun `not in is the complement of in`() {
        val r = rule(StateFilterOperator.NOT_IN, "home", "work")
        assertThat(evaluateStateFilterRule(r, "away")).isTrue()
        assertThat(evaluateStateFilterRule(r, "home")).isFalse()
    }

    @Test fun `regex matches and a bad pattern fails closed`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.REGEX, "^on"), "online")).isTrue()
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.REGEX, "xyz"), "online")).isFalse()
        // An invalid regex must not throw; it fails closed.
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.REGEX, "("), "x")).isFalse()
    }

    @Test fun `null state fails every rule`() {
        assertThat(evaluateStateFilterRule(rule(StateFilterOperator.EQ, "on"), null)).isFalse()
    }

    // ── entityFilterPasses: precedence + attribute ────────────────────────────

    private fun entry(
        id: String,
        stateFilter: List<StateFilterRule> = emptyList(),
        conditions: List<LovelaceCondition> = emptyList(),
    ) = EntityFilterEntry(EntityRow(id, null, null, null), stateFilter, conditions)

    @Test fun `missing entity never passes`() {
        val pass = entityFilterPasses(
            entry("light.gone"), listOf(rule(StateFilterOperator.EQ, "on")), emptyList(),
            emptyMap(), { _, _ -> null },
        )
        assertThat(pass).isFalse()
    }

    @Test fun `no filter passes an existing entity`() {
        val pass = entityFilterPasses(
            entry("light.a"), emptyList(), emptyList(),
            mapOf("light.a" to "off"), { _, _ -> null },
        )
        assertThat(pass).isTrue()
    }

    @Test fun `card-level state_filter applies when entry has none`() {
        val card = listOf(rule(StateFilterOperator.EQ, "on"))
        assertThat(
            entityFilterPasses(entry("light.a"), card, emptyList(), mapOf("light.a" to "on"), { _, _ -> null }),
        ).isTrue()
        assertThat(
            entityFilterPasses(entry("light.a"), card, emptyList(), mapOf("light.a" to "off"), { _, _ -> null }),
        ).isFalse()
    }

    @Test fun `per-entity state_filter overrides card-level`() {
        val card = listOf(rule(StateFilterOperator.EQ, "on"))
        val entryFilter = listOf(rule(StateFilterOperator.EQ, "off"))
        // The entry's own filter wins: it wants "off", not the card's "on".
        assertThat(
            entityFilterPasses(
                entry("light.a", stateFilter = entryFilter), card, emptyList(),
                mapOf("light.a" to "off"), { _, _ -> null },
            ),
        ).isTrue()
    }

    @Test fun `state_filter reads attribute when set`() {
        val card = listOf(rule(StateFilterOperator.GTE, "50", attribute = "battery"))
        val pass = entityFilterPasses(
            entry("sensor.x"), card, emptyList(),
            mapOf("sensor.x" to "on"),
            { id, attr -> if (id == "sensor.x" && attr == "battery") "75" else null },
        )
        assertThat(pass).isTrue()
    }

    @Test fun `card-level conditions filter substitutes the candidate entity`() {
        // A state condition with no entity inherits the candidate (HA's
        // addEntityToCondition). Only the entity whose state is "on" passes.
        val conditions = listOf(LovelaceCondition.StateEquals(entityId = null, state = "on"))
        val states = mapOf("light.a" to "on", "light.b" to "off")
        assertThat(
            entityFilterPasses(entry("light.a"), emptyList(), conditions, states, { _, _ -> null }),
        ).isTrue()
        assertThat(
            entityFilterPasses(entry("light.b"), emptyList(), conditions, states, { _, _ -> null }),
        ).isFalse()
    }

    @Test fun `per-entity conditions override card state_filter`() {
        val cardFilter = listOf(rule(StateFilterOperator.EQ, "on"))
        val entryConditions = listOf(LovelaceCondition.StateEquals(entityId = null, state = "off"))
        // The entry's conditions win: it passes for "off" despite the card filter.
        assertThat(
            entityFilterPasses(
                entry("light.a", conditions = entryConditions), cardFilter, emptyList(),
                mapOf("light.a" to "off"), { _, _ -> null },
            ),
        ).isTrue()
    }
}
