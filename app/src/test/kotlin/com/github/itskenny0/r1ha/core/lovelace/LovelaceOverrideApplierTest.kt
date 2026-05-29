package com.github.itskenny0.r1ha.core.lovelace

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LovelaceOverrideApplierTest {

    private fun mdCard(text: String): LovelaceCard {
        val raw = Json.parseToJsonElement("""{"type":"markdown","content":"$text"}""") as JsonObject
        return LovelaceParser.parseCard(raw)
    }

    private fun heading(text: String): LovelaceCard {
        val raw = Json.parseToJsonElement("""{"type":"heading","heading":"$text"}""") as JsonObject
        return LovelaceParser.parseCard(raw)
    }

    @Test fun `empty override returns original list`() {
        val cards = listOf(mdCard("A"), mdCard("B"))
        val result = LovelaceOverrideApplier.apply(cards, ViewOverride())
        assertEquals(cards, result)
    }

    @Test fun `replace swaps the card at the original index`() {
        val cards = listOf(mdCard("A"), mdCard("B"), mdCard("C"))
        val replacementRaw = Json.parseToJsonElement("""{"type":"heading","heading":"NEW"}""") as JsonObject
        val ov = ViewOverride(operations = listOf(OverrideOp.Replace(index = 1, json = replacementRaw.toString())))
        val result = LovelaceOverrideApplier.apply(cards, ov)
        assertEquals(3, result.size)
        val replaced = result[1] as LovelaceCard.Heading
        assertEquals("NEW", replaced.heading)
    }

    @Test fun `delete removes the card and reorders downstream stay stable`() {
        val cards = listOf(mdCard("A"), mdCard("B"), mdCard("C"))
        val ov = ViewOverride(operations = listOf(OverrideOp.Delete(index = 1)))
        val result = LovelaceOverrideApplier.apply(cards, ov)
        assertEquals(2, result.size)
        assertEquals("A", (result[0] as LovelaceCard.Markdown).content)
        assertEquals("C", (result[1] as LovelaceCard.Markdown).content)
    }

    @Test fun `reorder moves a card to the requested slot`() {
        val cards = listOf(mdCard("A"), mdCard("B"), mdCard("C"))
        val ov = ViewOverride(operations = listOf(OverrideOp.Reorder(fromIndex = 0, toIndex = 2)))
        val result = LovelaceOverrideApplier.apply(cards, ov)
        assertEquals(listOf("B", "C", "A"), result.map { (it as LovelaceCard.Markdown).content })
    }

    @Test fun `append adds new cards to the end`() {
        val cards = listOf(mdCard("A"))
        val newRaw = Json.parseToJsonElement("""{"type":"heading","heading":"X"}""") as JsonObject
        val ov = ViewOverride(operations = listOf(OverrideOp.Append(json = newRaw.toString())))
        val result = LovelaceOverrideApplier.apply(cards, ov)
        assertEquals(2, result.size)
        assertEquals("X", (result[1] as LovelaceCard.Heading).heading)
    }

    @Test fun `combined ops apply in the documented order replace then delete then reorder then append`() {
        val cards = listOf(mdCard("A"), mdCard("B"), mdCard("C"))
        val replacement = Json.parseToJsonElement("""{"type":"heading","heading":"Z"}""") as JsonObject
        val appended = Json.parseToJsonElement("""{"type":"markdown","content":"D"}""") as JsonObject
        val ov = ViewOverride(
            operations = listOf(
                OverrideOp.Replace(index = 0, json = replacement.toString()),
                OverrideOp.Delete(index = 2),
                OverrideOp.Reorder(fromIndex = 0, toIndex = 1),
                OverrideOp.Append(json = appended.toString()),
            ),
        )
        val result = LovelaceOverrideApplier.apply(cards, ov)
        // Steps:
        // - After Replace: [Heading(Z), B, C]
        // - After Delete:  [Heading(Z), B]
        // - After Reorder: [B, Heading(Z)]
        // - After Append:  [B, Heading(Z), D]
        assertEquals(3, result.size)
        assertEquals("B", (result[0] as LovelaceCard.Markdown).content)
        assertEquals("Z", (result[1] as LovelaceCard.Heading).heading)
        assertEquals("D", (result[2] as LovelaceCard.Markdown).content)
    }

    @Test fun `out of range indices are ignored without crashing`() {
        val cards = listOf(mdCard("A"))
        val ov = ViewOverride(
            operations = listOf(
                OverrideOp.Delete(index = 99),
                OverrideOp.Replace(index = 99, json = "{}"),
                OverrideOp.Reorder(fromIndex = 99, toIndex = 0),
            ),
        )
        val result = LovelaceOverrideApplier.apply(cards, ov)
        // Replace at index 99 looks for original-index 99 (not present)
        // so the slot doesn't exist. Delete at 99 is also a no-op. Reorder
        // from 99 coerces to lastIndex (0) but to=0 too so no movement.
        // The applier should still produce the unchanged single card.
        assertEquals(1, result.size)
    }

    @Test fun `renderWithFlags marks replaced + appended cards as overridden`() {
        val cards = listOf(mdCard("A"), mdCard("B"))
        val replacement = Json.parseToJsonElement("""{"type":"heading","heading":"R"}""") as JsonObject
        val appended = Json.parseToJsonElement("""{"type":"markdown","content":"X"}""") as JsonObject
        val ov = ViewOverride(
            operations = listOf(
                OverrideOp.Replace(index = 1, json = replacement.toString()),
                OverrideOp.Append(json = appended.toString()),
            ),
        )
        val rendered = renderWithFlags(cards, ov)
        assertEquals(3, rendered.size)
        // Original-index 0 untouched → not overridden.
        assertEquals(false, rendered[0].isOverridden)
        // Replaced original-index 1 → overridden.
        assertEquals(true, rendered[1].isOverridden)
        // Appended → overridden.
        assertEquals(true, rendered[2].isOverridden)
    }
}
