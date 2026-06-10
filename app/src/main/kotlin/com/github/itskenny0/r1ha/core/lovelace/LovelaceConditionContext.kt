package com.github.itskenny0.r1ha.core.lovelace

/**
 * Runtime inputs the Lovelace condition engine needs beyond entity states:
 * the current user, the window size, the local clock, the hosting view's column
 * count, and a host-card entity fallback. Pure data + lookups, no Compose / no
 * Android, so every condition decision stays unit-testable.
 *
 * Mirrors HA's `ConditionContext` plus the `hass` fields the runtime conditions
 * read (`hass.user`, `matchMedia`, the local time, `getUserPerson`).
 *
 * @property currentUserId the logged-in user's id, or null when unknown (the
 *   server predates `auth/current_user` or the fetch failed). A `user` /
 *   `location` condition fails closed for a null user, matching HA.
 * @property windowWidthPx the app window width in dp-as-css-px. Null when not
 *   yet measured; a `screen` width query then can't be matched (fails open at
 *   the evaluator, see [evaluateScreenCondition]).
 * @property windowHeightPx the app window height in dp-as-css-px.
 * @property nowSecondsOfDay seconds since local midnight (0..86399) for `time`
 *   window checks.
 * @property weekday the current local weekday as HA's lowercase token
 *   (sun..sat), for `time` weekday checks.
 * @property maxColumns the hosting view's column count, or null when unknown.
 *   R1HA is single-column, so this defaults to 1; a `view_columns` condition
 *   passes when it is null (HA's behaviour).
 * @property contextEntityId the host card/badge's own entity id, substituted for
 *   a `state` / `numeric_state` condition that omits `entity:`.
 * @property personStateForUser resolves the current user's person-entity state
 *   string (the `person.*` entity whose `user_id` attribute matches
 *   [currentUserId]), or null when there is no such entity. Mirrors HA's
 *   `getUserPerson(hass).state`.
 */
data class LovelaceConditionContext(
    val currentUserId: String? = null,
    val windowWidthPx: Int? = null,
    val windowHeightPx: Int? = null,
    val nowSecondsOfDay: Int = 0,
    val weekday: String = "",
    val maxColumns: Int? = 1,
    val contextEntityId: String? = null,
    val personStateForUser: () -> String? = { null },
) {
    companion object {
        /** A context with no runtime data. `user` / `location` fail closed,
         *  `screen` width/height queries fail open, `time` evaluates at midnight
         *  Sunday, `view_columns` passes (single column). Used by the state-only
         *  core evaluator and simple call sites that gate only state conditions. */
        val EMPTY = LovelaceConditionContext()
    }
}

/**
 * Result of evaluating a `screen` media query against a window size. [matched]
 * is the boolean outcome; [evaluable] is false when the query couldn't be
 * parsed or the needed window dimension was unavailable, in which case the
 * evaluator fails the `screen` condition OPEN (the card shows). Keeping the two
 * apart lets the caller distinguish "query says hide" from "we don't know".
 */
data class MediaQueryOutcome(val matched: Boolean, val evaluable: Boolean)

/**
 * Pure CSS-media-query evaluation for the `screen` condition. Handles the forms
 * real HA dashboards use:
 *  - `(min-width: 600px)` / `(max-width: 600px)`
 *  - `(min-height: 400px)` / `(max-height: 400px)`
 *  - `(orientation: portrait)` / `(orientation: landscape)`
 *  - any of the above joined with ` and ` (every clause must match)
 *
 * Widths/heights compare against [widthPx]/[heightPx] in dp-as-css-px terms (the
 * app passes its window size in dp, which equals CSS px). A clause whose
 * dimension is unavailable, or a query with a clause we can't parse, makes the
 * whole outcome non-[MediaQueryOutcome.evaluable] so the caller can fail open.
 */
fun evaluateMediaQuery(
    query: String,
    widthPx: Int?,
    heightPx: Int?,
): MediaQueryOutcome {
    // Strip a leading media-type ("screen", "all") before the first `and`;
    // dashboards write either `(min-width: ...)` or `screen and (min-width: ...)`.
    val normalized = query.trim().let { q ->
        val lower = q.lowercase()
        when {
            lower.startsWith("screen and ") -> q.substring("screen and ".length)
            lower.startsWith("all and ") -> q.substring("all and ".length)
            else -> q
        }
    }
    val clauses = normalized
        .split(Regex("\\band\\b", RegexOption.IGNORE_CASE))
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    if (clauses.isEmpty()) return MediaQueryOutcome(matched = false, evaluable = false)

    for (clause in clauses) {
        val outcome = evaluateMediaClause(clause, widthPx, heightPx)
        if (!outcome.evaluable) return MediaQueryOutcome(matched = false, evaluable = false)
        if (!outcome.matched) return MediaQueryOutcome(matched = false, evaluable = true)
    }
    return MediaQueryOutcome(matched = true, evaluable = true)
}

private val FEATURE_REGEX =
    Regex("""\(\s*(min|max)-(width|height)\s*:\s*(\d+(?:\.\d+)?)\s*px\s*\)""", RegexOption.IGNORE_CASE)
