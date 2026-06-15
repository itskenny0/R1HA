package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.core.prefs.UiOptions
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DynamicDeckTest {

    // ── effectiveDeckLayout: the AUTO -> mode resolution ────────────────────

    // A roomy phone's shortest side (well above the 600px floor); the dp tier
    // can't tell a real phone from a tiny low-density panel, so the raw px decide.
    private val ROOMY_PX = 1080

    @Test fun `forced modes win on every tier and size`() {
        for (tier in WindowTier.entries) {
            for (px in listOf(240, 480, 600, 1080)) {
                assertThat(effectiveDeckLayout(DeckLayoutMode.FULLSCREEN, tier, px))
                    .isEqualTo(DeckLayout.FULLSCREEN)
                assertThat(effectiveDeckLayout(DeckLayoutMode.HALF_HEIGHT, tier, px))
                    .isEqualTo(DeckLayout.HALF_HEIGHT)
                assertThat(effectiveDeckLayout(DeckLayoutMode.DYNAMIC, tier, px))
                    .isEqualTo(DeckLayout.DYNAMIC)
            }
        }
    }

    @Test fun `AUTO keeps the R1 full-viewport`() {
        // The R1's sub-compact panel is too small for a content-sized block.
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.R1, shortestSidePx = 240))
            .isEqualTo(DeckLayout.FULLSCREEN)
    }

    @Test fun `AUTO keeps a small low-density device full-viewport even on a COMPACT tier`() {
        // The "dumbphone" case: COMPACT dp width but only 480px on its shortest
        // side, below the 600px floor, so it should be treated like the R1 and stay
        // full-viewport rather than getting the cramped dynamic list.
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.COMPACT, shortestSidePx = 480))
            .isEqualTo(DeckLayout.FULLSCREEN)
        // Right at the floor is still small (the floor is the minimum to qualify as
        // roomy, mirroring the peek rule's `>=`).
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.COMPACT, shortestSidePx = 599))
            .isEqualTo(DeckLayout.FULLSCREEN)
    }

    @Test fun `AUTO is dynamic on a roomy phone or larger`() {
        // An ordinary phone (~720px+ shortest side) and every larger tier get the
        // matured dynamic list; the half-height peek is the explicit HALF_HEIGHT mode.
        for (tier in listOf(
            WindowTier.COMPACT,
            WindowTier.MEDIUM,
            WindowTier.EXPANDED,
            WindowTier.EXTRA_LARGE,
        )) {
            assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, tier, ROOMY_PX))
                .isEqualTo(DeckLayout.DYNAMIC)
        }
        // Exactly at the floor qualifies as roomy.
        assertThat(effectiveDeckLayout(DeckLayoutMode.AUTO, WindowTier.COMPACT, shortestSidePx = 600))
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

    // ── dynamicMinBottomPaddingPx: the second-to-last reachability pad ───────
    // The bottom-aligned last card blocks the scroll before the second-to-last
    // card's top can reach the chrome when the two end cards fit together, so a
    // fling skips it. The pad is the extra scroll range that frees it.

    @Test fun `bottom pad is zero until both end heights are measured`() {
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = null, secondToLastCardHeightPx = 120, gapPx = 24))
            .isEqualTo(0)
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 120, secondToLastCardHeightPx = null, gapPx = 24))
            .isEqualTo(0)
    }

    @Test fun `bottom pad is the deficit when the two end cards fit together`() {
        // 800 band, last 200, second-to-last 120, gap 24: with the second-to-last
        // at the top the last card's bottom is 200 + 24 + 120 = 344 down, leaving
        // 800 - 344 = 456 of scroll the fling can't reach without the pad.
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 200, secondToLastCardHeightPx = 120, gapPx = 24))
            .isEqualTo(456)
    }

    @Test fun `bottom pad is zero when the end cards do not fit together`() {
        // Tall end cards (their sum + gap exceeds the band): the second-to-last
        // already reaches the top by scrolling the last card below the fold, so the
        // deficit is negative and no pad is needed.
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 500, secondToLastCardHeightPx = 400, gapPx = 24))
            .isEqualTo(0)
        // Exactly filling the band (sum + gap == band) is also no help needed.
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 400, secondToLastCardHeightPx = 376, gapPx = 24))
            .isEqualTo(0)
    }

    @Test fun `bottom pad clamps into the band`() {
        // Two zero-height end cards, no gap: deficit is the whole band, clamped to
        // it (never larger, never negative).
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 0, secondToLastCardHeightPx = 0, gapPx = 0))
            .isEqualTo(800)
        assertThat(dynamicMinBottomPaddingPx(800, lastCardHeightPx = 0, secondToLastCardHeightPx = 0, gapPx = 10_000))
            .isEqualTo(0)
    }

    @Test fun `the second-to-last is the binding case for the pad`() {
        // A pad sized for the second-to-last frees every earlier card too: a card
        // higher up needs LESS extra scroll to reach the top, so its own deficit is
        // smaller. Model the third-to-last as "second-to-last of a shorter tail"
        // and assert its deficit never exceeds the second-to-last's.
        val band = 1000
        val gap = 24
        val last = 150
        val secondToLast = 130
        val thirdToLast = 140
        val padForSecond = dynamicMinBottomPaddingPx(band, last, secondToLast, gap)
        // Third-to-last reaching the top puts (secondToLast + gap + last) below it
        // already on-screen; its extra deficit beyond that is what a smaller tail
        // would show, which is <= the second-to-last's.
        val deficitForThird = (band - last - gap - secondToLast - gap - thirdToLast).coerceAtLeast(0)
        assertThat(deficitForThird).isAtMost(padForSecond)
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

    // ── the STUCK second-to-last card: focus stickiness ─────────────────────
    // The deck bottom-aligns the last card; when the last cards fit together in
    // the band the bottom scroll clamp is reached before the second-to-last
    // card's top can rise to the chrome, so that card can never sit on its
    // top-snap line. The scroll-derived focus always hands focus to the
    // bottom-aligned last card there (distance 0), so without stickiness the
    // second-to-last is unreachable and SKIPPED by the pip (4/6 -> 6/6).

    @Test fun `at the bottom clamp the stuck second-to-last is never the scroll focus`() {
        // The bug's geometry, 6-card deck (indices 0..5), 800 px band. Scrolled
        // hard to the bottom: the last card (5) bottom-aligns (800 - 200 = 600,
        // on its own line, distance 0); the second-to-last (4) sits above it
        // (start 380), a top-aligner so its line is 0, distance 380 and never
        // reachable. The scroll focus is the last card, so the pip would jump
        // 4/6 -> 6/6: index 4 (the 5/6 card) is skipped.
        val atBottomClamp = listOf(
            DynamicVisibleItem(index = 4, offsetPx = 380, sizePx = 200),
            DynamicVisibleItem(index = 5, offsetPx = 600, sizePx = 200),
        )
        val scrollFocus = dynamicFocusedIndex(atBottomClamp, bandHeightPx = 800, itemCount = 6)
        assertThat(scrollFocus).isEqualTo(5)
        // The second-to-last is genuinely stuck: its distance to its own line is
        // large and positive (it can never reach the chrome at this clamp), while
        // the last card rests exactly on its bottom line.
        assertThat(380 - dynamicSnapStartPx(4, 200, 800, 6)).isGreaterThan(0)
        assertThat(600 - dynamicSnapStartPx(5, 200, 800, 6)).isEqualTo(0)
    }

    @Test fun `a sticky target overrides the scroll focus so the stuck card is selectable`() {
        // At the same bottom clamp the scroll-derived focus is the last card (5),
        // but an explicit selection of the stuck second-to-last (4) sticks: the
        // settled focus honours the sticky target, so 5/6 becomes selectable.
        val scrollFocus = 5
        assertThat(resolveSettledFocus(scrollFocus, stickyTarget = 4, itemCount = 6))
            .isEqualTo(4)
        // Selecting the last card explicitly also resolves to it.
        assertThat(resolveSettledFocus(scrollFocus, stickyTarget = 5, itemCount = 6))
            .isEqualTo(5)
    }

    @Test fun `with no sticky target the scroll focus passes through`() {
        // The common case (no explicit selection pending): the scroll-derived
        // focus is used unchanged.
        assertThat(resolveSettledFocus(scrollFocus = 3, stickyTarget = null, itemCount = 6))
            .isEqualTo(3)
        assertThat(resolveSettledFocus(scrollFocus = 5, stickyTarget = null, itemCount = 6))
            .isEqualTo(5)
    }

    @Test fun `resolveSettledFocus clamps both inputs to the live deck`() {
        // Defensive: a stale sticky target or scroll focus past the deck end (a
        // deck shrank under the selection) clamps to the last index, never out of
        // range; an empty deck pins to 0.
        assertThat(resolveSettledFocus(scrollFocus = 0, stickyTarget = 9, itemCount = 6))
            .isEqualTo(5)
        assertThat(resolveSettledFocus(scrollFocus = 0, stickyTarget = -1, itemCount = 6))
            .isEqualTo(0)
        assertThat(resolveSettledFocus(scrollFocus = 9, stickyTarget = null, itemCount = 6))
            .isEqualTo(5)
        assertThat(resolveSettledFocus(scrollFocus = 4, stickyTarget = 2, itemCount = 0))
            .isEqualTo(0)
    }

    @Test fun `wheel stepping visits every index in order with no skip`() {
        // Stepping the wheel one card at a time off the SETTLED focus (which now
        // reflects a stuck card) must reach each index in turn. From the
        // second-to-last (n-2 = 4) a +1 step reaches the last (5); from n-3 (3) a
        // +1 step reaches n-2 (4): 4/6 -> 5/6 -> 6/6, no skip. Stepping back up
        // from the last returns to the second-to-last, then earlier.
        val n = 6
        assertThat(dynamicSnapTarget(targetIndex = 3 + 1, itemCount = n)).isEqualTo(4)
        assertThat(dynamicSnapTarget(targetIndex = 4 + 1, itemCount = n)).isEqualTo(5)
        assertThat(dynamicSnapTarget(targetIndex = 5 - 1, itemCount = n)).isEqualTo(4)
        assertThat(dynamicSnapTarget(targetIndex = 4 - 1, itemCount = n)).isEqualTo(3)
        // The whole down sweep 0..n-1 with the focus carried forward as the
        // sticky target each step lands on each index exactly once, in order.
        var focus = 0
        val visited = mutableListOf(focus)
        repeat(n - 1) {
            focus = dynamicSnapTarget(focus + 1, n)
            // The explicit step always sticks (a stuck card included), so the
            // next step is computed off it.
            focus = resolveSettledFocus(scrollFocus = n - 1, stickyTarget = focus, itemCount = n)
            visited += focus
        }
        assertThat(visited).containsExactly(0, 1, 2, 3, 4, 5).inOrder()
    }

    @Test fun `a cleared sticky target lets the scroll focus take over after a hand drag`() {
        // After the user hand-drags (the deck clears the sticky), the settled
        // focus follows the scroll again: a drag that rests at the bottom clamp
        // focuses the genuinely-resting last card, not the previously-selected
        // stuck second-to-last.
        val scrollFocusAtClamp = 5
        // Was sticky on the stuck card; the hand drag cleared it to null.
        assertThat(resolveSettledFocus(scrollFocusAtClamp, stickyTarget = null, itemCount = 6))
            .isEqualTo(5)
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
        assertThat(DeckLayoutMode.fromStored("PEEK")).isEqualTo(DeckLayoutMode.AUTO)
    }

    @Test fun `setting defaults to AUTO`() {
        assertThat(UiOptions().deckLayoutMode).isEqualTo(DeckLayoutMode.AUTO)
    }
}
