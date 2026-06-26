package com.github.itskenny0.r1ha.core.input

import com.github.itskenny0.r1ha.core.input.HardwareLongPressTracker.Outcome
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Pure state-machine tests for the hardware long-press tracker. The tracker defers a
 * bound key's short action to release so a hold can fire a long-press shortcut instead;
 * these pin the short-vs-long decision so the central key dispatch can stay a thin shell
 * over a tested core.
 */
class HardwareLongPressTrackerTest {

    private val threshold = 450L

    @Test fun `quick press down then up fires the short action`() {
        val t = HardwareLongPressTracker(threshold)
        assertThat(t.onDown(keyCode = 23, eventTime = 0L, repeat = 0)).isEqualTo(Outcome.CONSUME)
        assertThat(t.onUp(keyCode = 23, eventTime = 120L)).isEqualTo(Outcome.FIRE_SHORT)
    }

    @Test fun `hold past threshold via auto-repeat fires long once, release consumes`() {
        val t = HardwareLongPressTracker(threshold)
        assertThat(t.onDown(23, 0L, 0)).isEqualTo(Outcome.CONSUME)
        // Early repeats before the threshold are consumed (no fire yet).
        assertThat(t.onDown(23, 200L, 1)).isEqualTo(Outcome.CONSUME)
        // First repeat past the threshold fires long.
        assertThat(t.onDown(23, 500L, 2)).isEqualTo(Outcome.FIRE_LONG)
        // Further repeats do NOT re-fire.
        assertThat(t.onDown(23, 800L, 3)).isEqualTo(Outcome.CONSUME)
        // Release after a long-press is consumed (the short action must not also fire).
        assertThat(t.onUp(23, 900L)).isEqualTo(Outcome.CONSUME)
    }

    @Test fun `hold past threshold without auto-repeat fires long on release`() {
        // Some keys do not auto-repeat: the only signal is a late UP. The tracker must
        // still recognise the long hold from the down-to-up duration.
        val t = HardwareLongPressTracker(threshold)
        assertThat(t.onDown(66, 0L, 0)).isEqualTo(Outcome.CONSUME)
        assertThat(t.onUp(66, 600L)).isEqualTo(Outcome.FIRE_LONG)
    }

    @Test fun `pressing a different key restarts tracking`() {
        val t = HardwareLongPressTracker(threshold)
        t.onDown(23, 0L, 0)
        // A new key's first down replaces the tracked key.
        assertThat(t.onDown(99, 50L, 0)).isEqualTo(Outcome.CONSUME)
        // The original key's stale UP is ignored (consumed, no fire).
        assertThat(t.onUp(23, 120L)).isEqualTo(Outcome.CONSUME)
        // The new key still behaves: a quick release fires short.
        assertThat(t.onUp(99, 150L)).isEqualTo(Outcome.FIRE_SHORT)
    }

    @Test fun `exact threshold counts as a long press`() {
        val t = HardwareLongPressTracker(threshold)
        t.onDown(23, 1000L, 0)
        assertThat(t.onUp(23, 1450L)).isEqualTo(Outcome.FIRE_LONG)
    }
}
