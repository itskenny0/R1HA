package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelacePolishParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `appends a section footer card after the section cards`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """
                {"views":[{"path":"p","sections":[
                  {"type":"grid",
                   "cards":[{"type":"markdown","content":"body"}],
                   "footer":{"type":"markdown","content":"footer"}}
                ]}]}
                """.trimIndent(),
            ) as JsonObject,
        )
        val cards = cfg.views.single().cards
        assertThat(cards).hasSize(2)
        assertThat((cards[0] as LovelaceCard.Markdown).content).isEqualTo("body")
        assertThat((cards[1] as LovelaceCard.Markdown).content).isEqualTo("footer")
    }

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

    @Test fun `badge display_type complete shows name, minimal hides state`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """
                {"views":[{"path":"p","badges":[
                  {"entity":"sensor.a","display_type":"complete"},
                  {"entity":"sensor.b","display_type":"minimal"},
                  {"entity":"sensor.c","display_type":"standard"}
                ]}]}
                """.trimIndent(),
            ) as JsonObject,
        )
        val badges = cfg.views.single().badges
        // complete -> name on (state stays on by default)
        assertThat(badges[0].showName).isTrue()
        assertThat(badges[0].showState).isTrue()
        // minimal -> state off (name stays off by default)
        assertThat(badges[1].showState).isFalse()
        assertThat(badges[1].showName).isFalse()
        // standard -> defaults: name off, state on
        assertThat(badges[2].showName).isFalse()
        assertThat(badges[2].showState).isTrue()
    }

    @Test fun `explicit show_name overrides display_type complete`() {
        val cfg = LovelaceParser.parseConfig(
            Json.parseToJsonElement(
                """{"views":[{"path":"p","badges":[
                  {"entity":"sensor.a","display_type":"complete","show_name":false}
                ]}]}""".trimIndent(),
            ) as JsonObject,
        )
        assertThat(cfg.views.single().badges[0].showName).isFalse()
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
