package com.github.itskenny0.r1ha.feature.helpers

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure-logic coverage for the Helpers surface: per-domain control selection,
 * numeric bounds + step snapping, text length clamping, the search filter, the
 * select cycle, and input_datetime field parsing. No Compose / repository deps.
 */
class HelpersLogicTest {

    // ── control selection ──────────────────────────────────────────────────

    @Test
    fun `kind selection covers helper twins and general domains`() {
        assertThat(HelpersLogic.kindForDomain("input_boolean")).isEqualTo(HelpersViewModel.Kind.BOOLEAN)
        assertThat(HelpersLogic.kindForDomain("input_number")).isEqualTo(HelpersViewModel.Kind.NUMBER)
        assertThat(HelpersLogic.kindForDomain("number")).isEqualTo(HelpersViewModel.Kind.NUMBER)
        assertThat(HelpersLogic.kindForDomain("counter")).isEqualTo(HelpersViewModel.Kind.COUNTER)
        assertThat(HelpersLogic.kindForDomain("input_select")).isEqualTo(HelpersViewModel.Kind.SELECT)
        assertThat(HelpersLogic.kindForDomain("select")).isEqualTo(HelpersViewModel.Kind.SELECT)
        assertThat(HelpersLogic.kindForDomain("input_text")).isEqualTo(HelpersViewModel.Kind.TEXT)
        assertThat(HelpersLogic.kindForDomain("input_datetime")).isEqualTo(HelpersViewModel.Kind.DATETIME)
        assertThat(HelpersLogic.kindForDomain("input_button")).isEqualTo(HelpersViewModel.Kind.BUTTON)
        assertThat(HelpersLogic.kindForDomain("button")).isEqualTo(HelpersViewModel.Kind.BUTTON)
        assertThat(HelpersLogic.kindForDomain("timer")).isEqualTo(HelpersViewModel.Kind.TIMER)
        assertThat(HelpersLogic.kindForDomain("sensor")).isEqualTo(HelpersViewModel.Kind.UNKNOWN)
    }

    // ── number clamp / step ────────────────────────────────────────────────

    @Test
    fun `clampNumber respects open bounds`() {
        assertThat(HelpersLogic.clampNumber(5.0, null, null, null)).isEqualTo(5.0)
    }

    @Test
    fun `clampNumber coerces into window`() {
        assertThat(HelpersLogic.clampNumber(-3.0, 0.0, 10.0, null)).isEqualTo(0.0)
        assertThat(HelpersLogic.clampNumber(99.0, 0.0, 10.0, null)).isEqualTo(10.0)
    }

    @Test
    fun `clampNumber snaps onto the step grid from min`() {
        // min 0, step 5 -> 7 snaps to 5
        assertThat(HelpersLogic.clampNumber(7.0, 0.0, 100.0, 5.0)).isEqualTo(5.0)
        // 8 snaps to 10
        assertThat(HelpersLogic.clampNumber(8.0, 0.0, 100.0, 5.0)).isEqualTo(10.0)
        // offset grid: min 1, step 0.5 -> 2.2 snaps to 2.0
        assertThat(HelpersLogic.clampNumber(2.2, 1.0, 5.0, 0.5)).isEqualTo(2.0)
    }

    @Test
    fun `clampNumber snapping never overshoots max`() {
        // min 0, step 3, max 10: 10 would snap to 9 (3*3) which is in range,
        // but 11 coerces to 10 then snaps to 9.
        assertThat(HelpersLogic.clampNumber(11.0, 0.0, 10.0, 3.0)).isAtMost(10.0)
    }

    @Test
    fun `stepNumber moves one step in each direction and clamps`() {
        assertThat(HelpersLogic.stepNumber(5.0, up = true, 0.0, 10.0, 1.0)).isEqualTo(6.0)
        assertThat(HelpersLogic.stepNumber(5.0, up = false, 0.0, 10.0, 1.0)).isEqualTo(4.0)
        assertThat(HelpersLogic.stepNumber(10.0, up = true, 0.0, 10.0, 1.0)).isEqualTo(10.0)
        assertThat(HelpersLogic.stepNumber(0.0, up = false, 0.0, 10.0, 1.0)).isEqualTo(0.0)
        // null step defaults to 1
        assertThat(HelpersLogic.stepNumber(2.0, up = true, null, null, null)).isEqualTo(3.0)
    }

