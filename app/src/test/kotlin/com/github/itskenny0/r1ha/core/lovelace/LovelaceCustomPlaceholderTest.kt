package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Verifies that unknown / custom: card types, badge types, tile features, and
 * non-object card config entries all produce visible placeholders rather than
 * vanishing silently.
 */
class LovelaceCustomPlaceholderTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    private fun config(raw: String): LovelaceConfig =
        LovelaceParser.parseConfig(Json.parseToJsonElement(raw) as JsonObject)

    // ── custom: badge placeholder ─────────────────────────────────────────────

    @Test fun `custom badge with no entity emits a placeholder badge named after the type`() {
        val cfg = config(
            """{"views":[{"path":"p","badges":[
              {"type":"custom:my-badge"}
            ],"cards":[]}]}""",
        )
        val badges = cfg.views.single().badges
        assertThat(badges).hasSize(1)
        val b = badges.single()
        assertThat(b.entityId).isNull()
        // Placeholder name is the type with the custom: prefix stripped.
        assertThat(b.name).isEqualTo("my-badge")
        assertThat(b.showState).isFalse()
    }

    @Test fun `custom badge with entity is a normal entity badge regardless of custom type`() {
        val cfg = config(
            """{"views":[{"path":"p","badges":[
              {"type":"custom:my-badge","entity":"sensor.temp"}
            ],"cards":[]}]}""",
        )
        val b = cfg.views.single().badges.single()
        assertThat(b.entityId).isEqualTo("sensor.temp")
    }

    @Test fun `unknown non-custom badge with no entity is still dropped`() {
        // Only custom: types get the placeholder; a plain unknown type with
        // no entity/name is still omitted (HA drops these too).
        val cfg = config(
            """{"views":[{"path":"p","badges":[
              {"type":"some-unknown-badge"}
            ],"cards":[]}]}""",
        )
        assertThat(cfg.views.single().badges).isEmpty()
    }

    @Test fun `custom badge in heading card also gets a placeholder`() {
        val c = card(
            """{"type":"heading","heading":"Area","badges":[
              {"type":"custom:chip-badge"}
            ]}""",
        ) as LovelaceCard.Heading
        assertThat(c.badges).hasSize(1)
        assertThat(c.badges.single().name).isEqualTo("chip-badge")
    }

    // ── custom: / unknown tile feature placeholder ────────────────────────────

    @Test fun `unknown tile feature parses to Unsupported with the type string`() {
        val t = card(
            """{"type":"tile","entity":"switch.pump","features":[
              {"type":"custom:service-call"}
            ]}""",
        ) as LovelaceCard.Tile
        assertThat(t.features).hasSize(1)
        val f = t.features.single()
        assertThat(f).isInstanceOf(LovelaceTileFeature.Unsupported::class.java)
        assertThat((f as LovelaceTileFeature.Unsupported).type).isEqualTo("custom:service-call")
    }

    @Test fun `tile with mixed known and unknown features preserves both`() {
        val t = card(
            """{"type":"tile","entity":"light.hall","features":[
              {"type":"light-brightness"},
              {"type":"custom:unknown-feature"},
              {"type":"light-color-temp"}
            ]}""",
        ) as LovelaceCard.Tile
        assertThat(t.features).hasSize(3)
        assertThat(t.features[0]).isInstanceOf(LovelaceTileFeature.LightBrightness::class.java)
        assertThat(t.features[1]).isInstanceOf(LovelaceTileFeature.Unsupported::class.java)
        assertThat(t.features[2]).isInstanceOf(LovelaceTileFeature.LightColorTemp::class.java)
    }

    // ── non-object card config error ──────────────────────────────────────────

    @Test fun `bare string in view cards array becomes an Unsupported config-error card`() {
        val cfg = config(
            """{"views":[{"path":"p","cards":["not-an-object"]}]}""",
        )
        val cards = cfg.views.single().cards
        assertThat(cards).hasSize(1)
        val c = cards.single() as LovelaceCard.Unsupported
        assertThat(c.type).isEqualTo("(config error)")
        assertThat(c.friendlyType).contains("not-an-object")
    }

    @Test fun `null entry in view cards array is silently dropped`() {
        // JsonNull is an intentional omission; no error card surfaced.
        val cfg = config(
            """{"views":[{"path":"p","cards":[null,{"type":"markdown","content":"hi"}]}]}""",
        )
        val cards = cfg.views.single().cards
        // Only the markdown card survives; the null is dropped.
        assertThat(cards).hasSize(1)
        assertThat(cards.single()).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `bare string in section cards array becomes a config-error card`() {
        val cfg = config(
            """{"views":[{"path":"p","sections":[{"cards":["oops"]}]}]}""",
        )
        val cards = cfg.views.single().cards
        assertThat(cards).hasSize(1)
        val c = cards.single() as LovelaceCard.Unsupported
        assertThat(c.type).isEqualTo("(config error)")
    }

    @Test fun `bare string in vertical-stack cards array becomes a config-error card`() {
        val c = card(
            """{"type":"vertical-stack","cards":["oops",{"type":"markdown","content":"ok"}]}""",
        ) as LovelaceCard.VerticalStack
        assertThat(c.cards).hasSize(2)
        assertThat(c.cards[0]).isInstanceOf(LovelaceCard.Unsupported::class.java)
        assertThat((c.cards[0] as LovelaceCard.Unsupported).type).isEqualTo("(config error)")
        assertThat(c.cards[1]).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    // ── picture-elements unknown type placeholder (verify parser keeps type) ──

    @Test fun `unknown picture-elements type is kept with original type name as label`() {
        val c = card(
            """{"type":"picture-elements","image":"/local/bg.jpg","elements":[
              {"type":"state-badge","entity":"sensor.temp","style":{"top":"10%","left":"20%"}},
              {"type":"custom:my-element","entity":"switch.fan","style":{"top":"50%","left":"50%"}}
            ]}""",
        ) as LovelaceCard.PictureElements
        // Both elements are kept; the custom type is preserved as "unknown" with
        // the original type name stored in the name field for the placeholder label.
        assertThat(c.elements).hasSize(2)
        val unknown = c.elements[1]
        assertThat(unknown.type).isEqualTo("unknown")
        assertThat(unknown.name).isEqualTo("custom:my-element")
    }

    // ── custom: entity row in entities card ───────────────────────────────────

    @Test fun `custom entity row with entity key is kept as a normal entity row`() {
        val c = card(
            """{"type":"entities","entities":[
              {"type":"custom:slider-entity-row","entity":"input_number.volume"}
            ]}""",
        ) as LovelaceCard.Entities
        assertThat(c.rowItems).hasSize(1)
        val item = c.rowItems.single() as EntitiesItem.Entity
        assertThat(item.row.entityId).isEqualTo("input_number.volume")
    }

    @Test fun `custom entity row without entity becomes an Unknown special row`() {
        val c = card(
            """{"type":"entities","entities":[
              {"type":"custom:fold-entity-row","head":{"entity":"sensor.temp"}}
            ]}""",
        ) as LovelaceCard.Entities
        assertThat(c.rowItems).hasSize(1)
        val item = c.rowItems.single() as EntitiesItem.Special
        assertThat(item.row).isInstanceOf(SpecialRow.Unknown::class.java)
        assertThat((item.row as SpecialRow.Unknown).typeName).isEqualTo("custom:fold-entity-row")
    }
}
