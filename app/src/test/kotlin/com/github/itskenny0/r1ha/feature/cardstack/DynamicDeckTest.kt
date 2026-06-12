package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.prefs.DeckLayoutMode
import com.github.itskenny0.r1ha.core.prefs.UiOptions
import com.github.itskenny0.r1ha.ui.components.WindowTier
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DynamicDeckTest {

    private fun entityItem(id: String = "light.desk") = DeckItem.Entity(
        EntityState(
            id = EntityId(id), friendlyName = "n", area = null, isOn = true,
            percent = null, raw = null, lastChanged = Instant.EPOCH, isAvailable = true,
        ),
    )

    private fun cardItem(): DeckItem.Card {
        val raw = """{"type":"markdown","content":"hi"}"""
        return DeckItem.Card(card = parsePinnedCard(raw)!!, raw = raw, id = "c1")
    }

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

    // ── deckItemFillsViewport: the per-item height policy ───────────────────

    @Test fun `entity items fill the viewport so the wheel surface is unchanged`() {
        assertThat(deckItemFillsViewport(entityItem())).isTrue()
    }

    @Test fun `lovelace items hug their content`() {
        assertThat(deckItemFillsViewport(cardItem())).isFalse()
    }

    // ── dynamicFocusedIndex: nearest item start to the snap line ────────────

    @Test fun `settled item on the line is focused`() {
        val visible = listOf(
            DynamicVisibleItem(index = 3, offsetPx = 0),
            DynamicVisibleItem(index = 4, offsetPx = 220),
        )
        assertThat(dynamicFocusedIndex(visible)).isEqualTo(3)
    }

    @Test fun `nearest start wins over an earlier mostly-scrolled-out card`() {
        // Item 1 is two-thirds off the top, item 2's start is 40 px below the
        // line: item 2 is what the user perceives as the card.
        val visible = listOf(
            DynamicVisibleItem(index = 1, offsetPx = -300),
            DynamicVisibleItem(index = 2, offsetPx = 40),
            DynamicVisibleItem(index = 3, offsetPx = 480),
        )
        assertThat(dynamicFocusedIndex(visible)).isEqualTo(2)
    }

    @Test fun `ties break toward the earlier index`() {
        val visible = listOf(
            DynamicVisibleItem(index = 5, offsetPx = -60),
            DynamicVisibleItem(index = 6, offsetPx = 60),
        )
        assertThat(dynamicFocusedIndex(visible)).isEqualTo(5)
    }

    @Test fun `empty visible list falls back to zero`() {
        assertThat(dynamicFocusedIndex(emptyList())).isEqualTo(0)
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
