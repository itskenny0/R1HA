package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Locks in [coverStateLabel]: transit states win, the extremes read CLOSED/OPEN, and a
 * partial position falls through (null) to the percent readout.
 */
class CoverStateLabelTest {
    @Test fun `transit states take priority`() {
        assertThat(coverStateLabel("opening", 50)).isEqualTo("OPENING")
        assertThat(coverStateLabel("closing", 30)).isEqualTo("CLOSING")
        // Even at an extreme position, an in-transit raw state wins.
        assertThat(coverStateLabel("opening", 0)).isEqualTo("OPENING")
        // Case-insensitive.
        assertThat(coverStateLabel("CLOSING", 90)).isEqualTo("CLOSING")
    }

    @Test fun `extremes read closed and open`() {
        assertThat(coverStateLabel("closed", 0)).isEqualTo("CLOSED")
        assertThat(coverStateLabel("open", 100)).isEqualTo("OPEN")
        assertThat(coverStateLabel(null, 0)).isEqualTo("CLOSED")
        assertThat(coverStateLabel(null, 100)).isEqualTo("OPEN")
    }

    @Test fun `partial position falls through to percent`() {
        assertThat(coverStateLabel("open", 42)).isNull()
        assertThat(coverStateLabel(null, 50)).isNull()
    }

    @Test fun `positionless cover reads its raw open closed state`() {
        // No current_position attribute, so percent is null: a positionless garage door
        // or tilt-only blind must read its plain raw state instead of collapsing to a
        // misleading CLOSED/0% readout.
        assertThat(coverStateLabel("open", null)).isEqualTo("OPEN")
        assertThat(coverStateLabel("closed", null)).isEqualTo("CLOSED")
        assertThat(coverStateLabel("OPEN", null)).isEqualTo("OPEN")
        // An unknown raw word with no position still falls through to null.
        assertThat(coverStateLabel(null, null)).isNull()
        assertThat(coverStateLabel("jammed", null)).isNull()
    }
}
