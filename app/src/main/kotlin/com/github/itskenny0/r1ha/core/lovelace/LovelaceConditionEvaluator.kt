package com.github.itskenny0.r1ha.core.lovelace

/**
 * Pure, dependency-free evaluation of parsed [LovelaceCondition]s against a plain
 * snapshot of entity states (entity id to raw state string).
 *
 * This is the canonical semantics for Lovelace conditional cards and conditional
 * entity rows. It lives in `core/lovelace` so it can be exhaustively unit-tested in
 * isolation, with no Compose, no ViewModel, and no feature-layer state model.
 *
 * Fail-closed is the governing rule. A condition exists to gate visibility, so when
 * the data needed to prove it is missing, unknown, unavailable, or unparseable the
 * condition evaluates to false and the wrapped card or row is hidden. The only
 * deliberate exception is [LovelaceCondition.AlwaysTrue] (e.g. a `screen` breakpoint
 * hint the app cannot evaluate locally), which the parser reserves for shapes that
 * should fail open.
 */

/** State strings Home Assistant uses to mean "no usable value". */
private val UNUSABLE_STATES = setOf("unavailable", "unknown", "none", "")

/**
 * Evaluates [conditions] against [states]; returns true only when EVERY condition
 * passes (logical AND), matching Home Assistant conditional-card semantics. An empty
 * list is vacuously true.
 *
 * @param states entity id to its raw state string. A missing key is treated as an
 *   absent entity and fails closed for any condition that needs it.
 */
fun evaluateLovelaceConditions(
    conditions: List<LovelaceCondition>,
    states: Map<String, String>,
): Boolean = conditions.all { evaluateLovelaceCondition(it, states) }

/** Evaluates a single [condition]. See [evaluateLovelaceConditions]. */
fun evaluateLovelaceCondition(
    condition: LovelaceCondition,
    states: Map<String, String>,
): Boolean = when (condition) {
    is LovelaceCondition.StateEquals -> evaluateStateEquals(condition, states)
    is LovelaceCondition.NumericState -> evaluateNumericState(condition, states)
    // Logical groups. `and` requires every child; `or` any child; `not` is the
    // negation of an AND over the group (passes when NOT every child passes, i.e.
    // at least one fails). An empty group is vacuously true for all three,
    // matching HA's checkAnd/Or/NotCondition (which return true when `conditions`
    // is absent).
    is LovelaceCondition.And -> condition.conditions.all { evaluateLovelaceCondition(it, states) }
    is LovelaceCondition.Or ->
        condition.conditions.isEmpty() || condition.conditions.any { evaluateLovelaceCondition(it, states) }
    is LovelaceCondition.Not ->
        condition.conditions.isEmpty() || !condition.conditions.all { evaluateLovelaceCondition(it, states) }
    // `user` can't be evaluated without the logged-in user id (not reachable in
    // this layer), so fail OPEN rather than hide a card the user likely should
    // see. See LovelaceCondition.User.
    is LovelaceCondition.User -> true
    LovelaceCondition.AlwaysTrue -> true
    LovelaceCondition.Never -> false
}

private fun evaluateStateEquals(
    condition: LovelaceCondition.StateEquals,
    states: Map<String, String>,
): Boolean {
    // This evaluator works on a state-only snapshot, so an `attribute:`
    // comparison has no data to read and fails closed (the EntityStates-backed
    // renderer evaluator resolves attributes from the live attributesJson).
    if (condition.attribute != null) return false
    val state = states[condition.entityId] ?: return false
    if (isUnusable(state)) return false
    val matches = condition.states.any { state.equals(it, ignoreCase = true) }
    return if (condition.negate) !matches else matches
}

private fun evaluateNumericState(
    condition: LovelaceCondition.NumericState,
    states: Map<String, String>,
): Boolean {
    if (condition.attribute != null) return false
    val raw = states[condition.entityId] ?: return false
    if (isUnusable(raw)) return false
    val value = raw.trim().toDoubleOrNull() ?: return false
    // A bound is either a literal number or a reference to another entity's
    // numeric state; the parser guarantees at least one usable bound exists. An
    // entity-referenced bound that can't be resolved to a number fails closed
    // (HA treats a NaN reference bound as "no constraint", but we can't tell a
    // genuinely-absent reference from a non-numeric one here, so the safe gate
    // is to hide). The literal bound takes precedence when both are present.
    val above = condition.above ?: condition.aboveEntity?.let { resolveNumeric(it, states) ?: return false }
    val below = condition.below ?: condition.belowEntity?.let { resolveNumeric(it, states) ?: return false }
    above?.let { if (value <= it) return false }
    below?.let { if (value >= it) return false }
    return true
}

/** Resolve a referenced entity's state to a number, or null when missing,
 *  unusable, or non-numeric. */
private fun resolveNumeric(entityId: String, states: Map<String, String>): Double? {
    val raw = states[entityId] ?: return null
    if (isUnusable(raw)) return null
    return raw.trim().toDoubleOrNull()
}

private fun isUnusable(state: String): Boolean = state.lowercase() in UNUSABLE_STATES
