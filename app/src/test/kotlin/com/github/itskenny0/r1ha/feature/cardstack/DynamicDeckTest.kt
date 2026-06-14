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

    // ── snap provider / focus frame agreement ───────────────────────────────
    // The inconsistency bug: the provider centred inside the PADDED band while
    // the focus math used the FULL viewport, so they disagreed on every card's
    // rest line and the deck snapped erratically (and item 0 snapped to the
    // floated content-area top, not the chrome). These pin the shared frame.

    @Test fun `provider lands item zero flush at the true top, not floated`() {
        // Full 2043 viewport, 750 top pad. Item 0's full-band line is 0 (top);
        // the provider returns it content-relative, so -750: the framework lands
        // it at 750 + (-750) = 0, flush at the chrome. The OLD provider returned
        // 0 here, landing item 0 at +750 (floated a whole pad down).
        assertThat(
            dynamicSnapProviderOffsetPx(
                itemIndex = 0,
                itemSizePx = 542,
                layoutSizePx = 2043,
                beforeContentPaddingPx = 750,
            ),
        ).isEqualTo(-750)
    }

    @Test fun `provider centres later items in the FULL viewport`() {
        // Item 1, 536 px card, 2043 viewport, 750 top pad: full-band centre is
        // (2043 - 536) / 2 = 753, returned content-relative as 753 - 750 = 3, so
        // it lands at 750 + 3 = 753 -- the full-band centre the focus math also
        // uses. The old padded-band centre ((529 - 536)/2 = -3) disagreed.
        assertThat(
            dynamicSnapProviderOffsetPx(
                itemIndex = 1,
                itemSizePx = 536,
                layoutSizePx = 2043,
                beforeContentPaddingPx = 750,
            ),
        ).isEqualTo(3)
    }

    @Test fun `provider and focus frame agree across a wide sweep`() {
        // The core invariant, swept: for any band, card size, padding and index,
        // the landed norm (beforePad + providerOffset) equals the focus snap line
        // (dynamicSnapStartPx in the FULL band). If these ever diverged the deck
        // would snap to one line and read focus off another (the inconsistency
        // bug). Covers item 0 (flush-top line) and items 1..n (centre line).
        for (band in listOf(400, 800, 1000, 2043, 3000)) {
            for (size in listOf(0, 60, 200, 536, 800, band)) {
                for (before in listOf(0, 1, 181, 400, 750, band)) {
                    for (index in listOf(0, 1, 2, 7, 50)) {
                        val providerOffset =
                            dynamicSnapProviderOffsetPx(index, size, band, before)
                        val landedNorm = before + providerOffset
                        val focusLine = dynamicSnapStartPx(index, size, band)
                        assertThat(landedNorm).isEqualTo(focusLine)
                    }
                }
            }
        }
    }

    @Test fun `card zero lands flush at norm zero for any padding`() {
        // Item 0's landed norm is ALWAYS 0 (flush at the chrome), never floated by
        // the pad, whatever the top padding is. This is the property the flush
        // effect leans on.
        for (before in listOf(0, 1, 100, 181, 750, 5000)) {
            for (size in listOf(0, 120, 542, 2000)) {
                val landedNorm = before + dynamicSnapProviderOffsetPx(
                    itemIndex = 0,
                    itemSizePx = size,
                    layoutSizePx = 2043,
                    beforeContentPaddingPx = before,
                )
                assertThat(landedNorm).isEqualTo(0)
            }
        }
    }

    @Test fun `a snapped card reads as focused in the same frame`() {
        // The agreement, end to end: after the provider lands a card, its
        // normalised offset equals the focus math's snap line for it, so the
        // focus picks it with distance 0. Item 1 in a 2043 viewport, 750 pad:
        // provider offset 3 -> landed norm = beforePad + offset = 753; focus
        // snap line dynamicSnapStartPx(1, 536, 2043) = 753; distance 0.
        val layout = 2043
        val before = 750
        val sz = 536
        val providerOffset = dynamicSnapProviderOffsetPx(1, sz, layout, before)
        val landedNorm = before + providerOffset
        val focusLine = dynamicSnapStartPx(itemIndex = 1, itemSizePx = sz, bandHeightPx = layout)
        assertThat(landedNorm).isEqualTo(focusLine)
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

    // ── dynamicMinTopPaddingPx: the MINIMAL top pad (the overscroll fix) ─────
    // The old top pad mirrored the bottom: (band - firstHeight)/2. That is far
    // more scrollable empty space above card 0 than needed, so the user could
    // drag card 0 into the middle and it sprang back (awkward overscroll). The
    // minimal pad is the LEAST slack that still lets the second card reach its
    // centre line: P_min = (band - secondHeight)/2 - firstHeight - gap, floored
    // at 0.

    @Test fun `min top pad is zero until both head cards are measured`() {
        // Either height null = not composed yet / head just changed: no pad, so
        // card 0 is never speculatively floated.
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 2043, firstCardHeightPx = null, secondCardHeightPx = 536, gapPx = 30),
        ).isEqualTo(0)
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 2043, firstCardHeightPx = 542, secondCardHeightPx = null, gapPx = 30),
        ).isEqualTo(0)
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 2043, firstCardHeightPx = null, secondCardHeightPx = null, gapPx = 30),
        ).isEqualTo(0)
    }

    @Test fun `min top pad matches the worked on-device example`() {
        // The real log: band 2043, card0 542, card1 536, gap 30.
        // P_min = (2043 - 536)/2 - 542 - 30 = 753 - 572 = 181.
        assertThat(
            dynamicMinTopPaddingPx(
                bandHeightPx = 2043,
                firstCardHeightPx = 542,
                secondCardHeightPx = 536,
                gapPx = 30,
            ),
        ).isEqualTo(181)
    }

    @Test fun `min top pad is dramatically smaller than the old mirror pad`() {
        // The whole point of the fix: for representative sizes the minimal pad is
        // a fraction of the symmetric (band - firstHeight)/2 mirror, so there is
        // far less overscroll slack above card 0.
        val band = 2043
        val card0 = 542
        val card1 = 536
        val gap = 30
        val mirror = dynamicCenterPaddingPx(bandHeightPx = band, endItemHeightPx = card0) // 750
        val minimal = dynamicMinTopPaddingPx(band, card0, card1, gap) // 181
        assertThat(minimal).isLessThan(mirror)
        // Comfortably under half the mirror, not a marginal trim.
        assertThat(minimal * 2).isLessThan(mirror)
    }

    @Test fun `min top pad makes the second card reach with EQUALITY`() {
        // At the minimal pad the second card's ceiling lands exactly on its
        // centre line: the inequality in dynamicSecondItemCentreReachable becomes
        // an equality (reachable, with not a pixel of slack to spare). Verified
        // across a sweep of short and tall head cards.
        val band = 1000
        val gap = 24
        for (first in listOf(0, 50, 120, 300, 500, 900)) {
            for (second in listOf(0, 50, 120, 300, 500, 900, 1000, 1200)) {
                val pad = dynamicMinTopPaddingPx(band, first, second, gap)
                // The pad always keeps the second card reachable.
                assertThat(
                    dynamicSecondItemCentreReachable(
                        bandHeightPx = band,
                        firstItemHeightPx = first,
                        secondItemHeightPx = second,
                        interCardGapPx = gap,
                        topPaddingPx = pad,
                    ),
                ).isTrue()
                // When the pad is positive it is the EXACT minimum: dropping it
                // by one pixel would make the centre line unreachable.
                if (pad > 0) {
                    assertThat(
                        dynamicSecondItemCentreReachable(
                            bandHeightPx = band,
                            firstItemHeightPx = first,
                            secondItemHeightPx = second,
                            interCardGapPx = gap,
                            topPaddingPx = pad - 1,
                        ),
                    ).isFalse()
                }
            }
        }
    }

    @Test fun `min top pad is zero when the second card already reaches`() {
        // A band-filling (or taller) second card centres at <= 0, already at or
        // below its ceiling, so it reaches with no padding: P_min floors to 0.
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 800, firstCardHeightPx = 120, secondCardHeightPx = 800, gapPx = 24),
        ).isEqualTo(0)
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 800, firstCardHeightPx = 120, secondCardHeightPx = 1000, gapPx = 24),
        ).isEqualTo(0)
        // A tall FIRST card also pushes the ceiling up to the centre line on its
        // own: P_min floors to 0 there too.
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 800, firstCardHeightPx = 600, secondCardHeightPx = 120, gapPx = 24),
        ).isEqualTo(0)
    }

    @Test fun `second card centred is the raw top clamp, so no flush-on-clamp`() {
        // REGRESSION GUARD. At the minimal pad, the second card reaches its centre
        // with zero spare slack, which means: when the second card is centred,
        // card 0 is floated by EXACTLY the pad -- i.e. the list is at its raw top
        // clamp (scroll offset 0). So "card 0 floated at the raw top clamp" and
        // "the second card is focused" are the SAME list position. Any effect that
        // pins card 0 flush by reacting to the raw top clamp (offset 0) would
        // therefore scroll the user straight off the second card every time they
        // tried to focus it. This asserts that coincidence so the temptation to
        // re-add a clamp-triggered flush is caught: card 0's float when the second
        // card sits on its centre line equals the pad.
        val band = 2043
        val gap = 30
        for (card0 in listOf(120, 300, 542)) {
            for (card1 in listOf(120, 400, 536)) {
                val pad = dynamicMinTopPaddingPx(band, card0, card1, gap)
                if (pad > 0) {
                    val centre1 = dynamicSnapStartPx(itemIndex = 1, itemSizePx = card1, bandHeightPx = band)
                    val card0FloatWhenSecondCentred = centre1 - card0 - gap
                    assertThat(card0FloatWhenSecondCentred).isEqualTo(pad)
                }
            }
        }
    }

    @Test fun `min top pad clamps into the band`() {
        // Defensive bounds, mirroring dynamicCenterPaddingPx: never negative,
        // never more than one band even for a zero-height first/second card.
        // band 800, two zero-height cards, no gap: P_min = 400, within [0, 800].
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 800, firstCardHeightPx = 0, secondCardHeightPx = 0, gapPx = 0),
        ).isEqualTo(400)
        // A large gap can only shrink the pad, never push it negative.
        assertThat(
            dynamicMinTopPaddingPx(bandHeightPx = 800, firstCardHeightPx = 0, secondCardHeightPx = 0, gapPx = 10_000),
        ).isEqualTo(0)
    }

    @Test fun `min top pad keeps card zero pinnable to the flush top`() {
        // Even with the minimal pad, item 0's snap line is still the true top (0)
        // and its provider offset lands it flush under the chrome (-pad), so the
        // flush-effect / placement scroll still pull it up by exactly the pad. The
        // pad is just smaller now.
        val band = 2043
        val card0 = 542
        val card1 = 536
        val gap = 30
        val pad = dynamicMinTopPaddingPx(band, card0, card1, gap)
        assertThat(dynamicSnapStartPx(itemIndex = 0, itemSizePx = card0, bandHeightPx = band)).isEqualTo(0)
        assertThat(
            dynamicSnapProviderOffsetPx(itemIndex = 0, itemSizePx = card0, layoutSizePx = band, beforeContentPaddingPx = pad),
        ).isEqualTo(-pad)
    }

    // ── dynamicTopPaddingPx: the consistency floor on the top pad ────────────
    // The content-driven minimum is 0 for tall-first-card stacks and positive for
    // short ones, which read as arbitrary overscroll on device. The floor gives
    // every multi-card stack the same baseline give.

    @Test fun `top pad floor is a tenth of the band`() {
        assertThat(dynamicTopPaddingFloorPx(2043)).isEqualTo(204)
        assertThat(dynamicTopPaddingFloorPx(800)).isEqualTo(80)
        assertThat(dynamicTopPaddingFloorPx(0)).isEqualTo(0)
    }

    @Test fun `a stack that needs no slack still gets the floor`() {
        // Tall first card: dynamicMinTopPaddingPx is 0 (no slack needed), but the
        // applied pad is the floor so the stack is not rock-solid at the top while
        // its short-card neighbours have give. This is the consistency fix.
        val band = 2043
        assertThat(dynamicMinTopPaddingPx(band, firstCardHeightPx = 1200, secondCardHeightPx = 400, gapPx = 30))
            .isEqualTo(0)
        assertThat(dynamicTopPaddingPx(band, firstCardHeightPx = 1200, secondCardHeightPx = 400, gapPx = 30))
            .isEqualTo(dynamicTopPaddingFloorPx(band))
    }

    @Test fun `a stack that needs more than the floor keeps its full need`() {
        // Two very short cards need more slack than the floor; the MAX keeps the
        // full need so the second card still centres exactly (the floor never
        // starves it).
        val band = 2043
        val need = dynamicMinTopPaddingPx(band, firstCardHeightPx = 60, secondCardHeightPx = 60, gapPx = 30)
        assertThat(need).isGreaterThan(dynamicTopPaddingFloorPx(band))
        assertThat(dynamicTopPaddingPx(band, firstCardHeightPx = 60, secondCardHeightPx = 60, gapPx = 30))
            .isEqualTo(need)
    }

    @Test fun `top pad is the floor before the head heights are measured`() {
        // Null heights (not measured): the need term is 0, so the floor shows
        // through immediately, giving the baseline give from the first frame.
        val band = 2043
        assertThat(dynamicTopPaddingPx(band, firstCardHeightPx = null, secondCardHeightPx = null, gapPx = 30))
            .isEqualTo(dynamicTopPaddingFloorPx(band))
        assertThat(dynamicTopPaddingPx(band, firstCardHeightPx = 542, secondCardHeightPx = null, gapPx = 30))
            .isEqualTo(dynamicTopPaddingFloorPx(band))
    }

    @Test fun `top pad never drops below the floor for any measured stack`() {
        // Sweep: whatever the card sizes, a multi-card stack's pad is at least the
        // floor, so no stack sits at the top with zero give.
        val band = 1000
        val gap = 24
        val floor = dynamicTopPaddingFloorPx(band)
        for (first in listOf(0, 60, 200, 500, 900, 1200)) {
            for (second in listOf(0, 60, 200, 500, 900, 1200)) {
                assertThat(dynamicTopPaddingPx(band, first, second, gap)).isAtLeast(floor)
            }
        }
    }

    @Test fun `the floored pad still reaches the second card`() {
        // The floor only ever ADDS slack, so the second card is reachable under
        // the floored pad for every stack (the pad is >= the minimum need).
        val band = 1000
        val gap = 24
        for (first in listOf(0, 120, 300, 700)) {
            for (second in listOf(0, 120, 300, 700, 1000)) {
                val pad = dynamicTopPaddingPx(band, first, second, gap)
                assertThat(
                    dynamicSecondItemCentreReachable(
                        bandHeightPx = band,
                        firstItemHeightPx = first,
                        secondItemHeightPx = second,
                        interCardGapPx = gap,
                        topPaddingPx = pad,
                    ),
                ).isTrue()
            }
        }
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
