package com.github.itskenny0.r1ha.ui.components

/**
 * A pure, dependency-free Markdown tokenizer + AST builder. Kept out of the
 * Compose renderer so the parse can be unit-tested with plain assertions on the
 * node shapes. The Compose layer ([MarkdownView]) walks the produced
 * [MarkdownNode] tree and maps each block / inline node onto a widget.
 *
 * The grammar covers what HA users actually paste into a markdown card (which
 * renders CommonMark + GFM via markdown-it): headings, paragraphs, bullet /
 * ordered / task lists, blockquotes, fenced + indented code, tables,
 * horizontal rules, and the inline run of bold / italic / strikethrough /
 * inline-code / links / images. On top of CommonMark we map HA's two markdown
 * extensions natively: GitHub-style `[!NOTE]` / `[!WARNING]` blockquote alerts
 * and the inline `<ha-icon icon="mdi:...">` / `<ha-alert>` / `<ha-qr-code>`
 * custom elements (see ha-markdown-element.ts).
 *
 * This is a from-scratch implementation (no markdown-it / commonmark / markwon
 * dependency) so it stays clean under The Unlicense. It is deliberately a small
 * subset parser, not a spec-complete CommonMark engine: nested emphasis depth is
 * capped at one and reference-style links are not resolved, which matches the
 * scope a 640x480 dashboard card needs.
 */

/** A parsed inline run inside a block. */
sealed interface MarkdownInline {
    /** Plain text with no emphasis. */
    data class Text(val text: String) : MarkdownInline

    /** Emphasis span. [bold] / [italic] / [strike] / [code] compose. */
    data class Styled(
        val text: String,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val strike: Boolean = false,
        val code: Boolean = false,
    ) : MarkdownInline

    /** `[label](url)` link. [label] is the rendered text, [url] the target. */
    data class Link(val label: String, val url: String) : MarkdownInline

    /** `![alt](src)` inline image. */
    data class Image(val alt: String, val src: String) : MarkdownInline

    /** HA's `<ha-icon icon="mdi:foo">` custom element, mapped to a native glyph. */
    data class Icon(val slug: String) : MarkdownInline

    /** A hard line break (two trailing spaces, or a `<br>`). */
    data object LineBreak : MarkdownInline
}

/** Severity of a callout block, matching HA's ha-alert alertType. */
enum class MarkdownAlertType { INFO, SUCCESS, WARNING, ERROR }

/** A parsed block-level node. */
sealed interface MarkdownNode {
    /** `#`..`######` heading. [level] is 1..6. */
    data class Heading(val level: Int, val content: List<MarkdownInline>) : MarkdownNode

    /** A run of inline content terminated by a blank line. */
    data class Paragraph(val content: List<MarkdownInline>) : MarkdownNode

    /** A bullet (`-`/`*`/`+`) or ordered (`1.`) list. */
    data class ListBlock(val ordered: Boolean, val items: List<ListItem>) : MarkdownNode

    /** A single list item. [task] is non-null for a `- [ ]` / `- [x]` checkbox. */
    data class ListItem(
        val content: List<MarkdownInline>,
        val ordinal: Int?,
        val task: Boolean?,
    )

    /** A `> ` quote. */
    data class BlockQuote(val content: List<MarkdownInline>) : MarkdownNode

    /** HA / GitHub `[!NOTE]` style callout, mapped from a leading blockquote marker. */
    data class Alert(val type: MarkdownAlertType, val content: List<MarkdownInline>) : MarkdownNode

    /** A fenced (``` ```) or indented code block. [language] is the fence info string. */
    data class CodeBlock(val code: String, val language: String?) : MarkdownNode

