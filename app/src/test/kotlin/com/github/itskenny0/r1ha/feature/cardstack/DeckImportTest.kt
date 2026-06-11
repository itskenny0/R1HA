package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.lovelace.LovelaceParser
import com.github.itskenny0.r1ha.core.lovelace.LovelaceView
import com.github.itskenny0.r1ha.core.lovelace.parseCardJsonBlob
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class DeckImportTest {

    private fun card(blob: String) = LovelaceParser.parseCard(parseCardJsonBlob(blob)!!)

    private fun view(
        title: String?,
        path: String = "v",
        cards: List<String> = emptyList(),
    ) = LovelaceView(
        title = title,
        path = path,
        icon = null,
        panel = false,
        cards = cards.map { card(it) },
    )

    @Test fun `page names come from the view title uppercased and capped`() {
        assertThat(importPageName(view(title = "Living room"), 0)).isEqualTo("LIVING ROOM")
        assertThat(importPageName(view(title = "A very long view title indeed"), 0))
            .isEqualTo("A VERY LONG VIEW TIT")
    }

    @Test fun `missing titles fall back to path then position`() {
        assertThat(importPageName(view(title = null, path = "lights"), 0)).isEqualTo("LIGHTS")
        // An all-digit path is HA's index fallback, not a name; use position.
        assertThat(importPageName(view(title = null, path = "2"), 2)).isEqualTo("VIEW 3")
        assertThat(importPageName(view(title = "  ", path = ""), 0)).isEqualTo("VIEW 1")
    }

    @Test fun `card blobs round-trip the raw config verbatim including unknown keys`() {
        val v = view(
            title = "T",
            cards = listOf("""{"type":"gauge","entity":"sensor.cpu","custom_option":42}"""),
        )
        val blobs = viewCardBlobs(v)
        assertThat(blobs).hasSize(1)
        val reparsed = parseCardJsonBlob(blobs.single())!!
        assertThat(reparsed["custom_option"].toString()).isEqualTo("42")
        assertThat(parsePinnedCards(blobs)).hasSize(1)
    }

    @Test fun `import mapping keeps view order and card order`() {
        val pages = viewsToImportablePages(
            listOf(
                view(
                    title = "One", path = "one",
                    cards = listOf(
                        """{"type":"markdown","content":"a"}""",
                        """{"type":"markdown","content":"b"}""",
                    ),
                ),
                view(title = "Two", path = "two", cards = listOf("""{"type":"markdown","content":"c"}""")),
            ),
        )
        assertThat(pages.map { it.name }).containsExactly("ONE", "TWO").inOrder()
        assertThat(pages[0].cardBlobs).hasSize(2)
        val contents = pages[0].cardBlobs.map {
            parseCardJsonBlob(it)!!["content"].toString()
        }
        assertThat(contents).containsExactly("\"a\"", "\"b\"").inOrder()
    }

    @Test fun `views without cards are skipped`() {
        val pages = viewsToImportablePages(
            listOf(
                view(title = "Empty", path = "e"),
                view(title = "Full", path = "f", cards = listOf("""{"type":"markdown","content":"x"}""")),
            ),
        )
        assertThat(pages.map { it.name }).containsExactly("FULL")
    }
}
