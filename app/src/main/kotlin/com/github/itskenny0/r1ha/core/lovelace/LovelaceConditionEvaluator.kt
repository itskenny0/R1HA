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
    LovelaceCondition.AlwaysTrue -> true
    LovelaceCondition.Never -> false
}

private fun evaluateStateEquals(
    condition: LovelaceCondition.StateEquals,
    states: Map<String, String>,
): Boolean {
    val state = states[condition.entityId] ?: return false
    if (isUnusable(state)) return false
    val matches = condition.states.any { state.equals(it, ignoreCase = true) }
    return if (condition.negate) !matches else matches
}

private fun evaluateNumericState(
    condition: LovelaceCondition.NumericState,
    states: Map<String, String>,
): Boolean {
    val raw = states[condition.entityId] ?: return false
    if (isUnusable(raw)) return false
    val value = raw.trim().toDoubleOrNull() ?: return false
    // A null bound means "no constraint on that side". The parser guarantees a
    // numeric_state has at least one real bound (an unparseable or missing bound
    // becomes Never), so a NumericState here can never be fully unbounded.
    condition.above?.let { if (value <= it) return false }
    condition.below?.let { if (value >= it) return false }
    return true
}

private fun isUnusable(state: String): Boolean = state.lowercase() in UNUSABLE_STATES
