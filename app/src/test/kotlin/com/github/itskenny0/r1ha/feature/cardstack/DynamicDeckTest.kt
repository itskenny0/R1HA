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

    // ── second-card reachability: the focus bug, as geometry ────────────────
    // "I can never focus the second card unless it's huge": item 1's centre
    // snap line was above the highest offset it could ever occupy, so a fling
    // could not settle there. These pin the ceiling math and the cure.

    @Test fun `second card is unreachable with no top padding`() {
        // 800 px band, two short 120 px cards, 24 px gap, ZERO top padding (the
        // shipped state). Item 1's centre line is (800 - 120) / 2 = 340, but its
        // ceiling is 0 + 120 + 24 = 144: the centre sits 196 px below anything
        // item 1 can reach, so it can never be focused.
        assertThat(
            dynamicSecondItemCentreReachable(
                bandHeightPx = 800,
                firstItemHeightPx = 120,
                secondItemHeightPx = 120,
                interCardGapPx = 24,
                topPaddingPx = 0,
            ),
        ).isFalse()
    }

    @Test fun `a tall first or second card was the only thing that reached`() {
        // Why "unless it's huge" was the exception: a band-filling second card
        // centres at (800 - 800) / 2 = 0, already at or below its ceiling, so it
        // reached even with no top padding. This is the lucky case, not the fix.
        assertThat(
            dynamicSecondItemCentreReachable(
                bandHeightPx = 800,
                firstItemHeightPx = 120,
                secondItemHeightPx = 800,
                interCardGapPx = 24,
                topPaddingPx = 0,
            ),
        ).isTrue()
    }

    @Test fun `mirroring the bottom padding on top always reaches the second card`() {
        // The fix: a top pad of dynamicCenterPaddingPx(band, firstHeight) raises
        // item 1's ceiling past its centre line for ANY card sizes. Sweep a
        // range of short/tall first and second cards: every combination reaches.
        val band = 800
        val gap = 24
        for (first in listOf(0, 60, 120, 300, 500)) {
            for (second in listOf(0, 60, 120, 300, 500, 800)) {
                val topPad = dynamicCenterPaddingPx(bandHeightPx = band, endItemHeightPx = first)
                assertThat(
                    dynamicSecondItemCentreReachable(
                        bandHeightPx = band,
                        firstItemHeightPx = first,
                        secondItemHeightPx = second,
                        interCardGapPx = gap,
                        topPaddingPx = topPad,
                    ),
                ).isTrue()
            }
        }
    }

    @Test fun `item one ceiling rises by exactly the top padding`() {
        // The ceiling is topPad + firstHeight + gap: the top padding is the
        // load-bearing term, the slack above the first card that lets the second
        // scroll down to its centre.
        assertThat(dynamicSecondItemMaxStartPx(topPaddingPx = 0, firstItemHeightPx = 120, interCardGapPx = 24))
            .isEqualTo(144)
        assertThat(dynamicSecondItemMaxStartPx(topPaddingPx = 340, firstItemHeightPx = 120, interCardGapPx = 24))
            .isEqualTo(484)
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

    @Test fun `item zero flush at the band top is focused`() {
        // Item 0 snaps TOP-aligned (start at 0), so at rest its start is 0 and
        // its CENTRE is far above the band centre. The snap-line-aware focus
        // must still pick it (the old nearest-centre rule would have wrongly
        // jumped to item 1). 800 px band: item 0 start 0 (snap 0, distance 0),
        // item 1 start 220 with snap line (800-200)/2 = 300 (distance 80).
        val visible = listOf(
            DynamicVisibleItem(index = 0, offsetPx = 0, sizePx = 200),
            DynamicVisibleItem(index = 1, offsetPx = 220, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800)).isEqualTo(0)
    }

    @Test fun `second card centred is reachable as the focused card`() {
        // The regression: with item 0 pinned to the top, the SECOND card must
        // be a focusable rest. Here item 1 sits on its centre snap line
        // (start (800-200)/2 = 300) while item 0 has scrolled partly off the
        // top (start -80, away from its own 0 line): item 1 wins.
        val visible = listOf(
            DynamicVisibleItem(index = 0, offsetPx = -80, sizePx = 200),
            DynamicVisibleItem(index = 1, offsetPx = 300, sizePx = 200),
            DynamicVisibleItem(index = 2, offsetPx = 520, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800)).isEqualTo(1)
    }

    // ── dynamicSnapStartPx: the per-item snap line (the shared rule) ─────────

    @Test fun `item zero snaps flush to the band top`() {
        // Item 0 always rests at offset 0 regardless of its height: the user
        // wants the deck to open with the first card pinned under the chrome.
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 200, bandHeightPx = 800))
            .isEqualTo(0)
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 600, bandHeightPx = 800))
            .isEqualTo(0)
    }

    @Test fun `non-first items snap to the band centre`() {
        // (band - itemHeight) / 2: a 200 px card in an 800 px band centres at
        // start 300; the same arithmetic SnapPosition.Center uses.
        assertThat(dynamicSnapStartPx(itemIndex = 1, itemSizePx = 200, bandHeightPx = 800))
            .isEqualTo(300)
        assertThat(dynamicSnapStartPx(itemIndex = 5, itemSizePx = 400, bandHeightPx = 800))
            .isEqualTo(200)
    }

    @Test fun `every item past the first has a DISTINCT centre snap line`() {
        // The fix's core property: with item 0 top-anchored (line 0) and items
        // 1..n centred (line 300 for equal-height cards), no two adjacent cards
        // share a rest, so the fling can settle on each one (especially item 1,
        // which the uniform-centre regression could not reach).
        val band = 800
        val size = 200
        val item0 = dynamicSnapStartPx(itemIndex = 0, itemSizePx = size, bandHeightPx = band)
        val item1 = dynamicSnapStartPx(itemIndex = 1, itemSizePx = size, bandHeightPx = band)
        assertThat(item0).isNotEqualTo(item1)
    }

    @Test fun `a negative index is treated as the first item`() {
        // Defensive: a stray negative index still resolves to the top line.
        assertThat(dynamicSnapStartPx(itemIndex = -3, itemSizePx = 200, bandHeightPx = 800))
            .isEqualTo(0)
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
