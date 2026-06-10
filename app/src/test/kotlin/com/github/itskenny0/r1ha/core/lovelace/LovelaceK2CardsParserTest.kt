package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser coverage for Batch K2 card types: calendar, home-summary, updates,
 * repairs, empty-state, and the shortcut label/description/vertical additions.
 */
class LovelaceK2CardsParserTest {

    private fun card(raw: String): LovelaceCard =
        LovelaceParser.parseCard(Json.parseToJsonElement(raw) as JsonObject)

    // ── shortcut: label / description / vertical (2026.5 additions) ──────────

    @Test fun `shortcut parses label taking precedence over name`() {
        val c = card(
            """{"type":"shortcut","name":"Old","label":"New Label","icon":"mdi:home"}""",
        ) as LovelaceCard.Shortcut
        assertThat(c.label).isEqualTo("New Label")
        assertThat(c.name).isEqualTo("Old")
        // displayLabel resolves label first
        assertThat(c.displayLabel).isEqualTo("New Label")
    }

    @Test fun `shortcut displayLabel falls back to name when label absent`() {
        val c = card(
            """{"type":"shortcut","name":"My Shortcut","icon":"mdi:home"}""",
        ) as LovelaceCard.Shortcut
        assertThat(c.label).isNull()
        assertThat(c.displayLabel).isEqualTo("My Shortcut")
    }

    @Test fun `shortcut displayLabel is null when both label and name absent`() {
        val c = card("""{"type":"shortcut","icon":"mdi:home"}""") as LovelaceCard.Shortcut
        assertThat(c.displayLabel).isNull()
    }

    @Test fun `shortcut parses description`() {
        val c = card(
            """{"type":"shortcut","name":"A","description":"Opens lights panel"}""",
        ) as LovelaceCard.Shortcut
        assertThat(c.description).isEqualTo("Opens lights panel")
    }

    @Test fun `shortcut description defaults to null`() {
        val c = card("""{"type":"shortcut","name":"A"}""") as LovelaceCard.Shortcut
        assertThat(c.description).isNull()
    }

    @Test fun `shortcut parses vertical true`() {
        val c = card("""{"type":"shortcut","name":"A","vertical":true}""") as LovelaceCard.Shortcut
        assertThat(c.vertical).isTrue()
    }

    @Test fun `shortcut vertical defaults to false`() {
        val c = card("""{"type":"shortcut","name":"A"}""") as LovelaceCard.Shortcut
        assertThat(c.vertical).isFalse()
    }

