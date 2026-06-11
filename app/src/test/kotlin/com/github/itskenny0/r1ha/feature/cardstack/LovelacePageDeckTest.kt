package com.github.itskenny0.r1ha.feature.cardstack

import com.github.itskenny0.r1ha.core.lovelace.LovelaceCard
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

class LovelacePageDeckTest {

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
}
