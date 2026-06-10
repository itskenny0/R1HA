package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Locks in [formatRelativeTime]'s bidirectional buckets: past instants read "… ago",
 * future instants read "in …", and the sub-30-second window collapses to "just now" in
 * both directions. The future side is what fixes the sun card / timer / next-event labels
 * that previously all showed "just now".
 */
class RelativeTimeTest {
    private val now: Instant = Instant.ofEpochSecond(1_000_000)
    private fun past(sec: Long) = formatRelativeTime(now.minusSeconds(sec), now)
    private fun future(sec: Long) = formatRelativeTime(now.plusSeconds(sec), now)

    @Test fun `sub-30-seconds is just now either side`() {
        assertThat(formatRelativeTime(now, now)).isEqualTo("just now")
        assertThat(past(10)).isEqualTo("just now")
        assertThat(future(10)).isEqualTo("just now")
    }

    @Test fun `past instants read ago`() {
        assertThat(past(45)).isEqualTo("45s ago")
        assertThat(past(5 * 60)).isEqualTo("5m ago")
        assertThat(past(3 * 3600)).isEqualTo("3h ago")
        assertThat(past(2 * 86_400)).isEqualTo("2d ago")
        assertThat(past(14 * 86_400)).isEqualTo("2w ago")
    }

    @Test fun `future instants read in`() {
        assertThat(future(45)).isEqualTo("in 45s")
        assertThat(future(5 * 60)).isEqualTo("in 5m")
        assertThat(future(2 * 3600)).isEqualTo("in 2h")
        assertThat(future(3 * 86_400)).isEqualTo("in 3d")
        assertThat(future(14 * 86_400)).isEqualTo("in 2w")
    }

    @Test fun `weeks hold until a month then switch to months`() {
        assertThat(past(21 * 86_400)).isEqualTo("3w ago")
        assertThat(past(29 * 86_400)).isEqualTo("4w ago")
        assertThat(past(30 * 86_400)).isEqualTo("1mo ago")
        assertThat(past(75 * 86_400)).isEqualTo("2mo ago")
        assertThat(past(200 * 86_400)).isEqualTo("6mo ago")
    }

    @Test fun `dst spring-forward counts physical hours, not wall-clock hours`() {
        // Europe/Berlin, 2026-03-29: 02:00 CET jumps to 03:00 CEST. From 00:30
        // local (23:30Z) to a 06:30 CEST sunrise (04:30Z) the wall clock shows a
        // six-hour difference but only five physical hours elapse. The label is
        // a countdown, so the Instant-based "in 5h" is the correct reading; this
        // pins that the implementation stays on epoch deltas and never switches
        // to wall-clock field arithmetic.
        val before = Instant.parse("2026-03-28T23:30:00Z") // 00:30 CET
        val sunrise = Instant.parse("2026-03-29T04:30:00Z") // 06:30 CEST
        assertThat(formatRelativeTime(sunrise, before)).isEqualTo("in 5h")
        // And the symmetric past reading after the jump.
        assertThat(formatRelativeTime(before, sunrise)).isEqualTo("5h ago")
    }

    @Test fun `a year or more reads in years`() {
        assertThat(past(365 * 86_400)).isEqualTo("1y ago")
        assertThat(past(800 * 86_400)).isEqualTo("2y ago")
        assertThat(future(400 * 86_400)).isEqualTo("in 1y")
    }
}
