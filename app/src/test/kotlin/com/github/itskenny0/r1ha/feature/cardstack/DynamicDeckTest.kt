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

    // ── dynamicSnapStartPx: the per-item TOP-ALIGN snap line (shared rule) ───
    // Cards 0..n-2 snap flush at the band TOP (offset 0); the last card
    // BOTTOM-aligns (start band - height) so its bottom meets the band bottom.
    // No two adjacent non-last cards share a rest (they all top-align, but each
    // is only reachable at the top by scrolling the earlier ones off), and the
    // last card's bottom rest is distinct from the top.

    @Test fun `first card snaps flush to the band top`() {
        // Index 0 always rests at offset 0 regardless of its height: the deck
        // opens with the first card pinned under the chrome.
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 200, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 600, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
    }

    @Test fun `mid cards snap flush to the band top`() {
        // Every non-last card top-aligns (offset 0): it reaches the top by
        // scrolling the earlier cards off, no content padding needed.
        assertThat(dynamicSnapStartPx(itemIndex = 1, itemSizePx = 200, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
        assertThat(dynamicSnapStartPx(itemIndex = 3, itemSizePx = 540, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
    }

    @Test fun `the last card bottom-aligns flush with the band bottom`() {
        // band - itemHeight: a 200 px last card in an 800 px band starts at 600
        // so its bottom (600 + 200 = 800) meets the band bottom.
        assertThat(dynamicSnapStartPx(itemIndex = 4, itemSizePx = 200, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(600)
        // A taller last card sits higher: 540 px card starts at 800 - 540 = 260.
        assertThat(dynamicSnapStartPx(itemIndex = 4, itemSizePx = 540, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(260)
    }

    @Test fun `a last card taller than the band clamps to the top`() {
        // band - height is negative for a band-taller card; the clamp keeps it
        // at 0 (it just top-aligns and fills/overflows the band), never above.
        assertThat(dynamicSnapStartPx(itemIndex = 4, itemSizePx = 800, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
        assertThat(dynamicSnapStartPx(itemIndex = 4, itemSizePx = 1000, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
    }

    @Test fun `a single-card deck top-aligns the lone card`() {
        // The one card is both first (index 0) and last (index 0 == count - 1);
        // the top-align branch wins so it sits flush at the top, not floated to
        // a bottom-align with a void above it.
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 200, bandHeightPx = 800, itemCount = 1))
            .isEqualTo(0)
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = 600, bandHeightPx = 800, itemCount = 1))
            .isEqualTo(0)
    }

    @Test fun `the last card's bottom line is distinct from the top line`() {
        // Top-aligned cards rest at 0; the last card rests at band - height. For
        // any last card shorter than the band these differ, so the bottom rest
        // is its own reachable snap target (not collapsed onto the top).
        val band = 800
        for (size in listOf(60, 200, 540, 799)) {
            val top = dynamicSnapStartPx(itemIndex = 0, itemSizePx = size, bandHeightPx = band, itemCount = 5)
            val lastBottom = dynamicSnapStartPx(itemIndex = 4, itemSizePx = size, bandHeightPx = band, itemCount = 5)
            assertThat(top).isEqualTo(0)
            assertThat(lastBottom).isGreaterThan(0)
        }
    }

    @Test fun `a negative index is treated as the first item`() {
        // Defensive: a stray negative index still resolves to the top line.
        assertThat(dynamicSnapStartPx(itemIndex = -3, itemSizePx = 200, bandHeightPx = 800, itemCount = 5))
            .isEqualTo(0)
    }

    // ── snap provider / focus FRAME AGREEMENT ───────────────────────────────
    // The single invariant the whole deck rests on: the line the provider lands
    // a card on (beforeContentPadding + providerOffset) must equal the line the
    // focus math picks the card out by (dynamicSnapStartPx in the FULL band). If
    // these ever diverge the deck snaps to one line and reads focus off another.

    @Test fun `provider lands a top card flush, the last card bottom-aligned`() {
        // No content padding (top-align needs none): the provider offset IS the
        // snap line. A mid card returns 0 (flush top); the last card returns its
        // bottom-align line.
        assertThat(
            dynamicSnapProviderOffsetPx(
                itemIndex = 1,
                itemSizePx = 536,
                layoutSizePx = 2043,
                beforeContentPaddingPx = 0,
                itemCount = 5,
            ),
        ).isEqualTo(0)
        assertThat(
            dynamicSnapProviderOffsetPx(
                itemIndex = 4,
                itemSizePx = 536,
                layoutSizePx = 2043,
                beforeContentPaddingPx = 0,
                itemCount = 5,
            ),
        ).isEqualTo(2043 - 536)
    }

    @Test fun `provider subtracts any content padding so the landed norm is the snap line`() {
        // Even though top-align applies no padding, the frame correction must
        // hold for whatever beforeContentPadding the framework reports: a mid
        // card's line is 0, returned content-relative as -before (lands at
        // before + (-before) = 0).
        assertThat(
            dynamicSnapProviderOffsetPx(
                itemIndex = 1,
                itemSizePx = 536,
                layoutSizePx = 2043,
                beforeContentPaddingPx = 120,
                itemCount = 5,
            ),
        ).isEqualTo(-120)
    }

    @Test fun `provider and focus frame agree across a wide sweep`() {
        // The core invariant, swept: for any band, card size, padding, index and
        // deck size, the landed norm (beforePad + providerOffset) equals the
        // focus snap line (dynamicSnapStartPx in the FULL band). Covers the first
        // card, mid cards and the LAST card (bottom-align) of each deck size.
        for (band in listOf(400, 800, 1000, 2043, 3000)) {
            for (size in listOf(0, 60, 200, 536, 800, band)) {
                for (before in listOf(0, 1, 120, 400, band)) {
                    for (count in listOf(1, 2, 3, 8, 51)) {
                        for (index in listOf(0, 1, 2, 7, 50, count - 1)) {
                            val providerOffset =
                                dynamicSnapProviderOffsetPx(index, size, band, before, count)
                            val landedNorm = before + providerOffset
                            val focusLine = dynamicSnapStartPx(index, size, band, count)
                            assertThat(landedNorm).isEqualTo(focusLine)
                        }
                    }
                }
            }
        }
    }

    @Test fun `a snapped card reads as focused in the same frame`() {
        // The agreement, end to end: after the provider lands a card, its
        // normalised offset equals the focus math's snap line for it, so the
        // focus picks it with distance 0. The LAST card in a 2043 band, 536 px:
        // provider offset = bottom line (2043 - 536 = 1507); landed norm = that;
        // focus snap line = the same; distance 0.
        val layout = 2043
        val before = 0
        val sz = 536
        val providerOffset = dynamicSnapProviderOffsetPx(4, sz, layout, before, itemCount = 5)
        val landedNorm = before + providerOffset
        val focusLine = dynamicSnapStartPx(itemIndex = 4, itemSizePx = sz, bandHeightPx = layout, itemCount = 5)
        assertThat(landedNorm).isEqualTo(focusLine)
        assertThat(landedNorm).isEqualTo(1507)
    }

    // ── dynamicFocusedIndex: nearest item to its OWN snap line ──────────────

    @Test fun `first card flush at the top is focused at the raw clamp`() {
        // The raw top clamp (scroll offset 0) puts card 0's start at 0, on its
        // own top line (distance 0). Card 1 has not yet risen to the top (start
        // 220, also a top-aligner so its line is 0, distance 220). Card 0 wins:
        // the deck opens focused on the first card.
        val visible = listOf(
            DynamicVisibleItem(index = 0, offsetPx = 0, sizePx = 200),
            DynamicVisibleItem(index = 1, offsetPx = 220, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800, itemCount = 5)).isEqualTo(0)
    }

    @Test fun `a mid card on its top line wins`() {
        // Scrolled one down: card 0 has rolled partly off the top (start -80,
        // away from its 0 line), card 1 has risen flush to the top (start 0, on
        // its 0 line). Card 1 is focused: the natural next-card-up behaviour.
        val visible = listOf(
            DynamicVisibleItem(index = 0, offsetPx = -80, sizePx = 200),
            DynamicVisibleItem(index = 1, offsetPx = 0, sizePx = 200),
            DynamicVisibleItem(index = 2, offsetPx = 220, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800, itemCount = 5)).isEqualTo(1)
    }

    @Test fun `the last card on its bottom line wins when scrolled to the bottom`() {
        // Scrolled to the bottom: the last card (index 4) sits on its bottom
        // line (800 - 200 = 600, distance 0), the card before it is above (start
        // 380, a top-aligner so line 0, distance 380). The last card is focused
        // even though its start is far from the top: distance-to-OWN-line picks
        // it, mirroring the bottom-align snap.
        val visible = listOf(
            DynamicVisibleItem(index = 3, offsetPx = 380, sizePx = 200),
            DynamicVisibleItem(index = 4, offsetPx = 600, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800, itemCount = 5)).isEqualTo(4)
    }

    @Test fun `natural order, scroll up focuses first then scroll one down focuses second`() {
        // The exact bug the user hit (centre-snap inverted which card was at the
        // top). Top-align makes the order natural: at the top the first card is
        // focused; one notch down the second card rises to the top and takes
        // focus. Same two cards, two scroll positions, focus tracks the card at
        // the top.
        val scrolledToTop = listOf(
            DynamicVisibleItem(index = 0, offsetPx = 0, sizePx = 240),
            DynamicVisibleItem(index = 1, offsetPx = 270, sizePx = 240),
            DynamicVisibleItem(index = 2, offsetPx = 540, sizePx = 240),
        )
        assertThat(dynamicFocusedIndex(scrolledToTop, bandHeightPx = 800, itemCount = 5)).isEqualTo(0)

        val scrolledOneDown = listOf(
            DynamicVisibleItem(index = 0, offsetPx = -270, sizePx = 240),
            DynamicVisibleItem(index = 1, offsetPx = 0, sizePx = 240),
            DynamicVisibleItem(index = 2, offsetPx = 270, sizePx = 240),
        )
        assertThat(dynamicFocusedIndex(scrolledOneDown, bandHeightPx = 800, itemCount = 5)).isEqualTo(1)
    }

    @Test fun `ties break toward the earlier index`() {
        // Two cards equidistant from their own lines: both are top-aligners
        // (line 0); card 5 at start 40 and card 6 at start -40 are each 40 px off
        // their 0 line. The earlier index keeps focus.
        val visible = listOf(
            DynamicVisibleItem(index = 5, offsetPx = 40, sizePx = 200),
            DynamicVisibleItem(index = 6, offsetPx = -40, sizePx = 200),
        )
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800, itemCount = 8)).isEqualTo(5)
    }

    @Test fun `empty visible list falls back to zero`() {
        assertThat(dynamicFocusedIndex(emptyList(), bandHeightPx = 800, itemCount = 0)).isEqualTo(0)
    }

    @Test fun `a lone card on screen is focused as both first and last`() {
        // Single-card deck (count 1): the lone card's snap line is the top (0,
        // first-and-last resolves to top-align), so at its flush-top rest it is
        // focused.
        val visible = listOf(DynamicVisibleItem(index = 0, offsetPx = 0, sizePx = 200))
        assertThat(dynamicFocusedIndex(visible, bandHeightPx = 800, itemCount = 1)).isEqualTo(0)
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
