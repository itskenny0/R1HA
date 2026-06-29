package com.github.itskenny0.r1ha.feature.widget

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class WidgetRenderTierTest {

    @Test fun `a one-cell tile is compact`() {
        assertThat(widgetRenderTier(60, 60)).isEqualTo(RenderTier.COMPACT)
    }

    @Test fun `compact requires both axes small`() {
        assertThat(widgetRenderTier(129, 79)).isEqualTo(RenderTier.COMPACT)
    }

    @Test fun `wide but short is medium not compact`() {
        // width not under 130 → not compact; height under 110 → medium
        assertThat(widgetRenderTier(130, 79)).isEqualTo(RenderTier.MEDIUM)
    }

    @Test fun `tall but narrow is medium not compact`() {
        // height not under 80 → not compact; height under 110 → medium
        assertThat(widgetRenderTier(129, 80)).isEqualTo(RenderTier.MEDIUM)
    }

    @Test fun `a two-by-one tile is medium`() {
        assertThat(widgetRenderTier(150, 70)).isEqualTo(RenderTier.MEDIUM)
    }

    @Test fun `a wide short banner is medium`() {
        assertThat(widgetRenderTier(300, 90)).isEqualTo(RenderTier.MEDIUM)
    }

    @Test fun `the medium height ceiling is exclusive`() {
        // 110 is NOT under 110 → full
        assertThat(widgetRenderTier(300, 110)).isEqualTo(RenderTier.FULL)
    }

    @Test fun `a three-by-two card is full`() {
        assertThat(widgetRenderTier(200, 140)).isEqualTo(RenderTier.FULL)
    }

    @Test fun `degenerate zero size falls back to full`() {
        assertThat(widgetRenderTier(0, 0)).isEqualTo(RenderTier.FULL)
    }

    @Test fun `negative size falls back to full`() {
        assertThat(widgetRenderTier(-5, 200)).isEqualTo(RenderTier.FULL)
    }

    // ── widgetCellDp: orientation-correct cell size ──────────────────────────

    @Test fun `portrait reads min-width by max-height`() {
        // Android reports the portrait extents as MIN_WIDTH x MAX_HEIGHT.
        assertThat(widgetCellDp(false, 110, 250, 70, 180, 180, 110))
            .isEqualTo(110 to 180)
    }

    @Test fun `landscape reads max-width by min-height`() {
        // A landscape launcher (an Echo Show) reports MAX_WIDTH x MIN_HEIGHT;
        // reading the portrait pair there leaves the card narrow on a wide cell.
        assertThat(widgetCellDp(true, 110, 250, 70, 180, 180, 110))
            .isEqualTo(250 to 70)
    }

    @Test fun `portrait falls back to defaults when options are zero`() {
        assertThat(widgetCellDp(false, 0, 0, 0, 0, 180, 110))
            .isEqualTo(180 to 110)
    }

    @Test fun `landscape falls back to defaults when options are zero`() {
        assertThat(widgetCellDp(true, 0, 0, 0, 0, 180, 110))
            .isEqualTo(180 to 110)
    }
}
