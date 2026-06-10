package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser coverage for the energy card family (Batch P): the date-selection host
 * and every `energy-*` / `*-sankey` card type mapping to its [EnergyCardKind],
 * plus the collection-key seam.
 */
class LovelaceEnergyCardParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `energy-date-selection parses with explicit collection key`() {
        val c = card("""{"type":"energy-date-selection","collection_key":"energy_main"}""")
            as LovelaceCard.EnergyDateSelection
        assertThat(c.collectionKey).isEqualTo("energy_main")
    }

    @Test fun `energy-usage-graph maps to usage kind`() {
        val c = card("""{"type":"energy-usage-graph","title":"Usage"}""") as LovelaceCard.Energy
        assertThat(c.kind).isEqualTo(EnergyCardKind.USAGE_GRAPH)
        assertThat(c.title).isEqualTo("Usage")
    }

    @Test fun `every energy card type maps to a kind`() {
        EnergyCardKind.entries.forEach { kind ->
            val c = card("""{"type":"${kind.haType}"}""")
            assertThat(c).isInstanceOf(LovelaceCard.Energy::class.java)
            assertThat((c as LovelaceCard.Energy).kind).isEqualTo(kind)
        }
    }

    @Test fun `sankey variants all parse as energy cards`() {
        listOf("energy-sankey", "power-sankey", "water-sankey", "water-flow-sankey").forEach { type ->
            assertThat(card("""{"type":"$type"}""")).isInstanceOf(LovelaceCard.Energy::class.java)
        }
    }

    @Test fun `energy card collection key defaults to null when unset`() {
        val c = card("""{"type":"energy-distribution"}""") as LovelaceCard.Energy
        assertThat(c.collectionKey).isNull()
    }

    @Test fun `energy_date_selection boolean resolves to default key`() {
        // HA's energy_date_selection:true (no explicit key) uses the default key.
        val c = card("""{"type":"statistics-graph","entities":["sensor.a"],"energy_date_selection":true}""")
            as LovelaceCard.StatisticsGraph
        assertThat(c.collectionKey).isEqualTo("energy_date_selection")
    }
}
