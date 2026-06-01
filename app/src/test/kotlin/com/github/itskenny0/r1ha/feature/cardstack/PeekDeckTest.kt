package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.CardPeekMode
import com.github.itskenny0.r1ha.ui.components.WindowTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit coverage for [effectivePeek] — the pure peek-deck decision. Locks the gating
 * contract: AUTO peeks only on a phone-width tier in portrait; ALWAYS peeks everywhere;
 * NEVER never peeks. The R1 / sub-compact tier must never peek under AUTO so existing
 * installs see no change on upgrade.
 */
class PeekDeckTest {

    @Test
    fun auto_phonePortrait_peeks() {
        assertTrue(effectivePeek(CardPeekMode.AUTO, WindowTier.COMPACT, isPortrait = true))
        // MEDIUM (large landscape phones / small tablets) also counts as a phone tier
        // for peek when it happens to be in portrait.
        assertTrue(effectivePeek(CardPeekMode.AUTO, WindowTier.MEDIUM, isPortrait = true))
    }

    @Test
    fun auto_r1_doesNotPeek() {
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.R1, isPortrait = true))
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.R1, isPortrait = false))
    }

    @Test
    fun auto_landscape_doesNotPeek() {
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.COMPACT, isPortrait = false))
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.MEDIUM, isPortrait = false))
    }

    @Test
    fun auto_tablet_doesNotPeek() {
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.EXPANDED, isPortrait = true))
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.EXTRA_LARGE, isPortrait = true))
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.EXPANDED, isPortrait = false))
        assertFalse(effectivePeek(CardPeekMode.AUTO, WindowTier.EXTRA_LARGE, isPortrait = false))
    }

    @Test
    fun always_peeksEverywhere() {
        for (tier in WindowTier.entries) {
            for (portrait in listOf(true, false)) {
                assertTrue(
                    "ALWAYS should peek on $tier portrait=$portrait",
                    effectivePeek(CardPeekMode.ALWAYS, tier, portrait),
                )
            }
        }
    }

    @Test
    fun never_peeksNowhere() {
        for (tier in WindowTier.entries) {
            for (portrait in listOf(true, false)) {
                assertFalse(
                    "NEVER should never peek on $tier portrait=$portrait",
                    effectivePeek(CardPeekMode.NEVER, tier, portrait),
                )
            }
        }
    }

    // ── peekActiveForDeck ──────────────────────────────────────────────────────────────

    @Test
    fun peekActiveForDeck_needsAtLeastTwoCards() {
        // A lone card (or empty deck) has no neighbour to peek, so the peek layout is
        // suppressed and the card renders full-bleed.
        assertFalse(peekActiveForDeck(peekEnabled = true, cardCount = 0))
        assertFalse(peekActiveForDeck(peekEnabled = true, cardCount = 1))
        assertTrue(peekActiveForDeck(peekEnabled = true, cardCount = 2))
        assertTrue(peekActiveForDeck(peekEnabled = true, cardCount = 25))
    }

    @Test
    fun peekActiveForDeck_offWhenTierDecisionIsOff() {
        // When effectivePeek already said no, the card count never re-enables peeking.
        for (count in 0..5) {
            assertFalse("count=$count", peekActiveForDeck(peekEnabled = false, cardCount = count))
        }
    }

    // ── Snap / settle math ────────────────────────────────────────────────────────────
    //
    // Pure model of androidx.compose.foundation's VerticalPager settle behaviour, verified
    // against the decompiled foundation measure code:
    //   - page p's top offset from the content start at scroll d is  off_p(d) = p*(h + s) - d
    //   - the reachable scroll range is d in [ -before, dMax ] with
    //       dMax = max(0, count*h + (count - 1)*s - A)   and   A = viewport - before - after
    //   - the settled page minimises |off_p(d) - snap|, where the snap target offset is
    //       0           for SnapPosition.Start  (page top flush to the content start), or
    //       (A - h)/2   for SnapPosition.Center (page centred in the available band).
    // All arithmetic is integer (dp), matching the pager's integer measure pass. These tests
    // lock the design decisions behind the peek-deck fix: the active card centres, the edge
    // cards clamp flush to the top / bottom, and every card in a short deck can settle.

    private fun pageHeight(viewport: Int, before: Int, after: Int, fraction: Float): Int =
        Math.round((viewport - before - after) * fraction)

    private fun settledPageAt(d: Int, count: Int, pageWithSpacing: Int, snap: Int): Int {
        var best = 0
        var bestDist = Int.MAX_VALUE
        for (p in 0 until count) {
            val dist = kotlin.math.abs((p * pageWithSpacing - d) - snap)
            if (dist < bestDist) {
                bestDist = dist
                best = p
            }
        }
        return best
    }

    /** The set of card indices that can become the settled (active) page somewhere within
     *  the pager's reachable scroll range. */
    private fun reachableSettled(
        viewport: Int,
        before: Int,
        after: Int,
        fraction: Float,
        spacing: Int,
        count: Int,
        center: Boolean,
    ): Set<Int> {
        val a = viewport - before - after
        val h = pageHeight(viewport, before, after, fraction)
        val pageWithSpacing = h + spacing
        val dMax = maxOf(0, count * h + (count - 1) * spacing - a)
        val snap = if (center) (a - h) / 2 else 0
        val seen = sortedSetOf<Int>()
        var d = -before
        while (d <= dMax) {
            seen.add(settledPageAt(d, count, pageWithSpacing, snap))
            d++
        }
        return seen
    }

    /** The resting top offset (from the content start) of card [index] once it is the
     *  settled page: 0 = flush top, (A-h)/2 = centred, A-h = flush bottom. */
    private fun restOffset(
        index: Int,
        viewport: Int,
        before: Int,
        after: Int,
        fraction: Float,
        spacing: Int,
        count: Int,
        center: Boolean,
    ): Int {
        val a = viewport - before - after
        val h = pageHeight(viewport, before, after, fraction)
        val pageWithSpacing = h + spacing
        val dMax = maxOf(0, count * h + (count - 1) * spacing - a)
        val snap = if (center) (a - h) / 2 else 0
        val dStar = (index * pageWithSpacing - snap).coerceIn(-before, dMax)
        return index * pageWithSpacing - dStar
    }

    @Test
    fun startSnap_twoCardDeck_secondCardNeverSettles_regression() {
        // The shipped peek deck used the default SnapPosition.Start with chrome-sized
        // symmetric content padding. With a fractional page a two-card deck can never
        // scroll its last card to the top snap, so the second card never settled and could
        // never be activated. This characterises the broken behaviour the fix removes.
        val reachable = reachableSettled(
            viewport = 800, before = 104, after = 104, fraction = PEEK_FRACTION, spacing = 8,
            count = 2, center = false,
        )
        assertEquals(setOf(0), reachable)
    }

    @Test
    fun centerSnap_twoCardDeck_bothCardsSettle() {
        // The fix: pager inset below the chrome (zero content padding) + SnapPosition.Center.
        val reachable = reachableSettled(
            viewport = INSET_BAND, before = 0, after = 0, fraction = PEEK_FRACTION, spacing = 8,
            count = 2, center = true,
        )
        assertEquals(setOf(0, 1), reachable)
    }

    @Test
    fun centerSnap_everyCardCanSettle_acrossShortDecks() {
        for (count in 2..6) {
            val reachable = reachableSettled(
                viewport = INSET_BAND, before = 0, after = 0, fraction = PEEK_FRACTION, spacing = 8,
                count = count, center = true,
            )
            assertEquals("count=$count", (0 until count).toSet(), reachable)
        }
    }

    @Test
    fun centerSnap_topmostClampsTop_bottommostClampsBottom_middleCentres() {
        val a = INSET_BAND
        val h = pageHeight(INSET_BAND, 0, 0, PEEK_FRACTION)
        val top = 0
        val centre = (a - h) / 2
        val bottom = a - h

        // Three-card deck: first → top, middle → centre, last → bottom.
        assertEquals(top, restOffset(0, INSET_BAND, 0, 0, PEEK_FRACTION, 8, count = 3, center = true))
        assertEquals(centre, restOffset(1, INSET_BAND, 0, 0, PEEK_FRACTION, 8, count = 3, center = true))
        assertEquals(bottom, restOffset(2, INSET_BAND, 0, 0, PEEK_FRACTION, 8, count = 3, center = true))

        // Two-card deck (both are edge cards): first → top, last → bottom.
        assertEquals(top, restOffset(0, INSET_BAND, 0, 0, PEEK_FRACTION, 8, count = 2, center = true))
        assertEquals(bottom, restOffset(1, INSET_BAND, 0, 0, PEEK_FRACTION, 8, count = 2, center = true))
    }

    private companion object {
        /** Mirrors PEEK_PAGE_FRACTION in CardStackScreen.kt. */
        const val PEEK_FRACTION = 0.62f

        /** A representative below-chrome band: an 800 dp viewport minus a ~104 dp chrome
         *  inset and a 16 dp bottom inset. Used as the peek pager's effective viewport. */
        const val INSET_BAND = 680
    }
}
