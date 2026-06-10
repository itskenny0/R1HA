package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Test

/**
 * Parser coverage for the conditions engine: the runtime condition types
 * (screen / time / location / user / view_columns), the context-entity fallback
 * (state/numeric with no `entity:`), the entity-id deref accept-list, and the
 * `disabled: true` card key. Pure-helper coverage (media-query / time window)
 * lives in LovelaceConditionEvaluatorTest.
 */
class LovelaceConditionParserTest {

    private fun cond(raw: String): LovelaceCondition {
        val arr = Json.parseToJsonElement("[$raw]")
        return LovelaceParser.parseConditions(arr).single()
    }

    private fun conds(raw: String): List<LovelaceCondition> =
        LovelaceParser.parseConditions(Json.parseToJsonElement(raw) as JsonElement)

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // --- state / numeric: context-entity fallback --------------------------

    @Test fun `state without entity keeps a null entity for context fallback`() {
        val c = cond("""{"condition": "state", "state": "on"}""") as LovelaceCondition.StateEquals
        assertThat(c.entityId).isNull()
        assertThat(c.states).containsExactly("on")
    }

    @Test fun `numeric_state without entity keeps a null entity for context fallback`() {
        val c = cond("""{"condition": "numeric_state", "above": 10}""") as LovelaceCondition.NumericState
        assertThat(c.entityId).isNull()
        assertThat(c.above).isEqualTo(10.0)
    }

    @Test fun `state with entity and a list of values`() {
        val c = cond("""{"condition": "state", "entity": "s.mode", "state": ["home", "away"]}""")
            as LovelaceCondition.StateEquals
        assertThat(c.entityId).isEqualTo("s.mode")
        assertThat(c.states).containsExactly("home", "away")
    }

    @Test fun `state_not negates`() {
        val c = cond("""{"condition": "state_not", "entity": "s.mode", "state_not": "off"}""")
            as LovelaceCondition.StateEquals
        assertThat(c.negate).isTrue()
    }

    @Test fun `numeric bound as entity id becomes an entity reference`() {
        val c = cond("""{"condition": "numeric_state", "entity": "s.in", "above": "sensor.threshold"}""")
            as LovelaceCondition.NumericState
        assertThat(c.above).isNull()
        assertThat(c.aboveEntity).isEqualTo("sensor.threshold")
    }

    @Test fun `numeric_state with no usable bound fails closed`() {
        assertThat(cond("""{"condition": "numeric_state", "entity": "s.in"}""")).isEqualTo(LovelaceCondition.Never)
    }

    // --- screen ------------------------------------------------------------

    @Test fun `screen keeps the media query`() {
        val c = cond("""{"condition": "screen", "media_query": "(min-width: 600px)"}""")
            as LovelaceCondition.Screen
        assertThat(c.mediaQuery).isEqualTo("(min-width: 600px)")
    }

    @Test fun `screen without media_query fails closed`() {
        assertThat(cond("""{"condition": "screen"}""")).isEqualTo(LovelaceCondition.Never)
    }

    // --- time --------------------------------------------------------------

    @Test fun `time parses after before and weekdays`() {
        val c = cond("""{"condition": "time", "after": "08:00", "before": "17:30:15", "weekdays": ["mon", "fri"]}""")
            as LovelaceCondition.Time
        assertThat(c.after).isEqualTo(TimeOfDay(8, 0, 0))
        assertThat(c.before).isEqualTo(TimeOfDay(17, 30, 15))
        assertThat(c.weekdays).containsExactly("mon", "fri")
    }

    @Test fun `time weekday-only is valid`() {
        val c = cond("""{"condition": "time", "weekdays": ["sat", "sun"]}""") as LovelaceCondition.Time
        assertThat(c.after).isNull()
        assertThat(c.before).isNull()
        assertThat(c.weekdays).containsExactly("sat", "sun")
    }

