package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.prefs.CardPeekMode
import com.github.itskenny0.r1ha.ui.components.WindowTier
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
}