    // ── text ───────────────────────────────────────────────────────────────

    @Test
    fun `clampText truncates over max and defaults to 100`() {
        assertThat(HelpersLogic.clampText("abcdef", null, 3)).isEqualTo("abc")
        assertThat(HelpersLogic.clampText("short", null, null)).isEqualTo("short")
        val long = "x".repeat(150)
        assertThat(HelpersLogic.clampText(long, null, null).length).isEqualTo(100)
    }

    @Test
    fun `textMeetsMinLength gates short values`() {
        assertThat(HelpersLogic.textMeetsMinLength("ab", 3)).isFalse()
        assertThat(HelpersLogic.textMeetsMinLength("abc", 3)).isTrue()
        assertThat(HelpersLogic.textMeetsMinLength("", null)).isTrue()
    }

    // ── filter ─────────────────────────────────────────────────────────────

    @Test
    fun `matchesQuery is case-insensitive over name and id`() {
        assertThat(HelpersLogic.matchesQuery("Kitchen Light", "input_boolean.kitchen", "")).isTrue()
        assertThat(HelpersLogic.matchesQuery("Kitchen Light", "input_boolean.kitchen", "KITCHEN")).isTrue()
        assertThat(HelpersLogic.matchesQuery("Away Mode", "input_boolean.away", "kitchen")).isFalse()
        // matches on entity id even when the name doesn't
        assertThat(HelpersLogic.matchesQuery("Away Mode", "input_boolean.guest_kitchen", "kitchen")).isTrue()
    }

    // ── select cycle ───────────────────────────────────────────────────────

    @Test
    fun `cycleSelectIndex wraps both directions`() {
        assertThat(HelpersLogic.cycleSelectIndex(0, 3, forward = true)).isEqualTo(1)
        assertThat(HelpersLogic.cycleSelectIndex(2, 3, forward = true)).isEqualTo(0)
        assertThat(HelpersLogic.cycleSelectIndex(0, 3, forward = false)).isEqualTo(2)
        assertThat(HelpersLogic.cycleSelectIndex(5, 0, forward = true)).isEqualTo(0)
    }

    // ── datetime ───────────────────────────────────────────────────────────

    @Test
    fun `splitDateTimeState parses combined state`() {
        val (d, t) = HelpersLogic.splitDateTimeState("2024-01-15 14:30:00", hasDate = true, hasTime = true)
        assertThat(d).isEqualTo("2024-01-15")
        assertThat(t).isEqualTo("14:30:00")
    }

    @Test
    fun `splitDateTimeState honours has flags`() {
        val dateOnly = HelpersLogic.splitDateTimeState("2024-01-15", hasDate = true, hasTime = false)
        assertThat(dateOnly.first).isEqualTo("2024-01-15")
        assertThat(dateOnly.second).isNull()

        val timeOnly = HelpersLogic.splitDateTimeState("14:30:00", hasDate = false, hasTime = true)
        assertThat(timeOnly.first).isNull()
        assertThat(timeOnly.second).isEqualTo("14:30:00")
    }

    @Test
    fun `normaliseTime pads to HHMMSS`() {
        assertThat(HelpersLogic.normaliseTime("9:5")).isEqualTo("09:05:00")
        assertThat(HelpersLogic.normaliseTime("14:30")).isEqualTo("14:30:00")
        assertThat(HelpersLogic.normaliseTime("14:30:07")).isEqualTo("14:30:07")
    }

    @Test
    fun `isValidDate accepts plausible dates and rejects garbage`() {
        assertThat(HelpersLogic.isValidDate("2024-01-15")).isTrue()
        assertThat(HelpersLogic.isValidDate("2024-13-15")).isFalse()
        assertThat(HelpersLogic.isValidDate("2024-1-5")).isFalse()
        assertThat(HelpersLogic.isValidDate("nope")).isFalse()
    }

    @Test
    fun `isValidTime accepts HHMM and HHMMSS`() {
        assertThat(HelpersLogic.isValidTime("14:30")).isTrue()
        assertThat(HelpersLogic.isValidTime("14:30:07")).isTrue()
        assertThat(HelpersLogic.isValidTime("24:00")).isFalse()
        assertThat(HelpersLogic.isValidTime("12:60")).isFalse()
        assertThat(HelpersLogic.isValidTime("noon")).isFalse()
    }
}
