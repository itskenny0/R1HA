package com.github.itskenny0.r1ha.core.lovelace

/**
 * Pure, dependency-free evaluation of parsed [LovelaceCondition]s against a plain
 * snapshot of entity states (entity id to raw state string) and a
 * [LovelaceConditionContext] (current user, window size, local time, view column
 * count, host-card entity fallback).
 *
 * This is the canonical semantics for Lovelace conditional cards and conditional
 * entity rows. It lives in `core/lovelace` so it can be exhaustively unit-tested
 * in isolation, with no Compose, no ViewModel, and no feature-layer state model.
 * The renderer carries an EntityState-backed twin (LovelaceCardRenderer's
 * evaluateConditions) that reads attributes from the live attributes JSON; the
 * two share the same condition model and the same pure helpers in
 * [LovelaceConditionContext] (media-query, time-window, deref).
 *
 * Fail-closed is the governing rule for state-backed gates. A condition exists to
 * gate visibility, so when the data needed to prove it is missing, unknown,
 * unavailable, or unparseable the condition evaluates to false and the wrapped
 * card or row is hidden. The runtime conditions carry HA's own fail-open /
 * fail-closed rules: a `screen` query we cannot evaluate fails OPEN (the card
 * shows), a `user` / `location` with an unknown current user fails CLOSED, and a
 * `view_columns` with no known column count PASSES.
 */

/** State strings Home Assistant uses to mean "no usable value". */
private val UNUSABLE_STATES = setOf("unavailable", "unknown", "none", "")

/**
 * Evaluates [conditions] against [states]; returns true only when EVERY condition
 * passes (logical AND), matching Home Assistant conditional-card semantics. An
 * empty list is vacuously true. Uses [LovelaceConditionContext.EMPTY] (no runtime
 * data), so this state-only overload is for call sites that gate on state /
 * numeric / logical conditions alone.
 *
 * @param states entity id to its raw state string. A missing key is treated as an
 *   absent entity and fails closed for any condition that needs it.
 */
fun evaluateLovelaceConditions(
    conditions: List<LovelaceCondition>,
    states: Map<String, String>,
): Boolean = evaluateLovelaceConditions(conditions, states, LovelaceConditionContext.EMPTY)

/** Evaluates [conditions] against [states] and a runtime [context]. */
fun evaluateLovelaceConditions(
    conditions: List<LovelaceCondition>,
    states: Map<String, String>,
    context: LovelaceConditionContext,
): Boolean = conditions.all { evaluateLovelaceCondition(it, states, context) }

/** Evaluates a single [condition] with no runtime context (state-only). */
fun evaluateLovelaceCondition(
    condition: LovelaceCondition,
    states: Map<String, String>,
): Boolean = evaluateLovelaceCondition(condition, states, LovelaceConditionContext.EMPTY)

/** Evaluates a single [condition] against [states] and a runtime [context]. */
fun evaluateLovelaceCondition(
    condition: LovelaceCondition,
    states: Map<String, String>,
    context: LovelaceConditionContext,
): Boolean = when (condition) {
    is LovelaceCondition.StateEquals -> evaluateStateEquals(condition, states, context)
    is LovelaceCondition.NumericState -> evaluateNumericState(condition, states, context)
    // Logical groups. `and` requires every child; `or` any child; `not` is the
    // negation of an AND over the group (passes when NOT every child passes, i.e.
    // at least one fails). An empty group is vacuously true for all three,
    // matching HA's checkAnd/Or/NotCondition (which return true when `conditions`
    // is absent).
    is LovelaceCondition.And -> condition.conditions.all { evaluateLovelaceCondition(it, states, context) }
    is LovelaceCondition.Or ->
        condition.conditions.isEmpty() || condition.conditions.any { evaluateLovelaceCondition(it, states, context) }
    is LovelaceCondition.Not ->
        condition.conditions.isEmpty() || !condition.conditions.all { evaluateLovelaceCondition(it, states, context) }
    // `user`: membership of the current user in the listed ids. Unknown user
    // fails closed (HA returns false when `hass.user?.id` is undefined).
    is LovelaceCondition.User ->
        context.currentUserId != null && condition.userIds.contains(context.currentUserId)
    is LovelaceCondition.Screen -> evaluateScreenCondition(condition, context)
    is LovelaceCondition.Time ->
        evaluateTimeWindow(condition, context.nowSecondsOfDay, context.weekday)
    is LovelaceCondition.Location -> evaluateLocationCondition(condition, context)
    is LovelaceCondition.ViewColumns -> evaluateViewColumns(condition, context)
    LovelaceCondition.AlwaysTrue -> true
    LovelaceCondition.Never -> false
}

