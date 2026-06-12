package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.ha.EntityId
import com.github.itskenny0.r1ha.core.ha.EntityState
import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.github.itskenny0.r1ha.core.prefs.FavoritePage
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

class DeckItemsTest {

    private fun entity(id: String, name: String = "n") = EntityState(
        id = EntityId(id), friendlyName = name, area = null, isOn = true,
        percent = null, raw = null, lastChanged = Instant.EPOCH, isAvailable = true,
    )

    private fun page(
        favorites: List<String> = emptyList(),
        pinnedCards: List<String> = emptyList(),
        pinnedCardIds: List<String> = emptyList(),
        deckOrder: List<String> = emptyList(),
    ) = FavoritePage(
        id = "p1", name = "HOME",
        favorites = favorites,
        pinnedCards = pinnedCards,
        pinnedCardIds = pinnedCardIds,
        deckOrder = deckOrder,
    )

    // ── parsePinnedCards (carried over from the v1 deck) ────────────────────

    @Test fun `iframe config parses into a renderable card with its url`() {
        val cards = parsePinnedCards(
            listOf("""{"type":"iframe","url":"https://grafana.local/d/abc","aspect_ratio":"16:9"}"""),
        )
        assertThat(cards).hasSize(1)
        val card = cards.single()
        assertThat(card.type).isEqualTo("iframe")
        assertThat((card as LovelaceCard.Unsupported).url)
            .isEqualTo("https://grafana.local/d/abc")
    }

    @Test fun `native card types parse to their typed variants`() {
        val cards = parsePinnedCards(
            listOf(
                """{"type":"markdown","content":"# hi"}""",
                """{"type":"gauge","entity":"sensor.cpu","min":0,"max":100}""",
            ),
        )
        assertThat(cards).hasSize(2)
        assertThat(cards[0]).isInstanceOf(LovelaceCard.Markdown::class.java)
        assertThat(cards[1]).isInstanceOf(LovelaceCard.Gauge::class.java)
    }

