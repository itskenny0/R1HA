package com.github.itskenny0.r1ha.core.lovelace

import com.github.itskenny0.r1ha.feature.dashboards.cards.EntityStates
import com.github.itskenny0.r1ha.feature.dashboards.cards.collectEntityIds
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

class LovelacePictureElementsParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    @Test fun `parses a minimal picture-elements card with static image`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/floorplan.png","elements":[]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.image).isEqualTo("/local/floorplan.png")
        assertThat(c.cameraImage).isNull()
        assertThat(c.elements).isEmpty()
    }

    @Test fun `parses a picture-elements card with camera_image`() {
        val c = card(
            """
            {"type":"picture-elements","camera_image":"camera.hallway","elements":[]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.image).isNull()
        assertThat(c.cameraImage).isEqualTo("camera.hallway")
    }

    @Test fun `parses all supported element types`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/map.png","elements":[
              {"type":"state-badge","entity":"light.living","style":{"top":"10%","left":"20%"}},
              {"type":"state-icon","entity":"switch.fan","style":{"top":"30%","left":"40%"}},
              {"type":"state-label","entity":"sensor.temp","style":{"top":"50%","left":"60%"}},
              {"type":"icon","icon":"mdi:thermometer","style":{"top":"70%","left":"80%"}},
              {"type":"image","entity":"camera.back","style":{"top":"90%","left":"15%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.elements).hasSize(5)
        assertThat(c.elements[0].type).isEqualTo("state-badge")
        assertThat(c.elements[1].type).isEqualTo("state-icon")
        assertThat(c.elements[2].type).isEqualTo("state-label")
        assertThat(c.elements[3].type).isEqualTo("icon")
        assertThat(c.elements[4].type).isEqualTo("image")
    }

    @Test fun `parses top and left percentages stripping the percent sign`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-badge","entity":"light.a","style":{"top":"25%","left":"75%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.topPct).isEqualTo(25.0)
        assertThat(el.leftPct).isEqualTo(75.0)
    }

    @Test fun `parses top and left as plain numbers without percent sign`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-icon","entity":"light.b","style":{"top":"33","left":"66"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.topPct).isEqualTo(33.0)
        assertThat(el.leftPct).isEqualTo(66.0)
    }

    @Test fun `defaults top and left to 50 when style is missing`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-label","entity":"sensor.x"}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.topPct).isEqualTo(50.0)
        assertThat(el.leftPct).isEqualTo(50.0)
    }

    @Test fun `defaults to 50 when top or left value is unparseable`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"icon","icon":"mdi:star","style":{"top":"bad%","left":"???"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.topPct).isEqualTo(50.0)
        assertThat(el.leftPct).isEqualTo(50.0)
    }

    @Test fun `parses tap_action on an element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-badge","entity":"light.desk",
               "tap_action":{"action":"toggle"},
               "style":{"top":"10%","left":"10%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        val action = el.tapAction as LovelaceAction.Builtin
        assertThat(action.name).isEqualTo("toggle")
    }

    @Test fun `parses navigate tap_action on an element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"icon","icon":"mdi:home",
               "tap_action":{"action":"navigate","navigation_path":"/lovelace/home"},
               "style":{"top":"5%","left":"5%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        val action = el.tapAction as LovelaceAction.Navigate
        assertThat(action.path).isEqualTo("/lovelace/home")
    }

    @Test fun `skips an element with no type`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"entity":"light.a","style":{"top":"10%","left":"10%"}},
              {"type":"state-badge","entity":"light.b","style":{"top":"20%","left":"20%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.elements).hasSize(1)
        assertThat(c.elements[0].entityId).isEqualTo("light.b")
    }

    @Test fun `skips an unknown element type gracefully`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"service-button","title":"Refresh","service":"homeassistant.update_entity","style":{"top":"5%","left":"5%"}},
              {"type":"state-label","entity":"sensor.co2","style":{"top":"15%","left":"15%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        // service-button is not in our supported set, so only state-label should remain
        assertThat(c.elements).hasSize(1)
        assertThat(c.elements[0].type).isEqualTo("state-label")
    }

    @Test fun `parses prefix and suffix on state-label element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-label","entity":"sensor.temp","prefix":"T:","suffix":"°C","style":{"top":"50%","left":"50%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.prefix).isEqualTo("T:")
        assertThat(el.suffix).isEqualTo("°C")
    }

    @Test fun `parses attribute on state-label element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-label","entity":"climate.living","attribute":"current_temperature","style":{"top":"50%","left":"50%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.attribute).isEqualTo("current_temperature")
    }

    @Test fun `parses image element with image url`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/bg.png","elements":[
              {"type":"image","image":"/local/overlay.png","style":{"top":"10%","left":"10%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val el = c.elements.single()
        assertThat(el.image).isEqualTo("/local/overlay.png")
        assertThat(el.entityId).isNull()
    }

    @Test fun `collectEntityIds includes element entities and cameraImage`() {
        val c = card(
            """
            {"type":"picture-elements","camera_image":"camera.hallway","elements":[
              {"type":"state-badge","entity":"light.living","style":{"top":"10%","left":"20%"}},
              {"type":"state-icon","entity":"switch.fan","style":{"top":"30%","left":"40%"}},
              {"type":"icon","icon":"mdi:star","style":{"top":"5%","left":"5%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val ids = mutableSetOf<String>()
        collectEntityIds(c, ids)
        assertThat(ids).containsExactly("camera.hallway", "light.living", "switch.fan")
    }

    @Test fun `collectEntityIds with no cameraImage only collects element entities`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/bg.png","elements":[
              {"type":"state-label","entity":"sensor.temp","style":{"top":"50%","left":"50%"}},
              {"type":"image","image":"/local/overlay.png","style":{"top":"10%","left":"10%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        val ids = mutableSetOf<String>()
        collectEntityIds(c, ids)
        assertThat(ids).containsExactly("sensor.temp")
    }

    @Test fun `parses name on element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-badge","entity":"light.kitchen","name":"Kitchen Light","style":{"top":"10%","left":"10%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.elements.single().name).isEqualTo("Kitchen Light")
    }

    @Test fun `parses icon on state-icon element`() {
        val c = card(
            """
            {"type":"picture-elements","image":"/local/x.png","elements":[
              {"type":"state-icon","entity":"light.a","icon":"mdi:lightbulb","style":{"top":"10%","left":"10%"}}
            ]}
            """.trimIndent(),
        ) as LovelaceCard.PictureElements
        assertThat(c.elements.single().icon).isEqualTo("mdi:lightbulb")
    }
}