    @Test fun `shortcut parses hold_action and double_tap_action`() {
        val c = card(
            """{"type":"shortcut","name":"A",
               "hold_action":{"action":"more-info"},
               "double_tap_action":{"action":"navigate","navigation_path":"/lights"}}""",
        ) as LovelaceCard.Shortcut
        assertThat(c.holdAction).isInstanceOf(LovelaceAction.Builtin::class.java)
        assertThat((c.holdAction as LovelaceAction.Builtin).name).isEqualTo("more-info")
        assertThat(c.doubleTapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
    }

    // ── calendar ─────────────────────────────────────────────────────────────

    @Test fun `parses calendar card with string entity list`() {
        val c = card(
            """{"type":"calendar","entities":["calendar.work","calendar.home"]}""",
        ) as LovelaceCard.Calendar
        assertThat(c.entityIds).containsExactly("calendar.work", "calendar.home").inOrder()
        assertThat(c.title).isNull()
        assertThat(c.initialView).isNull()
    }

    @Test fun `parses calendar card with object entity list`() {
        val c = card(
            """{"type":"calendar","entities":[{"entity":"calendar.work","color":"blue"}]}""",
        ) as LovelaceCard.Calendar
        assertThat(c.entityIds).containsExactly("calendar.work")
    }

    @Test fun `parses calendar card with title and initial_view`() {
        val c = card(
            """{"type":"calendar","title":"My Cal","initial_view":"listWeek",
               "entities":["calendar.work"]}""",
        ) as LovelaceCard.Calendar
        assertThat(c.title).isEqualTo("My Cal")
        assertThat(c.initialView).isEqualTo("listWeek")
    }

    @Test fun `calendar card without entities degrades to Unsupported`() {
        val c = card("""{"type":"calendar","title":"Empty"}""")
        assertThat(c).isInstanceOf(LovelaceCard.Unsupported::class.java)
    }

    @Test fun `calendar card with empty entities array degrades to Unsupported`() {
        val c = card("""{"type":"calendar","entities":[]}""")
        assertThat(c).isInstanceOf(LovelaceCard.Unsupported::class.java)
    }

    // ── home-summary ─────────────────────────────────────────────────────────

    @Test fun `parses home-summary card for all seven known summaries`() {
        for (summary in listOf("light", "climate", "security", "media_players", "maintenance", "energy", "persons")) {
            val c = card("""{"type":"home-summary","summary":"$summary"}""") as LovelaceCard.HomeSummary
            assertThat(c.summary).isEqualTo(summary)
        }
    }

    @Test fun `home-summary normalises summary to lowercase`() {
        val c = card("""{"type":"home-summary","summary":"Light"}""") as LovelaceCard.HomeSummary
        assertThat(c.summary).isEqualTo("light")
    }

    @Test fun `home-summary parses vertical flag`() {
        val c = card("""{"type":"home-summary","summary":"light","vertical":true}""") as LovelaceCard.HomeSummary
        assertThat(c.vertical).isTrue()
    }

    @Test fun `home-summary vertical defaults to false`() {
        val c = card("""{"type":"home-summary","summary":"light"}""") as LovelaceCard.HomeSummary
        assertThat(c.vertical).isFalse()
    }

    @Test fun `home-summary without summary degrades to Unsupported`() {
        val c = card("""{"type":"home-summary"}""")
        assertThat(c).isInstanceOf(LovelaceCard.Unsupported::class.java)
    }

    @Test fun `home-summary parses tap_action`() {
        val c = card(
            """{"type":"home-summary","summary":"light",
               "tap_action":{"action":"navigate","navigation_path":"/lights"}}""",
        ) as LovelaceCard.HomeSummary
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
        assertThat((c.tapAction as LovelaceAction.Navigate).path).isEqualTo("/lights")
    }

    // ── updates ──────────────────────────────────────────────────────────────

    @Test fun `parses updates card with defaults`() {
        val c = card("""{"type":"updates"}""") as LovelaceCard.Updates
        assertThat(c.hideEmpty).isFalse()
        assertThat(c.vertical).isFalse()
        assertThat(c.tapAction).isNull()
    }

    @Test fun `parses updates card with hide_empty and vertical`() {
        val c = card("""{"type":"updates","hide_empty":true,"vertical":true}""") as LovelaceCard.Updates
        assertThat(c.hideEmpty).isTrue()
        assertThat(c.vertical).isTrue()
    }

    @Test fun `parses updates card with tap_action`() {
        val c = card(
            """{"type":"updates","tap_action":{"action":"navigate","navigation_path":"/updates"}}""",
        ) as LovelaceCard.Updates
        assertThat(c.tapAction).isInstanceOf(LovelaceAction.Navigate::class.java)
    }

    // ── repairs ──────────────────────────────────────────────────────────────

    @Test fun `parses repairs card with defaults`() {
        val c = card("""{"type":"repairs"}""") as LovelaceCard.Repairs
        assertThat(c.hideEmpty).isFalse()
        assertThat(c.vertical).isFalse()
        assertThat(c.tapAction).isNull()
    }

    @Test fun `parses repairs card with hide_empty and vertical`() {
        val c = card("""{"type":"repairs","hide_empty":true,"vertical":true}""") as LovelaceCard.Repairs
        assertThat(c.hideEmpty).isTrue()
        assertThat(c.vertical).isTrue()
    }

    // ── empty-state ───────────────────────────────────────────────────────────

    @Test fun `parses empty-state card with all fields`() {
        val c = card(
            """{"type":"empty-state","title":"No devices","content":"Add a device to get started",
               "icon":"mdi:devices","content_only":true}""",
        ) as LovelaceCard.EmptyState
        assertThat(c.title).isEqualTo("No devices")
        assertThat(c.content).isEqualTo("Add a device to get started")
        assertThat(c.icon).isEqualTo("mdi:devices")
        assertThat(c.contentOnly).isTrue()
    }

    @Test fun `parses empty-state card with minimal fields`() {
        val c = card("""{"type":"empty-state"}""") as LovelaceCard.EmptyState
        assertThat(c.title).isNull()
        assertThat(c.content).isNull()
        assertThat(c.icon).isNull()
        assertThat(c.contentOnly).isFalse()
    }
}
