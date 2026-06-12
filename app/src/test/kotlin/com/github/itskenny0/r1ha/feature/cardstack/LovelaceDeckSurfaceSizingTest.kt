package com.github.itskenny0.r1ha.feature.cardstack

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertTopPositionInRootIsEqualTo
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Measure-pass contract of [DeckCardSurface], the visible panel of a pinned
 * Lovelace deck slot. The slot (pager page) is full-size for swipe mechanics;
 * the surface inside must
 *  - HUG short content (a button card paints button-height, not a full page),
 *  - stay vertically centred in the slot,
 *  - CAP at the slot height when the content is taller, with the overflow
 *    behind the internal scroll.
 *
 * Runs the real production composable inside a fixed-size centering Box that
 * mirrors the PageDeck slot's loose constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LovelaceDeckSurfaceSizingTest {

    @get:Rule val compose = createComposeRule()

    private fun host(contentHeight: androidx.compose.ui.unit.Dp, scrollable: Boolean) {
        compose.setContent {
            Box(
                modifier = Modifier.size(width = 360.dp, height = SLOT_HEIGHT),
                contentAlignment = Alignment.Center,
            ) {
                DeckCardSurface(
                    scrollable = scrollable,
                    modifier = Modifier.testTag("surface"),
                ) {
                    Box(Modifier.fillMaxWidth().height(contentHeight))
                }
            }
        }
    }

    @Test fun shortContentHugsItsHeight() {
        host(contentHeight = 40.dp, scrollable = true)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(40.dp)
    }

    @Test fun shortContentIsVerticallyCentredInTheSlot() {
        host(contentHeight = 40.dp, scrollable = true)
        // (400 - 40) / 2 = 180dp of slack above the surface.
        compose.onNodeWithTag("surface").assertTopPositionInRootIsEqualTo(180.dp)
    }

    @Test fun tallContentCapsAtTheSlotHeight() {
        host(contentHeight = 800.dp, scrollable = true)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(SLOT_HEIGHT)
        compose.onNodeWithTag("surface").assertTopPositionInRootIsEqualTo(0.dp)
    }

    @Test fun nonScrollableIframeSurfaceStillHugs() {
        host(contentHeight = 120.dp, scrollable = false)
        compose.onNodeWithTag("surface").assertHeightIsEqualTo(120.dp)
    }

    private companion object {
        val SLOT_HEIGHT = 400.dp
    }
}
