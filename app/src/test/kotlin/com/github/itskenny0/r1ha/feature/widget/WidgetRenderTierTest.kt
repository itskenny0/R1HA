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
}