    @Test fun `time with no bounds and no weekdays fails closed`() {
        assertThat(cond("""{"condition": "time"}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `time rejects an invalid time string`() {
        assertThat(cond("""{"condition": "time", "after": "8:00 AM"}""")).isEqualTo(LovelaceCondition.Never)
        assertThat(cond("""{"condition": "time", "after": "25:00"}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `time rejects an unknown weekday token`() {
        assertThat(cond("""{"condition": "time", "weekdays": ["funday"]}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `time rejects after equal to before`() {
        assertThat(cond("""{"condition": "time", "after": "08:00", "before": "08:00"}""")).isEqualTo(LovelaceCondition.Never)
    }

    // --- user / location / view_columns -----------------------------------

    @Test fun `user keeps the id list`() {
        val c = cond("""{"condition": "user", "users": ["a", "b"]}""") as LovelaceCondition.User
        assertThat(c.userIds).containsExactly("a", "b")
    }

    @Test fun `user with empty list fails closed`() {
        assertThat(cond("""{"condition": "user", "users": []}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `location keeps the zones`() {
        val c = cond("""{"condition": "location", "locations": ["home", "work"]}""")
            as LovelaceCondition.Location
        assertThat(c.locations).containsExactly("home", "work")
    }

    @Test fun `location with empty list fails closed`() {
        assertThat(cond("""{"condition": "location", "locations": []}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `view_columns parses min and max`() {
        val c = cond("""{"condition": "view_columns", "min": 2, "max": 4}""")
            as LovelaceCondition.ViewColumns
        assertThat(c.min).isEqualTo(2)
        assertThat(c.max).isEqualTo(4)
    }

    @Test fun `view_columns with neither bound fails closed`() {
        assertThat(cond("""{"condition": "view_columns"}""")).isEqualTo(LovelaceCondition.Never)
    }

    @Test fun `a template condition still fails closed`() {
        assertThat(cond("""{"condition": "template", "value_template": "{{ true }}"}"""))
            .isEqualTo(LovelaceCondition.Never)
    }

    // --- logical groups carry the new types --------------------------------

    @Test fun `and group nests a time and a screen condition`() {
        val group = cond(
            """
            {"condition": "and", "conditions": [
              {"condition": "time", "after": "08:00"},
              {"condition": "screen", "media_query": "(min-width: 600px)"}
            ]}
            """.trimIndent(),
        ) as LovelaceCondition.And
        assertThat(group.conditions[0]).isInstanceOf(LovelaceCondition.Time::class.java)
        assertThat(group.conditions[1]).isInstanceOf(LovelaceCondition.Screen::class.java)
    }

    // --- disabled card key -------------------------------------------------

    @Test fun `disabled true wraps the card in a never-passing conditional`() {
        val c = card("""{"type": "markdown", "content": "hi", "disabled": true}""")
        assertThat(c).isInstanceOf(LovelaceCard.Conditional::class.java)
        val cond = c as LovelaceCard.Conditional
        assertThat(cond.conditions).containsExactly(LovelaceCondition.Never)
        assertThat(cond.card).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `disabled false leaves the card unwrapped`() {
        val c = card("""{"type": "markdown", "content": "hi", "disabled": false}""")
        assertThat(c).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `a disabled card never renders`() {
        val c = card("""{"type": "markdown", "content": "hi", "disabled": true}""")
        assertThat(evaluateLovelaceCondition((c as LovelaceCard.Conditional).conditions.single(), emptyMap()))
            .isFalse()
    }

    // --- TimeOfDay validation ---------------------------------------------

    @Test fun `TimeOfDay parses HH MM and HH MM SS`() {
        assertThat(TimeOfDay.parse("08:30")).isEqualTo(TimeOfDay(8, 30, 0))
        assertThat(TimeOfDay.parse("23:59:59")).isEqualTo(TimeOfDay(23, 59, 59))
    }

    @Test fun `TimeOfDay rejects malformed strings`() {
        assertThat(TimeOfDay.parse("")).isNull()
        assertThat(TimeOfDay.parse("8")).isNull()
        assertThat(TimeOfDay.parse("8:00 AM")).isNull()
        assertThat(TimeOfDay.parse("24:00")).isNull()
        assertThat(TimeOfDay.parse("08:60")).isNull()
        assertThat(TimeOfDay.parse("08:00:60")).isNull()
        assertThat(TimeOfDay.parse("08:00:00:00")).isNull()
    }
}