private val ORIENTATION_REGEX =
    Regex("""\(\s*orientation\s*:\s*(portrait|landscape)\s*\)""", RegexOption.IGNORE_CASE)

private fun evaluateMediaClause(clause: String, widthPx: Int?, heightPx: Int?): MediaQueryOutcome {
    FEATURE_REGEX.matchEntire(clause)?.let { m ->
        val bound = m.groupValues[1].lowercase()
        val axis = m.groupValues[2].lowercase()
        val px = m.groupValues[3].toDoubleOrNull()
            ?: return MediaQueryOutcome(matched = false, evaluable = false)
        val actual = (if (axis == "width") widthPx else heightPx)
            ?: return MediaQueryOutcome(matched = false, evaluable = false)
        // CSS: min-* matches when actual >= bound; max-* when actual <= bound.
        val matched = if (bound == "min") actual >= px else actual <= px
        return MediaQueryOutcome(matched = matched, evaluable = true)
    }
    ORIENTATION_REGEX.matchEntire(clause)?.let { m ->
        val want = m.groupValues[1].lowercase()
        if (widthPx == null || heightPx == null) {
            return MediaQueryOutcome(matched = false, evaluable = false)
        }
        // CSS orientation: portrait when height >= width, landscape otherwise.
        val actual = if (heightPx >= widthPx) "portrait" else "landscape"
        return MediaQueryOutcome(matched = actual == want, evaluable = true)
    }
    return MediaQueryOutcome(matched = false, evaluable = false)
}

/**
 * Pure `time`-window membership for the current local time, mirroring HA's
 * `checkTimeInRange`: weekday allow-list first, then the after/before window.
 *
 * Boundaries follow HA exactly: an [after] bound passes when now is not before
 * it (>=); a [before] bound passes when now is not after it (<=). When both are
 * present and [before] is earlier than [after] the window wraps across midnight,
 * matching at-or-after [after] OR at-or-before [before]. An empty condition (no
 * bounds, no weekdays) is true.
 */
fun evaluateTimeWindow(
    condition: LovelaceCondition.Time,
    nowSecondsOfDay: Int,
    weekday: String,
): Boolean {
    if (condition.weekdays.isNotEmpty() && weekday !in condition.weekdays) return false

    val after = condition.after?.secondsOfDay
    val before = condition.before?.secondsOfDay
    if (after == null && before == null) return true

    if (after != null && before != null) {
        return if (before < after) {
            // Wraps midnight (e.g. 22:00..06:00): in-range at-or-after `after`
            // OR at-or-before `before`.
            nowSecondsOfDay >= after || nowSecondsOfDay <= before
        } else {
            nowSecondsOfDay in after..before
        }
    }
    if (after != null) return nowSecondsOfDay >= after
    // before only
    return nowSecondsOfDay <= before!!
}

/** The +1-minute buffer HA adds past a time boundary so the clock has fully
 *  crossed it before re-evaluating (matches calculateNextTimeUpdate). */
private const val TIME_BOUNDARY_BUFFER_MS = 60_000L

/** setTimeout's safe ceiling (2^31 - 1 ms, ~24.8 days). Longer delays overflow
 *  to immediate fire; HA caps to this and reschedules. */
const val MAX_TIME_DELAY_MS = 2_147_483_647L

private const val MS_PER_DAY = 24L * 60 * 60 * 1000

/**
 * Milliseconds until the next moment a [condition]'s visibility could flip,
 * mirroring HA's `calculateNextTimeUpdate`: the soonest of the next `after`
 * crossing, the next `before` crossing, and (when a partial weekday list is
 * set) the next local midnight, plus a 1-minute buffer. Returns null when the
 * condition has no time/weekday boundary to schedule against.
 *
 * @param nowMillisOfDay milliseconds since local midnight (0..86_399_999).
 */
fun nextTimeBoundaryMillis(
    condition: LovelaceCondition.Time,
    nowMillisOfDay: Long,
): Long? {
    val candidates = ArrayList<Long>(3)

    condition.after?.let { candidates += nextCrossingMillis(it.secondsOfDay * 1000L, nowMillisOfDay) }
    condition.before?.let { candidates += nextCrossingMillis(it.secondsOfDay * 1000L, nowMillisOfDay) }
    // A partial weekday list flips at midnight; a full 7-day list never does.
    if (condition.weekdays.size in 1..6) {
        candidates += MS_PER_DAY - nowMillisOfDay
    }
    if (candidates.isEmpty()) return null

    val soonest = candidates.min()
    return soonest + TIME_BOUNDARY_BUFFER_MS
}

/** Delay until the next time the wall clock reads [targetMillisOfDay], i.e.
 *  later today, or tomorrow when that time has already passed. HA schedules for
 *  tomorrow when `<= now`, so an exact-now boundary lands a full day out. */
private fun nextCrossingMillis(targetMillisOfDay: Long, nowMillisOfDay: Long): Long =
    if (targetMillisOfDay > nowMillisOfDay) {
        targetMillisOfDay - nowMillisOfDay
    } else {
        MS_PER_DAY - nowMillisOfDay + targetMillisOfDay
    }
