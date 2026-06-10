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

    // --- state: entity-id dereference --------------------------------------

    @Test
    fun `state value that is an entity id is dereferenced to that entity's state`() {
        // HA accepts both the literal token AND the referenced entity's state.
        val c = LovelaceCondition.StateEquals("input_select.mode", listOf("sensor.target"))
        // input_select.mode == sensor.target's current state -> match
        assertThat(eval(c, mapOf("input_select.mode" to "home", "sensor.target" to "home"))).isTrue()
        assertThat(eval(c, mapOf("input_select.mode" to "away", "sensor.target" to "home"))).isFalse()
    }

    @Test
    fun `state value still matches the literal token when it looks like an entity id`() {
        // When the listed entity id has no live state, the literal token is kept.
        val c = LovelaceCondition.StateEquals("light.k", listOf("light.k"))
        assertThat(eval(c, mapOf("light.k" to "light.k"))).isTrue()
    }

    // --- context entity fallback ------------------------------------------

    @Test
    fun `state condition without entity uses the context entity`() {
        val ctx = LovelaceConditionContext(contextEntityId = "light.host")
        val c = LovelaceCondition.StateEquals(entityId = null, states = listOf("on"))
        assertThat(evaluateLovelaceCondition(c, mapOf("light.host" to "on"), ctx)).isTrue()
        assertThat(evaluateLovelaceCondition(c, mapOf("light.host" to "off"), ctx)).isFalse()
    }

    @Test
    fun `state condition without entity and without context fails closed`() {
        val c = LovelaceCondition.StateEquals(entityId = null, states = listOf("on"))
        assertThat(eval(c, mapOf("light.host" to "on"))).isFalse()
    }

    @Test
    fun `numeric condition without entity uses the context entity`() {
        val ctx = LovelaceConditionContext(contextEntityId = "sensor.host")
        val c = LovelaceCondition.NumericState(entityId = null, above = 10.0, below = null)
        assertThat(evaluateLovelaceCondition(c, mapOf("sensor.host" to "15"), ctx)).isTrue()
        assertThat(evaluateLovelaceCondition(c, mapOf("sensor.host" to "5"), ctx)).isFalse()
    }

    // --- user --------------------------------------------------------------

    @Test
    fun `user condition matches the current user id`() {
        val ctx = LovelaceConditionContext(currentUserId = "abc")
        assertThat(evaluateLovelaceCondition(LovelaceCondition.User(listOf("abc", "def")), emptyMap(), ctx)).isTrue()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.User(listOf("def")), emptyMap(), ctx)).isFalse()
    }

    @Test
    fun `user condition fails closed for an unknown current user`() {
        // HA returns false when hass.user?.id is undefined.
        assertThat(eval(LovelaceCondition.User(listOf("abc")), emptyMap())).isFalse()
    }

    // --- screen (media query) ---------------------------------------------

    @Test
    fun `screen min-width matches when window is at least the bound`() {
        val wide = LovelaceConditionContext(windowWidthPx = 800, windowHeightPx = 480)
        val narrow = LovelaceConditionContext(windowWidthPx = 320, windowHeightPx = 480)
        val c = LovelaceCondition.Screen("(min-width: 600px)")
        assertThat(evaluateLovelaceCondition(c, emptyMap(), wide)).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), narrow)).isFalse()
    }

    @Test
    fun `screen max-width matches when window is at most the bound`() {
        val c = LovelaceCondition.Screen("(max-width: 500px)")
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 320, windowHeightPx = 480))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 700, windowHeightPx = 480))).isFalse()
    }

    @Test
    fun `screen combined and clause requires every clause`() {
        val c = LovelaceCondition.Screen("(min-width: 600px) and (max-width: 1000px)")
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 800, windowHeightPx = 480))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 1200, windowHeightPx = 480))).isFalse()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 400, windowHeightPx = 480))).isFalse()
    }

    @Test
    fun `screen orientation reads window aspect`() {
        val landscape = LovelaceConditionContext(windowWidthPx = 640, windowHeightPx = 480)
        val portrait = LovelaceConditionContext(windowWidthPx = 480, windowHeightPx = 640)
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Screen("(orientation: landscape)"), emptyMap(), landscape)).isTrue()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Screen("(orientation: portrait)"), emptyMap(), landscape)).isFalse()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Screen("(orientation: portrait)"), emptyMap(), portrait)).isTrue()
    }

    @Test
    fun `screen strips a leading screen-and media type`() {
        val c = LovelaceCondition.Screen("screen and (min-width: 600px)")
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 700, windowHeightPx = 480))).isTrue()
    }

    @Test
    fun `screen fails open when window size is unknown`() {
        val c = LovelaceCondition.Screen("(min-width: 600px)")
        assertThat(eval(c, emptyMap())).isTrue() // EMPTY context: width null -> fail open
    }

    @Test
    fun `screen fails open on an unparseable query`() {
        val c = LovelaceCondition.Screen("(prefers-color-scheme: dark)")
        assertThat(evaluateLovelaceCondition(c, emptyMap(), LovelaceConditionContext(windowWidthPx = 640, windowHeightPx = 480))).isTrue()
    }

    // --- time --------------------------------------------------------------

    private fun timeCtx(hms: String, weekday: String = "mon"): LovelaceConditionContext {
        val t = TimeOfDay.parse(hms)!!
        return LovelaceConditionContext(nowSecondsOfDay = t.secondsOfDay, weekday = weekday)
    }

    @Test
    fun `time after-before window matches inside and rejects outside`() {
        val c = LovelaceCondition.Time(after = TimeOfDay.parse("08:00"), before = TimeOfDay.parse("17:00"))
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00"))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("07:59"))).isFalse()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("17:30"))).isFalse()
    }

    @Test
    fun `time window boundaries are inclusive (HA not-before, not-after)`() {
        val c = LovelaceCondition.Time(after = TimeOfDay.parse("08:00"), before = TimeOfDay.parse("17:00"))
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("08:00"))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("17:00"))).isTrue()
    }

    @Test
    fun `time window wraps across midnight`() {
        val c = LovelaceCondition.Time(after = TimeOfDay.parse("22:00"), before = TimeOfDay.parse("06:00"))
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("23:30"))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("02:00"))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00"))).isFalse()
    }

    @Test
    fun `time after-only and before-only`() {
        val afterOnly = LovelaceCondition.Time(after = TimeOfDay.parse("18:00"))
        assertThat(evaluateLovelaceCondition(afterOnly, emptyMap(), timeCtx("19:00"))).isTrue()
        assertThat(evaluateLovelaceCondition(afterOnly, emptyMap(), timeCtx("17:00"))).isFalse()
        val beforeOnly = LovelaceCondition.Time(before = TimeOfDay.parse("06:00"))
        assertThat(evaluateLovelaceCondition(beforeOnly, emptyMap(), timeCtx("05:00"))).isTrue()
        assertThat(evaluateLovelaceCondition(beforeOnly, emptyMap(), timeCtx("07:00"))).isFalse()
    }

    @Test
    fun `time weekday allow-list gates the day`() {
        val c = LovelaceCondition.Time(weekdays = listOf("sat", "sun"))
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00", weekday = "sat"))).isTrue()
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00", weekday = "mon"))).isFalse()
    }

    @Test
    fun `time weekday plus window both apply`() {
        val c = LovelaceCondition.Time(
            after = TimeOfDay.parse("08:00"),
            before = TimeOfDay.parse("17:00"),
            weekdays = listOf("mon"),
        )
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00", weekday = "mon"))).isTrue()
        // right time, wrong day
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("12:00", weekday = "tue"))).isFalse()
        // right day, wrong time
        assertThat(evaluateLovelaceCondition(c, emptyMap(), timeCtx("20:00", weekday = "mon"))).isFalse()
    }

    @Test
    fun `empty time condition is vacuously true`() {
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Time(), emptyMap(), timeCtx("12:00"))).isTrue()
    }

    @Test
    fun `time next boundary picks the soonest crossing plus the buffer`() {
        // now = 12:00:00 = 43_200_000 ms of day; after=18:00 -> 6h away, before=06:00 -> 18h away.
        val nowMs = 12L * 3600 * 1000
        val c = LovelaceCondition.Time(after = TimeOfDay.parse("18:00"), before = TimeOfDay.parse("06:00"))
        val delay = nextTimeBoundaryMillis(c, nowMs)!!
        // soonest is 18:00 (6h) + 1-minute buffer
        assertThat(delay).isEqualTo(6L * 3600 * 1000 + 60_000L)
    }

    @Test
    fun `time next boundary schedules tomorrow when the bound already passed today`() {
        // now = 19:00, after = 18:00 already passed -> next crossing is tomorrow 18:00.
        val nowMs = 19L * 3600 * 1000
        val c = LovelaceCondition.Time(after = TimeOfDay.parse("18:00"))
        val delay = nextTimeBoundaryMillis(c, nowMs)!!
        val msPerDay = 24L * 3600 * 1000
        assertThat(delay).isEqualTo(msPerDay - nowMs + 18L * 3600 * 1000 + 60_000L)
    }

    @Test
    fun `time next boundary adds a midnight crossing for a partial weekday list`() {
        // now = 23:00, weekdays partial -> next midnight (1h away) is the soonest.
        val nowMs = 23L * 3600 * 1000
        val c = LovelaceCondition.Time(weekdays = listOf("mon", "tue"))
        val delay = nextTimeBoundaryMillis(c, nowMs)!!
        assertThat(delay).isEqualTo(1L * 3600 * 1000 + 60_000L)
    }

    @Test
    fun `time next boundary is null with no time and a full-week list`() {
        val c = LovelaceCondition.Time(weekdays = listOf("sun", "mon", "tue", "wed", "thu", "fri", "sat"))
        assertThat(nextTimeBoundaryMillis(c, 0L)).isNull()
    }

    // --- location ----------------------------------------------------------

    @Test
    fun `location matches the current user's person state`() {
        val ctx = LovelaceConditionContext(currentUserId = "u1", personStateForUser = { "home" })
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Location(listOf("home", "work")), emptyMap(), ctx)).isTrue()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Location(listOf("work")), emptyMap(), ctx)).isFalse()
    }

    @Test
    fun `location fails closed for unknown user or missing person`() {
        // no current user
        assertThat(eval(LovelaceCondition.Location(listOf("home")), emptyMap())).isFalse()
        // user but no person entity
        val ctx = LovelaceConditionContext(currentUserId = "u1", personStateForUser = { null })
        assertThat(evaluateLovelaceCondition(LovelaceCondition.Location(listOf("home")), emptyMap(), ctx)).isFalse()
    }

    // --- view_columns ------------------------------------------------------

    @Test
    fun `view_columns min and max against the column count`() {
        val twoCols = LovelaceConditionContext(maxColumns = 2)
        assertThat(evaluateLovelaceCondition(LovelaceCondition.ViewColumns(min = 2, max = null), emptyMap(), twoCols)).isTrue()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.ViewColumns(min = 3, max = null), emptyMap(), twoCols)).isFalse()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.ViewColumns(min = null, max = 1), emptyMap(), twoCols)).isFalse()
        assertThat(evaluateLovelaceCondition(LovelaceCondition.ViewColumns(min = 1, max = 4), emptyMap(), twoCols)).isTrue()
    }

    @Test
    fun `view_columns passes when the column count is unavailable`() {
        // HA: if (!context.max_columns) return true.
        val unknown = LovelaceConditionContext(maxColumns = null)
        assertThat(evaluateLovelaceCondition(LovelaceCondition.ViewColumns(min = 2, max = null), emptyMap(), unknown)).isTrue()
    }

    @Test
    fun `view_columns default single column gates a min of 2 closed`() {
        // EMPTY context defaults to 1 column.
        assertThat(eval(LovelaceCondition.ViewColumns(min = 2, max = null), emptyMap())).isFalse()
        assertThat(eval(LovelaceCondition.ViewColumns(min = 1, max = 1), emptyMap())).isTrue()
    }
}
