package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.core.prefs.UiOptions
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicDeckTest {

    // ── effectiveDeckLayout: the AUTO -> mode resolution ────────────────────

    @Test fun `forced modes win on every tier`() {
        for (tier in WindowTier.entries) {
            assertThat(effectiveDeckLayout(DeckLayoutMode.FULLSCREEN, tier))
                .isEqualTo(DeckLayout.FULLSCREEN)
            assertThat(effectiveDeckLayout(DeckLayoutMode.DYNAMIC, tier))
                .isEqualTo(DeckLayout.DYNAMIC)
        }
    }

    @Test fun `AUTO keeps the small tiers full-viewport`() {
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.R1))
            .isEqualTo(DeckLayout.FULLSCREEN)
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.COMPACT))
            .isEqualTo(DeckLayout.FULLSCREEN)
    }

    @Test fun `AUTO goes dynamic from medium up`() {
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.MEDIUM))
            .isEqualTo(DeckLayout.DYNAMIC)
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.EXPANDED))
            .isEqualTo(DeckLayout.DYNAMIC)
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.EXTRA_LARGE))
            .isEqualTo(DeckLayout.DYNAMIC)
    }

    // ── DYNAMIC_VALUE_BAR_HEIGHT_DP: the wrap-mode value-bar band ────────────
    // Entity items wrap to content now (the old fixed-height-item helper
    // clipped climate/light controls and is gone); the one concrete height
    // left in the policy is the value bar's, since the tape meter cannot
    // answer an intrinsic measurement and must be told a size.

    @Test fun `wrap-mode value bar leaves room for five labelled ticks`() {
        // Five tick labels (a climate scale's 35/27/20/12/4) with their tap
        // padding need roughly 24 dp each to stay distinct touch targets.
        assertThat(DYNAMIC_VALUE_BAR_HEIGHT_DP).isAtLeast(5 * 24)
    }

    @Test fun `wrap-mode value bar stays under the old clipping card cap`() {
        // The 220 dp flat item cap was the bug (it clipped rich cards); the
        // meter band must come in clearly below it so a meter-dominated card
        // is shorter than the layout this replaces, not taller.
        assertThat(DYNAMIC_VALUE_BAR_HEIGHT_DP).isLessThan(220)
    }

    // ── dynamicCenterPaddingPx: symmetric centre-reach padding ──────────────

    @Test fun `unmeasured end card pads nothing`() {
        assertThat(dynamicCenterPaddingPx(bandHeightPx = 800, endItemHeightPx = null))
            .isEqualTo(0)
    }

    @Test fun `short end card gets half the missing band as centre padding`() {
        // 800 px band, 120 px end card: centring it needs (800 - 120) / 2 = 340
        // px of free space on its outer side, no more (half the old start-snap
        // tail, the rest read as the neighbour peeking).
        assertThat(dynamicCenterPaddingPx(bandHeightPx = 800, endItemHeightPx = 120))
            .isEqualTo(340)
    }

    @Test fun `end card filling the band needs no padding`() {
        assertThat(dynamicCenterPaddingPx(bandHeightPx = 800, endItemHeightPx = 800))
            .isEqualTo(0)
        // Taller than the band (cap rounding, oversized content): still 0.
        assertThat(dynamicCenterPaddingPx(bandHeightPx = 800, endItemHeightPx = 900))
            .isEqualTo(0)
    }

    @Test fun `centre padding never exceeds one band even for a zero-height item`() {
        // Half of an 800 px band; far below the one-band clamp.
        assertThat(dynamicCenterPaddingPx(bandHeightPx = 800, endItemHeightPx = 0))
            .isEqualTo(400)
    }

    // ── dynamicFocusedIndex: nearest item CENTRE to the band centre ─────────

    @Test fun `settled item centred in the band is focused`() {
        // 800 px band (centre 400). Item 3 spans 300..500 (centre 400): dead
        // on the band centre.
        val visible = listOf(
            DynamicVisibleItem(index = 3, offsetPx = 300, sizePx = 200),
            DynamicVisibleItem(index = 4, offsetPx = 520, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800)).isEqualTo(3)
    }

    @Test fun `nearest centre wins over an earlier mostly-scrolled-out card`() {
        // 800 px band (centre 400). Item 1's centre is well above the band
        // centre, item 2's centre (440) is nearest it: item 2 is the card.
        val visible = listOf(
            DynamicVisibleItem(index = 1, offsetPx = -300, sizePx = 200),
            DynamicVisibleItem(index = 2, offsetPx = 340, sizePx = 200),
            DynamicVisibleItem(index = 3, offsetPx = 560, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800)).isEqualTo(2)
    }

    @Test fun `ties break toward the earlier index`() {
        // 800 px band (centre 400). Item 5 centre 340 and item 6 centre 460 are
        // both 60 px from the centre: the earlier index keeps focus.
        val visible = listOf(
            DynamicVisibleItem(index = 5, offsetPx = 240, sizePx = 200),
            DynamicVisibleItem(index = 6, offsetPx = 360, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800)).isEqualTo(5)
    }

    @Test fun `empty visible list falls back to zero`() {
        assertThat(dynamicFocusedIndex(emptyList(), bandHeightPx = 800)).isEqualTo(0)
    }

    // ── dynamicSnapTarget: programmatic snap-index math ─────────────────────

    @Test fun `targets clamp to the finite deck`() {
        assertThat(dynamicSnapTarget(targetIndex = -2, itemCount = 5)).isEqualTo(0)
        assertThat(dynamicSnapTarget(targetIndex = 3, itemCount = 5)).isEqualTo(3)
        assertThat(dynamicSnapTarget(targetIndex = 9, itemCount = 5)).isEqualTo(4)
    }

    @Test fun `empty deck pins to zero`() {
        assertThat(dynamicSnapTarget(targetIndex = 7, itemCount = 0)).isEqualTo(0)
    }

    // ── DeckLayoutMode storage codec + default ──────────────────────────────

    @Test fun `stored names round-trip`() {
        for (mode in DeckLayoutMode.entries) {
            assertThat(DeckLayoutMode.fromStored(mode.name)).isEqualTo(mode)
        }
    }

    @Test fun `absent and unknown stored values decode as AUTO`() {
        assertThat(DeckLayoutMode.fromStored(null)).isEqualTo(DeckLayoutMode.AUTO)
        assertThat(DeckLayoutMode.fromStored("")).isEqualTo(DeckLayoutMode.AUTO)
        assertThat(DeckLayoutMode.fromStored("HALF_HEIGHT")).isEqualTo(DeckLayoutMode.AUTO)
    }

    @Test fun `setting defaults to AUTO`() {
        assertThat(UiOptions().deckLayoutMode).isEqualTo(DeckLayoutMode.AUTO)
    }
}
