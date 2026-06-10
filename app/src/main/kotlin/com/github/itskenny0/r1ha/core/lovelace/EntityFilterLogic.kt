package com.github.itskenny0.r1ha.core.lovelace

/**
 * Pure decision logic for the entity-filter card, isolated from Compose and the
 * live state model so the operator matrix and per-entity filter resolution can be
 * exhaustively unit-tested.
 *
 * Mirrors HA's `evaluateStateFilter` (src/panels/lovelace/common/evaluate-filter.ts)
 * for the operator-form `state_filter:` and the per-entity / card-level filter
 * precedence of `hui-entity-filter-card.ts`'s `update()`:
 *  - a per-entity filter (state_filter OR conditions) overrides the card-level one;
 *  - `conditions:` runs the Batch B evaluator with the candidate substituted as the
 *    condition entity (HA's `addEntityToCondition`);
 *  - `state_filter:` runs the operator rules, ANY-match passing the entity;
 *  - with neither filter the entity passes (HA returns true).
 *
 * The functions take a `stateOf` lookup (entity id to raw state) and an
 * `attributeOf` lookup (entity id + attribute to raw value) so they stay free of
 * the EntityState model and run on a plain map in tests.
 */

/**
 * Evaluate one operator rule against an entity, mirroring HA's
 * `evaluateStateFilter`. Reads the entity's state (or the rule's [attribute]) and
 * applies the operator. Numeric coercion happens only for == / != when BOTH sides
 * look numeric (HA's rule); the ordering operators always coerce both sides to
 * numbers and fail closed when either side isn't numeric (a string `<=` string is
 * a JS lexicographic compare HA leans on, but dashboards use these only on numeric
 * states, so a non-numeric operand failing closed is the safe parity choice).
 *
 * @return true when the entity passes the rule.
 */
fun evaluateStateFilterRule(
    rule: StateFilterRule,
    state: String?,
): Boolean {
    val actual = state ?: return false
    return when (rule.operator) {
        StateFilterOperator.EQ -> compareEquality(actual, rule.value, equal = true)
        StateFilterOperator.NE -> compareEquality(actual, rule.value, equal = false)
        StateFilterOperator.LT -> compareNumeric(actual, rule.value) { a, b -> a < b }
        StateFilterOperator.LTE -> compareNumeric(actual, rule.value) { a, b -> a <= b }
        StateFilterOperator.GT -> compareNumeric(actual, rule.value) { a, b -> a > b }
        StateFilterOperator.GTE -> compareNumeric(actual, rule.value) { a, b -> a >= b }
        // HA stringifies list members and checks membership against the state.
        StateFilterOperator.IN -> rule.values.any { it == actual }
        StateFilterOperator.NOT_IN -> rule.values.none { it == actual }
        StateFilterOperator.REGEX -> runCatching { Regex(rule.value).containsMatchIn(actual) }.getOrDefault(false)
    }
}

/** == / != with HA's both-numeric coercion: when both operands parse as numbers
 *  they compare numerically (so "21.0" == "21"), else as strings. */
private fun compareEquality(state: String, value: String, equal: Boolean): Boolean {
    val s = state.trim().toDoubleOrNull()
    val v = value.trim().toDoubleOrNull()
    val match = if (s != null && v != null) s == v else state == value
    return if (equal) match else !match
}

/** Ordering compare: both sides must be numeric, else fail closed. */
private inline fun compareNumeric(state: String, value: String, cmp: (Double, Double) -> Boolean): Boolean {
    val s = state.trim().toDoubleOrNull() ?: return false
    val v = value.trim().toDoubleOrNull() ?: return false
    return cmp(s, v)
}

/**
 * Whether one entity passes the entity-filter card, applying HA's precedence:
 * per-entity filter overrides card-level; `conditions:` beats `state_filter:` on
 * the same level; with no filter at all the entity passes.
 *
 * Conditions are evaluated by [evaluateLovelaceConditions] with the candidate
 * substituted as each condition's entity (via the context's [contextEntityId],
 * which the core evaluator uses for a condition that omits `entity:`). To match
 * HA's `addEntityToCondition`, a condition that already names an entity keeps it;
 * only entity-less conditions inherit the candidate.
 *
 * @param states entity id to raw state, for conditions + state-filter lookups.
 * @param attributeOf entity id + attribute to its raw value (null when absent).
 */
fun entityFilterPasses(
    entry: EntityFilterEntry,
    cardStateFilter: List<StateFilterRule>,
    cardConditions: List<LovelaceCondition>,
    states: Map<String, String>,
    attributeOf: (entityId: String, attribute: String) -> String?,
    context: LovelaceConditionContext = LovelaceConditionContext.EMPTY,
): Boolean {
    val entityId = entry.row.entityId
    // The entity must exist for any filter form (HA returns false when the
    // candidate has no state object).
    if (states[entityId] == null) return false

    // Per-entity conditions override everything (HA: conditions checked first when
    // present and no per-entity state_filter).
    val conditions = entry.conditions.ifEmpty { if (entry.stateFilter.isEmpty()) cardConditions else emptyList() }
    if (conditions.isNotEmpty()) {
        return evaluateLovelaceConditions(
            conditions,
            states,
            context.copy(contextEntityId = entityId),
        )
    }

    val filter = entry.stateFilter.ifEmpty { cardStateFilter }
    if (filter.isNotEmpty()) {
        return filter.any { rule ->
            val value = rule.attribute?.let { attributeOf(entityId, it) } ?: states[entityId]
            evaluateStateFilterRule(rule, value)
        }
    }
    // No filter of any kind: the entity passes (it exists).
    return true
}
