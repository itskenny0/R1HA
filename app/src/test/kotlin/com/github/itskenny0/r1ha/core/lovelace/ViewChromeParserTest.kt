package com.github.itskenny0.r1ha.core.lovelace

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/**
 * Parser coverage for Batch M (views / sections / dashboard chrome): the view
 * `header:` / `footer:` slots, sections-view `sidebar:`, section
 * spans/disabled/background, per-user `visible:`, `background:`, subview
 * `back_path`, `show_icon_and_title`, and the dashboard-level background.
 */
class ViewChromeParserTest {

    private fun config(raw: String): LovelaceConfig =
        LovelaceParser.parseConfig(Json.parseToJsonElement(raw) as JsonObject)

    private fun view(raw: String): LovelaceView =
        config("""{"views":[$raw]}""").views.single()

    // ── header ────────────────────────────────────────────────────────────────

    @Test fun `view header parses card and badge placement`() {
        val v = view(
            """{"path":"a","header":{"layout":"start","badges_position":"top",
               "badges_wrap":"scroll","card":{"type":"markdown","content":"hi"}}}""",
        )
        val h = v.header!!
        assertThat(h.layout).isEqualTo("start")
        assertThat(h.badgesPosition).isEqualTo("top")
        assertThat(h.badgesWrap).isEqualTo("scroll")
        assertThat(h.card).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `header card is not folded into the flat cards list`() {
        // The header card renders explicitly so its badge placement is honoured;
        // it must NOT also appear as a regular card.
        val v = view(
            """{"path":"a","header":{"card":{"type":"markdown","content":"hi"}},
               "cards":[{"type":"button","entity":"light.x"}]}""",
        )
        assertThat(v.cards).hasSize(1)
        assertThat(v.cards.single()).isInstanceOf(LovelaceCard.Button::class.java)
        assertThat(v.header!!.card).isInstanceOf(LovelaceCard.Markdown::class.java)
    }

    @Test fun `an inert header (no card, no options) is dropped`() {
        val v = view("""{"path":"a","header":{}}""")
        assertThat(v.header).isNull()
    }

    // ── footer ──────────────────────────────────────────────────────────────

    @Test fun `view footer parses card and max_width but stays out of flat cards`() {
        val v = view(
            """{"path":"a","footer":{"max_width":400,"card":{"type":"markdown","content":"f"}},
               "cards":[{"type":"button","entity":"light.x"}]}""",
        )
        assertThat(v.footer!!.maxWidth).isEqualTo(400)
        assertThat(v.footer!!.card).isInstanceOf(LovelaceCard.Markdown::class.java)
        assertThat(v.cards).hasSize(1)
    }

    @Test fun `a footer with no card is dropped`() {
        val v = view("""{"path":"a","footer":{"max_width":400}}""")
        assertThat(v.footer).isNull()
    }

    // ── sections + spans + disabled ──────────────────────────────────────────

    @Test fun `sections flatten into reading order and carry span keys`() {
        val v = view(
            """{"path":"a","sections":[
               {"column_span":2,"row_span":1,"cards":[{"type":"button","entity":"light.a"}]},
               {"cards":[{"type":"button","entity":"light.b"}]}]}""",
        )
        assertThat(v.sections).hasSize(2)
        assertThat(v.sections[0].columnSpan).isEqualTo(2)
        assertThat(v.sections[0].rowSpan).isEqualTo(1)
        // Flatten preserves declaration order on the single column.
        assertThat(v.cards).hasSize(2)
    }

    @Test fun `a disabled section is dropped from the flat cards`() {
        val v = view(
            """{"path":"a","sections":[
               {"disabled":true,"cards":[{"type":"button","entity":"light.a"}]},
               {"cards":[{"type":"button","entity":"light.b"}]}]}""",
        )
        assertThat(v.sections).hasSize(2)
        assertThat(v.sections[0].disabled).isTrue()
        // Only the enabled section's card survives the flatten.
        assertThat(v.cards).hasSize(1)
    }

    @Test fun `section visibility gate is pushed onto each section card`() {
        val v = view(
            """{"path":"a","sections":[
               {"visibility":[{"condition":"state","entity":"light.a","state":"on"}],
                "cards":[{"type":"button","entity":"light.b"}]}]}""",
        )
        // The flattened card is wrapped in a conditional so the section gate
        // survives the single-column flatten.
        assertThat(v.cards.single()).isInstanceOf(LovelaceCard.Conditional::class.java)
    }

    @Test fun `section background true resolves to a default-opacity surface`() {
        val v = view(
            """{"path":"a","sections":[{"background":true,"cards":[{"type":"button","entity":"light.a"}]}]}""",
        )
        assertThat(v.sections.single().background).isEqualTo(LovelaceSectionBackground())
    }

    @Test fun `section background object parses color and opacity`() {
        val v = view(
            """{"path":"a","sections":[{"background":{"color":"#ff0000","opacity":30},
               "cards":[{"type":"button","entity":"light.a"}]}]}""",
        )
        val bg = v.sections.single().background!!
        assertThat(bg.color).isEqualTo("#ff0000")
        assertThat(bg.opacity).isEqualTo(30)
    }

    @Test fun `section background false resolves to none`() {
        val v = view(
            """{"path":"a","sections":[{"background":false,"cards":[{"type":"button","entity":"light.a"}]}]}""",
        )
        assertThat(v.sections.single().background).isNull()
    }

    // ── sidebar ──────────────────────────────────────────────────────────────

    @Test fun `sidebar sections flatten after the main sections`() {
        val v = view(
            """{"path":"a",
               "sections":[{"cards":[{"type":"button","entity":"light.main"}]}],
               "sidebar":{"sidebar_label":"More",
                 "sections":[{"cards":[{"type":"button","entity":"light.side"}]}]}}""",
        )
        assertThat(v.sidebar!!.sidebarLabel).isEqualTo("More")
        assertThat(v.sidebar!!.sections).hasSize(1)
        // Both the main and sidebar section cards land in the flat list.
        assertThat(v.cards).hasSize(2)
    }

    @Test fun `sidebar visibility conditions are parsed`() {
        val v = view(
            """{"path":"a","sidebar":{
               "visibility":[{"condition":"state","entity":"light.a","state":"on"}],
               "sections":[{"cards":[{"type":"button","entity":"light.side"}]}]}}""",
        )
        assertThat(v.sidebar!!.visibility).hasSize(1)
    }

    // ── visibility ──────────────────────────────────────────────────────────

    @Test fun `visible false parses to AlwaysHidden`() {
        val v = view("""{"path":"a","visible":false}""")
        assertThat(v.visible).isEqualTo(ViewVisibility.AlwaysHidden)
    }

    @Test fun `visible true parses to null (always visible)`() {
        val v = view("""{"path":"a","visible":true}""")
        assertThat(v.visible).isNull()
    }

    @Test fun `visible user list parses to a Users set`() {
        val v = view("""{"path":"a","visible":[{"user":"u1"},{"user":"u2"}]}""")
        assertThat(v.visible).isEqualTo(ViewVisibility.Users(setOf("u1", "u2")))
    }

    // ── background ──────────────────────────────────────────────────────────

    @Test fun `view background object parses all keys`() {
        val v = view(
            """{"path":"a","background":{"image":"/local/bg.png","opacity":40,
               "size":"cover","alignment":"top left","repeat":"no-repeat","attachment":"fixed"}}""",
        )
        val bg = v.background!!
        assertThat(bg.image).isEqualTo("/local/bg.png")
        assertThat(bg.opacity).isEqualTo(40)
        assertThat(bg.size).isEqualTo("cover")
        assertThat(bg.alignment).isEqualTo("top left")
        assertThat(bg.repeat).isEqualTo("no-repeat")
        assertThat(bg.attachment).isEqualTo("fixed")
    }

    @Test fun `bare-string url background becomes the image source`() {
        val v = view("""{"path":"a","background":"https://example.com/a.jpg"}""")
        assertThat(v.background!!.image).isEqualTo("https://example.com/a.jpg")
    }

    @Test fun `bare-string gradient background stays in rawString, not image`() {
        val v = view("""{"path":"a","background":"linear-gradient(to right, red, blue)"}""")
        assertThat(v.background!!.image).isNull()
        assertThat(v.background!!.rawString).isEqualTo("linear-gradient(to right, red, blue)")
    }

    @Test fun `dashboard-level background is parsed on the config root`() {
        val c = config("""{"background":"/local/dash.png","views":[{"path":"a"}]}""")
        assertThat(c.background!!.image).isEqualTo("/local/dash.png")
    }

    // ── addressing + indicators ──────────────────────────────────────────────

    @Test fun `subview back_path and show_icon_and_title parse`() {
        val v = view("""{"path":"a","subview":true,"back_path":"home","icon":"mdi:x","show_icon_and_title":true}""")
        assertThat(v.subview).isTrue()
        assertThat(v.backPath).isEqualTo("home")
        assertThat(v.showIconAndTitle).isTrue()
    }

    @Test fun `path defaults to the numeric index when omitted`() {
        val c = config("""{"views":[{"title":"First"},{"title":"Second"}]}""")
        assertThat(c.views[0].path).isEqualTo("0")
        assertThat(c.views[1].path).isEqualTo("1")
    }

    @Test fun `sections-view layout keys parse`() {
        val v = view(
            """{"path":"a","max_columns":3,"dense_section_placement":true,"top_margin":true,
               "theme":"midnight","sections":[{"cards":[{"type":"button","entity":"light.a"}]}]}""",
        )
        assertThat(v.maxColumns).isEqualTo(3)
        assertThat(v.denseSectionPlacement).isTrue()
        assertThat(v.topMargin).isTrue()
        assertThat(v.theme).isEqualTo("midnight")
    }
}
