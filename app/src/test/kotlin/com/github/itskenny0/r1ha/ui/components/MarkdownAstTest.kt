package com.github.itskenny0.r1ha.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Unit tests for the pure Markdown tokenizer + AST builder in MarkdownAst.kt.
 * No Android / Compose dependencies, so these run on the plain JVM source set.
 * Each test asserts the produced node shape for one syntax feature plus the
 * HA-specific alert / icon / qr extensions.
 */
class MarkdownAstTest {

    @Test
    fun `headings carry their level`() {
        val nodes = parseMarkdown("# One\n## Two\n### Three")
        assertThat(nodes).hasSize(3)
        assertThat((nodes[0] as MarkdownNode.Heading).level).isEqualTo(1)
        assertThat((nodes[1] as MarkdownNode.Heading).level).isEqualTo(2)
        assertThat((nodes[2] as MarkdownNode.Heading).level).isEqualTo(3)
    }

    @Test
    fun `paragraph folds wrapped lines`() {
        val nodes = parseMarkdown("hello\nworld\n\nnext")
        assertThat(nodes).hasSize(2)
        assertThat(nodes[0]).isInstanceOf(MarkdownNode.Paragraph::class.java)
        assertThat(nodes[1]).isInstanceOf(MarkdownNode.Paragraph::class.java)
    }

    @Test
    fun `inline bold italic strike and code`() {
        val inlines = parseInline("a **b** _c_ ~~d~~ `e`")
        val styled = inlines.filterIsInstance<MarkdownInline.Styled>()
        assertThat(styled.first { it.text == "b" }.bold).isTrue()
        assertThat(styled.first { it.text == "c" }.italic).isTrue()
        assertThat(styled.first { it.text == "d" }.strike).isTrue()
        assertThat(styled.first { it.text == "e" }.code).isTrue()
    }

    @Test
    fun `unpaired emphasis stays literal`() {
        val inlines = parseInline("a * b")
        assertThat(inlines).hasSize(1)
        assertThat((inlines[0] as MarkdownInline.Text).text).isEqualTo("a * b")
    }

    @Test
    fun `link captures label and url`() {
        val inlines = parseInline("see [docs](https://x.test) now")
        val link = inlines.filterIsInstance<MarkdownInline.Link>().single()
        assertThat(link.label).isEqualTo("docs")
        assertThat(link.url).isEqualTo("https://x.test")
    }

    @Test
    fun `image distinguished from link`() {
        val inlines = parseInline("![alt](https://img.test/p.png)")
        val img = inlines.filterIsInstance<MarkdownInline.Image>().single()
        assertThat(img.alt).isEqualTo("alt")
        assertThat(img.src).isEqualTo("https://img.test/p.png")
    }

    @Test
    fun `bullet list items`() {
        val nodes = parseMarkdown("- one\n- two\n- three")
        val list = nodes.single() as MarkdownNode.ListBlock
        assertThat(list.ordered).isFalse()
        assertThat(list.items).hasSize(3)
    }

    @Test
    fun `ordered list keeps ordinals`() {
        val nodes = parseMarkdown("1. one\n2. two")
        val list = nodes.single() as MarkdownNode.ListBlock
        assertThat(list.ordered).isTrue()
        assertThat(list.items[0].ordinal).isEqualTo(1)
        assertThat(list.items[1].ordinal).isEqualTo(2)
    }

    @Test
    fun `task list checkbox state`() {
        val nodes = parseMarkdown("- [x] done\n- [ ] todo")
        val list = nodes.single() as MarkdownNode.ListBlock
        assertThat(list.items[0].task).isTrue()
        assertThat(list.items[1].task).isFalse()
    }

    @Test
    fun `blockquote becomes quote node`() {
        val nodes = parseMarkdown("> quoted line")
        assertThat(nodes.single()).isInstanceOf(MarkdownNode.BlockQuote::class.java)
    }

    @Test
    fun `github alert promotes blockquote`() {
        val nodes = parseMarkdown("> [!WARNING]\n> be careful")
        val alert = nodes.single() as MarkdownNode.Alert
        assertThat(alert.type).isEqualTo(MarkdownAlertType.WARNING)
    }

    @Test
    fun `github alert note maps to info`() {
        val nodes = parseMarkdown("> [!NOTE]\n> heads up")
        assertThat((nodes.single() as MarkdownNode.Alert).type).isEqualTo(MarkdownAlertType.INFO)
    }

    @Test
    fun `github alert tip maps to success`() {
        val nodes = parseMarkdown("> [!TIP]\n> nice")
        assertThat((nodes.single() as MarkdownNode.Alert).type).isEqualTo(MarkdownAlertType.SUCCESS)
    }

    @Test
    fun `ha-alert element maps to callout`() {
        val nodes = parseMarkdown("<ha-alert alert-type=\"error\">boom</ha-alert>")
        val alert = nodes.single() as MarkdownNode.Alert
        assertThat(alert.type).isEqualTo(MarkdownAlertType.ERROR)
    }

    @Test
    fun `ha-icon element maps to inline icon`() {
        val inlines = parseInline("status <ha-icon icon=\"mdi:lightbulb\"></ha-icon>")
        val icon = inlines.filterIsInstance<MarkdownInline.Icon>().single()
        assertThat(icon.slug).isEqualTo("mdi:lightbulb")
    }

    @Test
    fun `ha-qr-code element becomes qr node`() {
        val nodes = parseMarkdown("<ha-qr-code data=\"abc123\"></ha-qr-code>")
        assertThat((nodes.single() as MarkdownNode.QrCode).data).isEqualTo("abc123")
    }

    @Test
    fun `fenced code block keeps language and body`() {
        val nodes = parseMarkdown("```kotlin\nval x = 1\nval y = 2\n```")
        val code = nodes.single() as MarkdownNode.CodeBlock
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("val x = 1\nval y = 2")
    }

    @Test
    fun `horizontal rule`() {
        val nodes = parseMarkdown("above\n\n---\n\nbelow")
        assertThat(nodes).contains(MarkdownNode.HorizontalRule)
    }

    @Test
    fun `gfm table header and rows`() {
        val src = "| A | B |\n| - | - |\n| 1 | 2 |\n| 3 | 4 |"
        val table = parseMarkdown(src).single() as MarkdownNode.Table
        assertThat(table.header).hasSize(2)
        assertThat(table.rows).hasSize(2)
        val cell = table.rows[0][0].filterIsInstance<MarkdownInline.Text>().single()
        assertThat(cell.text).isEqualTo("1")
    }

    @Test
    fun `static text with no template still parses`() {
        val nodes = parseMarkdown("just text")
        assertThat(nodes).hasSize(1)
        assertThat(nodes[0]).isInstanceOf(MarkdownNode.Paragraph::class.java)
    }
}
