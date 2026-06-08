package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelacePolishParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `parses heading card badges`() {
        val c = card(
            """
            {"type":"heading","heading":"Living room","icon":"mdi:sofa","badges":[
              {"entity":"sensor.temp","show_state":true},
              {"type":"entity","entity":"light.lr","tap_action":{"action":"toggle"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.Heading
        assertThat(c.heading).isEqualTo("Living room")
        assertThat(c.badges).hasSize(2)
        assertThat(c.badges[0].entityId).isEqualTo("sensor.temp")
    }

    @Test fun `parses markdown tap hold and double-tap actions`() {
        val c = card(
            """
            {"type":"markdown","content":"Hi",
             "tap_action":{"action":"navigate","navigation_path":"/x"},
             "hold_action":{"action":"more-info"},
             "double_tap_action":{"action":"toggle"}}
            """.trimIndent(),
        ) as LovelaceCard.Markdown
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
        assertThat(c.holdAction).isInstanceOf(LovelaceAction.Builtin::class.java)
        assertThat(c.doubleTapAction).isInstanceOf(LovelaceAction.Builtin::class.java)
    }
}
