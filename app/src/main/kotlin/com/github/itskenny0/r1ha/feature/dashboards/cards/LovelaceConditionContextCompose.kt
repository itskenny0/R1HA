package com.github.itskenny0.r1ha.feature.dashboards.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCondition
import com.github.itskenny0.r1ha.core.lovelace.LovelaceConditionContext
import com.github.itskenny0.r1ha.core.lovelace.MAX_TIME_DELAY_MS
import com.github.itskenny0.r1ha.core.lovelace.nextTimeBoundaryMillis
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoField

/**
 * The logged-in user's id, supplied by the dashboards screen from the cached
 * `auth/current_user` result. Null when unknown (server predates the command or
 * the call failed); `user` / `location` conditions then fail closed, matching HA.
 */
val LocalLovelaceCurrentUserId = staticCompositionLocalOf<String?> { null }

/**
 * Resolves the current user's person-entity state (the `person.*` entity whose
 * `user_id` attribute matches the current user), for the `location` condition.
 * The default returns null (no person resolvable), which makes `location` fail
 * closed. The screen overrides it with a live lookup against the state stream.
 */
val LocalLovelacePersonStateForUser = staticCompositionLocalOf<() -> String?> { { null } }

/**
 * The hosting view's column count for the `view_columns` condition. R1HA renders
 * single-column, so the default is 1; the condition passes when the count is
 * unavailable (HA behaviour).
 */
val LocalLovelaceMaxColumns = staticCompositionLocalOf<Int?> { 1 }

/**
 * Build the live [LovelaceConditionContext] for a card's [conditions], reading
 * the current user, the window size, the column count, and a self-advancing
 * local clock from the composition.
 *
 * Re-evaluation triggers, mirroring HA's conditional-listener-mixin adapted to
 * Compose:
 *  - entity-state changes: the caller already keys its `evaluateConditions` on
 *    the sliced state map, so a gating entity change recomposes the card.
 *  - window-size changes: [LocalConfiguration] is a composition input, so a
 *    configuration/size change recomposes and feeds the new metrics in.
 *  - time-condition boundaries: when [conditions] contain a `time` rule, a
 *    coroutine sleeps until the next after/before/midnight boundary (plus HA's
 *    1-minute buffer, capped to the safe setTimeout ceiling) and then bumps the
 *    clock state, recomposing the card so the gate flips live. Without a time
 *    rule the clock is read once and never ticks (no wasted timer).
 *
 * The window width/height are taken from the configuration's screen dp, which
 * equals CSS px for media-query purposes on the device.
 */
@Composable
fun rememberLovelaceConditionContext(
    conditions: List<LovelaceCondition>,
): LovelaceConditionContext {
    val config = LocalConfiguration.current
    val currentUserId = LocalLovelaceCurrentUserId.current
    val personStateForUser = LocalLovelacePersonStateForUser.current
    val maxColumns = LocalLovelaceMaxColumns.current

    val hasTimeCondition = remember(conditions) { conditionsContainTime(conditions) }
    val clock by rememberConditionClock(conditions, hasTimeCondition)

    return LovelaceConditionContext(
        currentUserId = currentUserId,
        windowWidthPx = config.screenWidthDp.takeIf { it > 0 },
        windowHeightPx = config.screenHeightDp.takeIf { it > 0 },
        nowSecondsOfDay = clock.secondsOfDay,
        weekday = clock.weekday,
        maxColumns = maxColumns,
        contextEntityId = null,
        personStateForUser = personStateForUser,
    )
}

/**
 * Build a condition context covering the visibility conditions of a list of
 * [cards] (e.g. a stack's children), so a layout-level `cardWillRender` gate
 * sees the same runtime inputs (and the same self-advancing clock) the per-card
 * render uses. The context's clock ticks at the soonest boundary across every
 * child's `time` condition.
 */
@Composable
fun rememberLovelaceConditionContextForCards(
    cards: List<LovelaceCard>,
): LovelaceConditionContext {
    val conditions = remember(cards) { cards.flatMap(::topLevelConditions) }
    return rememberLovelaceConditionContext(conditions)
}

/** The visibility conditions a card gates on at its top level (a
 *  [LovelaceCard.Conditional] wrapper); other cards contribute none. */
private fun topLevelConditions(card: LovelaceCard): List<LovelaceCondition> =
    if (card is LovelaceCard.Conditional) {
        card.conditions + topLevelConditions(card.card)
    } else {
        emptyList()
    }

/** Local-clock snapshot the condition context reads: seconds since midnight and
 *  HA's lowercase weekday token. */
private data class ConditionClock(val secondsOfDay: Int, val weekday: String)

/**
 * A clock [State] that holds the current local seconds-of-day + weekday and, when
 * [hasTimeCondition], reschedules itself at the next time-condition boundary so a
 * `time` gate re-evaluates live. Recomputed whenever [conditions] change.
 */
@Composable
private fun rememberConditionClock(
    conditions: List<LovelaceCondition>,
    hasTimeCondition: Boolean,
): State<ConditionClock> = produceState(initialValue = currentConditionClock(), conditions, hasTimeCondition) {
    value = currentConditionClock()
    if (!hasTimeCondition) return@produceState
    val timeConditions = collectTimeConditions(conditions)
    while (true) {
        val now = LocalDateTime.now()
        val nowMillisOfDay = now.toLocalTime().get(ChronoField.MILLI_OF_DAY).toLong()
        // The soonest boundary across every time condition gates this card.
        val nextDelay = timeConditions
            .mapNotNull { nextTimeBoundaryMillis(it, nowMillisOfDay) }
            .minOrNull()
            ?: return@produceState
        delay(nextDelay.coerceIn(1L, MAX_TIME_DELAY_MS))
        value = currentConditionClock()
    }
}

private fun currentConditionClock(): ConditionClock {
    val now = LocalTime.now()
    val secondsOfDay = now.toSecondOfDay()
    return ConditionClock(secondsOfDay = secondsOfDay, weekday = currentWeekdayToken())
}

/** Today's weekday as HA's lowercase three-letter token (sun..sat). */
private fun currentWeekdayToken(): String = when (java.time.LocalDate.now().dayOfWeek) {
    java.time.DayOfWeek.MONDAY -> "mon"
    java.time.DayOfWeek.TUESDAY -> "tue"
    java.time.DayOfWeek.WEDNESDAY -> "wed"
    java.time.DayOfWeek.THURSDAY -> "thu"
    java.time.DayOfWeek.FRIDAY -> "fri"
    java.time.DayOfWeek.SATURDAY -> "sat"
    java.time.DayOfWeek.SUNDAY -> "sun"
}

private fun conditionsContainTime(conditions: List<LovelaceCondition>): Boolean =
    conditions.any { c ->
        when (c) {
            is LovelaceCondition.Time -> true
            is LovelaceCondition.And -> conditionsContainTime(c.conditions)
            is LovelaceCondition.Or -> conditionsContainTime(c.conditions)
            is LovelaceCondition.Not -> conditionsContainTime(c.conditions)
            else -> false
        }
    }

private fun collectTimeConditions(conditions: List<LovelaceCondition>): List<LovelaceCondition.Time> =
    conditions.flatMap { c ->
        when (c) {
            is LovelaceCondition.Time -> listOf(c)
            is LovelaceCondition.And -> collectTimeConditions(c.conditions)
            is LovelaceCondition.Or -> collectTimeConditions(c.conditions)
            is LovelaceCondition.Not -> collectTimeConditions(c.conditions)
            else -> emptyList()
        }
    }