private fun evaluateStateEquals(
    condition: LovelaceCondition.StateEquals,
    states: Map<String, String>,
    context: LovelaceConditionContext,
): Boolean {
    // This evaluator works on a state-only snapshot, so an `attribute:`
    // comparison has no data to read and fails closed (the EntityStates-backed
    // renderer evaluator resolves attributes from the live attributesJson).
    if (condition.attribute != null) return false
    // A condition without `entity:` falls back to the host card's own entity
    // (HA's context.entity_id); with no context entity there is nothing to
    // compare, so fail closed.
    val entityId = condition.entityId ?: context.contextEntityId ?: return false
    val state = states[entityId] ?: return false
    if (isUnusable(state)) return false
    // A listed value that is itself an entity id is ALSO accepted as that
    // entity's current state (HA's getValueFromEntityId: both the literal token
    // and the dereferenced state match). Build the accepted set accordingly.
    val accepted = condition.states.flatMap { value ->
        val deref = states[value]?.takeUnless { isUnusable(it) }
        if (deref != null) listOf(value, deref) else listOf(value)
    }
    val matches = accepted.any { state.equals(it, ignoreCase = true) }
    return if (condition.negate) !matches else matches
}

private fun evaluateNumericState(
    condition: LovelaceCondition.NumericState,
    states: Map<String, String>,
    context: LovelaceConditionContext,
): Boolean {
    if (condition.attribute != null) return false
    val entityId = condition.entityId ?: context.contextEntityId ?: return false
    val raw = states[entityId] ?: return false
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

private fun evaluateScreenCondition(
    condition: LovelaceCondition.Screen,
    context: LovelaceConditionContext,
): Boolean {
    val outcome = evaluateMediaQuery(condition.mediaQuery, context.windowWidthPx, context.windowHeightPx)
    // A query we couldn't parse or measure fails OPEN: the app's single window is
    // the only screen a dashboard ever shows on, so an undecidable breakpoint
    // should not hide content the user expects to see.
    return if (!outcome.evaluable) true else outcome.matched
}

private fun evaluateLocationCondition(
    condition: LovelaceCondition.Location,
    context: LovelaceConditionContext,
): Boolean {
    if (context.currentUserId == null || condition.locations.isEmpty()) return false
    val personState = context.personStateForUser() ?: return false
    return condition.locations.contains(personState)
}

private fun evaluateViewColumns(
    condition: LovelaceCondition.ViewColumns,
    context: LovelaceConditionContext,
): Boolean {
    // HA: a missing / zero column count passes the condition unconditionally.
    val columns = context.maxColumns ?: return true
    if (columns == 0) return true
    val minOk = condition.min?.let { columns >= it } ?: true
    val maxOk = condition.max?.let { columns <= it } ?: true
    return minOk && maxOk
}

/** Resolve a referenced entity's state to a number, or null when missing,
 *  unusable, or non-numeric. */
private fun resolveNumeric(entityId: String, states: Map<String, String>): Double? {
    val raw = states[entityId] ?: return null
    if (isUnusable(raw)) return null
    return raw.trim().toDoubleOrNull()
}

private fun isUnusable(state: String): Boolean = state.lowercase() in UNUSABLE_STATES