    @Test fun `unparseable blobs are dropped without sinking the rest`() {
        val cards = parsePinnedCards(
            listOf(
                "{not json",
                """{"type":"markdown","content":"survives"}""",
                """["an","array","not","an","object"]""",
            ),
        )
        assertThat(cards).hasSize(1)
        assertThat(cards.single()).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `empty stored list parses to an empty deck`() {
        assertThat(parsePinnedCards(emptyList())).isEmpty()
    }

    // ── keys ────────────────────────────────────────────────────────────────

    @Test fun `entity and card keys live in disjoint namespaces`() {
        val e = DeckItem.Entity(entity("light.a"))
        val markdown = parsePinnedCards(listOf("""{"type":"markdown","content":"x"}""")).single()
        val c = DeckItem.Card(card = markdown, raw = "{}", id = "light.a")
        // Same underlying string, different prefixes: never a pager-key clash.
        assertThat(e.key).isNotEqualTo(c.key)
    }

    @Test fun `two identical card configs keep distinct keys via their ids`() {
        val blob = """{"type":"markdown","content":"twin"}"""
        val parsed = parsePinnedCards(listOf(blob, blob))
        val a = DeckItem.Card(parsed[0], blob, id = "c1")
        val b = DeckItem.Card(parsed[1], blob, id = "c2")
        assertThat(a.key).isNotEqualTo(b.key)
    }

    // ── buildDeckItems ──────────────────────────────────────────────────────

    @Test fun `favourites-only page materializes in favourites order`() {
        val states = listOf("light.a", "switch.b").associateWith { entity(it) }
        val deck = buildDeckItems(
            page = page(favorites = listOf("switch.b", "light.a")),
            materializeEntity = { states[it] },
            parseCard = { null },
        )
        assertThat(deck.map { it.key }).containsExactly("e:switch.b", "e:light.a").inOrder()
    }

    @Test fun `mixed page interleaves per deckOrder and drops unresolvable slots`() {
        val states = mapOf("light.a" to entity("light.a"))
        val good = """{"type":"markdown","content":"ok"}"""
        val deck = buildDeckItems(
            page = page(
                favorites = listOf("light.a", "sensor.pending"),
                pinnedCards = listOf(good, "{broken"),
                pinnedCardIds = listOf("c1", "c2"),
                deckOrder = listOf("c:c1", "e:light.a", "c:c2", "e:sensor.pending"),
            ),
            // sensor.pending has no HA state yet: drops out like before.
            materializeEntity = { states[it] },
            parseCard = { raw -> parsePinnedCard(raw) },
        )
        assertThat(deck.map { it.key }).containsExactly("c:c1", "e:light.a").inOrder()
        val cardItem = deck[0] as DeckItem.Card
        assertThat(cardItem.raw).isEqualTo(good)
        assertThat(cardItem.id).isEqualTo("c1")
    }

    @Test fun `legacy page without ids renders cards after favourites`() {
        val states = mapOf("light.a" to entity("light.a"))
        val deck = buildDeckItems(
            page = page(
                favorites = listOf("light.a"),
                pinnedCards = listOf("""{"type":"markdown","content":"x"}"""),
            ),
            materializeEntity = { states[it] },
            parseCard = { raw -> parsePinnedCard(raw) },
        )
        assertThat(deck.map { it.key }).containsExactly("e:light.a", "c:legacy-0").inOrder()
    }

    // ── display helpers ─────────────────────────────────────────────────────

    @Test fun `card titles prefer explicit title then entity then url then type`() {
        fun titled(blob: String): String = deckCardTitle(parsePinnedCards(listOf(blob)).single())
        assertThat(titled("""{"type":"gauge","entity":"sensor.cpu","title":"CPU"}""")).isEqualTo("CPU")
        assertThat(titled("""{"type":"gauge","entity":"sensor.cpu"}""")).isEqualTo("sensor.cpu")
        assertThat(titled("""{"type":"iframe","url":"https://x.y"}""")).isEqualTo("https://x.y")
        assertThat(titled("""{"type":"energy-date-selection"}"""))
            .isEqualTo("ENERGY DATE SELECTION")
    }

    @Test fun `markdown titles fall back to the first content line`() {
        val t = deckCardTitle(
            parsePinnedCards(listOf("""{"type":"markdown","content":"# Hello\nbody"}""")).single(),
        )
        assertThat(t).isEqualTo("Hello")
    }

    @Test fun `display kind reads domain for entities and type for cards`() {
        assertThat(DeckItem.Entity(entity("light.a")).displayKind()).isEqualTo("LIGHT")
        val md = parsePinnedCards(listOf("""{"type":"weather-forecast","entity":"weather.h"}""")).single()
        assertThat(DeckItem.Card(md, "{}", "c1").displayKind()).isEqualTo("WEATHER FORECAST")
    }

    // ── deckCardHeaderTitle: the deck slot's identity header ────────────────

    private fun header(blob: String): String? =
        deckCardHeaderTitle(parsePinnedCards(listOf(blob)).single())

    @Test fun `self-titled cards get no header`() {
        // The renderers surface explicit title / name / heading on the card
        // face themselves; a deck header would double-label them.
        assertThat(header("""{"type":"entities","title":"Office","entities":[]}""")).isNull()
        assertThat(header("""{"type":"button","name":"All off","entity":"switch.a"}""")).isNull()
        assertThat(header("""{"type":"heading","heading":"Upstairs"}""")).isNull()
    }

    @Test fun `markdown faces are their own title`() {
        assertThat(header("""{"type":"markdown","content":"# Hello\nbody"}""")).isNull()
    }

    @Test fun `untitled faces get the jump sheet's derived title`() {
        // Same fallback chain as deckCardTitle, so the stack header always
        // matches the jump-sheet row for the slot.
        assertThat(header("""{"type":"gauge","entity":"sensor.cpu"}""")).isEqualTo("sensor.cpu")
        assertThat(header("""{"type":"iframe","url":"https://x.y"}""")).isEqualTo("https://x.y")
        assertThat(header("""{"type":"entities","entities":[]}""")).isEqualTo("ENTITIES")
    }

    @Test fun `header equals the jump sheet title whenever it shows`() {
        val blob = """{"type":"gauge","entity":"sensor.cpu"}"""
        val card = parsePinnedCards(listOf(blob)).single()
        assertThat(deckCardHeaderTitle(card)).isEqualTo(deckCardTitle(card))
    }
}
