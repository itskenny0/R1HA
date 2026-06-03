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
}
