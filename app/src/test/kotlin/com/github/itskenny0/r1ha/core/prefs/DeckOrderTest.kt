package com.github.itskenny0.r1ha.core.prefs

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeckOrderTest {

    private fun page(
        favorites: List<String> = emptyList(),
        pinnedCards: List<String> = emptyList(),
        pinnedCardIds: List<String> = emptyList(),
        deckOrder: List<String> = emptyList(),
    ) = FavoritePage(
        id = "p1",
        name = "HOME",
        favorites = favorites,
        pinnedCards = pinnedCards,
        pinnedCardIds = pinnedCardIds,
        deckOrder = deckOrder,
    )

    // ── ref codec ───────────────────────────────────────────────────────────

    @Test fun `refs round-trip through encode and decode`() {
        val e = DeckRef.Entity("light.desk")
        val c = DeckRef.Card("lc123")
        assertThat(decodeDeckRef(e.encode())).isEqualTo(e)
        assertThat(decodeDeckRef(c.encode())).isEqualTo(c)
    }

    @Test fun `malformed and unknown-prefix refs decode to null`() {
        assertThat(decodeDeckRef("")).isNull()
        assertThat(decodeDeckRef("e:")).isNull()
        assertThat(decodeDeckRef("c:")).isNull()
        assertThat(decodeDeckRef("x:future-kind")).isNull()
        assertThat(decodeDeckRef("light.desk")).isNull()
    }

    // ── id resolution + healing ─────────────────────────────────────────────

    @Test fun `missing ids resolve to deterministic positional placeholders`() {
        val p = page(pinnedCards = listOf("{}", "{}"))
        assertThat(p.resolvedPinnedCardIds()).containsExactly("legacy-0", "legacy-1").inOrder()
        // Deterministic: a second resolution sees the same ids.
        assertThat(p.resolvedPinnedCardIds()).isEqualTo(p.resolvedPinnedCardIds())
    }

    @Test fun `stored ids win and duplicates get unique fallbacks`() {
        val p = page(pinnedCards = listOf("{}", "{}", "{}"), pinnedCardIds = listOf("a", "a"))
        val resolved = p.resolvedPinnedCardIds()
        assertThat(resolved[0]).isEqualTo("a")
        assertThat(resolved).containsNoDuplicates()
        assertThat(resolved).hasSize(3)
    }

    @Test fun `healing persists resolved ids and is idempotent`() {
        val p = page(pinnedCards = listOf("{}", "{}"), pinnedCardIds = listOf("a"))
        val healed = healPinnedCardIds(p)
        assertThat(healed.pinnedCardIds).isEqualTo(listOf("a", "legacy-1"))
        assertThat(healPinnedCardIds(healed)).isSameInstanceAs(healed)
    }

    @Test fun `healing trims id tails left behind by card deletion`() {
        val p = page(pinnedCards = listOf("{}"), pinnedCardIds = listOf("a", "b"))
        assertThat(healPinnedCardIds(p).pinnedCardIds).containsExactly("a")
    }

    // ── effective order ─────────────────────────────────────────────────────

    @Test fun `empty deckOrder degrades to favourites then cards`() {
        val p = page(
            favorites = listOf("light.a", "switch.b"),
            pinnedCards = listOf("{}"),
            pinnedCardIds = listOf("c1"),
        )
        assertThat(p.effectiveDeckRefs()).containsExactly(
            DeckRef.Entity("light.a"),
            DeckRef.Entity("switch.b"),
            DeckRef.Card("c1"),
        ).inOrder()
    }

    @Test fun `stored interleave is honoured`() {
        val p = page(
            favorites = listOf("light.a", "switch.b"),
            pinnedCards = listOf("{}"),
            pinnedCardIds = listOf("c1"),
            deckOrder = listOf("e:light.a", "c:c1", "e:switch.b"),
        )
        assertThat(p.effectiveDeckRefs()).containsExactly(
            DeckRef.Entity("light.a"),
            DeckRef.Card("c1"),
            DeckRef.Entity("switch.b"),
        ).inOrder()
    }

    @Test fun `stale refs drop out and new items append at the end`() {
        val p = page(
            favorites = listOf("light.a", "light.new"),
            pinnedCards = listOf("{}", "{}"),
            pinnedCardIds = listOf("c1", "cNew"),
            // c:gone was deleted; light.new + cNew were added by an older
            // build that doesn't write deckOrder.
            deckOrder = listOf("c:gone", "c:c1", "e:light.a"),
        )
        assertThat(p.effectiveDeckRefs()).containsExactly(
            DeckRef.Card("c1"),
            DeckRef.Entity("light.a"),
            DeckRef.Entity("light.new"),
            DeckRef.Card("cNew"),
        ).inOrder()
    }

    @Test fun `duplicate refs collapse to first occurrence`() {
        val p = page(
            favorites = listOf("light.a"),
            deckOrder = listOf("e:light.a", "e:light.a"),
        )
        assertThat(p.effectiveDeckRefs()).containsExactly(DeckRef.Entity("light.a"))
    }

    @Test fun `canonical order stores as empty list`() {
        val p = page(
            favorites = listOf("light.a"),
            pinnedCards = listOf("{}"),
            pinnedCardIds = listOf("c1"),
        )
        assertThat(canonicalDeckOrder(p, p.effectiveDeckRefs())).isEmpty()
    }

    // ── move ────────────────────────────────────────────────────────────────

    @Test fun `favourites-only move keeps storage byte-identical to legacy reorder`() {
        val p = page(favorites = listOf("a.a", "b.b", "c.c"))
        val moved = p.withDeckMove(DeckRef.Entity("a.a"), DeckRef.Entity("b.b"))
        assertThat(moved.favorites).containsExactly("b.b", "a.a", "c.c").inOrder()
        // No deckOrder, no card ids: exactly the shape a pre-interleave build wrote.
        assertThat(moved.deckOrder).isEmpty()
        assertThat(moved.pinnedCardIds).isEmpty()
        assertThat(moved.pinnedCards).isEmpty()
    }

    @Test fun `mixed move persists the interleave and reorders stored lists`() {
        val p = page(
            favorites = listOf("a.a", "b.b"),
            pinnedCards = listOf("{\"type\":\"markdown\"}"),
            pinnedCardIds = listOf("c1"),
        )
        // Default order: a.a, b.b, c1. Drag the card up onto a.a's slot.
        val moved = p.withDeckMove(DeckRef.Card("c1"), DeckRef.Entity("a.a"))
        assertThat(moved.effectiveDeckRefs()).containsExactly(
            DeckRef.Card("c1"),
            DeckRef.Entity("a.a"),
            DeckRef.Entity("b.b"),
        ).inOrder()
        // Entity relative order is intact for old builds reading favourites only.
        assertThat(moved.favorites).containsExactly("a.a", "b.b").inOrder()
        assertThat(moved.deckOrder).isNotEmpty()
    }

    @Test fun `downward move lands after the target like a list drag`() {
        val p = page(favorites = listOf("a.a", "b.b", "c.c"))
        val moved = p.withDeckMove(DeckRef.Entity("a.a"), DeckRef.Entity("c.c"))
        assertThat(moved.favorites).containsExactly("b.b", "c.c", "a.a").inOrder()
    }

    @Test fun `move with an unresolvable ref is a no-op`() {
        val p = page(favorites = listOf("a.a", "b.b"))
        assertThat(p.withDeckMove(DeckRef.Entity("gone.x"), DeckRef.Entity("a.a"))).isEqualTo(p)
    }

    // ── card mutations ──────────────────────────────────────────────────────

    @Test fun `append assigns fresh unique ids and keeps canonical order empty`() {
        var n = 0
        val p = page(favorites = listOf("a.a"))
            .withPinnedCardsAppended(listOf("{}", "{}"), idGen = { "id${n++}" })
        assertThat(p.pinnedCards).hasSize(2)
        assertThat(p.pinnedCardIds).containsExactly("id0", "id1").inOrder()
        assertThat(p.deckOrder).isEmpty()
    }

    @Test fun `append onto a custom interleave lists the new refs explicitly`() {
        var n = 0
        val base = page(
            favorites = listOf("a.a"),
            pinnedCards = listOf("{}"),
            pinnedCardIds = listOf("c1"),
            deckOrder = listOf("c:c1", "e:a.a"),
        )
        val grown = base.withPinnedCardsAppended(listOf("{}"), idGen = { "id${n++}" })
        assertThat(grown.effectiveDeckRefs()).containsExactly(
            DeckRef.Card("c1"),
            DeckRef.Entity("a.a"),
            DeckRef.Card("id0"),
        ).inOrder()
    }

    @Test fun `removal heals the order and drops the dead ref`() {
        val p = page(
            favorites = listOf("a.a"),
            pinnedCards = listOf("{\"x\":1}", "{\"x\":2}"),
            pinnedCardIds = listOf("c1", "c2"),
            deckOrder = listOf("c:c2", "e:a.a", "c:c1"),
        )
        val removed = p.withPinnedCardRemoved("c2")
        assertThat(removed.pinnedCards).containsExactly("{\"x\":1}")
        assertThat(removed.pinnedCardIds).containsExactly("c1")
        assertThat(removed.deckOrder).doesNotContain("c:c2")
        assertThat(removed.effectiveDeckRefs()).containsExactly(
            DeckRef.Entity("a.a"),
            DeckRef.Card("c1"),
        ).inOrder()
    }

    @Test fun `replace keeps the card id and slot`() {
        val p = page(
            pinnedCards = listOf("{\"x\":1}", "{\"x\":2}"),
            pinnedCardIds = listOf("c1", "c2"),
        )
        val edited = p.withPinnedCardReplaced("c1", "{\"x\":9}")
        assertThat(edited.pinnedCards).containsExactly("{\"x\":9}", "{\"x\":2}").inOrder()
        assertThat(edited.pinnedCardIds).containsExactly("c1", "c2").inOrder()
    }

    @Test fun `favourite removal also drops its deckOrder ref`() {
        val p = page(
            favorites = listOf("a.a", "b.b"),
            pinnedCards = listOf("{}"),
            pinnedCardIds = listOf("c1"),
            deckOrder = listOf("e:b.b", "c:c1", "e:a.a"),
        )
        val removed = p.withFavoriteRemoved("b.b")
        assertThat(removed.favorites).containsExactly("a.a")
        assertThat(removed.deckOrder).doesNotContain("e:b.b")
        assertThat(removed.effectiveDeckRefs()).containsExactly(
            DeckRef.Card("c1"),
            DeckRef.Entity("a.a"),
        ).inOrder()
    }

    // ── rendered-index moves (fast-drag composition) ────────────────────────

    @Test fun `sequential rendered-index swaps compose like one continuous drag`() {
        // A fast drag fires adjacent swaps per frame, each against the page
        // state the PREVIOUS swap produced. Regression: translating indices at
        // the call site read the pre-drag snapshot for every swap and
        // scrambled the order (A dragged down two slots yielded [A,C,B,D]).
        val visible = setOf("e:a.a", "e:b.b", "e:c.c", "e:d.d")
        var p = page(favorites = listOf("a.a", "b.b", "c.c", "d.d"))
        p = p.withDeckMoveByRenderedIndex(0, 1, visible)
        p = p.withDeckMoveByRenderedIndex(1, 2, visible)
        assertThat(p.favorites).containsExactly("b.b", "c.c", "a.a", "d.d").inOrder()
    }

    @Test fun `rendered indices skip hidden items when translating to refs`() {
        // b.b is stored but not rendered (hidden while unavailable): rendered
        // slot 1 is c.c, and the move must land relative to it while b.b keeps
        // its storage position.
        val visible = setOf("e:a.a", "e:c.c")
        val p = page(favorites = listOf("a.a", "b.b", "c.c"))
            .withDeckMoveByRenderedIndex(0, 1, visible)
        assertThat(p.favorites).containsExactly("b.b", "c.c", "a.a").inOrder()
    }

    @Test fun `out-of-range rendered indices are a no-op`() {
        val p = page(favorites = listOf("a.a", "b.b"))
        assertThat(p.withDeckMoveByRenderedIndex(5, 0, setOf("e:a.a", "e:b.b"))).isEqualTo(p)
        assertThat(p.withDeckMoveByRenderedIndex(0, 0, setOf("e:a.a", "e:b.b"))).isEqualTo(p)
    }
}
