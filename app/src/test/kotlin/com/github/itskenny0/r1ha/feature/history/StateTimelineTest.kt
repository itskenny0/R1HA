package com.github.itskenny0.r1ha.feature.history

import com.github.itskenny0.r1ha.core.ha.HistoryPoint
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * Locks in the categorical / state-timeline path that backs non-numeric
 * entities (binary_sensor, person, climate mode, text sensors). The worries:
 * (1) consecutive identical states collapse into one segment the way HA dedups,
 * (2) segments span the union window as [0..1] fractions, (3) a single instant
 * still renders one full-width band, (4) the numeric vs categorical fork picks
 * the right renderer, (5) state labels and the spoken description read cleanly.
 */
class StateTimelineTest {

    private fun pt(epochSec: Long, state: String, numeric: Double? = state.toDoubleOrNull()) =
        HistoryPoint(timestamp = Instant.ofEpochSecond(epochSec), state = state, numeric = numeric)

    @Test fun `null for empty history`() {
        assertThat(buildTimelineProjection(emptyList())).isNull()
    }

    @Test fun `collapses consecutive duplicate states into runs`() {
        val pts = listOf(
            pt(0, "off"),
            pt(100, "off"),
            pt(200, "on"),
            pt(300, "on"),
            pt(400, "off"),
        )
        val tl = buildTimelineProjection(pts)!!
        // off, on, off -> three segments despite five samples.
        assertThat(tl.segments.map { it.state }).containsExactly("off", "on", "off").inOrder()
        assertThat(tl.distinctStates).containsExactly("off", "on").inOrder()
    }

    @Test fun `segments span 0 to 1 of the window`() {
        val pts = listOf(pt(0, "a"), pt(50, "b"), pt(100, "a"))
        val tl = buildTimelineProjection(pts)!!
        assertThat(tl.segments.first().startFrac).isWithin(1e-4f).of(0f)
        assertThat(tl.segments.last().endFrac).isWithin(1e-4f).of(1f)
        // The middle "b" run starts at 50/100 = 0.5.
        assertThat(tl.segments[1].startFrac).isWithin(1e-4f).of(0.5f)
    }

    @Test fun `single instant renders one full-width band`() {
        val tl = buildTimelineProjection(listOf(pt(0, "home")))!!
        assertThat(tl.segments).hasSize(1)
        assertThat(tl.segments.single().startFrac).isEqualTo(0f)
        assertThat(tl.segments.single().endFrac).isEqualTo(1f)
        assertThat(tl.segments.single().state).isEqualTo("home")
    }

    @Test fun `isCategoricalHistory true when fewer than two numeric samples`() {
        val text = listOf(pt(0, "home"), pt(100, "away"), pt(200, "home"))
        assertThat(isCategoricalHistory(text)).isTrue()
        val numeric = listOf(pt(0, "1.0"), pt(100, "2.0"))
        assertThat(isCategoricalHistory(numeric)).isFalse()
        assertThat(isCategoricalHistory(emptyList())).isFalse()
    }

    @Test fun `formatStateLabel titlecases snake_case and names sentinels`() {
        assertThat(formatStateLabel("not_home")).isEqualTo("Not Home")
        assertThat(formatStateLabel("on")).isEqualTo("On")
        assertThat(formatStateLabel("unavailable")).isEqualTo("Unavailable")
        assertThat(formatStateLabel("unknown")).isEqualTo("Unknown")
    }

    @Test fun `timeline description names current state and change count`() {
        val tl = buildTimelineProjection(
            listOf(pt(0, "off"), pt(100, "on"), pt(200, "off")),
        )
        val desc = buildTimelineContentDescription("Front Door", tl)
        assertThat(desc).contains("Front Door")
        assertThat(desc).contains("Currently Off")
        assertThat(desc).contains("2 changes")
        assertThat(desc).contains("Off")
        assertThat(desc).contains("On")
    }

    @Test fun `timeline description handles null`() {
        assertThat(buildTimelineContentDescription("X", null))
            .isEqualTo("State timeline for X with no history to display.")
    }
}
