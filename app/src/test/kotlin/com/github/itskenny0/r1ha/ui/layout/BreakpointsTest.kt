package com.github.itskenny0.r1ha.ui.layout

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.util.Locale

/**
 * Locks the camera-grid column progression to the exact width boundaries the dashboard has
 * always used (2 / 2 / 3 / 4), so the unification onto WindowTier cannot silently shift a
 * boundary by a dp.
 */
class BreakpointsTest {

    init {
        Locale.setDefault(Locale.US)
    }

    @Test
    fun `r1 and tiny widths use two columns`() {
        assertThat(cameraGridColumnsForWidthDp(0)).isEqualTo(2)
        assertThat(cameraGridColumnsForWidthDp(240)).isEqualTo(2)
        assertThat(cameraGridColumnsForWidthDp(360)).isEqualTo(2)
    }

    @Test
    fun `ordinary phone widths use two columns`() {
        assertThat(cameraGridColumnsForWidthDp(361)).isEqualTo(2)
        assertThat(cameraGridColumnsForWidthDp(599)).isEqualTo(2)
    }

    @Test
    fun `tablet widths use three columns up to the wide boundary`() {
        assertThat(cameraGridColumnsForWidthDp(600)).isEqualTo(3)
        assertThat(cameraGridColumnsForWidthDp(840)).isEqualTo(3)
        assertThat(cameraGridColumnsForWidthDp(959)).isEqualTo(3)
    }

    @Test
    fun `very wide widths use four columns`() {
        assertThat(cameraGridColumnsForWidthDp(960)).isEqualTo(4)
        assertThat(cameraGridColumnsForWidthDp(1200)).isEqualTo(4)
        assertThat(cameraGridColumnsForWidthDp(4000)).isEqualTo(4)
    }
}
