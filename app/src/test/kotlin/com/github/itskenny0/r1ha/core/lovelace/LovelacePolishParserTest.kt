package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelacePolishParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

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