    /** A GFM table: a header row + body rows, each a list of cell inline runs. */
    data class Table(
        val header: List<List<MarkdownInline>>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownNode

    /** A `---` / `***` / `___` thematic break. */
    data object HorizontalRule : MarkdownNode

    /** HA's `<ha-qr-code data="...">` element. Rendered as a labelled placeholder. */
    data class QrCode(val data: String) : MarkdownNode
}

/** GitHub-style alert markers, case-insensitive, mapped to ha-alert types. */
private val ALERT_RE =
    Regex("""^\[!(caution|important|note|tip|warning)]\s*""", RegexOption.IGNORE_CASE)

private fun alertTypeFor(marker: String): MarkdownAlertType = when (marker.lowercase()) {
    "caution" -> MarkdownAlertType.ERROR
    "important", "note" -> MarkdownAlertType.INFO
    "tip" -> MarkdownAlertType.SUCCESS
    "warning" -> MarkdownAlertType.WARNING
    else -> MarkdownAlertType.INFO
}

private val HR_RE = Regex("""^ {0,3}([-*_])( *\1){2,} *$""")
private val ATX_RE = Regex("""^ {0,3}(#{1,6})\s+(.*?)\s*#*\s*$""")
private val ORDERED_RE = Regex("""^ {0,3}(\d{1,9})[.)]\s+(.*)$""")
private val BULLET_RE = Regex("""^ {0,3}([-*+])\s+(.*)$""")
private val TASK_RE = Regex("""^\[([ xX])]\s+(.*)$""")
private val FENCE_RE = Regex("""^ {0,3}(`{3,}|~{3,})\s*([^`]*)$""")
private val HA_ALERT_OPEN = Regex("""<ha-alert\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HA_ALERT_TYPE = Regex("""alert-type\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
private val HA_QR = Regex("""<ha-qr-code\b[^>]*>""", RegexOption.IGNORE_CASE)
private val HA_QR_DATA = Regex("""data\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)

/**
 * Parse [source] into a flat list of block nodes. The parser is line-oriented:
 * it consumes the source one logical block at a time, dispatching on the first
 * line's shape. Unknown constructs degrade to paragraphs so no input is ever
 * dropped.
 */
fun parseMarkdown(source: String): List<MarkdownNode> {
    val lines = source.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val out = mutableListOf<MarkdownNode>()
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.isBlank() -> i++

            FENCE_RE.matches(line) -> {
                val m = FENCE_RE.matchEntire(line)!!
                val fence = m.groupValues[1]
                val lang = m.groupValues[2].trim().ifBlank { null }
                val body = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith(fence.take(3))) {
                    if (body.isNotEmpty()) body.append('\n')
                    body.append(lines[i])
                    i++
                }
                if (i < lines.size) i++ // consume the closing fence
                out += MarkdownNode.CodeBlock(body.toString(), lang)
            }

            HR_RE.matches(line) -> { out += MarkdownNode.HorizontalRule; i++ }

            ATX_RE.matches(line) -> {
                val m = ATX_RE.matchEntire(line)!!
                out += MarkdownNode.Heading(m.groupValues[1].length, parseInline(m.groupValues[2]))
                i++
            }

            HA_QR.containsMatchIn(line.trim()) -> {
                val data = HA_QR_DATA.find(line)?.groupValues?.get(1).orEmpty()
                out += MarkdownNode.QrCode(data)
                i++
            }

            line.trimStart().startsWith(">") -> {
                val (node, next) = parseBlockQuote(lines, i)
                out += node
                i = next
            }

            HA_ALERT_OPEN.containsMatchIn(line) -> {
                val (node, next) = parseHaAlert(lines, i)
                out += node
                i = next
            }

            isTableHeader(lines, i) -> {
                val (node, next) = parseTable(lines, i)
                out += node
                i = next
            }

            BULLET_RE.matches(line) || ORDERED_RE.matches(line) -> {
                val (node, next) = parseList(lines, i)
                out += node
                i = next
            }

            else -> {
                val (node, next) = parseParagraph(lines, i)
                out += node
                i = next
            }
        }
    }
    return out
}

/** A `> ` blockquote, joined across consecutive marker lines. A leading
 *  GitHub-alert marker promotes the whole quote to an [MarkdownNode.Alert]. */
private fun parseBlockQuote(lines: List<String>, start: Int): Pair<MarkdownNode, Int> {
    var i = start
    val collected = mutableListOf<String>()
    while (i < lines.size && lines[i].trimStart().startsWith(">")) {
        collected += lines[i].trimStart().removePrefix(">").removePrefix(" ")
        i++
    }
    val firstNonBlank = collected.firstOrNull { it.isNotBlank() }.orEmpty()
    val alert = ALERT_RE.find(firstNonBlank)
    if (alert != null) {
        // Drop the marker token from the first content line; the rest of the
        // quote becomes the alert body (HA strips the [!TYPE] token likewise).
        val body = collected.toMutableList()
        val idx = body.indexOfFirst { it.isNotBlank() }
        body[idx] = body[idx].removeRange(alert.range)
        val text = body.joinToString("\n").trim()
        return MarkdownNode.Alert(alertTypeFor(alert.groupValues[1]), parseInline(text)) to i
    }
    return MarkdownNode.BlockQuote(parseInline(collected.joinToString("\n").trim())) to i
}

/** An inline `<ha-alert alert-type="warning">body</ha-alert>` element spanning
 *  one or more lines. */
private fun parseHaAlert(lines: List<String>, start: Int): Pair<MarkdownNode, Int> {
    var i = start
    val buf = StringBuilder()
    while (i < lines.size) {
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(lines[i])
        i++
        if (buf.contains("</ha-alert>", ignoreCase = true)) break
    }
    val raw = buf.toString()
    val typeStr = HA_ALERT_TYPE.find(raw)?.groupValues?.get(1)?.lowercase()
    val type = when (typeStr) {
        "error" -> MarkdownAlertType.ERROR
        "warning" -> MarkdownAlertType.WARNING
        "success" -> MarkdownAlertType.SUCCESS
        else -> MarkdownAlertType.INFO
    }
    val inner = raw
        .replace(HA_ALERT_OPEN, "")
        .replace(Regex("""</ha-alert>""", RegexOption.IGNORE_CASE), "")
        .trim()
    return MarkdownNode.Alert(type, parseInline(inner)) to i
}

/** True when line [i] is a table header followed by a `|---|---|` delimiter row. */
private fun isTableHeader(lines: List<String>, i: Int): Boolean {
    if (i + 1 >= lines.size) return false
    if (!lines[i].contains('|')) return false
    val delim = lines[i + 1].trim()
    return delim.isNotEmpty() && delim.all { it == '|' || it == '-' || it == ':' || it == ' ' } &&
        delim.contains('-')
}

private fun parseTable(lines: List<String>, start: Int): Pair<MarkdownNode, Int> {
    val header = splitRow(lines[start]).map { parseInline(it) }
    var i = start + 2 // skip header + delimiter
    val rows = mutableListOf<List<List<MarkdownInline>>>()
    while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
        rows += splitRow(lines[i]).map { parseInline(it) }
        i++
    }
    return MarkdownNode.Table(header, rows) to i
}

/** Split a GFM table row into cells, stripping the optional outer pipes. */
private fun splitRow(line: String): List<String> {
    var s = line.trim()
    if (s.startsWith("|")) s = s.substring(1)
    if (s.endsWith("|")) s = s.substring(0, s.length - 1)
    // Split on un-escaped pipes.
    val cells = mutableListOf<String>()
    val cur = StringBuilder()
    var j = 0
    while (j < s.length) {
        val c = s[j]
        if (c == '\\' && j + 1 < s.length && s[j + 1] == '|') {
            cur.append('|'); j += 2; continue
        }
        if (c == '|') { cells += cur.toString().trim(); cur.clear(); j++; continue }
        cur.append(c); j++
    }
    cells += cur.toString().trim()
    return cells
}

private fun parseList(lines: List<String>, start: Int): Pair<MarkdownNode, Int> {
    var i = start
    val ordered = ORDERED_RE.matches(lines[start])
    val items = mutableListOf<MarkdownNode.ListItem>()
    while (i < lines.size) {
        val line = lines[i]
        val om = ORDERED_RE.matchEntire(line)
        val bm = BULLET_RE.matchEntire(line)
        if (ordered && om != null) {
            items += listItem(om.groupValues[2], om.groupValues[1].toIntOrNull())
            i++
        } else if (!ordered && bm != null) {
            items += listItem(bm.groupValues[2], null)
            i++
        } else if (line.isBlank()) {
            break
        } else if (line.startsWith("    ") || line.startsWith("\t")) {
            // A continuation line of the previous item: fold it in.
            if (items.isNotEmpty()) {
                val prev = items.removeAt(items.size - 1)
                val folded = (prev.content.joinToString("") { inlineText(it) } + " " + line.trim())
                items += prev.copy(content = parseInline(folded))
            }
            i++
        } else {
            break
        }
    }
    return MarkdownNode.ListBlock(ordered, items) to i
}

private fun listItem(rawContent: String, ordinal: Int?): MarkdownNode.ListItem {
    val task = TASK_RE.matchEntire(rawContent)
    return if (task != null) {
        MarkdownNode.ListItem(
            content = parseInline(task.groupValues[2]),
            ordinal = ordinal,
            task = task.groupValues[1].lowercase() == "x",
        )
    } else {
        MarkdownNode.ListItem(parseInline(rawContent), ordinal, null)
    }
}

private fun parseParagraph(lines: List<String>, start: Int): Pair<MarkdownNode, Int> {
    var i = start
    val buf = StringBuilder()
    while (i < lines.size && lines[i].isNotBlank() &&
        !ATX_RE.matches(lines[i]) && !HR_RE.matches(lines[i]) &&
        !lines[i].trimStart().startsWith(">") &&
        !BULLET_RE.matches(lines[i]) && !ORDERED_RE.matches(lines[i]) &&
        !FENCE_RE.matches(lines[i])
    ) {
        if (buf.isNotEmpty()) buf.append('\n')
        buf.append(lines[i])
        i++
    }
    return MarkdownNode.Paragraph(parseInline(buf.toString())) to i
}

/** Flatten an inline node back to its visible text (for list-item folding). */
private fun inlineText(node: MarkdownInline): String = when (node) {
    is MarkdownInline.Text -> node.text
    is MarkdownInline.Styled -> node.text
    is MarkdownInline.Link -> node.label
    is MarkdownInline.Image -> node.alt
    is MarkdownInline.Icon -> ""
    MarkdownInline.LineBreak -> "\n"
}

/**
 * Tokenise an inline run into [MarkdownInline] nodes. Single pass, left to
 * right; emphasis is depth-1 (no recursion into nested `**_x_**`). Markup that
 * does not pair off is emitted as literal text so malformed input never drops
 * characters.
 */
fun parseInline(text: String): List<MarkdownInline> {
    val out = mutableListOf<MarkdownInline>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) { out += MarkdownInline.Text(plain.toString()); plain.clear() }
    }
    var i = 0
    while (i < text.length) {
        // Hard line break: two trailing spaces before a newline, or an explicit
        // newline inside a folded paragraph.
        if (text[i] == '\n') {
            flush(); out += MarkdownInline.LineBreak; i++; continue
        }
        if (text.startsWith("<br", i, ignoreCase = true)) {
            val close = text.indexOf('>', i)
            if (close > 0) { flush(); out += MarkdownInline.LineBreak; i = close + 1; continue }
        }
        // HA inline icon element.
        if (text.startsWith("<ha-icon", i, ignoreCase = true)) {
            val close = text.indexOf('>', i)
            if (close > 0) {
                val tag = text.substring(i, close + 1)
                val slug = Regex("""icon\s*=\s*"([^"]*)"""", RegexOption.IGNORE_CASE)
                    .find(tag)?.groupValues?.get(1).orEmpty()
                flush(); out += MarkdownInline.Icon(slug); i = close + 1; continue
            }
        }
        // Image `![alt](src)` — checked before link since it shares `[`.
        if (text.startsWith("![", i)) {
            val parsed = parseLinkLike(text, i + 1)
            if (parsed != null) {
                flush(); out += MarkdownInline.Image(parsed.first, parsed.second); i = parsed.third; continue
            }
        }
        when {
            text.startsWith("**", i) || text.startsWith("__", i) -> {
                val token = text.substring(i, i + 2)
                val end = text.indexOf(token, i + 2)
                if (end > i + 1) {
                    flush(); out += MarkdownInline.Styled(text.substring(i + 2, end), bold = true)
                    i = end + 2
                } else { plain.append(text[i]); i++ }
            }
            text.startsWith("~~", i) -> {
                val end = text.indexOf("~~", i + 2)
                if (end > i + 1) {
                    flush(); out += MarkdownInline.Styled(text.substring(i + 2, end), strike = true)
                    i = end + 2
                } else { plain.append(text[i]); i++ }
            }
            text[i] == '*' || text[i] == '_' -> {
                val token = text[i]
                val end = text.indexOf(token, i + 1)
                if (end > i) {
                    flush(); out += MarkdownInline.Styled(text.substring(i + 1, end), italic = true)
                    i = end + 1
                } else { plain.append(text[i]); i++ }
            }
            text[i] == '`' -> {
                val end = text.indexOf('`', i + 1)
                if (end > i) {
                    flush(); out += MarkdownInline.Styled(text.substring(i + 1, end), code = true)
                    i = end + 1
                } else { plain.append(text[i]); i++ }
            }
            text[i] == '[' -> {
                val parsed = parseLinkLike(text, i)
                if (parsed != null) {
                    flush(); out += MarkdownInline.Link(parsed.first, parsed.second); i = parsed.third
                } else { plain.append(text[i]); i++ }
            }
            else -> { plain.append(text[i]); i++ }
        }
    }
    flush()
    return out
}

/** Parse `[label](url)` starting at the `[` (index [open]). Returns
 *  (label, url, indexAfterClosingParen) or null when malformed. */
private fun parseLinkLike(text: String, open: Int): Triple<String, String, Int>? {
    val labelEnd = text.indexOf(']', open + 1)
    if (labelEnd < 0 || labelEnd + 1 >= text.length || text[labelEnd + 1] != '(') return null
    val urlEnd = text.indexOf(')', labelEnd + 2)
    if (urlEnd < 0) return null
    val label = text.substring(open + 1, labelEnd)
    val url = text.substring(labelEnd + 2, urlEnd).trim()
    return Triple(label, url, urlEnd + 1)
}
