package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelaceNewCardsParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `parses a shortcut card with explicit name icon color and navigate action`() {
        val c = card(
            """
            {"type":"shortcut","name":"Lights","icon":"mdi:lightbulb","color":"amber",
             "tap_action":{"action":"navigate","navigation_path":"/lovelace/lights"}}
            """.trimIndent(),
        ) as LovelaceCard.Shortcut
        assertThat(c.name).isEqualTo("Lights")
        assertThat(c.icon).isEqualTo("mdi:lightbulb")
        assertThat(c.color).isEqualTo("amber")
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
    }

    @Test fun `shortcut card with no name keeps a null name for render-time resolution`() {
        val c = card(
            """{"type":"shortcut","tap_action":{"action":"url","url":"https://example.com"}}""",
        ) as LovelaceCard.Shortcut
        assertThat(c.name).isNull()
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Url::class.java)
    }

    @Test fun `parses a distribution card with string and object entities`() {
        val c = card(
            """
            {"type":"distribution","title":"Power split","entities":[
              "sensor.fridge",
              {"entity":"sensor.oven","name":"Oven","color":"red"}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.Distribution
        assertThat(c.title).isEqualTo("Power split")
        assertThat(c.entries).hasSize(2)
        assertThat(c.entries[0].entityId).isEqualTo("sensor.fridge")
        assertThat(c.entries[0].name).isNull()
        assertThat(c.entries[1].entityId).isEqualTo("sensor.oven")
        assertThat(c.entries[1].name).isEqualTo("Oven")
        assertThat(c.entries[1].color).isEqualTo("red")
    }

    @Test fun `parses a shortcut badge in a view badges array`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """
                {"views":[{"path":"p","badges":[
                  {"type":"shortcut","name":"Assist","icon":"mdi:microphone",
                   "tap_action":{"action":"navigate","navigation_path":"/assist"}}
                ],"cards":[]}]}
                """.trimIndent(),
            ) as JsonObject,
        )
        val badge = cfg.views.first().badges.single()
        assertThat(badge.entityId).isNull()
        assertThat(badge.name).isEqualTo("Assist")
        assertThat(badge.icon).isEqualTo("mdi:microphone")
        assertThat(badge.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
    }
}
